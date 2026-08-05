package top.windyvalley.magicsushi.engine

/**
 * MatchEngine.kt — three-in-a-row (and longer) detection.
 *
 * **Pure Kotlin, ZERO Android dependencies.** Depends only on [Models.kt].
 * Compiled and unit-tested without an Android device.
 *
 * ---
 * ## Responsibilities (T-CORE-002)
 *
 * 1. Scan a [Board] for every horizontal and vertical run of 3+ same-type
 *    sushi tiles (FR-3.1 — 横/竖三连, FR-3.2 — 4/5 连额外加分).
 * 2. Return each run as a [Match] tagged with the axial direction
 *    ([MatchAxis.HORIZONTAL] or [MatchAxis.VERTICAL]) on which it was found.
 * 3. Do NOT detect diagonal runs (FR-3.3 — 不支持斜线).
 * 4. Do NOT deduplicate tiles that appear in multiple Matches. The L-shape
 *    "3 horizontal + 3 vertical sharing one corner tile" must yield exactly
 *    **two** Matches, with the shared tile appearing in both — that is the
 *    GravityEngine's responsibility (it unions the tile id sets before
 *    sweeping). See [Match] for the field shape.
 *
 * ## Algorithm
 *
 * Two passes (rows + columns), each pass delegating to [detectLineMatches],
 * which runs a single forward scan with a sliding-window / run-length
 * accumulator. A "run" is a maximal consecutive sequence of non-null tiles of
 * the same [SushiType]. Null cells (post-elimination, pre-gravity) break a
 * run. Whenever a run reaches length ≥ 3 it is emitted as a [Match].
 *
 * Complexity: O(n²) on an n×n board (one row scan + one column scan, each
 * O(n) per line). For the 7×7 MVP board that's ≤ 98 cell visits per match
 * check — well within frame budget.
 *
 * ## Axis vs Direction
 *
 * Per the 2026-06-20 13:47 resolution (see `02-design.md` §3.1, `Models.kt`
 * header doc), [Match.axis] uses [MatchAxis] (2 axial values) and never
 * [Direction] (4 cardinal values, used only by gestures and gravity).
 * [MatchEngine] does not import or reference [Direction] at all — that
 * separation is enforced by both convention and the compile-time import
 * graph (this file has zero `Direction` references).
 *
 * ## Out of scope (handled by sibling engines)
 *
 * - **Cascade / chain detection** — T-CORE-003 (`CascadeEngine`) iterates
 *    this engine's output across successive gravity + refill rounds.
 * - **Tile-id dedup for falling animation** — GravityEngine.
 * - **Scoring** — `ScoreEngine` consumes the [Match.length] field.
 */
object MatchEngine {

    /**
     * Detect every horizontal and vertical run of 3+ same-type sushi on
     * [board].
     *
     * @param board the current board state. May contain `null` cells.
     * @return all detected [Match]es, in scan order (row-major for
     *         horizontal, then column-major for vertical). May be empty but
     *         never `null`. Tiles that appear in more than one Match
     *         (L-shape) are NOT deduplicated — see class doc.
     */
    fun detectMatches(board: Board): List<Match> {
        val matches = mutableListOf<Match>()

        // Horizontal pass: scan each row left-to-right.
        for (row in 0 until board.size) {
            matches += detectLineMatches(board.grid[row], MatchAxis.HORIZONTAL)
        }

        // Vertical pass: project each column top-to-bottom into a temporary
        // array and scan. We rebuild per column (rather than reading column
        // by column through board.grid[row][col]) so detectLineMatches
        // stays a uniform Array<SushiTile?> consumer — the same code path
        // is exercised by both axes, halving the bug surface.
        for (col in 0 until board.size) {
            val column = Array(board.size) { r -> board.grid[r][col] }
            matches += detectLineMatches(column, MatchAxis.VERTICAL)
        }

        return matches
    }

    /**
     * Scan a single 1-D line for runs of 3+ same-type non-null tiles.
     *
     * @param line the cells of one row OR one column, in order. May contain
     *             `null` entries (which break runs). May be empty.
     * @param axis which axis [line] represents. Recorded on every emitted
     *             [Match]. Per the 2026-06-13:47 resolution this is the
     *             **only** direction information stored on a Match.
     * @return one [Match] per maximal run of length ≥ 3. Empty list if the
     *         line has no qualifying runs (or is empty).
     *
     * ### Signature deviation from task sketch
     *
     * The T-CORE-002 sketch specified `detectLineMatches(...): Match?`
     * (single match or null). That signature is **incorrect** for the
     * general case: a single line can contain multiple non-overlapping
     * runs (e.g. `AAA BBB AAA`), all of which must be emitted. Returning
     * only the first one would silently drop valid matches after cascades.
     * We return `List<Match>` instead and let [detectMatches] flatten.
     */
    private fun detectLineMatches(
        line: Array<SushiTile?>,
        axis: MatchAxis,
    ): List<Match> {
        if (line.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()

        // Indices are into `line`.
        //   runStart   : index of the first cell of the current run, or -1 if no active run.
        //   runType    : SushiType of the current run (meaningful iff runStart != -1).
        // We track runType rather than just comparing line[i].type to line[i-1].type
        // because line[i] can be null and we must not NPE on `line[i].type`.
        var runStart = -1
        var runType: SushiType? = null

        fun closeRun(endExclusive: Int) {
            if (runStart == -1) return
            val len = endExclusive - runStart
            if (len >= 3) {
                // Within a run every cell is non-null and same-type (invariant
                // of the loop below), so filterNotNull() is a defensive no-op
                // that keeps the contract explicit: tiles.size == length.
                val tiles: List<SushiTile> =
                    line.copyOfRange(runStart, endExclusive).filterNotNull()
                matches += Match(tiles = tiles, axis = axis, length = tiles.size)
            }
            runStart = -1
            runType = null
        }

        for (i in line.indices) {
            val cellType: SushiType? = line[i]?.type

            if (cellType == null) {
                // Null cell — close the current run, if any. No new run starts.
                closeRun(i)
                continue
            }

            if (runStart != -1 && cellType == runType) {
                // Same type as the active run — extend it.
                continue
            }

            // Type changed (or no active run yet). Close the previous run,
            // then open a fresh one starting at this cell.
            closeRun(i)
            runStart = i
            runType = cellType
        }

        // Flush a run that reached the end of the line.
        closeRun(line.size)

        return matches
    }
}

// ============================================================================
// Manual test entry
// ============================================================================

// main for manual test
// Verifies the T-CORE-002 acceptance criteria:
//   - empty board → empty list
//   - full row of 7 identical → exactly 1 Match (axis=HORIZONTAL, length=7)
//   - full column of 7 identical → exactly 1 Match (axis=VERTICAL, length=7)
//   - L-shape (3 horizontal + 3 vertical sharing one tile) → exactly 2 Matches,
//     and the shared tile appears in BOTH
//   - null in the middle breaks a run (no spurious match across the gap)
//   - 4-in-a-row and 5-in-a-row report correct length
//   - mixed types in a row are not falsely reported as a match
//   - runs on two disjoint segments of the same row are both detected
//   - heterogeneous 7×7 board produces no matches at all
//
// All tests build a 7×7 board by overlaying target cells onto a "no-match filler"
// pattern. The filler is `FILLER[(c + r) % 3]` with FILLER = [S3, S4, S5] — a
// period-3 cycle that has no 3-in-a-row in any row or any column, so any match
// we observe can only come from the overlay.
fun main() {
    var nextId = 0
    val FILLER = arrayOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)

    fun fillerAt(r: Int, c: Int): SushiType = FILLER[(c + r) % 3]

    fun emptyBoard(): Board = Board()

    /**
     * Build a 7×7 [Board] by taking the no-match filler pattern everywhere
     * except at cells where [overlay] returns non-null. Overlay returning
     * null means "use filler" (no explicit null cells — use [filledBoardWithNulls]
     * if you need to test null handling).
     */
    fun filledBoard(overlay: (row: Int, col: Int) -> SushiType?): Board {
        nextId = 0
        val b = Board()
        val newGrid = Array(b.size) { r ->
            Array<SushiTile?>(b.size) { c ->
                val t = overlay(r, c) ?: fillerAt(r, c)
                SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        return b.copy(grid = newGrid)
    }

    /**
     * Build a 7×7 [Board] where [cellAt] fully dictates each cell:
     * returning `null` produces an explicit null (empty cell, post-elimination),
     * returning a non-null [SushiType] produces a tile. No filler substitution.
     */
    fun filledBoardWithNulls(cellAt: (row: Int, col: Int) -> SushiType?): Board {
        nextId = 0
        val b = Board()
        val newGrid = Array(b.size) { r ->
            Array<SushiTile?>(b.size) { c ->
                val t = cellAt(r, c)
                if (t == null) null else SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        return b.copy(grid = newGrid)
    }

    // ---------------------------------------------------------------- 1. empty board
    run {
        val ms = MatchEngine.detectMatches(emptyBoard())
        check(ms.isEmpty()) { "empty board should produce no matches, got ${ms.size}" }
    }

    // ---------------------------------------------- 2. full row of 7 identical (H)
    run {
        val ms = MatchEngine.detectMatches(
            filledBoard { r, _ -> if (r == 3) SushiType.SUSHI1 else null }
        )
        check(ms.size == 1) { "full row of 7 should yield exactly 1 match, got ${ms.size}: $ms" }
        val m = ms[0]
        check(m.axis == MatchAxis.HORIZONTAL) { "expected HORIZONTAL, got ${m.axis}" }
        check(m.length == 7) { "expected length 7, got ${m.length}" }
        check(m.tiles.size == 7) { "tiles.size must equal length" }
        check(m.tiles.all { it.type == SushiType.SUSHI1 }) { "all tiles should be SUSHI1" }
        check(m.tiles.all { it.row == 3 }) { "all tiles should be on row 3" }
    }

    // ---------------------------------------------- 3. full column of 7 identical (V)
    run {
        val ms = MatchEngine.detectMatches(
            filledBoard { _, c -> if (c == 2) SushiType.SUSHI3 else null }
        )
        check(ms.size == 1) { "full column of 7 should yield exactly 1 match, got ${ms.size}: $ms" }
        val m = ms[0]
        check(m.axis == MatchAxis.VERTICAL) { "expected VERTICAL, got ${m.axis}" }
        check(m.length == 7) { "expected length 7, got ${m.length}" }
        check(m.tiles.size == 7) { "tiles.size must equal length" }
        check(m.tiles.all { it.type == SushiType.SUSHI3 }) { "all tiles should be SUSHI3" }
        check(m.tiles.all { it.col == 2 }) { "all tiles should be on col 2" }
    }

    // --------------------------------------------------------- 4. L-shape (3 H + 3 V)
    run {
        // Target cells: (0,0),(0,1),(0,2) for the H arm; (1,0),(2,0) for the V arm.
        // Corner (0,0) is shared. The rest is filler (no other matches).
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c ->
                when {
                    r == 0 && c in 0..2 -> SushiType.SUSHI1
                    c == 0 && r in 1..2 -> SushiType.SUSHI1
                    else -> null
                }
            }
        )
        check(ms.size == 2) { "L-shape should produce exactly 2 matches, got ${ms.size}: $ms" }

        val h = ms.firstOrNull { it.axis == MatchAxis.HORIZONTAL }
        val v = ms.firstOrNull { it.axis == MatchAxis.VERTICAL }
        check(h != null) { "L-shape must include a HORIZONTAL match" }
        check(v != null) { "L-shape must include a VERTICAL match" }
        // requireNotNull gives us a non-null Match without `!!` noise (the
        // closures above prevent Kotlin's smart-cast across check boundaries).
        val hMatch = requireNotNull(h)
        val vMatch = requireNotNull(v)
        check(hMatch.length == 3 && vMatch.length == 3) {
            "L-shape matches should both be length 3, got H=${hMatch.length} V=${vMatch.length}"
        }

        // Shared corner tile must appear in both matches — verify by id-set intersection.
        val hIds = hMatch.tiles.map { it.id }.toSet()
        val vIds = vMatch.tiles.map { it.id }.toSet()
        check(hIds.intersect(vIds).size == 1) {
            "L-shape: expected exactly 1 shared tile between H and V, got ${hIds intersect vIds}"
        }
    }

    // ------------------------------------------------- 5. null in middle breaks run
    run {
        // Row 0: A A [null] A A A A  → the AA before null is too short (run length 2),
        // the AAAA after is a valid run of length 4. null breaks the run cleanly.
        // All other rows are pure filler.
        val b = filledBoardWithNulls { r, c ->
            if (r == 0) when (c) {
                0, 1 -> SushiType.SUSHI1
                2 -> null                // explicit null test
                else -> SushiType.SUSHI1
            } else fillerAt(r, c)
        }
        check(b.grid[0][2] == null) { "test setup: (0,2) should be null" }

        val ms = MatchEngine.detectMatches(b)
        val h = ms.filter { it.axis == MatchAxis.HORIZONTAL && it.tiles[0].row == 0 }
        check(h.size == 1) { "null in middle should leave exactly 1 horizontal match, got ${h.size}: $ms" }
        check(h[0].length == 4) { "match length should be 4 (cells 3..6), got ${h[0].length}" }
    }

    // ----------------------------------------------------------- 6. 4-in-a-row length
    run {
        // Row 1: cells at cols 2..5 = SUSHI3 (length 4 horizontal).
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c -> if (r == 1 && c in 2..5) SushiType.SUSHI3 else null }
        )
        check(ms.size == 1) { "expected exactly 1 match, got ${ms.size}: $ms" }
        val m = ms[0]
        check(m.length == 4) { "expected length 4, got ${m.length}" }
        check(m.axis == MatchAxis.HORIZONTAL) { "expected HORIZONTAL, got ${m.axis}" }
        check(m.tiles.size == 4) { "tiles.size must equal length" }
        check(m.tiles.all { it.type == SushiType.SUSHI3 }) { "tiles should all be SUSHI3" }
        check(m.tiles.map { it.col }.sorted() == listOf(2, 3, 4, 5)) {
            "match tiles should be at cols 2..5"
        }
    }

    // ----------------------------------------------------------- 7. 5-in-a-row length
    run {
        // Col 4: rows 0..4 = SUSHI5 (length 5 vertical).
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c -> if (c == 4 && r in 0..4) SushiType.SUSHI5 else null }
        )
        check(ms.size == 1) { "expected exactly 1 match, got ${ms.size}: $ms" }
        val m = ms[0]
        check(m.length == 5) { "expected length 5, got ${m.length}" }
        check(m.axis == MatchAxis.VERTICAL) { "expected VERTICAL, got ${m.axis}" }
        check(m.tiles.size == 5) { "tiles.size must equal length" }
        check(m.tiles.all { it.type == SushiType.SUSHI5 }) { "tiles should all be SUSHI5" }
        check(m.tiles.map { it.row }.sorted() == listOf(0, 1, 2, 3, 4)) {
            "match tiles should be at rows 0..4"
        }
    }

    // ---------------------------------- 8. mixed types in a row are not a false match
    run {
        // Row 2: pattern SUSHI1, SUSHI2, SUSHI3, SUSHI1, SUSHI2, SUSHI3, SUSHI1.
        // No 3 consecutive same-type. Other rows are filler.
        val pattern = arrayOf(
            SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI3,
            SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI3,
            SushiType.SUSHI1,
        )
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c -> if (r == 2) pattern[c] else null }
        )
        check(ms.isEmpty()) { "mixed-type row should produce no matches, got $ms" }
    }

    // ---------------------------------- 9. two disjoint runs in the same row (signature deviation)
    // On a 7-wide row, AAA + ≥1 separator + AAA = 3+1+3 = 7 cells. We use cols 0..2
    // = SUSHI1, col 3 = SUSHI2, cols 4..6 = SUSHI1. This forces the signature-deviation
    // case (two matches on one line) that a `Match?` return type would silently drop.
    run {
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c ->
                if (r == 3) when (c) {
                    0, 1, 2 -> SushiType.SUSHI1
                    3 -> SushiType.SUSHI2
                    else -> SushiType.SUSHI1        // cols 4, 5, 6
                } else null
            }
        )
        val row3 = ms.filter { it.axis == MatchAxis.HORIZONTAL && it.tiles[0].row == 3 }
        check(row3.size == 2) {
            "AAA-B-AAA on row 3 should yield 2 horizontal matches, got ${row3.size}: $ms"
        }
        check(row3.all { it.length == 3 }) {
            "both runs should be length 3, got ${row3.map { it.length }}"
        }
        val colsByRun = row3.map { it.tiles.map { t -> t.col } }.sortedBy { it.first() }
        check(colsByRun[0] == listOf(0, 1, 2)) {
            "first run cols 0..2, got ${colsByRun[0]}"
        }
        check(colsByRun[1] == listOf(4, 5, 6)) {
            "second run cols 4..6, got ${colsByRun[1]}"
        }
    }

    // ---------------------------------- 10. length-6 run (length value passthrough)
    run {
        // Row 4: col 0 = SUSHI2 (single different cell), cols 1..6 = SUSHI1 (length 6).
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c ->
                if (r == 4) when (c) {
                    0 -> SushiType.SUSHI2
                    else -> SushiType.SUSHI1
                } else null
            }
        )
        val row4 = ms.filter { it.axis == MatchAxis.HORIZONTAL && it.tiles[0].row == 4 }
        check(row4.size == 1) { "X-AAAAAA should yield 1 match, got ${row4.size}: $ms" }
        check(row4[0].length == 6) { "expected length 6, got ${row4[0].length}" }
        check(row4[0].tiles.size == 6) { "tiles.size must equal length" }
    }

    // ---------------------------------------------------- 11. heterogeneous board → 0 matches
    run {
        // Pure filler pattern everywhere — already known to have no 3-in-a-row.
        val ms = MatchEngine.detectMatches(filledBoard { _, _ -> null })
        check(ms.isEmpty()) { "filler-only board should produce no matches, got $ms" }
    }

    // ---------------------------------------------------- 12. axis field sanity (per-row check)
    // Every emitted match must have axis in {HORIZONTAL, VERTICAL}. We sanity-check
    // by counting the matches returned for the L-shape case from both axes; the
    // result is the same data class whether axis is read directly or via filter.
    run {
        val ms = MatchEngine.detectMatches(
            filledBoard { r, c ->
                when {
                    r == 0 && c in 0..2 -> SushiType.SUSHI1
                    c == 0 && r in 1..2 -> SushiType.SUSHI1
                    else -> null
                }
            }
        )
        check(ms.all { it.axis == MatchAxis.HORIZONTAL || it.axis == MatchAxis.VERTICAL }) {
            "every Match.axis must be HORIZONTAL or VERTICAL, got ${ms.map { it.axis }}"
        }
        // Sanity: count by axis is exactly what filter gives.
        check(ms.count { it.axis == MatchAxis.HORIZONTAL } == 1) { "expected exactly 1 HORIZONTAL" }
        check(ms.count { it.axis == MatchAxis.VERTICAL } == 1) { "expected exactly 1 VERTICAL" }
    }

    println("MatchEngine.kt manual test passed:")
    println("  - empty board → 0 matches")
    println("  - full row (len 7) → 1 H match (length 7)")
    println("  - full column (len 7) → 1 V match (length 7)")
    println("  - L-shape → 2 matches, 1 shared tile id")
    println("  - null in middle → run broken, 1 match on right side")
    println("  - 4-in-a-row → length 4")
    println("  - 5-in-a-row → length 5")
    println("  - mixed types in row → no false match")
    println("  - two disjoint AAA in same row → 2 matches (signature deviation verified)")
    println("  - heterogeneous 7×7 → no matches")
}