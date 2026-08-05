package top.windyvalley.magicsushi.engine

import kotlin.math.roundToInt

/**
 * ScoreEngine.kt — Scoring rules for Magic Sushi.
 *
 * **Pure Kotlin, ZERO Android dependencies.** No `android.*` imports allowed.
 * Lives next to [Models.kt] in the engine layer and is consumed by
 * `GameViewModel`. Mirrors the shape of [TimerEngine] (an `object` with
 * pure functions) so the engine layer stays consistent.
 *
 * ---
 * ## Scoring formula (per 02-design.md §3.5 / T-CORE-005)
 *
 * ```
 * score = BASE_POINTS_PER_TILE   (10)
 *       × match.tiles.size       (number of sushi eliminated)
 *       × lengthBonus(length)    (1.0 / 1.5 / 2.0 / 3.0)
 *       × comboMultiplier(combo) (1.0 / 1.5 / 2.0 / 2.5)
 * ```
 *
 * The product is rounded to the nearest integer before being returned.
 * With the current constant set (`1.0, 1.5, 2.0, 2.5, 3.0` and `BASE = 10`)
 * every legal combination already evaluates to an integer, but `roundToInt()`
 * is kept as defense in depth for future balance tweaks.
 *
 * ### Length bonus (3+ same-type tiles in a row/column)
 *
 * | Length | Bonus |
 * |--------|-------|
 * | 3      | 1.0×  |
 * | 4      | 1.5×  |
 * | 5      | 2.0×  |
 * | 6+     | 3.0×  |
 *
 * Below 3 (defensive — should never happen since `Match.length >= 3` by
 * the MatchEngine invariant) the bonus falls back to 1.0× so the engine
 * can never produce negative or unbounded scores.
 *
 * ### Combo multiplier (cascade chain index, 1-based)
 *
 * | Combo | Multiplier |
 * |-------|------------|
 * | 1     | 1.0×       |
 * | 2     | 1.5×       |
 * | 3     | 2.0×       |
 * | 4+    | 2.5×       |
 *
 * Combo is 1-based: the first elimination of a swap cascade uses `combo = 1`
 * (no bonus), the second elimination uses `combo = 2`, and so on. The
 * multiplier saturates at `combo = 4` to keep late-cascade scoring
 * predictable. `combo < 1` is clamped to `1.0×` defensively.
 *
 * ## Why a stateless `object`?
 *
 * The engine is **stateless** (all methods are pure functions of their
 * inputs). The ViewModel owns the running `score: Int` and `combo: Int`
 * fields inside a `StateFlow` and calls into the engine on each
 * elimination step:
 *
 * ```kotlin
 * // After each cascade step
 * val earned = ScoreEngine.scoreForMatches(matches, combo)
 * score += earned
 * if (earned > 0) showFloatingText("+$earned")
 * combo += 1   // bump combo for next cascade step
 * ```
 *
 * Keeping state in the ViewModel (not here) is what allows the engine to
 * be unit-tested without any Android `Context` — see `T-CORE-007` for
 * the test suite.
 *
 * ## Invariants
 *
 * - `lengthBonus(x) >= 1.0` for all `x`.
 * - `comboMultiplier(x) >= 1.0` for all `x`.
 * - `scoreForMatch(_, _) >= 0` (zero or positive).
 * - `scoreForMatches(emptyList, _) == 0`.
 * - `totalTilesMatched(emptyList()) == 0`.
 *
 * @see Models.kt for the data types ([Match]) consumed here.
 */
object ScoreEngine {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Base points awarded per individual sushi tile eliminated.
     * Multiplied by the length bonus and combo multiplier to form the
     * final score. Source: FR-5.1 ("+10 per sushi").
     */
    const val BASE_POINTS_PER_TILE: Int = 10

    // ========================================================================
    // Public API — pure functions
    // ========================================================================

    /**
     * Length bonus for a match of [length] tiles in a row/column.
     *
     * Mapping (per 02-design.md §3.5):
     * - `3`  → `1.0`
     * - `4`  → `1.5`
     * - `5`  → `2.0`
     * - `>=6` → `3.0` (saturates — 6 and 7 both yield 3.0)
     * - `<3`  → `1.0` (defensive default; shouldn't occur by MatchEngine invariant)
     *
     * @param length match length, expected `>= 3`
     * @return bonus multiplier in `[1.0, 3.0]`
     */
    fun lengthBonus(length: Int): Double {
        return when (length) {
            3 -> 1.0
            4 -> 1.5
            5 -> 2.0
            else -> if (length >= 6) 3.0 else 1.0
        }
    }

    /**
     * Combo multiplier for the [combo]-th elimination in a cascade chain
     * (1-based: the first elimination uses `combo = 1`).
     *
     * Mapping (per 02-design.md §3.5):
     * - `1`   → `1.0`  (no bonus — first elimination)
     * - `2`   → `1.5`
     * - `3`   → `2.0`
     * - `>=4` → `2.5`  (saturates — 4 and beyond all yield 2.5)
     * - `<1`  → `1.0`  (defensive default)
     *
     * @param combo 1-based cascade index, expected `>= 1`
     * @return multiplier in `[1.0, 2.5]`
     */
    fun comboMultiplier(combo: Int): Double {
        return when (combo) {
            1 -> 1.0
            2 -> 1.5
            3 -> 2.0
            else -> if (combo >= 4) 2.5 else 1.0
        }
    }

    /**
     * Score awarded for a single [match] at the given [combo] step.
     *
     * Formula:
     * ```
     * score = BASE_POINTS_PER_TILE
     *       × match.tiles.size
     *       × lengthBonus(match.tiles.size)
     *       × comboMultiplier(combo)
     * ```
     * rounded to the nearest integer.
     *
     * Note: `match.length` and `match.tiles.size` are equal by the
     * MatchEngine invariant (`Match.length >= 3`, set when the match
     * is constructed). We use `match.tiles.size` here as the canonical
     * "tiles eliminated" source, matching the formula and the
     * [totalTilesMatched] contract.
     *
     * Returns `0` for an empty `tiles` list (defensive — MatchEngine
     * should never produce such a `Match`).
     *
     * @param match detected match (length >= 3 by invariant)
     * @param combo 1-based cascade index, default `1` (no combo bonus)
     * @return score in `[0, Int.MAX_VALUE]`
     */
    fun scoreForMatch(match: Match, combo: Int = 1): Int {
        val tileCount = match.tiles.size
        if (tileCount == 0) return 0
        val raw = BASE_POINTS_PER_TILE.toDouble() *
                tileCount.toDouble() *
                lengthBonus(tileCount) *
                comboMultiplier(combo)
        return raw.roundToInt()
    }

    /**
     * Total score awarded for a batch of matches at the same [combo] step.
     *
     * Each match in [matches] is scored with [scoreForMatch] using the
     * same [combo] (the typical use case is "all matches eliminated in
     * cascade step #N"), then summed.
     *
     * Empty input short-circuits to `0` (per FR-6.9 spirit — no score
     * without elimination).
     *
     * @param matches matches detected in the current step
     * @param combo   1-based cascade index, default `1`
     * @return sum of per-match scores; `0` for `matches.isEmpty()`
     */
    fun scoreForMatches(matches: List<Match>, combo: Int = 1): Int {
        if (matches.isEmpty()) return 0
        return matches.sumOf { scoreForMatch(it, combo) }
    }

    /**
     * Total number of sushi tiles eliminated across all [matches].
     *
     * Used by the UI for floating-text ("3×SUSHI × 4!") and for
     * end-of-round stats. Empty input short-circuits to `0`.
     *
     * @param matches matches detected (typically in one cascade step)
     * @return `matches.sumOf { it.tiles.size }`; `0` for `matches.isEmpty()`
     */
    fun totalTilesMatched(matches: List<Match>): Int {
        return matches.sumOf { it.tiles.size }
    }
}

// ============================================================================
// Manual test entry (per T-CORE-005 acceptance criteria)
// ============================================================================

// main for manual test
// Verifies the T-CORE-005 invariants:
//   - BASE_POINTS_PER_TILE = 10
//   - lengthBonus: 3→1.0, 4→1.5, 5→2.0, 7→3.0
//   - comboMultiplier: 1→1.0, 2→1.5, 3→2.0, 4→2.5
//   - scoreForMatch: 3连×1=30, 4连×2=90, 5连×3=200
//   - scoreForMatches: 2×3连@combo2 = 90
//   - totalTilesMatched: sum of tiles.size
// Run with:  kotlinc ScoreEngine.kt -include-runtime -d ScoreEngine.jar && java -jar ScoreEngine.jar
fun main() {
    // --- Constants ---
    check(ScoreEngine.BASE_POINTS_PER_TILE == 10) {
        "BASE_POINTS_PER_TILE should be 10, was ${ScoreEngine.BASE_POINTS_PER_TILE}"
    }

    // --- lengthBonus ---
    check(ScoreEngine.lengthBonus(3) == 1.0) {
        "lengthBonus(3) should be 1.0, was ${ScoreEngine.lengthBonus(3)}"
    }
    check(ScoreEngine.lengthBonus(4) == 1.5) {
        "lengthBonus(4) should be 1.5, was ${ScoreEngine.lengthBonus(4)}"
    }
    check(ScoreEngine.lengthBonus(5) == 2.0) {
        "lengthBonus(5) should be 2.0, was ${ScoreEngine.lengthBonus(5)}"
    }
    check(ScoreEngine.lengthBonus(7) == 3.0) {
        "lengthBonus(7) should be 3.0, was ${ScoreEngine.lengthBonus(7)}"
    }
    // Defensive / boundary
    check(ScoreEngine.lengthBonus(6) == 3.0) {
        "lengthBonus(6) should be 3.0, was ${ScoreEngine.lengthBonus(6)}"
    }
    check(ScoreEngine.lengthBonus(2) == 1.0) {
        "lengthBonus(2) defensive default should be 1.0, was ${ScoreEngine.lengthBonus(2)}"
    }

    // --- comboMultiplier ---
    check(ScoreEngine.comboMultiplier(1) == 1.0) {
        "comboMultiplier(1) should be 1.0, was ${ScoreEngine.comboMultiplier(1)}"
    }
    check(ScoreEngine.comboMultiplier(2) == 1.5) {
        "comboMultiplier(2) should be 1.5, was ${ScoreEngine.comboMultiplier(2)}"
    }
    check(ScoreEngine.comboMultiplier(3) == 2.0) {
        "comboMultiplier(3) should be 2.0, was ${ScoreEngine.comboMultiplier(3)}"
    }
    check(ScoreEngine.comboMultiplier(4) == 2.5) {
        "comboMultiplier(4) should be 2.5, was ${ScoreEngine.comboMultiplier(4)}"
    }
    // Defensive / boundary
    check(ScoreEngine.comboMultiplier(5) == 2.5) {
        "comboMultiplier(5) should saturate at 2.5, was ${ScoreEngine.comboMultiplier(5)}"
    }
    check(ScoreEngine.comboMultiplier(0) == 1.0) {
        "comboMultiplier(0) defensive default should be 1.0, was ${ScoreEngine.comboMultiplier(0)}"
    }

    // --- scoreForMatch: synthetic Matches ---
    // A 3-match: tiles.size = 3, lengthBonus = 1.0
    val match3 = Match(
        tiles = List(3) { i ->
            SushiTile(id = i, type = SushiType.SUSHI1, row = 0, col = i)
        },
        axis = MatchAxis.HORIZONTAL,
        length = 3,
    )
    // A 4-match: tiles.size = 4, lengthBonus = 1.5
    val match4 = Match(
        tiles = List(4) { i ->
            SushiTile(id = 10 + i, type = SushiType.SUSHI2, row = 1, col = i)
        },
        axis = MatchAxis.HORIZONTAL,
        length = 4,
    )
    // A 5-match: tiles.size = 5, lengthBonus = 2.0
    val match5 = Match(
        tiles = List(5) { i ->
            SushiTile(id = 20 + i, type = SushiType.SUSHI3, row = 2, col = i)
        },
        axis = MatchAxis.VERTICAL,
        length = 5,
    )

    // 3连 × combo=1 → 10 × 3 × 1.0 × 1.0 = 30
    val s1 = ScoreEngine.scoreForMatch(match3, combo = 1)
    check(s1 == 30) {
        "scoreForMatch(3连, combo=1) should be 30, was $s1"
    }

    // 4连 × combo=2 → 10 × 4 × 1.5 × 1.5 = 90
    val s2 = ScoreEngine.scoreForMatch(match4, combo = 2)
    check(s2 == 90) {
        "scoreForMatch(4连, combo=2) should be 90, was $s2"
    }

    // 5连 × combo=3 → 10 × 5 × 2.0 × 2.0 = 200
    val s3 = ScoreEngine.scoreForMatch(match5, combo = 3)
    check(s3 == 200) {
        "scoreForMatch(5连, combo=3) should be 200, was $s3"
    }

    // Default combo=1
    val s1default = ScoreEngine.scoreForMatch(match3)
    check(s1default == 30) {
        "scoreForMatch(match3) default combo=1 should be 30, was $s1default"
    }

    // 6连 × combo=4 → 10 × 6 × 3.0 × 2.5 = 450
    val match6 = Match(
        tiles = List(6) { i ->
            SushiTile(id = 30 + i, type = SushiType.SUSHI4, row = 3, col = i)
        },
        axis = MatchAxis.HORIZONTAL,
        length = 6,
    )
    val s4 = ScoreEngine.scoreForMatch(match6, combo = 4)
    check(s4 == 450) {
        "scoreForMatch(6连, combo=4) should be 450, was $s4"
    }

    // --- scoreForMatches ---
    // [3连, 3连] × combo=2 → each match: 10×3×1.0×1.5 = 45; total = 90
    val sum1 = ScoreEngine.scoreForMatches(listOf(match3, match3), combo = 2)
    check(sum1 == 90) {
        "scoreForMatches([3连,3连], combo=2) should be 90, was $sum1"
    }

    // [3连, 4连, 5连] × combo=1 → 30 + 60 + 100 = 190
    val sum2 = ScoreEngine.scoreForMatches(listOf(match3, match4, match5), combo = 1)
    check(sum2 == 190) {
        "scoreForMatches([3,4,5], combo=1) should be 190, was $sum2"
    }

    // Empty → 0
    val sumEmpty = ScoreEngine.scoreForMatches(emptyList())
    check(sumEmpty == 0) {
        "scoreForMatches(emptyList) should be 0, was $sumEmpty"
    }

    // --- totalTilesMatched ---
    val tt1 = ScoreEngine.totalTilesMatched(listOf(match3, match4, match5))
    check(tt1 == 3 + 4 + 5) {
        "totalTilesMatched([3,4,5]) should be 12, was $tt1"
    }
    val tt2 = ScoreEngine.totalTilesMatched(emptyList())
    check(tt2 == 0) {
        "totalTilesMatched(emptyList) should be 0, was $tt2"
    }

    println("ScoreEngine.kt manual test passed:")
    println("  - BASE_POINTS_PER_TILE = ${ScoreEngine.BASE_POINTS_PER_TILE}")
    println("  - lengthBonus: 3=${ScoreEngine.lengthBonus(3)}, " +
            "4=${ScoreEngine.lengthBonus(4)}, " +
            "5=${ScoreEngine.lengthBonus(5)}, " +
            "7=${ScoreEngine.lengthBonus(7)}")
    println("  - comboMultiplier: 1=${ScoreEngine.comboMultiplier(1)}, " +
            "2=${ScoreEngine.comboMultiplier(2)}, " +
            "3=${ScoreEngine.comboMultiplier(3)}, " +
            "4=${ScoreEngine.comboMultiplier(4)}")
    println("  - scoreForMatch: 3连×1=$s1, 4连×2=$s2, 5连×3=$s3, 6连×4=$s4")
    println("  - scoreForMatches: [3,3]×2=$sum1, [3,4,5]×1=$sum2, []=$sumEmpty")
    println("  - totalTilesMatched: [3,4,5]=$tt1, []=$tt2")
}