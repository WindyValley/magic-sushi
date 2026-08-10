package top.windyvalley.magicsushi.engine

/**
 * 暂停的准入规则（纯逻辑）。
 *
 * ## 为什么需要这个类型
 *
 * `GameViewModel.onPause()` 曾经**无条件**把 phase 改成 [GamePhase.PAUSED]，
 * 而它的两个调用来源都与「对局是否在进行」无关：
 *
 *     ON_PAUSE  → onPause()              切后台、弹系统对话框、分屏…
 *     ON_STOP   → onStopWithSnapshot()   内部也调 onPause()
 *
 * 于是玩家在**结算面板**上切后台再回来，看到的是暂停面板 —— 那一局已经
 * 结束了，"暂停"没有任何意义，而且回到前台不自动继续（见
 * `onSystemResume`），玩家会卡在一个点「继续」才能离开的面板上。
 *
 * ## 顺带修掉的死代码
 *
 * 更隐蔽的后果在 `onStopWithSnapshot()`：
 *
 *     onPause()                       // 无条件置 PAUSED
 *     val s = _state.value            // 读到的必然是 PAUSED
 *     val worthSaving = … s.phase != IDLE && s.phase != GAME_OVER
 *
 * 那两个条件**永远为真** —— 它们想挡的状态在读之前就被覆盖掉了。快照
 * 是否值得存，实际上只剩 `currentRoundRecorded` 和 `isRestorable` 在把关。
 * 把暂停变成有条件的之后，phase 能如实传到判据里，这两个条件才真正生效。
 *
 * ## 为什么放在 engine
 *
 * 同 [RoundSettlement] / [RoundExitOptions]：`GameViewModel` 依赖
 * `Context` / `SoundPool`，单测要 Robolectric。而"哪些 phase 可以暂停"
 * 是纯判断，放 engine 就能用普通 JUnit 钉住每个状态的行为 —— 上面那个
 * 死代码之所以能长期存在，正是因为它没有任何测试覆盖。
 */
object PauseRules {

    /**
     * 该 phase 下是否应该转入 [GamePhase.PAUSED]。
     *
     * 只有**正在进行**的对局能被暂停。逐状态的理由：
     *
     * | phase | 可暂停 | 理由 |
     * |---|---|---|
     * | [GamePhase.PLAYING]   | 是 | 唯一真正需要暂停的状态 |
     * | [GamePhase.PAUSED]    | 否 | 已经暂停，再置一次是空操作 |
     * | [GamePhase.GAME_OVER] | 否 | 这局已结束，必须停留在结算面板 |
     * | [GamePhase.IDLE]      | 否 | 没有对局可暂停（在菜单/已挂起回菜单）|
     *
     * ⚠️ [GamePhase.IDLE] 返回 false 尤其重要：玩家停在菜单时切后台也会
     * 触发 `ON_PAUSE`，若把 IDLE 改成 PAUSED，「挂起回菜单」留下的那个
     * IDLE 就被抹掉了 —— 而 `onStopWithSnapshot` 正靠 phase 区分该不该
     * 保住快照。
     */
    fun shouldPause(phase: GamePhase): Boolean = phase == GamePhase.PLAYING
}
