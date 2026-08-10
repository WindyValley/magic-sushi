package top.windyvalley.magicsushi.engine

/**
 * 「破纪录」的唯一定义。
 *
 * ## 为什么这个判断要放在 engine 而不是 app
 *
 * 它曾经写在 `PrefsRepository.saveHighScore()` 里（`if (score > currentHigh)`）
 * —— Android 类内部的隐式规则，**无法单测**。提成纯函数后，这条判据同时被
 * [RoundSettlement]（结算与历史标记）和 [HighScoreDerivation]（从历史派生
 * 最高分）消费，只有一份定义、一处测试。
 *
 * ## 曾经还有一个 shouldCelebrateHighScore
 *
 * 它判断「最高分变化时该不该播庆祝动画」，服务于 `ScoreOverlay` 里最高分
 * 旁边闪一下「新纪录！」的效果。已连同那个效果一起删除 —— 原因见
 * `ScoreOverlay` 的类注释：最高分只在结算时变化，而那一刻结算面板全屏
 * 盖住了分数条，动画永远看不到。
 */
object HighScoreRules {

    /**
     * [candidate] 分是否构成新纪录（相对于 [currentHigh]）。
     *
     * ## 语义：严格大于，且必须是正分
     *
     * - **严格大于**：平纪录不算，也避免无意义的落盘
     * - **正分**：0 分意味着一次都没消除过，负分不该出现；两者都不构成成绩
     *
     * ## 为什么 `candidate > 0` 要内置在这里
     *
     * 它曾经**不在**这里，于是每个调用方自己补：`RoundSettlement` 用一个
     * 前置的 `score <= 0` 分支，`RoundSettlementTest` 的一致性用例写
     * `score > 0 && HighScoreRules.isNewRecord(...)`，[HighScoreDerivation]
     * 又在自己的版本里写了一遍 `candidate > 0 &&`。
     *
     * 同一个语义在三处手工重复，就是"记得补"的隐式契约 —— 与本项目栽过的
     * 双份真相同一形态，只是这次重复的是**判据**而不是数据。既然「0 分不算
     * 成绩」是「破纪录」定义的一部分，它就该长在定义里。
     *
     * @param candidate  待判定的分数（通常是本局得分）
     * @param currentHigh 当前最高分基准
     */
    fun isNewRecord(candidate: Int, currentHigh: Int): Boolean =
        candidate > 0 && candidate > currentHigh
}
