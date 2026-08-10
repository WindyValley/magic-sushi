package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoundExitOptions] 的边界覆盖。
 *
 * 重点是四个否决条件各自**单独**成立时都必须否决 —— 这类「与」逻辑最容易
 * 在后续重构时漏掉一项，所以每项各有一个只破坏它的用例。
 */
class RoundExitOptionsTest {

    // ── 可以保留的正常情况 ────────────────────────────────────────────

    @Test
    fun `进行中的有分对局可以保留`() {
        assertTrue(
            RoundExitOptions.canKeepProgress(
                score = 800,
                remainingSeconds = 40,
                boardHasTiles = true,
                alreadyRecorded = false,
            )
        )
    }

    @Test
    fun `一分也算有进度`() {
        // 边界：score > 0 而非 score >= 某个阈值。只要玩家有实际战果就该
        // 让他决定去留，不替他判断「才 1 分不值得」。
        assertTrue(
            RoundExitOptions.canKeepProgress(
                score = 1,
                remainingSeconds = 1,
                boardHasTiles = true,
                alreadyRecorded = false,
            )
        )
    }

    // ── 四个否决条件，各自单独破坏 ────────────────────────────────────

    @Test
    fun `零分不给保留 —— 快照里只有一个随时能重开的初始棋盘`() {
        assertFalse(
            RoundExitOptions.canKeepProgress(
                score = 0,
                remainingSeconds = 55,
                boardHasTiles = true,
                alreadyRecorded = false,
            )
        )
    }

    @Test
    fun `负分不给保留`() {
        // 分数理论上不会为负，但判据写成 score > 0 而不是 score != 0，
        // 这条用例把该意图固定下来。
        assertFalse(
            RoundExitOptions.canKeepProgress(
                score = -10,
                remainingSeconds = 55,
                boardHasTiles = true,
                alreadyRecorded = false,
            )
        )
    }

    @Test
    fun `时间耗尽不给保留 —— 恢复出来立刻game over`() {
        assertFalse(
            RoundExitOptions.canKeepProgress(
                score = 800,
                remainingSeconds = 0,
                boardHasTiles = true,
                alreadyRecorded = false,
            )
        )
    }

    @Test
    fun `空棋盘不给保留`() {
        assertFalse(
            RoundExitOptions.canKeepProgress(
                score = 800,
                remainingSeconds = 40,
                boardHasTiles = false,
                alreadyRecorded = false,
            )
        )
    }

    @Test
    fun `已结算的局不给保留 —— 恢复会让玩家白玩一局`() {
        // 幂等保护会让恢复后的这局不再入库，所以保留它是个陷阱。
        assertFalse(
            RoundExitOptions.canKeepProgress(
                score = 800,
                remainingSeconds = 40,
                boardHasTiles = true,
                alreadyRecorded = true,
            )
        )
    }

    // ── 与 GameSnapshot.isRestorable 的一致性 ─────────────────────────

    @Test
    fun `放行的条件下快照必然是可恢复的`() {
        // 这是本类最重要的一条：若这里放行、存盘时却被 isRestorable 挡掉，
        // 玩家会遇到「点了保留，菜单却没有继续上局」。两处判据必须同向。
        val tile = SushiTile(id = 1, type = SushiType.entries.first(), row = 0, col = 0)
        val size = 7
        val grid = List(size) { r ->
            List<SushiTile?>(size) { c ->
                if (r == 0 && c == 0) tile else null
            }
        }
        val snapshot = GameSnapshot(
            board = Board(size = size, grid = grid),
            score = 500,
            combo = 1,
            remainingSeconds = 30,
        )

        val boardHasTiles = snapshot.board.grid.flatten().any { it != null }
        assertTrue(
            RoundExitOptions.canKeepProgress(
                score = snapshot.score,
                remainingSeconds = snapshot.remainingSeconds,
                boardHasTiles = boardHasTiles,
                alreadyRecorded = false,
            )
        )
        assertTrue(snapshot.isRestorable)
    }
}
