package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameState.presentation] 的投影语义测试。
 *
 * D4 把 `(board, animFrame?)` 这对隐式互斥字段收敛成密封类型
 * [BoardPresentation]，消灭了"animFrame 非空时忽略 board"这条只写在
 * 注释里的口头约定。这里锁住投影规则本身。
 *
 * 为什么值得测：投影是 UI 渲染的唯一入口。如果它选错分支，
 * 玩家看到的就是"动画期间棋盘还显示着已被消除的 tile"这类错乱 ——
 * 而单测是唯一能在真机之前拦住它的地方。
 */
class BoardPresentationTest {

    private fun tile(row: Int, col: Int, type: SushiType = SushiType.SUSHI1) =
        SushiTile(id = TileIdGenerator.next(), type = type, row = row, col = col)

    private fun filledBoard(): Board =
        Board(grid = List(7) { r -> List(7) { c -> tile(r, c) } })

    // ---- 分支选择 ----

    @Test
    fun `animFrame 为 null 时投影为 Stable`() {
        val board = filledBoard()
        val state = GameState(board = board, animFrame = null)

        val p = state.presentation

        assertTrue("无动画时必须是 Stable", p is BoardPresentation.Stable)
        assertEquals(board, (p as BoardPresentation.Stable).board)
    }

    @Test
    fun `animFrame 非 null 时投影为 Animating`() {
        val board = filledBoard()
        val frame: AnimFrame = mapOf(
            AnimationEngine.CellKey(0, 0) to AnimationEngine.TileRenderState(
                tileId = 1,
                type = SushiType.SUSHI1,
                alpha = 0.5f,
                offsetY = 0f,
                offsetX = 0f,
                scale = 1f,
                anim = AnimationEngine.TileAnim.FadingOut,
            ),
        )
        val state = GameState(board = board, animFrame = frame)

        val p = state.presentation

        assertTrue("有动画帧时必须是 Animating", p is BoardPresentation.Animating)
        assertEquals(frame, (p as BoardPresentation.Animating).frame)
    }

    // ---- 逻辑棋盘的语义 ----

    @Test
    fun `Animating 的 logicalBoard 是冻结的那份 board 而非帧数据`() {
        val board = filledBoard()
        val frame: AnimFrame = emptyMap()
        val state = GameState(board = board, animFrame = frame)

        val p = state.presentation as BoardPresentation.Animating

        // 关键契约：动画期间 board 被刻意冻结（还含着已被消除的 tile），
        // 手势命中测试要用的正是这份，不是帧数据。
        assertSame(
            "logicalBoard 必须原样透传 GameState.board —— 手势命中依赖它",
            board,
            p.logicalBoard,
        )
    }

    @Test
    fun `GameState_board 在两种投影下都保持可读`() {
        val board = filledBoard()

        // 逻辑用途（相邻判定、命中测试）永远走 state.board，
        // 不受投影分支影响 —— 这是 D4 刻意保留的读取路径。
        assertEquals(board, GameState(board = board, animFrame = null).board)
        assertEquals(board, GameState(board = board, animFrame = emptyMap()).board)
    }

    // ---- 投影是纯函数 ----

    @Test
    fun `相同 state 连续取 presentation 结果相等`() {
        val state = GameState(board = filledBoard(), animFrame = emptyMap())

        // presentation 是 computed property。它必须是纯投影：
        // 同一个 state 多次读取结果一致，不携带隐藏状态。
        assertEquals(state.presentation, state.presentation)
    }

    @Test
    fun `空棋盘也能正常投影`() {
        // 默认 GameState 的 board 是全 null 的空棋盘（D6），
        // startGame() 之前 UI 就可能读到它，不能崩。
        val p = GameState().presentation

        assertTrue(p is BoardPresentation.Stable)
        assertEquals(7, (p as BoardPresentation.Stable).board.size)
    }
}
