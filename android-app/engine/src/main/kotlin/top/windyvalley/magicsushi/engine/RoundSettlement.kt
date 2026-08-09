package top.windyvalley.magicsushi.engine

/**
 * 一局游戏的结算规则（纯逻辑）。
 *
 * ## 为什么需要这个类型
 *
 * 「一局结束」在 `GameViewModel` 里有三个入口：
 *
 * | 入口 | 触发 |
 * |---|---|
 * | `onGameOver` | 倒计时归零 |
 * | `onRestart`  | 玩家点重开 |
 * | `onQuit`     | 玩家点退出 |
 *
 * 早期实现让每个入口自己决定做哪些收尾动作，结果 `saveHighScore()` 只写在
 * `onGameOver` 里，而历史入库 `recordCurrentRound()` 三处都调。于是玩家
 * **正常退出或点重开时，成绩进了历史但最高分从不更新**，历史里的「新纪录」
 * 标记也永远是 false —— 因为 `isNewRecord` 同样只在 `onGameOver` 里被赋值。
 *
 * 这不是某一行代码写错，是「结算」这件事没有单一表达。把它做成纯函数后，
 * 三个入口只能整体调用它，不存在「记得做 A 忘了做 B」的空间。
 *
 * ## 为什么放在 engine 而不是 app
 *
 * `GameViewModel` 依赖 `Context` / `SoundPool`，单测要 Robolectric。而这里
 * 的规则是纯算术，放 engine 就能用普通 JUnit 覆盖 —— 上述 bug 之所以能溜到
 * 真机，正是因为 app 模块当时只有一个导航测试，VM 完全没有覆盖。
 */
object RoundSettlement {

    /**
     * 一局的结算结果。
     *
     * @property shouldRecord   是否应写入历史记录。
     * @property isNewRecord    是否破纪录 —— 同时决定要不要持久化最高分、
     *                          发庆祝事件、以及历史记录里的标记。
     * @property newHighScore   结算后的最高分（未破纪录时等于原值）。
     */
    data class Outcome(
        val shouldRecord: Boolean,
        val isNewRecord: Boolean,
        val newHighScore: Int,
    )

    /**
     * 结算一局。
     *
     * @param score           本局得分。
     * @param savedHighScore  当前已保存的最高分。
     * @param alreadyRecorded 本局是否已经结算过（幂等保护）。
     *                        game over 后玩家再点退出属于这种情况。
     */
    fun settle(
        score: Int,
        savedHighScore: Int,
        alreadyRecorded: Boolean,
    ): Outcome {
        // 已结算过 → 什么都不做。重复结算会让同一局在历史里出现两次，
        // 也会把「新纪录」事件重复发出去。
        if (alreadyRecorded) {
            return Outcome(
                shouldRecord = false,
                isNewRecord = false,
                newHighScore = savedHighScore,
            )
        }

        // 0 分不入库：玩家开局就退出不该在历史里留一条 0 分记录。
        // 注意这与「不算成绩」不同 —— 0 分也不可能破纪录（只升不降），
        // 所以最高分保持原值即可。
        if (score <= 0) {
            return Outcome(
                shouldRecord = false,
                isNewRecord = false,
                newHighScore = savedHighScore,
            )
        }

        val isNew = HighScoreRules.isNewRecord(score, savedHighScore)
        return Outcome(
            shouldRecord = true,
            isNewRecord = isNew,
            newHighScore = if (isNew) score else savedHighScore,
        )
    }
}
