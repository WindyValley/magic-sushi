package top.windyvalley.magicsushi.engine

/**
 * AnimationEngine.kt — 3-phase animation frame generator for tile cascades.
 *
 * **Pure Kotlin, ZERO Android dependencies.** Depends only on [Models.kt],
 * [GravityEngine], and [BoardEngine].
 *
 * ---
 * ## Responsibilities
 *
 * Given a [Board] and the [Match]es eliminated in the current cascade
 * round, generate a **strictly ordered 3-frame animation sequence**:
 *
 * | Frame | Phase | Duration | Content |
 * |-------|-------|----------|---------|
 * | 0     | 1 — Fade Out  | 100 ms | Tiles in `matches` fade to alpha=0. Board unchanged otherwise. |
 * | 1     | 2 — Fall       | 100 ms | Surviving tiles slide to their post-gravity positions. |
 * | 2     | 3 — Spawn In   | 100 ms | New tiles fall in from above to fill empty cells. |
 *
 * **间歇（gap）帧不输出**：两个动画阶段之间的 100 ms 静止状态由
 * [GameViewModel] 的 `delay()` 调用在迭代帧序列之间插入，不需要 Engine 输出。
 *
 * ## Data model
 *
 * - [TileAnim] — per-tile animation intent: `FadingOut | Falling | SpawningIn | Stable`
 * - [CellKey]  — stable `(row, col)` identity key. Does NOT change when a tile
 *                moves; the tile that was at `(3, 4)` is still keyed as `(3, 4)`
 *                even after gravity moves it to `(4, 4)`.
 * - [TileRenderState] — what the UI should render at a given [CellKey]:
 *                       `tileId`, `type`, `alpha`, `offsetY` (fractional row
 *                       offset for falling animation), `scale`, `anim`.
 * - [AnimFrame] — `Map<CellKey, TileRenderState>` for one animation frame.
 *                 The map is sparse: only cells that should render something
 *                 non-default are present.
 *
 * ## Algorithm
 *
 * ```
 * frame0 (Phase 1 — Fade Out):
 *   For every tile in every Match:
 *     render(key=(row,col), alpha=0, anim=FadingOut)
 *   All other tiles: Stable, alpha=1.0, no offset.
 *
 * frame1 (Phase 2 — Fall):
 *   resultBoard = GravityEngine.applyGravity(board, matches)
 *   For every non-null tile on resultBoard:
 *     oldRow = tile.row    (pre-gravity position — captured before copy())
 *     newRow = tile.row    (post-gravity — from GravityEngine.copy())
 *     If oldRow != newRow:
 *       offsetY = oldRow - newRow  (positive = fell DOWN by this many rows)
 *       anim = Falling(oldRow, newRow)
 *     Else:
 *       offsetY = 0, anim = Stable
 *   (All tiles have alpha=1.0 in this frame.)
 *
 * frame2 (Phase 3 — Spawn In):
 *   For every null cell in resultBoard (top-of-column gaps after gravity):
 *     spawnFromRow = -1 - nullCountFromTop  (the Nth null from top → spawn from row -1-N)
 *     render(key=(row,col), offsetY = spawnFromRow, anim=SpawningIn(spawnFromRow))
 *   All non-null tiles: Stable, alpha=1.0, offsetY=0.
 * ```
 *
 * ## TileId lifecycle
 *
 * - Tiles that **survived** (not eliminated) carry their original `tile.id` as `tileId`
 *   through frames 0 → 1 → 2 (unchanged — same instance moves position).
 * - Tiles **eliminated** in frame 0 only appear in frames 0 and are absent from 1 and 2.
 * - **New tiles** (spawned in frame 2) 携带 `spawnRefill` 产出的**真实**
 *   `tile.id` 与真实 `type` —— 动画里飞进来的那一个，就是落定后留在该格
 *   的那一个。
 *
 *   早期实现在这里发负数 id 并随机编 type，造成两个问题：
 *   (a) 负数构成独立编号空间，和 `tile.id` 抢同一个 Compose key 槽位；
 *   (b) 计数器每次调用都从 -1 重置，跨轮次 key 会重复；
 *   (c) 随机 type 意味着飞入动画显示的寿司图案与落定结果无关。
 *   现在全 App 只有 [TileIdGenerator] 一个身份来源。
 *
 * ## Key design decisions
 *
 * 1. **GravityEngine is NOT modified** — it keeps its single-step `applyGravity`
 *    API and unit tests. AnimationEngine calls it internally to compute the
 *    post-fall layout.
 * 2. **BoardEngine.spawnRefill is NOT called** — AnimationEngine generates the
 *    spawn-in animation using the **null cells left by gravity** (not yet refilled).
 *    The final stable board (after frame 2) is the **input board** with those
 *    nulls visible (the ViewModel will replace it with the fully-refilled board
 *    once the animation sequence completes).
 * 3. **间歇不建模** — gaps between phases are handled by the caller's `delay()`.
 * 4. **Fractional offsetY** — `offsetY` is a `Float` in units of rows (not pixels).
 *    A tile falling from row 5 to row 6 has `offsetY = 1.0f`. Compose's
 *    `Modifier.offset(y = offsetY * cellSizePx)` renders the intermediate position.
 *
 * ## Out of scope
 *
 * - **Pixel-level interpolation** — handled by Compose `Animatable` in
 *   [SushiTile]. AnimationEngine outputs the **from** and **to** values;
 *   the UI drives the tween.
 * - **Multi-cascade chains** — one call to `generateFrames` handles exactly one
 *   cascade round. The caller is responsible for iterating over the
 *   `CascadeEngine.CascadeResult.cascades` list and calling this engine once
 *   per round.
 */
/**
 * One animation frame: a sparse map of what to render at each cell.
 * Cells absent from the map render as empty (null cell).
 */
typealias AnimFrame = Map<AnimationEngine.CellKey, AnimationEngine.TileRenderState>

object AnimationEngine {

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Generate the 3-frame animation sequence for one cascade round.
     *
     * @param board              Current board state (pre-elimination).
     * @param matches           Matches eliminated in this round.
     * @return                  Exactly 3 [AnimFrame] objects in order:
     *                          `[frameFadeOut, frameFall, frameSpawnIn]`.
     *                          Call this once per cascade round; do NOT feed
     *                          the output of one call as input to the next —
     *                          use the original `board` for each round.
     */
    fun generateFrames(
        board: Board,
        matches: List<Match>,
        fallenBoard: Board? = null,
        refilledBoard: Board? = null,
    ): List<AnimFrame> {
        if (matches.isEmpty()) {
            // No animation — all tiles stable at full alpha.
            return listOf(emptyStableFrame(board), emptyStableFrame(board), emptyStableFrame(board))
        }

        // Flatten all tiles that will be eliminated this round.
        val eliminatedTiles: Set<Int> = matches.flatMap { m -> m.tiles }.map { it.id }.toSet()

        // ------------------------------------------------------------------
        // Frame 0: Fade Out
        // ------------------------------------------------------------------
        val frameFadeOut = buildMap<CellKey, TileRenderState> {
            for (row in 0 until board.size) {
                for (col in 0 until board.size) {
                    val tile = board.grid[row][col] ?: continue
                    if (tile.id in eliminatedTiles) {
                        // This tile is being eliminated — fade it out.
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 0f,
                                offsetY = 0f,
                                scale = 1f,
                                anim = TileAnim.FadingOut,
                            ),
                        )
                    } else {
                        // Stable tile — full alpha, no animation.
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 1f,
                                offsetY = 0f,
                                scale = 1f,
                                anim = TileAnim.Stable,
                            ),
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Frame 1: Fall
        // ------------------------------------------------------------------
        // Ask GravityEngine where tiles end up after the fall.
        // applyGravity 只落不补，空洞保留下来供 SpawnIn 帧使用（P1-3）。
        //
        // 调用方（GameViewModel 的 cascade 循环）本来也要算一次
        // 同样的重力来推进到下一轮，此前两边各算一次 —— 不仅浪费，更危险的是
        // 两次调用的参数还可能漂移（VM 那次会额外跑 RNG 补 tile）。
        // 现在允许调用方把已算好的结果传进来复用，
        // 保证"动画依据的落点"与"下一轮起始棋盘"永远同源。
        val fallen = fallenBoard
            ?: GravityEngine.applyGravity(board, matches)

        // 索引体系收口：spawn tile 的真实身份来自补充后的棋盘。
        //
        // 早期实现在下面用 `nextSpawnId--` 发负数 id、`SushiType.entries
        // .random()` 现编类型 —— 于是动画里飞进来的寿司和之后真正落进棋盘
        // 的那个 tile 毫无关系（图案会变），而负数 id 又凭空造出了第三套
        // 编号空间，和 tile.id 抢同一个 Compose key 槽位。
        //
        // 现在由调用方把 `spawnRefill` 的结果传进来，spawn tile 直接读它的
        // 真实 id 与 type。
        //
        // ⚠️ 这里曾经有个 `?: BoardEngine.spawnRefill(fallen)` 兜底，注释称
        // 「不传时退化为自己算一份（单测/独立调用路径）」。那个兜底就是
        // 「动画寿司与落定不符」的根源：spawnRefill 每次调用都重新摇 type、
        // 重新发 id，兜底算出的必然是与 finalBoard 不同的另一批 tile，
        // 而生产路径一旦漏传就会静默走进这个分支。
        //
        // 现在不再兜底：refilledBoard 为 null 时**不生成 spawn-in 帧**。
        // 少播一段动画是可见且无害的退化，凭空造一批假 tile 则是会误导
        // 玩家的错误渲染 —— 宁可少画，不可画错。
        val refilled = refilledBoard

        // Track the "pre-fall row" for each tile so we can compute the offset.
        // Build a map: tileId → pre-fall row.
        val preFallRow: Map<Int, Int> = buildMap {
            for (row in 0 until board.size) {
                for (col in 0 until board.size) {
                    val tile = board.grid[row][col] ?: continue
                    if (tile.id !in eliminatedTiles) {
                        put(tile.id, row)
                    }
                }
            }
        }

        val frameFall = buildMap<CellKey, TileRenderState> {
            for (row in 0 until fallen.size) {
                for (col in 0 until fallen.size) {
                    val tile = fallen.grid[row][col]
                    if (tile == null) {
                        // This cell will be filled by a spawn-in tile.
                        // Render nothing now (frame 1 gap — the cell is empty).
                        continue
                    }
                    val origRow = preFallRow[tile.id]
                    if (origRow != null && origRow != row) {
                        // Tile fell from a different row.
                        //
                        // offsetY 的符号约定：**正值 = 这个 tile 往下落了几格**。
                        // 这是 engine 的领域语义（"落了多少"），由
                        // `Falling offsetY must be positive` 等测试钉住。
                        //
                        // ⚠️ 它不是 Compose 的 y 位移。Compose 里 y 向下为正，
                        // 而动画起点在落点**上方**，所以 UI 消费这个值时必须
                        // 取负 —— 转换发生在 SushiTile，见那里的注释。
                        val offsetY = (row - origRow).toFloat()
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 1f,
                                offsetY = offsetY,
                                scale = 1f,
                                anim = TileAnim.Falling(origRow, row),
                            ),
                        )
                    } else {
                        // Tile stayed in place.
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 1f,
                                offsetY = 0f,
                                scale = 1f,
                                anim = TileAnim.Stable,
                            ),
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Frame 2: Spawn In
        // ------------------------------------------------------------------
        val frameSpawnIn = buildMap<CellKey, TileRenderState> {
            // First: all tiles that survived the fall — stable at their new positions.
            for (row in 0 until fallen.size) {
                for (col in 0 until fallen.size) {
                    val tile = fallen.grid[row][col] ?: continue
                    val origRow = preFallRow[tile.id]
                    if (origRow != null && origRow != row) {
                        // Tile that was falling in frame 1 — now at rest.
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 1f,
                                offsetY = 0f,
                                scale = 1f,
                                anim = TileAnim.Stable,
                            ),
                        )
                    } else {
                        // Tile never moved — stable.
                        put(
                            CellKey(row, col),
                            TileRenderState(
                                tileId = tile.id,
                                type = tile.type,
                                alpha = 1f,
                                offsetY = 0f,
                                scale = 1f,
                                anim = TileAnim.Stable,
                            ),
                        )
                    }
                }
            }

            // Second: spawn-in tiles for genuine top-of-column gaps.
            //
            // IMPORTANT: nullCountFromTop must track only consecutive nulls from the TOP.
            // A null at (row, col) is a true "spawn slot" only if ALL rows 0..row
            // in this column are also null (gravity cleared them). If a tile settled
            // at row N and row N+1 is null, that null is INTERIOR to the column
            // (gravity couldn't fill it because the tile that would have fallen was
            // itself eliminated). A SpawningIn tile must NOT be placed there —
            // it would overwrite the gravity-fallen tile AND carry a wrong offset.
            //
            // We detect this by tracking nullCountFromTop as the count of consecutive
            // nulls seen so far. When we encounter a non-null at row N, subsequent
            // nulls (row N+1, N+2, ...) are interior and get skipped.
            // refilled 为 null 表示调用方没有提供补充结果 —— 不生成
            // spawn-in（见上方关于「宁可少画，不可画错」的说明）。
            for (col in 0 until fallen.size) {
                if (refilled == null) break
                var nullCountFromTop = 0
                for (row in 0 until fallen.size) {
                    val occupant = fallen.grid[row][col]
                    if (occupant != null) {
                        // Tile settled here (fell from above or stayed). Reset counter:
                        // any nulls below this row are interior, not top-gaps.
                        nullCountFromTop = 0
                        continue
                    }
                    // Null at (row, col). It is a genuine spawn slot only if all
                    // rows above are also null (nullCountFromTop == row).
                    if (nullCountFromTop != row) {
                        // Interior null — skip and do NOT increment nullCountFromTop.
                        continue
                    }
                    // Genuine top-of-column gap. Place SpawningIn.
                    //
                    // 身份与类型都取自 refilled ——「动画里飞进来的这一个」
                    // 就是「落定后留在这一格的那一个」。此前这里发负数 id
                    // 且随机编 type，两者都是假的。
                    val spawnFromRow = -(nullCountFromTop + 1)
                    val spawned = refilled.grid[row][col]
                    if (spawned == null) {
                        // refilled 理应把顶部空洞补满；真为 null 说明调用方
                        // 传了不匹配的 refilledBoard。跳过而不是造假数据。
                        nullCountFromTop++
                        continue
                    }
                    put(
                        CellKey(row, col),
                        TileRenderState(
                            tileId = spawned.id,
                            type = spawned.type,
                            alpha = 1f,
                            // 符号约定同 Falling：**正值 = 往下落了几格**。
                            // spawnFromRow 是负的行号（-1, -2, -3… = 棋盘外
                            // 上方第 N 行），落到 row，所以落差 = row - spawnFromRow，
                            // 恒为正。由 `frame2 spawn-in tiles have positive
                            // offsetY` 钉住。
                            //
                            //   row=0, spawnFromRow=-1 → +1（落 1 格）
                            //   row=2, spawnFromRow=-3 → +5（落 5 格）
                            //
                            // ⚠️ UI 消费时取负，转换在 SushiTile。
                            offsetY = (row - spawnFromRow).toFloat(),
                            scale = 1f,
                            anim = TileAnim.SpawningIn(spawnFromRow),
                        ),
                    )
                    nullCountFromTop++
                }
            }
        }

        return listOf(frameFadeOut, frameFall, frameSpawnIn)
    }

    /**
     * Build the "empty" all-stable frame for a board with no animations.
     * Used when there are no matches (all tiles stable).
     */
    private fun emptyStableFrame(board: Board): AnimFrame = buildMap {
        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                val tile = board.grid[row][col] ?: continue
                put(
                    CellKey(row, col),
                    TileRenderState(
                        tileId = tile.id,
                        type = tile.type,
                        alpha = 1f,
                        offsetY = 0f,
                        scale = 1f,
                        anim = TileAnim.Stable,
                    ),
                )
            }
        }
    }

    // ========================================================================
    // Data models
    // ========================================================================

    /**
     * Per-tile animation intent. Drives the UI layer's `Animatable` config.
     */
    sealed class TileAnim {
        /** Tile is disappearing (alpha 1→0 over Phase 1). */
        object FadingOut : TileAnim()

        /**
         * Tile is falling from [fromRow] to [toRow] (Phase 2).
         * [fromRow] < [toRow]. The tile's visual Y position moves from
         * `fromRow * cellSizePx` to `toRow * cellSizePx`.
         */
        data class Falling(val fromRow: Int, val toRow: Int) : TileAnim()

        /**
         * New tile spawning in from above, falling from [spawnFromRow]
         * (a negative row index, e.g. `-1`, `-2`) to its destination row.
         * The tile appears at `spawnFromRow * cellSizePx` and animates to `0`.
         */
        data class SpawningIn(val spawnFromRow: Int) : TileAnim()

        /** Tile is idle — no animation. */
        object Stable : TileAnim()
    }

    /**
     * Stable grid position key. Does NOT change when a tile moves —
     * the tile that was at `(3, 4)` is still keyed as `(3, 4)` even
     * after gravity moves it to `(4, 4)`. This makes `key()` stable
     * across animation frames for Compose.
     */
    data class CellKey(val row: Int, val col: Int)

    /**
     * What the UI should render for one cell in one animation frame.
     *
     * @property tileId   真实 tile 身份，直接来自 [SushiTile.id]。
     *                    幸存 tile 用它自己的 id；spawn-in tile 用
     *                    `spawnRefill` 产出的那个真实 tile 的 id。
     *                    **全 App 唯一的身份来源**，可直接作 Compose key。
     * @property type     Sushi type (determines which drawable is shown).
     * @property alpha    Opacity, `0f`..`1f`.
     * @property offsetY  Fractional row offset for falling/spawning.
     *                     `0f` = at rest; positive = displaced downward
     *                     by this many rows.
     * @property scale    Scale factor. `1f` = normal.
     * @property anim     The animation intent for this frame.
     */
    data class TileRenderState(
        val tileId: Int,
        val type: SushiType,
        val alpha: Float,
        val offsetY: Float,
        val scale: Float,
        val anim: TileAnim,
    )

}
