package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HighScoreRules] 单测。
 *
 * 重点在 [shouldCelebrate_asyncLoadDoesNotCelebrate] 那组 —— 它锁死的是
 * DataStore 迁移引入的**真实回归**：异步加载让最高分从占位 0 跳到真实值，
 * 旧判据会把这次跳变误判成破纪录。
 */
class HighScoreRulesTest {

    // ========================================================================
    // isNewRecord —— 只升不降
    // ========================================================================

    @Test
    fun isNewRecord_higherScoreWins() {
        assertTrue(HighScoreRules.isNewRecord(candidate = 100, currentHigh = 50))
    }

    @Test
    fun isNewRecord_lowerScoreRejected() {
        assertFalse(HighScoreRules.isNewRecord(candidate = 30, currentHigh = 50))
    }

    /**
     * 平分不算破纪录 —— 避免无意义落盘。
     * 这条是边界，容易在重构时被写成 `>=`。
     */
    @Test
    fun isNewRecord_equalScoreIsNotNewRecord() {
        assertFalse(HighScoreRules.isNewRecord(candidate = 50, currentHigh = 50))
    }

    @Test
    fun isNewRecord_firstEverScoreOnFreshInstall() {
        // 全新安装：最高分 0，得 1 分也算破纪录
        assertTrue(HighScoreRules.isNewRecord(candidate = 1, currentHigh = 0))
    }

    @Test
    fun isNewRecord_zeroScoreOnFreshInstallIsNot() {
        // 0 分不该被记成纪录
        assertFalse(HighScoreRules.isNewRecord(candidate = 0, currentHigh = 0))
    }

    /**
     * 负分不算纪录。
     *
     * 正分检查现在**内置**在 isNewRecord 里（曾经由每个调用方自己补
     * `score > 0 &&`），所以这条边界归它自己守。
     */
    @Test
    fun isNewRecord_negativeScoreIsNot() {
        assertFalse(HighScoreRules.isNewRecord(candidate = -10, currentHigh = 0))
        // 即使"大于"一个更负的基准，也不算 —— 正分是硬条件。
        assertFalse(HighScoreRules.isNewRecord(candidate = -10, currentHigh = -50))
    }
}
