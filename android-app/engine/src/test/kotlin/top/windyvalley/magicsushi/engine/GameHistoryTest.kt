package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameHistory] 裁剪规则测试。
 *
 * ## 为什么这些测试值得写细
 *
 * 裁剪语义是「保留**分数最高**的 50 条」，而不是常见的「保留最近 50 条」。
 * 两者在未满 50 条时行为完全一致，只有满了之后才分叉 —— 也就是说，
 * 如果只测「插入几条看看」，两种实现都能通过。必须专门构造满库场景。
 *
 * 用户明确说过：满 50 条后低分局**不记录**（而非挤掉旧的）。
 */
class GameHistoryTest {

    private fun rec(score: Int, ts: Long, newRecord: Boolean = false) =
        GameRecord(score = score, timestampMillis = ts, isNewRecord = newRecord)

    // ========================================================================
    // 基本插入
    // ========================================================================

    @Test
    fun `空列表插入第一条`() {
        val result = GameHistory.insert(emptyList(), rec(100, 1000))
        assertEquals(1, result.size)
        assertEquals(100, result[0].score)
    }

    @Test
    fun `未满上限时按分数降序插入`() {
        var list = emptyList<GameRecord>()
        list = GameHistory.insert(list, rec(100, 1000))
        list = GameHistory.insert(list, rec(300, 2000))
        list = GameHistory.insert(list, rec(200, 3000))

        assertEquals(listOf(300, 200, 100), list.map { it.score })
    }

    @Test
    fun `插入不修改原列表（纯函数）`() {
        val original = listOf(rec(100, 1000))
        GameHistory.insert(original, rec(999, 2000))
        assertEquals("原列表不应被修改", 1, original.size)
        assertEquals(100, original[0].score)
    }

    // ========================================================================
    // 同分排序
    // ========================================================================

    @Test
    fun `同分时新记录排在前面`() {
        val old = rec(500, ts = 1_000)
        val new = rec(500, ts = 9_000)
        val result = GameHistory.insert(listOf(old), new)

        assertEquals(2, result.size)
        assertEquals("同分时时间戳大的（新的）在前", 9_000L, result[0].timestampMillis)
        assertEquals(1_000L, result[1].timestampMillis)
    }

    // ========================================================================
    // 满库裁剪 —— 核心语义
    // ========================================================================

    /** 造一个正好 50 条、分数 100..5000 的满库（降序）。 */
    private fun fullHistory(): List<GameRecord> =
        (1..GameHistory.MAX_RECORDS).map { i ->
            rec(score = i * 100, ts = i.toLong() * 1000)
        }.sortedByDescending { it.score }

    @Test
    fun `满库时高分新记录挤掉最低分`() {
        val full = fullHistory()
        assertEquals(GameHistory.MAX_RECORDS, full.size)
        val lowest = full.last().score      // 100

        val result = GameHistory.insert(full, rec(9999, 99_000))

        assertEquals("条数不应超过上限", GameHistory.MAX_RECORDS, result.size)
        assertEquals("新高分应排第一", 9999, result[0].score)
        assertFalse(
            "原最低分应被挤出",
            result.any { it.score == lowest },
        )
    }

    @Test
    fun `满库时低分新记录根本不被保存`() {
        val full = fullHistory()
        val lowest = full.last().score      // 100

        // 比最低分还低 → 不应进榜
        val result = GameHistory.insert(full, rec(lowest - 1, 99_000))

        assertEquals(GameHistory.MAX_RECORDS, result.size)
        assertFalse(
            "低于第 50 名的记录不应被保存（而非挤掉旧记录）",
            result.any { it.timestampMillis == 99_000L },
        )
        assertEquals(
            "列表内容应与插入前一致",
            full.map { it.score },
            result.map { it.score },
        )
    }

    @Test
    fun `满库时与最低分同分的新记录应入榜（同分新的优先）`() {
        val full = fullHistory()
        val lowest = full.last()

        val result = GameHistory.insert(full, rec(lowest.score, ts = 99_000))

        assertEquals(GameHistory.MAX_RECORDS, result.size)
        assertTrue(
            "同分且更新 → 应挤掉那条更旧的同分记录",
            result.any { it.timestampMillis == 99_000L },
        )
        assertFalse(
            "更旧的那条同分记录应被挤出",
            result.any { it.timestampMillis == lowest.timestampMillis },
        )
    }

    @Test
    fun `正好第 50 名边界：新记录恰好排在最后一位`() {
        // 造 49 条高分 + 1 个空位
        val almost = (2..GameHistory.MAX_RECORDS).map { i ->
            rec(score = i * 100, ts = i.toLong() * 1000)
        }
        assertEquals(GameHistory.MAX_RECORDS - 1, almost.size)

        // 插一条比所有人都低的 —— 未满库，应该进得去
        val result = GameHistory.insert(almost, rec(1, 99_000))

        assertEquals(GameHistory.MAX_RECORDS, result.size)
        assertEquals("最低分应排在最后一位", 1, result.last().score)
    }

    @Test
    fun `连续插入超过上限只保留最高的 50 条`() {
        var list = emptyList<GameRecord>()
        // 插 200 条，分数 1..200
        for (i in 1..200) {
            list = GameHistory.insert(list, rec(score = i, ts = i.toLong()))
        }

        assertEquals(GameHistory.MAX_RECORDS, list.size)
        assertEquals("应保留最高分 200", 200, list.first().score)
        assertEquals(
            "第 50 名应是 151（200 down to 151）",
            151,
            list.last().score,
        )
    }

    // ========================================================================
    // isNewRecord 字段
    // ========================================================================

    @Test
    fun `isNewRecord 标记被原样保留`() {
        val result = GameHistory.insert(emptyList(), rec(500, 1000, newRecord = true))
        assertTrue("破纪录标记应被保留", result[0].isNewRecord)
    }

    // ========================================================================
    // normalize
    // ========================================================================

    @Test
    fun `normalize 对乱序输入重排`() {
        val messy = listOf(rec(100, 1000), rec(500, 2000), rec(300, 3000))
        val result = GameHistory.normalize(messy)
        assertEquals(listOf(500, 300, 100), result.map { it.score })
    }

    @Test
    fun `normalize 裁剪超量输入`() {
        // 模拟存储层因历史原因存了 80 条
        val tooMany = (1..80).map { rec(score = it, ts = it.toLong()) }
        val result = GameHistory.normalize(tooMany)
        assertEquals(GameHistory.MAX_RECORDS, result.size)
        assertEquals(80, result.first().score)
    }

    @Test
    fun `normalize 空列表返回空列表`() {
        assertTrue(GameHistory.normalize(emptyList()).isEmpty())
    }
}
