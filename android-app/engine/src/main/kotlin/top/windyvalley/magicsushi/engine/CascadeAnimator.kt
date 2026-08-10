package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.delay

/**
 * 播放一次 swap 引发的完整 cascade 动画序列。
 *
 * ## 为什么要从 GameViewModel 抽出来
 *
 * 这段循环此前内嵌在 `onSwapAttempt` 里，和计分、音效、事件流、
 * 异常处理挤在同一个 60 行的协程块中。它承担的是**时序编排**这一件
 * 独立的事：每个 cascade round 播 3 帧，帧间 100ms，round 间 100ms，
 * 逐轮推进棋盘状态。
 *
 * 抽出后的直接收益是**可测**：时序与棋盘推进逻辑不再需要启动整个
 * ViewModel 才能验证。P1-2（双份重力）和 D4（spawn tile 身份）这两个
 * bug 都长在这段循环里，而它们此前只能靠真机观察。
 *
 * ## 单一职责
 *
 * 本函数**只**负责"按时序把帧推给渲染层"。它不计分、不放音效、不发
 * 事件、不写最终棋盘 —— 那些仍归调用方。它对外只暴露两个副作用出口：
 * [onFrame] 和 [delay]。
 *
 * ## 中断语义
 *
 * 每轮开始前调用 [shouldContinue]。返回 false 立即停止（用于
 * `phase != PLAYING` 的场景，如超时、暂停）。这保持了原实现中
 * `if (_state.value.phase != GamePhase.PLAYING) break` 的行为。
 *
 * 函数本身是 `suspend`，协程取消会正常向上传播 —— 调用方的
 * CancellationException 处理不受影响。
 *
 * @param startBoard   swap 完成、尚未开始消除的棋盘。
 * @param cascades     每一轮检测到的 matches，来自
 *                     [CascadeEngine.cascadeUntilStable] 的结果。
 * @param phaseMs      单帧停留时长。
 * @param gapMs        帧与帧、轮与轮之间的间歇。
 * @param shouldContinue 每轮开始前的守卫；false 表示中止后续轮次。
 * @param awaitResume  每个帧间等待之后的挂起点。默认空实现（不可暂停）。
 *                     传入「暂停时挂起、恢复时返回」的实现即可让动画
 *                     真正暂停在原地 —— 协程始终存活，不推进帧也不写
 *                     state，恢复后从同一位置继续。
 *
 *                     ⚠️ 不要用 `while (paused) delay(16)` 轮询实现：
 *                     虚拟时钟下会让 advanceTimeBy 无限推进，真机上白耗电。
 *                     用 flow 的 `first { !paused }` 之类的零轮询写法。
 * @param onFrame      把"当前棋盘 + 当前帧"推给渲染层。`board` 为
 *                     null 表示沿用上一次推送的棋盘（仅换帧）。
 * @return 最后一轮补充完毕的棋盘。调用方通常会用
 *         `CascadeResult.finalBoard` 覆盖它作为权威终态；返回值主要
 *         用于测试断言与调试。
 */
suspend fun playCascadeAnimation(
    startBoard: Board,
    cascades: List<List<Match>>,
    phaseMs: Long,
    gapMs: Long,
    rounds: List<CascadeRound> = emptyList(),
    shouldContinue: () -> Boolean = { true },
    awaitResume: suspend () -> Unit = {},
    onFrame: (board: Board?, frame: AnimFrame) -> Unit,
): Board {
    /**
     * 帧间等待 = 正常延时 + 「若处于暂停态则挂在这里」。
     *
     * 把两者绑在一处，是为了保证**每个**等待点都是可暂停的 ——
     * 漏掉任何一个，暂停就会在那一帧「漏过去」，表现为暂停后画面
     * 还会再动一下。
     */
    suspend fun waitPausable(ms: Long) {
        delay(ms)
        awaitResume()
    }

    // 逐轮跟踪棋盘。
    //
    // ⚠️ 关键：每个 cascade round 的 matches 是在**不同的** board 状态上
    // 检测出来的：
    //   Round 0 matches 在 startBoard 上检测 → 重力+补充 → board1
    //   Round 1 matches 在 board1 上检测     → 重力+补充 → board2
    // 如果每轮都传 startBoard 给 generateFrames，Round 1 的 preFallRow
    // 会基于 startBoard 而非 board1，算出不该移动的 tile 的非零 offsetY。
    var currentBoard = startBoard

    for ((roundIdx, round) in cascades.withIndex()) {
        if (!shouldContinue()) break

        // round 之间的间歇放在**下一轮开头**而非上一轮结尾。
        //
        // 这样守卫中途返回 false 时不会多等一个 gap（玩家感知为超时/暂停
        // 后画面多停顿 100ms）。放结尾则必须预判"下一轮会不会播"，那需要
        // 重复调用 shouldContinue —— 会要求调用方保证守卫幂等，不是好接口。
        if (roundIdx > 0) {
            waitPausable(gapMs)
        }

        // 本轮的重力与补充结果**来自 CascadeEngine**，不在这里重算。
        //
        // ⚠️ 曾经这里自己调 applyGravity + spawnRefill。那看似「同源」
        // （fallen 与 refilled 确实来自同一次计算），但与 CascadeEngine
        // 算出的 finalBoard 是**两批不同的随机 tile** —— spawnRefill 每次
        // 都重新摇 type、重新发 id。玩家看到的是掉下来的寿司和最终落定的
        // 不是同一个，每次消除都发生。
        //
        // rounds 与 cascades 等长且一一对应；缺失时退化为「只播消除与下落、
        // 不播 spawn-in」，而不是伪造一批新 tile 蒙混过去。
        val snapshot = rounds.getOrNull(roundIdx)
        val fallen = snapshot?.fallen ?: GravityEngine.applyGravity(currentBoard, round)
        val refilled = snapshot?.refilled

        val frames = AnimationEngine.generateFrames(
            currentBoard,
            round,
            fallenBoard = fallen,
            refilledBoard = refilled,
        )

        // 帧 0: Fade Out —— 同时推送本轮起始棋盘。
        onFrame(currentBoard, frames[0])
        waitPausable(phaseMs)
        waitPausable(gapMs)

        // 帧 1: Fall
        onFrame(null, frames[1])
        waitPausable(phaseMs)
        waitPausable(gapMs)

        // 帧 2: Spawn In
        onFrame(null, frames[2])
        waitPausable(phaseMs)

        // 推进到下一轮起点。与 SpawnIn 帧同源，故「飞进来的 tile」
        // 与「下一轮站在那格的 tile」id 一致。
        //
        // refilled 缺失时退回 fallen：既然没有权威的补充结果，就不臆造
        // 一批 tile 塞进下一轮。权威终态始终由调用方用
        // CascadeResult.finalBoard 覆盖，所以这里的退化不影响最终棋盘。
        currentBoard = refilled ?: fallen
    }

    return currentBoard
}
