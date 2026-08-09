package top.windyvalley.magicsushi.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * TileIdGenerator — 全局唯一 tile id 生成器。
 *
 * **Pure Kotlin, ZERO Android dependencies.**
 *
 * ---
 * ## 为什么需要它
 *
 * `SushiTile.id` 的唯一职责是充当 Compose `key()`（见 `GameCanvas`），
 * 因此必须保证**同一块棋盘内绝对不重复**。
 *
 * 历史实现（v1.0.6 之前）在 [BoardEngine.generateInitialBoard] 和
 * [BoardEngine.spawnRefill] 两处都用 `row * BOARD_SIZE + col` 推导 id。
 * 由于 `spawnRefill` 只看「当前空位的坐标」，它不知道棋盘上已有哪些 id，
 * 于是在重力让老 tile 换位之后会发出撞号的 id：
 *
 * ```
 * 初始：       (0,0) id=0        (3,0) id=21
 * 消除 (3,0)： (0,0) id=0        (3,0) null
 * 重力后：     (0,0) null        (3,0) id=0     ← id=0 掉到了 row 3
 * spawnRefill：(0,0) id=0  ←←←   (3,0) id=0     ← 同一棋盘出现两个 id=0
 * ```
 *
 * 同级 `key()` 重复会让 Compose 复用错误的 slot —— `SushiTile` 内部
 * `remember { dragOffset }` 与 `animateFloatAsState` 的状态会串到另一个
 * 格子上，表现为「没有参与消除的 tile 莫名跳动 / 选中高亮错位」。
 * （若日后改用 `LazyVerticalGrid`，重复 key 会直接抛异常。）
 *
 * ## 设计
 *
 * - **单调递增**：`incrementAndGet()` 从 1 开始，保证跨轮次、跨 cascade、
 *   跨整局都不重复。
 * - **线程安全**：`AtomicInteger`。当前引擎在主线程单线程运行，成本可忽略，
 *   但避免了未来把棋盘生成挪到后台线程时的隐患。
 * - **正数区间**：本生成器只产出 **正数**（>= 1）。
 *   动画帧里的 spawn tile 直接复用 `spawnRefill` 产出的真实 `tile.id`，
 *   不再有独立的负数编号空间 —— 整个 App 只有这一个身份来源。
 *   `0` 不被使用，正数即合法身份。
 *
 * ## 溢出
 *
 * `Int` 上限约 21.4 亿。按最激进的估算（每秒消除并补充 49 格）需要连续
 * 游玩 ~1.4 年才会溢出，且溢出后只是回绕到负数区间，不会崩溃 —— 对本项目
 * 而言无需处理。
 */
object TileIdGenerator {

    private val counter = AtomicInteger(0)

    /**
     * 取下一个全局唯一 id。首次调用返回 `1`。
     *
     * @return 单调递增的正整数，保证在进程生命周期内不重复。
     */
    fun next(): Int = counter.incrementAndGet()

    /**
     * 把计数器推进到**严格大于** [minExclusive] 的位置。
     *
     * ## 为什么需要它
     *
     * 计数器活在进程内存里，进程重启即归零。而对局快照（断点续玩）会把
     * 带 id 的 tile 从磁盘恢复回来 —— 若不同步计数器，`next()` 会从 1
     * 重新发号，与棋盘上存活的 tile 撞号。
     *
     * 撞号的后果不是崩溃而是**视觉错乱**：Compose 用 tile id 当 `key`，
     * 两个同 id 的 tile 会被认成同一个，动画在它们之间乱窜。这正是
     * [resetForTest] 的注释里警告过的那个 bug —— 进程重启是一次隐式
     * reset，只是没人调用它。
     *
     * ## 幂等且单调
     *
     * 只在当前值更小时才推进（`getAndUpdate` + `maxOf`），所以：
     * - 重复调用无副作用
     * - 永远不会把计数器往回拨（那才会真的制造撞号）
     *
     * @param minExclusive 恢复的棋盘上最大的 tile id。传 0 或负数是 no-op。
     */
    fun seedAtLeast(minExclusive: Int) {
        counter.getAndUpdate { current -> maxOf(current, minExclusive) }
    }

    /**
     * 仅供单元测试重置计数器，保证用例之间互不影响。
     *
     * **不要在产品代码中调用** —— 重置后可能与棋盘上存活的 tile 撞号，
     * 正是本类要消灭的那个 bug。
     */
    fun resetForTest() {
        counter.set(0)
    }
}
