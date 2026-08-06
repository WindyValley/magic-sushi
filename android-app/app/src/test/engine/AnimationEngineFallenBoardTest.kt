package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * FIX_PLAN P1-2 回归测试：`generateFrames` 的 `fallenBoard` 参数注入。
 *
 * 背景：`generateFrames` 内部需要知道「重力落地后各 tile 在哪」，此前它自己
 * 调 `GravityEngine.applyGravity(board, matches, doRefill = false)` 算一次；
 * 而 `GameViewModel` 的 cascade 循环末尾为了推进到下一轮，又算了一次
 * （且用的是默认 `doRefill = true`，会额外跑 RNG 补 tile）。
 *
 * 两份结果不同源，一旦哪边参数漂移就会出现「动画依据的落点」与「下一轮起始
 * 棋盘」不一致 —— 表现为 tile 位置跳变。
 *
 * 本测试锁死两件事：
 *  1. 注入 `fallenBoard` 与不注入必须产出**完全相同**的帧（纯粹的复用，
 *     不改变行为）。
 *  2. 注入的必须是 `doRefill = false` 的中间态；如果误传补齐后的棋盘，
 *     SpawnIn 帧会丢失（因为没有空格了）—— 这个用例把该差异显式记录下来，
 *     防止后人"顺手"传错。
 */
class AnimationEngineFallenBoardTest {

    /** 造一块第 3 行 col 0..2 为 SUSHI1 的棋盘，filler 保证别处无三连。 */
    private fun boardWithRowMatch(): Board {
        val filler = listOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 1
        val grid = List(7) { r ->
            List<SushiTile?>(7) { c ->
                val t = if (r == 3 && c in 0..2) SushiType.SUSHI1 else filler[(c + r) % 3]
                SushiTile(id = nextId++, type = t, row = r, col = c)
            }
        }
        return Board(grid = grid)
    }

    @Test
    fun `injecting fallenBoard produces structurally identical frames`() {
        val board = boardWithRowMatch()
        val matches = MatchEngine.detectMatches(board)
        assertTrue("测试前提：棋盘上应有匹配", matches.isNotEmpty())

        // 不注入 —— generateFrames 内部自己算。
        val framesInternal = AnimationEngine.generateFrames(board, matches)

        // 注入调用方算好的同一份重力结果。
        val fallen = GravityEngine.applyGravity(board, matches, doRefill = false)
        val framesInjected = AnimationEngine.generateFrames(board, matches, fallenBoard = fallen)

        assertEquals("帧数必须一致", framesInternal.size, framesInjected.size)

        // ⚠️ 不能整帧 assertEquals。SpawnIn 帧里新 tile 的 type 是
        // generateFrames 内部用 Random.Default 现场生成的装饰性数据 ——
        // 与最终棋盘无关。注入路径少跑/多跑一次 applyGravity 会让全局
        // RNG 序列错位，导致这些装饰类型不同。那不是行为差异。
        //
        // 真正需要锁死的是**结构**：每帧覆盖哪些格子、visualId 是什么、
        // 动画状态和 offsetY 是什么。
        for (i in framesInternal.indices) {
            val a = framesInternal[i]
            val b = framesInjected[i]
            assertEquals("第 $i 帧覆盖的格子集合必须一致", a.keys, b.keys)
            for (cell in a.keys) {
                val sa = a.getValue(cell)
                val sb = b.getValue(cell)
                assertEquals("第 $i 帧 $cell 的 visualId", sa.visualId, sb.visualId)
                assertEquals("第 $i 帧 $cell 的 alpha", sa.alpha, sb.alpha, 0.0001f)
                assertEquals("第 $i 帧 $cell 的 offsetY", sa.offsetY, sb.offsetY, 0.0001f)
                assertEquals("第 $i 帧 $cell 的 scale", sa.scale, sb.scale, 0.0001f)
                assertEquals("第 $i 帧 $cell 的 anim 状态", sa.anim, sb.anim)
                // 幸存 tile（visualId > 0）的 type 来自真实棋盘，必须一致；
                // 新生成 tile（visualId < 0）的 type 是随机装饰，跳过。
                if (sa.visualId > 0) {
                    assertEquals("第 $i 帧 $cell 的 type（幸存 tile）", sa.type, sb.type)
                }
            }
        }
    }

    @Test
    fun `injected fallenBoard must be the doRefill-false intermediate state`() {
        val board = boardWithRowMatch()
        val matches = MatchEngine.detectMatches(board)

        val fallenNoRefill = GravityEngine.applyGravity(board, matches, doRefill = false)
        val fallenRefilled = GravityEngine.applyGravity(
            board, matches, doRefill = true, rng = Random(42),
        )

        // doRefill=false 保留空格（给 SpawnIn 帧用）；doRefill=true 补满。
        val nullsNoRefill = fallenNoRefill.grid.sumOf { row -> row.count { it == null } }
        val nullsRefilled = fallenRefilled.grid.sumOf { row -> row.count { it == null } }
        assertTrue("doRefill=false 必须留下空格给 SpawnIn 帧", nullsNoRefill > 0)
        assertEquals("doRefill=true 必须补满棋盘", 0, nullsRefilled)

        // 误传补满的棋盘会让 SpawnIn 帧丢失新生成的 tile —— 记录这个差异，
        // 防止后人"顺手"传 doRefill=true 的结果。
        // 比较 key 集合（确定性），而非整帧（含随机 type）。
        val framesCorrect =
            AnimationEngine.generateFrames(board, matches, fallenBoard = fallenNoRefill)
        val framesWrong =
            AnimationEngine.generateFrames(board, matches, fallenBoard = fallenRefilled)

        val spawnCellsCorrect = framesCorrect[2].filterValues { it.visualId < 0 }.keys
        val spawnCellsWrong = framesWrong[2].filterValues { it.visualId < 0 }.keys
        assertTrue(
            "传 doRefill=false 的中间态时，SpawnIn 帧应包含新生成的 tile（visualId<0）",
            spawnCellsCorrect.isNotEmpty(),
        )
        assertTrue(
            "误传补满棋盘时没有空格，SpawnIn 帧不会产出新 tile —— 这不是等价替换",
            spawnCellsWrong.isEmpty(),
        )
    }

    @Test
    fun `no matches yields stable frames regardless of fallenBoard`() {
        val filler = listOf(SushiType.SUSHI3, SushiType.SUSHI4, SushiType.SUSHI5)
        var nextId = 1
        val grid = List(7) { r ->
            List<SushiTile?>(7) { c ->
                SushiTile(id = nextId++, type = filler[(c + r) % 3], row = r, col = c)
            }
        }
        val board = Board(grid = grid)
        val matches = MatchEngine.detectMatches(board)
        assertTrue("filler 棋盘不应有匹配", matches.isEmpty())

        // 空 matches 走 early-return，fallenBoard 参数应被完全忽略。
        val a = AnimationEngine.generateFrames(board, matches)
        val b = AnimationEngine.generateFrames(board, matches, fallenBoard = board)
        assertEquals(a, b)
    }
}
