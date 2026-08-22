package top.windyvalley.magicsushi.engine

import org.junit.Ignore
import org.junit.Test

/**
 * 临时探针：为 debug 固定棋盘挑一个种子，并打印出可消除的那一步。
 *
 * 不是回归测试 —— 只是把「哪个种子的开局有一步好走」这件事算出来，
 * 好让人工验证时能用 adb 精确点击，而不是随机乱划等运气。
 *
 * ## 为什么 @Ignore
 *
 * 它只打印不断言，跑起来对正确性没有任何保障作用，还会往测试计数里
 * 掺一个「永远通过」的用例、往日志里灌 200 次棋盘生成的输出。
 *
 * ## 怎么跑（注意：@Ignore 挡得住 --tests）
 *
 * JUnit4 的 `@Ignore` 优先于 `--tests` 过滤 —— 只加 `--tests` 会「构建成功
 * 但零输出」，看起来像探针坏了。必须**先临时注掉下面那行 @Ignore**：
 *
 *     ./gradlew :engine:test --tests '*DebugSeedProbe*' -i
 *
 * 拿到新的可行解坐标后更新 `GameViewModel.DEBUG_BOARD_SEED`，再把
 * @Ignore 加回来。
 *
 * ## 已知可行解（seed=1，实测有效）
 *
 * 两个都验证过，任选其一：
 *
 * - `(0,2) ↔ (0,3)` → c2 列 2/2/2 竖三连。屏幕 (395,656) → (540,656)
 * - `(2,1) ↔ (3,1)` → c1 列 3/3/3 竖三连。屏幕 (250,946) → (250,1091)
 *
 * 屏幕网格（模拟器 1080 宽）：列 left x = 33/178/323/468/612/757/902，
 * 行 top y 从 584 起、间距 145，tile 145×145，中心 = left+72 / top+72。
 */
@Ignore("探针工具，只打印不断言。换 debug 种子时手动跑")
class DebugSeedProbe {

    @Test
    fun `find seeds with an easy first move`() {
        // 找前几个「开局就有可行解」的种子，并打印第一步怎么走。
        val found = mutableListOf<String>()

        for (seed in 1L..200L) {
            TileIdGenerator.resetForTest()
            val board = BoardEngine.generateInitialBoard(seed)
            // 开局保证无 match，所以一定要交换才有解。
            val move = firstWinningSwap(board) ?: continue

            val (from, to) = move
            found.add(
                "seed=$seed  交换 (${from.first},${from.second}) <-> (${to.first},${to.second})"
            )
            if (found.size >= 5) break
        }

        println("=== 开局有可行解的种子 ===")
        found.forEach { println(it) }

        // 把第一个种子的棋盘整个打出来，方便对照屏幕。
        val best = 1L..200L
        for (seed in best) {
            TileIdGenerator.resetForTest()
            val board = BoardEngine.generateInitialBoard(seed)
            val move = firstWinningSwap(board) ?: continue
            println()
            println("=== seed=$seed 的棋盘（行,列，字母是类型首字母）===")
            for (r in 0 until board.size) {
                val line = (0 until board.size).joinToString(" ") { c ->
                    board.grid[r][c]?.type?.name?.first()?.toString() ?: "."
                }
                println("$r: $line")
            }
            val (from, to) = move
            println("可行解：(${from.first},${from.second}) <-> (${to.first},${to.second})")
            break
        }
    }

    /** 暴力找第一个能产生消除的相邻交换。返回两个坐标，找不到返回 null。 */
    private fun firstWinningSwap(
        board: Board,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        for (r in 0 until board.size) {
            for (c in 0 until board.size) {
                // 只试右和下，避免重复
                for ((dr, dc) in listOf(0 to 1, 1 to 0)) {
                    val nr = r + dr
                    val nc = c + dc
                    if (nr >= board.size || nc >= board.size) continue

                    val a = board.grid[r][c] ?: continue
                    val b = board.grid[nr][nc] ?: continue

                    val swapped = swapTiles(board, a, b)
                    if (MatchEngine.detectMatches(swapped).isNotEmpty()) {
                        return (r to c) to (nr to nc)
                    }
                }
            }
        }
        return null
    }

    /** 交换两个 tile 的位置，返回新棋盘。 */
    private fun swapTiles(board: Board, a: SushiTile, b: SushiTile): Board {
        val grid = board.grid.map { it.toMutableList() }.toMutableList()
        grid[a.row][a.col] = b.copy(row = a.row, col = a.col)
        grid[b.row][b.col] = a.copy(row = b.row, col = b.col)
        return board.copy(grid = grid)
    }
}
