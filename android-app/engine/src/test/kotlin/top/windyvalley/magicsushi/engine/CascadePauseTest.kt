package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画「真暂停」测试：挂起而非取消。
 *
 * ## 背景：两代修法
 *
 * ### 第一代（取消 + 立即落盘）
 *
 * 暂停时 `swapJob.cancel()`。协程从 `delay` 抛 CancellationException，
 * 此前终态没落盘 → 棋盘错乱（残帧 + 旧 board）。修法是在取消分支里
 * 补上落盘，代价是**动画被跳过**：用户点暂停看到的是棋盘瞬间结算完毕。
 *
 * ### 第二代（本测试锁定的语义）
 *
 * 暂停不再取消动画协程，而是让它**挂在原地**。`playCascadeAnimation`
 * 每次等待都拆成两步：
 *
 * 1. `delay(ms)` —— 正常的帧间隔
 * 2. `awaitResume()` —— 若处于暂停态则挂起，直到恢复才返回
 *
 * 协程始终存活，暂停期间不推进帧也不写 state；恢复后从**同一个位置**
 * 继续播放。终态仍在动画自然结束时落盘，不需要「取消时补写」这种补丁。
 *
 * ## 为什么不能用 delay 轮询实现
 *
 * 轮询（`while (paused) delay(16)`）在虚拟时钟下会让 `advanceTimeBy`
 * 无限推进，而且真机上白耗电。`awaitResume` 由调用方用
 * `suspendCancellableCoroutine` 或 flow 的 `first { !paused }` 实现，
 * 零轮询。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CascadePauseTest {

    private val phaseMs = 100L
    private val gapMs = 100L

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
    fun `暂停期间不再推进帧，恢复后从原处继续`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        var paused = false
        val frameLog = mutableListOf<Int>()
        var completed = false

        val job = launch {
            playCascadeAnimation(
                startBoard = board,
                cascades = cascadeResult.cascades,
                phaseMs = phaseMs,
                gapMs = gapMs,
                // 挂起点：暂停时不返回，直到 paused 变回 false。
                awaitResume = {
                    while (paused) {
                        // 用极短 delay 让测试调度器有机会跑其他协程；
                        // 生产实现用 flow.first { !paused }，零轮询。
                        delayTiny()
                    }
                },
                onFrame = { _, _ -> frameLog.add(frameLog.size) },
            )
            completed = true
        }

        // 播到第一帧
        advanceTimeBy(50)
        runCurrent()
        val framesBeforePause = frameLog.size
        assertTrue("暂停前应至少播了一帧", framesBeforePause >= 1)

        // 暂停
        paused = true
        advanceTimeBy(1000)   // 推进一大段虚拟时间
        runCurrent()

        assertEquals(
            "暂停期间不应再推进任何新帧",
            framesBeforePause,
            frameLog.size,
        )
        assertTrue("暂停期间动画不应完成", !completed)

        // 恢复
        paused = false
        advanceTimeBy(2000)
        runCurrent()

        assertTrue("恢复后动画应能跑完", completed)
        assertTrue(
            "恢复后应继续播放剩余帧（总帧数 = 轮数 × 3）",
            frameLog.size == cascadeResult.cascades.size * 3,
        )
        job.cancel()
    }

    @Test
    fun `不传 awaitResume 时行为与之前完全一致`() = runTest {
        // 向后兼容：默认参数是空实现，老调用方（和老测试）不受影响。
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        val timeline = mutableListOf<Long>()
        val scope = this
        playCascadeAnimation(
            startBoard = board,
            cascades = cascadeResult.cascades,
            phaseMs = phaseMs,
            gapMs = gapMs,
            onFrame = { _, _ -> timeline.add(scope.currentTimeMs()) },
        )

        // 单轮时序仍是 0 / 200 / 400（与 CascadeAnimatorTest 的断言一致）。
        assertTrue("应播满所有帧", timeline.size == cascadeResult.cascades.size * 3)
        assertEquals("首帧仍在 0ms", 0L, timeline[0])
    }

    @Test
    fun `暂停发生在轮次之间也能正确挂起`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        // 只对多轮 cascade 有意义；单轮时该断言退化为「不崩」。
        var paused = false
        val frameLog = mutableListOf<Int>()

        val job = launch {
            playCascadeAnimation(
                startBoard = board,
                cascades = cascadeResult.cascades,
                phaseMs = phaseMs,
                gapMs = gapMs,
                awaitResume = { while (paused) delayTiny() },
                onFrame = { _, _ -> frameLog.add(frameLog.size) },
            )
        }

        // 播完第一轮（3 帧 = 0/200/400，第一轮结束在 500ms）
        advanceTimeBy(500)
        runCurrent()
        paused = true
        val atPause = frameLog.size
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("轮次间暂停也不应推进帧", atPause, frameLog.size)

        paused = false
        advanceTimeBy(3000)
        runCurrent()
        assertTrue("恢复后应播完", frameLog.size == cascadeResult.cascades.size * 3)
        job.cancel()
    }
}

/** 测试内部用的极短挂起，让调度器有机会切换协程。 */
private suspend fun delayTiny() = kotlinx.coroutines.delay(1)

/** runTest 作用域里读虚拟时钟。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.currentTimeMs(): Long =
    this.testScheduler.currentTime
