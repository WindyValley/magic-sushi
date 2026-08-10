package top.windyvalley.magicsushi.engine

/**
 * 中途退出时「有哪些选项可给玩家」的规则（纯逻辑）。
 *
 * ## 为什么需要这个类型
 *
 * 暂停面板的退出按钮曾经直接固定成「保留并返回首页」—— 一个按钮绑死一种
 * 语义。问题是这一局到底**值不值得保留**，取决于对局现场：
 *
 * | 现场 | 保留有意义吗 |
 * |---|---|
 * | 得了 800 分、还剩 40 秒 | 有 —— 玩家想接着玩 |
 * | 刚进游戏 0 分没动过     | 没有 —— 保留的是一个随时能重开的初始棋盘 |
 * | 时间已耗尽 / 空棋盘     | 没有 —— 恢复出来立刻 game over |
 * | 本局已结算入库          | 没有 —— 恢复会让玩家白玩一局（幂等保护不再入库）|
 *
 * 把判断做成纯函数，UI 才能据此决定确认弹窗长什么样：可保留时给玩家两条
 * 路（保留 / 结束），不可保留时不摆一个点了等于没点的按钮。
 *
 * ## 为什么放在 engine 而不是 app
 *
 * 同 [RoundSettlement]：`GameViewModel` 依赖 `Context` / `SoundPool`，单测要
 * Robolectric；这里是纯判断，放 engine 就能用普通 JUnit 覆盖边界。
 *
 * ## 与 [RoundSettlement] 的分工
 *
 * 两者都看 `score` 和 `alreadyRecorded`，但回答的是不同问题，**不要合并**：
 *
 * - [RoundSettlement]：这局结束了，成绩怎么算？（历史、最高分）
 * - 本对象：这局还没结束，能不能先放着？（快照）
 *
 * 一次退出会先问本对象拿到玩家的选择，选「结束本局」才轮到 [RoundSettlement]。
 */
object RoundExitOptions {

    /**
     * 这一局是否值得保留进度（即：确认弹窗该不该给出「保留」这条路）。
     *
     * ## 0 分为什么不给保留
     *
     * 0 分意味着玩家一次消除都没完成，快照里除了一个初始棋盘什么都没有 ——
     * 而初始棋盘随时能重开一个。给出「保留」只会让菜单多一个「继续上局」，
     * 点进去回到的现场与「开始新游戏」几乎无差别，纯粹是噪音。
     *
     * 所以 0 分退出直接按**丢弃本局**处理（用户决定）。注意「丢弃」在这里
     * 指不留快照，而非「成绩作废」—— 0 分本来就不入库（见 [RoundSettlement]）。
     *
     * ## 判据与 [GameSnapshot.isRestorable] 的关系
     *
     * `remainingSeconds` / `boardHasTiles` 两项与 [GameSnapshot.isRestorable]
     * 刻意保持一致：若这里放行、存盘时却被 `isRestorable` 挡掉，玩家会遇到
     * 「点了保留，菜单却没有『继续上局』」—— 最糟的一类不一致。
     *
     * @param score            本局当前得分。
     * @param remainingSeconds 剩余秒数。
     * @param boardHasTiles    棋盘上是否还有 tile。
     * @param alreadyRecorded  本局是否已结算入库。
     */
    fun canKeepProgress(
        score: Int,
        remainingSeconds: Int,
        boardHasTiles: Boolean,
        alreadyRecorded: Boolean,
    ): Boolean =
        score > 0 &&
            !alreadyRecorded &&
            remainingSeconds > 0 &&
            boardHasTiles
}
