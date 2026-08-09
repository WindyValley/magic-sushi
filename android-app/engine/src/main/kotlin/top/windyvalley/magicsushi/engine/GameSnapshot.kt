package top.windyvalley.magicsushi.engine

/**
 * 一局进行中的对局快照 —— 断点续玩用。
 *
 * ## 为什么不直接序列化 GameState
 *
 * [GameState] 里大半字段是**瞬时状态**，存下来恢复出的是坏现场：
 *
 * | 字段 | 不存的理由 |
 * |---|---|
 * | `animationFrame` | 存下来会恢复成「卡在动画中间」 |
 * | `isRollback`     | 一次性信号，存活窗口约 150ms |
 * | `selectedTile`   | 瞬时交互状态，玩家回来重新点即可 |
 * | `phase`          | 恢复时一律是 PAUSED，与存的那个无关 |
 * | `highScore`      | 已由 PrefsRepository 持有，存两份必然分叉 |
 * | `isMuted`        | 同上 |
 * | `isNewRecord`    | 结算时算出，不是对局现场的一部分 |
 *
 * 剩下的才是「恢复现场」真正需要的：棋盘、分数、连击、剩余时间。
 *
 * ## 快照的生命周期
 *
 * 绑定在**暂停动作**上，而不是独立的存储逻辑：
 *
 *     切后台 / 划掉应用 → 暂停 → 写快照
 *     回到游戏          → 恢复 → 消费并删除快照
 *
 * 所以不需要过期时间：「快照存在」本身就等价于「有一局被中断了」。
 * 恢复后立即删除，自然失效 —— 不存在「隔天打开恢复出一局 15 秒残局」
 * 之外的怪状态，而那恰恰是玩家自己中断的那一局，符合预期。
 *
 * ## tile id 与进程重启
 *
 * 恢复棋盘时**必须**调用 [TileIdGenerator.seedAtLeast]（见 [maxTileId]）。
 * 计数器活在内存里，进程重启即归零；不同步就会与恢复回来的 tile 撞号，
 * 导致 Compose 的 `key` 错乱、动画乱窜。
 */
data class GameSnapshot(
    val board: Board,
    val score: Int,
    val combo: Int,
    val remainingSeconds: Int,
) {
    /**
     * 棋盘上最大的 tile id。恢复后用它同步 [TileIdGenerator]。
     *
     * 空棋盘返回 0 —— [TileIdGenerator.seedAtLeast] 对 0 是 no-op。
     */
    val maxTileId: Int
        get() = board.grid.flatten().filterNotNull().maxOfOrNull { it.id } ?: 0

    /**
     * 这个快照是否值得恢复。
     *
     * 空棋盘或非正的剩余时间说明这局其实没在进行（刚启动、或已结束），
     * 恢复它只会让玩家看到一个立刻就 game over 的残局。
     */
    val isRestorable: Boolean
        get() = remainingSeconds > 0 && board.grid.flatten().any { it != null }
}
