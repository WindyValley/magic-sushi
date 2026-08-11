package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.delay

/**
 * 死局重排的动画帧生成与时序编排。
 *
 * ## 与 [playCascadeAnimation] 的分工
 *
 * 级联动画是「消除 → 下落 → 补充」三阶段、每轮三帧的固定结构。重排只有一个
 * 阶段：所有 tile 同时从旧位置移到新位置。所以这里只产两帧：
 *
 * ```
 *   frame 0   全部 tile 带位移（在旧位置），anim = Reshuffling
 *   frame 1   全部 tile 位移归零（在新位置），anim = Stable
 * ```
 *
 * frame 0 的 tile 已经**渲染在目标格**，位移把它推回起点 —— 与
 * [AnimationEngine.TileAnim.Falling] 完全一致的约定。UI 侧看到非零位移就
 * 在组合期置位到起点，再动画到 0。
 *
 * ## 弧线在哪
 *
 * **不在这里。** engine 只给起点与终点（`offsetX` / `offsetY` 两个分量），
 * 弧形轨迹由 UI 层插值。
 *
 * 这样分工的理由：弧线的半径、旋转方向、缓动曲线全是观感参数，调它们不该
 * 动 engine，更不该让 engine 的单测跟着一起改。engine 负责「谁从哪来」这个
 * 事实，UI 负责「怎么飞过去」这个表现。
 */
object ReshuffleAnimator {

    /**
     * 生成重排的两帧。
     *
     * @param fromBoard 重排前的棋盘。
     *
     *                  ⚠️ 当前实现**没有用到它** —— 位移是从 [origin] 映射
     *                  算的（`来源格 - 目标格`），不需要旧棋盘。编译器会报
     *                  `Parameter 'fromBoard' is never used`。
     *
     *                  留着是因为签名已被 8 处测试引用，删它属于接口变更，
     *                  不该夹在发版收尾里做。清理时连带更新那些调用点。
     * @param toBoard   重排后的棋盘
     * @param origin    `(目标格) -> (来源格)`，来自
     *                  [DeadlockEngine.ReshuffleResult.origin]
     * @return `[frameMoving, frameSettled]` 两帧
     */
    fun generateFrames(
        fromBoard: Board,
        toBoard: Board,
        origin: Map<Pair<Int, Int>, Pair<Int, Int>>,
    ): List<AnimFrame> {
        val frameMoving = buildMap<AnimationEngine.CellKey, AnimationEngine.TileRenderState> {
            for (row in toBoard.grid.indices) {
                for (col in toBoard.grid[row].indices) {
                    val tile = toBoard.grid[row][col] ?: continue
                    val source = origin[row to col]

                    // 没在 origin 里 = 原地未动。仍要出现在帧里（否则那格
                    // 会被渲染成空），但位移为 0、anim 为 Stable。
                    val offsetY: Float
                    val offsetX: Float
                    val anim: AnimationEngine.TileAnim

                    if (source == null || source == (row to col)) {
                        offsetY = 0f
                        offsetX = 0f
                        anim = AnimationEngine.TileAnim.Stable
                    } else {
                        // 位移 = 来源 - 目标。
                        //
                        // 符号约定与 Falling 的 offsetY 一致：正值表示「相对
                        // 静止位置向下/向右偏移」。tile 从 (0,0) 搬到 (3,4)
                        // 时，起点在目标格的左上方，所以两个分量都是负的。
                        offsetY = (source.first - row).toFloat()
                        offsetX = (source.second - col).toFloat()
                        anim = AnimationEngine.TileAnim.Reshuffling(
                            fromRow = source.first,
                            fromCol = source.second,
                        )
                    }

                    put(
                        AnimationEngine.CellKey(row, col),
                        AnimationEngine.TileRenderState(
                            tileId = tile.id,
                            type = tile.type,
                            alpha = 1f,
                            offsetY = offsetY,
                            offsetX = offsetX,
                            scale = 1f,
                            anim = anim,
                        ),
                    )
                }
            }
        }

        // 落定帧：全部归零。
        val frameSettled = buildMap<AnimationEngine.CellKey, AnimationEngine.TileRenderState> {
            for (row in toBoard.grid.indices) {
                for (col in toBoard.grid[row].indices) {
                    val tile = toBoard.grid[row][col] ?: continue
                    put(
                        AnimationEngine.CellKey(row, col),
                        AnimationEngine.TileRenderState(
                            tileId = tile.id,
                            type = tile.type,
                            alpha = 1f,
                            offsetY = 0f,
                            offsetX = 0f,
                            scale = 1f,
                            anim = AnimationEngine.TileAnim.Stable,
                        ),
                    )
                }
            }
        }

        return listOf(frameMoving, frameSettled)
    }
}

/**
 * 播放重排动画。
 *
 * 时序：推 moving 帧 → 等动画时长 → 推 settled 帧。
 *
 * ## 为什么等待时长等于动画时长
 *
 * UI 侧的位移动画由 `SushiTile` 自己驱动（组合期置位到起点，`animate` 推向
 * 0），engine 这边只负责「什么时候换帧」。等待短于动画时长会导致 settled 帧
 * 在移动还没跑完时到达，`SushiTile` 看到位移变 0 会中断当前动画 —— 就是
 * `SushiTile.kt` 里记着的那个「两级 tween 串联导致回弹」的坑。
 *
 * @param animMs         位移动画时长。**必填，无默认值。**
 *
 *                       故意不给默认值：这个数字的唯一定义处在 UI 层
 *                       （`SushiTile.kt` 的 `RESHUFFLE_ANIM_MS`），因为
 *                       「多久算看得清但不拖沓」是观感问题。engine 留默认值
 *                       等于把同一个含义的数字定义两遍，两处迟早改漏 ——
 *                       而改漏的表现很隐蔽：engine 短了则动画被落定帧打断
 *                       （位移没跑完就归零），长了则寿司到位后干等一段才
 *                       响应操作。漏传直接编译不过，比注释可靠。
 * @param shouldContinue 代际守卫。restart / 超时后返回 false，动画中止。
 * @param onFrame        把「棋盘 + 帧」推给渲染层。
 */
suspend fun playReshuffleAnimation(
    fromBoard: Board,
    toBoard: Board,
    origin: Map<Pair<Int, Int>, Pair<Int, Int>>,
    animMs: Long,
    shouldContinue: () -> Boolean = { true },
    onFrame: (board: Board?, frame: AnimFrame) -> Unit,
) {
    if (!shouldContinue()) return

    val frames = ReshuffleAnimator.generateFrames(fromBoard, toBoard, origin)

    // moving 帧：tile 渲染在新格但带位移，视觉上还在旧位置。
    //
    // 这里就把 board 换成 toBoard —— 逻辑棋盘必须与帧一致，否则手势命中
    // 测试会用旧棋盘算出错误的 tile。视觉上的「还在旧位置」纯粹由位移表达。
    onFrame(toBoard, frames[0])

    delay(animMs)

    if (!shouldContinue()) return

    // settled 帧：位移归零，动画结束。
    onFrame(toBoard, frames[1])
}
