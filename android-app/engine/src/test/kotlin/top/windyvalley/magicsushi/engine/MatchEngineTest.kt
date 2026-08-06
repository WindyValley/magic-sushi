package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [MatchEngine].
 *
 * Covers:
 *  - empty board
 *  - horizontal 3-in-a-row
 *  - vertical 3-in-a-row
 *  - 4-in-a-row, 5-in-a-row (length passthrough)
 *  - diagonal NOT detected (FR-3.3)
 *  - L-shape (3 H + 3 V sharing one tile → 2 matches)
 *  - null cells break runs
 */
class MatchEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = 0, type = type, row = row, col = col, isSelected = false, isLocked = false)

    private fun boardOf(vararg rows: List<SushiTile?>): Board =
        Board(size = 7, grid = rows.toList())

    @Test
    fun `detectMatches on empty board returns empty list`() {
        val empty = Board(size = 7, grid = List(7) { List<SushiTile?>(7) { null } })
        assertTrue(
            "empty board must have no matches",
            MatchEngine.detectMatches(empty).isEmpty(),
        )
    }

    @Test
    fun `detectMatches horizontal 3-in-a-row`() {
        // Row 0 cols 0..2 = SUSHI1; filler everywhere else.
        // Use FILLER[(c+r)%3] = [S3,S4,S5] cycle which has no 3-in-a-row anywhere.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (r == 0 && c < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].length)
        assertEquals(MatchAxis.HORIZONTAL, matches[0].axis)
        assertEquals(3, matches[0].tiles.size)
    }

    @Test
    fun `detectMatches vertical 3-in-a-row`() {
        // Col 0 rows 0..2 = SUSHI1; filler everywhere else.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (c == 0 && r < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].length)
        assertEquals(MatchAxis.VERTICAL, matches[0].axis)
    }

    @Test
    fun `detectMatches 4-in-a-row has length 4`() {
        // Row 0 cols 0..3 = SUSHI1; filler everywhere else.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (r == 0 && c < 4) SushiType.SUSHI1 else filler[(c + r) % 3]
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertEquals(1, matches.size)
        assertEquals(4, matches[0].length)
        assertEquals(4, matches[0].tiles.size)
        assertTrue(matches[0].tiles.all { it.type == SushiType.SUSHI1 })
    }

    @Test
    fun `detectMatches 5-in-a-row has length 5`() {
        // Row 0 cols 0..4 = SUSHI1; filler everywhere else.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (r == 0 && c < 5) SushiType.SUSHI1 else filler[(c + r) % 3]
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertEquals(1, matches.size)
        assertEquals(5, matches[0].length)
        assertEquals(5, matches[0].tiles.size)
    }

    @Test
    fun `detectMatches rejects diagonal runs`() {
        // Diagonal: (0,0), (1,1), (2,2) all SUSHI1; filler everywhere else.
        // Filler cycle has no 3-in-a-row in any row or column.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (r == c && r < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        assertTrue(
            "diagonal must not be detected as a match (FR-3.3)",
            MatchEngine.detectMatches(board).isEmpty(),
        )
    }

    @Test
    fun `detectMatches L-shape returns 2 matches with shared corner`() {
        // L-shape: H arm on row 0 cols 0..2; V arm on col 0 rows 1..2.
        // Corner (0,0) is shared. Filler everywhere else.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = when {
                r == 0 && c < 3 -> SushiType.SUSHI1
                c == 0 && r < 3 -> SushiType.SUSHI1
                else -> filler[(c + r) % 3]
            }
            tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertEquals("L-shape must yield exactly 2 matches", 2, matches.size)

        val hMatch = matches.firstOrNull { it.axis == MatchAxis.HORIZONTAL }
        val vMatch = matches.firstOrNull { it.axis == MatchAxis.VERTICAL }
        assertNotNull("L-shape must include a HORIZONTAL match", hMatch)
        assertNotNull("L-shape must include a VERTICAL match", vMatch)
        assertEquals(3, hMatch!!.length)
        assertEquals(3, vMatch!!.length)

        // The shared corner tile must appear in BOTH matches (by tile reference).
        val hTiles = hMatch.tiles
        val vTiles = vMatch.tiles
        val sharedCount = hTiles.count { hTile -> vTiles.any { it === hTile } }
        assertEquals("L-shape: corner tile must appear in both H and V matches", 1, sharedCount)
    }

    @Test
    fun `detectMatches skips null cells (null breaks run)`() {
        // Row 0: cols 0,1 = SUSHI1, col 2 = null, cols 3..6 = SUSHI1.
        // Expected: AA before null is too short (run=2), null breaks the run,
        // then 4 S1s in cols 3..6 form a length-4 match.
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 0
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t: SushiType? = when {
                r == 0 && c < 2 -> SushiType.SUSHI1
                r == 0 && c >= 3 -> SushiType.SUSHI1
                r == 0 && c == 2 -> null
                else -> filler[(c + r) % 3]
            }
            if (t == null) null else tile(r, c, t).copy(id = nextId++)
        }}
        val board = Board(size = 7, grid = grid)
        assertNull("setup: (0,2) should be null", board.grid[0][2])

        val matches = MatchEngine.detectMatches(board)
        // 1 horizontal match on row 0 (cols 3..6 = length 4).
        val hOnRow0 = matches.filter { it.axis == MatchAxis.HORIZONTAL && it.tiles[0].row == 0 }
        assertEquals(1, hOnRow0.size)
        assertEquals(4, hOnRow0[0].length)
    }

    @Test
    fun `detectMatches on freshly generated board returns empty list`() {
        // Sanity check: a real board from BoardEngine must have no matches (FR-1.2).
        val board = BoardEngine.generateInitialBoard(seed = 7L)
        assertTrue(
            "fresh board must have no matches",
            MatchEngine.detectMatches(board).isEmpty(),
        )
    }
}