package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameSnapshotCodec] 与 [GameSnapshot] 的测试。
 *
 * 重点覆盖三类真实风险：
 * 1. round-trip 保真 —— 恢复出的棋盘必须与存入时逐格一致（tile id 尤其
 *    重要，它是 Compose 的 key）
 * 2. append-only 兼容 —— 未来加字段不能让老快照解析失败
 * 3. 脏数据一律丢弃 —— 绝不返回半个棋盘
 */
class GameSnapshotCodecTest {

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun tile(id: Int, type: SushiType, row: Int, col: Int) =
        SushiTile(id = id, type = type, row = row, col = col)

    /** 3x3 棋盘，(1,1) 是空格。id 从 101 起，便于与 ordinal 区分。 */
    private fun sampleBoard(): Board {
        val types = SushiType.entries
        var nextId = 101
        val grid = List(3) { row ->
            List(3) { col ->
                if (row == 1 && col == 1) null
                else tile(nextId++, types[(row * 3 + col) % types.size], row, col)
            }
        }
        return Board(size = 3, grid = grid)
    }

    private fun sampleSnapshot() = GameSnapshot(
        board = sampleBoard(),
        score = 1234,
        combo = 3,
        remainingSeconds = 42,
    )

    // ------------------------------------------------------------------
    // round-trip
    // ------------------------------------------------------------------

    @Test
    fun `round-trip 后所有标量字段保持不变`() {
        val original = sampleSnapshot()
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(original))
        assertNotNull("合法快照必须能解出来", decoded)
        assertEquals(1234, decoded!!.score)
        assertEquals(3, decoded.combo)
        assertEquals(42, decoded.remainingSeconds)
        assertEquals(3, decoded.board.size)
    }

    /**
     * tile id 必须逐格保真。
     *
     * 这是本 codec 最关键的性质：id 是 Compose 的 `key`。若恢复后 id 变了，
     * Compose 会认为整盘 tile 都是新的，导致全盘重建 / 动画错乱。
     */
    @Test
    fun `round-trip 后每一格的 id 与 type 都逐格一致`() {
        val original = sampleSnapshot()
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(original))!!

        for (row in 0 until original.board.size) {
            for (col in 0 until original.board.size) {
                val before = original.board.grid[row][col]
                val after = decoded.board.grid[row][col]
                if (before == null) {
                    assertNull("($row,$col) 原本是空格，恢复后也必须是空", after)
                } else {
                    assertNotNull("($row,$col) 原本有 tile，恢复后不能是空", after)
                    assertEquals("($row,$col) 的 id 必须保真", before.id, after!!.id)
                    assertEquals("($row,$col) 的 type 必须保真", before.type, after.type)
                    assertEquals("($row,$col) 的 row 必须与位置一致", row, after.row)
                    assertEquals("($row,$col) 的 col 必须与位置一致", col, after.col)
                }
            }
        }
    }

    @Test
    fun `7x7 满盘 round-trip`() {
        var nextId = 1
        val types = SushiType.entries
        val grid = List(7) { row ->
            List(7) { col -> tile(nextId++, types[(row + col) % types.size], row, col) }
        }
        val snapshot = GameSnapshot(
            board = Board(size = 7, grid = grid),
            score = 9999,
            combo = 0,
            remainingSeconds = 1,
        )
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot))!!
        assertEquals(49, decoded.board.grid.flatten().count { it != null })
        assertEquals(snapshot.board.grid, decoded.board.grid)
    }

    /**
     * 瞬时锁刻意不进快照 —— 恢复出的棋盘必须是解锁状态。
     *
     * 若把 cascadeLock=true 存下来再恢复，玩家会得到一个**永久无法操作**
     * 的棋盘（锁本该由动画结束时释放，但那个动画已经随进程消失了）。
     */
    @Test
    fun `瞬时锁不进快照，恢复后一律解锁`() {
        val locked = sampleBoard().copy(swapLock = true, cascadeLock = true)
        val snapshot = GameSnapshot(locked, score = 0, combo = 0, remainingSeconds = 30)
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot))!!
        assertFalse("swapLock 不能被恢复成 true", decoded.board.swapLock)
        assertFalse("cascadeLock 不能被恢复成 true", decoded.board.cascadeLock)
    }

    @Test
    fun `0 分 0 连击的快照也能 round-trip`() {
        val snapshot = GameSnapshot(sampleBoard(), score = 0, combo = 0, remainingSeconds = 60)
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot))!!
        assertEquals(0, decoded.score)
        assertEquals(0, decoded.combo)
        assertEquals(60, decoded.remainingSeconds)
    }

    // ------------------------------------------------------------------
    // append-only 兼容
    // ------------------------------------------------------------------

    /**
     * 未来给 header 加字段后，**老版本写的快照仍要能解**。
     *
     * GameRecordCodec 曾用 `parts.size != 3` 校验，导致加第 4 个字段会
     * 静默清空所有历史。这里用 `>=`，本用例锁死这个行为。
     */
    @Test
    fun `header 多出未知字段时仍能解析（前向兼容）`() {
        val original = sampleSnapshot()
        val encoded = GameSnapshotCodec.encode(original)
        val lines = encoded.lines()
        // 模拟「新版本加了两个字段」写出的快照
        val futureText = "${lines[0]},999,someFutureFlag\n${lines[1]}"

        val decoded = GameSnapshotCodec.decode(futureText)
        assertNotNull("多余字段应被忽略，不能整个丢弃快照", decoded)
        assertEquals("已知字段仍要正确解析", 1234, decoded!!.score)
        assertEquals(42, decoded.remainingSeconds)
    }

    @Test
    fun `header 字段不足时丢弃`() {
        // 只有 3 个字段，缺 boardSize —— 没有它无法确定棋盘尺寸，
        // 这是真正的不可恢复，与「多余字段」不同。
        val decoded = GameSnapshotCodec.decode("100,2,30\n1:0|2:1")
        assertNull("缺少必需字段时必须丢弃", decoded)
    }

    // ------------------------------------------------------------------
    // 脏数据一律丢弃（绝不返回半个棋盘）
    // ------------------------------------------------------------------

    @Test
    fun `空字符串返回 null`() {
        assertNull(GameSnapshotCodec.decode(""))
        assertNull(GameSnapshotCodec.decode("   \n  "))
    }

    @Test
    fun `只有 header 没有棋盘行返回 null`() {
        assertNull(GameSnapshotCodec.decode("100,2,30,3"))
    }

    @Test
    fun `格子数与 size 不匹配返回 null`() {
        // 声明 3x3 但只给了 2 格
        assertNull(GameSnapshotCodec.decode("100,2,30,3\n1:0|2:1"))
    }

    @Test
    fun `某一格损坏时丢弃整个快照`() {
        val encoded = GameSnapshotCodec.encode(sampleSnapshot())
        val lines = encoded.lines()
        val cells = lines[1].split("|").toMutableList()
        cells[4] = "notANumber:0"
        val corrupted = "${lines[0]}\n${cells.joinToString("|")}"

        assertNull(
            "单格损坏也要丢弃整盘 —— 半个棋盘可能带非法状态，比不恢复更糟",
            GameSnapshotCodec.decode(corrupted),
        )
    }

    @Test
    fun `type ordinal 越界返回 null`() {
        // SushiType 只有 6 个值，ordinal 99 非法
        assertNull(GameSnapshotCodec.decode("0,0,30,1\n1:99"))
    }

    @Test
    fun `tile id 非正数返回 null`() {
        // TileIdGenerator.next() 从 1 起，0 和负数都不是合法身份
        assertNull("id=0 非法", GameSnapshotCodec.decode("0,0,30,1\n0:0"))
        assertNull("负数 id 非法", GameSnapshotCodec.decode("0,0,30,1\n-5:0"))
    }

    @Test
    fun `负数标量返回 null`() {
        assertNull("负分非法", GameSnapshotCodec.decode("-1,0,30,1\n1:0"))
        assertNull("负连击非法", GameSnapshotCodec.decode("0,-1,30,1\n1:0"))
        assertNull("负剩余时间非法", GameSnapshotCodec.decode("0,0,-1,1\n1:0"))
    }

    @Test
    fun `size 非法返回 null`() {
        assertNull("size=0 非法", GameSnapshotCodec.decode("0,0,30,0\n"))
        assertNull("负 size 非法", GameSnapshotCodec.decode("0,0,30,-3\n1:0"))
        assertNull("过大的 size 非法", GameSnapshotCodec.decode("0,0,30,999\n1:0"))
    }

    @Test
    fun `全空棋盘能 round-trip 但不可恢复`() {
        val empty = GameSnapshot(
            board = Board(size = 3),
            score = 0,
            combo = 0,
            remainingSeconds = 30,
        )
        val decoded = GameSnapshotCodec.decode(GameSnapshotCodec.encode(empty))
        assertNotNull("格式上合法，应能解析", decoded)
        assertFalse("但全空棋盘不该被恢复", decoded!!.isRestorable)
    }

    // ------------------------------------------------------------------
    // GameSnapshot 的派生属性
    // ------------------------------------------------------------------

    @Test
    fun `maxTileId 取棋盘上最大的 id`() {
        val snapshot = sampleSnapshot()
        // sampleBoard 的 id 从 101 起，9 格去掉 1 个空格 = 8 个 tile
        assertEquals(108, snapshot.maxTileId)
    }

    @Test
    fun `空棋盘的 maxTileId 是 0`() {
        val snapshot = GameSnapshot(Board(size = 3), 0, 0, 30)
        assertEquals("空棋盘返回 0，seedAtLeast(0) 是 no-op", 0, snapshot.maxTileId)
    }

    @Test
    fun `isRestorable 要求有 tile 且剩余时间为正`() {
        assertTrue("正常进行中的局可恢复", sampleSnapshot().isRestorable)

        assertFalse(
            "剩余 0 秒说明已结束，恢复出来会立刻 game over",
            sampleSnapshot().copy(remainingSeconds = 0).isRestorable,
        )
        assertFalse(
            "空棋盘说明这局没真正开始",
            GameSnapshot(Board(size = 7), 0, 0, 60).isRestorable,
        )
    }
}
