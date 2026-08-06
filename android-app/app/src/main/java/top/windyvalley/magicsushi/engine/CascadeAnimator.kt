package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.delay

/**
 * 播放一次 swap 引发的完整 cascade 动画序列（FIX_PLAN P1-1）。
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
 * CancellationException 处理不受影响（FIX_PLAN P0-1）。
 *
 * @param startBoard   swap 完成、尚未开始消除的棋盘。
 * @param cascades     每一轮检测到的 matches，来自
 *                     [CascadeEngine.cascadeUntilStable] 的结果。
 * @param phaseMs      单帧停留时长。
 * @param gapMs        帧与帧、轮与轮之间的间歇。
 * @param shouldContinue 每轮开始前的守卫；false 表示中止后续轮次。
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
    shouldContinue: () -> Boolean = { true },
    onFrame: (board: Board?, frame: AnimFrame) -> Unit,
): Board {
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
            delay(gapMs)
        }

        // 本轮的重力与补充各算一次，供帧生成与下一轮共用。
        //
        // 这两份结果必须同源（FIX_PLAN P1-2 / D4）：
        //   - fallen   决定 SpawnIn 帧里哪些格子是空的
        //   - refilled 决定飞进来的 tile 的真实 id 与 type
        // 若各算一次，动画显示的寿司和落定后的寿司会是两个不同的 tile。
        val fallen = GravityEngine.applyGravity(currentBoard, round)
        val refilled = BoardEngine.spawnRefill(fallen)

        val frames = AnimationEngine.generateFrames(
            currentBoard,
            round,
            fallenBoard = fallen,
            refilledBoard = refilled,
        )

        // 帧 0: Fade Out —— 同时推送本轮起始棋盘。
        onFrame(currentBoard, frames[0])
        delay(phaseMs)
        delay(gapMs)

        // 帧 1: Fall
        onFrame(null, frames[1])
        delay(phaseMs)
        delay(gapMs)

        // 帧 2: Spawn In
        onFrame(null, frames[2])
        delay(phaseMs)

        // 推进到下一轮起点。与 SpawnIn 帧同源，故「飞进来的 tile」
        // 与「下一轮站在那格的 tile」id 一致。
        currentBoard = refilled
    }

    return currentBoard
}
