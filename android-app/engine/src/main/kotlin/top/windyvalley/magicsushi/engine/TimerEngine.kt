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
 * ## Design
 *
 * - **Initial time:** 60 seconds (FR-6.1).
 * - **Per-tick decrement:** 1 second (driven by the ViewModel's tick loop;
 *   the engine itself does **not** own a coroutine or counter).
 * - **消除行为：重置回 [INITIAL_SECONDS] (60s)**，见 [resetOnMatch]。
 *   与 match 数量无关 —— 一个 match 和三个 match 结果相同。
 * - **No-op on no matches:** an invalid swap (`matches` empty) must not
 *   touch the timer (FR-6.9). The ViewModel is expected to check
 *   `matches.isNotEmpty()` before calling, but [resetOnMatch] is
 *   defensive and also short-circuits on empty input.
 *
 * ## 历史：奖励时间机制已废弃
 *
 * 早期设计是「每次消除 +5s，上限 90s」（`REWARD_SECONDS` / `MAX_SECONDS`）。
 * v1.0.3 改为重置语义后，那两个常量与 `rewardOnMatch` 返回的
 * `(新值, 加了多少秒)` 二元组都成了残留 —— 二元组的第二个值仅用于渲染
 * "+5s" 飘字。飘字与奖励概念一并废弃，故：
 *
 * - `rewardOnMatch` → [resetOnMatch]，返回单个 `Int`
 * - `REWARD_SECONDS` / `MAX_SECONDS` 删除（删除前唯一使用者是断言其数值的测试）
 * - `GameEvent.TimeReward` 与 `RewardOverlay` 删除
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
 * remaining = TimerEngine.resetOnMatch(remaining, matches)
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
 * - `resetOnMatch(x, matches) == INITIAL_SECONDS` when `matches` is non-empty,
 *   **regardless of `x`** —— 高于 60 也会被拉回 60。
 * - `resetOnMatch(x, emptyList()) == x`（FR-6.9）。
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
     * 也是每次成功消除后重置回的值（见 [resetOnMatch]）。
     * Source: FR-6.1, ADR-004.
     */
    const val INITIAL_SECONDS: Int = 60

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
     * @return `remainingSeconds - 1`, floored at 0.
     *         Returns `0` if input is `0` (does not become `-1`).
     */
    fun tick(remainingSeconds: Int): Int {
        return (remainingSeconds - 1).coerceAtLeast(0)
    }

    /**
     * 倒计时此刻是否应该走表。
     *
     * ## 为什么需要这个判据
     *
     * 消除的奖励语义是「拿回满 [INITIAL_SECONDS] 秒」。但消除**不是瞬间完成
     * 的**：连锁动画每轮约 700ms（3 个 phase × (100ms 停留 + 100ms 间歇)
     * + 轮间 100ms），多轮连锁可以跑好几秒。
     *
     * 如果这段时间照常走表，会出两个问题：
     *
     * 1. **玩家在低时间线上消除却被判负**。剩 1 秒时拼出一个消除，动画
     *    700ms 里 tick 归零 → `GAME_OVER`，而奖励的 60 秒在动画结束后
     *    才写进 state —— 永远等不到。这是玩家会明确感到不公的死法：
     *    「我明明消掉了」。
     * 2. **奖励被动画吃掉**。5 轮连锁约 3.5 秒，玩家拿到的实际是 56 秒
     *    而不是 60 秒 —— 连锁越猛，扣得越多，与「连锁是好事」的直觉相反。
     *
     * ## 语义
     *
     * `settling = true` 表示「消除已判定、结算动画正在播」。这段时间倒计时
     * 冻结，动画结束后恢复。
     *
     * 冻结而不是「补偿动画时长」：补偿要记录动画实际耗时，暂停/切后台会让
     * 这个时长失真；冻结没有这个问题，且玩家看到的就是「数字停住了」，
     * 与「正在结算」的观感一致。
     *
     * @param phaseIsPlaying `phase == PLAYING`。非 PLAYING（暂停、结束、
     *                       IDLE）本来就不该走表。
     * @param settling       是否正处于消除结算窗口。
     * @return 该走表则 `true`。
     */
    fun shouldTick(phaseIsPlaying: Boolean, settling: Boolean): Boolean {
        return phaseIsPlaying && !settling
    }

    /**
     * 消除后重置倒计时。
     *
     * ## 语义：重置，不是奖励
     *
     * 每次成功消除都把倒计时拉回 [INITIAL_SECONDS]，**与 match 数量无关**。
     * 三个 match 和一个 match 的结果完全相同。
     *
     * 为什么不再叫 `rewardOnMatch`：那个名字承诺「奖励」（增量语义），
     * 而实际行为是「重置」（绝对语义）。它还返回 `(新值, 增加了多少秒)`
     * 二元组，那个第二个值只被用来渲染 "+5s" 飘字 —— 飘字连同奖励概念
     * 一起废弃后，二元组也没有存在理由了。
     *
     * 一并删除的历史残留：
     * - `REWARD_SECONDS = 5`（每次消除加 5 秒）
     * - `MAX_SECONDS = 90`（加时累加的上限）
     *
     * 这两个常量在删除前的唯一使用者是**断言它们等于 5 和 90 的测试**，
     * 生产代码零引用。自证式测试不产生价值，一并移除。
     *
     * @param remainingSeconds 当前剩余秒数
     * @param matches          本次检测到的消除。空列表 = 无消除（如无效交换），
     *                         此时原样返回，不碰计时器（FR-6.9）
     * @return 消除后的剩余秒数：有消除则为 [INITIAL_SECONDS]，否则原值
     */
    fun resetOnMatch(
        remainingSeconds: Int,
        matches: List<Match>,
    ): Int {
        // FR-6.9: 无效交换（无消除）不影响计时器。
        if (matches.isEmpty()) return remainingSeconds
        return INITIAL_SECONDS
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
