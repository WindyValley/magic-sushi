package top.windyvalley.magicsushi.engine

/**
 * 最高分相关的纯规则。
 *
 * ## 为什么这些判断要从 app 模块搬到 engine（FIX_PLAN D8）
 *
 * 迁 DataStore 之前，「是否破纪录」写在 `PrefsRepository.saveHighScore()`
 * 里（`if (score > currentHigh)`），「是否放庆祝动画」写在 `ScoreOverlay`
 * 的 `LaunchedEffect` 里。两处都是 Android 类内部的隐式规则，**无法单测**。
 *
 * 迁移把持久化改成异步后，这两条规则同时变得更容易出错（见
 * [shouldCelebrateHighScore] 的说明），所以先把它们提成纯函数、补上测试，
 * 再动存储层。顺序反了就等于在没有安全网的地方改地基。
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

    /**
     * 最高分从 [previousHigh] 变成 [newHigh] 时，是否应该播「破纪录」庆祝动画。
     *
     * ## 为什么不能只判断 `newHigh > previousHigh`
     *
     * 最高分是**异步**装载的 —— UI 会先看到占位值 0，随后才被真实值（比如
     * 500）替换。这次 `0 → 500` 的跳变满足 `newHigh > previousHigh`，于是
     * **冷启动进游戏就会无故放一次庆祝动画**。
     *
     * ⚠️ 这个坑在最高分改为「从历史记录派生」（见 [HighScoreDerivation]）
     * 之后**依然存在**：数据源虽然从 DataStore 的 prefs 换成了历史记录的
     * `Flow`，但异步这一点没变，冷启动照样先发 0 再发真实值。
     *
     * 区分真假的关键不在最高分本身，而在**本局是否得过分**：
     *
     * | 场景 | previousHigh | newHigh | currentScore | 结果 |
     * |---|---|---|---|---|
     * | 冷启动异步加载 | 0 | 500 | 0（还没开局） | 不庆祝 |
     * | 首局破纪录（史上第一局） | 0 | 300 | 300 | 庆祝 |
     * | 正常破纪录 | 500 | 800 | 800 | 庆祝 |
     *
     * 判据：最高分确实上升了，**且**本局已经得过分。异步加载发生在开局前，
     * 那时 `currentScore == 0`，因此能被干净地排除。
     *
     * ## 为什么不直接复用 [isNewRecord]
     *
     * 两者问的不是同一件事。[isNewRecord] 问「这个成绩够不够格成为纪录」，
     * 输入是**一局的得分**；本函数问「最高分这次变化值不值得庆祝」，输入是
     * **最高分的前后两个值**。冷启动那次 0 → 500 里，500 并不是谁的本局
     * 得分，用 isNewRecord 去套会得到"是新纪录"的错误结论。
     *
     * @param currentScore 本局当前得分。0 表示还没消除过任何一次。
     */
    fun shouldCelebrateHighScore(
        previousHigh: Int,
        newHigh: Int,
        currentScore: Int,
    ): Boolean = newHigh > previousHigh && currentScore > 0
}
