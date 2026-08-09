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
     * 新分数是否应该覆盖已保存的最高分。
     *
     * 语义是**只升不降**：等于当前最高分也不写（避免无意义的落盘）。
     */
    fun isNewRecord(newScore: Int, savedHighScore: Int): Boolean =
        newScore > savedHighScore

    /**
     * 最高分从 [previousHigh] 变成 [newHigh] 时，是否应该播「破纪录」庆祝动画。
     *
     * ## 为什么不能只判断 `newHigh > previousHigh`
     *
     * 那是迁 DataStore 之前的写法，当时成立是因为 SharedPreferences 同步读
     * —— UI 拿到的第一个值就是磁盘上的真实值。
     *
     * DataStore 的读是异步的：UI 会先看到占位值 0，随后才被真实值（比如
     * 500）替换。这次 `0 → 500` 的跳变满足 `newHigh > previousHigh`，于是
     * **冷启动进游戏就会无故放一次庆祝动画**。
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
     * @param currentScore 本局当前得分。0 表示还没消除过任何一次。
     */
    fun shouldCelebrateHighScore(
        previousHigh: Int,
        newHigh: Int,
        currentScore: Int,
    ): Boolean = newHigh > previousHigh && currentScore > 0
}
