package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [GravityEngine].
 *
 * Gravity behavior summary:
 *  1. Phase 1 — null-out: eliminated cells become null
 *  2. Phase 2 — gravity sweep: per-column, non-null tiles pack to bottom
 *     (preserving relative order); nulls float to top
 *  3. Phase 3 — optional refill: top nulls filled by BoardEngine.spawnRefill
 *
 * Key invariants tested:
 *  - empty matches → board content unchanged (observable behavior, not identity)
 *  - single elimination → null floats to top, survivors pack to bottom
 *  - full column elimination → entire column becomes null
 *  - null distribution: correct number of nulls at top per column
 *  - tile row/col fields synchronized to new grid position after fall
 *  - grid size always preserved (7×7)
 *  - input board never mutated
 *  - tiles never cross columns (vertical movement only)
 */
class GravityEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = 0, type = type, row = row, col = col, isSelected = false, isLocked = false)

    @Test
    fun `applyGravity with empty matches leaves board content unchanged`() {
        val board = BoardEngine.generateInitialBoard(seed = 1L)
        val snapshot = board.grid.map { it.map { t -> t?.type } }

        val result = GravityEngine.applyGravity(board, emptyList())

        // Observable behavior: every cell type must be identical to before
        val resultTypes = result.grid.map { it.map { t -> t?.type } }
        assertEquals("empty matches → board content unchanged", snapshot, resultTypes)
    }

    @Test
    fun `applyGravity single elimination packs survivors to bottom with null at top`() {
        // Col 0 full of SUSHI1; eliminate the middle tile at row=3.
        // Pre-fall col 0 (top→bottom): [S1, S1, S1, null, S1, S1, S1]
        //   filterNotNull → [S1, S1, S1, S1, S1, S1] (6 survivors)
        //   nullCount = 1 → prepend 1 null
        // Post-fall col 0: [null, S1, S1, S1, S1, S1, S1]
        val grid: Array<Array<SushiTile?>> = Array(7) { row -> Array<SushiTile?>(7) { col ->
            tile(row, col, SushiType.SUSHI1).copy(id = row * 7 + col)
        }}
        val board = Board(size = 7, grid = grid)
        val eliminatedTile = board.grid[3][0]!!
        val match = Match(
            tiles = listOf(eliminatedTile),
            axis = MatchAxis.VERTICAL,
            length = 1,
        )
        val newBoard = GravityEngine.applyGravity(board, listOf(match), doRefill = false)

        // Row 0 col 0 = null (the single null "floats to top" after pack-to-bottom)
        assertNull("row 0 col 0 should be null after pack-to-bottom", newBoard.grid[0][0])
        // Rows 1-6 col 0 = SUSHI1 (6 survivors bottom-packed)
        for (r in 1..6) {
            assertEquals("row $r col 0 should be SUSHI1", SushiType.SUSHI1, newBoard.grid[r][0]?.type)
        }
        // The eliminated tile id must not appear anywhere in column 0
        val stillThere = (0..6).any { r -> newBoard.grid[r][0]?.id == eliminatedTile.id }
        assertFalse("eliminated tile id should be gone from column 0", stillThere)
    }

    @Test
    fun `applyGravity full column elimination leaves column all-null`() {
        // Col 0 = SUSHI3 (7 cells), other cols SUSHI4 (no matches).
        val grid: Array<Array<SushiTile?>> = Array(7) { row -> Array<SushiTile?>(7) { col ->
            val t = if (col == 0) SushiType.SUSHI3 else SushiType.SUSHI4
            tile(row, col, t).copy(id = row * 7 + col)
        }}
        val board = Board(size = 7, grid = grid)
        val tiles = (0..6).map { board.grid[it][0]!! }
        val match = Match(tiles = tiles, axis = MatchAxis.VERTICAL, length = 7)
        val newBoard = GravityEngine.applyGravity(board, listOf(match), doRefill = false)
        // Column 0 should be all null (no non-null tiles to fall into it).
        for (row in 0..6) {
            assertNull("col 0 row $row should be null after full V elim", newBoard.grid[row][0])
        }
        // Other cols unchanged.
        for (c in 1..6) for (r in 0..6) {
            assertEquals(
                "col $c row $r should still be SUSHI4",
                SushiType.SUSHI4,
                newBoard.grid[r][c]?.type,
            )
        }
    }

    @Test
    fun `applyGravity places nulls at top of each affected column`() {
        // Build a board where col 1 has a 3-cell V match (rows 2..4).
        // Pre-fall col 1: [S1, S1, S5, S5, S5, S1, S1]
        // After nulling rows 2..4: [S1, S1, null, null, null, S1, S1]
        // After pack-to-bottom: [null, null, null, S1, S1, S1, S1]
        val grid: Array<Array<SushiTile?>> = Array(7) { row -> Array<SushiTile?>(7) { col ->
            when {
                col == 1 && row in 2..4 -> tile(row, col, SushiType.SUSHI5).copy(id = row * 7 + col)
                col == 1 -> tile(row, col, SushiType.SUSHI1).copy(id = row * 7 + col)
                else -> tile(row, col, SushiType.SUSHI4).copy(id = row * 7 + col)
            }
        }}
        val board = Board(size = 7, grid = grid)
        val vTiles = listOf(board.grid[2][1]!!, board.grid[3][1]!!, board.grid[4][1]!!)
        val vMatch = Match(tiles = vTiles, axis = MatchAxis.VERTICAL, length = 3)
        val newBoard = GravityEngine.applyGravity(board, listOf(vMatch), doRefill = false)

        // Col 1 rows 0-2 should be null (3 nulls at top)
        for (r in 0..2) assertNull("col 1 row $r should be null", newBoard.grid[r][1])
        // Col 1 rows 3-6 should be SUSHI1 (4 survivors bottom-packed)
        for (r in 3..6) {
            assertEquals("col 1 row $r should be SUSHI1", SushiType.SUSHI1, newBoard.grid[r][1]?.type)
        }
    }

    @Test
    fun `applyGravity updates tile row and col fields to new grid position`() {
        // Same setup as the null-distribution test above.
        // Survivors at original rows 0, 1, 5, 6 should end up at rows 3, 4, 5, 6.
        val grid: Array<Array<SushiTile?>> = Array(7) { row -> Array<SushiTile?>(7) { col ->
            when {
                col == 1 && row in 2..4 -> tile(row, col, SushiType.SUSHI5).copy(id = row * 7 + col)
                col == 1 -> tile(row, col, SushiType.SUSHI1).copy(id = row * 7 + col)
                else -> tile(row, col, SushiType.SUSHI4).copy(id = row * 7 + col)
            }
        }}
        val board = Board(size = 7, grid = grid)
        val s1At0 = board.grid[0][1]!!
        val s1At1 = board.grid[1][1]!!
        val s1At5 = board.grid[5][1]!!
        val s1At6 = board.grid[6][1]!!

        val vTiles = listOf(board.grid[2][1]!!, board.grid[3][1]!!, board.grid[4][1]!!)
        val vMatch = Match(tiles = vTiles, axis = MatchAxis.VERTICAL, length = 3)
        val newBoard = GravityEngine.applyGravity(board, listOf(vMatch))

        // Locate each surviving tile by id in the new board
        val byId = (0..6).mapNotNull { r -> newBoard.grid[r][1]?.let { it.id to it } }.toMap()

        // Original row=0 → new row=3 (fell 3 steps)
        val m0 = byId[s1At0.id]!!
        assertEquals("tile at row=0 should fall to row=3", 3, m0.row)
        assertEquals("col should stay 1", 1, m0.col)
        assertEquals(SushiType.SUSHI1, m0.type)

        // Original row=1 → new row=4 (fell 3 steps)
        val m1 = byId[s1At1.id]!!
        assertEquals("tile at row=1 should fall to row=4", 4, m1.row)
        assertEquals(1, m1.col)

        // Original row=5 → new row=5 (did not move)
        val m5 = byId[s1At5.id]!!
        assertEquals("tile at row=5 should stay row=5", 5, m5.row)
        assertEquals(1, m5.col)

        // Original row=6 → new row=6 (did not move)
        val m6 = byId[s1At6.id]!!
        assertEquals("tile at row=6 should stay row=6", 6, m6.row)
        assertEquals(1, m6.col)
    }

    @Test
    fun `applyGravity preserves grid size 7x7`() {
        val board = BoardEngine.generateInitialBoard(seed = 5L)
        // Pick a real tile from the generated board.
        val firstTile = board.grid.flatten().filterNotNull().first()
        val match = Match(
            tiles = listOf(firstTile),
            axis = MatchAxis.HORIZONTAL,
            length = 1,
        )
        val newBoard = GravityEngine.applyGravity(board, listOf(match), doRefill = false)
        assertEquals(7, newBoard.size)
        assertEquals(7, newBoard.grid.size)
        assertTrue("all rows should remain length 7", newBoard.grid.all { it.size == 7 })
    }

    @Test
    fun `applyGravity does not mutate input board`() {
        val grid: Array<Array<SushiTile?>> = Array(7) { row -> Array<SushiTile?>(7) { col ->
            tile(row, col, SushiType.SUSHI1).copy(id = row * 7 + col)
        }}
        val board = Board(size = 7, grid = grid)
        val snapshotTypes = Array(7) { r -> Array(7) { c -> board.grid[r][c]?.type } }

        val eliminatedTile = board.grid[3][0]!!
        val match = Match(
            tiles = listOf(eliminatedTile),
            axis = MatchAxis.VERTICAL,
            length = 1,
        )
        GravityEngine.applyGravity(board, listOf(match))

        // Verify the original board's types are exactly what we set (immutability).
        for (r in 0..6) for (c in 0..6) {
            assertEquals(
                "input board grid should not have been mutated at ($r,$c)",
                snapshotTypes[r][c],
                board.grid[r][c]?.type,
            )
        }
    }
}