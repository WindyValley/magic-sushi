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
import top.windyvalley.magicsushi.engine.BoardEngine
import top.windyvalley.magicsushi.engine.AnimationEngine
import top.windyvalley.magicsushi.engine.CascadeEngine
import top.windyvalley.magicsushi.engine.GameEvent
import top.windyvalley.magicsushi.engine.GameRecord
import top.windyvalley.magicsushi.engine.GravityEngine
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.engine.GameState
import top.windyvalley.magicsushi.engine.MatchEngine
import top.windyvalley.magicsushi.engine.ScoreEngine
import top.windyvalley.magicsushi.engine.SwapResult
import top.windyvalley.magicsushi.engine.TimerEngine
import top.windyvalley.magicsushi.engine.playCascadeAnimation

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
            highScore = prefsRepo.getHighScore(),
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
     */
    private var currentRoundRecorded = false

    init {
        // FIX_PLAN D5：把音效的静音判断绑定到 PrefsRepository —— 静音状态的
        // 唯一数据源。SoundPlayer 自己不再存一份，避免 toggleMute 时漏同步。
        soundPlayer.bindMutedProvider(prefsRepo::isMuted)

        // FIX_PLAN D5：GameState.isMuted 是 prefs 的**派生投影**（供 UI 渲染
        // 图标用），靠 collect 保持同步，而不是在 toggleMute 里手工赋值。
        // 这样即使将来别处调用 prefsRepo.setMuted()，UI 也会自动跟上。
        viewModelScope.launch {
            prefsRepo.mutedFlow.collect { muted ->
                _state.update { it.copy(isMuted = muted) }
            }
        }
        // 同理，最高分也从 prefs 流投影过来。
        viewModelScope.launch {
            prefsRepo.highScoreFlow.collect { high ->
                _state.update { it.copy(highScore = high) }
            }
        }

        // Auto-start the game on construction. If you want a manual "Start"
        // button, change this to no-op and gate the timer on a user action.
        startGame()
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
        _state.update {
            GameState(
                board = BoardEngine.generateInitialBoard(),
                remainingSeconds = TimerEngine.INITIAL_SECONDS,
                phase = GamePhase.PLAYING,
                isMuted = prefsRepo.isMuted(),
                highScore = prefsRepo.getHighScore(),
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
        if (currentRoundRecorded) return
        val score = _state.value.score
        if (score <= 0) {
            // 0 分也算「已处理」，免得后续路径反复检查。
            currentRoundRecorded = true
            return
        }
        currentRoundRecorded = true
        val record = GameRecord(
            score = score,
            timestampMillis = System.currentTimeMillis(),
            isNewRecord = _state.value.isNewRecord,
        )
        viewModelScope.launch {
            historyRepo.addRecord(record)
        }
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
        val alreadyRecorded = currentRoundRecorded
        val score = _state.value.score
        recordCurrentRound()
        timerJob?.cancel()
        swapJob?.cancel()
        _state.update { it.copy(phase = GamePhase.IDLE) }

        if (alreadyRecorded || score <= 0) {
            // 没有实际写盘动作，直接回调。
            onRecorded()
        } else {
            // 等写盘完成 —— 与 recordCurrentRound 里那个 launch 是两个协程，
            // 但 DataStore 的 edit 有内部串行化，所以这个 launch 排在后面
            // 执行完时，前一个写入必然已落盘。
            viewModelScope.launch {
                historyRepo.getRecordsOnce()
                onRecorded()
            }
        }
    }

    /**
     * Activity is going to the background (or the user hit "pause"). Flip
     * the phase to [GamePhase.PAUSED] and cancel the timer. The next
     * [onResume] will pick up where we left off — board state, score,
     * combo, and remaining time are all preserved.
     *
     * Safe to call from any phase — it just sets the flag and cancels the
     * job. (If the timer wasn't running, the cancel is a no-op.)
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
        _state.update { it.copy(phase = GamePhase.PAUSED) }
        timerJob?.cancel()
    }

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
                //    时序编排已抽到 engine/CascadeAnimator.kt（FIX_PLAN P1-1）。
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
                        _state.update {
                            it.copy(
                                board = cascadeResult.finalBoard,
                                animFrame = null,
                                score = it.score + totalScore,
                                combo = cascadeResult.cascades.size,
                                remainingSeconds = newRemaining,
                                selectedTile = null,
                            )
                        }
                    }
                }

                playCascadeAnimation(
                    startBoard = newBoard,
                    cascades = cascadeResult.cascades,
                    phaseMs = ANIM_PHASE_MS,
                    gapMs = ANIM_GAP_MS,
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
        // FIX_PLAN D5：只写唯一数据源。SoundPlayer 通过 mutedProvider 实时读取，
        // GameState.isMuted 由 init 中的 mutedFlow collect 自动投影 ——
        // 此处不再需要三处手工同步（旧实现漏一处即静默不一致）。
        prefsRepo.setMuted(!prefsRepo.isMuted())
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
        val finalScore = _state.value.score
        val oldHigh = prefsRepo.getHighScore()
        val isNew = finalScore > oldHigh
        if (isNew) prefsRepo.saveHighScore(finalScore)

        _state.update {
            it.copy(
                phase = GamePhase.GAME_OVER,
                highScore = if (isNew) finalScore else oldHigh,
                isNewRecord = isNew,
            )
        }

        // isNewRecord 保留在 state 里供 GameOverDialog 渲染（它是对话框存续
        // 期间的持续状态）；这里额外发一次事件，供一次性庆祝效果（音效、
        // 撒花动画）消费。
        if (isNew) {
            _events.tryEmit(GameEvent.NewRecord(finalScore))
        }

        // 写入历史记录。
        //
        // ⚠️ 必须放在上面那个 _state.update 之后：recordCurrentRound 会读
        // state.isNewRecord 填进记录，放前面读到的是上一局的值。
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
