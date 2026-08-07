package top.windyvalley.magicsushi.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史记录的展示格式化。
 *
 * ## 为什么放在 engine module
 *
 * 这是纯函数：`(时间戳, 时区) -> 字符串`，没有任何 Android 类型。
 * `java.time` 是 JVM 标准库，engine 的 classpath 上就有；Android 侧
 * minSdk 26 也原生支持（无需 desugaring）。
 *
 * 放这里的收益是**可单测**。日期格式化是典型的「看起来不会错、真错了
 * 又很难发现」的代码 —— 跨月、跨年、午夜边界都靠肉眼验成本很高。
 *
 * ## 为什么时区要作为参数注入
 *
 * `ZoneId.systemDefault()` 让测试依赖 CI/开发机的时区设置：同一份断言
 * 在 UTC+8 绿、在 UTC 红。这正是本项目在随机数上踩过的同一类坑
 * （不可推测的隐式输入）。所以时区显式入参，默认值给系统时区供生产
 * 调用，测试注入固定时区。
 */
object HistoryFormatter {

    /**
     * 展示用的日期时间格式：`MM-dd HH:mm`。
     *
     * 刻意不显示年份和秒：历史列表里每行都要挤进排名、分数、时间和
     * 新纪录标记，年份对「上周打的这局」没有信息量，秒更没有。
     */
    private val PATTERN: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /**
     * 把 epoch millis 格式化为 `MM-dd HH:mm`。
     *
     * @param timestampMillis epoch 毫秒
     * @param zone            用于换算本地时间的时区。测试请显式传入固定值。
     */
    fun formatTimestamp(
        timestampMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        PATTERN.format(Instant.ofEpochMilli(timestampMillis).atZone(zone))

    /**
     * 排名的展示文本。前三名用奖牌，其余用序号。
     *
     * @param index 0-based 下标（列表已按分数降序排好）
     */
    fun rankLabel(index: Int): String = when (index) {
        0 -> "🥇"
        1 -> "🥈"
        2 -> "🥉"
        else -> "${index + 1}"
    }
}
