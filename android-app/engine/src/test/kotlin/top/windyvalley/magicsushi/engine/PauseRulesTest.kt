package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PauseRules] 的逐状态覆盖。
 *
 * 四个 phase 各有一条用例 —— 这类"只有一个状态放行"的规则，最容易在后续
 * 加状态时漏掉判断，穷举比写一条 PLAYING 用例更能挡住回归。
 */
class PauseRulesTest {

    @Test
    fun `进行中的对局可以暂停`() {
        assertTrue(PauseRules.shouldPause(GamePhase.PLAYING))
    }

    @Test
    fun `结算面板下不暂停 —— 切后台回来必须停在结算面板`() {
        // 用户报的边界场景：这局已经结束，"暂停"没有意义。且回到前台
        // 不自动继续，玩家会卡在一个点「继续」才能离开的面板上。
        assertFalse(PauseRules.shouldPause(GamePhase.GAME_OVER))
    }

    @Test
    fun `已暂停不重复暂停`() {
        assertFalse(PauseRules.shouldPause(GamePhase.PAUSED))
    }

    @Test
    fun `IDLE 不暂停 —— 否则会抹掉挂起回菜单留下的状态`() {
        // 停在菜单时切后台也会触发 ON_PAUSE。若把 IDLE 改成 PAUSED，
        // onStopWithSnapshot 就无法再靠 phase 区分该不该保住快照。
        assertFalse(PauseRules.shouldPause(GamePhase.IDLE))
    }

    @Test
    fun `只有一个 phase 放行`() {
        // 把"放行集合恰好是 {PLAYING}"这条意图固定下来：将来新增 phase
        // 时，若忘了考虑它是否该暂停，这条会失败。
        val allowed = GamePhase.entries.filter { PauseRules.shouldPause(it) }
        assertTrue(allowed == listOf(GamePhase.PLAYING))
    }
}
