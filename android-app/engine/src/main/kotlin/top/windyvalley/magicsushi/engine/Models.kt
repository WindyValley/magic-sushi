package top.windyvalley.magicsushi.engine

/**
 * Models.kt — Core data models for the Magic Sushi engine layer.
 *
 * **Pure Kotlin, ZERO Android dependencies.** No `android.*` imports allowed.
 * All Engine tasks (T-CORE-001 .. T-CORE-007) depend on this file, so keep it
 * minimal and stable.
 *
 * ---
 * ## Invariants (per T-CORE-008)
 *
 * - `Board` default `size = 7` (7×7 board per FR-1.1)
 * - `SushiType` has exactly **6** values (`SUSHI1` .. `SUSHI6` per FR-1.3)
 * - `Direction` has exactly **4** values (`UP`, `DOWN`, `LEFT`, `RIGHT`)
 * - `MatchAxis` has exactly **2** values (`HORIZONTAL`, `VERTICAL`)
 * - `Board.grid: List<List<SushiTile?>>` — cells may be `null` for empty
 *   (post-elimination, pre-gravity) positions
 * - All model fields are non-nullable unless explicitly nullable
 * - All `Int` fields use `Int` (not `Long`); all `Boolean` fields are non-nullable
 *
 * ## Type composition
 *
 * - `Board`, `SushiTile`, `Match` are `data class` (auto `equals`/`hashCode`/`copy`/`toString`)
 * - `SushiType`, `Direction`, `MatchAxis` are `enum class` — use `.entries` (Kotlin 1.9+), not `.values()`
 * - `GamePhase` lives in `GameState.kt` (v3 — moved out of this file) and is
 *   `enum class` (`IDLE`, `PLAYING`, `PAUSED`, `GAME_OVER`).
 *
 * ## Direction vs MatchAxis — separation of concerns
 *
 * The two enums serve **different purposes** and must NOT be merged:
 *
 * | Enum         | Values                          | Used for                                 |
 * |--------------|---------------------------------|------------------------------------------|
 * | `Direction`  | `UP`, `DOWN`, `LEFT`, `RIGHT`   | Swap gestures, gravity/fall direction    |
 * | `MatchAxis`  | `HORIZONTAL`, `VERTICAL`        | Which line a Match was detected on       |
 * | `GamePhase`  | `IDLE`, `PLAYING`, `PAUSED`, `GAME_OVER` | UI lifecycle state (in `GameState.kt`) |
 *
 * - `Direction` is **cardinal** (4 directions in the grid)
 * - `MatchAxis` is **axial** (which way the line extends)
 *
 * Keeping them separate matches both 02-arch-diagram.md Level 4 (which originally
 * conflated them) and 02-design.md §3.1's MatchEngine algorithm (which always
 * needed HORIZONTAL/VERTICAL, not cardinal).
 *
 * Resolution record:
 *   - v1 (subagent-A output): only `Direction` (4 cardinal values), no MatchAxis.
 *   - v2 (2026-06-20 13:47, main agent decision): introduce `MatchAxis` (2 axial
 *     values), keep `Direction` for cardinal uses. `Match.direction` renamed to
 *     `Match.axis: MatchAxis`.
 *   - v3 (2026-06-20 18:42, subagent-S / T-VM-001): `GamePhase` moved out of this
 *     file into `engine/GameState.kt` as an `enum class` (IDLE / PLAYING / PAUSED
 *     / GAME_OVER). The UI state machine needs a PAUSED state for activity
 *     lifecycle (paused by `onPause()`, resumed by `onResume()`), which the old
 *     sealed class (IDLE / ANIMATING / GAME_OVER) could not express. The new
 *     `GamePhase` lives next to `GameState` because both are UI snapshots
 *     (Models.kt stays Android-free pure-Kotlin data models).
 */

// ============================================================================
// 1. SushiType — 6 kinds of sushi (FR-1.3)
// ============================================================================

/**
 * The 6 sushi types in Magic Sushi. Enum constants are upper-snake-case and the
 * numeric suffix matches the drawable ID order (`sushi_1.png` .. `sushi_6.png`).
 *
 * Use [entries] (Kotlin 1.9+) instead of the deprecated [values].
 */
enum class SushiType {
    SUSHI1,
    SUSHI2,
    SUSHI3,
    SUSHI4,
    SUSHI5,
    SUSHI6;
}

// ============================================================================
// 2. SushiTile — a single cell on the board
// ============================================================================

/**
 * A single sushi piece on the board.
 *
 * @property id         globally unique tile id (used for Compose `key()` animation)
 * @property type       which of the 6 sushi types
 * @property row        current row index, 0 .. size-1
 * @property col        current col index, 0 .. size-1
 * @property isSelected currently selected by click-to-swap gesture
 * @property isLocked   locked from interaction (e.g. mid-animation)
 */
data class SushiTile(
    val id: Int,
    val type: SushiType,
    val row: Int,
    val col: Int,
    val isSelected: Boolean = false,
    val isLocked: Boolean = false,
)

// ============================================================================
// 3. Direction — 4 cardinal directions (swap gestures, gravity)
// ============================================================================

/**
 * 4 cardinal directions used for:
 *   - Swap gestures (drag direction)
 *   - Gravity / fall direction
 *
 * **Not to be confused with [MatchAxis]**: Direction is cardinal (4 values),
 * MatchAxis is axial (2 values). Both enums exist intentionally.
 */
enum class Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT;
}

// ============================================================================
// 4. MatchAxis — 2 axial values for match detection
// ============================================================================

/**
 * Which line direction a `Match` was detected on.
 *
 * Used by [Match.axis] to record whether a match is a horizontal row or a
 * vertical column. Independent of [Direction] (which is cardinal and used for
 * gestures/gravity).
 *
 * Use [entries] (Kotlin 1.9+) instead of the deprecated [values].
 */
enum class MatchAxis {
    HORIZONTAL,
    VERTICAL;
}

// ============================================================================
// 5. Board — 7×7 grid (FR-1.1)
// ============================================================================

/**
 * 7×7 board state.
 *
 * @property size        board side length. Always `7` for Magic Sushi MVP.
 * @property grid        row-major 2D list. `grid[row][col]` may be `null`
 *                       (empty cell — eliminated, awaiting gravity fill).
 * @property swapLock    `true` while a swap animation is in progress
 *                       (blocks new user gestures).
 * @property cascadeLock `true` while a cascade (chained elimination) is in
 *                       progress.
 *
 * The auto-generated `copy()` from `data class` is the default implementation
 * required by T-CORE-008 — no custom override needed.
 *
 * ## 为什么是 List 而不是 Array（FIX_PLAN D3）
 *
 * 早期实现用 `Array<Array<SushiTile?>>`，带来两个问题：
 *
 * 1. **`equals` 失真** —— Kotlin 的 `Array.equals` 是**引用相等**，所以
 *    `data class Board` 自动生成的 `equals` 对两块内容完全相同的棋盘会
 *    返回 `false`，必须靠 `contentDeepEquals` 手工比较。`List` 的
 *    `equals` 是结构相等，`Board` 的 `equals`/`hashCode` 因此变得可信。
 *
 * 2. **Compose 全量重组** —— `Array` 不是 `@Stable` 类型，Compose 无法
 *    判断它有没有变化，于是每次重组都把整块棋盘当作"可能变了"，导致
 *    49 个 tile 全部重组。`List` 是 Compose 已知的稳定类型（配合
 *    不可变元素），可跳过未变化的部分。
 *
 * 只读语义也更贴合本项目的架构：引擎层全程走"复制 → 修改副本 → 返回新
 * Board"的不可变数据流，从不原地写 grid，本就不需要 `Array` 的可变性。
 */
data class Board(
    val size: Int = 7,
    val grid: List<List<SushiTile?>> = List(size) { List(size) { null } },
    val swapLock: Boolean = false,
    val cascadeLock: Boolean = false,
)

// ============================================================================
// 6. Match — a group of 3+ matching tiles
// ============================================================================

/**
 * A detected match: 3+ consecutive same-type tiles in a line.
 *
 * @property tiles  tiles in the match. Invariant: `tiles.size == length`.
 * @property axis   which line axis the match was detected on
 *                  ([MatchAxis.HORIZONTAL] = same row, [MatchAxis.VERTICAL] = same column).
 *                  Independent of [Direction] (which is cardinal).
 * @property length number of tiles in the match (>= 3).
 *
 * Field rename history:
 *   - v1 had `direction: Direction` (cardinal — wrong axis)
 *   - v2 (2026-06-20 13:47) renamed to `axis: MatchAxis` (axial — correct)
 */
data class Match(
    val tiles: List<SushiTile>,
    val axis: MatchAxis,
    val length: Int,
)
