package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追踪 spawn-in tile 跨帧的身份与位置，定位「新生成 tile 来回闪」。
 *
 * 这不是回归测试，是**诊断测试**：把 UI 层看到的帧序列打印/断言出来，
 * 确认 spawn tile 在相邻帧之间是否发生了
 *   (a) 身份变化（tileId 变 → Compose key 变 → composable 销毁重建）
 *   (b) 位置变化（同一 id 出现在不同 CellKey → 布局 offset 跳变）
 * 两者都会表现为「闪」。
 */
class SpawnTileFrameContinuityTest {

    private fun tile(row: Int, col: Int, type: SushiType) =
        SushiTile(id = TileIdGenerator.next(), type = type, row = row, col = col)

    /**
     * 单列全消 → 整列 7 格都要补新 tile。
     *
     * 检查 frame 2 里每个 spawn tile 的 (id, CellKey)，与最终 refilled
     * 棋盘上该 id 的实际位置是否一致。不一致就意味着 UI 上 tile 会先画在
     * A 格、下一帧跳到 B 格。
     */
    @Test
    fun `spawn tile 在 frame2 的位置与 refilled 棋盘一致`() {
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List(7) { c ->
                if (c == 0) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
            }
        }
        val board = Board(size = 7, grid = grid)
        val allCol0 = (0..6).map { board.grid[it][0]!! }
        val match = Match(tiles = allCol0, axis = MatchAxis.VERTICAL, length = 7)

        val fallen = GravityEngine.applyGravity(board, listOf(match))
        val refilled = BoardEngine.spawnRefill(fallen)
        val frames = AnimationEngine.generateFrames(
            board, listOf(match), fallenBoard = fallen, refilledBoard = refilled,
        )

        // refilled 棋盘上：id → 它最终待的位置
        val finalPos = mutableMapOf<Int, AnimationEngine.CellKey>()
        for (r in 0..6) for (c in 0..6) {
            refilled.grid[r][c]?.let { finalPos[it.id] = AnimationEngine.CellKey(r, c) }
        }

        var checked = 0
        for ((cellKey, state) in frames[2]) {
            if (state.anim !is AnimationEngine.TileAnim.SpawningIn) continue
            checked++
            val whereItEndsUp = finalPos[state.tileId]
            assertEquals(
                "spawn tile id=${state.tileId} 在 frame2 画在 $cellKey，" +
                    "但它在 refilled 棋盘上位于 $whereItEndsUp —— " +
                    "位置不一致会让 tile 在下一帧跳格（表现为闪）",
                cellKey,
                whereItEndsUp,
            )
        }
        assertTrue("应该检查到 spawn tile", checked > 0)
    }

    /**
     * frame 1 是否真的完全不画 spawn 格子。
     *
     * 若 frame 1 已经在那些格子上画了别的东西（比如残留的旧 tile），
     * 那么 frame 1 → frame 2 会看到「一个 tile 变成另一个 tile」，也是闪。
     */
    @Test
    fun `frame1 在 spawn 格子上不画任何东西`() {
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List(7) { c ->
                if (c == 0) tile(r, c, SushiType.SUSHI1) else tile(r, c, SushiType.SUSHI3)
            }
        }
        val board = Board(size = 7, grid = grid)
        val allCol0 = (0..6).map { board.grid[it][0]!! }
        val match = Match(tiles = allCol0, axis = MatchAxis.VERTICAL, length = 7)

        val fallen = GravityEngine.applyGravity(board, listOf(match))
        val refilled = BoardEngine.spawnRefill(fallen)
        val frames = AnimationEngine.generateFrames(
            board, listOf(match), fallenBoard = fallen, refilledBoard = refilled,
        )

        // frame2 里所有 SpawningIn 的格子
        val spawnCells = frames[2]
            .filterValues { it.anim is AnimationEngine.TileAnim.SpawningIn }
            .keys

        assertTrue("应该有 spawn 格子", spawnCells.isNotEmpty())
        for (cell in spawnCells) {
            assertTrue(
                "frame1 不应在 spawn 格子 $cell 上画东西，" +
                    "否则 frame1→frame2 会看到 tile 突变（闪）。" +
                    "实际画了：${frames[1][cell]}",
                frames[1][cell] == null,
            )
        }
    }

    /**
     * 同一个 tile id 在 frame1 / frame2 之间是否换过格子。
     *
     * 这是「来回闪」最可能的来源：Compose 的 key 是 tileId，布局位置由
     * CellKey 决定。同一个 key 换 CellKey → Modifier.offset 跳变，而动画
     * 位移是另一套，两者叠加就会看到来回。
     */
    @Test
    fun `同一个 tile 在 frame1 和 frame2 不应换格子`() {
        val grid: List<List<SushiTile?>> = List(7) { r ->
            List(7) { c ->
                // 让 col 0 的 row 4,5,6 三连 → 只消 3 格，上方 4 格要落下来
                if (c == 0 && r >= 4) tile(r, c, SushiType.SUSHI1)
                else if (c == 0) tile(r, c, SushiType.SUSHI2)
                else tile(r, c, SushiType.SUSHI3)
            }
        }
        val board = Board(size = 7, grid = grid)
        val matchTiles = (4..6).map { board.grid[it][0]!! }
        val match = Match(tiles = matchTiles, axis = MatchAxis.VERTICAL, length = 3)

        val fallen = GravityEngine.applyGravity(board, listOf(match))
        val refilled = BoardEngine.spawnRefill(fallen)
        val frames = AnimationEngine.generateFrames(
            board, listOf(match), fallenBoard = fallen, refilledBoard = refilled,
        )

        fun idToCell(frame: AnimFrame): Map<Int, AnimationEngine.CellKey> =
            frame.entries.associate { (k, v) -> v.tileId to k }

        val f1 = idToCell(frames[1])
        val f2 = idToCell(frames[2])

        val moved = mutableListOf<String>()
        for ((id, cell1) in f1) {
            val cell2 = f2[id] ?: continue
            if (cell1 != cell2) moved += "id=$id: $cell1 → $cell2"
        }
        assertTrue(
            "同一个 tile 在 frame1/frame2 换了格子，Compose 的 Modifier.offset " +
                "会跳变（表现为闪）：\n" + moved.joinToString("\n"),
            moved.isEmpty(),
        )
    }
}
