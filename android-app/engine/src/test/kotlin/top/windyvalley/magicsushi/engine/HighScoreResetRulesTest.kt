package top.windyvalley.magicsushi.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重置最高分」与「只升不降守卫」的关系测试。
 *
 * ## 为什么这个测试值得存在
 *
 * `PrefsRepository.saveHighScore()` 有一道同步守卫：
 *
 *     if (!HighScoreRules.isNewRecord(score, highScore.value)) return
 *
 * 于是 `saveHighScore(0)` **会被静默挡掉** —— 设置页的「清空最高分」若图省事
 * 复用它，表现就是点了没反应，而且不报错。这是本项目已经栽过一次的形态：
 * 最高分那个 bug（24f7863 / decc959）也是"写入路径看起来调了、实际没生效"。
 *
 * `resetHighScore()` 之所以是独立方法而非给 saveHighScore 加 force 参数，
 * 就是为了不让那道守卫变成可选的。这里用纯函数把"守卫确实会挡住 0"这个
 * 前提钉住 —— 一旦哪天有人放宽 isNewRecord（比如允许等值写入），
 * 本测试会失败，提醒他重新检查重置路径。
 *
 * ⚠️ 本测试覆盖的是**规则**，不是 DataStore 的落盘。`resetHighScore()` 是
 * suspend + Android 依赖，要 Robolectric 才能测，成本不划算；它的正确性靠
 * "绕开守卫直接 set" 这个实现足够简单 + 真机验证。
 */
class HighScoreResetRulesTest {

    @Test
    fun `守卫会挡住 0 分写入 —— 所以重置不能复用 saveHighScore`() {
        // 这是 resetHighScore 存在的全部理由。若这条变红，说明守卫放宽了，
        // 需要重新确认重置路径还对不对。
        assertFalse(HighScoreRules.isNewRecord(0, 500))
    }

    @Test
    fun `已经是 0 时写 0 也被挡住`() {
        // 连"从 0 重置到 0"都进不去，可见守卫与重置语义完全不兼容。
        assertFalse(HighScoreRules.isNewRecord(0, 0))
    }

    @Test
    fun `重置后任何正分都能重新成为纪录`() {
        // 重置的目的：让下一局的成绩能重新破纪录。基准归零后 1 分也算。
        assertTrue(HighScoreRules.isNewRecord(1, 0))
        assertTrue(HighScoreRules.isNewRecord(9999, 0))
    }

    @Test
    fun `重置不改变只升不降在正常路径上的语义`() {
        // 确认这次新增的重置入口没有动摇原有规则。
        assertTrue(HighScoreRules.isNewRecord(600, 500))
        assertFalse(HighScoreRules.isNewRecord(500, 500))
        assertFalse(HighScoreRules.isNewRecord(400, 500))
    }
}
