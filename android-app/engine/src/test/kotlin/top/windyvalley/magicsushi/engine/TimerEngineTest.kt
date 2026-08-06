package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [TimerEngine].
 *
 * Covers:
 *  - INITIAL_SECONDS / REWARD_SECONDS / MAX_SECONDS constants
 *  - initialState() == 60
 *  - tick decrements and floors at 0
 *  - rewardOnMatch adds 5s, caps at MAX_SECONDS, no-op on empty
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
    fun `REWARD_SECONDS is 5`() {
        assertEquals(5, TimerEngine.REWARD_SECONDS)
    }

    @Test
    fun `MAX_SECONDS is 90`() {
        assertEquals(90, TimerEngine.MAX_SECONDS)
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
    fun `rewardOnMatch resets to 60 and returns actual seconds added`() {
        // v1.0.3: every elimination resets to 60s. If timer was below 60,
        // the reward is the top-up amount (60 - remaining).
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        val (newRemaining, reward) = TimerEngine.rewardOnMatch(50, listOf(match))
        assertEquals(60, newRemaining)
        assertEquals(10, reward)  // topped up from 50 → 60
    }

    @Test
    fun `rewardOnMatch at or above 60 returns 0 reward`() {
        // v1.0.3: timer resets to 60, but if already at/above 60, no time is added.
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        // 88 >= 60 → reset to 60, reward = max(0, 60 - 88) = 0
        val (newRemaining, reward) = TimerEngine.rewardOnMatch(88, listOf(match))
        assertEquals(60, newRemaining)
        assertEquals(0, reward)
    }

    @Test
    fun `rewardOnMatch above 60 also returns 0 reward`() {
        // 90 >= 60 → reset to 60, reward = max(0, 60 - 90) = 0
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        val (newRemaining, reward) = TimerEngine.rewardOnMatch(90, listOf(match))
        assertEquals(60, newRemaining)
        assertEquals(0, reward)
    }

    @Test
    fun `rewardOnMatch with no matches is no-op (FR-6_9)`() {
        val (newRemaining, reward) = TimerEngine.rewardOnMatch(50, emptyList())
        assertEquals(50, newRemaining)
        assertEquals(0, reward)
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
}