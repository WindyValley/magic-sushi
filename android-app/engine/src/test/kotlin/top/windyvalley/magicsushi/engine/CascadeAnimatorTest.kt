package top.windyvalley.magicsushi.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：`playCascadeAnimation` 的时序与棋盘推进。
 *
 * 这段逻辑此前内嵌在 GameViewModel 的 60 行协程块里，只能靠真机观察。
 * 抽出后可以用 runTest 的虚拟时钟精确断言时序 —— P1-2（双份重力）和
 * D4（spawn tile 身份）两个 bug 都长在这里，正是缺少这层验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CascadeAnimatorTest {

    private val phaseMs = 100L
    private val gapMs = 100L

    /** 一个 col0 整列同色、可触发消除的棋盘。 */
    private fun boardWithColumnMatch(): Board {
        var nextId = 1
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List(7) { c ->
                val type = if (c == 0) SushiType.SUSHI1
                else listOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)[(r + c) % 3]
                SushiTile(id = nextId++, type = type, row = r, col = c)
            }
        }
        return Board(size = 7, grid = grid)
    }

    @Test
    fun `单轮 cascade 推送 3 帧，时序为 phase-gap-phase-gap-phase`() = runTest {
        val scope = this
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        assertTrue("测试前提：应检测到匹配", matches.isNotEmpty())

        // 记录每次 onFrame 的虚拟时刻。
        val timeline = mutableListOf<Long>()
        playCascadeAnimation(
            startBoard = board,
            cascades = listOf(matches),
            phaseMs = phaseMs,
            gapMs = gapMs,
            onFrame = { _, _ -> timeline.add(scope.currentTime) },
        )

        // 帧 0 在 0ms；帧 1 在 phase+gap=200ms；帧 2 在 400ms。
        assertEquals("单轮应推送 3 帧", 3, timeline.size)
        assertEquals("帧 0 应在 0ms", 0L, timeline[0])
        assertEquals("帧 1 应在 200ms（phase+gap）", 200L, timeline[1])
        assertEquals("帧 2 应在 400ms", 400L, timeline[2])

        // 最后一轮播完还要等一个 phase，总时长 500ms，且不含尾部 gap。
        assertEquals("单轮总时长应为 500ms（无尾部 gap）", 500L, scope.currentTime)
    }

    @Test
    fun `多轮 cascade 之间插入一个 gap`() = runTest {
        val scope = this
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)

        val timeline = mutableListOf<Long>()
        playCascadeAnimation(
            startBoard = board,
            cascades = listOf(matches, matches),
            phaseMs = phaseMs,
            gapMs = gapMs,
            onFrame = { _, _ -> timeline.add(scope.currentTime) },
        )

        assertEquals("两轮应推送 6 帧", 6, timeline.size)
        // 第一轮 0→500ms，插入一个 gap 后第二轮起始于 600ms。
        assertEquals("第二轮帧 0 应在 600ms（500 + gap）", 600L, timeline[3])
        assertEquals("两轮总时长应为 1100ms（500 + gap + 500）", 1100L, scope.currentTime)
    }

    @Test
    fun `只有第一帧携带棋盘，后两帧仅换帧`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)

        val boards = mutableListOf<Board?>()
        playCascadeAnimation(
            startBoard = board,
            cascades = listOf(matches),
            phaseMs = phaseMs,
            gapMs = gapMs,
            onFrame = { b, _ -> boards.add(b) },
        )

        // 原实现里帧 0 用 copy(board=..., animFrame=...)，帧 1/2 只改 animFrame。
        // 这个契约必须保住，否则会在动画中途重置棋盘。
        assertEquals(board, boards[0])
        assertEquals("帧 1 不应携带棋盘", null, boards[1])
        assertEquals("帧 2 不应携带棋盘", null, boards[2])
    }

    @Test
    fun `shouldContinue 返回 false 时立即停止后续轮次`() = runTest {
        val scope = this
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)

        // 模拟第一轮播完后游戏超时/暂停。守卫是幂等查询，无副作用 ——
        // 这正是 VM 传 `_state.value.phase == PLAYING` 的形态。
        var playedRounds = 0
        val frames = mutableListOf<AnimFrame>()
        playCascadeAnimation(
            startBoard = board,
            cascades = listOf(matches, matches, matches),
            phaseMs = phaseMs,
            gapMs = gapMs,
            shouldContinue = { playedRounds < 1 },
            onFrame = { _, f ->
                frames.add(f)
                // 每轮 3 帧，第 3 帧后视为该轮播完。
                if (frames.size % 3 == 0) playedRounds++
            },
        )

        assertEquals("应只播放第一轮的 3 帧", 3, frames.size)
        // 中止时不应多等一个 round 间隙 —— gap 放在下一轮开头保证了这点。
        assertEquals("总时长应为单轮的 500ms", 500L, scope.currentTime)
    }

    @Test
    fun `空 cascade 列表不推送任何帧且不耗时`() = runTest {
        val scope = this
        val board = boardWithColumnMatch()

        val frames = mutableListOf<AnimFrame>()
        val result = playCascadeAnimation(
            startBoard = board,
            cascades = emptyList(),
            phaseMs = phaseMs,
            gapMs = gapMs,
            onFrame = { _, f -> frames.add(f) },
        )

        assertTrue("不应推送任何帧", frames.isEmpty())
        assertEquals("不应消耗时间", 0L, scope.currentTime)
        assertEquals("应原样返回起始棋盘", board, result)
    }

    @Test
    fun `返回的棋盘是最后一轮补充后的结果且已补满`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        // 与生产路径一致：补充结果由 CascadeEngine 单点算出后传入。
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        val result = playCascadeAnimation(
            startBoard = board,
            cascades = cascadeResult.cascades,
            phaseMs = phaseMs,
            gapMs = gapMs,
            rounds = cascadeResult.rounds,
            onFrame = { _, _ -> },
        )

        val nulls = result.grid.sumOf { row -> row.count { it == null } }
        assertEquals("返回的棋盘必须已补满", 0, nulls)
        assertTrue("棋盘应已变化（发生了消除）", result != board)
    }

    /**
     * D4 索引体系收口 + bug「动画寿司与落定不符」的回归防线。
     *
     * 契约：动画里飞进来的那一个，就是落定后站在那格的那一个。
     *
     * ⚠️ 关键在于 rounds 必须来自 `CascadeEngine` —— 早期实现允许动画层
     * 在不传时自己调 `spawnRefill` 兜底，那会重新摇一次随机，产出与
     * `finalBoard` 不同的另一批 tile。本用例额外断言与 `finalBoard`
     * 一致，把那条路彻底堵死。
     */
    @Test
    fun `SpawnIn 帧的 tile 身份与返回棋盘一致`() = runTest {
        val board = boardWithColumnMatch()
        val matches = MatchEngine.detectMatches(board)
        val cascadeResult = CascadeEngine.cascadeUntilStable(board, matches)

        var lastFrame: AnimFrame? = null
        val result = playCascadeAnimation(
            startBoard = board,
            cascades = cascadeResult.cascades,
            phaseMs = phaseMs,
            gapMs = gapMs,
            rounds = cascadeResult.rounds,
            onFrame = { _, f -> lastFrame = f },
        )

        val spawnStates = lastFrame!!.filterValues {
            it.anim is AnimationEngine.TileAnim.SpawningIn
        }
        assertTrue("该场景应有 spawn tile", spawnStates.isNotEmpty())
        for ((cell, state) in spawnStates) {
            val real = result.grid[cell.row][cell.col]
            assertEquals("$cell 的 id 必须与返回棋盘一致", real!!.id, state.tileId)
            assertEquals("$cell 的 type 必须与返回棋盘一致", real.type, state.type)

            // 与权威终态对齐 —— 这是玩家实际看到的那张棋盘。
            val authoritative = cascadeResult.finalBoard.grid[cell.row][cell.col]
            assertEquals(
                "$cell 的 id 必须与 CascadeEngine 的 finalBoard 一致",
                authoritative!!.id, state.tileId,
            )
            assertEquals(
                "$cell 的 type 必须与 CascadeEngine 的 finalBoard 一致",
                authoritative.type, state.type,
            )
        }
    }
}
