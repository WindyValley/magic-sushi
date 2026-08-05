package top.windyvalley.magicsushi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.windyvalley.magicsushi.R

/**
 * 音效播放器（SoundPool 实现）。
 *
 * 4 种音效对应 4 个预加载的 OGG 资源。
 * 支持静音切换。
 */
class SoundPlayer(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var swapId: Int = 0
    private var matchId: Int = 0
    private var comboId: Int = 0
    private var tickId: Int = 0

    private var isLoaded = false

    private val _mutedFlow = MutableStateFlow(false)
    val mutedFlow = _mutedFlow.asStateFlow()

    init {
        initialize()
    }

    private fun initialize() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attributes)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        // 检查是否全部加载完成
                        if (swapId > 0 && matchId > 0 && comboId > 0 && tickId > 0) {
                            isLoaded = true
                        }
                    }
                }

                swapId = pool.load(context, R.raw.sfx_swap, 1)
                matchId = pool.load(context, R.raw.sfx_match, 1)
                comboId = pool.load(context, R.raw.sfx_combo, 1)
                tickId = pool.load(context, R.raw.sfx_tick, 1)
            }
    }

    fun playSwap() {
        if (_mutedFlow.value || !isLoaded) return
        soundPool?.play(swapId, 1f, 1f, 1, 0, 1f)
    }

    fun playMatch() {
        if (_mutedFlow.value || !isLoaded) return
        soundPool?.play(matchId, 1f, 1f, 1, 0, 1f)
    }

    fun playCombo() {
        if (_mutedFlow.value || !isLoaded) return
        soundPool?.play(comboId, 1f, 1f, 1, 0, 1f)
    }

    fun playTick() {
        if (_mutedFlow.value || !isLoaded) return
        soundPool?.play(tickId, 1f, 1f, 1, 0, 1f)
    }

    fun setMuted(muted: Boolean) {
        _mutedFlow.value = muted
    }

    fun isMuted(): Boolean = _mutedFlow.value

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
    }
}