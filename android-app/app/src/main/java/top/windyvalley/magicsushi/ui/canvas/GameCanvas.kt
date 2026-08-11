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
import top.windyvalley.magicsushi.engine.BoardPresentation
import top.windyvalley.magicsushi.engine.SushiType
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
 * @param presentation  棋盘渲染态。`Stable` 直接画棋盘，`Animating` 画动画帧。
 *                      取代早期的 `(board, animFrame?)` 双入参 —— 那对字段
 *                      隐式互斥，靠"animFrame 非空则忽略 board"的注释约定
 *                      维持正确性。
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
    presentation: BoardPresentation,
    selectedTile: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
    onTileTap: (row: Int, col: Int) -> Unit = { _, _ -> },
    onDragEnd: (fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) -> Unit = { _, _, _, _ -> },
) {
    // 逻辑棋盘：只用于取尺寸与手势命中，绝不用于绘制（见 BoardPresentation 文档）。
    val logicalBoard = when (presentation) {
        is BoardPresentation.Stable -> presentation.board
        is BoardPresentation.Animating -> presentation.logicalBoard
    }
    val gridSize = logicalBoard.size

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
        // 两个数据来源（动画帧 / 静态棋盘）先统一映射成 [TileSlot] 列表，
        // 再走**同一套**渲染逻辑。
        //
        // 早期实现是 `if (animFrame != null) {...45 行...} else {...45 行...}`，
        // 两个分支逐字重复，只有 4 处不同（key / type / tileAnim / 数据源）。
        // 手势处理（阈值判定、方向推导、边界 clamp）在两边各写一份，
        // 改动时极易只改一边 —— 这正是过去动画相关 bug 反复出现的土壤。
        //
        // D4 之后判别改为 BoardPresentation 的 `when`：新增渲染态时编译器
        // 会强制这里补分支，不再依赖"animFrame 非空则忽略 board"的口头约定。
        // ------------------------------------------------------------------
        val slots: List<TileSlot> = when (presentation) {
            // 动画进行中：身份来自 renderState.tileId，它就是真实
            // SushiTile.id（spawn tile 也一样）—— 全 App 单一身份来源。
            is BoardPresentation.Animating -> presentation.frame.map { (cellKey, renderState) ->
                TileSlot(
                    tileId = renderState.tileId,
                    row = cellKey.row,
                    col = cellKey.col,
                    type = renderState.type,
                    tileAnim = renderState.anim,
                    // 直接用 engine 算好的偏移，不在这里从 tileAnim 反推。
                    offsetYCells = renderState.offsetY,
                    offsetXCells = renderState.offsetX,
                )
            }
            // 无动画：直接读棋盘，身份同样是 tile.id。
            is BoardPresentation.Stable -> buildList {
                for (row in 0 until gridSize) {
                    for (col in 0 until gridSize) {
                        val tile = presentation.board.grid[row][col] ?: continue
                        add(
                            TileSlot(
                                tileId = tile.id,
                                row = row,
                                col = col,
                                type = tile.type,
                                tileAnim = null,
                                offsetYCells = 0f,
                            )
                        )
                    }
                }
            }
        }

        for (slot in slots) {
            val row = slot.row
            val col = slot.col
            val isSelected = selectedTile == row to col
            val isDragging = draggingTile == row to col

            key(slot.tileId) {
                SushiTile(
                    tileId = slot.tileId,
                    type = slot.type,
                    cellSizePx = cellSizePx,
                    isSelected = isSelected,
                    isDragging = isDragging,
                    tileAnim = slot.tileAnim,
                    offsetYCells = slot.offsetYCells,
                    offsetXCells = slot.offsetXCells,
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

/**
 * 一个待渲染的格子 —— [GameCanvas] 内部的统一渲染单元。
 *
 * 把"动画帧"和"静态棋盘"这两种数据来源归一化，使渲染与手势逻辑只需写一份。
 *
 * @property tileId   Compose `key()` 与手势作用域共用的稳定身份，
 *                    恒等于 [SushiTile.id]（动画中来自
 *                    `TileRenderState.tileId`，那也是真实 tile id）。
 *                    **必须全局唯一**，否则 Compose 会复用错误的 slot，
 *                    导致 `remember` 的拖拽偏移与动画状态串格（参见 D1）。
 * @property row      网格行号，用于定位与手势回调。
 * @property col      网格列号。
 * @property type     寿司类型，决定绘制哪张图。
 * @property tileAnim 动画状态；`null` 表示静态渲染（无 cascade 动画）。
 */
private data class TileSlot(
    val tileId: Int,
    val row: Int,
    val col: Int,
    val type: SushiType,
    val tileAnim: AnimationEngine.TileAnim?,
    /**
     * 下落距离，单位是**格数**，由 engine 算好。
     *
     * 语义沿用 engine 的领域约定：**正值 = 这个 tile 往下落了几格**。
     * Stable 态（无动画）时为 0f。
     *
     * ⚠️ 这不是 Compose 的 y 位移 —— Compose 里 y 向下为正，而动画起点在
     * 落点上方，所以消费时要取负。转换在 `SushiTile` 内部完成，这里保持
     * engine 口径不变。
     *
     * ⚠️ 必须用 engine 的 `TileRenderState.offsetY`，不要在 UI 侧从
     * `TileAnim` 重新推导 —— 那会变成同一个公式的两份实现。曾经的 bug：
     * UI 对 `SpawningIn(spawnFromRow)` 算的是 `spawnFromRow`，而正确的
     * 落差是 `row - spawnFromRow`。row=2、spawnFromRow=-3 的顶部空洞，
     * 实际要落 5 格，UI 只让它落了 3 格 —— 起点还在棋盘内。
     */
    val offsetYCells: Float,
    /**
     * 横向位移，单位**格数**，由 engine 算好。仅重排动画非零。
     *
     * ## 符号约定与 offsetYCells 不同
     *
     * 这个是「来源格 - 目标格」的差值：负值表示起点在目标格**左边**。
     * 消费时**不取负**，直接就是 Compose 需要的方向。
     *
     * 而 [offsetYCells] 是「往下落了几格」的正值领域语义，消费时要取负。
     * 两套口径并存看着别扭，但各自都对：下落是物理运动（engine 描述
     * 「落了多远」），重排是位置置换（engine 描述「从哪来」）。
     *
     * 重排的 Y 分量也走这一套 —— 见 `ReshuffleAnimator.generateFrames`，
     * 它给 offsetY 填的同样是 `source.row - row`，与这里同源。
     */
    val offsetXCells: Float = 0f,
)