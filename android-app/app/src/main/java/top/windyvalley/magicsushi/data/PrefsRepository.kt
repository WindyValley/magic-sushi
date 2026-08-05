package top.windyvalley.magicsushi.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地持久化仓库（SharedPreferences 实现）。
 *
 * 存储：
 * - 历史最高分（Int）
 * - 静音开关（Boolean）
 *
 * 用 StateFlow 暴露当前值，外部 ViewModel/UI 可观察变化。
 */
class PrefsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    // --- High Score ---

    private val _highScoreFlow = MutableStateFlow(getHighScore())
    val highScoreFlow: Flow<Int> = _highScoreFlow.asStateFlow()

    /**
     * 读取当前最高分（默认 0）。
     */
    fun getHighScore(): Int = prefs.getInt(KEY_HIGH_SCORE, 0)

    /**
     * 保存新最高分（**只升不降**，传入分数 ≤ 当前最高分时静默忽略）。
     */
    fun saveHighScore(score: Int) {
        val currentHigh = getHighScore()
        if (score > currentHigh) {
            prefs.edit().putInt(KEY_HIGH_SCORE, score).apply()
            _highScoreFlow.value = score
        }
    }

    // --- Mute ---

    private val _mutedFlow = MutableStateFlow(isMuted())
    val mutedFlow: Flow<Boolean> = _mutedFlow.asStateFlow()

    /**
     * 当前是否静音（默认 false）。
     */
    fun isMuted(): Boolean = prefs.getBoolean(KEY_MUTED, false)

    /**
     * 设置静音状态。
     */
    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        _mutedFlow.value = muted
    }

    companion object {
        private const val PREFS_NAME = "magic_sushi_prefs"
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_MUTED = "muted"
    }
}