package top.windyvalley.magicsushi.engine

/**
 * GameEvent.kt — 一次性游戏事件（transient signal）。
 *
 * **Pure Kotlin, ZERO Android dependencies.**
 *
 * ---
 * ## 与 [GameState] 的分工
 *
 * | | [GameState] | [GameEvent] |
 * |---|---|---|
 * | 回答的问题 | "现在是什么" | "刚刚发生了什么" |
 * | 读取语义 | 可重复读取，幂等 | 只应被消费一次 |
 * | 载体 | `StateFlow`（有当前值） | `SharedFlow`（无当前值） |
 *
 * ## 为什么一次性信号不能塞进 GameState
 *
 * `GameState` 是 data class，下游 Compose 靠 **值变化** 触发副作用
 * （`LaunchedEffect(key)`）。当同一个信号连续以相同值触发时，字段值
 * 没有变化，`LaunchedEffect` 不会重启，**信号就丢了**。
 *
 * 真实案例：`lastRewardSeconds` 曾是 `GameState` 的字段，
 * 而 `+5s` 是当时最常见的奖励值。连续两次消除都奖励 5 秒时：
 *
 * ```
 * 第一次消除：lastRewardSeconds  null → 5   ✅ 飘字显示
 * 第二次消除：lastRewardSeconds     5 → 5   ❌ 值未变，飘字不显示
 * ```
 *
 * 用 `SharedFlow<GameEvent>` 承载则不存在这个问题 —— 每次 emit 都是一次
 * 独立投递，与值是否相同无关。
 *
 * （该案例中的奖励时间机制已废弃，`TimeReward` 事件与 `RewardOverlay`
 * 已删除。但教训适用于任何一次性信号，故保留在此。）
 *
 * ## 使用约定
 *
 * - VM 侧用 `tryEmit`（配 `extraBufferCapacity`）发射，**不要**用
 *   `emit`：后者在无订阅者时会挂起协程。
 * - UI 侧用 `collect` 消费，配合 `filterIsInstance<T>()` 只取关心的类型。
 * - 事件**不参与状态恢复**：进程重建后历史事件不会重放，这是有意为之
 *   （没人想在旋屏后重看一遍破纪录提示）。需要跨重建存活的信息属于
 *   [GameState]，不属于这里。
 */
sealed interface GameEvent {

    /**
     * 交换无效，棋盘即将弹回原位。可用于驱动震动反馈或抖动动画。
     *
     * 注意：棋盘的**弹回渲染**仍由 `GameState.isRollback` 驱动 —— 那是
     * 一个持续状态（"当前正处于弹回过程中"），确实属于 state。本事件只
     * 负责"刚刚发生了一次无效交换"这个瞬时通知。
     */
    data object SwapRejected : GameEvent

    /**
     * 本局打破了历史最高分。
     *
     * @property score 打破纪录时的最终分数。
     */
    data class NewRecord(val score: Int) : GameEvent

    /**
     * 棋盘陷入死局（任何相邻交换都凑不出三连），已自动重排。
     *
     * 必须让玩家知道 —— 否则棋盘无故跳变会被当成 bug。重排本身不是惩罚，
     * 也不扣时间扣分，只是把无解局面换成有解的。
     *
     * 建模为事件而非 [GameState] 字段：这是**瞬时通知**，一次重排提示一次。
     * 做成 state 字段会遇到「同一个 true 值连续两次重排时 Compose 认为
     * 没变化、第二次不触发」的经典陷阱，还得额外加清除方法复位。
     */
    data object BoardReshuffled : GameEvent
}
