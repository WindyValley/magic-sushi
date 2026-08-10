package top.windyvalley.magicsushi.engine

/**
 * 从历史记录派生最高分。
 *
 * ## 为什么要派生而不是单独存一份
 *
 * 最高分曾经是独立持久化的（`PrefsRepository.highScore`），于是同一个事实
 * 有两个存储位置：
 *
 *     prefs.highScore  ──┐
 *                        ├── 必须永远相等，靠调用方记得同时写
 *     max(history.score) ─┘
 *
 * 「必须永远相等 + 靠调用方记得」就是双份真相，本项目已经为它付过两次代价：
 *
 *  1. `saveHighScore` 只在 `onGameOver` 里调，而历史入库三处都调 ——
 *     正常退出/点重开时历史有成绩、最高分却是 0（修法：[RoundSettlement]）
 *  2. 热缓存没同步更新，写完立刻读还是旧值 —— 最高分表现为"不更新"
 *
 * 两次的形态相同：不是某一行写错，而是同一个事实有两条独立的写入路径。
 * 派生把它降为**一份**真相 —— 历史记录是唯一数据源，最高分是它的函数。
 * 从此不存在"忘了同步"的空间，因为没有第二处可写。
 *
 * ## 这个派生为什么是正确的（不变式）
 *
 * 依赖 [RoundSettlement.settle] 的两条性质：
 *
 *     score <= 0  →  shouldRecord=false, isNewRecord=false   （都不写）
 *     score >  0  →  shouldRecord=true,  isNewRecord=score>high
 *
 * 即 **`isNewRecord=true` 必然伴随 `shouldRecord=true`** —— 不存在"破了纪录
 * 但没进历史"的组合。所以任何曾经成为最高分的成绩，都在历史里。
 *
 * 再加上 [GameHistory] 按**分数降序**裁剪到 [GameHistory.MAX_RECORDS] 条，
 * 最高分那条永远排第一，不可能被淘汰。
 *
 * 两点合起来：`max(history.score) == 曾经的最高分`，恒等。
 *
 * ⚠️ 若将来改动 [RoundSettlement] 让某条路径"破纪录但不入库"，或把历史
 * 改成按时间裁剪（新的挤掉旧的），这个不变式就断了 —— 届时最高分会随着
 * 高分局被淘汰而**变小**。`HighScoreDerivationTest` 里有针对这两点的测试。
 *
 * ## 附带解决的问题
 *
 * - 「清空记录」不再需要单独重置最高分：历史清空后 `max` 自然是 0
 * - 不再需要 `resetHighScore()` 绕过"只升不降"守卫这种别扭的写法
 * - 不再需要"只升不降"守卫本身：`max` 天然单调，无法被低分覆盖
 */
object HighScoreDerivation {

    /**
     * 历史记录中的最高分。空历史返回 0。
     *
     * @param records 历史记录，**不要求**已排序（本函数自己取 max）。
     *                刻意不假设"第一条就是最高分"—— 那会让本函数依赖调用方
     *                有没有先 normalize，又是一个"记得做"的隐式契约。
     */
    fun highScoreOf(records: List<GameRecord>): Int =
        records.maxOfOrNull { it.score } ?: 0

    /**
     * 在 [records] 的基础上，[candidate] 分是否构成新纪录。
     *
     * 语义与旧的 `HighScoreRules.isNewRecord` 一致（严格大于），只是基准从
     * "存着的最高分"换成"历史里的最高分"。
     *
     * 0 分和负分永远不算 —— 与 [RoundSettlement] 的 `score <= 0` 分支一致。
     */
    fun isNewRecord(candidate: Int, records: List<GameRecord>): Boolean =
        candidate > 0 && candidate > highScoreOf(records)
}
