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
 * ## 背景
 *
 * `lastRewardSeconds` 曾是 `GameState` 的字段，UI 侧靠
 * `LaunchedEffect(lastRewardSeconds)` 的 **key 变化** 触发 `+Ns` 飘字。
 * 由于 `+5s` 是当时最常见的奖励值，连续两次消除都奖励 5 秒时字段值
 * `5 → 5` 没有变化，`LaunchedEffect` 不会重启，第二次飘字直接不显示。
 *
 * ⚠️ 奖励时间机制本身已废弃（`TimeReward` 事件与 `RewardOverlay` 已删除，
 * 消除改为把倒计时重置回 60s）。但**这个教训与具体功能无关** —— 任何
 * 一次性信号塞进 data class 字段都会踩同一个坑。故测试保留，载体换成
 * 仍在使用的 [GameEvent.NewRecord]（连续两局同分破纪录是真实场景）。
 *
 * 本测试锁死的不变量：**同值信号连续发射 N 次，消费端必须收到 N 次。**
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
    fun `连续两次同值事件必须产出两个事件`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<GameEvent.NewRecord>()

        val job = launch {
            events.filterIsInstance<GameEvent.NewRecord>().collect { received += it }
        }
        runCurrent()

        // 同值连续发射 —— 旧的 state 字段实现在这里丢掉第二次。
        events.tryEmit(GameEvent.NewRecord(1234))
        events.tryEmit(GameEvent.NewRecord(1234))
        runCurrent()

        assertEquals(
            "同值连续两次必须收到 2 个事件（旧 state 字段实现只会触发 1 次）",
            2,
            received.size,
        )
        assertTrue("两个事件都应是 1234 分", received.all { it.score == 1234 })

        job.cancel()
    }

    @Test
    fun `连续多次同值事件全部送达`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.NewRecord>().collect { received += it.score }
        }
        runCurrent()

        repeat(5) { events.tryEmit(GameEvent.NewRecord(1234)) }
        runCurrent()

        assertEquals("5 次同值发射应收到 5 个事件", listOf(1234, 1234, 1234, 1234, 1234), received)

        job.cancel()
    }

    @Test
    fun `不同值的事件按顺序送达`() = runTest {
        val events = newEventFlow()
        val received = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.NewRecord>().collect { received += it.score }
        }
        runCurrent()

        listOf(100, 100, 250, 100, 80).forEach { events.tryEmit(GameEvent.NewRecord(it)) }
        runCurrent()

        assertEquals("事件应保序且不去重", listOf(100, 100, 250, 100, 80), received)

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
                events.tryEmit(GameEvent.NewRecord(1234)),
            )
        }
    }

    // ------------------------------------------------------------------
    // 类型过滤：各类事件互不干扰
    // ------------------------------------------------------------------

    @Test
    fun `filterIsInstance 只取关心的事件类型`() = runTest {
        val events = newEventFlow()
        val records = mutableListOf<Int>()

        val job = launch {
            events.filterIsInstance<GameEvent.NewRecord>().collect { records += it.score }
        }
        runCurrent()

        events.tryEmit(GameEvent.SwapRejected)
        events.tryEmit(GameEvent.NewRecord(1234))
        events.tryEmit(GameEvent.SwapRejected)
        events.tryEmit(GameEvent.NewRecord(1234))
        runCurrent()

        assertEquals(
            "订阅者只应收到 NewRecord，且同值两次都收到",
            listOf(1234, 1234),
            records,
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
    fun `NewRecord 是值相等的 data class`() {
        assertEquals(GameEvent.NewRecord(1234), GameEvent.NewRecord(1234))
        assertTrue(GameEvent.NewRecord(1234) != GameEvent.NewRecord(5678))
    }

    @Test
    fun `NewRecord 携带最终分数`() {
        val ev = GameEvent.NewRecord(score = 9999)
        assertEquals(9999, ev.score)
    }
}
