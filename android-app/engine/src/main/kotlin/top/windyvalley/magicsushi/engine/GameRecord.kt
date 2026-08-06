package top.windyvalley.magicsushi.engine

/**
 * 一局游戏的历史记录。
 *
 * ## 为什么放在 engine module
 *
 * 纯数据，不含任何 Android 类型。序列化格式由 app 层决定，引擎不关心 ——
 * 引擎只负责「一条记录长什么样」和「一批记录怎么排序裁剪」这两件事，
 * 两者都是可单测的纯逻辑。
 *
 * @property score           本局最终得分
 * @property timestampMillis 本局结束时刻（epoch millis）
 * @property isNewRecord     本局是否打破了当时的最高分
 */
data class GameRecord(
    val score: Int,
    val timestampMillis: Long,
    val isNewRecord: Boolean,
)

/**
 * 历史记录的排序与裁剪规则。
 *
 * ## 裁剪语义：保留分数最高的 N 条，不是最近 N 条
 *
 * 这是用户明确指定的规则，与常见的「最近 N 条」不同，容易被后来者
 * 想当然改错，故在此写明：
 *
 * - 列表按**分数降序**排列，同分时**新记录在前**（时间戳降序）
 * - 满 [MAX_RECORDS] 条后，分数低于第 N 名的新记录**根本不会被保存**
 *   （而不是挤掉一条旧的）
 *
 * 换句话说：这是一张排行榜，不是一本流水账。
 */
object GameHistory {

    /** 历史记录保留上限。 */
    const val MAX_RECORDS: Int = 50

    /**
     * 插入一条新记录，返回排序并裁剪后的新列表。
     *
     * 纯函数：不修改 [existing]，返回新列表。
     *
     * 排序键：分数降序 → 时间戳降序（同分新的在前）。
     * 裁剪：只保留前 [MAX_RECORDS] 条。若 [new] 的分数排不进前
     * [MAX_RECORDS] 名，返回的列表内容与 [existing] 相同（新记录被丢弃）。
     *
     * @param existing 现有记录，**不要求**已排序（本函数会整体重排）
     * @param new      新记录
     */
    fun insert(existing: List<GameRecord>, new: GameRecord): List<GameRecord> =
        (existing + new)
            .sortedWith(
                compareByDescending<GameRecord> { it.score }
                    .thenByDescending { it.timestampMillis }
            )
            .take(MAX_RECORDS)

    /**
     * 把一批记录按展示顺序排列（分数降序，同分新的在前），并裁剪到上限。
     *
     * 用于从持久层读出后的规范化 —— 存储层不保证顺序，也可能因为历史
     * 原因存了超过上限的条数。
     */
    fun normalize(records: List<GameRecord>): List<GameRecord> =
        records
            .sortedWith(
                compareByDescending<GameRecord> { it.score }
                    .thenByDescending { it.timestampMillis }
            )
            .take(MAX_RECORDS)
}
