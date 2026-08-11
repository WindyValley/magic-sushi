package top.windyvalley.magicsushi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.HistoryRepository
import top.windyvalley.magicsushi.data.PrefsRepository
import top.windyvalley.magicsushi.data.SnapshotRepository
import top.windyvalley.magicsushi.engine.BoardEngine
import top.windyvalley.magicsushi.engine.Board
import top.windyvalley.magicsushi.engine.AnimationEngine
import top.windyvalley.magicsushi.engine.CascadeEngine
import top.windyvalley.magicsushi.engine.DeadlockEngine
import top.windyvalley.magicsushi.engine.GameEvent
import top.windyvalley.magicsushi.engine.GameSnapshot
import top.windyvalley.magicsushi.engine.TileIdGenerator
import top.windyvalley.magicsushi.engine.GameRecord
import top.windyvalley.magicsushi.engine.GravityEngine
import top.windyvalley.magicsushi.engine.HighScoreDerivation
import top.windyvalley.magicsushi.engine.HighScoreRules
import top.windyvalley.magicsushi.engine.RoundSettlement
import top.windyvalley.magicsushi.engine.RoundTeardown
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.engine.GameState
import top.windyvalley.magicsushi.engine.MatchEngine
import top.windyvalley.magicsushi.engine.PauseRules
import top.windyvalley.magicsushi.engine.ScoreEngine
import top.windyvalley.magicsushi.engine.SwapResult
import top.windyvalley.magicsushi.engine.TimerEngine
import top.windyvalley.magicsushi.engine.playCascadeAnimation
import top.windyvalley.magicsushi.engine.playReshuffleAnimation

/**
 * GameViewModel.kt — UI state coordinator for Magic Sushi.
 *
 * Owns the single source of truth ([state]) for the game, drives the
 * countdown timer, and orchestrates calls into the Engine + Data + Audio
 * layers in response to user gestures. The ViewModel is the **only**
 * component that mutates [state] — the UI is read-only.
 *
 * ---
 * ## Architecture (per 02-design.md §2.1, ADR-002)
 *
 * ```
 *   ┌────────────────────────────────────────────────────────────┐
 *   │ UI (Compose)                                               │
 *   │   GameScreen / GameCanvas / ScoreOverlay / GameOverDialog  │
 *   │   ─ reads state: StateFlow<GameState> via collectAsState() │
 *   │   ─ writes:     fun onTileTapped / onDragEnd / onRestart … │
 *   └────────────────────────────────┬───────────────────────────┘
 *                                    │ events
 *                                    ▼
 *   ┌────────────────────────────────────────────────────────────┐
 *   │ GameViewModel (this class)                                 │
 *   │   _state: MutableStateFlow<GameState>  ← private writer    │
 *   │   state:  StateFlow<GameState>          ← public reader     │
 *   │   viewModelScope.launch { while(playing) { delay(1000) … }}│
 *   └────┬────────────────┬────────────────┬─────────────────────┘
 *        │                │                │
 *        ▼                ▼                ▼
 *   ┌─────────┐    ┌──────────┐    ┌──────────────┐
 *   │ Engines │    │ PrefsRepo│    │  SoundPlayer │
 *   │ (pure)  │    │ (persis) │    │   (audio)    │
 *   └─────────┘    └──────────┘    └──────────────┘
 * ```
 *
 * Engines are stateless (`object`s) — they take inputs and return outputs.
 * `PrefsRepository` persists high score + muted flag. `SoundPlayer`
 * pre-loads 4 OGG sound effects via `SoundPool` and plays them on demand.
 * The ViewModel glues them all together.
 *
 * ## Swap → Match → Score → Gravity → Cascade → Reward pipeline
 *
 * A single user swap triggers this sequence in `onSwapAttempt()`:
 *
 * 1. **Attempt swap** — `BoardEngine.attemptSwap(board, from, to)` returns
 *    `(newBoard, SwapResult)`. Only `Success` proceeds; `InvalidSwap`
 *    (not adjacent) and `BoardLocked` (mid-animation) are dropped.
 * 2. **Detect matches** — `MatchEngine.detectMatches(newBoard)`.
 * 3. **Empty** (no 3-in-a-row) — rollback flow:
 *    a. Flip `isRollback = true` and set `board = swappedBoard` so the UI
 *       shows the tentative swap.
 *    b. `delay(150)` (so the UI can show the rollback animation).
 *    c. Revert `board = original` and clear `isRollback`.
 *    d. (A haptic feedback event would be emitted here in a future
 *       enhancement — see the "Out of scope" section below.)
 * 4. **Non-empty** (matches found) — full cascade flow:
 *    a. `CascadeEngine.cascadeUntilStable(newBoard, matches)` — runs
 *       gravity → re-detect until stable; returns `(cascades, finalBoard)`.
 *    b. Score each cascade round with `ScoreEngine.scoreForMatch(match, combo)`,
 *       bumping the combo index for each subsequent round.
 *    c. `TimerEngine.rewardOnMatch(remaining, allMatches)` — adds +5s (capped
 *       at 90s, FR-6.5 / FR-6.7).
 *    d. Play a sound effect: `playMatch()` for a single round, `playCombo()`
 *       for chained cascades.
 *    e. Update `state` with the new `board` / `score` / `combo` /
 *       `remainingSeconds` and clear `selectedTile`.
 *
 * ## Re-entrancy guard
 *
 * `swapProcessing` is a `var` (single-threaded — viewModelScope is
 * `Dispatchers.Main.immediate` by default) that blocks re-entrant swap
 * attempts while a previous swap is still in flight (e.g. during the
 * 150ms rollback delay). The UI could also be guarded by
 * `Board.swapLock` / `Board.cascadeLock`, but those are engine-level
 * state, not gesture-level — `swapProcessing` is the gesture-level
 * counterpart.
 *
 * ## Lifecycle
 *
 * - **Constructed** via [GameViewModelFactory] from the Activity. The
 *   `init` block immediately calls [startGame] so the first
 *   `state` emission lands in `PLAYING`. (If you want a "Start" button
 *   in v2, change `init` to do nothing and gate the timer on a
 *   user-triggered `startGame()` call.)
 * - **Paused** by `onPause()` — flips `phase` to `PAUSED` and cancels
 *   the timer coroutine.
 * - **Resumed** by `onResume()` — if `phase` is `PAUSED`, flips back to
 *   `PLAYING` and re-launches the timer. (If the user backgrounded the
 *   app after `GAME_OVER`, `onResume` is a no-op.)
 * - **Cleared** by `onCleared()` — cancels the timer coroutine. The
 *   coroutine is also cancelled when `viewModelScope` is cancelled
 *   (Activity finished / process killed), so this is belt-and-suspenders.
 *
 * ## Out of scope (intentionally deferred to later tasks)
 *
 * - **Haptic feedback** on invalid swap. The `isRollback` flag is the
 *   signal — Compose UI can fire a `HapticFeedback` when it observes the
 *   transition. The VM does not depend on Android's `Vibrator` directly
 *   (no Android imports beyond `lifecycle.ViewModel` and `viewModelScope`).
 * - **Floating "+5s" / "+90" text** — would be rendered by a
 *   `MatchFeedbackOverlay` Composable observing state changes.
 * - **Sound on game-over** — T-AUDIO-001 doesn't define a `playGameOver()`
 *   SFX; if added, the VM would call it from `onGameOver()`.
 * - **Refill (top-of-column spawn)** — not yet implemented in the Engine
 *   layer; once it is, the VM will fold it into the cascade flow.
 *
 * @see GameState for the UI snapshot shape
 * @see GamePhase for the lifecycle state machine
 * @see GameViewModelFactory for the DI wiring
 */
class GameViewModel(
    private val prefsRepo: PrefsRepository,
    private val historyRepo: HistoryRepository,
    private val soundPlayer: SoundPlayer,
    private val snapshotRepo: SnapshotRepository,
) : ViewModel() {

    // ========================================================================
    // Private state
    // ========================================================================

    /**
     * Private writer. The UI never touches this directly — it only sees
     * the read-only [state] view. Updates use [MutableStateFlow.update]
     * so concurrent writes are atomic.
     *
     * Initial value: pull `isMuted` / `highScore` from Prefs so the very
     * first UI render has correct values. `board` / `remainingSeconds` /
     * `phase` are overwritten by [startGame] in the `init` block.
     */
    private val _state = MutableStateFlow(
        GameState(
            isMuted = prefsRepo.isMuted(),
            // highScore 初始为默认 0，由 init 里的 historyRepo.records
            // collect 立刻填上真实值（历史记录的 max）。
            //
            // 刻意不在这里同步读一次：最高分不再单独持久化，没有同步读的
            // 入口了 —— 这正是派生方案想要的效果（只有一条数据来源）。
        )
    )

    /**
     * Public read-only view. Compose subscribes via `collectAsState()`.
     */
    val state: StateFlow<GameState> = _state.asStateFlow()

    /**
     * One-shot game events (see [GameEvent]).
     *
     * `extraBufferCapacity = 8` so [tryEmit] never drops an event when the
     * UI is momentarily busy — with `replay = 0` and no buffer, `tryEmit`
     * would silently return `false` whenever no collector was ready.
     *
     * Deliberately **not** part of [GameState]: transient signals that can
     * fire twice with the same value (e.g. two consecutive `+5s` rewards)
     * are lost when modelled as data-class fields, because Compose keys
     * side effects off value changes. See [GameEvent] for the full rationale.
     */
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)

    /** Public read-only event stream. Compose collects this in a `LaunchedEffect`. */
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /**
     * 历史记录流（分数降序，同分新的在前，最多
     * [top.windyvalley.magicsushi.engine.GameHistory.MAX_RECORDS] 条）。
     *
     * 直接转发 Repository 的 Flow —— VM 不缓存，因为历史界面是独立屏幕，
     * 每次进入重新订阅即可，没有必要让它常驻内存。
     */
    val history: Flow<List<GameRecord>> = historyRepo.records

    /**
     * The currently-running timer coroutine, if any. Held as a `Job?` so
     * we can [cancel][Job.cancel] it on pause / restart / clear. There is
     * only ever one timer at a time — starting a new one first cancels
     * the old.
     */
    private var timerJob: Job? = null

    /**
     * Gesture-level re-entrancy guard. Set to `true` for the duration of
     * [onSwapAttempt] (including the 150ms rollback delay) so the user
     * can't fire a second swap mid-flight. Reset to `false` at the end of
     * every swap attempt regardless of outcome.
     *
     * Single-threaded: viewModelScope's default dispatcher is
     * `Dispatchers.Main.immediate`, so a plain `var` (not `AtomicBoolean`)
     * is safe.
     */
    private var swapProcessing = false

    /**
     * The currently-running swap/animation coroutine, if any. Held as a
     * `Job?` so we can [cancel][Job.cancel] it on pause / restart.
     * There is only ever one swap at a time — starting a new one first
     * cancels the old.
     */
    private var swapJob: Job? = null

    /**
     * 局代际计数。每次 [startGame]（含 [onRestart]）自增。
     *
     * ## 为什么需要它
     *
     * `onRestart()` 只 cancel 了 `timerJob`，**没有 cancel `swapJob`**。
     * 于是在 cascade 动画播放期间点「重新开始」会出现：
     *
     * 1. `startGame()` 写入全新棋盘，`phase` 重新变成 `PLAYING`
     * 2. 旧的 swap 协程**还在跑**，它的 `shouldContinue` 守卫检查
     *    `phase == PLAYING` —— 此刻恰好成立，于是守卫放行
     * 3. 旧动画继续把旧局的帧写进新局的 state
     *
     * 光靠 phase 无法区分「还在本局」和「已经是下一局了」，因为两者的
     * phase 都是 `PLAYING`。代际计数提供这个区分：swap 协程启动时捕获
     * 当时的代际，落盘前比对，不一致就放弃写入。
     */
    private var roundGeneration = 0L

    /**
     * 本局成绩是否已写入历史记录。
     *
     * ## 为什么需要它
     *
     * 成绩要在**三条路径**都入库（这是用户报的「重开/退出时成绩没进历史」
     * 的修法）：
     *
     * 1. 倒计时归零 → [onGameOver]
     * 2. 中途重新开始 → [onRestart]
     * 3. 退出到主菜单 / 结束进程 → [onQuit]
     *
     * 但三者会**串联触发**：game over 后玩家点「重新开始」，onGameOver 和
     * onRestart 都会跑。若各写一次，同一局会在历史里出现两条。
     *
     * 这个标记保证「一局最多入库一次」。由 [startGame] 复位。
     *
     * ## 为什么它是 [GameState] 的投影而不是独立字段
     *
     * 退出确认弹窗要靠这个事实决定「保留进度」该不该出现（见
     * [RoundExitOptions]），所以 UI 必须能读到它。
     *
     * 一旦 UI 要读，就有两种做法：VM 里存一份 + 往 state 里同步一份，或者
     * 干脆让这个属性**就是** state 里那个字段的别名。选后者 —— 前者是典型的
     * 双份真相，本项目已经在最高分上栽过一次（热缓存写入未同步生效，
     * 见 commit 24f7863）。这里没有任何一行代码需要「记得同步」。
     */
    private var currentRoundRecorded: Boolean
        get() = _state.value.roundFinalized
        set(value) {
            _state.update { it.copy(roundFinalized = value) }
        }

    /**
     * 本局是否已通过「保留并返回首页」挂起。
     *
     * ## 为什么需要它
     *
     * [onSuspendToMenu] 会故意存下快照，同时把 phase 置成 IDLE（离开游戏屏
     * 后不该停在 PAUSED）。但 [onStopWithSnapshot] 判定「不值得存」时会
     * **主动清快照**，而 IDLE 正是它的排除条件之一。
     *
     * 于是没有这个标记就会出现：玩家点「保留并返回首页」→ 在菜单划掉应用
     * → ON_STOP 看到 phase == IDLE → 把刚刚保留的那一局清掉。
     *
     * 换句话说，phase 单独无法区分两种 IDLE：
     * - 从没开局 / 已结算退出 → 该清快照
     * - 挂起后回菜单 → 必须保住快照
     *
     * 由 [startGame]（开新局）和 [restoreSnapshot]（快照已被消费）复位。
     */
    private var roundSuspendedToMenu = false

    init {
        // 把音效的静音判断绑定到 PrefsRepository —— 静音状态的
        // 唯一数据源。SoundPlayer 自己不再存一份，避免 toggleMute 时漏同步。
        soundPlayer.bindMutedProvider(prefsRepo::isMuted)

        // GameState.isMuted 是 prefs 的**派生投影**（供 UI 渲染
        // 图标用），靠 collect 保持同步，而不是在 toggleMute 里手工赋值。
        // 这样即使将来别处调用 prefsRepo.setMuted()，UI 也会自动跟上。
        viewModelScope.launch {
            prefsRepo.mutedFlow.collect { muted ->
                _state.update { it.copy(isMuted = muted) }
            }
        }
        // 最高分是**历史记录的派生值**，不再单独持久化。
        //
        // 曾经它存在 prefs 里，于是同一个事实有两个存储位置，必须靠调用方
        // 记得同时写 —— 本项目为此付过两次代价（saveHighScore 漏调用点、
        // 热缓存没同步）。改成从历史 max 派生后，历史是唯一数据源，
        // 「忘了同步」这件事在结构上不可能发生。
        //
        // 正确性依赖两条不变式（RoundSettlement 破纪录必然入库 +
        // GameHistory 按分数降序裁剪），见 HighScoreDerivation 的说明，
        // 且有 HighScoreDerivationTest 守着。
        viewModelScope.launch {
            historyRepo.records.collect { records ->
                _state.update {
                    it.copy(highScore = HighScoreDerivation.highScoreOf(records))
                }
            }
        }

        // ⚠️ 刻意**不**在这里 startGame()。
        //
        // 批次 C 引入开始界面后，VM 的构造时机与「玩家想开局」不再等同：
        // `by viewModels()` 是懒加载，但 MainActivity 的生命周期观察者
        // （ON_PAUSE/ON_RESUME → viewModel.onPause()/onResume()）在 onCreate
        // 里就注册了，一旦触发就会实例化 VM。于是玩家还停在菜单，
        // 构造函数里的 startGame() 已经让倒计时在后台跑起来 ——
        // 等他点【开始游戏】时时间已经少了好几秒。
        //
        // 现在开局的唯一触发点是 UI 显式调用 startGame()（见
        // MainActivity 的 AppScreen.Game 分支）。phase 保持默认的 IDLE，
        // 语义正好对上：「还没开始」。
    }

    // ========================================================================
    // Game lifecycle
    // ========================================================================

    /**
     * Start a fresh game. Resets board, score, combo, timer, and selection.
     * Preserves `isMuted` and `highScore` from prefs (they're user
     * preferences, not per-round state). Transitions to [GamePhase.PLAYING]
     * and starts the countdown timer.
     *
     * Safe to call multiple times — it always cancels the previous timer
     * before starting a new one.
     */
    fun startGame() {
        // 先 cancel 任何现有的 timer job，避免双重 timer 并行导致
        // "倒计时跳秒/超过 60s 重置" Bug B。startTimer() 内部也会 cancel，
        // 但显式 cancel 增加了安全网。
        timerJob?.cancel()
        // 同时 cancel 在飞的 swap/动画协程：旧局的动画不应继续往新局写帧。
        // 与 roundGeneration 是双重防线 —— cancel 负责让它停下，代际比对
        // 负责在「cancel 与协程实际停止之间的窗口」里挡住迟到的写入。
        swapJob?.cancel()
        roundGeneration++
        // 新一局尚未入库。
        //
        // ⚠️ 顺序要紧：调用方（onRestart / onQuit）必须**先**写旧局成绩
        // 再调 startGame，否则这里一复位就再也认不出「旧局还没入库」。
        currentRoundRecorded = false
        // 开新局 → 之前的「挂起」状态作废。
        roundSuspendedToMenu = false
        // 开新局 = 放弃上一局的中断现场。
        //
        // 这是所有「新局」的唯一入口（onRestart 重开、菜单「开始新游戏」
        // 都走这里），所以清快照放这一处就够，不必在每个调用方重复。
        //
        // 不清的后果：玩家重开一局后退出，菜单仍显示「继续上局」，点进去
        // 回到的是**更早那一局**的残局。
        viewModelScope.launch { snapshotRepo.clear() }
        _state.update { current ->
            val initialBoard = BoardEngine.generateInitialBoard()
            // 开局也可能撞上死局（概率极低但非零），直接重排到有解为止。
            //
            // 刻意不发 BoardReshuffled 事件：玩家还没看过原始棋盘，
            // 提示「已重排」只会让人困惑「重排了什么」。
            val settledBoard = DeadlockEngine.reshuffleIfDeadlocked(initialBoard).board
            GameState(
                board = settledBoard,
                remainingSeconds = TimerEngine.INITIAL_SECONDS,
                phase = GamePhase.PLAYING,
                isMuted = prefsRepo.isMuted(),
                // ⚠️ 必须显式带过来。这里是**重建**而非 copy，漏掉就会把
                // 最高分重置成默认 0，UI 上表现为开新局后纪录消失
                // （Flow 下次回灌才恢复，而只有历史变化才会回灌 ——
                // 实际是一直显示 0 直到下一局结算）。
                highScore = current.highScore,
            )
        }
        startTimer()
    }

    /**
     * 把本局成绩写入历史记录。幂等 —— 同一局重复调用只写一次。
     *
     * ## 为什么 0 分不入库
     *
     * 玩家进游戏没动就退出、或误触重开，会产生一堆 0 分记录淹没历史。
     * 0 分不构成「一局游戏」。
     *
     * ## 为什么用 viewModelScope 而不是调用方的协程
     *
     * 退出路径（[onQuit]）之后进程可能马上结束。DataStore 的写是 suspend，
     * 挂在 viewModelScope 上至少能在 Activity 存活期间完成；真要保证
     * 「进程被杀前一定写完」得用 `GlobalScope` 或 WorkManager，但历史记录
     * 丢一条不值得引入那个复杂度。
     *
     * 注意 [onQuit] 里对此有额外处理（等写完再退出进程）。
     */
    private fun recordCurrentRound() {
        // 结算规则在 engine 的 RoundSettlement 里（纯函数、有测试覆盖）。
        //
        // ⚠️ 曾经 saveHighScore 只在 onGameOver（倒计时归零）里调用，而
        // recordCurrentRound 有三个调用方（onGameOver / onRestart / onQuit）。
        // 于是正常退出、点重开这两条路径只写历史、不存最高分 —— 玩家看到
        // 「历史记录有成绩，但最高分一直是 0」，且历史里的「新纪录」标记
        // 也永远是 false（isNewRecord 当时只在 onGameOver 里被赋值）。
        //
        // 现在「结算一局」是一个不可分割的动作，三条路径共用，不存在
        // 「某条路径记得做 A 忘了做 B」的空间。
        // ⚠️ 基准取 _state.value.highScore，不是 prefsRepo.getHighScore()。
        //
        // 最高分现在是历史记录的派生值（见 init 里的 collect）。而 addRecord
        // 是异步的 —— 本函数返回时 Flow 还没回灌，所以此刻的 state 里存的
        // 正是「入库之前」的最高分，恰好就是结算需要的基准。
        //
        // 不能改成「先 await addRecord 再算」：结算要同步产出 isNewRecord
        // 给庆祝动画和历史记录的标记用，挂起会让这一帧的 UI 拿不到结果。
        val outcome = RoundSettlement.settle(
            score = _state.value.score,
            savedHighScore = _state.value.highScore,
            alreadyRecorded = currentRoundRecorded,
        )

        // 无论是否入库都标记为已处理，免得后续路径反复检查。
        currentRoundRecorded = true

        if (outcome.isNewRecord) {
            // 不再调 prefsRepo.saveHighScore —— 最高分不单独持久化了，
            // 它会随下面的 addRecord 通过 Flow 自动派生出来。
            //
            // 一次性庆祝效果（音效、撒花）消费此事件。
            _events.tryEmit(GameEvent.NewRecord(outcome.newHighScore))
        }
        _state.update {
            it.copy(
                // 乐观更新：Flow 回灌前先把新纪录显示出来，否则结算面板会有
                // 一帧显示旧的最高分。回灌后的值与这里相同（都是本局分数），
                // 所以不会闪。
                highScore = outcome.newHighScore,
                isNewRecord = outcome.isNewRecord,
            )
        }

        if (!outcome.shouldRecord) return

        val record = GameRecord(
            score = _state.value.score,
            timestampMillis = System.currentTimeMillis(),
            isNewRecord = outcome.isNewRecord,
        )
        viewModelScope.launch {
            historyRepo.addRecord(record)
            // 本局已结算入库 → 中断快照作废。
            //
            // ⚠️ 不清会留下一个「已经入过库」的残局：玩家下次启动被拉回
            // 那个现场，玩完却因幂等保护不再入库，等于白玩一局。
            snapshotRepo.clear()
        }
        // 已结算 ≠ 挂起：清掉挂起标记，避免 ON_STOP 误以为要保住快照。
        roundSuspendedToMenu = false
    }

    /**
     * Restart from a game-over or any other phase. Equivalent to
     * [startGame] but explicitly named for the restart-button use case.
     * Cancels any in-flight timer first.
     *
     * 先把当前局成绩写入历史再开新局 —— 这是用户报的「中途重开成绩没进
     * 历史」的修法。[recordCurrentRound] 是幂等的，所以 game over 后点重开
     * 不会写两条。
     */
    fun onRestart() {
        recordCurrentRound()
        timerJob?.cancel()
        startGame()
    }

    /**
     * 退出当前对局。写入成绩后把状态置为 [GamePhase.IDLE]。
     *
     * 由 UI 的「退出」按钮调用。真正「结束进程」还是「回主菜单」由 UI
     * 决定 —— VM 只负责把本局收尾干净。
     *
     * @param onRecorded 成绩确实写完后的回调（在主线程）。退出进程这类
     *                   不可逆操作应放在这里，避免写盘被进程终止打断。
     */
    fun onQuit(onRecorded: () -> Unit = {}) {
        val hadUnsettledScore = !currentRoundRecorded && _state.value.score > 0
        recordCurrentRound()
        timerJob?.cancel()
        swapJob?.cancel()
        // 回菜单时把本局现场清干净。
        //
        // ## 为什么不只改 phase
        //
        // ViewModel 活得比 GameScreen 长，成绩入库后 board / score / combo
        // 留在 state 里没有任何用途，但会造成可见的 bug —— 下次从菜单进
        // 游戏时，首帧渲染的是**上一局的现场**：分数从旧值跳到 0、棋盘明显
        // 刷一下（startGame() 下一帧才覆盖）。
        //
        // 清理清单在 RoundTeardown.teardown 里，与挂起路径
        // （onStopWithSnapshot）共用一份 —— 这个 bug 分三次才修完
        // （先补分数、再补棋盘、又补 animFrame），因为清单曾经散在两处
        // 靠人记全。现在它是 engine 的纯函数，有 6 条测试锁住。
        //
        // phase 同时变成 IDLE，结算面板随之消失，所以清零不会让面板上的
        // 成绩闪一下再没 —— 两者在同一帧生效。
        _state.update { RoundTeardown.teardown(it) }

        if (!hadUnsettledScore) {
            // 没有实际写盘动作，直接回调。
            //
            // ⚠️ 但快照必须清：玩家主动退出意味着「这局结束了」，即使 0 分
            // 不入库也一样。不清会让菜单继续显示「继续上局」，点进去回到一个
            // 玩家已经放弃的残局。
            //
            // 得分 > 0 的路径由 recordCurrentRound 入库后一并清，不必重复。
            viewModelScope.launch { snapshotRepo.clear() }
            // 这是真退出，不是挂起。
            roundSuspendedToMenu = false
            onRecorded()
            return
        }

        // 等两个仓库都落盘再回调。
        //
        // ⚠️ 不能只等 historyRepo：最高分走的是 prefsRepo，且用的是
        // appScope（与 viewModelScope 无关）。若 UI 借这个回调退出进程，
        // 只等历史会让最高分的写入被进程终止打断 —— 表现为「退出前破的
        // 纪录没保存」。
        //
        // 两者都靠「DataStore 的 edit 内部串行化」这一性质：后发起的读
        // 完成时，先发起的写必然已落盘。
        viewModelScope.launch {
            historyRepo.getRecordsOnce()
            prefsRepo.awaitPendingWrites()
            onRecorded()
        }
    }

    /**
     * 挂起当前对局并回首页 —— 暂停面板的「退出」。
     *
     * ## 与 [onQuit] 的区别：这一局还没结束
     *
     * | | [onQuit] | 本方法 |
     * |---|---|---|
     * | 语义 | 这局**结束了** | 这局**先放着** |
     * | 成绩入库 | 是 | 否 |
     * | 最高分结算 | 是 | 否 |
     * | 快照 | 清掉 | **保留** |
     * | 能否恢复 | 不能 | 能（菜单「继续上局」）|
     *
     * 玩家从暂停面板退出，意图是「我先干点别的，这局待会儿接着玩」。
     * 若此时结算入库，会产生两个坏后果：
     *
     * 1. 一局被玩了两半，却在历史里留下两条记录（先记 300 分，恢复后
     *    玩完再记 800 分）
     * 2. 更糟：结算会清快照（见 [recordCurrentRound]），玩家回到菜单
     *    发现没有「继续上局」—— 那一局凭空消失
     *
     * 所以这里**只写快照、不做结算**。成绩留到这局真正结束时再算。
     *
     * ## 为什么同步写
     *
     * 与 [onStopWithSnapshot] 同理，但原因不同：这里进程不会死，是为了
     * **顺序保证** —— 回调触发后 UI 立刻切到菜单，菜单要马上查
     * [hasRestorableSnapshot] 来决定是否显示「继续上局」。异步写会让这两
     * 件事赛跑，输了就是「刚退出却没有继续按钮」。
     *
     * @param onSuspended 快照落盘后的回调（在主线程），UI 在此切回菜单。
     */
    fun onSuspendToMenu(onSuspended: () -> Unit = {}) {
        timerJob?.cancel()
        swapJob?.cancel()

        val s = _state.value
        val snapshot = GameSnapshot(
            board = s.board,
            score = s.score,
            combo = s.combo,
            remainingSeconds = s.remainingSeconds,
        )

        // 已结算的局不该再留快照（例如结算弹窗上切后台又回来）。
        // 这里的判据与 onStopWithSnapshot 一致，避免两处行为分叉。
        if (!currentRoundRecorded && snapshot.isRestorable) {
            snapshotRepo.saveBlocking(snapshot)
            // 告诉 onStopWithSnapshot：这个 IDLE 是「挂起」，别清快照。
            roundSuspendedToMenu = true
        } else {
            snapshotRepo.clearBlocking()
            roundSuspendedToMenu = false
        }

        // 置 IDLE 并清掉本局现场。清理清单与 onQuit 共用
        // RoundTeardown.teardown（engine 纯函数，6 条测试锁住）。
        //
        // 离开游戏屏后 phase 不该停在 PAUSED，否则下次进游戏屏会先闪一下
        // 暂停面板。棋盘/分数也要清，否则从菜单点「开始新游戏」时首帧是
        // 上一局的现场。
        //
        // ⚠️ 清空**不影响恢复**：现场已经 saveBlocking 存盘了（就在上面
        // 几行），restoreSnapshot 是读盘重建 GameState，不依赖 state 里的
        // 残留值。
        _state.update { RoundTeardown.teardown(it) }
        onSuspended()
    }

    /**
     * 系统级恢复（Activity 回到前台）。
     *
     * ## 刻意什么都不做
     *
     * 从后台切回来**不自动继续对局**，停在 [GamePhase.PAUSED] 等玩家手动
     * 点「继续」。
     *
     * 理由：玩家切回来的注意力不在棋盘上 —— 可能刚回完消息、刚看完通知。
     * 此时倒计时立刻跑起来，等于凭空吃掉几秒。而这是个 60 秒计时的消除
     * 游戏，几秒是实打实的损失。
     *
     * 与「暂停」的语义也更一致：切后台已经进入暂停态（`ON_PAUSE` →
     * [onPause]），那么解除暂停就该由玩家显式发起，而不是系统替他决定。
     *
     * ## 为什么保留这个方法而不删掉调用
     *
     * 保留 `ON_RESUME` 这个接线点，是为了让「回到前台什么都不做」成为一个
     * **显式的决定**而非遗漏。若哪天要加「回到前台播个恢复提示音」之类的
     * 逻辑，落点在这里。
     *
     * 也保留 [isOnGameScreen] 参数：它记录了一个真实约束 —— 玩家可能在
     * 菜单/历史屏切后台，那些屏幕上恢复对局是无意义的。将来若改回自动
     * 继续，这个判断仍然必须存在。
     *
     * @param isOnGameScreen 当前是否停留在游戏屏
     */
    @Suppress("UNUSED_PARAMETER")
    fun onSystemResume(isOnGameScreen: Boolean) {
        // 有意为之的空实现 —— 见上方文档。
        // 恢复动作由玩家在暂停面板上手动触发（PauseDialog 的「继续」）。
    }

    /**
     * Activity is going to the background (or the user hit "pause"). Flip
     * the phase to [GamePhase.PAUSED] and cancel the timer. The next
     * [onResume] will pick up where we left off — board state, score,
     * combo, and remaining time are all preserved.
     *
     * Safe to call from any phase — but it only *acts* on
     * [GamePhase.PLAYING]. Any other phase is a no-op (see [PauseRules]):
     * a finished round must stay on the game-over dialog, and a round
     * suspended back to the menu must keep its `IDLE` phase.
     * (If the timer wasn't running, the cancel is a no-op.)
     *
     * ## 为什么不 cancel swapJob
     *
     * 曾经这里有一句 `swapJob?.cancel()`，导致过两个 bug：
     *
     * 1. **第一代**：cancel 让协程从 delay 抛 CancellationException，
     *    终态来不及落盘 → 恢复后棋盘错乱（残帧 + 旧 board，tile 不下落、
     *    手势失效）。
     * 2. **第二代**：在取消分支里补写终态修好了错乱，但动画被整段跳过 ——
     *    点暂停看到的是棋盘瞬间结算完毕，失去了「暂停」的意义。
     *
     * 现在改为**挂起而非取消**：动画协程通过 `awaitResume` 挂在
     * `phase != PAUSED` 上（见 [onSwapAttempt]）。协程始终存活，暂停期间
     * 不推进帧、不写 state，恢复后从同一位置继续播放。
     *
     * 于是 phase 一个字段同时驱动三件事：倒计时停、动画挂起、手势拦截。
     */
    fun onPause() {
        // ⚠️ 有条件暂停。曾经这里是无条件 `_state.update { copy(PAUSED) }`，
        // 而调用来源（ON_PAUSE / ON_STOP）与「对局是否在进行」完全无关：
        //
        //   1. 玩家在**结算面板**上切后台再回来 → 看到暂停面板。那一局已经
        //      结束了，"暂停"没有意义；又因为回到前台不自动继续（见
        //      onSystemResume），他会卡在一个点「继续」才能离开的面板上。
        //   2. 玩家停在**菜单**时切后台 → IDLE 被改成 PAUSED，而
        //      onStopWithSnapshot 正靠 phase 区分「挂起回菜单」该不该保住
        //      快照，状态被抹掉后判断就失准。
        //
        // 准入规则抽到 engine 的 PauseRules（纯函数 + 逐状态测试覆盖）。
        if (!PauseRules.shouldPause(_state.value.phase)) return

        _state.update { it.copy(phase = GamePhase.PAUSED) }
        timerJob?.cancel()
    }

    // ========================================================================
    // 对局快照（断点续玩）
    // ========================================================================

    /**
     * 暂停并**同步**保存对局快照。
     *
     * 由 `MainActivity` 在 `ON_STOP` 调用 —— 那是「进程可能马上消失」前
     * 最后一个保证会执行的回调（`onDestroy` / `onCleared` 在从任务列表
     * 划掉应用时都不保证被调用）。
     *
     * ## 为什么同步
     *
     * `ON_STOP` 返回之后系统随时可杀进程，异步落盘会被打断。此刻界面
     * 已退到后台，阻塞几十毫秒无人感知。取舍同 `PrefsRepository.warmUp`。
     *
     * ## 什么情况下不存
     *
     * 只有**进行中的对局**才值得存。已结算（currentRoundRecorded）的局
     * 若也存快照，玩家下次进来会被拉回一个**已经入过库**的残局 ——
     * 继续玩完还会因幂等保护而不再入库，等于白玩一局。
     */
    fun onStopWithSnapshot() {
        onPause()

        val s = _state.value
        val snapshot = GameSnapshot(
            board = s.board,
            score = s.score,
            combo = s.combo,
            remainingSeconds = s.remainingSeconds,
        )

        // 只存真正进行中且未结算的对局。
        //
        // isRestorable 挡掉空棋盘和已耗尽的计时器；currentRoundRecorded
        // 挡掉「已经算过成绩」的局（game over 后停在结算弹窗、或已退出）。
        //
        // ⚠️ 这两个 phase 条件曾是**死代码**：上面的 onPause() 当时无条件
        // 把 phase 改成 PAUSED，等到这里读 _state.value 时必然是 PAUSED，
        // 于是 `!= IDLE && != GAME_OVER` 恒为真，它们想挡的状态在读之前
        // 就被覆盖了。真正在把关的只有 currentRoundRecorded 和 isRestorable。
        //
        // onPause() 改为有条件之后（见 PauseRules），phase 能如实传到这里，
        // 这两个条件才真正生效。
        val worthSaving = !currentRoundRecorded &&
            s.phase != GamePhase.IDLE &&
            s.phase != GamePhase.GAME_OVER &&
            snapshot.isRestorable

        if (worthSaving) {
            snapshotRepo.saveBlocking(snapshot)
        } else if (!roundSuspendedToMenu) {
            // 主动清掉旧快照：否则「上次中断留下的快照」会在这次
            // 已结算/未开局的情况下残留，下次启动恢复出过期现场。
            //
            // ⚠️ 但「挂起回菜单」是例外：那条路径刚刚**故意**存了快照，
            // phase 也被置成 IDLE。若不排除，玩家点「保留并返回首页」后
            // 在菜单划掉应用，这里会把刚保留的对局清掉 —— 正是要保住的
            // 那一局没了。
            snapshotRepo.clearBlocking()
        }
    }

    /**
     * 尝试恢复上次中断的对局。
     *
     * @return `true` 表示已恢复（调用方应直接进游戏屏），`false` 表示
     *         没有可恢复的快照。
     *
     * ## 恢复后是暂停态
     *
     * 玩家切回来需要重新建立注意力，直接跑计时器不公平（用户决定）。
     * 复用现有的 [GamePhase.PAUSED] UI，零额外工作。
     *
     * ## 恢复即消费
     *
     * 读到就删。快照的语义是「有一局被中断了」，恢复之后这个事实就不
     * 成立了。不删会导致玩家正常玩完这局、下次进游戏又被拉回残局。
     */
    suspend fun restoreSnapshot(): Boolean {
        val snapshot = snapshotRepo.load() ?: return false
        if (!snapshot.isRestorable) {
            // 格式合法但内容无意义（空棋盘 / 时间耗尽）—— 清掉，当作没有。
            snapshotRepo.clear()
            return false
        }

        // ⚠️ 必须先同步 tile id 计数器再把棋盘交给 UI。
        //
        // 计数器活在内存里，进程重启即归零。恢复的棋盘带着旧 id，若不
        // 播种，后续 spawnRefill 会从 1 重新发号并与盘上 tile 撞号 ——
        // Compose 用 id 当 key，撞号表现为动画在两个 tile 之间乱窜。
        TileIdGenerator.seedAtLeast(snapshot.maxTileId)

        timerJob?.cancel()
        swapJob?.cancel()
        roundGeneration++
        // 恢复的是一局**尚未结算**的对局 —— 它当初就是被中断的。
        currentRoundRecorded = false
        // 快照已被消费，「挂起」状态随之结束。
        roundSuspendedToMenu = false

        _state.update { current ->
            GameState(
                board = snapshot.board,
                score = snapshot.score,
                combo = snapshot.combo,
                remainingSeconds = snapshot.remainingSeconds,
                phase = GamePhase.PAUSED,
                isMuted = prefsRepo.isMuted(),
                // 同 startGame：这里是重建，最高分必须显式带过来。
                highScore = current.highScore,
            )
        }

        // 恢复即消费。
        snapshotRepo.clear()
        return true
    }

    /** 是否存在可恢复的对局快照。供启动时决定要不要显示「继续游戏」。 */
    suspend fun hasRestorableSnapshot(): Boolean =
        snapshotRepo.load()?.isRestorable == true

    /**
     * Activity is back in the foreground. If we were paused, flip back to
     * [GamePhase.PLAYING] and restart the timer. No-op if the phase is
     * already `PLAYING`, `IDLE`, or `GAME_OVER` (so the caller doesn't
     * have to gate the call).
     */
    fun onResume() {
        if (_state.value.phase == GamePhase.PAUSED) {
            _state.update { it.copy(phase = GamePhase.PLAYING) }
            // 显式 cancel：防止 onPause cancel 后还有 timer 残留（竞态保护）
            timerJob?.cancel()
            startTimer()
        }
    }

    // ========================================================================
    // Timer
    // ========================================================================

    /**
     * Launch the 1 Hz countdown coroutine. Each iteration:
     * 1. `delay(1000)` — sleep one second.
     * 2. Compute the new `remainingSeconds` via [TimerEngine.tick].
     * 3. If the new value is 0, set `phase = GAME_OVER` and call
     *    [onGameOver] (which persists the high score if beaten).
     * 4. Otherwise, update `remainingSeconds`.
     *
     * The loop exits when `phase` is no longer `PLAYING` (e.g. on pause or
     * game-over) OR when the timer hits 0.
     *
     * Cancels any previous timer before starting — there is only ever one
     * timer at a time.
     */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_state.value.phase == GamePhase.PLAYING) {
                delay(1000)
                _state.update { current ->
                    val newRemaining = TimerEngine.tick(current.remainingSeconds)
                    if (newRemaining == 0) {
                        current.copy(
                            remainingSeconds = 0,
                            phase = GamePhase.GAME_OVER,
                        )
                    } else {
                        current.copy(remainingSeconds = newRemaining)
                    }
                }

                // ✅ 倒计时 ≤10 秒时播放 tick 音效（FR-7.4）
                // 只在 PLAYING 阶段 + remainingSeconds ∈ 1..10 触发；
                // 0 秒不播（避免和 game over 冲突）；SoundPlayer 内部已处理静音。
                val currentRemaining = _state.value.remainingSeconds
                if (currentRemaining in 1..10 && _state.value.phase == GamePhase.PLAYING) {
                    soundPlayer.playTick()
                }

                if (_state.value.phase == GamePhase.GAME_OVER) {
                    onGameOver()
                    break
                }
            }
        }
    }

    /**
     * External tick entry point. Reserved for cases where the timer is
     * driven by something other than [startTimer]'s coroutine (e.g. a
     * unit test or a manual debug hook). The current implementation
     * drives the timer entirely from the coroutine, so this is a stub.
     */
    fun onTimeTick() {
        // 由外部（如果不用协程）触发；当前实现里没用，留接口
    }

    // ========================================================================
    // Animation constants (per T-ANIM-001: 3 phases × 100ms, gaps 100ms)
    // ========================================================================
    private companion object {
        const val ANIM_PHASE_MS = 100L
        const val ANIM_GAP_MS = 100L
    }

    // ========================================================================
    // Tile interaction
    // ========================================================================

    /**
     * User tapped a tile. Implements click-to-swap (FR-2.1):
     *
     * 1. **No selection** → select this tile.
     * 2. **Same tile already selected** → deselect.
     * 3. **Different tile, adjacent** ([BoardEngine.isAdjacent]) → call
     *    [onSwapAttempt].
     * 4. **Different tile, not adjacent** → move the selection to the new
     *    tile (the old selection is discarded).
     *
     * Rejected if a swap is already in flight ([swapProcessing]) or the
     * game is not in [GamePhase.PLAYING].
     *
     * @param row  tapped tile's row, 0..6
     * @param col  tapped tile's col, 0..6
     */
    fun onTileTapped(row: Int, col: Int) {
        if (swapProcessing || _state.value.phase != GamePhase.PLAYING) return
        val current = _state.value
        val tile = current.board.grid[row][col] ?: return
        val selected = current.selectedTile

        if (selected == null) {
            // 选中
            _state.update { it.copy(selectedTile = row to col) }
        } else if (selected == row to col) {
            // 取消选中
            _state.update { it.copy(selectedTile = null) }
        } else {
            // 尝试交换
            val fromTile = current.board.grid[selected.first][selected.second] ?: return
            if (BoardEngine.isAdjacent(fromTile, tile)) {
                onSwapAttempt(selected.first, selected.second, row, col)
            } else {
                // 选中另一个
                _state.update { it.copy(selectedTile = row to col) }
            }
        }
    }

    /**
     * User finished a drag gesture. Implements drag-to-swap (FR-2.2):
     *
     * - **Same cell** → no-op.
     * - **Non-adjacent cell** (Manhattan distance ≠ 1) → no-op (the drag
     *   was too long; ignore it).
     * - **Adjacent cell** → call [onSwapAttempt] with the four coordinates.
     *
     * The check `|fromRow-toRow| + |fromCol-toCol| == 1` rejects both
     * "dragged to same cell" (distance 0) and "dragged across the board"
     * (distance > 1) in one expression.
     *
     * Rejected if a swap is already in flight or the game is not in
     * [GamePhase.PLAYING].
     *
     * @param fromRow  drag origin row, 0..6
     * @param fromCol  drag origin col, 0..6
     * @param toRow    drag target row, 0..6
     * @param toCol    drag target col, 0..6
     */
    fun onDragEnd(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        if (swapProcessing || _state.value.phase != GamePhase.PLAYING) return
        if (fromRow == toRow && fromCol == toCol) return
        if (kotlin.math.abs(fromRow - toRow) + kotlin.math.abs(fromCol - toCol) != 1) {
            // 拖到了不相邻位置，忽略
            return
        }
        onSwapAttempt(fromRow, fromCol, toRow, toCol)
    }

    /**
     * Attempt a swap of two adjacent tiles. Runs in [viewModelScope] so we
     * can `delay(150)` for the rollback animation without blocking the
     * main thread.
     *
     * The full pipeline (swap → match → score → gravity → cascade → reward)
     * is documented at the class level. Re-entrancy is guarded by
     * [swapProcessing] which is set `true` on entry and `false` on every
     * exit path (success / invalid-swap / no-match-rollback).
     *
     * @param fromRow  source row, 0..6
     * @param fromCol  source col, 0..6
     * @param toRow    target row, 0..6
     * @param toCol    target col, 0..6
     */
    private fun onSwapAttempt(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) {
        swapJob?.cancel()
        // 捕获启动时的局代际。若期间发生 restart，落盘前的比对会拦住写入。
        val myGeneration = roundGeneration
        swapJob = viewModelScope.launch {
            // 「把本次 swap 终态落盘」的动作。在 try 内算出 cascade 结果后被赋值，
            // 好让 catch (CancellationException) 分支也能调用它 —— 局部函数的
            // 作用域限于 try 块内，catch 里看不见，故用 try 外的可空变量持有。
            var commitFinalState: (() -> Unit)? = null
            try {
                swapProcessing = true
                val current = _state.value
                val fromTile = current.board.grid[fromRow][fromCol]!!
                val toTile = current.board.grid[toRow][toCol]!!

                // 1. 尝试交换
                val (swappedBoard, swapResult) = BoardEngine.attemptSwap(
                    current.board, fromTile, toTile
                )

                if (swapResult != SwapResult.Success) {
                    // 不相邻或棋盘错误
                    return@launch
                }

                soundPlayer.playSwap()

                // 2. 检测三连
                val matches = MatchEngine.detectMatches(swappedBoard)

                if (matches.isEmpty()) {
                    // 无效交换：弹回
                    _state.update {
                        it.copy(
                            board = swappedBoard,  // 临时显示交换后的状态
                            selectedTile = null,
                            isRollback = true,
                        )
                    }
                    _events.tryEmit(GameEvent.SwapRejected)
                    // 短暂延迟后回弹（让 UI 显示弹回动画）
                    delay(150)
                    _state.update {
                        it.copy(
                            board = current.board,  // 回弹
                            isRollback = false,
                        )
                    }
                    return@launch
                }

                // 3. 有消除 - 计算分数
                val newBoard = swappedBoard
                var totalScore = 0
                var comboCount = 1

                // 4. 连锁
                val cascadeResult = CascadeEngine.cascadeUntilStable(newBoard, matches)
                for (cascade in cascadeResult.cascades) {
                    for (match in cascade) {
                        totalScore += ScoreEngine.scoreForMatch(match, comboCount)
                    }
                    comboCount++
                }

                // 5. 消除后倒计时重置回 60s（与 match 数量无关）
                val newRemaining = TimerEngine.resetOnMatch(
                    _state.value.remainingSeconds, cascadeResult.cascades.flatten()
                )

                // 6. 音效
                if (cascadeResult.cascades.size > 1) soundPlayer.playCombo()
                else soundPlayer.playMatch()

                // 7. 播放 3-phase 动画（每个 cascade round 各 3 帧）
                //    时序编排已抽到 engine/CascadeAnimator.kt。
                //    这里只负责把帧写进 GameState，并在 phase 变化时中止。
                //
                // 把本次 swap 的最终结果落盘。
                //
                // ⚠️ 必须在**正常播完**和**动画被取消**两条路径都调用。
                // 见下方 CancellationException 分支的注释。
                //
                // 代际守卫：期间若发生 restart，本局终态就是过期数据，
                // 写进去会盖掉新局的棋盘。
                commitFinalState = {
                    if (myGeneration == roundGeneration) {
                        // 棋盘落定后检测死局。必须放在这里而不是动画开始前 ——
                        // 连锁结束、重力和补充都跑完，此刻的 finalBoard 才是
                        // 玩家将要面对的局面。
                        val reshuffle =
                            DeadlockEngine.reshuffleIfDeadlocked(cascadeResult.finalBoard)

                        _state.update {
                            it.copy(
                                // 先落这一局连锁的结果（未重排的棋盘）。
                                // 重排动画由下面的 playReshuffleAnimation 接手 ——
                                // 直接写重排后的棋盘会让玩家看到瞬间跳变，
                                // 那正是「跟重开似的」的来源。
                                board = cascadeResult.finalBoard,
                                animFrame = null,
                                score = it.score + totalScore,
                                combo = cascadeResult.cascades.size,
                                remainingSeconds = newRemaining,
                                selectedTile = null,
                            )
                        }

                        if (reshuffle.didReshuffle) {
                            // 不 await —— commitFinalState 可能在动画被取消的
                            // 路径上调用，那里不能挂起。用独立协程播重排动画。
                            viewModelScope.launch {
                                playReshuffleWithNotice(
                                    fromBoard = cascadeResult.finalBoard,
                                    reshuffle = reshuffle,
                                    generation = myGeneration,
                                )
                            }
                        }
                    }
                }

                playCascadeAnimation(
                    startBoard = newBoard,
                    cascades = cascadeResult.cascades,
                    phaseMs = ANIM_PHASE_MS,
                    gapMs = ANIM_GAP_MS,
                    // 每轮的补充结果由 CascadeEngine 单点算出，动画层直接复用。
                    // 不传会导致动画层自己再摇一次随机，飞进来的寿司与最终
                    // 落定的不是同一批（每次消除都可见）。
                    rounds = cascadeResult.rounds,
                    // 守卫只负责**永久性**中止：本局已被换掉（代际不符）或
                    // 已经结束/回到 IDLE。
                    //
                    // ⚠️ 这里刻意**不检查 PAUSED**：暂停是临时状态，应该由
                    // awaitResume 挂起等待，而不是 break 掉剩余轮次。若在此
                    // 处 break，多轮 cascade 在暂停后就只剩终态一次性落盘，
                    // 恢复时看不到后续几轮动画。
                    shouldContinue = {
                        myGeneration == roundGeneration &&
                            _state.value.phase != GamePhase.GAME_OVER &&
                            _state.value.phase != GamePhase.IDLE
                    },
                    // 真暂停：挂在 phase != PAUSED 上。
                    // 用 StateFlow.first 而非轮询 —— 零 CPU 占用，
                    // phase 一变回 PLAYING 立即恢复。
                    awaitResume = {
                        if (_state.value.phase == GamePhase.PAUSED) {
                            _state.first { it.phase != GamePhase.PAUSED }
                        }
                    },
                    onFrame = { board, frame ->
                        // 迟到的帧不得写进新局。
                        if (myGeneration != roundGeneration) return@playCascadeAnimation
                        _state.update {
                            if (board != null) it.copy(board = board, animFrame = frame)
                            else it.copy(animFrame = frame)
                        }
                    },
                )

                // 动画正常结束
                commitFinalState.invoke()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 协程被取消（onRestart / VM 清理 / 新 swap 顶掉旧的）是正常
                // 控制流，必须原样抛出以维持结构化并发。
                //
                // ⚠️ 抛之前先把终态落盘。历史原因：
                //
                // 曾经 onPause() 也会 cancel 这个 job，而 playCascadeAnimation
                // 在帧之间 delay()，取消让 delay 抛异常 → 下面那句
                // commitFinalState 不执行 → board 停在旧值、animFrame 卡在
                // 中途某一帧。恢复后 UI 渲染残帧（tile 不下落不补齐），
                // 手势判定又走旧 board（操作无效）。
                //
                // 现在暂停改为**挂起**（见 onPause 与 awaitResume），不再走
                // 这条路径。但 onRestart / onCleared / 新 swap 顶掉旧 swap
                // 仍会取消，落盘依然是必要的兜底 —— 代际守卫会确保过期的
                // 终态不会污染新局。
                //
                // 终态在任何 delay 之前就纯计算好了，取消不影响其正确性。
                // 回归测试见 engine/CascadeCancellationTest.kt。
                //
                // 为什么不放在 finally：finally 对 onRestart 也会执行，
                // 那时新局已经开始 —— 虽然代际守卫拦得住，但语义上
                // 「取消时兜底」比「无论如何都写」更清晰。
                //
                // 用 ?.invoke()：取消可能发生在 cascade 算完之前（例如无效
                // 交换的那 150ms 弹回 delay 期间），那时它还是 null，
                // 没有终态可落 —— 也确实不需要落。
                commitFinalState?.invoke()
                throw ce
            } catch (e: Exception) {
                // 异常安全：防止未捕获异常导致 swapProcessing 永远为 true，
                // 进而让棋盘永久不接受输入（游戏冻结）。
                android.util.Log.e("GameViewModel", "onSwapAttempt failed", e)
                // 回到一个可继续游玩的干净状态：清掉动画帧与选中态。
                _state.update { it.copy(animFrame = null, selectedTile = null, isRollback = false) }
            } finally {
                swapProcessing = false
            }
        }
    }

    // ========================================================================
    // Sound
    // ========================================================================

    /**
     * Flip the muted flag. Persists to [PrefsRepository] and pushes the
     * new value to [SoundPlayer] (which gates `play*` calls on its own
     * `_mutedFlow`). The UI sees the new value via [state].
     *
     * Safe to call from any phase (the sound settings are not part of
     * the round state).
     */
    fun toggleMute() {
        // 只写唯一数据源。SoundPlayer 通过 mutedProvider 实时读取，
        // GameState.isMuted 由 init 中的 mutedFlow collect 自动投影 ——
        // 此处不再需要三处手工同步（旧实现漏一处即静默不一致）。
        prefsRepo.setMuted(!prefsRepo.isMuted())
    }

    // ========================================================================
    // 调试专用
    // ========================================================================

    /**
     * 把当前棋盘强制变成死局，用于验证自动重排。
     *
     * 自然玩法下死局概率极低，靠运气可能玩很久都碰不到，没法验证重排的真机
     * 表现。这个入口提供确定性触发。
     *
     * ## 为什么守卫放在调用方
     *
     * `BuildConfig.DEBUG` 的判断在 `GameScreen` 的手势那一侧，不在这里。
     * 这样 debug 与 release 的差异集中在一处，读代码时一眼能看到边界。
     *
     * ⚠️ 注意本项目 `isMinifyEnabled = false`，R8 不运行 —— 这个方法在
     * release APK 里**依然存在**（已用 dexdump 核实），只是永远不被调用。
     * 别当成「正式包里没有这段代码」。
     *
     * ## 为什么只在 PLAYING 生效
     *
     * 暂停或结算态改棋盘会让状态自相矛盾：结算面板显示的分数对应的是旧棋盘，
     * 而快照存的是新的。
     *
     * ## 为什么造完死局要立刻走重排
     *
     * 第一版只把棋盘改成死局就结束，那是个逻辑死结：重排只在消除落定后
     * （`commitFinalState`）触发，而死局盘上消不掉任何东西 —— 玩家点到
     * 倒计时归零也等不到重排，看到的只是「游戏卡住了」。
     *
     * 所以这里直接把「造死局 → 检测 → 重排 → 发通知」跑完，一次长按看到
     * 完整流程。想单独观察死局本身，可以先看 Toast 弹出前的那一帧。
     */
    fun debugForceDeadlock() {
        if (_state.value.phase != GamePhase.PLAYING) return

        val deadlocked = DeadlockEngine.forceDeadlock(_state.value.board)
        val reshuffle = DeadlockEngine.reshuffleIfDeadlocked(deadlocked)

        // 先把死局盘落进 state，让玩家看到「无解的局面」这一帧 ——
        // 重排动画的起点就是它。直接跳到重排后的棋盘，玩家不知道刚才发生了什么。
        _state.update { it.copy(board = deadlocked, selectedTile = null) }

        if (reshuffle.didReshuffle) {
            viewModelScope.launch {
                playReshuffleWithNotice(deadlocked, reshuffle, roundGeneration)
            }
        }
    }

    /**
     * 播放重排动画并发出提示。
     *
     * ## 提示与动画的先后
     *
     * 提示先发、动画后播。玩家需要先知道「怎么了」（局面无解），才能理解
     * 接下来看到的移动是什么意思。反过来先播动画，玩家会先困惑一下
     * 「棋盘怎么突然动了」，提示到得太晚。
     *
     * @param generation 起始代际。restart 会让它失效，动画随之中止。
     */
    private suspend fun playReshuffleWithNotice(
        fromBoard: Board,
        reshuffle: DeadlockEngine.ReshuffleResult,
        generation: Long,
    ) {
        _events.tryEmit(GameEvent.BoardReshuffled)

        playReshuffleAnimation(
            fromBoard = fromBoard,
            toBoard = reshuffle.board,
            origin = reshuffle.origin,
            shouldContinue = { generation == roundGeneration },
            onFrame = { board, frame ->
                if (generation != roundGeneration) return@playReshuffleAnimation
                _state.update {
                    if (board != null) it.copy(board = board, animFrame = frame)
                    else it.copy(animFrame = frame)
                }
            },
        )

        // 动画结束，清掉帧回到静态渲染。
        //
        // 代际再查一次：动画期间可能发生 restart，那时 animFrame 已属于新局，
        // 清掉会让新局的进行中动画凭空消失。
        if (generation == roundGeneration) {
            _state.update { it.copy(animFrame = null) }
        }
    }

    // ========================================================================
    // 设置页面：清空数据
    // ========================================================================

    /**
     * 清空全部记录：历史、快照、最高分。
     *
     * ## 为什么三者是一个动作
     *
     * 玩家的意图是「把我的记录都抹掉」，而这三份数据都是那个意图的一部分。
     * 拆成多个开关只会让人分别点三次，且很容易漏掉一项后以为没清干净。
     *
     * ## 为什么连快照一起清
     *
     * 快照是「有一局被中断了」的凭证，而那一局的成绩尚未入库。若留下快照，
     * 玩家恢复那一局玩完后，历史里会**凭空多出一条**刚被清空的记录 ——
     * 看起来像清空失败了。
     *
     * 顺带也避免了更隐蔽的一种：那个残局若在清空前已被结算过
     * （currentRoundRecorded），恢复它还会因幂等保护而不再入库。
     *
     * ## 最高分为什么不用单独清
     *
     * 它是历史记录的派生值（见 [HighScoreDerivation]），历史清空后
     * `max(emptyList()) = 0` 会通过 Flow 自动投影过来。
     *
     * 这是派生方案的直接红利：曾经这里要额外调一次 `resetHighScore()`，
     * 而那个方法还得绕开 saveHighScore 的「只升不降」守卫 —— 一个为了
     * 维护双份真相而存在的别扭写法。现在两者都不需要了。
     *
     * ## 为什么还要清 isNewRecord
     *
     * 那个标记的含义是「本局分数 > 当时的最高分」。最高分归零后它引用的是
     * 一个已经不存在的基准，留着会让结算面板继续显示「🏆 新纪录」，而玩家
     * 刚刚亲手把纪录清空了 —— 界面在自相矛盾。
     *
     * 也不重算（比如改成「score > 0 就算新纪录」）：清空动作不该顺手给玩家
     * 颁发一个他没挣到的纪录。
     *
     * @param onDone 全部落盘后的回调（主线程）。
     */
    fun clearHistory(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            historyRepo.clear()
            snapshotRepo.clear()
            // 最高分随历史清空自动归零（派生），这里只需处理它带不动的
            // 派生标记。
            _state.update { it.copy(isNewRecord = false) }
            onDone()
        }
    }

    // ========================================================================
    // Game over
    // ========================================================================

    /**
     * Called from [startTimer] when the countdown hits 0. Persists the
     * high score if beaten, then updates [state] with the final
     * `highScore` and `isNewRecord` flag. The UI's GameOverDialog reads
     * `score` / `highScore` / `isNewRecord` to render.
     *
     * Note: the `phase` is already `GAME_OVER` by the time we get here
     * (the timer set it in its `_state.update` block before calling us),
     * so this method only needs to handle the persistence + readout
     * fields. We re-affirm `phase = GAME_OVER` in the `update` for safety
     * (idempotent, no harm).
     */
    private fun onGameOver() {
        // 最高分结算与历史入库都在 recordCurrentRound 里（它是幂等的），
        // 三条退出路径共用同一套结算逻辑 —— 这里不再单独处理最高分。
        //
        // ⚠️ phase 必须先落定：GameOverDialog 依赖它显示，而 recordCurrentRound
        // 内部会 _state.update 写 highScore / isNewRecord，两次 update 无先后
        // 依赖，但 phase 先写能保证任何时刻观察到的 state 都是自洽的。
        _state.update { it.copy(phase = GamePhase.GAME_OVER) }
        recordCurrentRound()
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Called by the framework when the ViewModel will not be used again
     * (Activity finished, or the back-stack entry was popped). Cancels
     * the timer coroutine. `viewModelScope` is also cancelled at this
     * point, so this is belt-and-suspenders.
     */
    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        swapJob?.cancel()
    }
}
