package top.windyvalley.magicsushi.engine

/**
 * 「离开对局」时该把 [GameState] 清成什么样。
 *
 * ## 为什么需要这个函数
 *
 * 玩家离开对局有两条路径，它们的**副作用**不同（一条写历史、一条存快照），
 * 但对 state 的清理**必须完全一致**：
 *
 *   GameViewModel.onQuit()             回菜单（结算面板「返回菜单」、
 *                                      暂停面板「结束本局」）
 *   GameViewModel.onStopWithSnapshot() 挂起回菜单（暂停面板「保留进度」）
 *
 * 曾经这两处各写一遍 `_state.update { it.copy(phase = IDLE) }`，只清了
 * phase。结果 ViewModel 活得比 GameScreen 长，下次从菜单进游戏时首帧
 * 渲染的是**上一局的现场**，玩家看到：
 *
 *   - 分数从旧值跳到 0（startGame() 下一帧才重置）
 *   - 棋盘明显刷一下（旧棋盘被新棋盘替换）
 *
 * 修的时候先补了分数，漏了棋盘；补棋盘时又发现 animFrame 也得清。
 * 三次都是同一个 bug 的不同字段 —— 说明「离开对局要清哪些字段」这件事
 * 需要一份定义，而不是散在两个调用点里靠人记全。
 *
 * ## 为什么清空而不是直接生成新棋盘
 *
 * 清成空棋盘（[Board] 默认值 = 全 null 的 7×7 grid），而不是在这里调
 * `BoardEngine.generateInitialBoard()`：
 *
 * 1. 离开对局和开始新对局是两件事。这里只负责「拆台」，搭台是
 *    `startGame()` / `restoreSnapshot()` 的职责。
 * 2. 若这里生成一副盘，`startGame()` 又会生成另一副，玩家仍会看到一次
 *    替换 —— 等于没修。
 * 3. 空棋盘在 `GameCanvas` 里只画背景网格、不画 tile，视觉上是干净的
 *    空盘，接着被填满，读起来就是正常开局。
 *
 * ## 为什么不影响快照恢复
 *
 * 挂起路径的现场在调用本函数**之前**已经 `saveBlocking` 存盘。
 * `restoreSnapshot()` 是读盘重建 GameState，不依赖 state 里的残留值。
 *
 * ## 保留哪些字段
 *
 * 只保留**跨对局有效**的东西：
 *
 *   highScore   历史记录的派生值，与单局无关（见 [HighScoreDerivation]）
 *   isMuted     用户设置，与单局无关
 *
 * 其余一律清成默认值。`roundFinalized` 也清 —— 它的含义是「本局是否已
 * 结算」，没有本局了自然为 false，留着会让下一局开局时被误判成已结算。
 */
object RoundTeardown {

    /**
     * 把 [current] 清成「不在对局中」的状态。
     *
     * @return phase 为 [GamePhase.IDLE]、棋盘为空、本局数据归零的新 state，
     *         其中 `highScore` 和 `isMuted` 原样保留。
     */
    fun teardown(current: GameState): GameState = current.copy(
        phase = GamePhase.IDLE,
        board = Board(),
        score = 0,
        combo = 0,
        remainingSeconds = TimerEngine.INITIAL_SECONDS,
        isNewRecord = false,
        roundFinalized = false,
        animFrame = null,
        selectedTile = null,
        isRollback = false,
    )
}
