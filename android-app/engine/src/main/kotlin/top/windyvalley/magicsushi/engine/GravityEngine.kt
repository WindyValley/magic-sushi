package top.windyvalley.magicsushi.engine

import kotlin.random.Random

/**
 * GravityEngine.kt — gravity fall and top-null padding after elimination.
 *
 * **Pure Kotlin, ZERO Android dependencies.** Depends only on [Models.kt].
 * Compiled and unit-tested without an Android device.
 *
 * ---
 * ## Responsibilities (T-CORE-003)
 *
 * 1. Given a [Board] and the [Match]es eliminated in the current cascade
 *    round, return a **new** [Board] where:
 *    - every eliminated cell is `null`,
 *    - each column is independently "gravity-swept": non-null tiles fall
 *      toward the bottom of the column, preserving their relative order,
 *    - any cells vacated at the **top** of a column are filled with `null`
 *      (the spawner / filler will replace them with fresh sushi tiles in a
 *      later cascade round),
 *    - every moved tile has its `row`/`col` fields synchronised with its
 *      new grid position via [SushiTile.copy] (so downstream animation and
 *      selection code can rely on `tile.row`/`tile.col` reflecting the
 *      current location, not the pre-fall location).
 * 2. When `eliminatedMatches` is empty, return the input `board` **by
 *    reference** — no allocation, no copy. This lets callers cheaply
 *    short-circuit when there is nothing to sweep.
 *
 * ## Algorithm
 *
 * Two phases per invocation:
 *
 * 1. **Null-out.** Deep-clone `board.grid` (each inner row is an independent
 *    array so future writes do not mutate the input). For every tile in
 *    every eliminated Match, write `null` into `newGrid[tile.row][tile.col]`.
 *    Tiles may appear in more than one Match (L-shape intersection); the
 *    second write is a harmless `null = null`.
 * 2. **Per-column gravity sweep.** For each column index `c`:
 *    - project the column into a temporary `List<SushiTile?>`,
 *    - run [dropColumn] (filterNotNull + prepend N nulls to keep size),
 *    - write the resulting list back into `newGrid`, replacing every entry
 *      with `tile?.copy(row = newRow, col = c)`. The `copy()` call is the
 *      only place we touch the tile instances; it preserves `id`/`type`/
 *      `isSelected`/`isLocked` and only refreshes the position fields.
 *
 * Complexity: O(n²) on an n×n board (one column scan × n cells per column,
 * each cell touched at most twice). For the 7×7 MVP board that's ≤ 98 cell
 * visits per call — well within a single frame budget.
 *
 * ## Mutation discipline
 *
 * - The input [Board] is **never mutated**. `newGrid` is a mutable scratch
 *   copy built from `board.grid[row].toMutableList()` (each inner row is a
 *   fresh list) and
 *   every element read from `board.grid` is `.copy()`-ed before being
 *   placed into `newGrid`. The returned `Board` is `board.copy(grid = newGrid)`.
 * - The only exception to the copy discipline is the empty-matches
 *   fast-path: there is nothing to change, so we return the input `board`
 *   itself, identity and all.
 *
 * ## Out of scope (handled by sibling engines)
 *
 * - **Match detection** (T-CORE-002 / [MatchEngine]) — caller must
 *    pre-compute the eliminated Matches before invoking `applyGravity`.
 * - **Cascade chaining** (T-CORE-004 / `CascadeEngine`) — this engine
 *    performs exactly one fall step; the cascade loop calls us repeatedly
 *    after each elimination round. (We do **not** import `CascadeEngine`:
 *    that would create a cycle, since `CascadeEngine` consumes us.)
 * - **Spawning new tiles** to refill the top-of-column nulls — that lives
 *    in `BoardEngine.spawnRefill` (a future task). Until then, callers must
 *    treat the post-gravity `null` cells as "empty, awaiting fill".
 * - **Scoring** (`ScoreEngine`) and **timing** (`TimerEngine`) — unrelated
 *    to gravity; never imported here.
 */
object GravityEngine {

    /**
     * Apply one round of gravity to [board] for the given [eliminatedMatches].
     *
     * @param board              the current board state. Not mutated.
     * @param eliminatedMatches  the Matches eliminated in the current
     *                           cascade round. Tiles may appear in more
     *                           than one Match (L-shape intersection);
     *                           duplicates are tolerated harmlessly.
     * @return                   a **new** [Board] (unless
     *                           `eliminatedMatches.isEmpty()`, in which
     *                           case the input [board] is returned by
     *                           reference). On the new Board:
     *                           - every eliminated cell is `null`,
     *                           - non-null tiles in each column have
     *                             fallen to the lowest available row in
     *                             the same relative order,
     *                           - the vacated top cells are `null`,
     *                           - every moved tile has `row`/`col`
     *                             fields equal to its current grid
     *                             position.
     */
    fun applyGravity(
        board: Board,
        eliminatedMatches: List<Match>,
    ): Board {
        // Fast-path: nothing to do, no allocation, identity return.
        if (eliminatedMatches.isEmpty()) return board

        // Phase 1: deep-copy the grid into a mutable scratch buffer so we can
        // fill it in place without touching the input board. Each inner row
        // must be its own MutableList (a shallow copy would alias rows).
        // 出口处再转回不可变 List。
        val newGrid: MutableList<MutableList<SushiTile?>> =
            MutableList(board.size) { row -> board.grid[row].toMutableList() }

        // Null-out every eliminated cell. L-shape duplicates overwrite
        // null with null — harmless.
        val eliminatedTiles: List<SushiTile> =
            eliminatedMatches.flatMap { it.tiles }
        for (tile in eliminatedTiles) {
            newGrid[tile.row][tile.col] = null
        }

        // Phase 2: per-column gravity sweep.
        for (col in 0 until board.size) {
            // Project column c into a list of nullable tiles, top → bottom.
            val column: List<SushiTile?> =
                ArrayList<SushiTile?>(board.size).apply {
                    for (row in 0 until board.size) add(newGrid[row][col])
                }

            val dropped: List<SushiTile?> = dropColumn(column)

            // Write the dropped column back, refreshing each tile's
            // row/col to its new position via copy(). The null entries
            // stay null. Tile id / type / isSelected / isLocked are
            // preserved by copy().
            for (row in 0 until board.size) {
                val tile = dropped[row]
                newGrid[row][col] = if (tile == null) null
                else tile.copy(row = row, col = col)
            }
        }

        // Phase 3: 冻结为不可变 List（每行也冻结），避免把可变引用泄漏进 Board。
        //
        // 不再补充空格。
        //
        // 此前这里有个 `doRefill: Boolean = true` 开关，默认会顺手调
        // spawnRefill。问题在于：
        //   - 函数名承诺的是"施加重力"，补新 tile 是另一件事；
        //   - 默认 true 意味着调用方不写参数就会**隐式消耗 RNG**，
        //     P1-2 排查出的双份不同源重力结果正是这么来的；
        //   - 想看空洞位置的调用方（AnimationEngine 生成 SpawnIn 帧）
        //     必须记得传 false，忘了就静默拿到补满的棋盘。
        //
        // 现在职责单一：applyGravity 只让 tile 下落并留下空洞。
        // 需要补充的调用方显式调 BoardEngine.spawnRefill。
        val frozenGrid: List<List<SushiTile?>> = newGrid.map { it.toList() }
        return board.copy(grid = frozenGrid)
    }

    /**
     * Apply "fall down" to a single column: drop every non-null tile to
     * the bottom of the column while preserving its relative order, then
     * pad the top with `null` so the returned list has the same size as
     * the input.
     *
     * This is the **per-column primitive** used by [applyGravity]. It is
     * intentionally generic over `List<SushiTile?>` (not bound to a
     * specific board size) so it composes cleanly: the caller controls
     * the projection from 2-D grid to 1-D column and back.
     *
     * Contract:
     *   - Input size == output size.
     *   - The multiset of non-null elements is preserved (no tile is
     *     dropped or duplicated).
     *   - The relative order of non-null elements is preserved (gravity
     *     is non-interacting: a tile that was above another non-null tile
     *     stays above it).
     *   - Non-null elements end up at the **bottom**; nulls at the top.
     *
     * @param column the column as a top→bottom list of nullable tiles.
     * @return       a new list of the same size with non-null elements
     *               bottom-packed and nulls at the top.
     */
    private fun dropColumn(column: List<SushiTile?>): List<SushiTile?> {
        val nonNull: List<SushiTile> = column.filterNotNull()
        val nullCount = column.size - nonNull.size
        // Build `[null, null, ..., nonNull..., nonNull...]`.
        // `ArrayList(column.size)` avoids the usual ArrayList grow cost.
        val result = ArrayList<SushiTile?>(column.size)
        repeat(nullCount) { result.add(null) }
        result.addAll(nonNull)
        return result
    }
}
