package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [HistoryFormatter] 测试。
 *
 * ## 所有用例都显式指定时区
 *
 * 时间格式化的测试如果依赖 `ZoneId.systemDefault()`，就变成了「在我机器上
 * 是绿的」——换台 CI 或改个系统设置就红。这里统一注入固定时区，让断言
 * 与运行环境解耦。
 */
class HistoryFormatterTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 用可读的本地时间构造时间戳，避免测试里出现看不懂的魔数。 */
    private fun millisAt(
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
        zone: ZoneId = shanghai,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        .toInstant().toEpochMilli()

    @Test
    fun `格式为 MM-dd HH mm`() {
        val t = millisAt(2026, 8, 7, 14, 35)
        assertEquals("08-07 14:35", HistoryFormatter.formatTimestamp(t, shanghai))
    }

    @Test
    fun `月和日不足两位时补零`() {
        val t = millisAt(2026, 1, 2, 3, 4)
        assertEquals("01-02 03:04", HistoryFormatter.formatTimestamp(t, shanghai))
    }

    @Test
    fun `同一时刻在不同时区显示不同本地时间`() {
        // 上海 2026-08-07 00:30 == UTC 2026-08-06 16:30（差 8 小时，跨了日期）
        val t = millisAt(2026, 8, 7, 0, 30, shanghai)
        assertEquals("08-07 00:30", HistoryFormatter.formatTimestamp(t, shanghai))
        assertEquals("08-06 16:30", HistoryFormatter.formatTimestamp(t, utc))
    }

    @Test
    fun `午夜显示为 00 时而非 24 时`() {
        val t = millisAt(2026, 8, 7, 0, 0)
        assertEquals("08-07 00:00", HistoryFormatter.formatTimestamp(t, shanghai))
    }

    @Test
    fun `跨年边界不串到上一年的月份`() {
        // 12-31 23:59 与 01-01 00:00 只差一分钟，格式化后必须分属两个日期
        val last = millisAt(2026, 12, 31, 23, 59)
        val first = millisAt(2027, 1, 1, 0, 0)
        assertEquals("12-31 23:59", HistoryFormatter.formatTimestamp(last, shanghai))
        assertEquals("01-01 00:00", HistoryFormatter.formatTimestamp(first, shanghai))
    }

    @Test
    fun `epoch 零点在 UTC 下是 1970-01-01`() {
        assertEquals("01-01 00:00", HistoryFormatter.formatTimestamp(0L, utc))
    }

    @Test
    fun `前三名用奖牌`() {
        assertEquals("🥇", HistoryFormatter.rankLabel(0))
        assertEquals("🥈", HistoryFormatter.rankLabel(1))
        assertEquals("🥉", HistoryFormatter.rankLabel(2))
    }

    @Test
    fun `第四名起用序号且为 1-based`() {
        assertEquals("4", HistoryFormatter.rankLabel(3))
        assertEquals("50", HistoryFormatter.rankLabel(49))
    }
}
