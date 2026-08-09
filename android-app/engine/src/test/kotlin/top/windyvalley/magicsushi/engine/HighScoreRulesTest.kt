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
        assertTrue(HighScoreRules.isNewRecord(newScore = 100, savedHighScore = 50))
    }

    @Test
    fun isNewRecord_lowerScoreRejected() {
        assertFalse(HighScoreRules.isNewRecord(newScore = 30, savedHighScore = 50))
    }

    /**
     * 平分不算破纪录 —— 避免无意义落盘。
     * 这条是边界，容易在重构时被写成 `>=`。
     */
    @Test
    fun isNewRecord_equalScoreIsNotNewRecord() {
        assertFalse(HighScoreRules.isNewRecord(newScore = 50, savedHighScore = 50))
    }

    @Test
    fun isNewRecord_firstEverScoreOnFreshInstall() {
        // 全新安装：最高分 0，得 1 分也算破纪录
        assertTrue(HighScoreRules.isNewRecord(newScore = 1, savedHighScore = 0))
    }

    @Test
    fun isNewRecord_zeroScoreOnFreshInstallIsNot() {
        // 0 分不该被记成纪录
        assertFalse(HighScoreRules.isNewRecord(newScore = 0, savedHighScore = 0))
    }

    // ========================================================================
    // shouldCelebrateHighScore —— 迁移回归防线
    // ========================================================================

    /**
     * **迁移回归防线**：冷启动时 DataStore 异步把最高分从占位 0 填成 500。
     *
     * 旧判据 `newHigh > previousHigh` 在这里为 true，会导致玩家一进游戏
     * 就看到一次莫名的庆祝动画。真实区分点是「本局还没得分」。
     */
    @Test
    fun shouldCelebrate_asyncLoadDoesNotCelebrate() {
        assertFalse(
            "冷启动异步加载最高分不应触发庆祝",
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 0,
                newHigh = 500,
                currentScore = 0,
            )
        )
    }

    /**
     * 与上一条对照：同样是 `0 → 300` 的跳变，但本局得了 300 分，
     * 说明这是史上第一局且真的破了纪录，应该庆祝。
     *
     * 这两条必须成对存在 —— 只有前一条时，把实现写成 `always false`
     * 也能通过。
     */
    @Test
    fun shouldCelebrate_firstEverRecordCelebrates() {
        assertTrue(
            "史上第一局破纪录应该庆祝",
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 0,
                newHigh = 300,
                currentScore = 300,
            )
        )
    }

    @Test
    fun shouldCelebrate_normalNewRecordCelebrates() {
        assertTrue(
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 500,
                newHigh = 800,
                currentScore = 800,
            )
        )
    }

    @Test
    fun shouldCelebrate_noChangeDoesNotCelebrate() {
        assertFalse(
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 500,
                newHigh = 500,
                currentScore = 200,
            )
        )
    }

    /**
     * 最高分不可能下降，但万一状态错乱（比如清数据后残留 UI 状态），
     * 也不该庆祝。
     */
    @Test
    fun shouldCelebrate_decreaseDoesNotCelebrate() {
        assertFalse(
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 800,
                newHigh = 500,
                currentScore = 500,
            )
        )
    }

    /**
     * 边界：本局得 1 分就破了纪录（最高分原本是 0）。
     * `currentScore > 0` 的下边界，确认不是写成了 `>= 某个阈值`。
     */
    @Test
    fun shouldCelebrate_oneStepAboveZeroIsEnough() {
        assertTrue(
            HighScoreRules.shouldCelebrateHighScore(
                previousHigh = 0,
                newHigh = 1,
                currentScore = 1,
            )
        )
    }
}
