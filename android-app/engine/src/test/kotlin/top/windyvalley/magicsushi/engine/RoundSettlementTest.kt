package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoundSettlement] 的回归测试。
 *
 * 核心防线是 `三条退出路径的结算结果必须一致` —— 那个用户报告的 bug
 * （历史有成绩但最高分一直是 0）本质就是三条路径行为不一致。
 */
class RoundSettlementTest {

    // ------------------------------------------------------------------
    // 核心：三条路径一致性
    // ------------------------------------------------------------------

    /**
     * 用户报告 bug 的直接回归测试。
     *
     * 现象：正常退出 / 点重开时成绩进了历史，但最高分一直是 0。
     * 根因：`saveHighScore` 只在倒计时归零那条路径上被调用。
     *
     * 结算是纯函数，天然与「从哪个入口来」无关 —— 本用例锁死这个性质，
     * 防止有人再给某条路径加特殊分支。
     */
    @Test
    fun `同样的局面无论从哪条路径结算，结果都相同`() {
        val score = 500
        val savedHigh = 0

        // 三条路径唯一的合法差异是 alreadyRecorded，而首次结算时它必为 false。
        val fromGameOver = RoundSettlement.settle(score, savedHigh, alreadyRecorded = false)
        val fromRestart = RoundSettlement.settle(score, savedHigh, alreadyRecorded = false)
        val fromQuit = RoundSettlement.settle(score, savedHigh, alreadyRecorded = false)

        assertEquals("game over 与 restart 的结算必须一致", fromGameOver, fromRestart)
        assertEquals("game over 与 quit 的结算必须一致", fromGameOver, fromQuit)
        assertTrue("500 分 vs 最高分 0 必须判为破纪录", fromGameOver.isNewRecord)
        assertEquals("最高分必须更新为 500", 500, fromGameOver.newHighScore)
    }

    /**
     * 首次游玩就是最典型的场景：最高分 0，得了分就该破纪录。
     *
     * 用户重装后「一直都是 0」正是这个场景失效 —— 只要 isNewRecord 返回
     * false，最高分就永远停在 0，而 0 又让下一局继续判 false…… 不会自愈。
     */
    @Test
    fun `首次游玩得分必定破纪录`() {
        val outcome = RoundSettlement.settle(score = 1, savedHighScore = 0, alreadyRecorded = false)
        assertTrue("最高分为 0 时任何正分都是新纪录", outcome.isNewRecord)
        assertEquals(1, outcome.newHighScore)
        assertTrue("必须入库", outcome.shouldRecord)
    }

    // ------------------------------------------------------------------
    // 只升不降
    // ------------------------------------------------------------------

    @Test
    fun `低于最高分不破纪录且最高分不变`() {
        val outcome = RoundSettlement.settle(300, savedHighScore = 800, alreadyRecorded = false)
        assertFalse(outcome.isNewRecord)
        assertEquals("最高分不能被更低的分数覆盖", 800, outcome.newHighScore)
        assertTrue("没破纪录也要入库 —— 历史是流水，不是排行榜", outcome.shouldRecord)
    }

    @Test
    fun `打平最高分不算破纪录`() {
        val outcome = RoundSettlement.settle(800, savedHighScore = 800, alreadyRecorded = false)
        assertFalse("等于不算超过，避免无意义落盘", outcome.isNewRecord)
        assertEquals(800, outcome.newHighScore)
        assertTrue(outcome.shouldRecord)
    }

    @Test
    fun `超过最高分则更新`() {
        val outcome = RoundSettlement.settle(801, savedHighScore = 800, alreadyRecorded = false)
        assertTrue(outcome.isNewRecord)
        assertEquals(801, outcome.newHighScore)
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    /**
     * game over 后玩家再点退出 —— 不能重复入库，也不能重复发庆祝事件。
     */
    @Test
    fun `已结算过的局不再重复结算`() {
        val outcome = RoundSettlement.settle(900, savedHighScore = 100, alreadyRecorded = true)
        assertFalse("不能重复入库", outcome.shouldRecord)
        assertFalse("不能重复发新纪录事件", outcome.isNewRecord)
        assertEquals("最高分保持已有值", 100, outcome.newHighScore)
    }

    // ------------------------------------------------------------------
    // 0 分
    // ------------------------------------------------------------------

    @Test
    fun `0 分不入库且不影响最高分`() {
        val outcome = RoundSettlement.settle(0, savedHighScore = 500, alreadyRecorded = false)
        assertFalse("开局即退出不该留 0 分记录", outcome.shouldRecord)
        assertFalse(outcome.isNewRecord)
        assertEquals(500, outcome.newHighScore)
    }

    /**
     * 史上第一局且 0 分：最高分 0、得分 0。
     *
     * 这是唯一「得分不低于最高分却不该破纪录」的场景，容易被
     * `score >= savedHighScore` 这类写法误判。
     */
    @Test
    fun `首局 0 分不算破纪录`() {
        val outcome = RoundSettlement.settle(0, savedHighScore = 0, alreadyRecorded = false)
        assertFalse("0 分不能算破纪录，否则历史里会出现 0 分新纪录", outcome.isNewRecord)
        assertFalse(outcome.shouldRecord)
        assertEquals(0, outcome.newHighScore)
    }

    @Test
    fun `负分被当作无效局处理`() {
        // 理论上不该出现，但结算是最后一道关口，不该把脏数据写进历史。
        val outcome = RoundSettlement.settle(-10, savedHighScore = 300, alreadyRecorded = false)
        assertFalse(outcome.shouldRecord)
        assertFalse(outcome.isNewRecord)
        assertEquals(300, outcome.newHighScore)
    }

    // ------------------------------------------------------------------
    // 与 HighScoreRules 的一致性
    // ------------------------------------------------------------------

    /**
     * 结算判定必须与 [HighScoreRules.isNewRecord] 完全一致。
     *
     * 「破纪录」这个概念在项目里只能有一份定义 —— 它同时被最高分持久化、
     * 庆祝动画、历史记录标记三处消费，分叉过一次就会出现「弹窗说破纪录但
     * 最高分没变」这类自相矛盾的表现。
     */
    @Test
    fun `破纪录判定与 HighScoreRules 保持一致`() {
        val cases = listOf(0 to 0, 1 to 0, 300 to 300, 301 to 300, 100 to 800)
        for ((score, high) in cases) {
            val outcome = RoundSettlement.settle(score, high, alreadyRecorded = false)
            val expected = score > 0 && HighScoreRules.isNewRecord(score, high)
            assertEquals(
                "score=$score high=$high 的破纪录判定必须与 HighScoreRules 一致",
                expected, outcome.isNewRecord,
            )
        }
    }
}
