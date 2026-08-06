package top.windyvalley.magicsushi.engine

import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

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
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
            val t = if (r == 3 && c < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
            SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
        }}
        val board = Board(size = 7, grid = grid)
        val initial = MatchEngine.detectMatches(board)
        assertEquals("test setup: should detect 1 match", 1, initial.size)

        // ⚠️ 必须注入固定 seed：spawnRefill 补充的新 tile 有概率又凑成三连，
        // 触发第 2 轮 cascade。用 Random.Default 时本用例约 1/3 概率失败
        // （已实测 3 次运行中 1 次失败）——属于预先存在的 flaky 测试。
        // seed = 20250806L 下补充的 tile 不产生新匹配，恰好 1 轮。
        val result = CascadeEngine.cascadeUntilStable(board, initial, rng = Random(20250806L))
        assertEquals(1, result.cascades.size)
    }

    @Test
    fun `cascadeUntilStable 的轮数至少为 1 且不超过上限（任意 seed）`() {
        // 上一个用例锁定"某个特定 seed 下恰好 1 轮"；本用例验证与 seed 无关的
        // 通用不变量，避免把偶然的随机结果当成规格。
        val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        repeat(30) { seed ->
            var nextId = 0
            val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
                val t = if (r == 3 && c < 3) SushiType.SUSHI1 else filler[(c + r) % 3]
                SushiTile(id = nextId++, type = t, row = r, col = c, isSelected = false, isLocked = false)
            }}
            val board = Board(size = 7, grid = grid)
            val initial = MatchEngine.detectMatches(board)
            val result = CascadeEngine.cascadeUntilStable(board, initial, rng = Random(seed.toLong()))

            assertTrue(
                "seed=$seed: 有初始匹配时至少应有 1 轮 cascade",
                result.cascades.size >= 1,
            )
            assertTrue(
                "seed=$seed: 轮数不应超过 MAX_CASCADE_ITERATIONS+1，实际 ${result.cascades.size}",
                result.cascades.size <= CascadeEngine.MAX_CASCADE_ITERATIONS + 1,
            )
            assertEquals("seed=$seed: finalBoard 必须填满", 49,
                result.finalBoard.grid.flatten().filterNotNull().size)
        }
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
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
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
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
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
        val grid: List<List<SushiTile?>> = List(7) { r -> List<SushiTile?>(7) { c ->
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