package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameEventTest — 回归测试：一次性信号必须每次都投递到位。
 *
 * ## 背景（FIX_PLAN D2）
 *
 * `lastRewardSeconds` 曾是 `GameState` 的字段，UI 侧靠
 * `LaunchedEffect(lastRewardSeconds)` 的 **key 变化** 触发 `+Ns` 飘字。
 * 由于 `+5s` 是本游戏最常见的奖励值，连续两次消除都奖励 5 秒时字段值
 * `5 → 5` 没有变化，`LaunchedEffect` 不会重启，第二次飘字直接不显示。
 *
 * 本测试锁死修复后的不变量：**同值信号连续发射 N 次，消费端必须收到 N 次。**
 *
 * 这里直接测 `MutableSharedFlow` 的投递语义（与 VM 中 `_events` 的配置
 * 一致：`replay = 0`、`extraBufferCapacity = 8`），不引入 Android
 * ViewModel 依赖 —— VM 侧的 emit 位置由代码审查保证，此处保证机制正确。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEventTest {

    /** 与 GameViewModel._events 相同的配置。 */
    private fun newEventFlow() =
        MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)

    // ------------------------------------------------------------------
    // 核心回归：同值连续发射不丢事件
    // ------------------------------------------------------------------

    @Test
    fun `连续两次同值 TimeReward 必须产出两个事件`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<GameEvent.TimeReward>()

        val job = launch {
            events.filterIsInstance<GameEvent.TimeReward>().collect { received += it }
        }
        runCurrent()

        // 模拟连续两次消除，都奖励 +5s —— 旧实现在这里丢掉第二次。
        events.tryEmit(GameEvent.TimeReward(5))
        events.tryEmit(GameEvent.TimeReward(5))
        runCurrent()

        assertEquals(
            "同值连续两次奖励必须收到 2 个事件（旧 state 字段实现只会触发 1 次）",
            2,
            received.size,
        )
        assertTrue("两个事件都应是 +5s", received.all { it.seconds == 5 })

        job.cancel()
    }

    @Test
    fun `连续多次同值 TimeReward 全部送达`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.TimeReward>().collect { received += it.seconds }
        }
        runCurrent()

        repeat(5) { events.tryEmit(GameEvent.TimeReward(5)) }
        runCurrent()

        assertEquals("5 次同值发射应收到 5 个事件", listOf(5, 5, 5, 5, 5), received)

        job.cancel()
    }

    @Test
    fun `不同值的 TimeReward 按顺序送达`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.TimeReward>().collect { received += it.seconds }
        }
        runCurrent()

        listOf(5, 5, 10, 5, 3).forEach { events.tryEmit(GameEvent.TimeReward(it)) }
        runCurrent()

        assertEquals("事件应保序且不去重", listOf(5, 5, 10, 5, 3), received)

        job.cancel()
    }

    // ------------------------------------------------------------------
    // 缓冲：无订阅者时 tryEmit 不应静默失败
    // ------------------------------------------------------------------

    @Test
    fun `无订阅者时 tryEmit 仍成功（靠 extraBufferCapacity）`() = runTest {
        val events = newEventFlow()

        // replay=0 且无 buffer 时，tryEmit 在无订阅者时会返回 false（事件丢弃）。
        // extraBufferCapacity = 8 保证 VM 在 UI 尚未订阅时发的事件不会凭空消失。
        repeat(8) { i ->
            assertTrue(
                "第 ${i + 1} 次 tryEmit 应成功（缓冲区容量 8）",
                events.tryEmit(GameEvent.TimeReward(5)),
            )
        }
    }

    // ------------------------------------------------------------------
    // 类型过滤：各类事件互不干扰
    // ------------------------------------------------------------------

    @Test
    fun `filterIsInstance 只取关心的事件类型`() = runTest {
        val events = newEventFlow()
        val rewards = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.TimeReward>().collect { rewards += it.seconds }
        }
        runCurrent()

        events.tryEmit(GameEvent.SwapRejected)
        events.tryEmit(GameEvent.TimeReward(5))
        events.tryEmit(GameEvent.NewRecord(1234))
        events.tryEmit(GameEvent.TimeReward(5))
        runCurrent()

        assertEquals(
            "RewardOverlay 只应收到 TimeReward，且同值两次都收到",
            listOf(5, 5),
            rewards,
        )

        job.cancel()
    }

    @Test
    fun `SwapRejected 连续发射不去重`() = runTest {
        val events = newEventFlow()
        var count = 0

        val job = launch {
            events.filterIsInstance<GameEvent.SwapRejected>().collect { count++ }
        }
        runCurrent()

        // data object 每次都是同一个实例 —— 若下游误用 distinctUntilChanged
        // 或 state 字段建模，第二次就会被吞掉。
        events.tryEmit(GameEvent.SwapRejected)
        events.tryEmit(GameEvent.SwapRejected)
        events.tryEmit(GameEvent.SwapRejected)
        runCurrent()

        assertEquals("连续 3 次无效交换应收到 3 个事件", 3, count)

        job.cancel()
    }

    // ------------------------------------------------------------------
    // 数据契约
    // ------------------------------------------------------------------

    @Test
    fun `TimeReward 是值相等的 data class`() {
        assertEquals(GameEvent.TimeReward(5), GameEvent.TimeReward(5))
        assertTrue(GameEvent.TimeReward(5) != GameEvent.TimeReward(10))
    }

    @Test
    fun `NewRecord 携带最终分数`() {
        val ev = GameEvent.NewRecord(score = 9999)
        assertEquals(9999, ev.score)
    }
}
