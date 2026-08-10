package top.windyvalley.magicsushi.ui.canvas

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import top.windyvalley.magicsushi.R
import top.windyvalley.magicsushi.engine.AnimationEngine
import top.windyvalley.magicsushi.engine.SushiType
import kotlin.math.roundToInt

/**
 * Mapping from [SushiType] enum to drawable resource ids.
 *
 * The numeric suffix (`SUSHI1`..`SUSHI6`) matches the drawable filename suffix
 * (`sushi_1`..`sushi_6`) by design — see Models.kt `SushiType` doc.
 *
 * File-private — GameCanvas delegates per-tile rendering to [SushiTile], so
 * the map only needs to be reachable from here.
 */
private val SUSHI_RESOURCES: Map<SushiType, Int> = mapOf(
    SushiType.SUSHI1 to R.drawable.sushi_1,
    SushiType.SUSHI2 to R.drawable.sushi_2,
    SushiType.SUSHI3 to R.drawable.sushi_3,
    SushiType.SUSHI4 to R.drawable.sushi_4,
    SushiType.SUSHI5 to R.drawable.sushi_5,
    SushiType.SUSHI6 to R.drawable.sushi_6,
)

// ============================================================================
// Visual constants (per ADR-001)
// ============================================================================

/** Scale factor when the tile is selected (red-border highlight). */
private const val SELECTED_SCALE = 1.15f

/** Alpha (transparency) factor applied while the tile is being dragged. */
private const val DRAGGING_ALPHA = 0.7f

/** Duration (ms) of the scale and drag-snap-back animations. */
private const val ANIM_DURATION_MS = 150

/** Duration (ms) of tile cascade animations (fade / fall / spawn). */
private const val CASCADE_ANIM_MS = 100

/**
 * 下落用的加速曲线（重力感）。
 *
 * tween 的默认 easing 是 FastOutSlowInEasing —— 两头慢、中间快，尾部会
 * **减速**。用它做下落，tile 接近落点时会缓一下，看着像飘下来而不是掉下来。
 *
 * 这条三次贝塞尔 (0.33, 0, 0.67, 0.2) 是「起步慢、越落越快、到底才停」的
 * 形状，控制点的 y 全程低于 x，意味着整段位移的速度单调上升，符合自由落体。
 *
 * 只用于 Falling / SpawningIn 的 Y 位移。淡出（alpha）仍用默认曲线 ——
 * 消失是视觉过渡，不是物理运动，加速反而显得突兀。
 */
private val FallEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 0.2f)

// ============================================================================
// Public API
// ============================================================================

/**
 * Composable that renders a single sushi tile with touch interaction.
 *
 * Per ADR-001 (touch interaction implementation):
 *   - **Tap**   → calls [onClick] (intended for the "click-to-swap" gesture).
 *   - **Drag**  → the tile follows the finger while held; on release, calls
 *                 [onDragEnd] with the cumulative drag offset. The
 *                 caller is responsible for interpreting the offset
 *                 (threshold check, direction mapping).
 *   - **Selected** → scales up to [SELECTED_SCALE] with a short tween.
 *   - **Dragging** → applies [DRAGGING_ALPHA] so the original cell shows
 *                     a "ghost" hint behind the moving tile.
 *
 * Cascade animation (T-ANIM-001):
 *   [tileAnim] drives per-phase tile animation:
 *   - `FadingOut` → alpha lerps 1 → 0 over [CASCADE_ANIM_MS] ms.
 *   - `Falling(fromRow, toRow)` → offsetY lerps
 *     `(fromRow - toRow) * cellSizePx` → 0 over [CASCADE_ANIM_MS] ms,
 *     eased by [FallEasing] (accelerating — see below).
 *   - `SpawningIn(spawnFromRow)` → offsetY lerps
 *     `spawnFromRow * cellSizePx` → 0 over [CASCADE_ANIM_MS] ms,
 *     same easing as `Falling`.
 *   - `Stable` / null → no cascade animation.
 *
 * The drag offset resets to zero after each drag, so the tile snaps back to
 * its layout position via `animateFloatAsState` ([ANIM_DURATION_MS] ms).
 *
 * ## 下落位移不再经过第二级 tween
 *
 * 拖拽位移和级联下落位移是**两条独立的路径**，只有前者走
 * [ANIM_DURATION_MS] 的 tween，两者在最后相加。
 *
 * 曾经的写法是 `targetValue = dragOffset.y + animOffsetY`，把已经是 tween
 * 输出的 `animOffsetY` 又喂给另一个 tween —— 两级串联产生二阶滞后，tile
 * 落到位后会被拉回一下，看起来在「跳动」。详见 `animatedOffsetY` 处的注释。
 *
 * Implementation notes:
 *   - `pointerInput(type)` is keyed by [type] so that gesture state is
 *     recreated when the underlying tile type changes (Compose-stable).
 *   - `detectTapGestures` and `detectDragGestures` are split across two
 *     `pointerInput` blocks so both detectors coexist; the drag path
 *     calls `change.consume()` to avoid a follow-up tap firing on release.
 *   - [cellSizePx] is provided in **pixels** so the parent (GameCanvas) can
 *     compute the drag threshold (`cellSizePx * 0.3f`) consistently with
 *     the offset units Compose hands us from `detectDragGestures`.
 *
 * @param type        which sushi image to draw
 * @param cellSizePx  size of one grid cell in **pixels**; used for both the
 *                    rendered size (converted via [LocalDensity]) and the
 *                    drag-threshold passed back in [onDragEnd]
 * @param isSelected  whether this tile is currently selected (red border);
 *                    drives the scale-up animation
 * @param isDragging  whether this tile is being dragged; drives the alpha
 *                    drop and prevents layout-position drift from looking
 *                    "detached" from the underlying cell
 * @param tileAnim    cascade animation intent for this tile; null = no cascade
 *                    animation (stable / normal rendering)
 * @param modifier    layout modifier — GameCanvas uses this to position the
 *                    tile at its `(row, col)` cell
 * @param onClick     invoked on a simple tap (no drag, or drag under threshold)
 * @param onDragStart invoked once when the user begins a drag (after touch slop)
 * @param onDragEnd   invoked when the user releases the finger; receives
 *                    `(fromOffset, toOffset, cellSizePx)`. `fromOffset` is
 *                    always `Offset.Zero`; `toOffset` is the cumulative drag
 *                    offset from drag start. `cellSizePx` echoes [cellSizePx].
 */
@Composable
fun SushiTile(
    tileId: Int,
    type: SushiType,
    cellSizePx: Float,
    isSelected: Boolean,
    isDragging: Boolean,
    tileAnim: AnimationEngine.TileAnim? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDragEnd: (fromOffset: Offset, toOffset: Offset, cellSizePx: Float) -> Unit = { _, _, _ -> },
) {
    // 手势回调必须始终指向"当前这一格"。
    //
    // 这些 lambda 由调用方 (GameCanvas) 在每次重组时新建，闭包里捕获了
    // 该格子的 (row, col)。但 pointerInput 只在它的 key 变化时才重建
    // 协程作用域 —— 若不做处理，detectTapGestures 里捕获的会是**首次
    // 组合时**那个 lambda，坐标永久停留在旧值。
    //
    // rememberUpdatedState 让长生命周期的手势闭包透过一个稳定的
    // State 容器读到最新 lambda，这是 Compose 官方针对该场景的解法。
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // Resolve drawable → ImageBitmap. Done unconditionally; Compose caches
    // the bitmaps internally so repeated calls are cheap.
    val imageBitmap: ImageBitmap? = SUSHI_RESOURCES[type]?.let { ImageBitmap.imageResource(it) }

    // Convert the pixel cell-size to dp for the Modifier.size() call.
    // (Modifier.size expects Dp; the threshold math uses raw pixels.)
    val cellSizeDp = with(LocalDensity.current) { cellSizePx.toDp() }

    // Scale: 1.15 when selected, 1.0 otherwise. Animated for a soft "pop".
    val scale by animateFloatAsState(
        targetValue = if (isSelected) SELECTED_SCALE else 1.0f,
        animationSpec = tween(durationMillis = ANIM_DURATION_MS),
        label = "sushiTile.scale",
    )

    // Alpha: 0.7 while dragging, 1.0 otherwise. Cascade fade (Phase 1)
    // multiplies on top: FadingOut → target 0, all others → target 1.
    val baseAlpha = if (isDragging) DRAGGING_ALPHA else 1.0f
    val cascadeAlphaTarget = if (tileAnim is AnimationEngine.TileAnim.FadingOut) 0f else 1f
    val animAlpha by animateFloatAsState(
        targetValue = cascadeAlphaTarget,
        animationSpec = tween(durationMillis = CASCADE_ANIM_MS),
        label = "sushiTile.cascadeAlpha",
    )
    val alpha = baseAlpha * animAlpha

    // Cascade Y offset: Falling / SpawningIn → animate to 0 (rest position).
    // Fall: offset = (fromRow - toRow) * cellSizePx (positive = fell DOWN).
    // Spawn: offset = spawnFromRow * cellSizePx (negative = above board).
    val cascadeOffsetYTarget: Float = when (tileAnim) {
        is AnimationEngine.TileAnim.Falling ->
            (tileAnim.fromRow - tileAnim.toRow).toFloat() * cellSizePx
        is AnimationEngine.TileAnim.SpawningIn ->
            tileAnim.spawnFromRow.toFloat() * cellSizePx
        else -> 0f
    }
    val animOffsetY by animateFloatAsState(
        targetValue = cascadeOffsetYTarget,
        animationSpec = tween(durationMillis = CASCADE_ANIM_MS, easing = FallEasing),
        label = "sushiTile.cascadeOffsetY",
    )

    // Live drag offset (in pixels). Reset to Zero in onDragEnd / onDragCancel
    // so the animateFloatAsState tween smoothly snaps the tile back.
    // 同样用 tileId 而非 type 作 key：换 tile 时清零拖拽位移，
    // 但同一个 tile 在重组中要保住进行中的拖拽状态。
    var dragOffset by remember(tileId) { mutableStateOf(Offset.Zero) }

    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffset.x,
        animationSpec = tween(durationMillis = ANIM_DURATION_MS),
        label = "sushiTile.offsetX",
    )
    // ⚠️ 只有**拖拽**位移走这个 tween，级联下落位移（animOffsetY）不经过它。
    //
    // ## 为什么不能把两者相加再一起 tween
    //
    // animOffsetY 本身已经是一个 tween 的输出（上面的 cascadeOffsetY，
    // CASCADE_ANIM_MS）。把它当作另一个 animateFloatAsState 的 targetValue，
    // 等于把「动画的当前值」作为「另一个动画的目标」—— 两级 tween 串联，
    // 产生二阶滞后：
    //
    //   第一级已经到 0（tile 到位）
    //   第二级还在追前一刻那个非 0 的值 → 冲过静止位再被拉回
    //
    // 视觉上就是被消除区域上方的 tile 落到位后弹一下，也就是用户说的
    // 「跳动」。两个时长不同（100ms vs 150ms）会让这个回弹更明显。
    //
    // 下落的插值已经由 cascadeOffsetY 那级负责，这里直接叠加即可。
    val animatedDragOffsetY by animateFloatAsState(
        targetValue = dragOffset.y,
        animationSpec = tween(durationMillis = ANIM_DURATION_MS),
        label = "sushiTile.dragOffsetY",
    )
    val animatedOffsetY = animatedDragOffsetY + animOffsetY

    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Sushi ${type.name}",
            // Layout size is fixed at one cell — the "pop" on selection is
            // handled by .scale(scale) below, which scales around the tile
            // center so the larger selected tile stays balanced on the cell
            // (matches T-UI-002's centered `drawSushi` growth offset).
            // The drag offset shifts the tile visually; the layout box does
            // not move, so neighboring tiles' gesture detection is unaffected.
            modifier = modifier
                .size(cellSizeDp)
                .offset {
                    IntOffset(
                        animatedOffsetX.roundToInt(),
                        animatedOffsetY.roundToInt(),
                    )
                }
                .scale(scale)
                .alpha(alpha)
                // Tap path: single-finger tap → onClick.
                // key 用 tileId（稳定身份）而非 type：type 只有 5 种取值，
                // 多轮消除后 tile 换位而 type 恰好不变时，手势作用域不会
                // 重建 —— 这正是"点到的不是视觉位置那一格"的根因。
                .pointerInput(tileId) {
                    detectTapGestures(
                        onTap = { currentOnClick() },
                    )
                }
                // Drag path: follow finger → onDragStart / onDragEnd.
                // Separate pointerInput block so tap and drag coexist; the
                // drag path consumes pointer changes so a successful drag
                // does not also fire a tap on release.
                .pointerInput(tileId) {
                    detectDragGestures(
                        onDragStart = {
                            dragOffset = Offset.Zero
                            currentOnDragStart()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        },
                        onDragEnd = {
                            // Report the cumulative drag offset, then snap back.
                            currentOnDragEnd(Offset.Zero, dragOffset, cellSizePx)
                            dragOffset = Offset.Zero
                        },
                        onDragCancel = {
                            // External cancellation (e.g. parent scrolled) — snap back.
                            dragOffset = Offset.Zero
                        },
                    )
                },
        )
    }
}
