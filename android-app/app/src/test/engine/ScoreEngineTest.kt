package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [ScoreEngine].
 *
 * Covers:
 *  - BASE_POINTS_PER_TILE constant
 *  - lengthBonus values (3, 4, 5, 6+, defensive <3)
 *  - comboMultiplier values (1, 2, 3, 4+, defensive <1)
 *  - scoreForMatch with various lengths and combos
 *  - scoreForMatches with batch
 *  - totalTilesMatched
 *  - empty match list returns 0
 */
class ScoreEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = 0, type = type, row = row, col = col, isSelected = false, isLocked = false)

    @Test
    fun `BASE_POINTS_PER_TILE is 10`() {
        assertEquals(10, ScoreEngine.BASE_POINTS_PER_TILE)
    }

    @Test
    fun `lengthBonus values`() {
        assertEquals(1.0, ScoreEngine.lengthBonus(3), 0.001)
        assertEquals(1.5, ScoreEngine.lengthBonus(4), 0.001)
        assertEquals(2.0, ScoreEngine.lengthBonus(5), 0.001)
        assertEquals(3.0, ScoreEngine.lengthBonus(6), 0.001)
        assertEquals(3.0, ScoreEngine.lengthBonus(7), 0.001)
        // Defensive: length < 3 → 1.0 (shouldn't occur by MatchEngine invariant)
        assertEquals(1.0, ScoreEngine.lengthBonus(2), 0.001)
    }

    @Test
    fun `comboMultiplier values`() {
        assertEquals(1.0, ScoreEngine.comboMultiplier(1), 0.001)
        assertEquals(1.5, ScoreEngine.comboMultiplier(2), 0.001)
        assertEquals(2.0, ScoreEngine.comboMultiplier(3), 0.001)
        assertEquals(2.5, ScoreEngine.comboMultiplier(4), 0.001)
        // Saturating at 4+
        assertEquals(2.5, ScoreEngine.comboMultiplier(5), 0.001)
        // Defensive: combo < 1 → 1.0
        assertEquals(1.0, ScoreEngine.comboMultiplier(0), 0.001)
    }

    @Test
    fun `scoreForMatch 3-tile combo1 is 30`() {
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        // 3 tiles × 10 base × 1.0 length bonus × 1.0 combo = 30
        assertEquals(30, ScoreEngine.scoreForMatch(match, combo = 1))
    }

    @Test
    fun `scoreForMatch 4-tile combo1 is 60`() {
        val match = Match(
            tiles = (0..3).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 4,
        )
        // 4 × 10 × 1.5 × 1.0 = 60
        assertEquals(60, ScoreEngine.scoreForMatch(match, combo = 1))
    }

    @Test
    fun `scoreForMatch 4-tile combo2 is 90`() {
        val match = Match(
            tiles = (0..3).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 4,
        )
        // 4 × 10 × 1.5 × 1.5 = 90
        assertEquals(90, ScoreEngine.scoreForMatch(match, combo = 2))
    }

    @Test
    fun `scoreForMatch 5-tile combo3 is 200`() {
        val match = Match(
            tiles = (0..4).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 5,
        )
        // 5 × 10 × 2.0 × 2.0 = 200
        assertEquals(200, ScoreEngine.scoreForMatch(match, combo = 3))
    }

    @Test
    fun `scoreForMatch default combo is 1`() {
        val match = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        // scoreForMatch(match) uses default combo = 1 → 30.
        assertEquals(30, ScoreEngine.scoreForMatch(match))
    }

    @Test
    fun `scoreForMatches sums scores with same combo`() {
        val m3 = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        val m4 = Match(
            tiles = (0..3).map { tile(1, it, SushiType.SUSHI2) },
            axis = MatchAxis.HORIZONTAL,
            length = 4,
        )
        // combo=1: m3 → 30, m4 → 60. Total = 90.
        assertEquals(90, ScoreEngine.scoreForMatches(listOf(m3, m4), combo = 1))
        // combo=2: m3 → 45, m4 → 90. Total = 135.
        assertEquals(135, ScoreEngine.scoreForMatches(listOf(m3, m4), combo = 2))
    }

    @Test
    fun `scoreForMatches empty list returns 0`() {
        assertEquals(0, ScoreEngine.scoreForMatches(emptyList()))
    }

    @Test
    fun `totalTilesMatched sums tile counts`() {
        val m3 = Match(
            tiles = (0..2).map { tile(0, it, SushiType.SUSHI1) },
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        val m4 = Match(
            tiles = (0..3).map { tile(1, it, SushiType.SUSHI2) },
            axis = MatchAxis.HORIZONTAL,
            length = 4,
        )
        val m5 = Match(
            tiles = (0..4).map { tile(2, it, SushiType.SUSHI3) },
            axis = MatchAxis.HORIZONTAL,
            length = 5,
        )
        assertEquals(12, ScoreEngine.totalTilesMatched(listOf(m3, m4, m5)))
        assertEquals(0, ScoreEngine.totalTilesMatched(emptyList()))
    }
}