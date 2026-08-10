package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models.kt 的基础契约测试。
 *
 * 迁移来源：`Models.kt` 末尾的 `fun main()` 手动测试（清理）。
 * 那些 `check(...)` 断言此前没有任何 JUnit 覆盖，直接删除会丢失覆盖率，
 * 因此先在这里落地再删源文件里的 main()。
 *
 * 这些断言看似琐碎，但守的是**下游引擎的隐式假设**：
 * - 棋盘尺寸 7×7 被 BoardEngine / MatchEngine / GravityEngine 硬编码依赖
 * - SushiType 基数决定随机生成的分布与"三连概率"
 * - Direction / MatchAxis 基数决定手势与匹配扫描的分支完备性
 */
class ModelsTest {

    // ---- Board 默认值 ----

    @Test
    fun `Board 默认尺寸为 7`() {
        assertEquals(7, Board().size)
    }

    @Test
    fun `Board 默认 grid 是 7x7 且全为 null`() {
        val board = Board()

        assertEquals("行数应为 7", 7, board.grid.size)
        assertTrue("每行都应有 7 列", board.grid.all { it.size == 7 })
        assertTrue(
            "默认棋盘必须全空 —— 真实棋盘由 startGame() 注入（D6）",
            board.grid.flatten().all { it == null },
        )
    }

    @Test
    fun `Board 默认不加锁`() {
        val board = Board()

        assertFalse("swapLock 默认应为 false", board.swapLock)
        assertFalse("cascadeLock 默认应为 false", board.cascadeLock)
    }

    // ---- 枚举基数 ----
    //
    // 这几条不是"数一数枚举有几项"的废话测试：基数变化会静默改变
    // 游戏难度与匹配逻辑的分支完备性，必须显式锁定。

    @Test
    fun `SushiType 有 6 种`() {
        assertEquals(
            "寿司种类数决定随机生成的三连概率，改动会影响游戏难度",
            6,
            SushiType.entries.size,
        )
    }

    @Test
    fun `Direction 有 4 个`() {
        assertEquals(
            "四方向对应手势的上下左右，新增方向需同步 swap 合法性判定",
            4,
            Direction.entries.size,
        )
    }

    @Test
    fun `MatchAxis 有 2 个`() {
        assertEquals(
            "匹配轴只有横竖两种，新增需同步 MatchEngine 的扫描分支",
            2,
            MatchAxis.entries.size,
        )
    }
}
