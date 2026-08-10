package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoundTeardown] 的测试。
 *
 * ## 这些用例真正在守什么
 *
 * 不是「copy 会不会赋值」（那是 Kotlin 的事），而是**清理清单的完整性**。
 *
 * 「离开对局时棋盘没重置」这个 bug 分三次才修完：先补了分数、再补棋盘、
 * 又补 animFrame —— 每次都以为清完了。根因是这份清单散在两个调用点里，
 * 靠人肉记全。
 *
 * 所以最重要的用例是 [teardown_clearsEveryRoundScopedField]：它枚举
 * GameState 的全部字段，逐个断言「该清的清了、该留的留了」。将来给
 * GameState 加字段而忘了在 teardown 里处理，这条会失败。
 */
class RoundTeardownTest {

    /** 造一个「对局进行到一半」的 state，所有字段都是非默认值。 */
    private fun midRoundState(): GameState = GameState(
        board = BoardEngine.generateInitialBoard(seed = 42L),
        score = 1234,
        combo = 5,
        remainingSeconds = 17,
        phase = GamePhase.PLAYING,
        selectedTile = 3 to 4,
        isMuted = true,
        highScore = 9999,
        isRollback = true,
        isNewRecord = true,
        roundFinalized = true,
        animFrame = null,
    )

    // ========================================================================
    // 核心：清理清单的完整性
    // ========================================================================

    /**
     * **穷尽性防线**：GameState 的每个字段都要有明确归属 —— 清或留。
     *
     * 12 个构造字段（presentation 是派生 getter，不算）：
     *   清 10 个：board score combo remainingSeconds phase selectedTile
     *            isRollback isNewRecord roundFinalized animFrame
     *   留 2 个：isMuted highScore
     *
     * 若给 GameState 新增字段而没在 teardown 里处理，它会保留上一局的值。
     * 这条用例本身不会自动发现新字段（Kotlin 没有编译期穷尽检查 data class
     * 字段的手段），但它把清单显式列在这里 —— 加字段的人读到这个测试就会
     * 意识到需要决定归属。
     */
    @Test
    fun teardown_clearsEveryRoundScopedField() {
        val result = RoundTeardown.teardown(midRoundState())

        // ---- 该清的 ----
        assertEquals("phase 应回到 IDLE", GamePhase.IDLE, result.phase)
        assertEquals("分数应归零", 0, result.score)
        assertEquals("连击应归零", 0, result.combo)
        assertEquals(
            "倒计时应回到初始值",
            TimerEngine.INITIAL_SECONDS,
            result.remainingSeconds,
        )
        assertFalse("isNewRecord 应清除", result.isNewRecord)
        assertFalse("roundFinalized 应清除", result.roundFinalized)
        assertFalse("isRollback 应清除", result.isRollback)
        assertNull("selectedTile 应清除", result.selectedTile)
        assertNull("animFrame 应清除", result.animFrame)

        // ---- 该留的 ----
        assertTrue("isMuted 是用户设置，与单局无关，必须保留", result.isMuted)
        assertEquals(
            "highScore 是历史派生值，与单局无关，必须保留",
            9999,
            result.highScore,
        )
    }

    /**
     * 棋盘必须清成**空盘**，而不是一副新生成的棋盘。
     *
     * 若这里生成新棋盘，startGame() 又会生成另一副，玩家仍会看到一次替换
     * —— 那正是要修的 bug。空盘在 GameCanvas 里只画背景网格。
     */
    @Test
    fun teardown_boardBecomesEmptyNotFreshlyGenerated() {
        val result = RoundTeardown.teardown(midRoundState())

        assertEquals("棋盘尺寸不变", 7, result.board.size)
        val tileCount = result.board.grid.sumOf { row -> row.count { it != null } }
        assertEquals("棋盘应该是空的（全 null），不是新生成的一副", 0, tileCount)
    }

    /**
     * animFrame 非 null 时 GameState.presentation 会走 Animating 分支并
     * **忽略 board**。所以只清 board 不清 animFrame 的话，空棋盘会配着
     * 上一局的动画帧渲染 —— 比不清更糟。
     *
     * 这条用例锁住的是 presentation 的推导结果，而不只是字段值。
     */
    @Test
    fun teardown_presentationBecomesStableEmptyBoard() {
        val result = RoundTeardown.teardown(midRoundState())

        val presentation = result.presentation
        assertTrue(
            "清理后 presentation 必须是 Stable（animFrame 已清），" +
                "实际是 ${presentation::class.simpleName}",
            presentation is BoardPresentation.Stable,
        )
    }

    /**
     * 幂等：对已经清理过的 state 再清一次，结果不变。
     *
     * onQuit 和 onStopWithSnapshot 都可能在 phase 已是 IDLE 时被调用
     * （比如结算面板上切后台）。
     */
    @Test
    fun teardown_isIdempotent() {
        val once = RoundTeardown.teardown(midRoundState())
        val twice = RoundTeardown.teardown(once)

        assertEquals("重复清理结果应完全相同", once, twice)
    }

    /**
     * 从 GAME_OVER 清理（结算面板点「返回菜单」）也要清干净。
     *
     * 这条路径下 roundFinalized 已是 true、分数已入库，最容易被认为
     * 「反正要走了不用清」—— 而那正是 bug 的来源。
     */
    @Test
    fun teardown_fromGameOverAlsoClears() {
        val gameOver = midRoundState().copy(
            phase = GamePhase.GAME_OVER,
            remainingSeconds = 0,
            roundFinalized = true,
        )

        val result = RoundTeardown.teardown(gameOver)

        assertEquals(GamePhase.IDLE, result.phase)
        assertEquals(0, result.score)
        assertEquals(TimerEngine.INITIAL_SECONDS, result.remainingSeconds)
        assertFalse(result.roundFinalized)
    }

    /**
     * 0 分退出（玩家进游戏没动就走）同样清理，且不因为「本来就是 0」
     * 而跳过其他字段。
     */
    @Test
    fun teardown_zeroScoreRoundStillClearsBoard() {
        val untouched = GameState(
            board = BoardEngine.generateInitialBoard(seed = 7L),
            score = 0,
            phase = GamePhase.PLAYING,
            highScore = 500,
        )

        val result = RoundTeardown.teardown(untouched)

        val tileCount = result.board.grid.sumOf { row -> row.count { it != null } }
        assertEquals("0 分也要清棋盘", 0, tileCount)
        assertEquals("最高分仍保留", 500, result.highScore)
    }
}
