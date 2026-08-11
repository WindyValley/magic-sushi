package top.windyvalley.magicsushi.ui.canvas

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * 级联动画（淡出 / 下落 / 生成）的时长，毫秒。**全 App 唯一定义处。**
 *
 * `GameViewModel.ANIM_PHASE_MS` 直接引用它 —— 两者必须相等：相位间隔短于
 * 动画时长，位移动画会被下一帧打断；长于则寿司落定后干等。
 *
 * `internal` 而非 `private` 就是为了让 ViewModel 能引用，不用再写一个 100。
 */
internal const val CASCADE_ANIM_MS = 100

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

/**
 * 重排动画时长（毫秒）。**全 App 唯一定义处。**
 *
 * ## 为什么定义在 UI 层
 *
 * 这是个观感参数 —— 多久算「看得清但不拖沓」只有看着屏幕才能定。engine
 * 不该被观感绑住，所以它不持有这个值，而是由 `playReshuffleAnimation` 的
 * 必填参数接收。
 *
 * ## 为什么不给 engine 侧留默认值
 *
 * 留了默认值就等于在 engine 里又定义了一遍同一个含义的数字，两处迟早改漏
 * （engine 推落定帧的时机与 UI 跑动画的时长不一致：UI 更长会被落定帧打断，
 * 动画没跑完位移就归零；更短则寿司到位后干等一段才响应操作）。
 *
 * 现在 engine 侧是必填参数，漏传直接编译不过。
 *
 * `internal` 而非 `private`：ViewModel 要读它传给 engine。
 */
internal const val RESHUFFLE_ANIM_MS = 420

/**
 * 弧高与移动距离的比例。
 *
 * 0.18 大约是「明显看得出是弧线，但不至于绕远路」的量。调大到 0.3 以上会
 * 让寿司划出夸张的大圈，短距离移动尤其明显；小于 0.1 则几乎看不出弯。
 */
private const val ARC_RATIO = 0.18f

/**
 * 重排的缓动曲线。
 *
 * 用 `FastOutSlowIn` 而非下落那条 [FallEasing]：下落要表现重力加速（越落
 * 越快），重排是「被一只手拿起来放到别处」，起步快、临近落点减速更贴合。
 */
private val ReshuffleEasing = FastOutSlowInEasing

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
 *   - `Falling(fromRow, toRow)` → 起始位移 = `-engineOffsetY * cellSizePx`
 *     （在落点上方），动画到 0，eased by [FallEasing]（加速）。
 *   - `SpawningIn(spawnFromRow)` → 同上，起点在棋盘外上方。
 *   - `Stable` / null → no cascade animation.
 *
 * 位移量由 engine 的 `TileRenderState.offsetY` 给出（经 [offsetYCells] 传入），
 * **不在这里从 [tileAnim] 反推** —— 详见 `cascadeOffsetYTarget` 处关于两套
 * 符号约定的说明。
 *
 * The drag offset resets to zero after each drag, so the tile snaps back to
 * its layout position via `animateFloatAsState` ([ANIM_DURATION_MS] ms).
 *
 * ## 下落位移不再经过第二级 tween
 *
 * 拖拽位移和级联下落位移是**两条独立的路径**，只有前者走
 * [ANIM_DURATION_MS] 的 tween，两者在最后相加。
 *
 * 曾经的写法是 `targetValue = dragOffset.y + animOffsetY`，把已经是动画
 * 输出的 `animOffsetY` 又喂给一个 tween —— 两级串联产生二阶滞后，tile
 * 落到位后会被拉回一下，看起来在「跳动」。详见 `cascadeAnim` 处的注释。
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
    offsetYCells: Float = 0f,
    offsetXCells: Float = 0f,
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

    // Cascade Y offset: 起点由 engine 给出（offsetYCells，单位格数）。
    //
    // ## 两套符号约定，这里是唯一的转换点
    //
    // engine 的 offsetY 是**领域语义**：「这个 tile 往下落了几格」，正值。
    // 由 `Falling offsetY must be positive` 等测试钉住。
    //
    // Compose 的 y 轴**向下为正**，而 tile 已经渲染在落点上，动画起点在
    // 落点**上方** —— 上方是更小的 y。所以起始位移 = **负**的落差：
    //
    //   engine: 往下落了 3 格  (+3)
    //   Compose: 起点在上方 3 格 (-3)，动画到 0 就是落下来
    //
    // 取负只在这一处发生。engine 不改（领域语义是对的），UI 不再自己
    // 从 tileAnim 反推距离（那会变成同一公式的两份实现）。
    //
    // ⚠️ 曾经 UI 侧自己算，两个分支的口径还不一致：
    //   Falling(fromRow, toRow)   算 fromRow - toRow      = -落差  ✓ 恰好等价于取负
    //   SpawningIn(spawnFromRow)  算 spawnFromRow          ✗ 距离错了
    //
    // 后者是这次的 bug：row=2、spawnFromRow=-3 的顶部空洞，实际要落
    // 5 格（从棋盘外第 3 行落到第 2 行），UI 只让它落了 3 格 ——
    // 新生成的 tile 一直落得比应有距离短，起点还在棋盘内。
    val cascadeOffsetYTarget: Float = -offsetYCells * cellSizePx

    // ⚠️ 用 Animatable 手工控制，不能用 animateFloatAsState。原因见下。
    //
    // ## 为什么 animateFloatAsState 会导致「落地后弹回去」
    //
    // engine 每轮产出 3 帧，同一个 tile 的 anim 依次是：
    //
    //   frame 1  Falling(origRow, row)   起始位移 = -落差（在落点上方）
    //   frame 2  Stable                  起始位移 = 0
    //
    // 注意 tile 在 frame 1 就已经渲染在**目标行**了，位移的作用是把它
    // 「往上推」到起点，动画到 0 的过程才是视觉上的下落。
    //
    // 但相位间隔与动画时长相等（ANIM_PHASE_MS 直接引用 CASCADE_ANIM_MS，
    // 所以恒等），frame 2 到达时第一段动画往往还没跑完。此时 animateFloatAsState
    // 看到 targetValue 从 -落差 突变为 0，会**从当前值重新起跑一段新动画** ——
    // 而当前值是个中间量，于是 tile 先往上跳回一点再落下。那就是回弹。
    //
    // 根因不是 easing、不是时长、也不是新生成 tile 被重复计算：是「用
    // targetValue 表达一次性冲量」这件事本身不成立 —— 声明式动画只知道
    // 「目标变了」，不知道「这是同一次下落的延续」。
    //
    // ## 改法
    //
    // 目标值永远是 0，下落起点在组合期一次性置位：
    //
    //   看到 Falling/SpawningIn（offset 非 0）→ 组合期置位到起点，动画到 0
    //   看到 Stable                           → 直接落定，不播动画
    //
    // key 用 tileId：换 tile 时重建动画状态，同一 tile 跨帧保持进行中的下落。
    //
    // ## ⚠️ 起点必须在**组合期**置位，不能靠 LaunchedEffect
    //
    // 这里有两条不同的时间线，只改「初值」只能救其中一条：
    //
    //   新生成的 tile：首次进入组合时 target 已经非 0
    //       → 初值就是起点，首帧正确
    //         （engine 的 frame 1 对 spawn 格子是 continue，什么都不画，
    //          所以它的生命周期是 frame1 不存在 → frame2 带 SpawningIn 出现）
    //
    //   已在棋盘上的 tile：Stable 时状态就建好了（初值 0），之后 Falling
    //   帧到来，target 变成 -落差，但 `remember(tileId)` **不会重建** ——
    //   初值参数根本不再求值
    //       → 这一帧仍然渲染 offset=0（落点），下一帧副作用才置位。
    //         中间那一帧就是「闪」
    //
    // 只改初值时，新 tile 不闪了但已有 tile 还闪，就是因为第二条时间线
    // 没被覆盖。置位必须发生在**组合期**（本帧渲染之前）。
    //
    // 这也是为什么这里不用 Animatable：它的 `snapTo` 是 suspend 函数
    // （javap 确认签名带 Continuation），只能在协程里调，天生晚一帧。
    // 改用普通 Float state 自己驱动动画 —— 置位就是一次同步赋值。
    //
    // 同类陷阱见 ScoreOverlay 的 `remember { Animatable(currentScore) }`：
    // remember 的 lambda 只在首次组合求值，任何"稍后再修正"的写法都会
    // 先渲染一帧错的。
    var cascadeOffset by remember(tileId) { mutableStateOf(cascadeOffsetYTarget) }

    // 上一次已置位的 target。用它判断「target 变了」，避免每次重组都重置
    // 位移（那会让进行中的动画永远停在起点）。
    var lastTarget by remember(tileId) { mutableStateOf(cascadeOffsetYTarget) }

    // 组合期同步置位：target 变了就立刻把 tile 放到新起点。
    //
    // 写 snapshot state 在组合期是允许的（它就是 remember 的值），关键是
    // 这一句在返回 UI 之前执行，所以**本帧**渲染用的就是起点值，不会先
    // 画一帧落点。
    if (cascadeOffsetYTarget != lastTarget) {
        cascadeOffset = cascadeOffsetYTarget
        lastTarget = cascadeOffsetYTarget
    }

    // 动画：从当前 cascadeOffset 跑到 0。
    //
    // 用 animate(...) 的手工循环而不是 animateFloatAsState，原因见上面
    // 「为什么 animateFloatAsState 会导致落地后弹回去」：target 恒为 0，
    // 起点由上面的组合期赋值决定，动画只负责把它推向 0。
    LaunchedEffect(tileId, cascadeOffsetYTarget) {
        if (cascadeOffsetYTarget == 0f) {
            // Stable / FadingOut：确保落定，不播动画。
            cascadeOffset = 0f
            return@LaunchedEffect
        }
        val from = cascadeOffsetYTarget
        val spec = tween<Float>(durationMillis = CASCADE_ANIM_MS, easing = FallEasing)
        animate(
            initialValue = from,
            targetValue = 0f,
            animationSpec = spec,
        ) { value, _ ->
            cascadeOffset = value
        }
    }
    val animOffsetY = cascadeOffset

    // ========================================================================
    // 重排位移（X + Y 双分量 + 弧线）
    // ========================================================================
    //
    // 与级联下落共用「组合期置位 → 动画到 0」的模式，但多两件事：
    //   1. X 方向也要动（洗牌是二维搬家，下落只有 Y）
    //   2. 走弧线而非直线
    //
    // ## 为什么不复用 cascadeOffset
    //
    // 那个只有 Y 分量，且 key 是 `cascadeOffsetYTarget` 单值。重排要两个
    // 分量同步推进，共用一个 Animatable 会让 X/Y 各跑一条 LaunchedEffect，
    // 中途重组时两者进度不同 —— 视觉上是先斜着走再拐弯。
    //
    // ## 为什么不用 animateFloatAsState
    //
    // 同一个坑：重排帧序列是 `Reshuffling(带位移) → Stable(位移 0)`，
    // 后一帧到达时前一段动画可能还没跑完，声明式动画会从中间值重新起跑，
    // 表现为「快到位了又弹一下」。见上面 CASCADE 那段的详细分析。
    val reshuffleAnim = tileAnim as? AnimationEngine.TileAnim.Reshuffling

    // 起点位移（像素）。符号约定与 offsetYCells 一致：engine 给的是
    // 「来源 - 目标」的格数差，Compose 里 y 向下为正、x 向右为正，
    // 而 tile 已渲染在目标格，所以位移直接就是「往起点方向推」的量。
    //
    // 注意这里**不取负** —— 与级联那边不同。engine 的 offsetX/offsetY
    // 对重排给的已经是 `source - target`（负值表示起点在左上），
    // 正是 Compose 需要的方向。级联的 offsetY 是「往下落了几格」的正值
    // 领域语义，才需要取负。两处口径不同，都在注释里钉住。
    val reshuffleTargetX: Float = (reshuffleAnim?.let { offsetXCells } ?: 0f) * cellSizePx
    val reshuffleTargetY: Float = (reshuffleAnim?.let { offsetYCells } ?: 0f) * cellSizePx

    // 进度 0→1。用它同时驱动 X、Y 和弧线偏移，保证三者永远同相位。
    var reshuffleProgress by remember(tileId) { mutableStateOf(if (reshuffleAnim != null) 0f else 1f) }
    var lastReshuffleKey by remember(tileId) { mutableStateOf(reshuffleTargetX to reshuffleTargetY) }

    // 组合期同步置位：target 变了就把进度打回 0（回到起点）。
    // 与级联那边同理 —— 靠 LaunchedEffect 会先渲染一帧在终点，就是「闪」。
    if ((reshuffleTargetX to reshuffleTargetY) != lastReshuffleKey) {
        reshuffleProgress = if (reshuffleAnim != null) 0f else 1f
        lastReshuffleKey = reshuffleTargetX to reshuffleTargetY
    }

    LaunchedEffect(tileId, reshuffleTargetX, reshuffleTargetY) {
        if (reshuffleAnim == null) {
            reshuffleProgress = 1f
            return@LaunchedEffect
        }
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RESHUFFLE_ANIM_MS,
                easing = ReshuffleEasing,
            ),
        ) { value, _ ->
            reshuffleProgress = value
        }
    }

    // 弧线合成。
    //
    // ## 怎么把直线掰成弧
    //
    // 直线插值是「从起点线性推向 0」：
    //
    //   straight = start * (1 - t)
    //
    // 弧线 = 直线 + 一个**垂直于运动方向**的偏移，偏移量在中点最大、
    // 两端为 0。用 sin(πt) 做这个包络：t=0 和 t=1 时为 0，t=0.5 时为 1。
    //
    //   perpendicular = normalize(rotate90(direction)) * arcHeight * sin(πt)
    //
    // 旋转 90° 取 `(-dy, dx)`（Compose 坐标系下顺时针），所以所有寿司的
    // 弧都朝同一侧弯 —— 整盘看起来是一致的旋转感，而不是各自乱拐。
    //
    // 弧高与距离成正比（`ARC_RATIO`），远的弯得多、近的弯得少。固定弧高
    // 会让只挪一格的 tile 划出夸张的大弯，而横跨全盘的 tile 看着几乎是直线。
    val reshuffleOffset: Offset = if (reshuffleAnim == null) {
        Offset.Zero
    } else {
        val t = reshuffleProgress
        // 直线部分：从起点位移衰减到 0。
        val straightX = reshuffleTargetX * (1f - t)
        val straightY = reshuffleTargetY * (1f - t)

        // 运动方向 = 从起点指向终点 = -(起点位移)。
        val dirX = -reshuffleTargetX
        val dirY = -reshuffleTargetY
        val dist = sqrt(dirX * dirX + dirY * dirY)

        if (dist < 0.5f) {
            // 距离几乎为 0（不该出现在 Reshuffling 里，但防一手除零）。
            Offset(straightX, straightY)
        } else {
            // 垂直方向单位向量：把方向向量转 90°。
            val perpX = -dirY / dist
            val perpY = dirX / dist

            val arcHeight = dist * ARC_RATIO
            val bulge = arcHeight * sin(PI.toFloat() * t)

            Offset(straightX + perpX * bulge, straightY + perpY * bulge)
        }
    }

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
    // 最终位移 = 拖拽 + 级联下落 + 重排弧线。
    //
    // 三者相加而非嵌套动画：每一项都已经是各自动画的输出值，再套一层 tween
    // 就会产生二阶滞后（见上面 animatedDragOffsetY 那段注释里的回弹分析）。
    //
    // 实践上三者不会同时非零 —— 重排时不能拖拽，级联时手势被锁 —— 但相加
    // 天然处理了边界情况，不需要额外的互斥判断。
    val animatedOffsetY = animatedDragOffsetY + animOffsetY + reshuffleOffset.y

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
                        // ⚠️ 重排的 X 位移在这里相加，**不能**塞进上面
                        // animatedOffsetX 那个 animateFloatAsState 的 targetValue。
                        // 那会把已经是动画输出的值再喂给一个 tween ——
                        // 正是 SushiTile 历史上「落到位后弹一下」的成因。
                        (animatedOffsetX + reshuffleOffset.x).roundToInt(),
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
