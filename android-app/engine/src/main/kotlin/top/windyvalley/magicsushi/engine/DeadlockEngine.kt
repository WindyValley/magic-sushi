package top.windyvalley.magicsushi.engine

import kotlin.random.Random

/**
 * 死局检测与自动重排。
 *
 * ## 什么是死局
 *
 * 棋盘上不存在任何一次「交换相邻两格」能凑出三连 —— 玩家无论怎么点都消不掉，
 * 只能干等倒计时归零。这不是难度，是卡死。
 *
 * ## 为什么 v1.0.0 没有这个问题的兜底
 *
 * [BoardEngine.generateInitialBoard] 只保证**开局无三连**（FR-1.3），
 * 不保证**开局有解**。后者是更强的条件：无三连是「当前没有已成立的匹配」，
 * 有解是「存在一步能造出匹配」。7×7 六种寿司下死局概率低但非零，
 * 且每次消除后的 [BoardEngine.spawnRefill] 都是一次新的随机填充，
 * 同样可能填出死局。
 *
 * ## 检测方法
 *
 * 枚举所有相邻对（横向 + 纵向，共 `2 × size × (size - 1)` 对），
 * 对每对做一次**纯类型交换**后跑 [MatchEngine.detectMatches]。
 * 任意一对交换后有匹配 → 非死局，立即短路返回。
 *
 * 刻意不走 [BoardEngine.attemptSwap]：那个函数带 [Board.swapLock] 检查、
 * 会产生 [SwapResult]、并且成功时返回的是已交换的棋盘 —— 都是副作用。
 * 死局检测是**只读探测**，不该受锁状态影响，也不该留下痕迹。
 *
 * ## 重排策略
 *
 * Fisher-Yates 洗牌**现有 tile 的类型序列**，位置不变、tile id 不变。
 * 这保证两条不变量：
 *
 *  1. **类型分布不变** —— 洗牌只是重排列，不生成新类型。玩家看到的还是
 *     那些寿司，只是位置换了，不会突然多出一种没见过的。
 *  2. **tile id 不变** —— id 绑在格子上而非类型上。Compose 用 id 做
 *     `key`，id 稳定则重排表现为「同一个格子换了内容」的原地动画，
 *     而不是 49 个 tile 全部销毁重建。
 *
 * 洗完检查两件事：无已成立三连（否则重排瞬间自动消除，玩家没操作就加分）、
 * 且有解。不满足就重洗，上限 [MAX_RESHUFFLE_ATTEMPTS] 次。
 */
object DeadlockEngine {

    /**
     * 重排尝试上限。
     *
     * 单次洗牌产出合法局面（无三连且有解）的概率很高，实测 7×7 六种寿司下
     * 首次成功率 > 90%。给 64 次是极宽裕的余量 —— 连续 64 次失败的概率
     * 小到可以认为是类型分布本身病态（例如全盘只剩一种寿司），
     * 那种情况下重排本就无解，兜底交给调用方。
     */
    const val MAX_RESHUFFLE_ATTEMPTS = 64

    /**
     * 单次洗牌后修复三连的最大轮数。
     *
     * 每轮打断一条线。7×7 洗牌后通常只有 0-3 条三连，32 轮是宽裕余量；
     * 超出则说明这次洗牌的分布很差，重洗比继续修更快。
     */
    private const val REPAIR_ROUNDS = 32

    /**
     * 棋盘是否存在至少一个可行交换。
     *
     * @return `true` = 有解（非死局）；`false` = 死局
     *
     * 只读函数，不修改 [board]。空格（`null`）会被跳过 —— 下落动画进行中的
     * 棋盘可能有空格，此时检测结果无意义，调用方应在棋盘稳定后再调。
     */
    fun hasValidMove(board: Board): Boolean {
        val n = board.size

        for (row in 0 until n) {
            for (col in 0 until n) {
                // 只向右和向下试，避免把每对相邻格检测两次。
                if (col + 1 < n && swapCreatesMatch(board, row, col, row, col + 1)) return true
                if (row + 1 < n && swapCreatesMatch(board, row, col, row + 1, col)) return true
            }
        }
        return false
    }

    /**
     * 死局的反面，语义糖。见 [hasValidMove]。
     */
    fun isDeadlock(board: Board): Boolean = !hasValidMove(board)

    /**
     * 交换 `(r1,c1)` 与 `(r2,c2)` 的类型后，棋盘上是否出现三连。
     *
     * 只交换 [SushiTile.type]，不动 id / row / col —— 探测用的临时棋盘
     * 不需要维护这些字段的一致性，[MatchEngine.detectMatches] 只看 type 和位置。
     */
    private fun swapCreatesMatch(board: Board, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val a = board.grid[r1][c1] ?: return false
        val b = board.grid[r2][c2] ?: return false

        // 同类型交换等于没换，不可能产生新匹配 —— 提前短路省一次全盘扫描。
        if (a.type == b.type) return false

        val probe = board.withTypesSwapped(r1, c1, r2, c2, a.type, b.type)
        return MatchEngine.detectMatches(probe).isNotEmpty()
    }

    /**
     * 若 [board] 是死局则重排，否则原样返回。
     *
     * @param rng 注入随机源，测试可传固定 seed 复现
     * @return `(新棋盘, 是否发生了重排)`
     *
     * 返回的 boolean 是给 UI 用的：`true` 时需要提示玩家「局面无解，已重排」，
     * 否则玩家会看到棋盘无故跳变。
     */
    fun reshuffleIfDeadlocked(
        board: Board,
        rng: Random = Random.Default,
    ): Pair<Board, Boolean> {
        if (hasValidMove(board)) return board to false

        repeat(MAX_RESHUFFLE_ATTEMPTS) {
            // 纯随机洗牌撞上三连的概率不低（类型越少越容易撞），所以洗完先
            // 做一轮局部修复：把造成三连的格子与随机位置对调，直到无三连。
            // 比「整盘重洗到碰巧合法」收敛快一个量级。
            val candidate = repairMatches(shuffleTypes(board, rng), rng)

            // 双条件：不能有已成立的三连（否则玩家没操作就自动消除加分），
            // 且必须有解（否则重排了还是死局，白搭）。
            if (candidate != null && hasValidMove(candidate)) {
                return candidate to true
            }
        }

        // 兜底：洗不出合法局面。返回原棋盘 + false，让调用方按「未重排」处理 ——
        // 宁可维持死局让倒计时结束，也不返回一个有三连的棋盘造成自动连锁。
        return board to false
    }

    /**
     * 消掉洗牌产生的已成立三连，保持类型分布不变。
     *
     * 做法：找到任意一个匹配里的一个格子，与棋盘上随机另一格**对调类型**。
     * 对调而非改写，所以类型总数不变 —— 这是 [reshuffleIfDeadlocked] 的
     * 分布不变量所依赖的关键性质。
     *
     * @return 修好的棋盘；若 [REPAIR_ROUNDS] 轮内没能消完三连则返回 `null`
     *         （交给调用方重洗）
     */
    private fun repairMatches(board: Board, rng: Random): Board? {
        var current = board

        repeat(REPAIR_ROUNDS) {
            val matches = MatchEngine.detectMatches(current)
            if (matches.isEmpty()) return current

            // 取第一个匹配的中间那块 —— 中间格参与的连线最多，
            // 换掉它比换端点更容易一次打断整条线。
            val victim = matches.first().tiles.let { it[it.size / 2] }

            // 随机挑一个类型不同的格子对调。同类型对调等于没动，白费一轮。
            val partner = randomCellWithDifferentType(current, victim.type, rng)
                ?: return null // 全盘只剩一种类型，无从修复

            current = current.withTypesSwapped(
                victim.row, victim.col,
                partner.row, partner.col,
                victim.type, partner.type,
            )
        }

        // 轮数耗尽仍有三连 —— 返回 null 触发重洗。
        return if (MatchEngine.detectMatches(current).isEmpty()) current else null
    }

    /**
     * 随机返回一个类型不等于 [excludeType] 的格子，全盘同类时返回 `null`。
     */
    private fun randomCellWithDifferentType(
        board: Board,
        excludeType: SushiType,
        rng: Random,
    ): SushiTile? {
        val candidates = board.grid.flatten().filterNotNull().filter { it.type != excludeType }
        return if (candidates.isEmpty()) null else candidates[rng.nextInt(candidates.size)]
    }

    /**
     * Fisher-Yates 洗牌棋盘上所有非空 tile 的类型。
     *
     * 位置与 tile id 保持不变，只有 [SushiTile.type] 被重新分配。
     */
    private fun shuffleTypes(board: Board, rng: Random): Board {
        // 收集所有非空格的坐标与类型。
        val cells = mutableListOf<Pair<Int, Int>>()
        val types = mutableListOf<SushiType>()

        for (row in board.grid.indices) {
            for (col in board.grid[row].indices) {
                val tile = board.grid[row][col] ?: continue
                cells += row to col
                types += tile.type
            }
        }

        // Fisher-Yates：从后往前，每步与前面（含自己）的随机位置交换。
        for (i in types.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = types[i]
            types[i] = types[j]
            types[j] = tmp
        }

        // 按洗好的顺序回填。
        val newGrid = board.grid.map { it.toMutableList() }
        cells.forEachIndexed { index, (row, col) ->
            val tile = newGrid[row][col] ?: return@forEachIndexed
            newGrid[row][col] = tile.copy(type = types[index])
        }

        return board.copy(grid = newGrid.map { it.toList() })
    }

    /**
     * 构造一个必然死局的棋盘，**仅供调试验证使用**。
     *
     * 自然玩法下死局概率极低（6 种寿司 7×7），靠运气可能玩很久都遇不到，
     * 没法验证重排逻辑的真机表现。这个函数提供确定性的触发方式。
     *
     * ## 为什么 2×2 分块图案必然死局
     *
     * ```
     *   A A B B A A B
     *   A A B B A A B
     *   C C D D C C D
     *   C C D D C C D
     *   A A B B A A B
     *   A A B B A A B
     *   C C D D C C D
     * ```
     *
     * 每种类型都以 2×2 块出现，任意方向最长同色连续段都是 2。交换任何一对
     * 相邻格，只能把某个 2 段拆成 1+1 或拼出另一个 2 段 —— 永远凑不到 3。
     *
     * 这不是猜测：`DeadlockEngineTest` 有一条用例直接对**本函数的输出**
     * 断言 `isDeadlock == true`，另一条断言它没有已成立三连。改坏了会红。
     *
     * ## 保留 tile id
     *
     * 只改 [SushiTile.type]，id 与位置沿用传入棋盘 —— 与
     * [reshuffleIfDeadlocked] 的不变量一致，Compose 不会因此重建 tile。
     *
     * @param board 用于取 id / 尺寸的基准棋盘
     */
    fun forceDeadlock(board: Board): Board {
        // 四种类型足够构成分块图案，多的用不上。
        val palette = listOf(
            SushiType.SUSHI1, SushiType.SUSHI2,
            SushiType.SUSHI3, SushiType.SUSHI4,
        )

        val grid = board.grid.mapIndexed { row, cells ->
            cells.mapIndexed { col, tile ->
                // (row/2, col/2) 的奇偶共同决定块的颜色 —— 保证上下、左右
                // 相邻的块都不同色，不会意外接出 3 连。
                val blockIndex = (row / 2 % 2) * 2 + (col / 2 % 2)
                tile?.copy(type = palette[blockIndex])
            }
        }

        return board.copy(grid = grid)
    }

    /**
     * 返回一个把 `(r1,c1)` 和 `(r2,c2)` 类型互换后的浅拷贝棋盘。
     *
     * 只重建受影响的两行，其余行沿用原引用 —— 死局检测要跑
     * `2 × 7 × 6 = 84` 次探测，每次都全盘深拷贝会产生可观的临时对象。
     */
    private fun Board.withTypesSwapped(
        r1: Int, c1: Int,
        r2: Int, c2: Int,
        typeAt1: SushiType,
        typeAt2: SushiType,
    ): Board {
        val newGrid = grid.toMutableList()

        if (r1 == r2) {
            // 同一行：一次重建即可，两个改动都落在这行。
            val row = newGrid[r1].toMutableList()
            row[c1] = row[c1]?.copy(type = typeAt2)
            row[c2] = row[c2]?.copy(type = typeAt1)
            newGrid[r1] = row
        } else {
            val row1 = newGrid[r1].toMutableList()
            row1[c1] = row1[c1]?.copy(type = typeAt2)
            newGrid[r1] = row1

            val row2 = newGrid[r2].toMutableList()
            row2[c2] = row2[c2]?.copy(type = typeAt1)
            newGrid[r2] = row2
        }

        return copy(grid = newGrid)
    }
}
