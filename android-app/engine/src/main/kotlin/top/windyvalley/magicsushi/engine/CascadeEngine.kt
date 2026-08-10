package top.windyvalley.magicsushi.engine

import kotlin.random.Random

/**
 * CascadeEngine.kt — recursive cascade / chained-elimination driver.
 *
 * **Pure Kotlin, ZERO Android dependencies.** Depends only on [Models.kt],
 * [MatchEngine], and [GravityEngine]. Compiled and unit-tested without an
 * Android device.
 *
 * ---
 * ## Responsibilities (T-CORE-004)
 *
 * 1. Given a [Board] and an initial set of [Match]es (the matches produced
 *    by the first round of elimination after a swap), iterate:
 *    - **eliminate** (the matches passed in are already eliminated on the
 *      input board — they are conceptually "marked" / about to vanish),
 *    - **fall** ([GravityEngine.applyGravity] nulls out the matched cells
 *      and lets non-null tiles in each column drop to the bottom),
 *    - **detect** ([MatchEngine.detectMatches] scans the new board for any
 *      fresh 3-in-a-row formed by the fall).
 *    Repeat until the detect step returns an empty list (no new matches).
 * 2. Return every cascade round (in chronological order) plus the **final**
 *    board (after the last fall, with or without matches — depends on which
 *    step the loop ended on).
 * 3. Do NOT spawn new tiles. The top-of-column `null`s that gravity leaves
 *    behind stay `null`; the spawner / refiller is a separate engine
 *    (a future `BoardEngine.spawnRefill` task — out of scope here).
 *
 * ## Algorithm
 *
 * ```
 * currentBoard  = board
 * currentMatches = initialMatches
 * cascades = empty list
 * if (initialMatches.isEmpty()) return (empty cascades, board)   // fast-path
 * cascades.add(initialMatches)
 *
 * repeat up to MAX_CASCADE_ITERATIONS times:
 *     currentBoard   = GravityEngine.applyGravity(currentBoard, currentMatches)
 *     currentMatches = MatchEngine.detectMatches(currentBoard)
 *     if (currentMatches.isEmpty()) break   // stable — no more chain
 *     cascades.add(currentMatches)
 * ```
 *
 * The loop body is **detect-after-fall**: we first gravity-fall the cells
 * that were just matched, then ask MatchEngine whether the new layout
 * forms any 3-in-a-row. This ordering matters — we never re-detect on a
 * board whose eliminated cells are still on it.
 *
 * ## Termination guarantee
 *
 * Two ways the loop ends:
 *   1. **Steady state:** the detect step returns an empty list — the board
 *      has no fresh matches. Most common.
 *   2. **Iteration cap:** the loop hits [MAX_CASCADE_ITERATIONS]. This is
 *      a dead-loop guard; under normal play a chain tops out around 2-3
 *      rounds on a 7×7 board (because there is no spawner, the S1 cells
 *      are finite and the chain dies as soon as one cascade round clears
 *      them all). 20 is a comfortable safety margin.
 *
 * The cap does **not** mean the chain is over — the caller must decide
 * what to do if `cascades.size == MAX_CASCADE_ITERATIONS` (typically: log
 * a warning, render a final fall, and re-detect one more time as a
 * post-loop safety pass). We expose the cap via [MAX_CASCADE_ITERATIONS]
 * so the caller can compare.
 *
 * ## Edge cases
 *
 * - **Empty `initialMatches`** → the result cascades list is empty and the
 *    returned `finalBoard` is the **input** `board` by reference. We do
 *    NOT call [GravityEngine.applyGravity] in this case (the engine's own
 *    fast-path would also identity-return, but skipping the call is
 *    cheaper and makes the contract explicit).
 * - **Single round (no chain)** → cascades.size == 1, finalBoard is the
 *    board after one gravity pass. This is the most common path on a
 *    normal swap.
 * - **Chain length == MAX_CASCADE_ITERATIONS** → we hit the safety cap.
 *    The caller can detect this and decide whether to keep going.
 * - **Empty board + empty matches** → trivial: empty cascades, identity
 *    board.
 *
 * ## Out of scope (handled by sibling engines)
 *
 * - **Scoring** — `ScoreEngine` consumes the `cascades` list (each
 *    cascade round is one combo increment; longer chains score higher).
 * - **Single-round match detection** — [MatchEngine] (this engine is just
 *    its driver).
 * - **Single-round gravity** — [GravityEngine] (this engine is just its
 *    driver).
 * - **Top-of-column refill** — a future spawner; the post-gravity `null`
 *    cells are left as-is and the caller is expected to refill them
 *    BEFORE the next match detection if needed. **For the MVP
 *    cascade-loop, the fall step is what produces the chain — falling
 *    tiles landing on top of other tiles does not create `null` cells
 *    in the middle; the only new `null`s appear at the top of each
 *    column, where no match can form (3-in-a-row needs 3 non-null
 *    cells in a line).** So leaving the top nulls is safe for the
 *    cascade itself; the spawner only matters for the *next* player
 *    action, not for the chain we are computing.
 * - **Timer** (`TimerEngine`) — unrelated to chain detection.
 * - **Board** (`BoardEngine`) — does the swap that *starts* a chain; the
 *    cascade engine is downstream of the swap.
 */
object CascadeEngine {

    /**
     * Hard upper bound on chain length. Defensive guard against pathological
     * boards that never reach a steady state (e.g. a bug elsewhere that
     * re-introduces matches after each fall). 20 is well above any real
     * play chain and prevents an infinite loop.
     */
    const val MAX_CASCADE_ITERATIONS = 20

    /**
     * Run the cascade loop: starting from [board] with the given
     * [initialMatches] (the matches produced by the first elimination
     * round, typically right after a swap), iterate fall → detect until
     * no new matches form, capped at [MAX_CASCADE_ITERATIONS] iterations.
     *
     * @param board          the current board state. Not mutated; each
     *                       fall step allocates a fresh grid via
     *                       [GravityEngine.applyGravity].
     * @param initialMatches the matches to eliminate first. May be empty
     *                       (in which case the result is trivial: empty
     *                       cascades, identity board, no Gravity call).
     *                       When non-empty, may contain duplicate tile
     *                       references (L-shape intersections) —
     *                       [GravityEngine.applyGravity] handles this
     *                       gracefully.
     * @return               a [CascadeResult] containing:
     *                       - `cascades`: every cascade round in
     *                         chronological order. `cascades[0]` ==
     *                         `initialMatches` (when non-empty);
     *                         `cascades[last]` is the most recent match
     *                         round. Empty if `initialMatches` was
     *                         empty.
     *                       - `finalBoard`: the board after the last
     *                         gravity pass. When the loop ended on
     *                         "steady state" (no new matches), the
     *                         `null`s in `finalBoard.grid` are the
     *                         post-fall top-of-column gaps awaiting
     *                         refill; when the loop ended on the
     *                         iteration cap, the `null`s are
     *                         post-elimination of the **last** cascade
     *                         round (i.e. one fall step **after** the
     *                         last detected matches; the caller should
     *                         not re-apply gravity on this board).
     */
    fun cascadeUntilStable(
        board: Board,
        initialMatches: List<Match>,
        rng: Random = Random.Default,
    ): CascadeResult {
        // Fast-path: no initial matches → no chain at all. Return
        // identity board (no Gravity call — explicit & cheap).
        if (initialMatches.isEmpty()) {
            return CascadeResult(cascades = emptyList(), finalBoard = board, rounds = emptyList())
        }

        val cascades: MutableList<List<Match>> = mutableListOf()
        cascades.add(initialMatches)

        // 每轮的重力/补充快照，供动画层复用（见循环内的说明）。
        val rounds: MutableList<CascadeRound> = mutableListOf()

        var currentBoard: Board = board
        var currentMatches: List<Match> = initialMatches

        // Cascade loop: fall → detect, repeat until stable or capped.
        // `for (i in 0 until MAX_CASCADE_ITERATIONS)` is structurally
        // equivalent to `repeat(MAX_CASCADE_ITERATIONS) { ... }` and
        // makes the iteration bound self-documenting.
        for (i in 0 until MAX_CASCADE_ITERATIONS) {
            // Step A: fall, then refill.
            //
            // 补充现在是显式的一步。
            // 此前 applyGravity 的 `doRefill` 默认 true，这里靠隐式行为
            // 拿到补满的棋盘 —— 契约藏在默认参数里，读这段代码看不出
            // "新 tile 参与了下一轮检测"这个关键事实。
            //
            // 补充必须发生在 detect 之前：新落下的 tile 有资格构成新的
            // 连线，这正是 cascade 连锁的来源。
            currentBoard = GravityEngine.applyGravity(currentBoard, currentMatches)
            val fallenSnapshot = currentBoard
            currentBoard = BoardEngine.spawnRefill(currentBoard, rng)

            // 把本轮「落下后」「补充后」两张快照留档。
            //
            // 动画层过去自己再调一次 applyGravity + spawnRefill 来生成帧，
            // 那是**第二次**随机采样 —— 掉下来的寿司与最终落定的不是同一批
            // （id 和 type 都不同，因为 TileIdGenerator 全局自增、
            // SushiType.random 又各摇一次）。
            //
            // 现在补充结果由本引擎单点产出、动画层直接消费，从结构上排除
            // 不一致的可能，而不是试图让两次随机「碰巧相同」。
            rounds.add(CascadeRound(fallen = fallenSnapshot, refilled = currentBoard))

            // Step B: detect. If no new matches, the chain is over.
            currentMatches = MatchEngine.detectMatches(currentBoard)
            if (currentMatches.isEmpty()) {
                // Stable. finalBoard is the post-fall board with no
                // further matches.
                break
            }
            // Step C: record this cascade round.
            cascades.add(currentMatches)
            // Loop continues. Next iteration will fall the just-detected
            // matches and re-detect.
        }

        return CascadeResult(
            cascades = cascades.toList(),
            finalBoard = currentBoard,
            rounds = rounds.toList(),
        )
    }
}

/**
 * Result of [CascadeEngine.cascadeUntilStable].
 *
 * @property cascades   the cascade rounds, in chronological order.
 *                      `cascades[0]` is the initial matches (when
 *                      non-empty); `cascades[last]` is the last
 *                      elimination round in the chain. Empty if and
 *                      only if the input `initialMatches` was empty.
 *                      Length is in `1..MAX_CASCADE_ITERATIONS`
 *                      inclusive (the loop always runs at least one
 *                      round when `initialMatches` is non-empty; it
 *                      terminates as soon as detect returns empty, so
 *                      the length is `min(trueChainLen,
 *                      MAX_CASCADE_ITERATIONS)`).
 * @property finalBoard the board after the last gravity pass in the
 *                      loop. When the chain ended on steady state
 *                      (no new matches), the `null` cells in this
 *                      board are the post-fall top-of-column gaps;
 *                      when the chain ended on the iteration cap,
 *                      the `null` cells are post-elimination of the
 *                      **last** cascade round (one gravity pass after
 *                      the last detected matches — i.e. the matches in
 *                      `cascades.last()` have already been swept off
 *                      this board).
 */
data class CascadeResult(
    val cascades: List<List<Match>>,
    val finalBoard: Board,
    /**
     * 每一轮的重力 / 补充快照，与 [cascades] **一一对应且等长**。
     *
     * 存在的唯一目的是让动画层复用本引擎算出的补充结果，而不是自己再摇
     * 一次随机（那会让掉下来的寿司与落定的不是同一批）。详见 [CascadeRound]。
     */
    val rounds: List<CascadeRound>,
)

/**
 * 一个 cascade round 的两张中间快照。
 *
 * ## 为什么必须由 [CascadeEngine] 产出而不是动画层自己算
 *
 * `BoardEngine.spawnRefill` 每次调用都会**重新随机**：`SushiType.random(rng)`
 * 摇新类型，`TileIdGenerator.next()` 发新 id。所以「算两次」必然得到两批
 * 不同的 tile。
 *
 * 早期实现里 `CascadeEngine` 算一次（用于 `finalBoard`），`CascadeAnimator`
 * 又算一次（用于生成动画帧），于是玩家看到的是：**掉进格子的寿司和最后
 * 停在那里的寿司不是同一个**。每次消除都会发生，不限于连锁。
 *
 * 修法不是「设法让两次随机一致」（同一个 rng 也要求调用次数与顺序完全
 * 对齐，脆弱且难维护），而是**只算一次、把结果传下去** —— 单一数据源。
 *
 * @property fallen   本轮重力落下后、**尚未补充**的棋盘。顶部空洞正是
 *                    SpawnIn 帧要填的格子。
 * @property refilled 本轮补充完成的棋盘。飞进来的 tile 的真实 id 与 type
 *                    取自这里，与 `finalBoard` 同源。
 */
data class CascadeRound(
    val fallen: Board,
    val refilled: Board,
)
