package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HighScoreDerivation] 及其**成立前提**的测试。
 *
 * 这里有两类用例，第二类比第一类重要：
 *
 *  1. 派生函数本身的行为（空列表、乱序、负分…）
 *  2. **不变式守卫** —— 派生正确的前提是 RoundSettlement 和 GameHistory
 *     的两条性质。若将来有人改动它们，这些用例会红，提醒他最高分会算错。
 *
 * 第 2 类是这个测试文件存在的主要理由：派生本身只有一行 maxOfOrNull，
 * 不太可能写错；真正的风险是**别处的改动悄悄拆掉了它的地基**。
 */
class HighScoreDerivationTest {

    private fun record(score: Int, ts: Long = 0L) =
        GameRecord(score = score, timestampMillis = ts, isNewRecord = false)

    // ========================================================================
    // 1. 派生函数本身
    // ========================================================================

    @Test
    fun `空历史的最高分是 0`() {
        // 「清空记录」之后就是这个状态 —— 不需要额外重置最高分。
        assertEquals(0, HighScoreDerivation.highScoreOf(emptyList()))
    }

    @Test
    fun `取的是最大值而非第一条`() {
        // 刻意传乱序：本函数不能依赖调用方先 normalize，
        // 否则又多一个「记得做」的隐式契约。
        val records = listOf(record(120), record(880), record(300))
        assertEquals(880, HighScoreDerivation.highScoreOf(records))
    }

    @Test
    fun `单条记录时最高分就是它`() {
        assertEquals(450, HighScoreDerivation.highScoreOf(listOf(record(450))))
    }

    @Test
    fun `全是 0 分的历史最高分为 0`() {
        // 实际上 0 分不会入库（见下方不变式测试），但派生函数本身要能扛住。
        assertEquals(0, HighScoreDerivation.highScoreOf(listOf(record(0), record(0))))
    }

    @Test
    fun `isNewRecord 严格大于 —— 平纪录不算`() {
        val records = listOf(record(500))
        assertTrue(HighScoreDerivation.isNewRecord(501, records))
        assertFalse(HighScoreDerivation.isNewRecord(500, records))
        assertFalse(HighScoreDerivation.isNewRecord(499, records))
    }

    @Test
    fun `0 分和负分永远不算新纪录`() {
        // 与 RoundSettlement 的 score <= 0 分支保持一致。
        assertFalse(HighScoreDerivation.isNewRecord(0, emptyList()))
        assertFalse(HighScoreDerivation.isNewRecord(-10, emptyList()))
    }

    @Test
    fun `空历史时任何正分都是新纪录`() {
        assertTrue(HighScoreDerivation.isNewRecord(1, emptyList()))
    }

    // ========================================================================
    // 2. 不变式守卫 —— 派生成立的前提
    // ========================================================================

    @Test
    fun `不变式 A - 破纪录必然入库`() {
        // 这是派生正确的核心前提：若存在「isNewRecord=true 但 shouldRecord=false」
        // 的组合，那个成绩就不在历史里，max 会漏掉它 —— 最高分算小了。
        //
        // 遍历各种分数与基准的组合来确认这个蕴含关系恒成立。
        val scores = listOf(-5, 0, 1, 50, 100, 999)
        val bases = listOf(0, 1, 50, 100, 999)

        for (score in scores) {
            for (base in bases) {
                val outcome = RoundSettlement.settle(
                    score = score,
                    savedHighScore = base,
                    alreadyRecorded = false,
                )
                if (outcome.isNewRecord) {
                    assertTrue(
                        "score=$score base=$base 破了纪录却不入库 —— " +
                            "派生最高分会漏掉这一局",
                        outcome.shouldRecord,
                    )
                }
            }
        }
    }

    @Test
    fun `不变式 B - 历史按分数降序裁剪，最高分不会被淘汰`() {
        // 若改成按时间裁剪（新的挤掉旧的），高分局会被淘汰，
        // 派生出的最高分就会随之**变小** —— 玩家的纪录莫名下降。
        val many = (1..GameHistory.MAX_RECORDS).map { record(it * 10, ts = it.toLong()) }
        val best = record(99999, ts = 0L)  // 时间戳最老，若按时间裁剪必被淘汰

        val result = GameHistory.insert(many, best)

        assertEquals(
            "最高分被裁掉了 —— 历史的裁剪规则可能从分数降序改成了时间降序",
            99999,
            HighScoreDerivation.highScoreOf(result),
        )
    }

    @Test
    fun `不变式 B2 - 满库后低分局被丢弃，不影响最高分`() {
        val many = (1..GameHistory.MAX_RECORDS).map { record(it * 10, ts = it.toLong()) }
        val expectedHigh = HighScoreDerivation.highScoreOf(many)

        // 一条排不进前 N 名的低分局。
        val result = GameHistory.insert(many, record(1, ts = 999L))

        assertEquals(
            "低分局的插入改变了最高分",
            expectedHigh,
            HighScoreDerivation.highScoreOf(result),
        )
    }

    @Test
    fun `派生与 RoundSettlement 对新纪录的判断一致`() {
        // 两处都在判断「是否破纪录」，语义必须相同，否则会出现
        // 「结算说破了，派生说没破」这种自相矛盾。
        val records = listOf(record(300), record(700), record(120))
        val derivedHigh = HighScoreDerivation.highScoreOf(records)

        for (score in listOf(-1, 0, 1, 699, 700, 701, 5000)) {
            val settled = RoundSettlement.settle(
                score = score,
                savedHighScore = derivedHigh,
                alreadyRecorded = false,
            )
            assertEquals(
                "score=$score 两处对是否破纪录的判断不一致",
                settled.isNewRecord,
                HighScoreDerivation.isNewRecord(score, records),
            )
        }
    }
}
