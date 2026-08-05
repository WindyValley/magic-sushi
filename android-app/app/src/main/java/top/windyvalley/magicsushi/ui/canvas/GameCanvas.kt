package top.windyvalley.magicsushi.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import top.windyvalley.magicsushi.engine.AnimFrame
import top.windyvalley.magicsushi.engine.AnimationEngine
import top.windyvalley.magicsushi.engine.Board
import kotlin.math.abs

// ============================================================================
// Drawing constants — kept in sync with the original GameCanvas visuals so
// the refactor (Canvas drawImage → SushiTile composable) does not change
// the rendered appearance.
// ============================================================================

/** Outer canvas background — matches `SushiBgDark` in Color.kt. */
private val CanvasBgColor = Color(0xFF2A1810)

/** Cell background — slightly lighter than canvas for depth. */
private val CellBgColor = Color(0xFF3D2817)

/** Cell border — wood-like warm brown. */
private val CellBorderColor = Color(0xFF5C4033)

/** Selected-cell highlight border — red flash. */
private val SelectionBorderColor = Color(0xFFFF4444)

/** Stroke width for the selection highlight, in pixels. */
private const val SelectionStrokeWidthPx = 3f

/** Stroke width for the regular cell border, in pixels. */
private const val CellBorderStrokeWidthPx = 1f

/** Threshold ratio of cell width for an "intent to swap" drag (per ADR-001). */
private const val DragThresholdRatio = 0.3f

/**
 * Composable that renders the Magic Sushi 7×7 game board.
 *
 * Layout (post T-UI-003 refactor):
 *   1. [BoxWithConstraints] establishes a square, full-width canvas. The
 *      actual canvas width (in px and dp) is read from `maxWidth` so children
 *      can size and position themselves proportionally.
 *   2. A bottom-layer [Canvas] draws the grid background + cell borders +
 *      selection highlight. **No sushi is drawn here** — sushi is rendered
 *      by [SushiTile] composables layered on top.
 *   3. A loop places a [SushiTile] at every non-null `(row, col)`,
 *      offset by `cellSize × col/row`. The tile owns its own tap/drag
 *      gestures and visual state (scale, alpha, drag offset).
 *
 * Touch interaction (per ADR-001):
 *   - Tap → `onTileTap(row, col)`
 *   - Drag above `DragThresholdRatio × cellSize` in either axis →
 *     `onDragEnd(fromRow, fromCol, toRow, toCol)`. Direction is determined
 *     by the dominant axis (X vs Y) of the drag offset; the swap target
 *     is the **immediate neighbor only** (one cell in that direction),
 *     clamped to the board bounds.
 *   - Drag below threshold → falls back to `onTileTap(row, col)`
 *     (drag-as-tap behavior, see ADR-001 §实现要点 1).
 *
 * State ownership:
 *   - [selectedTile] and the [draggingTile] pair live here (not in the VM)
 *     because they are pure UI concerns: which cell is highlighted / which
 *     tile is in the middle of a drag gesture.
 *   - The actual game logic (swap, match, cascade) stays in the
 *     ViewModel and is triggered via [onTileTap] / [onDragEnd].
 *
 * @param board         current board state (cells may be `null` for empty)
 * @param selectedTile  the `(row, col)` currently selected, or `null`
 * @param onTileTap     callback invoked with `(row, col)` on a tap, or when
 *                      a drag ends below [DragThresholdRatio]
 * @param onDragEnd     callback invoked with `(fromRow, fromCol, toRow, toCol)`
 *                      when a drag ends above [DragThresholdRatio] and the
 *                      target cell is a valid in-bounds neighbor. The
 *                      `(fromRow, fromCol)` pair always equals the
 *                      originating tile; `(toRow, toCol)` is the
 *                      neighboring cell in the drag direction, clamped.
 * @param modifier      outer modifier; default gives a full-width square
 */
@Composable
fun GameCanvas(
    board: Board,
    selectedTile: Pair<Int, Int>?,
    animFrame: AnimFrame? = null,
    modifier: Modifier = Modifier,
    onTileTap: (row: Int, col: Int) -> Unit = { _, _ -> },
    onDragEnd: (fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) -> Unit = { _, _, _, _ -> },
) {
    val gridSize = board.size

    // Local UI state: which tile is currently being dragged (if any).
    // Reset by the drag-end callback below; survives recompositions.
    var draggingTile by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(CanvasBgColor),
    ) {
        // Resolve the canvas size once per layout pass. Both px and dp are
        // needed: px for Canvas drawing + drag-threshold math, dp for
        // Modifier.offset / Modifier.size of the SushiTile layer.
        val cellSizeDp = maxWidth / gridSize
        val density = LocalDensity.current
        val cellSizePx = with(density) { cellSizeDp.toPx() }
        val canvasWidthPx = cellSizePx * gridSize

        // ------------------------------------------------------------------
        // Layer 1: grid background + cell borders + selection highlight.
        // This Canvas no longer draws any sushi — sushi is rendered by
        // SushiTile composables below.
        // ------------------------------------------------------------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1a. Outer background (BoxWithConstraints already paints
            //     CanvasBgColor via .background(); we keep this defensive
            //     drawRect so the canvas surface is fully owned by the
            //     drawing routine, matching the pre-T-UI-003 behavior).
            drawRect(
                color = CanvasBgColor,
                size = Size(canvasWidthPx, canvasWidthPx),
            )

            // 1b. Iterate every cell.
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val x = col * cellSizePx
                    val y = row * cellSizePx

                    val isSelected = selectedTile != null &&
                        selectedTile.first == row &&
                        selectedTile.second == col

                    // Cell background.
                    drawRect(
                        color = CellBgColor,
                        topLeft = Offset(x, y),
                        size = Size(cellSizePx, cellSizePx),
                    )

                    // Selection highlight (red border, inset 2 px).
                    if (isSelected) {
                        drawRect(
                            color = SelectionBorderColor,
                            topLeft = Offset(x + 2f, y + 2f),
                            size = Size(cellSizePx - 4f, cellSizePx - 4f),
                            style = Stroke(width = SelectionStrokeWidthPx),
                        )
                    }

                    // Cell border.
                    drawRect(
                        color = CellBorderColor,
                        topLeft = Offset(x, y),
                        size = Size(cellSizePx, cellSizePx),
                        style = Stroke(width = CellBorderStrokeWidthPx),
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // Layer 2: sushi tiles.
        //
        // When [animFrame] is non-null, tiles are rendered from the frame data
        // (which carries per-tile alpha/offsetY/anim state for cascade animation).
        // The frame uses `CellKey(row, col)` as the key, so Compose can track
        // each tile's identity across frames even when tiles move.
        //
        // When [animFrame] is null, fall back to the normal board grid
        // (no cascade animation in progress).
        // ------------------------------------------------------------------
        if (animFrame != null) {
            // Render from animation frame — tile identity is keyed by visualId.
            for ((cellKey, renderState) in animFrame) {
                val (row, col) = cellKey.row to cellKey.col
                val isSelected = selectedTile == row to col
                val isDragging = draggingTile == row to col

                key(renderState.visualId) {
                    SushiTile(
                        type = renderState.type,
                        cellSizePx = cellSizePx,
                        isSelected = isSelected,
                        isDragging = isDragging,
                        tileAnim = renderState.anim,
                        onClick = { onTileTap(row, col) },
                        onDragStart = { draggingTile = row to col },
                        onDragEnd = { _, toOffset, cs ->
                            val threshold = cs * DragThresholdRatio
                            val absX = abs(toOffset.x)
                            val absY = abs(toOffset.y)
                            val (dRow, dCol) = when {
                                absX >= absY && absX > threshold ->
                                    if (toOffset.x > 0f) 0 to 1 else 0 to -1
                                absY > absX && absY > threshold ->
                                    if (toOffset.y > 0f) 1 to 0 else -1 to 0
                                else -> 0 to 0
                            }
                            draggingTile = null
                            if (dRow == 0 && dCol == 0) {
                                onTileTap(row, col)
                            } else {
                                val toRow = (row + dRow).coerceIn(0, gridSize - 1)
                                val toCol = (col + dCol).coerceIn(0, gridSize - 1)
                                if (toRow != row || toCol != col) {
                                    onDragEnd(row, col, toRow, toCol)
                                }
                            }
                        },
                        modifier = Modifier.offset(
                            x = cellSizeDp * col.toFloat(),
                            y = cellSizeDp * row.toFloat(),
                        ),
                    )
                }
            }
        } else {
            // Render from board grid — no cascade animation.
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val tile = board.grid[row][col] ?: continue
                    val isSelected = selectedTile == row to col
                    val isDragging = draggingTile == row to col

                    key(tile.id) {
                        SushiTile(
                            type = tile.type,
                            cellSizePx = cellSizePx,
                            isSelected = isSelected,
                            isDragging = isDragging,
                            tileAnim = null,
                            onClick = { onTileTap(row, col) },
                            onDragStart = { draggingTile = row to col },
                            onDragEnd = { _, toOffset, cs ->
                                val threshold = cs * DragThresholdRatio
                                val absX = abs(toOffset.x)
                                val absY = abs(toOffset.y)
                                val (dRow, dCol) = when {
                                    absX >= absY && absX > threshold ->
                                        if (toOffset.x > 0f) 0 to 1 else 0 to -1
                                    absY > absX && absY > threshold ->
                                        if (toOffset.y > 0f) 1 to 0 else -1 to 0
                                    else -> 0 to 0
                                }
                                draggingTile = null
                                if (dRow == 0 && dCol == 0) {
                                    onTileTap(row, col)
                                } else {
                                    val toRow = (row + dRow).coerceIn(0, gridSize - 1)
                                    val toCol = (col + dCol).coerceIn(0, gridSize - 1)
                                    if (toRow != row || toCol != col) {
                                        onDragEnd(row, col, toRow, toCol)
                                    }
                                }
                            },
                            modifier = Modifier.offset(
                                x = cellSizeDp * col.toFloat(),
                                y = cellSizeDp * row.toFloat(),
                            ),
                        )
                    }
                }
            }
        }
    }
}