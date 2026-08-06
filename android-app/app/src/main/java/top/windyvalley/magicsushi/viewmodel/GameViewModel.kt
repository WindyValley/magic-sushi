package top.windyvalley.magicsushi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.PrefsRepository
import top.windyvalley.magicsushi.engine.BoardEngine
import top.windyvalley.magicsushi.engine.AnimationEngine
import top.windyvalley.magicsushi.engine.CascadeEngine
import top.windyvalley.magicsushi.engine.GameEvent
import top.windyvalley.magicsushi.engine.GravityEngine
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.engine.GameState
import top.windyvalley.magicsushi.engine.MatchEngine
import top.windyvalley.magicsushi.engine.ScoreEngine
import top.windyvalley.magicsushi.engine.SwapResult
import top.windyvalley.magicsushi.engine.TimerEngine

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
     * Restart from a game-over or any other phase. Equivalent to
     * [startGame] but explicitly named for the restart-button use case.
     * Cancels any in-flight timer first.
     */
    fun onRestart() {
        timerJob?.cancel()
        startGame()
    }

    /**
     * Activity is going to the background (or the user hit "pause"). Flip
     * the phase to [GamePhase.PAUSED] and cancel the timer. The next
     * [onResume] will pick up where we left off — board state, score,
     * combo, and remaining time are all preserved.
     *
     * Safe to call from any phase — it just sets the flag and cancels the
     * job. (If the timer wasn't running, the cancel is a no-op.)
     */
    fun onPause() {
        _state.update { it.copy(phase = GamePhase.PAUSED) }
        timerJob?.cancel()
        swapJob?.cancel()
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
        swapJob = viewModelScope.launch {
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

                // 5. 奖励时间
                val (newRemaining, reward) = TimerEngine.rewardOnMatch(
                    _state.value.remainingSeconds, cascadeResult.cascades.flatten()
                )

                // 6. 音效
                if (cascadeResult.cascades.size > 1) soundPlayer.playCombo()
                else soundPlayer.playMatch()

                // 7. 播放 3-phase 动画（每个 cascade round 各 3 帧）
                //    每帧间隔 100 ms，round 之间间隔 100 ms。
                //
                //    ⚠️ 关键：每个 cascade round 是在不同的 board 状态上检测到 matches 的。
                //    Round 0 matches 在 swappedBoard 上检测 → gravity 后 → board1
                //    Round 1 matches 在 board1 上检测 → gravity 后 → board2
                //    ...
                //    如果每次都传 swappedBoard 给 generateFrames，Round 1 的 preFallRow
                //    会基于 swappedBoard 而不是 board1，导致不该移动的 tile 被算出非零 offsetY。
                //    所以用 currentAnimBoard 逐轮跟踪：
                var currentAnimBoard = newBoard
                for ((roundIdx, cascadeRound) in cascadeResult.cascades.withIndex()) {
                    if (_state.value.phase != GamePhase.PLAYING) break

                    // FIX_PLAN P1-2：本轮重力只算一次。
                    // 此前 generateFrames 内部算一次（doRefill=false），这里
                    // 循环末尾又算一次（默认 doRefill=true，还会多跑 RNG 补
                    // tile），两份结果不同源。现在算一次、两处共用：动画帧依据
                    // 的落点与下一轮起始棋盘保证一致。
                    val fallenBoard = GravityEngine.applyGravity(
                        currentAnimBoard,
                        cascadeRound,
                        doRefill = false,
                    )

                    val frames = AnimationEngine.generateFrames(
                        currentAnimBoard,
                        cascadeRound,
                        fallenBoard = fallenBoard,
                    )

                    // 帧 0: Fade Out (0-100ms)
                    _state.update { it.copy(board = currentAnimBoard, animFrame = frames[0]) }
                    delay(ANIM_PHASE_MS)

                    // 间歇 1 (100-200ms)
                    delay(ANIM_GAP_MS)

                    // 帧 1: Fall (200-300ms)
                    _state.update { it.copy(animFrame = frames[1]) }
                    delay(ANIM_PHASE_MS)

                    // 间歇 2 (300-400ms)
                    delay(ANIM_GAP_MS)

                    // 帧 2: Spawn In (400-500ms)
                    _state.update { it.copy(animFrame = frames[2]) }
                    delay(ANIM_PHASE_MS)

                    // 本 round 重力落地 → 作为下一 round 动画的起始 board。
                    //
                    // ⚠️ 这里必须补齐空格（spawnRefill），不能直接用上面的
                    // fallenBoard：那份是 doRefill=false 的中间态，顶部留着
                    // 空洞专门给 SpawnIn 帧用。而下一轮的 matches 是 cascade
                    // 在「已补齐」的棋盘上检测出来的，起始态必须对齐，否则
                    // preFallRow 会基于错误的行号算出虚假 offsetY。
                    currentAnimBoard = BoardEngine.spawnRefill(fallenBoard)

                    // round 之间有间歇；最后一 round 后不需要额外等待
                    if (roundIdx < cascadeResult.cascades.size - 1) {
                        delay(ANIM_GAP_MS)
                    }
                }

                // 动画结束：清除 animFrame，写入最终棋盘
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

                // 时间奖励是一次性信号，走事件流而非 GameState 字段：
                // 连续两次都奖励 +5s 时字段值不变，LaunchedEffect 不会重启，
                // 飘字会漏播（FIX_PLAN D2）。SharedFlow 每次 emit 都独立投递。
                if (reward > 0) {
                    _events.tryEmit(GameEvent.TimeReward(reward))
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 协程被取消（onPause / onRestart / VM 清理）是正常控制流，
                // 必须原样抛出以维持结构化并发；finally 仍会解锁 swapProcessing。
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
