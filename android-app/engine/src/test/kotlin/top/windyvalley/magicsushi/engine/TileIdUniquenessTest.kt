package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TileIdUniquenessTest — 回归测试：tile id 必须全局唯一。
 *
 * ## 背景（FIX_PLAN D1）
 *
 * `SushiTile.id` 被 `GameCanvas` 用作 Compose `key()`。同级 `key()` 重复会让
 * Compose 复用错误的 slot，导致 `SushiTile` 内部 `remember { dragOffset }` /
 * `animateFloatAsState` 的状态串到别的格子上 —— 手工测试中观察到的
 * 「未参与消除的 tile 莫名跳动」正是这个原因。
 *
 * 旧实现在 [BoardEngine.generateInitialBoard] 与 [BoardEngine.spawnRefill]
 * 两处都用 `row * 7 + col` 推导 id，而 `spawnRefill` 不知道棋盘上已有哪些
 * id 在用，于是在重力换位后必然撞号。
 *
 * 本测试锁死修复后的不变量：**任意时刻，一块棋盘上所有非空 tile 的 id 互不重复。**
 */
class TileIdUniquenessTest {

    @Before
    fun setUp() {
        // 每个用例独立计数，避免相互影响
        TileIdGenerator.resetForTest()
    }

    /** 收集棋盘上所有非空 tile 的 id。 */
    private fun idsOf(board: Board): List<Int> =
        (0 until board.size).flatMap { r ->
            (0 until board.size).mapNotNull { c -> board.grid[r][c]?.id }
        }

    /** 断言棋盘上没有重复 id，失败时打印出撞号的具体 id 及其坐标。 */
    private fun assertNoDuplicateIds(board: Board, label: String) {
        val ids = idsOf(board)
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (dupes.isNotEmpty()) {
            val detail = dupes.keys.joinToString("; ") { dupId ->
                val positions = (0 until board.size).flatMap { r ->
                    (0 until board.size).mapNotNull { c ->
                        if (board.grid[r][c]?.id == dupId) "($r,$c)" else null
                    }
                }
                "id=$dupId 出现在 $positions"
            }
            throw AssertionError("[$label] 棋盘存在重复 tile id → $detail")
        }
        assertEquals(
            "[$label] 去重后的 id 数量应等于 tile 总数",
            ids.size,
            ids.toSet().size,
        )
    }

    // ------------------------------------------------------------------
    // 1. 生成器本身的契约
    // ------------------------------------------------------------------

    @Test
    fun `next 单调递增且不重复`() {
        val ids = (1..1000).map { TileIdGenerator.next() }
        assertEquals("1000 次调用应产出 1000 个不同 id", 1000, ids.toSet().size)
        assertEquals("应单调递增", ids.sorted(), ids)
    }

    @Test
    fun `next 只产出正数`() {
        // 历史背景：AnimationEngine 曾给动画帧的 spawn tile 分配 -1, -2, ...
        // 构成独立编号空间。该约定已废除 —— spawn tile 现在直接用
        // spawnRefill 产出的真实 tile.id，全 App 只有这一个身份来源。
        // 保留"只产出正数"这条不变量：0 与负数都不是合法身份。
        repeat(100) {
            assertTrue("id 必须 >= 1", TileIdGenerator.next() >= 1)
        }
    }

    // ------------------------------------------------------------------
    // 2. 初始棋盘
    // ------------------------------------------------------------------

    @Test
    fun `generateInitialBoard 的 49 个 tile id 互不重复`() {
        val board = BoardEngine.generateInitialBoard(seed = 42L)
        assertEquals("初始棋盘应有 49 个 tile", 49, idsOf(board).size)
        assertNoDuplicateIds(board, "generateInitialBoard")
    }

    @Test
    fun `连续生成两块棋盘 id 不跨局复用`() {
        val b1 = BoardEngine.generateInitialBoard(seed = 1L)
        val b2 = BoardEngine.generateInitialBoard(seed = 2L)
        val overlap = idsOf(b1).toSet() intersect idsOf(b2).toSet()
        assertTrue(
            "重开一局时新棋盘不应复用上一局的 id（旧实现两局都是 0..48），撞号集合=$overlap",
            overlap.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 3. 核心回归：gravity + spawnRefill 之后不撞号
    // ------------------------------------------------------------------

    @Test
    fun `spawnRefill 在重力换位后不产生重复 id`() {
        // 构造一块确定性棋盘：col 0 的 row 3,4,5 是同类型（将被消除），
        // 其余用周期 3 的填充图案保证不额外触发匹配。
        val filler = listOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List<SushiTile?>(7) { c ->
                val type = if (c == 0 && r in 3..5) {
                    SushiType.SUSHI1
                } else {
                    filler[(r + c) % 3]
                }
                SushiTile(id = TileIdGenerator.next(), type = type, row = r, col = c)
            }
        }
        val board = Board(size = 7, grid = grid)
        assertNoDuplicateIds(board, "构造后的初始棋盘")

        // 消除 col 0 的 row 3..5 —— 这会让 row 0..2 的 tile 各下落 3 行，
        // 从而占据「按坐标公式算 id」时本属于 row 3..5 的位置，
        // 而 spawnRefill 又要为 row 0..2 发新 id。旧公式在此必然撞号。
        val eliminated = listOf(grid[3][0]!!, grid[4][0]!!, grid[5][0]!!)
        val match = Match(tiles = eliminated, axis = MatchAxis.VERTICAL, length = 3)

        val fallen = GravityEngine.applyGravity(board, listOf(match))
        assertNoDuplicateIds(fallen, "gravity 之后（未补充）")

        val refilled = BoardEngine.spawnRefill(fallen)
        assertEquals("补充后棋盘应重新填满 49 格", 49, idsOf(refilled).size)
        assertNoDuplicateIds(refilled, "spawnRefill 之后")
    }

    @Test
    fun `多轮 cascade 后棋盘 id 始终唯一`() {
        var board = BoardEngine.generateInitialBoard(seed = 7L)
        assertNoDuplicateIds(board, "cascade 起点")

        // 反复制造消除并补充，模拟长时间游玩。
        // 每轮强行把某一列前 3 格改成同类型来触发匹配。
        repeat(20) { round ->
            val col = round % 7
            val mutable: MutableList<MutableList<SushiTile?>> =
                MutableList(board.size) { r -> board.grid[r].toMutableList() }
            for (r in 0..2) {
                mutable[r][col] = mutable[r][col]?.copy(type = SushiType.SUSHI2)
            }
            board = board.copy(grid = mutable.map { it.toList() })

            val matches = MatchEngine.detectMatches(board)
            if (matches.isEmpty()) return@repeat

            val result = CascadeEngine.cascadeUntilStable(board, matches)
            board = result.finalBoard
            assertNoDuplicateIds(board, "cascade round $round")
        }
    }

    // ------------------------------------------------------------------
    // 4. 反向验证：旧公式确实会撞号（证明这个测试有意义）
    // ------------------------------------------------------------------

    @Test
    fun `旧的坐标公式在同场景下确实会撞号`() {
        // 这个用例不调用产品代码，而是复刻旧算法，
        // 证明上面的场景确实能触发撞号 —— 否则回归测试可能是个空壳。
        val boardSize = 7
        fun legacyId(row: Int, col: Int) = row * boardSize + col

        // 模拟：col 0 的 row 0..2 下落到 row 3..5（各降 3 行），
        // 然后 row 0..2 用旧公式补充新 tile。
        val survivorIdsAfterFall = (0..2).map { originalRow -> legacyId(originalRow, 0) }
        val spawnedIds = (0..2).map { newRow -> legacyId(newRow, 0) }

        val collision = survivorIdsAfterFall.toSet() intersect spawnedIds.toSet()
        assertTrue(
            "旧公式下，下落的老 tile 与新补充的 tile 必然共用 id 0/7/14 —— " +
                "这正是 tile 跳动的根因。撞号集合=$collision",
            collision.isNotEmpty(),
        )
        assertEquals("预期 3 个 id 撞号", 3, collision.size)
    }
}
