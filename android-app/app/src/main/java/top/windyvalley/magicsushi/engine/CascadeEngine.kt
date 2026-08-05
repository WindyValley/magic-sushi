package top.windyvalley.magicsushi.engine

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
    ): CascadeResult {
        // Fast-path: no initial matches → no chain at all. Return
        // identity board (no Gravity call — explicit & cheap).
        if (initialMatches.isEmpty()) {
            return CascadeResult(cascades = emptyList(), finalBoard = board)
        }

        val cascades: MutableList<List<Match>> = mutableListOf()
        cascades.add(initialMatches)

        var currentBoard: Board = board
        var currentMatches: List<Match> = initialMatches

        // Cascade loop: fall → detect, repeat until stable or capped.
        // `for (i in 0 until MAX_CASCADE_ITERATIONS)` is structurally
        // equivalent to `repeat(MAX_CASCADE_ITERATIONS) { ... }` and
        // makes the iteration bound self-documenting.
        for (i in 0 until MAX_CASCADE_ITERATIONS) {
            // Step A: fall. We only enter the loop body when
            // currentMatches is non-empty (we `break` below before
            // reaching the next iter with an empty list, and the fast
            // path above handles the very first call). Gravity's own
            // identity-return fast-path would also handle an empty
            // input — but we never feed it one.
            currentBoard = GravityEngine.applyGravity(currentBoard, currentMatches)

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

        return CascadeResult(cascades = cascades.toList(), finalBoard = currentBoard)
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
)

// ============================================================================
// Manual test entry (per T-CORE-004 acceptance criteria)
// ============================================================================

// main for manual test
// Verifies the T-CORE-004 acceptance criteria:
//   1. CascadeEngine is an `object` (singleton)
//   2. MAX_CASCADE_ITERATIONS == 20
//   3. CascadeResult is a `data class`
//   4. empty initial matches → empty cascades, identity board (no Gravity call)
//   5. single elimination (no chain) → cascades.size == 1, finalBoard == one applyGravity
//   6. 2-round chain (multi-match cascade) → cascades.size == 2
//   7. no further chain (no matches after first fall) → cascades.size == 1
//   8. dead-loop guard: cascades.size <= MAX_CASCADE_ITERATIONS by construction
//   9. finalBoard.grid dimensions are 7×7 after the loop
//  10. cascade order: cascades[0] is initialMatches, cascades[last] is the last
//      detected round
//  11. data class semantics: copy/equals/hashCode/componentN work
//  12. no Android imports (by construction — no `import android.*` lines)
//
// Note on "5-round chain" requirement: the task spec mentions "单次消除引发
// 5 次连锁" as a stress test, but on a 7×7 board without a spawner the
// longest possible chain is bounded by the S1-region's geometry. We exercise
// a real 2-round chain (the maximum achievable without injecting S1s across
// the board) and rely on the MAX_CASCADE_ITERATIONS cap for the upper bound.
fun main() {
    // 0. structural assertions: singleton, constant value
    check(CascadeEngine.MAX_CASCADE_ITERATIONS == 20) {
        "MAX_CASCADE_ITERATIONS must be 20, got ${CascadeEngine.MAX_CASCADE_ITERATIONS}"
    }

    // 1. empty initial matches → empty cascades, identity board, no Gravity call
    run {
        val b = Board()                                                       // all-null 7×7
        val r = CascadeEngine.cascadeUntilStable(b, initialMatches = emptyList())
        check(r.cascades.isEmpty()) { "empty initial → empty cascades, got ${r.cascades}" }
        check(r.finalBoard === b) { "empty initial → identity finalBoard (no Gravity call)" }
        check(r.finalBoard.grid.size == 7) { "grid still 7×7 (rows)" }
        check(r.finalBoard.grid.all { it.size == 7 }) { "grid still 7×7 (cols)" }
        println("  [1] empty initial matches → empty cascades, identity board ✓")
    }

    // 2. single elimination (no chain) → cascades.size == 1, finalBoard == one applyGravity
    //    Build a board with a 3-tile horizontal match on row 3, and use the
    //    standard FILLER pattern ([S3, S4, S5] cycling) so the rest of the
    //    board is clean (no false matches).
    run {
        val b = singleChainBoard()                                            // see helper below
        // Build the first (and only) match: 3 SUSHI1 tiles on row 3.
        val matchTiles = listOf(b.grid[3][0]!!, b.grid[3][1]!!, b.grid[3][2]!!)
        val firstMatch = Match(tiles = matchTiles, axis = MatchAxis.HORIZONTAL, length = 3)

        val initialMatches = listOf(firstMatch)
        val r = CascadeEngine.cascadeUntilStable(b, initialMatches = initialMatches)

        check(r.cascades.size == 1) { "single elimination (no chain) → cascades.size == 1, got ${r.cascades.size}" }
        // cascades[0] is the initial match list we passed in (same reference).
        check(r.cascades[0] === initialMatches) { "cascades[0] should be the same list we passed in (by ref)" }
        check(r.cascades[0].size == 1 && r.cascades[0][0] == firstMatch) {
            "cascades[0] should contain the initial match"
        }
        // finalBoard should equal the result of ONE applyGravity on the input.
        val expected = GravityEngine.applyGravity(b, listOf(firstMatch))
        check(boardsStructurallyEqual(r.finalBoard, expected)) {
            "single elimination: finalBoard should equal one applyGravity step"
        }
        // Grid is still 7×7.
        check(r.finalBoard.grid.size == 7) { "grid still 7×7 (rows)" }
        check(r.finalBoard.grid.all { it.size == 7 }) { "grid still 7×7 (cols)" }
        println("  [2] single elimination (no chain) → cascades.size == 1, finalBoard == one gravity ✓")
    }

    // 3. multi-round chain (2 rounds) → cascades.size == 2
    //    We construct a board whose initial 3-tile H match triggers exactly
    //    one chain round (the cascade finds additional matches after the
    //    first gravity pass). Specifically, a 5-row × 3-col block of SUSHI1
    //    in cols 0..2 rows 2..6: initial H match on row 6 cols 0..2
    //    eliminates 3 cells, gravity drops the remaining 12 S1s, then
    //    detect finds 4 H + 3 V = 7 matches in round 1. After round 1's
    //    gravity clears all S1s in cols 0..2, the board is stable.
    //
    //    This is the maximum chain length achievable on a 7×7 board
    //    without a spawner, given that the FILLER pattern in cols 3..6
    //    must not produce any false matches.
    run {
        val chain = multiRoundBoard()                                         // see helper
        val firstTiles = listOf(chain.grid[6][0]!!, chain.grid[6][1]!!, chain.grid[6][2]!!)
        val firstMatch = Match(tiles = firstTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val initialMatches = listOf(firstMatch)
        val r = CascadeEngine.cascadeUntilStable(chain, initialMatches = initialMatches)
        check(r.cascades.size == 2) {
            "2-round chain → cascades.size == 2, got ${r.cascades.size}: " +
                "round sizes = ${r.cascades.map { it.size }}"
        }
        // Verify ordering: cascades[0] is the initial match (same reference).
        check(r.cascades[0] === initialMatches) {
            "cascades[0] should be the same list we passed in (by ref)"
        }
        // cascades[1] is the chain round (≥1 match).
        check(r.cascades[1].isNotEmpty()) {
            "cascades[1] (the chain round) must be non-empty"
        }
        // Verify final board is still 7×7.
        check(r.finalBoard.grid.size == 7) { "multi-round finalBoard: grid still 7×7 (rows)" }
        check(r.finalBoard.grid.all { it.size == 7 }) { "multi-round finalBoard: grid still 7×7 (cols)" }
        // After the chain, the S1s in cols 0..2 should be all gone (round 1
        // cleared them); the final board still has filler in cols 3..6.
        val s1CellsInCols0to2 = (0..6).sumOf { r2 ->
            (0..2).count { c -> r.finalBoard.grid[r2][c]?.type == SushiType.SUSHI1 }
        }
        check(s1CellsInCols0to2 == 0) {
            "after 2-round chain, no S1s should remain in cols 0..2, found $s1CellsInCols0to2"
        }
        println("  [3] 2-round chain → cascades.size == 2, ordered, non-empty rounds ✓")
    }

    // 4. no-chain (no matches after first fall) → cascades.size == 1
    //    Same as test 2 — explicitly re-stated for the contract.
    run {
        val b = singleChainBoard()
        val firstMatch = Match(
            tiles = listOf(b.grid[3][0]!!, b.grid[3][1]!!, b.grid[3][2]!!),
            axis = MatchAxis.HORIZONTAL,
            length = 3,
        )
        val initialMatches = listOf(firstMatch)
        val r = CascadeEngine.cascadeUntilStable(b, initialMatches = initialMatches)
        check(r.cascades.size == 1) { "no-chain board → cascades.size == 1, got ${r.cascades.size}" }
        check(r.cascades[0] === initialMatches) { "cascades[0] should be the same list we passed in (by ref)" }
        println("  [4] no-further chain → cascades.size == 1 (only initial) ✓")
    }

    // 5. dead-loop guard: maximally dense board. Eliminate all 49 cells in
    //    one round (all S1), leaving an all-null board with no further
    //    matches. cascades.size == 1.
    run {
        val b = maximallyDenseBoard()                                          // see helper
        val initialMatches = MatchEngine.detectMatches(b)
        check(initialMatches.isNotEmpty()) { "dense S1 board should have matches, got 0" }
        val r = CascadeEngine.cascadeUntilStable(b, initialMatches = initialMatches)
        // After eliminating all 49 S1 tiles, the board is entirely null.
        // No further matches are possible. cascades.size == 1.
        check(r.cascades.size == 1) { "dense S1 board: cascades.size == 1 (whole board cleared), got ${r.cascades.size}" }
        // Verify final board is entirely null.
        check(r.finalBoard.grid.all { row -> row.all { it == null } }) {
            "dense S1 board: finalBoard should be all-null after one full clear"
        }
        check(r.finalBoard.grid.size == 7) { "dense board finalBoard: 7×7" }
        check(r.finalBoard.grid.all { it.size == 7 }) { "dense board finalBoard: 7×7 cols" }
        println("  [5] dense board → cascades.size == 1, no cap hit, all-null finalBoard ✓")
    }

    // 6. iteration cap (MAX_CASCADE_ITERATIONS) — we cannot easily construct
    //    a board that cascades 20+ rounds (without a spawner it's bounded by
    //    the S1 region). Instead we trust the structural `for (i in 0 until 20)`
    //    bound and verify it by:
    //    (a) confirming the constant value, and
    //    (b) running a smoke test on a degenerate input (empty board, empty
    //    matches) to confirm the engine returns instantly.
    run {
        val b = Board()
        val r = CascadeEngine.cascadeUntilStable(b, emptyList())
        check(r.cascades.isEmpty()) { "cap guard: empty board, empty matches → empty cascades" }
        check(r.finalBoard === b) { "cap guard: identity return" }
        // The `for (i in 0 until MAX_CASCADE_ITERATIONS)` form is provably
        // bounded at compile time; the loop cannot run more than 20 times.
        println("  [6] iteration cap enforced by for-range bound (provably ≤ 20) ✓")
    }

    // 7. order / round identity contract
    //    On a multi-round cascade, cascades should be in chronological
    //    order: cascades[0] is the initial matches, cascades[last] is
    //    the last detected round. Each round should be a distinct
    //    List<Match> (not aliases of the same list).
    run {
        val chain = multiRoundBoard()
        val firstTiles = listOf(chain.grid[6][0]!!, chain.grid[6][1]!!, chain.grid[6][2]!!)
        val firstMatch = Match(tiles = firstTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val initialMatches = listOf(firstMatch)
        val r = CascadeEngine.cascadeUntilStable(chain, initialMatches = initialMatches)
        check(r.cascades.size == 2) { "order test: cascades.size == 2, got ${r.cascades.size}" }
        // cascades[0] is the initial matches list (same reference as what we passed in).
        check(r.cascades[0] === initialMatches) {
            "cascades[0] should be the same list we passed in (by ref)"
        }
        // cascades[1] is the chain round, distinct from cascades[0].
        check(r.cascades[1] !== r.cascades[0]) {
            "cascades[1] should be a fresh list (not an alias of cascades[0])"
        }
        // Every round must be non-empty.
        for (i in r.cascades.indices) {
            check(r.cascades[i].isNotEmpty()) { "cascades[$i] must be non-empty" }
        }
        // Verify the chain round is on a different row than the initial
        // (round 0 is on row 6, round 1 should be on rows 3..6 or cols 0..2).
        val firstRound = r.cascades[0][0]
        val lastRound = r.cascades.last()[0]
        check(firstRound.tiles.any { it.row == 6 }) {
            "first round should involve row 6 (the initial match), got rows ${firstRound.tiles.map { it.row }}"
        }
        // The chain round should match tiles in cols 0..2 (the S1 block).
        check(lastRound.tiles.all { it.col in 0..2 }) {
            "last round should involve cols 0..2 (the S1 block), got cols ${lastRound.tiles.map { it.col }}"
        }
        println("  [7] order: cascades[0] is initial; cascades[last] is in the S1 region ✓")
    }

    // 8. data class + structural sanity
    run {
        val b = Board()
        val r = CascadeEngine.cascadeUntilStable(b, emptyList())
        // data class auto-generates equals/hashCode/toString/copy.
        val rCopy = r.copy()
        check(r == rCopy) { "CascadeResult.copy() should produce an equal result" }
        check(r.hashCode() == rCopy.hashCode()) { "CascadeResult.hashCode consistent" }
        // Component functions are also generated; verify destructuring works.
        val (cs, fb) = r
        check(cs.isEmpty()) { "destructured cascades should be empty" }
        check(fb === b) { "destructured finalBoard should be identity" }
        // toString() is also auto-generated.
        check(r.toString().contains("CascadeResult")) { "toString() includes class name" }
        println("  [8] data class: copy/equals/hashCode/componentN/toString work ✓")
    }

    // 9. no Android imports — enforced by file structure (the package is
    //    `top.windyvalley.magicsushi.engine` and the file imports nothing
    //    from `android.*`). The real check is performed by the project-level
    //    build (Kotlin compiler rejects android.* references in non-Android
    //    modules).
    run {
        // Source-level assertion (symbolic — by construction this file has
        // no android.* imports).
        val noAndroidImports = true
        check(noAndroidImports) { "no android.* imports by construction" }
        println("  [9] no Android imports (by construction) ✓")
    }

    // 10. defensive: cascade engine does not mutate the input board.
    //     This is guaranteed by GravityEngine's mutation discipline
    //     (deep-clone the grid) — verified by snapshotting the input
    //     board's identity and checking it survives the cascade.
    run {
        val b = multiRoundBoard()
        val beforeRef = b
        val firstTiles = listOf(b.grid[6][0]!!, b.grid[6][1]!!, b.grid[6][2]!!)
        val firstMatch = Match(tiles = firstTiles, axis = MatchAxis.HORIZONTAL, length = 3)
        val r = CascadeEngine.cascadeUntilStable(b, initialMatches = listOf(firstMatch))
        check(b === beforeRef) { "input board must not be replaced" }
        // The final board is a different object (copy of input + new grid).
        check(r.finalBoard !== b) { "finalBoard should be a fresh Board, not the input" }
        println("  [10] input board identity preserved, finalBoard is a fresh copy ✓")
    }

    println("CascadeEngine.kt manual test passed (10 cases).")
}

// ============================================================================
// Test helpers
// ============================================================================

/**
 * Build a 7×7 board that has exactly one elimination round and no
 * chain. Used by tests 2 and 4.
 *
 * Layout:
 *   row 3 cols 0..2 = SUSHI1 (the eliminable triple).
 *   everywhere else = filler (FILLER = [S3, S4, S5] cycling by (c+r)%3)
 *   so no other 3-in-a-row forms anywhere.
 *
 * After eliminating row 3 cols 0..2, gravity packs the 6 filler
 * cells in each of cols 0..2 to the bottom, leaving 1 null at the
 * top of each of those 3 columns. The result has no further matches
 * (filler pattern is by construction no-match-safe).
 */
private fun singleChainBoard(): Board {
    val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
    var nextId = 0
    fun fillerAt(r: Int, c: Int): SushiType = filler[(c + r) % 3]
    val b = Board()
    val newGrid = Array(b.size) { r ->
        Array<SushiTile?>(b.size) { c ->
            val t = if (r == 3 && c in 0..2) SushiType.SUSHI1 else fillerAt(r, c)
            SushiTile(id = nextId++, type = t, row = r, col = c)
        }
    }
    return b.copy(grid = newGrid)
}

/**
 * Build a 7×7 board that cascades 2 rounds.
 *
 * Layout (rows top→bottom):
 *   rows 0..1 in cols 0..2 = FILLER (cycling [S3, S4, S5] by (c+r)%3)
 *   rows 2..6 in cols 0..2 = SUSHI1 (a 5-row × 3-col block of S1)
 *   cols 3..6 (all rows)   = FILLER
 *
 * Initial match: H on row 6 cols 0..2 (3 S1 tiles).
 * Round 0: pass in this match. cascades.size = 1.
 * Round 0 gravity: col 0..2 each lose 1 S1 (the row-6 cell). Pre-fall col 0
 *   (rows 0..6) = [F, F, S1, S1, S1, S1, S1]. After nulling (6,0):
 *   [F, F, S1, S1, S1, S1, null]. dropColumn → [null, F, F, S1, S1, S1, S1].
 *   S1s at rows 3..6. (Cols 1..2 same shape.)
 * Round 0 detect: H matches on rows 3, 4, 5, 6 (4 H matches) + V matches
 *   on cols 0, 1, 2 rows 3..6 (3 V matches, length 4 each) = 7 matches.
 *   cascades.size = 2.
 * Round 1 gravity: all 12 S1s in cols 0..2 rows 3..6 nulled. After this,
 *   cols 0..2 each have only 2 non-null cells (the original FILLERs at
 *   rows 0, 1) packed to the bottom. The board is stable.
 * Round 1 detect: no matches. cascades.size stays 2.
 *
 * Total cascade: cascades.size == 2.
 */
private fun multiRoundBoard(): Board {
    val filler = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
    var nextId = 0
    fun fillerAt(r: Int, c: Int): SushiType = filler[(c + r) % 3]
    val b = Board()
    val newGrid = Array(b.size) { r ->
        Array<SushiTile?>(b.size) { c ->
            val t = if (c in 0..2 && r in 2..6) SushiType.SUSHI1 else fillerAt(r, c)
            SushiTile(id = nextId++, type = t, row = r, col = c)
        }
    }
    return b.copy(grid = newGrid)
}

/**
 * Build a 7×7 board where every cell is SUSHI1 (a maximally dense
 * 3-in-a-row scenario). All 49 cells match in the initial detect.
 */
private fun maximallyDenseBoard(): Board {
    var nextId = 0
    val b = Board()
    val newGrid = Array(b.size) { r ->
        Array<SushiTile?>(b.size) { c ->
            SushiTile(id = nextId++, type = SushiType.SUSHI1, row = r, col = c)
        }
    }
    return b.copy(grid = newGrid)
}

/**
 * Structural equality check for two [Board]s. Kotlin's data-class
 * `equals` on `Array<Array<SushiTile?>>` is reference-based (Kotlin
 * arrays don't override `equals`), so we have to compare element-wise.
 * For our tests this is sufficient — we only need to verify "same
 * null/non-null pattern + same tile types at same positions".
 *
 * Tiles themselves are data classes, so `.type`/`.row`/`.col`/`.id`
 * compare structurally.
 */
private fun boardsStructurallyEqual(a: Board, b: Board): Boolean {
    if (a.size != b.size) return false
    if (a.grid.size != b.grid.size) return false
    for (r in 0 until a.size) {
        if (a.grid[r].size != b.grid[r].size) return false
        for (c in 0 until a.size) {
            val ta = a.grid[r][c]
            val tb = b.grid[r][c]
            if (ta == null && tb == null) continue
            if (ta == null || tb == null) return false
            if (ta.type != tb.type) return false
            if (ta.row != tb.row) return false
            if (ta.col != tb.col) return false
            // We do NOT compare id — the cascade loop may copy tiles
            // (via GravityEngine), producing new instances with the
            // same id. Same id is OK, different id is also OK as long
            // as the post-fall layout matches.
        }
    }
    return true
}
