package top.windyvalley.magicsushi.ui.canvas

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
 *     `(fromRow - toRow) * cellSizePx` → 0 over [CASCADE_ANIM_MS] ms.
 *   - `SpawningIn(spawnFromRow)` → offsetY lerps
 *     `spawnFromRow * cellSizePx` → 0 over [CASCADE_ANIM_MS] ms.
 *   - `Stable` / null → no cascade animation.
 *
 * The drag offset resets to zero after each drag, so the tile snaps back to
 * its layout position via `animateFloatAsState` ([ANIM_DURATION_MS] ms).
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
        animationSpec = tween(durationMillis = CASCADE_ANIM_MS),
        label = "sushiTile.cascadeOffsetY",
    )

    // Live drag offset (in pixels). Reset to Zero in onDragEnd / onDragCancel
    // so the animateFloatAsState tween smoothly snaps the tile back.
    var dragOffset by remember(type) { mutableStateOf(Offset.Zero) }

    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffset.x,
        animationSpec = tween(durationMillis = ANIM_DURATION_MS),
        label = "sushiTile.offsetX",
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = dragOffset.y + animOffsetY,
        animationSpec = tween(durationMillis = ANIM_DURATION_MS),
        label = "sushiTile.offsetY",
    )

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
                // Keyed by `type` so a tile that gets a new sushi type
                // (post-swap) rebuilds its gesture detector cleanly.
                .pointerInput(type) {
                    detectTapGestures(
                        onTap = { onClick() },
                    )
                }
                // Drag path: follow finger → onDragStart / onDragEnd.
                // Separate pointerInput block so tap and drag coexist; the
                // drag path consumes pointer changes so a successful drag
                // does not also fire a tap on release.
                .pointerInput(type) {
                    detectDragGestures(
                        onDragStart = {
                            dragOffset = Offset.Zero
                            onDragStart()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        },
                        onDragEnd = {
                            // Report the cumulative drag offset, then snap back.
                            onDragEnd(Offset.Zero, dragOffset, cellSizePx)
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
