package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [TimerEngine].
 *
 * Covers:
 *  - INITIAL_SECONDS == 60
 *  - initialState() == 60
 *  - tick decrements and floors at 0
 *  - resetOnMatch 重置到 60（与 match 数量无关，高于 60 也拉回），空列表原样返回
 *  - isGameOver checks zero / negative
 */
class TimerEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = 0, type = type, row = row, col = col, isSelected = false, isLocked = false)

    @Test
    fun `INITIAL_SECONDS is 60`() {
        assertEquals(60, TimerEngine.INITIAL_SECONDS)
    }

    @Test
    fun `initialState returns 60`() {
        assertEquals(60, TimerEngine.initialState())
    }

    @Test
    fun `tick decrements and floors at 0`() {
        assertEquals(59, TimerEngine.tick(60))
        assertEquals(1, TimerEngine.tick(2))
        assertEquals(0, TimerEngine.tick(1))
        assertEquals(0, TimerEngine.tick(0))
    }

    @Test
    fun `resetOnMatch 消除后重置为 60 秒`() {
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        assertEquals(60, TimerEngine.resetOnMatch(50, listOf(match)))
    }

    @Test
    fun `resetOnMatch 剩余时间高于 60 时同样重置回 60`() {
        // 旧的加时累加机制可能让 remaining 超过 60（历史 cap 是 90）。
        // 重置语义不区分方向：无论高于还是低于，一律回到 60。
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        assertEquals(60, TimerEngine.resetOnMatch(88, listOf(match)))
        assertEquals(60, TimerEngine.resetOnMatch(90, listOf(match)))
    }

    @Test
    fun `resetOnMatch 多个 match 也只重置到 60 不累加`() {
        // 关键回归点：奖励语义下 N 个 match 会加 N×5 秒。
        // 重置语义下 match 数量不影响结果 —— 这正是废弃奖励机制的意义。
        val matches = (0..2).map {
            Match(
                tiles = (0..2).map { c -> tile(it, c, SushiType.SUSHI1) },
                axis = MatchAxis.HORIZONTAL,
                length = 3,
            )
        }
        assertEquals(60, TimerEngine.resetOnMatch(50, matches))
    }

    @Test
    fun `resetOnMatch 无消除时原样返回 (FR-6_9)`() {
        assertEquals(50, TimerEngine.resetOnMatch(50, emptyList()))
        assertEquals(0, TimerEngine.resetOnMatch(0, emptyList()))
    }

    @Test
    fun `isGameOver returns true at zero and false otherwise`() {
        assertTrue("isGameOver(0) should be true", TimerEngine.isGameOver(0))
        assertFalse("isGameOver(1) should be false", TimerEngine.isGameOver(1))
        assertFalse("isGameOver(60) should be false", TimerEngine.isGameOver(60))
        assertFalse("isGameOver(90) should be false", TimerEngine.isGameOver(90))
    }

    @Test
    fun `isGameOver is true for negative (defensive)`() {
        assertTrue("isGameOver(-1) should be true (defensive)", TimerEngine.isGameOver(-1))
    }

    // ========================================================================
    // shouldTick — 消除结算窗口内冻结倒计时
    // ========================================================================

    @Test
    fun `shouldTick is true only while playing and not settling`() {
        assertTrue(
            "PLAYING 且未结算时应该走表",
            TimerEngine.shouldTick(phaseIsPlaying = true, settling = false),
        )
    }

    @Test
    fun `shouldTick is false during settling window`() {
        // 这是本判据存在的理由：消除已判定、结算动画在播，倒计时必须冻结。
        // 否则剩 1 秒时消除会在动画期间归零判负，奖励的 60 秒永远等不到。
        assertFalse(
            "结算窗口内不得走表",
            TimerEngine.shouldTick(phaseIsPlaying = true, settling = true),
        )
    }

    @Test
    fun `shouldTick is false when not playing`() {
        assertFalse(
            "非 PLAYING（暂停/结束/IDLE）不得走表",
            TimerEngine.shouldTick(phaseIsPlaying = false, settling = false),
        )
    }

    @Test
    fun `shouldTick is false when both not playing and settling`() {
        // 两个条件同时不满足时也必须是 false —— 防止将来改成 or 之类的手滑。
        assertFalse(
            "既不在 PLAYING 又在结算，仍然不得走表",
            TimerEngine.shouldTick(phaseIsPlaying = false, settling = true),
        )
    }

    @Test
    fun `shouldTick truth table is exhaustive`() {
        // 穷尽四种组合，锁死语义：只有 (playing=true, settling=false) 走表。
        val expected = mapOf(
            (true to false) to true,
            (true to true) to false,
            (false to false) to false,
            (false to true) to false,
        )
        for ((input, want) in expected) {
            val (playing, settling) = input
            assertEquals(
                "shouldTick(playing=$playing, settling=$settling)",
                want,
                TimerEngine.shouldTick(phaseIsPlaying = playing, settling = settling),
            )
        }
    }
}