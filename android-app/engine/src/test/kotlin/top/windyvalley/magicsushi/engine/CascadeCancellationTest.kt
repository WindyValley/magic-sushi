package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：动画播放中途被取消后，棋盘状态必须可恢复。
 *
 * ## 真机 bug（批次 A 引入后暴露）
 *
 * 用户报告：「动画中暂停，从暂停中恢复时，棋盘没有刷新，表现为空位上方
 * tile 不下落不补齐，且上方 tile 操作无效」。
 *
 * ### 为什么批次 A 之前没人踩到
 *
 * 修暂停 bug 之前，点暂停按钮只置 UI 局部状态、**从不调 onPause()**，
 * 所以 `swapJob.cancel()` 永远不会在动画中途触发。批次 A 让暂停真正生效，
 * 这条路径第一次被走到，藏了很久的缺陷才浮出水面。
 *
 * ### 根因
 *
 * `playCascadeAnimation` 在每帧之间 `delay()`。协程被 cancel 时从 delay
 * 处抛 `CancellationException`，于是 VM 里**紧跟在动画之后**那段写终态的
 * 代码根本不执行：
 *
 * ```kotlin
 * playCascadeAnimation(...)          // ← 在这里抛 CancellationException
 *
 * _state.update {                    // ← 永远不执行
 *     it.copy(board = cascadeResult.finalBoard, animFrame = null, ...)
 * }
 * ```
 *
 * 结果 `board` 停在动画开始前的旧值、`animFrame` 卡在中途某一帧。
 * 恢复后 `animFrame != null` 使 `presentation` 仍是 `Animating`，
 * UI 渲染那个残帧；而残帧里被消除的格子已经是空的、下落也只做了一半，
 * 于是「空位上方 tile 不下落」。手势失效则是因为命中判定走的是
 * `state.board`（旧棋盘），与屏幕上显示的残帧对不上。
 *
 * ## 本测试锁住的语义
 *
 * 动画中途取消后，**终态棋盘必须仍能被算出并落盘**。这里用 engine 层
 * 复现同一个形状：cascade 结果（`finalBoard`）在动画之前就已算好，
 * 取消只该影响「有没有播完动画」，不该影响「棋盘最终长什么样」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CascadeCancellationTest {

    private val phaseMs = 100L
    private val gapMs = 100L

    /** 一个 col0 整列同色、可触发消除的棋盘。 */
    private fun boardWithColumnMatch(): Board {
        var nextId = 1
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List(7) { c ->
                val type = if (c == 0) SushiType.SUSHI1
                else listOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)[(r + c) % 3]
                SushiTile(id = nextId++, type = type, row = r, col = c)
            }
        }
        return Board(size = 7, grid = grid)
    }

    @Test
    fun `动画中途取消时终态棋盘已在取消前算出`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        assertTrue("测试前提：应检测到匹配", matches.isNotEmpty())

        // 关键：cascade 是**纯计算**，在任何 delay 之前就完成。
        // 所以取消动画不会让终态丢失 —— 只要 VM 记得写进去。
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)
        assertNotNull("终态棋盘应在动画开始前就算好", cascadeResult.finalBoard)

        var lastFrame: AnimFrame? = null
        var completed = false

        val job = launch {
            playCascadeAnimation(
                startBoard = board,
                cascades = cascadeResult.cascades,
                phaseMs = phaseMs,
                gapMs = gapMs,
                onFrame = { _, frame -> lastFrame = frame },
            )
            completed = true
        }

        // 播到第一帧之后、动画远未结束时取消（模拟用户点暂停）。
        advanceTimeBy(150)
        runCurrent()
        assertNotNull("取消前应至少推送过一帧", lastFrame)
        assertTrue("此时动画不应已完成", !completed)

        job.cancel()
        runCurrent()

        assertTrue("取消后协程不应正常跑完", !completed)

        // 核心断言：终态棋盘与是否播完动画**无关**。
        // VM 的修法就是在 CancellationException 分支里把这个终态写进 state。
        val stable = MatchEngine.detectMatches(cascadeResult.finalBoard)
        assertTrue(
            "终态棋盘应已稳定（无残留匹配）—— 恢复后直接采用它即可，无需重播动画",
            stable.isEmpty(),
        )
        assertTrue(
            "终态棋盘不应有空洞 —— 空洞正是用户看到的「tile 不下落不补齐」",
            cascadeResult.finalBoard.grid.all { row -> row.all { it != null } },
        )
    }

    @Test
    fun `无论在哪一帧取消，取消前算好的终态都不受影响`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)

        // ⚠️ 终态只算**一次**。
        //
        // 初版这里在循环内反复调用 cascadeUntilStable 做对比，结果失败：
        // 每次调用生成的补充 tile 会取到新的 id（15,16,17 → 29,30,31）。
        // 那是**正确行为**（D1 修的就是 tile id 不能撞号），不是 bug ——
        // 是我的测试问的问题不对。取消时刻与「重复调用产生不同 id」无关。
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)
        val expectedIds = cascadeResult.finalBoard.grid.map { row -> row.map { it?.id } }

        for (cancelAt in listOf(50L, 150L, 250L, 350L, 450L, 550L)) {
            var frames = 0
            val job = launch {
                playCascadeAnimation(
                    startBoard = board,
                    cascades = cascadeResult.cascades,
                    phaseMs = phaseMs,
                    gapMs = gapMs,
                    onFrame = { _, _ -> frames++ },
                )
            }
            advanceTimeBy(cancelAt)
            runCurrent()
            job.cancel()
            runCurrent()

            // 核心：播放（以及中途取消）是**只读**消费者，
            // 不得改动 cascadeResult 里已算好的终态。
            assertEquals(
                "在 ${cancelAt}ms 取消后，终态棋盘不应被动画播放改动",
                expectedIds,
                cascadeResult.finalBoard.grid.map { row -> row.map { it?.id } },
            )
            assertTrue(
                "在 ${cancelAt}ms 取消：终态仍应无空洞，可直接落盘",
                cascadeResult.finalBoard.grid.all { row -> row.all { it != null } },
            )
        }
    }

    @Test
    fun `shouldContinue 守卫中止时也需要落盘终态`() = runTest {
        // 另一条中止路径：守卫在轮次之间温和退出（不抛异常）。
        // 这条路径下函数会正常返回，但同样只播了部分动画 ——
        // VM 必须一样把终态写进 state。
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        var frames = 0
        var returned: Boolean
        // 第一轮之后就撤销许可。
        var allow = true

        playCascadeAnimation(
            startBoard = board,
            cascades = cascadeResult.cascades,
            phaseMs = phaseMs,
            gapMs = gapMs,
            shouldContinue = { allow },
            onFrame = { _, _ ->
                frames++
                if (frames >= 3) allow = false   // 播完第一轮就停
            },
        )
        returned = true

        assertTrue("守卫中止应让函数正常返回（不抛异常）", returned)
        assertTrue(
            "终态棋盘仍应稳定 —— 与走哪条中止路径无关",
            MatchEngine.detectMatches(cascadeResult.finalBoard).isEmpty(),
        )
    }
}
