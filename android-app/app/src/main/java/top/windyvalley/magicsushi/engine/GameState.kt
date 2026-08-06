package top.windyvalley.magicsushi.engine

/**
 * GameState.kt — UI snapshot for the Magic Sushi game.
 *
 * Sits in the `engine` package next to [Models.kt] because [GameState] is
 * consumed by both the `GameViewModel` (state producer) and the Compose UI
 * layer (state consumer). It is a **pure data class** — no business logic,
 * no coroutines, no Android dependencies. The ViewModel holds a
 * `MutableStateFlow<GameState>` and emits fresh snapshots; the UI subscribes
 * via `StateFlow.collectAsState()` and re-renders on every emission.
 *
 * ---
 * ## Design (per 02-design.md §2.2 / ADR-002)
 *
 * `GameState` is the **single source of truth** for the UI. Every piece of
 * state the screen can display — the board, the score, the timer, the
 * current phase, what tile is selected, whether sound is muted, and so on —
 * lives in this one immutable data class. The UI never reads from
 * `GameViewModel` directly; it only observes `state`.
 *
 * ### State machine (`GamePhase`)
 *
 * The `phase` field drives a 4-state machine:
 *
 * ```
 *            startGame() / onRestart()
 *   IDLE  ────────────────────────────▶  PLAYING
 *                                          │  ▲
 *                                  onPause()│  │onResume()
 *                                          ▼  │
 *                                         PAUSED
 *                                          │
 *                                          │ timer == 0
 *                                          ▼
 *                                       GAME_OVER
 * ```
 *
 * - [GamePhase.IDLE]      : VM constructed but `startGame()` not yet called.
 *                           (The default constructor of [GameState] lands here
 *                           so a freshly built ViewModel is harmless before
 *                           `init` kicks off.)
 * - [GamePhase.PLAYING]   : active gameplay — board accepts input, timer
 *                           ticks down, cascades resolve.
 * - [GamePhase.PAUSED]    : activity onPause() — board frozen, timer stopped.
 *                           Resumed by `onResume()`.
 * - [GamePhase.GAME_OVER] : timer hit zero — `onGameOver()` saved the high
 *                           score (if beaten) and set `isNewRecord`. The
 *                           GameOverDialog can read `score` / `highScore` /
 *                           `isNewRecord` to render.
 *
 * The new `PAUSED` state is the v3 change from the original design (sealed
 * class `IDLE / ANIMATING / GAME_OVER`); it exists so the VM can honor
 * Android's activity lifecycle (`onPause()` / `onResume()`) without
 * resetting the round.
 *
 * ### Animation rollback signaling
 *
 * `isRollback` is a **transient one-shot signal** for the UI to know that
 * the last swap produced no match and the board is about to revert to its
 * pre-swap layout. The flow is:
 *
 * 1. User swaps two tiles.
 * 2. VM attempts the swap, calls `MatchEngine.detectMatches`, finds nothing.
 * 3. VM flips `isRollback = true` and `board = swappedBoard` (so the UI
 *    shows the tentative swap state for ~150ms).
 * 4. After `delay(150)`, VM flips `isRollback = false` and `board = original`.
 *
 * The UI should listen for `isRollback` transitions and play a brief
 * "shake / wobble / red-flash" animation on the swapped tiles. Reading the
 * flag is idempotent — only the *transition* matters, so the recompose is
 * keyed off `isRollback` going `false → true → false` within a few hundred
 * ms.
 *
 * ## Field contract
 *
 * - [board]              — current `Board` (see [BoardEngine]). `Board` is
 *                           immutable; new boards come from engine calls.
 * - [score]              — running score for this round. Reset by
 *                           [top.windyvalley.magicsushi.viewmodel.GameViewModel.startGame].
 * - [combo]              — current cascade chain length. Reset on each
 *                           successful swap resolution.
 * - [remainingSeconds]   — countdown (60 → 0, FR-6.1). Bumped by +5s per
 *                           match (capped at 90, FR-6.5 / FR-6.7).
 * - [phase]              — see [GamePhase].
 * - [selectedTile]       — currently selected tile for click-to-swap, or
 *                           `null` if nothing selected. `Pair(row, col)`.
 * - [isMuted]            — sound on/off (mirrors `PrefsRepository.muted`).
 * - [highScore]          — persisted best (mirrors `PrefsRepository.highScore`).
 * - [isRollback]         — see "Animation rollback signaling" above.
 * - [isNewRecord]        — `true` once `score > highScore` is detected at
 *                           game-over. Reset on `startGame()`.
 *
 * ## Threading
 *
 * `GameState` is a passive data class — the ViewModel is the only writer
 * and it does so via `MutableStateFlow.update { ... }` on the main thread
 * (Kotlin's `viewModelScope` defaults to `Dispatchers.Main.immediate`).
 * The UI reads via `collectAsState()`, also on the main thread. No
 * synchronization is needed.
 *
 * ## Out of scope
 *
 * - **Persistence** — `highScore` / `isMuted` are mirrors of the values in
 *   `PrefsRepository`; the VM is the bridge.
 * - **Animation locks** — `Board.swapLock` / `cascadeLock` are set by the
 *   VM, not by the UI.
 * - **Engine calls** — this file declares the data shape; the engine
 *   invocations live in `GameViewModel`.
 *
 * @see Models.kt for the underlying data models (Board, SushiTile, Match)
 * @see ADR-002 for the ViewModel+StateFlow state-management decision
 */

// ============================================================================
// 1. GamePhase — UI lifecycle state machine
// ============================================================================

/**
 * Top-level game phase (state machine). Flat 4-state enum — chosen over the
 * v2 `sealed class` (`IDLE / ANIMATING / GAME_OVER`) because the new design
 * needs an explicit `PAUSED` state for activity-lifecycle handoff, and
 * none of the states need per-instance payloads, so a sealed class would
 * just add boilerplate.
 *
 * Use [entries] (Kotlin 1.9+) instead of the deprecated [values].
 */
enum class GamePhase {
    /**
     * VM constructed but no game in progress yet. The default `GameState`
     * is in this phase. `GameViewModel.init { startGame() }` transitions
     * out of IDLE to PLAYING immediately.
     */
    IDLE,

    /**
     * Active gameplay: board accepts user input, timer ticks down once
     * per second, cascades resolve. The only phase that consumes user
     * gestures (see `onTileTapped` / `onDragEnd` in `GameViewModel`).
     */
    PLAYING,

    /**
     * Activity went into the background (`onPause()` callback). Board is
     * frozen, timer is stopped, user input is rejected. Resume via
     * `onResume()`.
     */
    PAUSED,

    /**
     * Timer hit zero. `onGameOver()` has saved the high score (if beaten)
     * and set `isNewRecord`. The GameOverDialog reads `score` /
     * `highScore` / `isNewRecord` to render. `startGame()` / `onRestart()`
     * returns to PLAYING.
     */
    GAME_OVER,
}

// ============================================================================
// 2. GameState — UI snapshot (immutable data class)
// ============================================================================

/**
 * Immutable UI snapshot. Every field has a default value so the data class
 * can be constructed with no arguments in tests and in the VM's
 * `_state.update { ... }` blocks.
 *
 * Convention: when a field is "transient" (a one-shot signal), it MUST
 * reset to its default (false / null) on the next state emission — the
 * UI keys recompose off transitions, not off the steady value. Currently
 * the only transient field is [isRollback].
 *
 * @property board              Current board state. Default: freshly
 *                              generated 7×7 no-match board.
 * @property score              Current round score. Default 0.
 * @property combo              Current cascade chain length. Default 0.
 * @property remainingSeconds   Countdown in seconds. Default
 *                              [TimerEngine.INITIAL_SECONDS] (60, FR-6.1).
 * @property phase              Lifecycle state. Default [GamePhase.IDLE].
 * @property selectedTile       Currently selected tile, or `null`. Pair
 *                              is `(row, col)`, both `0..6`.
 * @property isMuted            Sound on/off. Default false.
 * @property highScore          Persisted best score. Default 0.
 * @property isRollback         One-shot signal: `true` between the
 *                              tentative swap and the rollback (≈150ms).
 *                              Default false.
 * @property isNewRecord        `true` if this round broke the high score.
 *                              Default false. Set by `onGameOver()` in
 *                              the VM.
 */
data class GameState(
    /**
     * 当前棋盘。默认值是**空棋盘**（全 `null`），真实棋盘由
     * `GameViewModel.startGame()` 注入。
     *
     * 早期版本这里的默认值是 `BoardEngine.generateInitialBoard()`，会导致：
     * 1. `GameState()` 构造不纯 —— 默认参数里跑拒绝采样循环 + RNG；
     * 2. 每局启动白算一副棋盘 —— VM 构造 `_state` 时生成一副，
     *    紧接着 `init { startGame() }` 又生成一副，第一副直接丢弃。
     * 详见 FIX_PLAN D6。
     */
    val board: Board = Board(),
    val score: Int = 0,
    val combo: Int = 0,                        // 当前连击数
    val remainingSeconds: Int = TimerEngine.INITIAL_SECONDS,
    val phase: GamePhase = GamePhase.IDLE,
    val selectedTile: Pair<Int, Int>? = null,   // (row, col) 或 null
    val isMuted: Boolean = false,
    val highScore: Int = 0,
    val isRollback: Boolean = false,           // 上次交换无效，需弹回
    val isNewRecord: Boolean = false,          // 本局打破最高分
    // ------------------------------------------------------------------
    // Animation (T-ANIM-001)
    // ------------------------------------------------------------------
    /**
     * 当前动画帧数据。`null` = 无动画进行中，显示普通棋盘。
     *
     * 当此字段非 `null` 时，UI 应渲染 [animFrame] 而非 [board]：
     * - `animFrame` 包含每个 tile 的 `alpha`、`offsetY`、`anim` 状态
     * - [board] 在动画期间保持不变（不因动画帧更新）
     *
     * 三个动画阶段（每阶段 100 ms，阶段间有 100 ms 间歇）：
     *   Phase 1 (0-100ms):   被消除的 tile alpha 1→0（Fade Out）
     *   Phase 2 (200-300ms): 存活 tile 从原位置滑落到新位置（Fall）
     *   Phase 3 (400-500ms): 新 tile 从棋盘上方落进空位（Spawn In）
     *
     * 连锁时：每个 cascade round 各自走一遍 3 帧序列，
     * round 之间有 100 ms 间歇（由 [GameViewModel] 的 `delay()` 实现）。
     */
    val animFrame: AnimFrame? = null,
)
