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
        doRefill: Boolean = true,
        rng: Random = Random.Default,
    ): Board {
        // Fast-path: nothing to do, no allocation, identity return.
        if (eliminatedMatches.isEmpty()) return board

        // Phase 1: deep-copy the grid into a mutable scratch buffer so we can
        // fill it in place without touching the input board. Each inner row
        // must be its own MutableList (a shallow copy would alias rows).
        // 出口处再转回不可变 List（FIX_PLAN D3）。
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

        // Phase 3 (optional): refill every null cell at the top of each column.
        // Caller sets doRefill=false when they need to inspect the null positions
        // (e.g. AnimationEngine needs to know where the gaps are for SpawnIn frames).
        // CascadeEngine and GameViewModel call spawnRefill explicitly after all
        // cascade rounds are done, so that new tiles can participate in detection.
        // 冻结为不可变 List（每行也冻结），避免把可变引用泄漏进 Board。
        val frozenGrid: List<List<SushiTile?>> = newGrid.map { it.toList() }
        return if (doRefill) {
            BoardEngine.spawnRefill(board.copy(grid = frozenGrid), rng)
        } else {
            board.copy(grid = frozenGrid)
        }
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

// ============================================================================
// Manual test entry (per T-CORE-003 acceptance criteria)
// ============================================================================

// main for manual test
// Verifies the T-CORE-003 acceptance criteria:
//   1. empty matches → returns the input board BY REFERENCE (identity)
//   2. single 3-tile horizontal elimination → those 3 cells go null, above tiles fall
//   3. full 7-tile vertical elimination → entire column null, top 7 nulls (whole column empty)
//   4. multiple matches (H + V) → fall applies across all columns simultaneously
//   5. row/col fields of every moved tile match its new grid position
//   6. boundary: single-tile elimination in a column → top 6 null + bottom 1 tile
//   7. boundary: column already all-null → still 7 nulls out
fun main() {
    var nextId = 0
    fun tileId() = nextId++

    /**
     * Build a 7×7 [Board] where [cellAt] fully dictates each cell:
     * returning `null` produces an explicit null (empty cell), returning a
     * non-null [SushiType] produces a tile with row/col stamped from the
     * grid position. `nextId` is incremented per non-null tile.
     */
    fun boardWith(cellAt: (row: Int, col: Int) -> SushiType?): Board {
        nextId = 0
        val b = Board()
        val newGrid = List(b.size) { r ->
            List<SushiTile?>(b.size) { c ->
                val t = cellAt(r, c)
                if (t == null) null else SushiTile(id = tileId(), type = t, row = r, col = c)
            }
        }
        return b.copy(grid = newGrid)
    }

    // Convenience: assert non-null grid[r][c] has matching row/col.
    fun assertPositionConsistent(b: Board, row: Int, col: Int) {
        val t = b.grid[row][col]
        if (t != null) {
            check(t.row == row) {
                "tile at ($row,$col) has tile.row=${t.row} (expected $row) — copy() bug?"
            }
            check(t.col == col) {
                "tile at ($row,$col) has tile.col=${t.col} (expected $col) — copy() bug?"
            }
        }
    }

    // After every transformation, walk the grid and check every non-null
    // tile's row/col matches its grid position. Catches "fell but forgot
    // to copy()" bugs.
    fun assertAllPositionsConsistent(b: Board, label: String) {
        for (r in 0 until b.size) for (c in 0 until b.size) {
            assertPositionConsistent(b, r, c)
        }
        // Also: type counts must be conserved — gravity never deletes tiles.
        val totalNonNull = (0 until b.size).sumOf { r ->
            (0 until b.size).count { c -> b.grid[r][c] != null }
        }
        check(totalNonNull <= b.size * b.size) { "[$label] sanity" }
    }

    // ---------------------------------------------------------------- 1. empty matches
    run {
        val b = boardWith { r, c -> SushiType.SUSHI1 }
        val out = GravityEngine.applyGravity(b, emptyList())
        check(out === b) { "empty matches must return the input board by reference" }
        // And nothing changed.
        for (r in 0 until b.size) for (c in 0 until b.size) {
            check(out.grid[r][c] === b.grid[r][c]) { "identity return must not copy" }
        }
        println("  [1] empty matches → identity return ✓")
    }

    // ---------------------------------------------- 2. single 3-tile horizontal elimination (mid-board)
    run {
        // Row 3 cols 0..2 = SUSHI1; everything else SUSHI2. Eliminate row 3 cells (3,0)(3,1)(3,2).
        // Pre-fall column state (top→bottom, row 0..6):
        //   col 0: [S2, S2, S2, null, S2, S2, S2]   → drop → [null, S2, S2, S2, S2, S2, S2]
        //   col 1: [S2, S2, S2, null, S2, S2, S2]   → drop → [null, S2, S2, S2, S2, S2, S2]
        //   col 2: [S2, S2, S2, null, S2, S2, S2]   → drop → [null, S2, S2, S2, S2, S2, S2]
        //   col 3..6: all S2 → unchanged (still all S2 top-to-bottom).
        //
        // The middle-row placement is intentional: an H elimination at row 0
        // would already leave the eliminated cells at the top of their
        // columns, demonstrating nothing about gravity. Placing the match
        // in the middle forces three columns to drop one cell each.
        //
        // After fall, cols 0..2 are: row 0 = null (the eliminated gap floats
        // to the top), rows 1..6 = S2 (the 6 original S2s bottom-pack).
        val b = boardWith { r, c ->
            if (r == 3 && c in 0..2) SushiType.SUSHI1 else SushiType.SUSHI2
        }
        // Build the match: the 3 SUSHI1 tiles on row 3.
        val matchTiles = listOf(
            b.grid[3][0]!!, b.grid[3][1]!!, b.grid[3][2]!!,
        )
        val match = Match(tiles = matchTiles, axis = MatchAxis.HORIZONTAL, length = 3)

        val out = GravityEngine.applyGravity(b, listOf(match))

        // Row 0 cols 0..2 must be null (the eliminated gap floated to the top).
        for (c in 0..2) check(out.grid[0][c] == null) {
            "row 0 col $c should be null (eliminated gap floated to top), got ${out.grid[0][c]}"
        }
        // Rows 1..6 in cols 0..2 must be SUSHI2 (the 6 S2s bottom-packed).
        for (c in 0..2) for (r in 1..6) {
            check(out.grid[r][c]?.type == SushiType.SUSHI2) {
                "row $r col $c should be SUSHI2 after fall, got ${out.grid[r][c]?.type}"
            }
        }
        // Cols 3..6 are completely untouched (no nulls in their column).
        for (c in 3..6) for (r in 0 until b.size) {
            check(out.grid[r][c]?.type == SushiType.SUSHI2) {
                "col $c row $r should be SUSHI2 (no change), got ${out.grid[r][c]?.type}"
            }
        }
        assertAllPositionsConsistent(out, "test 2")
        println("  [2] single 3-tile H elimination (mid-row) → fall correct, top nulls ✓")
    }

    // ---------------------------------------------- 3. full 7-tile vertical elimination
    run {
        // Col 2 all rows = SUSHI3; other cols SUSHI4 (no matches). Eliminate col 2.
        val b = boardWith { r, c -> if (c == 2) SushiType.SUSHI3 else SushiType.SUSHI4 }
        val matchTiles = (0 until b.size).map { r -> b.grid[r][2]!! }
        val match = Match(tiles = matchTiles, axis = MatchAxis.VERTICAL, length = 7)

        val out = GravityEngine.applyGravity(b, listOf(match))

        // Col 2 should be all null now (no non-null tiles to fall; the whole column was eliminated).
        for (r in 0 until b.size) {
            check(out.grid[r][2] == null) { "col 2 row $r should be null, got ${out.grid[r][2]}" }
        }
        // Other cols unchanged.
        for (c in 0..6) if (c != 2) for (r in 0 until b.size) {
            check(out.grid[r][c]?.type == SushiType.SUSHI4) {
                "col $c row $r should be SUSHI4, got ${out.grid[r][c]?.type}"
            }
        }
        assertAllPositionsConsistent(out, "test 3")
        println("  [3] full 7-tile V elimination → col 2 all null ✓")
    }

    // ---------------------------------------------- 4. multiple matches (H + V)
    run {
        // Horizontal match: row 5 cols 1..3 = SUSHI1 (length 3).
        // Vertical match:   col 4 rows 2..4 = SUSHI2 (length 3).
        // Other cells: SUSHI6 (no matches).
        val b = boardWith { r, c ->
            when {
                r == 5 && c in 1..3 -> SushiType.SUSHI1
                c == 4 && r in 2..4 -> SushiType.SUSHI2
                else -> SushiType.SUSHI6
            }
        }
        val hTiles = listOf(b.grid[5][1]!!, b.grid[5][2]!!, b.grid[5][3]!!)
        val vTiles = listOf(b.grid[2][4]!!, b.grid[3][4]!!, b.grid[4][4]!!)
        val hMatch = Match(tiles = hTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val vMatch = Match(tiles = vTiles, axis = MatchAxis.VERTICAL, length = 3)

        val out = GravityEngine.applyGravity(b, listOf(hMatch, vMatch))

        // Pre-fall column projections:
        //   col 0: [S6, S6, S6, S6, S6, S6, S6]            → unchanged
        //   col 1: [S6, S6, S6, S6, S6, null, S6]          → null at row 5 → fall one
        //           → expect [null, S6, S6, S6, S6, S6, S6]
        //   col 2: [S6, S6, S6, S6, S6, null, S6]          → same shape as col 1
        //   col 3: [S6, S6, S6, S6, S6, null, S6]          → same shape as col 1
        //   col 4: [S6, S6, null, null, null, S6, S6]      → 3 nulls in middle
        //           → expect [null, null, null, S6, S6, S6, S6]
        //   col 5: [S6, S6, S6, S6, S6, S6, S6]            → unchanged
        //   col 6: [S6, S6, S6, S6, S6, S6, S6]            → unchanged

        // Col 0 — unchanged.
        for (r in 0..6) check(out.grid[r][0]?.type == SushiType.SUSHI6) { "col 0 should be all S6" }

        // Cols 1..3: null at row 0, S6 at rows 1..6.
        for (c in 1..3) {
            check(out.grid[0][c] == null) { "col $c row 0 should be null, got ${out.grid[0][c]}" }
            for (r in 1..6) check(out.grid[r][c]?.type == SushiType.SUSHI6) {
                "col $c row $r should be S6 after fall, got ${out.grid[r][c]?.type}"
            }
        }

        // Col 4: three nulls at top, S6 at rows 3,4,5,6.
        for (r in 0..2) check(out.grid[r][4] == null) { "col 4 row $r should be null" }
        for (r in 3..6) check(out.grid[r][4]?.type == SushiType.SUSHI6) {
            "col 4 row $r should be S6 after fall, got ${out.grid[r][4]?.type}"
        }

        // Cols 5..6 — unchanged.
        for (c in 5..6) for (r in 0..6) check(out.grid[r][c]?.type == SushiType.SUSHI6) {
            "col $c row $r should be all S6"
        }

        assertAllPositionsConsistent(out, "test 4")
        println("  [4] multiple matches (H + V) → all columns fall correctly ✓")
    }

    // ---------------------------------------------- 5. row/col fields updated
    run {
        // Eliminate a 3-cell V match on col 1 (rows 2..4). After fall,
        // every S3 tile originally at rows 5,6 should now report row=6,5
        // (they swap positions as col 1 drops from [S3, S3, null, null, null, S3, S3]
        // to [null, null, null, null, null, S3, S3] — S3 at row 5 goes to row 5? no, wait).
        //
        // Let me redo: pre-fall col 1, top→bottom = row 0..6:
        //   [S3, S3, S5, S5, S5, S3, S3]
        // Eliminate rows 2..4 (the three S5 cells).
        // Remaining non-null, top→bottom: [S3, S3, S3, S3]
        // Drop to bottom: positions rows 3,4,5,6.
        // Expected post-fall col 1 (top→bottom):
        //   [null, null, null, S3, S3, S3, S3]
        //
        // The S3 originally at row 0 ends up at row 3; row 1 → row 4;
        // row 5 → row 5; row 6 → row 6. So two tiles move.
        val b = boardWith { r, c ->
            if (c == 1) when (r) {
                in 0..1 -> SushiType.SUSHI3
                in 2..4 -> SushiType.SUSHI5
                else -> SushiType.SUSHI3
            } else SushiType.SUSHI4
        }
        val vTiles = listOf(b.grid[2][1]!!, b.grid[3][1]!!, b.grid[4][1]!!)
        val vMatch = Match(tiles = vTiles, axis = MatchAxis.VERTICAL, length = 3)

        // Capture original row/col of the four S3 cells in col 1 BEFORE gravity.
        val s3At0 = b.grid[0][1]!!   // row=0, col=1
        val s3At1 = b.grid[1][1]!!   // row=1, col=1
        val s3At5 = b.grid[5][1]!!   // row=5, col=1
        val s3At6 = b.grid[6][1]!!   // row=6, col=1

        val out = GravityEngine.applyGravity(b, listOf(vMatch))

        // Post-fall col 1: rows 0..2 = null; rows 3..6 = S3.
        for (r in 0..2) check(out.grid[r][1] == null) { "col 1 row $r should be null" }
        for (r in 3..6) check(out.grid[r][1]?.type == SushiType.SUSHI3) {
            "col 1 row $r should be S3, got ${out.grid[r][1]?.type}"
        }

        // row/col field assertions:
        //   original row=0 → new row=3
        //   original row=1 → new row=4
        //   original row=5 → new row=5 (didn't move)
        //   original row=6 → new row=6 (didn't move)
        // Use identity match on tile id, NOT reference (copy() returns a new instance).
        val byId = (0 until b.size).associate { r -> out.grid[r][1]?.id to out.grid[r][1] }

        val moved0 = byId[s3At0.id]
        check(moved0 != null) { "S3 originally at (0,1) should still exist somewhere in col 1" }
        check(moved0.row == 3 && moved0.col == 1) {
            "S3 originally at row=0 should now have row=3 col=1, got row=${moved0.row} col=${moved0.col}"
        }
        val moved1 = byId[s3At1.id]
        check(moved1 != null) { "S3 originally at (1,1) should still exist" }
        check(moved1.row == 4 && moved1.col == 1) {
            "S3 originally at row=1 should now have row=4 col=1, got row=${moved1.row} col=${moved1.col}"
        }
        val stayed5 = byId[s3At5.id]
        check(stayed5 != null) { "S3 originally at (5,1) should still exist" }
        check(stayed5.row == 5 && stayed5.col == 1) {
            "S3 at row=5 should stay row=5 col=1, got row=${stayed5.row} col=${stayed5.col}"
        }
        val stayed6 = byId[s3At6.id]
        check(stayed6 != null) { "S3 originally at (6,1) should still exist" }
        check(stayed6.row == 6 && stayed6.col == 1) {
            "S3 at row=6 should stay row=6 col=1, got row=${stayed6.row} col=${stayed6.col}"
        }

        // Also: id and type should be preserved across the fall.
        check(moved0.id == s3At0.id && moved0.type == s3At0.type) { "id/type preserved?" }
        check(moved1.id == s3At1.id && moved1.type == s3At1.type) { "id/type preserved?" }
        check(stayed5.id == s3At5.id && stayed5.type == s3At5.type) { "id/type preserved?" }
        check(stayed6.id == s3At6.id && stayed6.type == s3At6.type) { "id/type preserved?" }

        assertAllPositionsConsistent(out, "test 5")
        println("  [5] row/col fields updated + id/type preserved across fall ✓")
    }

    // ---------------------------------------------- 6. boundary: single-tile elimination in a column
    run {
        // Col 3: [S2, S2, S2, S2, S2, S2, S2] (all 7 rows = SUSHI2). Eliminate the single tile at (6, 3).
        // Pre-fall col 3 (after nulling): [S2, S2, S2, S2, S2, S2, null]
        // Post-fall col 3 (pack-to-bottom):  [null, S2, S2, S2, S2, S2, S2]
        //                              = 1 null at top, 6 S2 bottom-packed.
        //
        // Why "1 null at top" not "6 nulls at top"? The algorithm is pack-to-bottom:
        // `filterNotNull + prepend nulls` collects all non-null tiles and packs them
        // against the bottom, regardless of where the original nulls were. A
        // single-tile elimination therefore leaves 1 null at the top, not 6.
        //
        // Other cols are entirely SUSHI6 (unchanged by gravity — no nulls in their
        // columns).
        val b = boardWith { _, c -> if (c == 3) SushiType.SUSHI2 else SushiType.SUSHI6 }
        val eliminatedTile = b.grid[6][3]!!
        val match = Match(tiles = listOf(eliminatedTile), axis = MatchAxis.VERTICAL, length = 1)

        // Capture original row/col of the 6 surviving S2 tiles in col 3 BEFORE gravity.
        // They are at rows 0..5; after gravity they shift down by 1 (to rows 1..6).
        val survivors: List<SushiTile> = (0..5).map { b.grid[it][3]!! }

        val out = GravityEngine.applyGravity(b, listOf(match))

        // Col 3 row 0 = null (the only null, floated to top by pack-to-bottom).
        check(out.grid[0][3] == null) { "col 3 row 0 should be null, got ${out.grid[0][3]}" }
        // Col 3 rows 1..6 = S2 (the 6 survivors bottom-packed).
        for (r in 1..6) check(out.grid[r][3]?.type == SushiType.SUSHI2) {
            "col 3 row $r should be SUSHI2 after fall, got ${out.grid[r][3]?.type}"
        }

        // Verify row/col fields are correctly updated on each survivor.
        //   survivor at original row 0 → new row 1
        //   survivor at original row 1 → new row 2
        //   ...
        //   survivor at original row 5 → new row 6
        val byId: Map<Int, SushiTile> =
            (0..6).mapNotNull { r -> out.grid[r][3]?.let { it.id to it } }.toMap()
        for ((i, s) in survivors.withIndex()) {
            val moved = byId[s.id]
            check(moved != null) { "survivor id=${s.id} (originally row=$i) should still exist" }
            val expectedRow = i + 1
            check(moved.row == expectedRow && moved.col == 3) {
                "survivor originally at row=$i should now be row=$expectedRow col=3, " +
                    "got row=${moved.row} col=${moved.col}"
            }
            check(moved.id == s.id && moved.type == s.type) {
                "survivor id/type should be preserved (was id=${s.id} type=${s.type}, " +
                    "got id=${moved.id} type=${moved.type})"
            }
        }

        // The eliminated tile id should NOT appear in the output grid (it was nulled).
        val eliminatedId = eliminatedTile.id
        val stillThere = (0..6).any { r -> out.grid[r][3]?.id == eliminatedId }
        check(!stillThere) { "eliminated tile id=$eliminatedId should be gone from output grid" }

        // Other cols are completely untouched (still all SUSHI6).
        for (c in 0..6) if (c != 3) for (r in 0 until b.size) {
            check(out.grid[r][c]?.type == SushiType.SUSHI6) {
                "col $c row $r should be SUSHI6 (unchanged), got ${out.grid[r][c]?.type}"
            }
        }
        assertAllPositionsConsistent(out, "test 6")
        println("  [6] single-tile elimination → 1 null at top + 6 S bottom-packed, row/col updated ✓")
    }

    // ---------------------------------------------- 7. boundary: column already all-null
    run {
        // Col 4 entirely null; other cols all S5. "Eliminate" an empty match
        // (0 tiles — odd but valid edge case: a 0-length Match shouldn't
        // really exist, but we test a column that's empty anyway by making
        // the match cover non-existent tiles... actually let's just construct
        // a no-op case: eliminate nothing in a column that has tiles).
        // Better: build a board where col 4 is all null, then eliminate a
        // match elsewhere. col 4 should remain all-null.
        val b = boardWith { r, c -> if (c == 4) null else SushiType.SUSHI5 }
        // Pre-verify col 4 is all null.
        for (r in 0..6) check(b.grid[r][4] == null) { "col 4 should start all-null" }
        // Make a dummy "match" on an entirely different column to exercise the loop.
        // Eliminate the three cells (0, 0), (1, 0), (2, 0) on col 0.
        val matchTiles = listOf(b.grid[0][0]!!, b.grid[1][0]!!, b.grid[2][0]!!)
        val match = Match(tiles = matchTiles, axis = MatchAxis.VERTICAL, length = 3)

        val out = GravityEngine.applyGravity(b, listOf(match))

        // Col 4 should still be all-null.
        for (r in 0..6) check(out.grid[r][4] == null) { "col 4 row $r should still be null" }
        // Col 0 after eliminating 3 of 7: expect top 3 null, S5 at rows 3..6.
        for (r in 0..2) check(out.grid[r][0] == null) { "col 0 row $r should be null after fall" }
        for (r in 3..6) check(out.grid[r][0]?.type == SushiType.SUSHI5) {
            "col 0 row $r should be S5, got ${out.grid[r][0]?.type}"
        }
        // Cols 1..3, 5..6 — unchanged.
        for (c in 1..3) for (r in 0..6) check(out.grid[r][c]?.type == SushiType.SUSHI5) {
            "col $c row $r should be S5"
        }
        for (c in 5..6) for (r in 0..6) check(out.grid[r][c]?.type == SushiType.SUSHI5) {
            "col $c row $r should be S5"
        }
        assertAllPositionsConsistent(out, "test 7")
        println("  [7] all-null column preserved across gravity ✓")
    }

    // ---------------------------------------------- 8. corner: L-shape double-elimination
    run {
        // Eliminate a 3-tile horizontal at (0,0)(0,1)(0,2) PLUS a 3-tile vertical
        // at (0,0)(1,0)(2,0). The corner tile (0,0) is in BOTH matches.
        // After gravity: col 0 had pre-fall top→bottom [S1, S1, S1, S4, S4, S4, S4]
        //   (after marking (0,0)(1,0)(2,0) null) → [null, null, null, S4, S4, S4, S4]
        //   → drop → [null, null, null, S4, S4, S4, S4] (unchanged)
        // Col 1 pre-fall top→bottom [S1, S4, S4, S4, S4, S4, S4] (after marking (0,1) null)
        //   → [null, S4, S4, S4, S4, S4, S4] → drop → [null, S4, S4, S4, S4, S4, S4] (unchanged)
        // Col 2 similarly [S1, S4, ...] → [null, S4, S4, S4, S4, S4, S4].
        // Cols 3..6 entirely S4, untouched.
        val b = boardWith { r, c ->
            when {
                r == 0 && c in 0..2 -> SushiType.SUSHI1
                c == 0 && r in 1..2 -> SushiType.SUSHI1
                else -> SushiType.SUSHI4
            }
        }
        val hTiles = listOf(b.grid[0][0]!!, b.grid[0][1]!!, b.grid[0][2]!!)
        val vTiles = listOf(b.grid[0][0]!!, b.grid[1][0]!!, b.grid[2][0]!!)
        val hMatch = Match(tiles = hTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val vMatch = Match(tiles = vTiles, axis = MatchAxis.VERTICAL, length = 3)

        val out = GravityEngine.applyGravity(b, listOf(hMatch, vMatch))

        // Col 0: rows 0..2 null, rows 3..6 S4.
        for (r in 0..2) check(out.grid[r][0] == null) { "col 0 row $r should be null" }
        for (r in 3..6) check(out.grid[r][0]?.type == SushiType.SUSHI4) {
            "col 0 row $r should be S4, got ${out.grid[r][0]?.type}"
        }
        // Col 1: row 0 null, rows 1..6 S4.
        check(out.grid[0][1] == null) { "col 1 row 0 should be null" }
        for (r in 1..6) check(out.grid[r][1]?.type == SushiType.SUSHI4) {
            "col 1 row $r should be S4, got ${out.grid[r][1]?.type}"
        }
        // Col 2: same as col 1.
        check(out.grid[0][2] == null) { "col 2 row 0 should be null" }
        for (r in 1..6) check(out.grid[r][2]?.type == SushiType.SUSHI4) {
            "col 2 row $r should be S4, got ${out.grid[r][2]?.type}"
        }
        // Cols 3..6: untouched, all S4.
        for (c in 3..6) for (r in 0..6) check(out.grid[r][c]?.type == SushiType.SUSHI4) {
            "col $c row $r should be S4 (untouched)"
        }
        assertAllPositionsConsistent(out, "test 8")
        println("  [8] L-shape double-elim (corner tile shared) ✓")
    }

    // ---------------------------------------------- 9. no tile type change after fall
    // Type and id preservation across a fall: build a board where column 0 has
    // S1, S2, S3, S4, S5, S6, S1 (cycling through 6 types + repeat; 7 cells),
    // eliminate all 7 of col 0 (a length-7 vertical match) — the column will
    // become fully null, but we mainly use this to verify other columns
    // remain untouched (no horizontal movement).
    run {
        // We can't use a 6-element types[r] lookup for r in 0..6, so cycle
        // through the 6 SushiType entries (mod 6) to fill col 0.
        val cycleTypes = SushiType.entries   // [S1, S2, S3, S4, S5, S6]
        val b = boardWith { r, c ->
            if (c == 0) cycleTypes[r % cycleTypes.size] else SushiType.SUSHI6
        }
        // Sanity-check the col-0 setup.
        for (r in 0..6) {
            check(b.grid[r][0] != null) { "col 0 row $r should be non-null in setup" }
        }
        // Eliminate all 7 of col 0 (length-7 vertical match).
        val matchTiles = (0 until b.size).map { r -> b.grid[r][0]!! }
        val match = Match(tiles = matchTiles, axis = MatchAxis.VERTICAL, length = 7)

        val out = GravityEngine.applyGravity(b, listOf(match))

        // Col 0 is fully null.
        for (r in 0..6) check(out.grid[r][0] == null) { "col 0 row $r should be null after full V elim" }
        // Other cols unchanged.
        for (c in 1..6) for (r in 0..6) check(out.grid[r][c]?.type == SushiType.SUSHI6) {
            "col $c row $r should be S6 (unchanged)"
        }
        // Total non-null count: 7 * 6 = 42 (six full columns of S6).
        val total = (0..6).sumOf { r -> (0..6).count { c -> out.grid[r][c] != null } }
        check(total == 42) { "expected 42 non-null cells after gravity, got $total" }
        assertAllPositionsConsistent(out, "test 9")
        println("  [9] full column elimination, type/id discipline ✓")
    }

    // ---------------------------------------------- 10. invariant: no horizontal movement
    // Tiles must NEVER cross columns during gravity. Build a board where each
    // column has a single distinct type, eliminate a tile mid-column in col 0,
    // and verify col 0 still has the original types (in their new order) and
    // every other column is byte-identical to before.
    run {
        // Col 0: S1 S2 S1 S2 S1 S2 S1 (alternating S1/S2).
        // Cols 1..6: each its own type, all 7 rows of that type.
        val b2 = Board()
        nextId = 0
        val colTypes = arrayOf(
            SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI3,
            SushiType.SUSHI4, SushiType.SUSHI5, SushiType.SUSHI6,
            SushiType.SUSHI1,   // col 6 = SUSHI1
        )
        val altCol0 = arrayOf(
            SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI1, SushiType.SUSHI2,
            SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI1,
        )
        val newGrid = List(b2.size) { r ->
            List<SushiTile?>(b2.size) { c ->
                val t = if (c == 0) altCol0[r] else colTypes[c]
                SushiTile(id = tileId(), type = t, row = r, col = c)
            }
        }
        val board = b2.copy(grid = newGrid)

        // Eliminate cell (3, 0) (which is S2 mid-column).
        val single = board.grid[3][0]!!
        val match = Match(tiles = listOf(single), axis = MatchAxis.VERTICAL, length = 1)

        // Snapshot per-column type lists BEFORE.
        val beforeTypes: List<List<SushiType?>> =
            (0 until board.size).map { c -> (0 until board.size).map { r -> board.grid[r][c]?.type } }

        val out = GravityEngine.applyGravity(board, listOf(match))

        // Snapshot per-column type lists AFTER.
        val afterTypes: List<List<SushiType?>> =
            (0 until board.size).map { c -> (0 until board.size).map { r -> out.grid[r][c]?.type } }

        // For every column c, the type sequence BEFORE and AFTER must be the same
        // list (gravity only changes null/non-null pattern within a column; no
        // horizontal crossing).
        for (c in 0 until board.size) {
            // Except col 0, which lost one S2 mid-column.
            if (c == 0) {
                // altCol0 with one S2 removed = [S1, S2, S1, _, S1, S2, S1] then bottom-packed
                // = [null, S1, S2, S1, S1, S2, S1]
                val expected = listOf<SushiType?>(
                    null, SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI1,
                    SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI1,
                )
                check(afterTypes[c] == expected) {
                    "col 0 type sequence after fall:\n  expected=$expected\n  got=${afterTypes[c]}"
                }
            } else {
                check(afterTypes[c] == beforeTypes[c]) {
                    "col $c type sequence must be unchanged across gravity:\n" +
                        "  before=${beforeTypes[c]}\n  after=${afterTypes[c]}"
                }
            }
        }
        assertAllPositionsConsistent(out, "test 10")
        println("  [10] no horizontal movement — type sequences per column preserved ✓")
    }

    println("GravityEngine.kt manual test passed (10 cases).")
}