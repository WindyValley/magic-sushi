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
     * 重排的结果：新棋盘 + 每个格子的内容从哪来。
     *
     * @property board       重排后的棋盘。
     * @property didReshuffle 是否真的重排了（原本有解时为 false）。
     * @property origin      `(目标行,目标列) -> (来源行,来源列)`。
     *
     *                       UI 靠它算每个寿司的移动轨迹。只包含真正移动了的
     *                       格子 —— 原地不动的不进这个表，省得 UI 为一堆
     *                       零位移的 tile 建动画状态。
     *
     *                       [didReshuffle] 为 false 时必然为空。
     */
    data class ReshuffleResult(
        val board: Board,
        val didReshuffle: Boolean,
        val origin: Map<Pair<Int, Int>, Pair<Int, Int>>,
    )

    /**
     * 死局则重排，否则原样返回。
     *
     * ## 为什么要追踪来源
     *
     * 初版只洗 `type`（tile 实体不动），信息不足以画移动动画：同类型的寿司
     * 有好几个，「(3,4) 现在这个 5 号是从哪来的」根本无法回答 —— 原本 (0,0)
     * 和 (6,6) 可能都是 5 号。
     *
     * 现在洗的是**格子内容的置换**：先给每个非空格编号，洗号码，于是
     * 「新的 i 号位置装的是原来的 perm[i] 号位置的内容」，来源唯一确定。
     *
     * 类型分布不变量仍然成立 —— 置换只是重新分配同一批内容。
     */
    fun reshuffleIfDeadlocked(
        board: Board,
        rng: Random = Random.Default,
    ): ReshuffleResult {
        if (hasValidMove(board)) {
            return ReshuffleResult(board, didReshuffle = false, origin = emptyMap())
        }

        repeat(MAX_RESHUFFLE_ATTEMPTS) {
            // 纯随机洗牌撞上三连的概率不低（类型越少越容易撞），所以洗完先
            // 做一轮局部修复：把造成三连的格子与随机位置对调，直到无三连。
            // 比「整盘重洗到碰巧合法」收敛快一个量级。
            val shuffled = shuffleWithOrigin(board, rng)
            val repaired = repairMatchesTracked(shuffled, rng)

            // 双条件：不能有已成立的三连（否则玩家没操作就自动消除加分），
            // 且必须有解（否则重排了还是死局，白搭）。
            if (repaired != null && hasValidMove(repaired.board)) {
                return ReshuffleResult(
                    board = repaired.board,
                    didReshuffle = true,
                    // 只留真正动了的格子。
                    origin = repaired.origin.filterKeys { key ->
                        repaired.origin[key] != key
                    },
                )
            }
        }

        // 兜底：洗不出合法局面。返回原棋盘 + false，让调用方按「未重排」处理 ——
        // 宁可维持死局让倒计时结束，也不返回一个有三连的棋盘造成自动连锁。
        return ReshuffleResult(board, didReshuffle = false, origin = emptyMap())
    }

    /**
     * 洗牌中间态：棋盘 + 每格内容的来源。
     *
     * 只在重排流程内部流转，对外暴露的是 [ReshuffleResult]。
     */
    private data class TrackedBoard(
        val board: Board,
        /** `(目标行,目标列) -> (来源行,来源列)`，含原地不动的格子。 */
        val origin: Map<Pair<Int, Int>, Pair<Int, Int>>,
    )

    /**
     * 消掉洗牌产生的已成立三连，同时维护来源映射。
     *
     * 做法：找到任意一个匹配里的一个格子，与棋盘上随机另一格**对调类型**。
     * 对调而非改写，所以类型总数不变 —— 这是 [reshuffleIfDeadlocked] 的
     * 分布不变量所依赖的关键性质。
     *
     * ## 为什么对调时也要换来源
     *
     * 修复是「把 A 格和 B 格的内容互换」。若只换 type 不换 origin，来源映射
     * 就会说谎 —— UI 会让寿司飞向错误的起点，动画看着像随机乱窜。
     *
     * @return 修好的棋盘 + 来源；若 [REPAIR_ROUNDS] 轮内没能消完三连则返回
     *         `null`（交给调用方重洗）
     */
    private fun repairMatchesTracked(tracked: TrackedBoard, rng: Random): TrackedBoard? {
        var current = tracked.board
        val origin = tracked.origin.toMutableMap()

        repeat(REPAIR_ROUNDS) {
            val matches = MatchEngine.detectMatches(current)
            if (matches.isEmpty()) return TrackedBoard(current, origin)

            // 取第一个匹配的中间那块 —— 中间格参与的连线最多，
            // 换掉它比换端点更容易一次打断整条线。
            val victim = matches.first().tiles.let { it[it.size / 2] }

            // 随机挑一个类型不同的格子对调。同类型对调等于没动，白费一轮。
            val partner = randomCellWithDifferentType(current, victim.type, rng)
                ?: return null // 全盘只剩一种类型，无从修复

            // 实体互换：两个 tile 交换所在格，各自的 row/col 跟着更新。
            //
            // ⚠️ 不能用 withTypesSwapped —— 那个只换 type、不动实体，会破坏
            // shuffleWithOrigin 刚建立的「tile 带身份搬家」语义：id 留在原格
            // 而 type 飞走，Compose 那边就变成两个 tile 各自原地换脸，
            // 移动动画彻底失效。
            current = current.withTilesSwapped(
                victim.row, victim.col,
                partner.row, partner.col,
            )

            // 内容互换了，来源也必须跟着换 —— 否则映射与实际不符，
            // UI 会让寿司飞向错误的起点。
            val vKey = victim.row to victim.col
            val pKey = partner.row to partner.col
            val vOrigin = origin[vKey]
            val pOrigin = origin[pKey]
            if (vOrigin != null && pOrigin != null) {
                origin[vKey] = pOrigin
                origin[pKey] = vOrigin
            }
        }

        // 轮数耗尽仍有三连 —— 返回 null 触发重洗。
        return if (MatchEngine.detectMatches(current).isEmpty()) {
            TrackedBoard(current, origin)
        } else {
            null
        }
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
     * Fisher-Yates 洗牌，**tile 实体带着自己的身份搬家**。
     *
     * ## 为什么是实体搬家，不是洗类型
     *
     * 初版洗的是 `type`：id 与位置不动，只重新分配类型。那个模型画不出移动
     * 动画，有两个原因：
     *
     * 1. **来源无法确定** —— 同类型寿司有好几个。原本 (0,0) 和 (6,6) 都是
     *    5 号，现在 (3,4) 是 5 号，它从哪来？答不出来就没有起点。
     *
     * 2. **语义不对** —— `GameCanvas` 用 `key(tileId)`。id 不动而 type 变，
     *    在 Compose 看来是「同一个 tile 原地换了张脸」，压根没有位移这回事。
     *
     * 实体搬家把语义摆正了：tile 保留自己的 id，`row`/`col` 更新为新位置。
     * Compose 看到的是「同一个 tile 挪到了别处」—— 与 [TileAnim.Falling]
     * 完全一致的语义，下落也正是同一个 tile 换 row。
     *
     * ## 不变量
     *
     * - **类型分布不变**：置换只是重新分配同一批 tile，没有生成或销毁。
     * - **id 集合不变**：搬家不改 id，只是 id 出现在了别的格子里。
     * - **`tile.row`/`tile.col` 与所在格一致**：这是 engine 的既有约定，
     *   `MatchEngine` 等下游依赖它。搬完必须 `copy` 更新，不能只挪引用。
     *
     * @return 新棋盘 + `(目标格 -> 来源格)` 映射，UI 靠它算移动轨迹
     */
    private fun shuffleWithOrigin(board: Board, rng: Random): TrackedBoard {
        // 收集所有非空格的坐标。空格（若有）不参与洗牌。
        val cells = mutableListOf<Pair<Int, Int>>()
        for (row in board.grid.indices) {
            for (col in board.grid[row].indices) {
                if (board.grid[row][col] != null) cells += row to col
            }
        }

        // perm[i] = i 先建恒等置换，再 Fisher-Yates 打乱。
        // 洗「格子编号」而非「类型列表」是这次重写的核心 —— 编号唯一，
        // 所以「新的 i 号格装的是原来 perm[i] 号格那个 tile」来源确定。
        val perm = IntArray(cells.size) { it }
        for (i in perm.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]
            perm[i] = perm[j]
            perm[j] = tmp
        }

        val newGrid = board.grid.map { it.toMutableList() }
        val origin = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()

        cells.forEachIndexed { index, target ->
            val source = cells[perm[index]]
            val movingTile = board.grid[source.first][source.second] ?: return@forEachIndexed

            // 整个 tile 搬过来：id 与 type 跟着走，row/col 改成新位置。
            //
            // ⚠️ 必须更新 row/col —— engine 里 tile 自带坐标，下游（MatchEngine
            // 的匹配收集、CascadeEngine 的重力计算）都读 tile.row/col 而非
            // 遍历下标。只挪引用不改坐标，会得到一个「自称在 (0,0) 却躺在
            // (3,4) 格里」的 tile，后续判定全乱。
            newGrid[target.first][target.second] = movingTile.copy(
                row = target.first,
                col = target.second,
            )
            origin[target] = source
        }

        return TrackedBoard(
            board = board.copy(grid = newGrid.map { it.toList() }),
            origin = origin,
        )
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
     * 返回一个把 `(r1,c1)` 和 `(r2,c2)` 的 **tile 实体**互换后的棋盘。
     *
     * 与 [withTypesSwapped] 的区别：这个搬实体（id 跟着走，row/col 更新），
     * 那个只换 type（id 留在原格）。
     *
     * - 重排修复用这个 —— 必须维持「tile 带身份搬家」语义
     * - 死局检测用 [withTypesSwapped] —— 那是只读探测，只关心
     *   [MatchEngine.detectMatches] 的结果，不需要身份正确，换 type 更省
     *
     * 两个函数看着像重复，实际语义不同，别合并。
     */
    private fun Board.withTilesSwapped(
        r1: Int, c1: Int,
        r2: Int, c2: Int,
    ): Board {
        val tile1 = grid[r1][c1] ?: return this
        val tile2 = grid[r2][c2] ?: return this

        val newGrid = grid.toMutableList()

        if (r1 == r2) {
            // 同一行：一次重建即可。
            val row = newGrid[r1].toMutableList()
            row[c1] = tile2.copy(row = r1, col = c1)
            row[c2] = tile1.copy(row = r2, col = c2)
            newGrid[r1] = row
        } else {
            val row1 = newGrid[r1].toMutableList()
            row1[c1] = tile2.copy(row = r1, col = c1)
            newGrid[r1] = row1

            val row2 = newGrid[r2].toMutableList()
            row2[c2] = tile1.copy(row = r2, col = c2)
            newGrid[r2] = row2
        }

        return copy(grid = newGrid)
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
