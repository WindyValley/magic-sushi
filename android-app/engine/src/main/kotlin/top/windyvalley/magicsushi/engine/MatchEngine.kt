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
        // list and scan. We rebuild per column (rather than reading column
        // by column through board.grid[row][col]) so detectLineMatches
        // stays a uniform List<SushiTile?> consumer — the same code path
        // is exercised by both axes, halving the bug surface.
        for (col in 0 until board.size) {
            val column = List(board.size) { r -> board.grid[r][col] }
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
        line: List<SushiTile?>,
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
                    line.subList(runStart, endExclusive).filterNotNull()
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
