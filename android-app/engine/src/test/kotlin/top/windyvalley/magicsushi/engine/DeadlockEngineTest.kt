package top.windyvalley.magicsushi.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeadlockEngine] 死局检测与重排测试。
 *
 * 构造真死局比想象中难 —— 需要「无已成立三连」且「任意相邻交换都凑不出三连」
 * 两个条件同时成立。这里用 2×2 分块的四色棋盘：
 *
 * ```
 * 1 1 2 2 1 1 2
 * 1 1 2 2 1 1 2
 * 3 3 4 4 3 3 4
 * 3 3 4 4 3 3 4
 * 1 1 2 2 1 1 2
 * 1 1 2 2 1 1 2
 * 3 3 4 4 3 3 4
 * ```
 *
 * 每种类型都以 2×2 方块出现，交换任意两个相邻格，最多只能把某类凑到 2 连
 * （因为 2×2 块的边界外侧永远是另一种类型），够不到 3 连。
 */
class DeadlockEngineTest {

    private fun tile(row: Int, col: Int, type: SushiType, id: Int = row * 7 + col) =
        SushiTile(id = id, type = type, row = row, col = col)

    /** 2×2 分块四色棋盘 —— 无三连且无解，是真死局。 */
    private fun deadlockBoard(): Board {
        val quad = listOf(
            listOf(SushiType.SUSHI1, SushiType.SUSHI2),
            listOf(SushiType.SUSHI3, SushiType.SUSHI4),
        )
        val grid = List(7) { row ->
            List<SushiTile?>(7) { col ->
                // (row/2) % 2 选行块，(col/2) % 2 选列块
                val type = quad[(row / 2) % 2][(col / 2) % 2]
                tile(row, col, type)
            }
        }
        return Board(size = 7, grid = grid)
    }

    /**
     * 有解棋盘：在死局盘上手工摆一处「一步可成三连」，且不引入已成立三连。
     *
     * 死局盘 row 6 原本是 `S3 S3 S4 S4 S3 S3 S4`（2×2 分块图案）。
     * 改成 `S5 S5 S6 S5 S3 S3 S4`，并把 `(5,2)` 也改成 S5：
     *
     * ```
     * row 5:  S1 S1 S5 S2 S1 S1 S2     ← (5,2) 改为 S5
     * row 6:  S5 S5 S6 S5 S3 S3 S4     ← col 0..3 重摆
     *          ↑  ↑  ↑
     *          交换 (6,2)S6 ↔ (5,2)S5 后 row 6 col 0,1,2 = S5 S5 S5 ✓
     * ```
     *
     * 交换前需确认无已成立三连：
     *  - row 6 = `S5 S5 S6 S5 S3 S3 S4` —— 最长同色 2 连 ✓
     *  - row 5 = `S1 S1 S5 S2 S1 S1 S2` —— 最长 2 连 ✓
     *  - col 2 = `S2 S2 S4 S4 S2 S5 S6` —— 最长 2 连 ✓
     *  - col 0 = `S1 S1 S3 S3 S1 S1 S5` —— 最长 2 连 ✓
     *  - col 1 = 同 col 0 图案 + S5 收尾 ✓
     *  - col 3 = `S2 S2 S4 S4 S2 S2 S5` —— 最长 2 连 ✓
     */
    private fun solvableBoard(): Board {
        val base = deadlockBoard()
        val grid = base.grid.map { it.toMutableList() }

        grid[6][0] = grid[6][0]!!.copy(type = SushiType.SUSHI5)
        grid[6][1] = grid[6][1]!!.copy(type = SushiType.SUSHI5)
        grid[6][2] = grid[6][2]!!.copy(type = SushiType.SUSHI6)
        grid[6][3] = grid[6][3]!!.copy(type = SushiType.SUSHI5)
        grid[5][2] = grid[5][2]!!.copy(type = SushiType.SUSHI5)

        return base.copy(grid = grid.map { it.toList() })
    }

    // ========================================================================
    // hasValidMove / isDeadlock
    // ========================================================================

    @Test
    fun `2x2 quad board is a genuine deadlock`() {
        val board = deadlockBoard()

        // 前提校验：必须先证明这个盘面没有已成立的三连，
        // 否则「无解」的结论毫无意义（有三连的盘根本不该进死局检测）。
        assertTrue(
            "fixture must have no pre-existing matches",
            MatchEngine.detectMatches(board).isEmpty(),
        )
        assertFalse("quad board must have no valid move", DeadlockEngine.hasValidMove(board))
        assertTrue("quad board must be reported as deadlock", DeadlockEngine.isDeadlock(board))
    }

    @Test
    fun `board with one swap available is not a deadlock`() {
        val board = solvableBoard()

        assertTrue(
            "fixture must have no pre-existing matches",
            MatchEngine.detectMatches(board).isEmpty(),
        )
        assertTrue("solvable board must report a valid move", DeadlockEngine.hasValidMove(board))
        assertFalse("solvable board must not be a deadlock", DeadlockEngine.isDeadlock(board))
    }

    @Test
    fun `generated initial boards are not deadlocked`() {
        // generateInitialBoard 只保证无三连，不保证有解 —— 这条测试正是为了
        // 量化「随机开局撞上死局」的概率。若将来某次改动让它频繁失败，
        // 说明生成算法需要加有解性约束。
        repeat(200) { seed ->
            val board = BoardEngine.generateInitialBoard(seed = seed.toLong())
            assertTrue(
                "generated board (seed=$seed) must have a valid move",
                DeadlockEngine.hasValidMove(board),
            )
        }
    }

    @Test
    fun `hasValidMove ignores empty cells`() {
        // 全空棋盘没有任何 tile 可交换 → 无解。不该抛异常。
        val empty = Board(size = 7, grid = List(7) { List<SushiTile?>(7) { null } })
        assertFalse("empty board has no valid move", DeadlockEngine.hasValidMove(empty))
    }

    @Test
    fun `hasValidMove does not mutate the board`() {
        val board = deadlockBoard()
        val before = board.copy()

        DeadlockEngine.hasValidMove(board)

        assertEquals("hasValidMove must be read-only", before, board)
    }

    @Test
    fun `hasValidMove is unaffected by swapLock`() {
        // 死局检测是只读探测，不该受动画锁影响 —— 锁住的棋盘同样要能判断死局，
        // 否则动画期间的检测会得出错误结论。
        val locked = deadlockBoard().copy(swapLock = true)
        assertFalse("locked deadlock board is still a deadlock", DeadlockEngine.hasValidMove(locked))

        val lockedSolvable = solvableBoard().copy(swapLock = true)
        assertTrue(
            "locked solvable board still has a move",
            DeadlockEngine.hasValidMove(lockedSolvable),
        )
    }

    // ========================================================================
    // reshuffleIfDeadlocked
    // ========================================================================

    @Test
    fun `reshuffle is a no-op when board already has a move`() {
        val board = solvableBoard()
        val (result, didReshuffle) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(42))

        assertFalse("must not reshuffle a solvable board", didReshuffle)
        assertEquals("board must be returned untouched", board, result)
    }

    @Test
    fun `reshuffle resolves a deadlock`() {
        val board = deadlockBoard()
        val (result, didReshuffle) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(42))

        assertTrue("must report that a reshuffle happened", didReshuffle)
        assertTrue("reshuffled board must have a valid move", DeadlockEngine.hasValidMove(result))
    }

    @Test
    fun `reshuffled board has no pre-existing matches`() {
        // 若重排后盘面自带三连，玩家什么都没做就会触发连锁加分 —— 白送分数。
        repeat(50) { seed ->
            val (result, didReshuffle) =
                DeadlockEngine.reshuffleIfDeadlocked(deadlockBoard(), Random(seed.toLong()))

            assertTrue("seed=$seed must reshuffle", didReshuffle)
            assertTrue(
                "seed=$seed reshuffled board must have no auto-matches",
                MatchEngine.detectMatches(result).isEmpty(),
            )
        }
    }

    @Test
    fun `reshuffle preserves type distribution`() {
        val board = deadlockBoard()
        val before = typeHistogram(board)

        val (result, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(7))

        assertEquals(
            "reshuffle must only permute types, never add or remove any",
            before,
            typeHistogram(result),
        )
    }

    @Test
    fun `reshuffle moves tile entities and keeps coordinates consistent`() {
        val board = deadlockBoard()
        val result = DeadlockEngine.reshuffleIfDeadlocked(board, Random(7)).board

        // ## 语义变更记录（v1.0.1 起）
        //
        // 这条测试原来断言「id 与位置都不变」，前提是洗牌只重新分配 type、
        // tile 实体留在原格。那个模型画不出移动动画（同类型寿司无法区分
        // 来源），已被 tile 实体搬家取代。
        //
        // 现在成立的不变量是下面三条，比原来更强 —— 原来只查了「没变」，
        // 现在要查「搬对了」。

        // 1. id 集合不变：搬家不生成也不销毁 tile，只是 id 出现在别的格子里。
        val originalIds = board.grid.flatten().filterNotNull().map { it.id }.sorted()
        val shuffledIds = result.grid.flatten().filterNotNull().map { it.id }.sorted()
        assertEquals("tile id 集合不能变（不新建也不丢弃 tile）", originalIds, shuffledIds)

        // 2. 每个 tile 自带的坐标必须与它实际所在的格子一致。
        //
        // 这条是搬家实现最容易错的地方：只挪引用忘了 copy(row=, col=)，
        // 就会得到「自称在 (0,0) 却躺在 (3,4) 格里」的 tile。
        // MatchEngine / GravityEngine 都读 tile.row/col 而非遍历下标，
        // 不一致会让后续所有判定错位。
        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                val tile = result.grid[row][col]!!
                assertEquals("($row,$col) 的 tile.row 必须等于所在行", row, tile.row)
                assertEquals("($row,$col) 的 tile.col 必须等于所在列", col, tile.col)
            }
        }

        // 3. 类型分布不变：置换只是重新分配同一批 tile。
        val originalTypes = board.grid.flatten().filterNotNull()
            .groupingBy { it.type }.eachCount()
        val shuffledTypes = result.grid.flatten().filterNotNull()
            .groupingBy { it.type }.eachCount()
        assertEquals("类型分布不能变", originalTypes, shuffledTypes)
    }

    @Test
    fun `reshuffle origin map points at the real source cell`() {
        val board = deadlockBoard()
        val result = DeadlockEngine.reshuffleIfDeadlocked(board, Random(7))

        assertTrue("这个棋盘应当触发重排", result.didReshuffle)
        assertTrue("重排后 origin 不应为空", result.origin.isNotEmpty())

        // origin 是 UI 画移动轨迹的唯一依据 —— 它说谎，寿司就会从错误的
        // 位置飞出来。这里逐条核对：目标格的 type 必须等于来源格原本的 type。
        for ((target, source) in result.origin) {
            val (tr, tc) = target
            val (sr, sc) = source

            val movedTile = result.board.grid[tr][tc]!!
            val sourceTile = board.grid[sr][sc]!!

            assertEquals(
                "($tr,$tc) 声称来自 ($sr,$sc)，类型必须与来源格原本的类型一致",
                sourceTile.type,
                movedTile.type,
            )
        }

        // origin 只收录真正移动了的格子 —— 原地不动的不该出现在表里，
        // 否则 UI 会为一堆零位移的 tile 建动画状态。
        for ((target, source) in result.origin) {
            assertTrue("原地未动的格子 $target 不应出现在 origin 里", target != source)
        }
    }

    @Test
    fun `reshuffle preserves tile count`() {
        val board = deadlockBoard()
        val (result, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(7))

        assertEquals(
            "non-null tile count must not change",
            board.grid.sumOf { row -> row.count { it != null } },
            result.grid.sumOf { row -> row.count { it != null } },
        )
    }

    @Test
    fun `reshuffle actually changes the board`() {
        // 防退化：如果洗牌实现有 bug（例如忘了写回），棋盘会原样返回，
        // 而 hasValidMove 仍然是 false —— 这时上面的「resolves deadlock」
        // 会失败，但加这条能更早定位问题。
        val board = deadlockBoard()
        val (result, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(42))

        assertNotEquals("reshuffled board must differ from the deadlocked one", board, result)
    }

    @Test
    fun `reshuffle is deterministic for a fixed seed`() {
        val board = deadlockBoard()
        val (first, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(123))
        val (second, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(123))

        assertEquals("same seed must produce the same board", first, second)
    }

    @Test
    fun `different seeds produce different reshuffles`() {
        val board = deadlockBoard()
        val (a, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(1))
        val (b, _) = DeadlockEngine.reshuffleIfDeadlocked(board, Random(999))

        assertNotEquals("different seeds should not collide", a, b)
    }

    @Test
    fun `reshuffle gives up gracefully on a single-type board`() {
        // 全盘同一种寿司：已成立无数三连，洗牌永远洗不出合法局面。
        // 必须优雅退出（返回 false），不能死循环也不能抛异常。
        val mono = Board(
            size = 7,
            grid = List(7) { row -> List<SushiTile?>(7) { col -> tile(row, col, SushiType.SUSHI1) } },
        )

        // 前提：全同色盘必然有解（任意交换后三连依旧成立），
        // 所以它根本进不到重排分支 —— 这里断言的是「不会误判」。
        val (result, didReshuffle) = DeadlockEngine.reshuffleIfDeadlocked(mono, Random(5))

        assertFalse("mono board has matches so it is not a deadlock", didReshuffle)
        assertEquals("must return the board untouched", mono, result)
    }

    private fun typeHistogram(board: Board): Map<SushiType, Int> =
        board.grid
            .flatten()
            .filterNotNull()
            .groupingBy { it.type }
            .eachCount()

    // ========================================================================
    // forceDeadlock（调试入口的唯一依据）
    // ========================================================================

    @Test
    fun `forceDeadlock produces a genuinely deadlocked board`() {
        val board = DeadlockEngine.forceDeadlock(BoardEngine.generateInitialBoard())

        assertTrue(
            "forceDeadlock 生成的棋盘必须真的是死局 —— 调试入口全靠它，" +
                "它不准就等于入口在骗人",
            DeadlockEngine.isDeadlock(board),
        )
    }

    @Test
    fun `forceDeadlock board has no pre-existing matches`() {
        val board = DeadlockEngine.forceDeadlock(BoardEngine.generateInitialBoard())

        assertTrue(
            "不能有已成立三连，否则一造出来就自动消除，玩家看不到死局",
            MatchEngine.detectMatches(board).isEmpty(),
        )
    }

    @Test
    fun `forceDeadlock preserves tile ids and positions`() {
        val original = BoardEngine.generateInitialBoard()
        val forced = DeadlockEngine.forceDeadlock(original)

        assertEquals(
            "id 必须原样保留 —— Compose 用 id 做 key，换了会触发全盘重建",
            original.grid.flatten().filterNotNull().map { it.id },
            forced.grid.flatten().filterNotNull().map { it.id },
        )

        forced.grid.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, tile ->
                if (tile != null) {
                    assertEquals("tile.row 必须与所在位置一致", row, tile.row)
                    assertEquals("tile.col 必须与所在位置一致", col, tile.col)
                }
            }
        }
    }

    @Test
    fun `forceDeadlock output can be reshuffled back to a solvable board`() {
        val forced = DeadlockEngine.forceDeadlock(BoardEngine.generateInitialBoard())
        val (settled, didReshuffle) = DeadlockEngine.reshuffleIfDeadlocked(forced, Random(7))

        assertTrue("forceDeadlock 的产物必须能被重排救回来", didReshuffle)
        assertTrue("重排后必须有解", DeadlockEngine.hasValidMove(settled))
        assertTrue(
            "重排后不能有已成立三连",
            MatchEngine.detectMatches(settled).isEmpty(),
        )
    }
}
