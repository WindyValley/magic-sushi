package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停挂起与取消的交互测试。
 *
 * ## 为什么单独一个文件
 *
 * 「动画挂在 awaitResume 上」引入了一个新状态：协程活着但停着。
 * 这个状态下如果发生 cancel（restart / VM 销毁），必须保证：
 *
 * 1. 挂起的协程能**真的被取消**（不是永久泄漏）
 * 2. `finally` 块照常执行（否则 swapProcessing 永远为 true → 棋盘冻结）
 * 3. 不会死锁
 *
 * 第 2 条尤其要紧：VM 用 `swapProcessing` 拦重入，它由 `finally` 复位。
 * 若挂起状态下取消导致 finally 不执行，玩家会遇到「棋盘永久不接受输入」——
 * 比原来的 bug 更严重。
 *
 * 这里用 StateFlow + first 复刻 VM 的真实实现，而不是测试专用的轮询版。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CascadePauseCancelTest {

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
    fun `挂在暂停上的协程可以被取消且 finally 会执行`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        // 复刻 VM：用 StateFlow 承载暂停态，awaitResume 用 first 挂起。
        val paused = MutableStateFlow(false)
        var finallyRan = false
        var frames = 0

        val job = launch {
            try {
                playCascadeAnimation(
                    startBoard = board,
                    cascades = cascadeResult.cascades,
                    phaseMs = phaseMs,
                    gapMs = gapMs,
                    awaitResume = {
                        if (paused.value) paused.first { !it }
                    },
                    onFrame = { _, _ -> frames++ },
                )
            } finally {
                // VM 里这里是 swapProcessing = false
                finallyRan = true
            }
        }

        advanceTimeBy(150)
        runCurrent()
        val framesAtPause = frames

        // 进入暂停：协程挂在 paused.first { !it } 上
        paused.value = true
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("暂停期间不推进帧", framesAtPause, frames)
        assertFalse("暂停期间 finally 不应执行", finallyRan)
        assertTrue("协程应仍然存活（挂起而非结束）", job.isActive)

        // 在**挂起状态下**取消（模拟暂停时点重新开始 / VM 销毁）
        job.cancel()
        advanceUntilIdle()

        assertTrue("挂起中的协程必须能被取消", job.isCancelled)
        assertTrue(
            "finally 必须执行 —— 否则 VM 的 swapProcessing 永远为 true，棋盘冻结",
            finallyRan,
        )
        assertEquals("取消后不应再推进帧", framesAtPause, frames)
    }

    @Test
    fun `暂停中恢复再暂停多次不会丢帧或死锁`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)
        val expectedTotal = cascadeResult.cascades.size * 3

        val paused = MutableStateFlow(false)
        var frames = 0
        var completed = false

        val job = launch {
            playCascadeAnimation(
                startBoard = board,
                cascades = cascadeResult.cascades,
                phaseMs = phaseMs,
                gapMs = gapMs,
                awaitResume = {
                    if (paused.value) paused.first { !it }
                },
                onFrame = { _, _ -> frames++ },
            )
            completed = true
        }

        // 连续 4 轮 暂停 → 恢复
        repeat(4) {
            advanceTimeBy(80)
            runCurrent()
            paused.value = true
            advanceTimeBy(500)
            runCurrent()
            paused.value = false
            runCurrent()
        }

        advanceUntilIdle()

        assertTrue("反复暂停恢复后动画仍应跑完", completed)
        assertEquals("总帧数不应因暂停而增减", expectedTotal, frames)
        job.cancel()
    }

    @Test
    fun `恢复后剩余帧数正确且无重复帧`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        val paused = MutableStateFlow(false)
        // 记录每帧的 phase 类型，用来确认恢复后不会重播已播过的帧。
        val framePhases = mutableListOf<String>()

        val job = launch {
            playCascadeAnimation(
                startBoard = board,
                cascades = cascadeResult.cascades,
                phaseMs = phaseMs,
                gapMs = gapMs,
                awaitResume = {
                    if (paused.value) paused.first { !it }
                },
                onFrame = { _, frame -> framePhases.add(frame::class.simpleName ?: "?") },
            )
        }

        // 播第一帧后暂停
        advanceTimeBy(50)
        runCurrent()
        assertEquals("此时应只播了 1 帧", 1, framePhases.size)
        val firstFrameName = framePhases[0]

        paused.value = true
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("暂停期间帧数不变", 1, framePhases.size)

        paused.value = false
        advanceUntilIdle()

        // 恢复后应继续播剩下的帧，且第一帧不会被重播
        assertEquals(
            "恢复后应播满全部帧",
            cascadeResult.cascades.size * 3,
            framePhases.size,
        )
        assertEquals("首帧仍是原来那个（未被重播覆盖）", firstFrameName, framePhases[0])
        job.cancel()
    }
}
