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
