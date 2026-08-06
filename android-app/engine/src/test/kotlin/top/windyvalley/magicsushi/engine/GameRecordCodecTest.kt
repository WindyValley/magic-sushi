package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameRecordCodec] 编解码测试。
 *
 * 重点在**容错**：存储里的字符串可能被外部改坏、可能是旧格式、
 * 可能被截断。解析失败必须是「丢掉那一行」而不是「抛异常崩溃」——
 * 历史记录不是关键数据，为它崩溃是本末倒置。
 */
class GameRecordCodecTest {

    private fun rec(score: Int, ts: Long, newRecord: Boolean = false) =
        GameRecord(score = score, timestampMillis = ts, isNewRecord = newRecord)

    // ========================================================================
    // 往返一致性
    // ========================================================================

    @Test
    fun `单条记录往返一致`() {
        val original = listOf(rec(1200, 1_717_000_000_000L, newRecord = true))
        val decoded = GameRecordCodec.decode(GameRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `多条记录往返一致且顺序保持`() {
        val original = listOf(
            rec(1200, 1_717_000_000_000L, newRecord = true),
            rec(800, 1_716_900_000_000L),
            rec(0, 0L),
        )
        val decoded = GameRecordCodec.decode(GameRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `满库 50 条往返一致`() {
        val original = (1..GameHistory.MAX_RECORDS).map {
            rec(score = it * 37, ts = it.toLong() * 1000, newRecord = it % 7 == 0)
        }
        val decoded = GameRecordCodec.decode(GameRecordCodec.encode(original))
        assertEquals(original.size, decoded.size)
        assertEquals(original, decoded)
    }

    @Test
    fun `空列表编码为空串，空串解码为空列表`() {
        assertEquals("", GameRecordCodec.encode(emptyList()))
        assertTrue(GameRecordCodec.decode("").isEmpty())
    }

    // ========================================================================
    // 容错：坏数据丢弃而非崩溃
    // ========================================================================

    @Test
    fun `null 输入返回空列表`() {
        assertTrue(GameRecordCodec.decode(null).isEmpty())
    }

    @Test
    fun `纯空白输入返回空列表`() {
        assertTrue(GameRecordCodec.decode("   \n  \n ").isEmpty())
    }

    @Test
    fun `字段数不对的行被跳过`() {
        val raw = "100,1000,1\n" +
            "200,2000\n" +           // 少一个字段
            "300,3000,0,extra\n" +   // 多一个字段
            "400,4000,1"
        val decoded = GameRecordCodec.decode(raw)
        assertEquals("只应保留两条合法记录", 2, decoded.size)
        assertEquals(listOf(100, 400), decoded.map { it.score })
    }

    @Test
    fun `分数或时间戳不是数字的行被跳过`() {
        val raw = "abc,1000,1\n" +
            "100,xyz,0\n" +
            "200,2000,1"
        val decoded = GameRecordCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(200, decoded[0].score)
    }

    @Test
    fun `isNewRecord 字段非法的行被跳过`() {
        val raw = "100,1000,maybe\n200,2000,1"
        val decoded = GameRecordCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(200, decoded[0].score)
    }

    @Test
    fun `isNewRecord 兼容 true false 字面量`() {
        // 防御性：万一以后改了写法，读旧数据也别炸。
        val decoded = GameRecordCodec.decode("100,1000,true\n200,2000,false")
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].isNewRecord)
        assertTrue(!decoded[1].isNewRecord)
    }

    @Test
    fun `负分或负时间戳的行被跳过`() {
        val raw = "-100,1000,0\n100,-1000,0\n200,2000,0"
        val decoded = GameRecordCodec.decode(raw)
        assertEquals("负值视为数据损坏", 1, decoded.size)
        assertEquals(200, decoded[0].score)
    }

    @Test
    fun `被截断的尾行不影响前面的记录`() {
        // 模拟写盘中断
        val raw = "100,1000,1\n200,2000,0\n300,30"
        val decoded = GameRecordCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(listOf(100, 200), decoded.map { it.score })
    }

    @Test
    fun `完全无法识别的内容返回空列表而不抛异常`() {
        val decoded = GameRecordCodec.decode("这不是历史记录 {\"json\": true}")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `行首行尾空白被容忍`() {
        val decoded = GameRecordCodec.decode("  100 , 1000 , 1  \n 200,2000,0 ")
        assertEquals(2, decoded.size)
        assertEquals(100, decoded[0].score)
        assertTrue(decoded[0].isNewRecord)
    }
}
