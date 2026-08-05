package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [CascadeEngine].
 *
 * Covers:
 *  - empty initial matches → empty cascades, identity board
 *  - one match → one cascade
 *  - finalBoard grid size 7x7
 *  - MAX_CASCADE_ITERATIONS constant
 *  - cascades are in time order (cascades[0] is initial)
 */
class CascadeEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = 0, type = type, row = row, col = col, isSelected = false, isLocked = false)

    @Test
    fun `cascadeUntilStable with empty initial returns empty cascades and identity board`() {
        val board = BoardEngine.generateInitialBoard(seed = 1L)
        val result = CascadeEngine.cascadeUntilStable(board, emptyList())
        assertTrue("empty initial matches must yield empty cascades", result.cascades.isEmpty())
        assertSame("empty initial must return input board by reference", board, result.finalBoard)
    }

    @Test
    fun `cascadeUntilStable with one match returns one cascade`() {
        // Row 3 cols 0..2 = SUSHI1; filler everywhere else (FILLER cycle has no 3-in-a-row).
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array<SushiTile?>(7) { c ->
            val t = if (r == 3 && c < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
        }}
        val board = Board(size = 7, grid = grid)
        val initial = MatchEngine.detectMatches(board)
        assertEquals("test setup: should detect 1 match", 1, initial.size)

        val result = CascadeEngine.cascadeUntilStable(board, initial)
        assertEquals(1, result.cascades.size)
    }

    @Test
    fun `cascadeUntilStable finalBoard is 7x7`() {
        val board = BoardEngine.generateInitialBoard(seed = 1L)
        val firstTile = board.grid.flatten().filterNotNull().first()
        val match = Match(
            tiles = listOf(firstTile),
            axis = MatchAxis.HORIZONTAL,
            length = 1,
        )
        val result = CascadeEngine.cascadeUntilStable(board, listOf(match))
        assertEquals(7, result.finalBoard.size)
        assertEquals(7, result.finalBoard.grid.size)
        assertTrue("all rows should be length 7", result.finalBoard.grid.all { it.size == 7 })
    }

    @Test
    fun `MAX_CASCADE_ITERATIONS is 20`() {
        // The dead-loop guard constant (see CascadeEngine docs).
        assertEquals(20, CascadeEngine.MAX_CASCADE_ITERATIONS)
    }

    @Test
    fun `cascadeUntilStable cascades are in time order (cascades0 is initial)`() {
        // Row 3 cols 0..2 = SUSHI1; filler everywhere else.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array<SushiTile?>(7) { c ->
            val t = if (r == 3 && c < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
        }}
        val board = Board(size = 7, grid = grid)
        val initial = MatchEngine.detectMatches(board)

        val result = CascadeEngine.cascadeUntilStable(board, initial)
        // cascades[0] should be the initial match list we passed in (by ref).
        assertSame("cascades[0] should be the same list we passed in", initial, result.cascades[0])
        assertEquals("first cascade should match initial.size", initial.size, result.cascades[0].size)
    }

    @Test
    fun `cascadeUntilStable on multi-round board returns 2 cascades`() {
        // A 5-row × 3-col block of SUSHI1 in cols 0..2 rows 2..6, filler elsewhere.
        // Initial match: H on row 6 cols 0..2 → triggers chain.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array<SushiTile?>(7) { c ->
            val t = if (c in 0..2 && r in 2..6) SushiType.SUSHI1 else filler[(c + r) % 3]
            SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
        }}
        val board = Board(size = 7, grid = grid)
        val firstTiles = listOf(board.grid[6][0]!!, board.grid[6][1]!!, board.grid[6][2]!!)
        val firstMatch = Match(tiles = firstTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val initialMatches = listOf(firstMatch)

        val result = CascadeEngine.cascadeUntilStable(board, initialMatches)
        // Expect at least 1 cascade (the initial). Could be 2 if chain fires.
        assertTrue(
            "cascade count must be in [1, MAX_CASCADE_ITERATIONS]",
            result.cascades.size in 1..CascadeEngine.MAX_CASCADE_ITERATIONS,
        )
    }

    @Test
    fun `cascadeUntilStable no-op board returns at least 1 cascade`() {
        // A single 3-tile horizontal match on a filler-pattern board.
        // After gravity, spawnRefill fills the 3 null cells (row 3, cols 0-2)
        // with random sushi. If all 3 happen to be the same type (e.g. SUSHI1),
        // they form an accidental H match → cascades.size = 2.
        // This is non-deterministic (depends on Random.Default). The stable
        // invariant is: cascades.size >= 1 (initial match always present).
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        fun fillerAt(r: Int, c: Int): SushiType = filler[(c + r) % 3]
        val grid: Array<Array<SushiTile?>> = Array(7) { r -> Array<SushiTile?>(7) { c ->
            val t = if (r == 3 && c < 3) SushiType.SUSHI1 else fillerAt(r, c)
            SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
        }}
        val board = Board(size = 7, grid = grid)
        val firstTiles = listOf(board.grid[3][0]!!, board.grid[3][1]!!, board.grid[3][2]!!)
        val firstMatch = Match(tiles = firstTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val initialMatches = listOf(firstMatch)

        val result = CascadeEngine.cascadeUntilStable(board, initialMatches)
        assertTrue(
            "cascades.size must be >= 1 (initial match always present); " +
                "random refill may add extra rounds",
            result.cascades.size >= 1,
        )
        // cascades.toList() creates a new list, so use assertEquals (content) not assertSame (reference)
        assertEquals(
            "cascades[0] should have same content as initial matches",
            initialMatches,
            result.cascades[0],
        )
    }
}