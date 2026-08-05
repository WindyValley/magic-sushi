package top.windyvalley.magicsushi.engine

/**
 * TimerEngine.kt — Countdown timer + elimination reset rules for Magic Sushi.
 *
 * **Pure Kotlin, ZERO Android dependencies.** No `android.*` imports allowed.
 * Lives next to [Models.kt] in the engine layer and is consumed by
 * `GameViewModel`. Mirrors the shape of [BoardEngine] (an `object` with
 * pure functions) so the engine layer stays consistent.
 *
 * ---
 * ## Design (v1.0.3 — 2026-06-21 沐风反馈改)
 *
 * - **Initial time:** 60 seconds (FR-6.1).
 * - **Per-tick decrement:** 1 second (driven by the ViewModel's tick loop;
 *   the engine itself does **not** own a coroutine or counter).
 * - **Elimination behavior (v1.0.3):** 每次消除，timer **重置回 [INITIAL_SECONDS] (60s)**。
 *   不要再加时累加，不再有 cap (MAX_SECONDS = 90 在 v1.0.3 不再起作用，保留常量仅为
 *   兼容性 / 文档，不参与计算)。每次消除都是"补满到 60"，让玩家不会因为多次消除而
 *   出现"时间 90+ 越打越久"的体验问题。
 * - **No-op on no matches:** an invalid swap (`matches` empty) must not
 *   touch the timer (FR-6.9). The ViewModel is expected to check
 *   `matches.isNotEmpty()` before calling, but [rewardOnMatch] is
 *   defensive and also short-circuits on empty input.
 *
 * ## Why a stateless `object`?
 *
 * The engine is **stateless** (all methods are pure functions of their
 * `Int` / `List<Match>` inputs). The ViewModel owns the
 * `remainingSeconds: Int` field inside a `StateFlow` and calls into the
 * engine on each event:
 *
 * ```kotlin
 * // 1 Hz tick loop
 * remaining = TimerEngine.tick(remaining)
 * if (TimerEngine.isGameOver(remaining)) phase = GamePhase.GAME_OVER
 *
 * // After a successful elimination cascade step
 * val (newRemaining, reward) = TimerEngine.rewardOnMatch(remaining, matches)
 * if (reward > 0) showFloatingText("+${reward}s")
 * remaining = newRemaining
 * ```
 *
 * Keeping state in the ViewModel (not here) is what allows the engine to
 * be unit-tested without any Android `Context` or coroutine machinery —
 * see `T-CORE-007` for the test suite.
 *
 * ## Invariants
 *
 * - `tick(x)` is monotonically non-increasing: `tick(x) <= x` for all `x`.
 * - `tick(x) >= 0` always (clamped via `coerceAtLeast(0)`).
 * - `rewardOnMatch(_, _).first == INITIAL_SECONDS` (v1.0.3 — always reset to 60).
 * - `rewardOnMatch(_, _).second` is in `[0, INITIAL_SECONDS]` (top-up amount).
 * - `isGameOver(x) == true` iff `x <= 0`.
 *
 * @see Models.kt for the data types ([Match]) consumed here.
 */
object TimerEngine {

    // ========================================================================
    // Constants
    // ========================================================================

    /**
     * Starting time at the beginning of a round, in seconds.
     * Source: FR-6.1, ADR-004.
     */
    const val INITIAL_SECONDS: Int = 60

    /**
     * Seconds added per successful elimination (one +5s per `Match`, not
     * per tile). Source: FR-6.5 / FR-6.8, ADR-004.
     */
    const val REWARD_SECONDS: Int = 5

    /**
     * Hard cap on the timer — 在 v1.0.3 中**不再使用**（重置回 60 后不会超过 60，
     * 所以 cap 不会触发）。保留常量仅为：
     * 1. 兼容已有的单元测试 / 文档引用
     * 2. 防止未来再切回"+N 秒累加"模式时找不到 MAX 常量
     * 3. 防御性编程：如果 v1.0.3 之后又改回加时累加，把这里的代码改回去即可
     *
     * 当前 [rewardOnMatch] 直接重置到 [INITIAL_SECONDS]，不查 [MAX_SECONDS]。
     * Source: FR-6.7, ADR-004 (历史)。
     */
    const val MAX_SECONDS: Int = 90

    // ========================================================================
    // Public API — pure functions
    // ========================================================================

    /**
     * Returns the initial timer value to seed `remainingSeconds` with.
     * Convenience accessor that keeps the literal `60` out of ViewModel
     * code; the ViewModel can also reference [INITIAL_SECONDS] directly.
     */
    fun initialState(): Int = INITIAL_SECONDS

    /**
     * Advance the countdown by one tick. Decrements by 1 second and floors
     * at 0 so the timer never goes negative (defense in depth — the
     * ViewModel is expected to stop ticking once [isGameOver] is true).
     *
     * @param remainingSeconds current timer value, expected `>= 0`
     * @return `remainingSeconds - 1`, clamped to `[0, MAX_SECONDS]`.
     *         Returns `0` if input is `0` (does not become `-1`).
     */
    fun tick(remainingSeconds: Int): Int {
        return (remainingSeconds - 1).coerceAtLeast(0)
    }

    /**
     * Apply the elimination-reward timer reset for one or more detected matches.
     *
     * Behavior (v1.0.3 — 沐风 2026-06-21 反馈：每次消除重置回 60s，不要加时累加，不要 cap 90s):
     * 1. **Empty matches** (FR-6.9): no-op. Returns `(remainingSeconds, 0)` unchanged.
     * 2. **Otherwise**: reset to [INITIAL_SECONDS] (60). The `second` of the
     *    returned pair is the *actual* seconds added — typically +X if timer
     *    was below 60 (we added time back up to 60), or 0 if already at/above 60.
     *
     * Examples (v1.0.3):
     * ```
     * rewardOnMatch(50, listOf(match)) // -> (60, 10) — topped up to 60
     * rewardOnMatch(88, listOf(match)) // -> (60, 0)  — was already past 60, reset
     * rewardOnMatch(60, listOf(match)) // -> (60, 0)  — was exactly 60
     * rewardOnMatch(50, emptyList())   // -> (50, 0)  — no-op (FR-6.9)
     * ```
     *
     * @param remainingSeconds current timer value
     * @param matches          matches detected in the current step; empty
     *                         list means no elimination (e.g. invalid swap)
     * @return `Pair(newRemainingSeconds, actualRewardSeconds)` where
     *         `newRemainingSeconds == INITIAL_SECONDS` and
     *         `actualRewardSeconds = max(0, INITIAL_SECONDS - remainingSeconds)`.
     */
    fun rewardOnMatch(
        remainingSeconds: Int,
        matches: List<Match>,
    ): Pair<Int, Int> {
        // FR-6.9: invalid swap (no matches) does not touch the timer.
        if (matches.isEmpty()) {
            return remainingSeconds to 0
        }
        // v1.0.3: 消除 → 倒计时重置回 60s（每次都是 60s，不累加，无 cap）。
        val newRemaining = INITIAL_SECONDS
        val actualReward = (newRemaining - remainingSeconds).coerceAtLeast(0)
        return newRemaining to actualReward
    }

    /**
     * Game-over predicate. Returns `true` once the timer has run out, i.e.
     * the very first tick that lands on 0.
     *
     * @param remainingSeconds current timer value
     * @return `true` iff `remainingSeconds <= 0`.
     */
    fun isGameOver(remainingSeconds: Int): Boolean {
        return remainingSeconds <= 0
    }
}

// ============================================================================
// Manual test entry (per T-CORE-006 acceptance criteria)
// ============================================================================

// main for manual test
// Verifies the T-CORE-006 invariants:
//   - Constants are 60 / 5 / 90
//   - tick(60) == 59; tick(0) == 0 (no negative)
//   - rewardOnMatch caps at MAX_SECONDS
//   - rewardOnMatch returns 0 reward when already at cap
//   - rewardOnMatch is a no-op on empty matches (FR-6.9)
//   - isGameOver(0) == true; isGameOver(1) == false
// Run with:  kotlinc TimerEngine.kt -include-runtime -d TimerEngine.jar && java -jar TimerEngine.jar
fun main() {
    // --- Constants ---
    check(TimerEngine.INITIAL_SECONDS == 60) {
        "INITIAL_SECONDS should be 60, was ${TimerEngine.INITIAL_SECONDS}"
    }
    check(TimerEngine.REWARD_SECONDS == 5) {
        "REWARD_SECONDS should be 5, was ${TimerEngine.REWARD_SECONDS}"
    }
    check(TimerEngine.MAX_SECONDS == 90) {
        "MAX_SECONDS should be 90, was ${TimerEngine.MAX_SECONDS}"
    }

    // --- initialState ---
    check(TimerEngine.initialState() == 60) {
        "initialState() should be 60, was ${TimerEngine.initialState()}"
    }

    // --- tick ---
    check(TimerEngine.tick(60) == 59) {
        "tick(60) should be 59, was ${TimerEngine.tick(60)}"
    }
    check(TimerEngine.tick(0) == 0) {
        "tick(0) should be 0 (no negative), was ${TimerEngine.tick(0)}"
    }
    check(TimerEngine.tick(1) == 0) {
        "tick(1) should be 0, was ${TimerEngine.tick(1)}"
    }

    // --- rewardOnMatch: a minimal synthetic Match (any axis/length ≥ 3 works) ---
    val fakeMatch = Match(
        tiles = emptyList(),           // tiles not inspected by the engine
        axis = MatchAxis.HORIZONTAL,
        length = 3,
    )
    val matches = listOf(fakeMatch)

    // v1.0.3: 每次消除重置回 60s
    val r1 = TimerEngine.rewardOnMatch(50, matches)
    check(r1.first == 60 && r1.second == 10) {
        "rewardOnMatch(50, [match]) should be (60, 10), was $r1"
    }

    val r2 = TimerEngine.rewardOnMatch(88, matches)
    check(r2.first == 60 && r2.second == 0) {
        "rewardOnMatch(88, [match]) should be (60, 0), was $r2"
    }

    val r3 = TimerEngine.rewardOnMatch(60, matches)
    check(r3.first == 60 && r3.second == 0) {
        "rewardOnMatch(60, [match]) should be (60, 0), was $r3"
    }

    // Empty matches: no-op (FR-6.9)
    val r4 = TimerEngine.rewardOnMatch(50, emptyList())
    check(r4.first == 50 && r4.second == 0) {
        "rewardOnMatch(50, []) should be (50, 0), was $r4"
    }

    // --- isGameOver ---
    check(TimerEngine.isGameOver(0)) {
        "isGameOver(0) should be true"
    }
    check(!TimerEngine.isGameOver(1)) {
        "isGameOver(1) should be false"
    }
    check(TimerEngine.isGameOver(-1)) {
        "isGameOver(-1) should be true (defensive)"
    }

    println("TimerEngine.kt manual test passed:")
    println("  - Constants: INITIAL=${TimerEngine.INITIAL_SECONDS}, " +
            "REWARD=${TimerEngine.REWARD_SECONDS}, MAX=${TimerEngine.MAX_SECONDS}")
    println("  - tick(60)=${TimerEngine.tick(60)}, tick(0)=${TimerEngine.tick(0)}")
    println("  - rewardOnMatch(50, [_])=$r1  <- topped up to 60")
    println("  - rewardOnMatch(88, [_])=$r2  <- reset from 88 to 60")
    println("  - rewardOnMatch(60, [_])=$r3  <- already at 60")
    println("  - rewardOnMatch(50, [])=$r4  <- no-op")
    println("  - isGameOver(0)=${TimerEngine.isGameOver(0)}, isGameOver(1)=${TimerEngine.isGameOver(1)}")
}
