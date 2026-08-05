package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [BoardEngine].
 *
 * Covers:
 *  - generateInitialBoard: size, fill, no-match guarantee, type diversity
 *  - attemptSwap: adjacent (Success), non-adjacent (InvalidSwap), locked (BoardLocked)
 *  - isAdjacent: positive (4 cardinal), negative (diagonal / far / same)
 */
class BoardEngineTest {

    @Test
    fun `generateInitialBoard returns 7x7 grid`() {
        val board = BoardEngine.generateInitialBoard()
        assertEquals(7, board.size)
        assertEquals(7, board.grid.size)
        assertTrue("all rows should be length 7", board.grid.all { it.size == 7 })
    }

    @Test
    fun `generateInitialBoard fills all 49 cells`() {
        val board = BoardEngine.generateInitialBoard()
        val filled = board.grid.flatten().count { it != null }
        assertEquals(49, filled)
    }

    @Test
    fun `generateInitialBoard has no initial matches across 50 seeds`() {
        repeat(50) { seed ->
            val board = BoardEngine.generateInitialBoard(seed = seed.toLong())
            val matches = MatchEngine.detectMatches(board)
            assertTrue(
                "Seed $seed should have no initial matches but found ${matches.size}",
                matches.isEmpty(),
            )
        }
    }

    @Test
    fun `generateInitialBoard uses all 6 sushi types across many seeds`() {
        val typesFound = mutableSetOf<SushiType>()
        repeat(50) { seed ->
            val board = BoardEngine.generateInitialBoard(seed = seed.toLong())
            board.grid.flatten().filterNotNull().forEach { typesFound.add(it.type) }
        }
        assertEquals("all 6 sushi types should appear over 50 seeds", 6, typesFound.size)
    }

    @Test
    fun `generateInitialBoard is deterministic for same seed`() {
        val a = BoardEngine.generateInitialBoard(seed = 123L)
        val b = BoardEngine.generateInitialBoard(seed = 123L)
        assertTrue(
            "same seed must produce structurally equal grids",
            a.grid.contentDeepEquals(b.grid),
        )
    }

    @Test
    fun `attemptSwap adjacent tiles returns Success and returns new board`() {
        val board = BoardEngine.generateInitialBoard(seed = 42L)
        val from = board.grid[3][3]!!
        val to = board.grid[3][4]!!
        val (newBoard, result) = BoardEngine.attemptSwap(board, from, to)
        assertEquals(SwapResult.Success, result)
        assertNotSame("Success must return a fresh Board", board, newBoard)
        // After swap, the types at (3,3) and (3,4) must be exchanged.
        assertEquals(from.type, newBoard.grid[3][4]!!.type)
        assertEquals(to.type, newBoard.grid[3][3]!!.type)
    }

    @Test
    fun `attemptSwap non-adjacent tiles returns InvalidSwap`() {
        val board = BoardEngine.generateInitialBoard(seed = 42L)
        val from = board.grid[0][0]!!
        val to = board.grid[2][2]!!
        val (sameBoard, result) = BoardEngine.attemptSwap(board, from, to)
        assertEquals(SwapResult.InvalidSwap, result)
        assertSame("InvalidSwap must return the original board reference", board, sameBoard)
    }

    @Test
    fun `attemptSwap on locked board returns BoardLocked`() {
        val board = BoardEngine.generateInitialBoard(seed = 42L)
        val from = board.grid[0][0]!!
        val to = board.grid[0][1]!!
        val locked = board.copy(swapLock = true)
        val (sameBoard, result) = BoardEngine.attemptSwap(locked, from, to)
        assertEquals(SwapResult.BoardLocked, result)
        assertSame("BoardLocked must return the original board reference", locked, sameBoard)
    }

    @Test
    fun `isAdjacent detects 4 cardinal directions`() {
        val center = SushiTile(0, SushiType.SUSHI1, 3, 3, false, false)
        // Right
        assertTrue(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 3, 4, false, false)),
        )
        // Left
        assertTrue(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 3, 2, false, false)),
        )
        // Up
        assertTrue(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 2, 3, false, false)),
        )
        // Down
        assertTrue(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 4, 3, false, false)),
        )
    }

    @Test
    fun `isAdjacent rejects diagonal and far tiles`() {
        val center = SushiTile(0, SushiType.SUSHI1, 3, 3, false, false)
        // Diagonal
        assertFalse(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 2, 2, false, false)),
        )
        // Distance 2
        assertFalse(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 5, 5, false, false)),
        )
        // Same cell
        assertFalse(
            BoardEngine.isAdjacent(center, SushiTile(1, SushiType.SUSHI1, 3, 3, false, false)),
        )
    }

    @Test
    fun `isAdjacentInDirection returns correct direction`() {
        val origin = SushiTile(0, SushiType.SUSHI1, 3, 3, false, false)
        assertEquals(
            Direction.RIGHT,
            BoardEngine.isAdjacentInDirection(origin, SushiTile(1, SushiType.SUSHI1, 3, 4, false, false)),
        )
        assertEquals(
            Direction.LEFT,
            BoardEngine.isAdjacentInDirection(origin, SushiTile(1, SushiType.SUSHI1, 3, 2, false, false)),
        )
        assertEquals(
            Direction.UP,
            BoardEngine.isAdjacentInDirection(origin, SushiTile(1, SushiType.SUSHI1, 2, 3, false, false)),
        )
        assertEquals(
            Direction.DOWN,
            BoardEngine.isAdjacentInDirection(origin, SushiTile(1, SushiType.SUSHI1, 4, 3, false, false)),
        )
        // Diagonal → null
        assertNull(
            BoardEngine.isAdjacentInDirection(origin, SushiTile(1, SushiType.SUSHI1, 2, 2, false, false)),
        )
    }
}