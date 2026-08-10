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

    /**
     * 字段数**不足**的行被跳过。
     *
     * ⚠️ 这条原本还断言「多一个字段的行也被跳过」（`300,3000,0,extra`
     * 期望被丢弃）。那个断言固化的正是 要修的缺陷 ——
     * 它使得给 GameRecord 加第 4 个字段必然清空所有老数据。
     *
     * 新契约是 append-only：多出的尾部字段忽略，整行照常读出。
     * 因此把「多字段」那行移到 `多出的尾部字段被忽略而非丢弃整行`，
     * 这里只保留「少字段」的用例。
     */
    @Test
    fun `字段数不足的行被跳过`() {
        val raw = "100,1000,1\n" +
            "200,2000\n" +           // 少一个字段 → 损坏，丢弃
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

    // ========================================================================
    // 跨版本兼容（字段演进契约）
    //
    // 上面所有容错用例都建立在「一行恰好 3 个字段」的前提上，所以它们
    // 拦不住格式演进事故：早期实现写的是 `parts.size != 3`，一旦加第 4 个
    // 字段，全部老数据都会被判非法并静默清空。下面两条锁死双向兼容。
    // ========================================================================

    /**
     * **老版本读新数据**（向后兼容）。
     *
     * 场景：玩家在新版本里玩过，历史里每行有 4 个字段；随后降级回老版本
     * （或老版本的代码路径读到了新数据）。多出来的尾部字段必须被忽略，
     * 而不是导致整行被丢弃。
     */
    @Test
    fun `多出的尾部字段被忽略而非丢弃整行`() {
        // 第 4 段 15 / 8 是未来版本追加的字段（比如 maxCombo），本版本不认识
        val raw = "1200,1717000000000,1,15\n800,1716900000000,0,8"
        val decoded = GameRecordCodec.decode(raw)

        assertEquals("两行都应被读出", 2, decoded.size)
        assertEquals(listOf(1200, 800), decoded.map { it.score })
        assertEquals(1_717_000_000_000L, decoded[0].timestampMillis)
        assertTrue("已知字段仍要正确解析", decoded[0].isNewRecord)
    }

    /**
     * **新版本读老数据**（向前兼容）。
     *
     * 这是升级路径，也是原实现真正会咬人的地方：老玩家存的每一行都只有
     * 3 段，若解析要求「恰好等于当前字段数」，升级后历史会一条不剩。
     */
    @Test
    fun `三字段老数据在新版本仍能完整读出`() {
        val raw = "1200,1717000000000,true\n800,1716900000000,false"
        val decoded = GameRecordCodec.decode(raw)

        assertEquals("老数据不能因为字段少而丢失", 2, decoded.size)
        assertEquals(listOf(1200, 800), decoded.map { it.score })
        assertTrue(decoded[0].isNewRecord)
        assertTrue(!decoded[1].isNewRecord)
    }

    /**
     * 兼容不能滑向「什么都收」：字段数**少于** v1 的三段仍是损坏数据，
     * 必须丢弃。这条守住 MIN_FIELDS 的下界，防止有人把判断放宽成
     * 「能解析出几个算几个」。
     */
    @Test
    fun `少于三字段仍视为损坏`() {
        val decoded = GameRecordCodec.decode("1200,1717000000000\n800\n500,1716900000000,1")
        assertEquals("只有完整的那行该被保留", 1, decoded.size)
        assertEquals(500, decoded[0].score)
    }
}
