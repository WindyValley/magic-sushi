package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ReshuffleAnimator 的帧生成测试。
 *
 * 重点是**位移方向**和**帧完整性** —— 这两处错了动画就是乱窜或缺格，
 * 而真机上「看着不对」很难定位到具体是哪一环。
 */
class ReshuffleAnimatorTest {

    /** 造一个 4×4 的确定性棋盘，id 就是 row*4+col。 */
    private fun board4x4(types: List<SushiType>): Board {
        val grid = (0 until 4).map { row ->
            (0 until 4).map { col ->
                SushiTile(
                    id = row * 4 + col,
                    type = types[row * 4 + col],
                    row = row,
                    col = col,
                )
            }
        }
        return Board(size = 4, grid = grid)
    }

    private val palette = listOf(
        SushiType.SUSHI1, SushiType.SUSHI2,
        SushiType.SUSHI3, SushiType.SUSHI4,
    )

    /** 2×2 分块图案：必然死局。 */
    private fun deadlockTypes(): List<SushiType> = (0 until 16).map { i ->
        val row = i / 4
        val col = i % 4
        palette[(row / 2 % 2) * 2 + (col / 2 % 2)]
    }

    // ========================================================================
    // 帧结构
    // ========================================================================

    @Test
    fun `generates exactly two frames`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val frames = ReshuffleAnimator.generateFrames(from, result.board, result.origin)

        assertEquals("重排只有一个阶段，两帧：移动中 + 落定", 2, frames.size)
    }

    @Test
    fun `every cell appears in both frames`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val frames = ReshuffleAnimator.generateFrames(from, result.board, result.origin)

        // 漏掉任何一格，那格在动画期间会被渲染成空 —— 视觉上是「寿司凭空
        // 消失一瞬」。这是最容易犯又最难在真机上定位的错。
        for (frame in frames) {
            assertEquals("每帧必须覆盖全部 16 格", 16, frame.size)
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    assertNotNull(
                        "($row,$col) 缺失，动画期间那格会是空的",
                        frame[AnimationEngine.CellKey(row, col)],
                    )
                }
            }
        }
    }

    @Test
    fun `settled frame has zero offsets everywhere`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val settled = ReshuffleAnimator.generateFrames(from, result.board, result.origin)[1]

        for ((key, state) in settled) {
            assertEquals("落定帧 $key 的 offsetX 必须为 0", 0f, state.offsetX, 0.001f)
            assertEquals("落定帧 $key 的 offsetY 必须为 0", 0f, state.offsetY, 0.001f)
            assertEquals(
                "落定帧 $key 必须是 Stable —— 残留 Reshuffling 会让 UI 再播一次",
                AnimationEngine.TileAnim.Stable,
                state.anim,
            )
        }
    }

    // ========================================================================
    // 位移方向 —— 弧线画错的根源就在这
    // ========================================================================

    @Test
    fun `moving frame offset equals source minus target`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val moving = ReshuffleAnimator.generateFrames(from, result.board, result.origin)[0]

        for ((target, source) in result.origin) {
            val (tr, tc) = target
            val (sr, sc) = source
            val state = moving[AnimationEngine.CellKey(tr, tc)]!!

            // 符号约定：位移 = 来源 - 目标。
            //
            // tile 已经渲染在目标格，位移的作用是把它「推回起点」。
            // 从 (0,0) 搬到 (3,4) 的 tile，起点在目标格左上方，
            // 两个分量都应该是负的。
            //
            // 这个约定与 UI 层 SushiTile 的消费方式绑定 —— 改这里必须同步改
            // 那边，否则寿司会朝反方向飞出去再倒回来。
            assertEquals(
                "($tr,$tc) 来自 ($sr,$sc)，offsetY 应为 ${sr - tr}",
                (sr - tr).toFloat(),
                state.offsetY,
                0.001f,
            )
            assertEquals(
                "($tr,$tc) 来自 ($sr,$sc)，offsetX 应为 ${sc - tc}",
                (sc - tc).toFloat(),
                state.offsetX,
                0.001f,
            )
        }
    }

    @Test
    fun `moved cells carry Reshuffling anim with correct source`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val moving = ReshuffleAnimator.generateFrames(from, result.board, result.origin)[0]

        for ((target, source) in result.origin) {
            val state = moving[AnimationEngine.CellKey(target.first, target.second)]!!
            val anim = state.anim

            assertTrue(
                "移动了的 $target 必须带 Reshuffling，否则 UI 不知道要播动画",
                anim is AnimationEngine.TileAnim.Reshuffling,
            )
            anim as AnimationEngine.TileAnim.Reshuffling
            assertEquals("fromRow 必须等于来源行", source.first, anim.fromRow)
            assertEquals("fromCol 必须等于来源列", source.second, anim.fromCol)
        }
    }

    @Test
    fun `cells absent from origin are Stable with zero offset`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val moving = ReshuffleAnimator.generateFrames(from, result.board, result.origin)[0]

        for (row in 0 until 4) {
            for (col in 0 until 4) {
                if (result.origin.containsKey(row to col)) continue

                // 不在 origin 里 = 原地未动。仍然要出现在帧里（否则那格空白），
                // 但不能带位移，否则会凭空飞一下。
                val state = moving[AnimationEngine.CellKey(row, col)]!!
                assertEquals("未移动的 ($row,$col) offsetX 应为 0", 0f, state.offsetX, 0.001f)
                assertEquals("未移动的 ($row,$col) offsetY 应为 0", 0f, state.offsetY, 0.001f)
                assertEquals(
                    "未移动的 ($row,$col) 应是 Stable",
                    AnimationEngine.TileAnim.Stable,
                    state.anim,
                )
            }
        }
    }

    // ========================================================================
    // 身份一致性
    // ========================================================================

    @Test
    fun `frame tile ids match the destination board`() {
        val from = board4x4(deadlockTypes())
        val result = DeadlockEngine.reshuffleIfDeadlocked(from, Random(7))

        val frames = ReshuffleAnimator.generateFrames(from, result.board, result.origin)

        // GameCanvas 用 tileId 做 Compose 的 key。帧里的 id 与最终棋盘不一致，
        // 动画结束切回静态渲染时 Compose 会认为「旧 tile 消失、新 tile 出现」，
        // 表现为动画播完闪一下。
        for (frame in frames) {
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    val state = frame[AnimationEngine.CellKey(row, col)]!!
                    val boardTile = result.board.grid[row][col]!!
                    assertEquals(
                        "($row,$col) 帧里的 tileId 必须与最终棋盘一致",
                        boardTile.id,
                        state.tileId,
                    )
                    assertEquals(
                        "($row,$col) 帧里的 type 必须与最终棋盘一致",
                        boardTile.type,
                        state.type,
                    )
                }
            }
        }
    }

    @Test
    fun `no origin means no movement frames`() {
        // 有解的棋盘不会被重排，origin 为空。
        val solvable = board4x4(
            listOf(
                SushiType.SUSHI1, SushiType.SUSHI1, SushiType.SUSHI2, SushiType.SUSHI3,
                SushiType.SUSHI2, SushiType.SUSHI1, SushiType.SUSHI3, SushiType.SUSHI4,
                SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI1, SushiType.SUSHI2,
                SushiType.SUSHI4, SushiType.SUSHI2, SushiType.SUSHI3, SushiType.SUSHI1,
            )
        )
        val result = DeadlockEngine.reshuffleIfDeadlocked(solvable, Random(7))

        val moving = ReshuffleAnimator.generateFrames(solvable, result.board, result.origin)[0]

        // origin 空 → 全部 Stable、零位移。UI 看到这样的帧不会播任何动画。
        for ((key, state) in moving) {
            assertEquals("$key 不该有位移", 0f, state.offsetX, 0.001f)
            assertEquals("$key 不该有位移", 0f, state.offsetY, 0.001f)
            assertEquals("$key 应是 Stable", AnimationEngine.TileAnim.Stable, state.anim)
        }
    }
}
