package top.windyvalley.magicsushi.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import top.windyvalley.magicsushi.R

/**
 * 音效播放器（SoundPool 实现）。
 *
 * 4 种音效对应 4 个预加载的 OGG 资源。
 *
 * ## 静音状态不由本类持有
 *
 * 早期版本这里有一份 `_mutedFlow: MutableStateFlow<Boolean>`，于是同一个
 * "是否静音"被存了三份：`PrefsRepository`（持久化 + Flow）、本类、
 * `GameState.isMuted`。`toggleMute()` 必须手工同步三处，漏掉任何一处就
 * 静默不一致。
 *
 * 现在改为由外部注入一个读取函数（[bindMutedProvider]），唯一数据源是
 * `PrefsRepository`。本类只负责"发声"这一件事。
 */
class SoundPlayer(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var swapId: Int = 0
    private var matchId: Int = 0
    private var comboId: Int = 0
    private var tickId: Int = 0

    private var isLoaded = false

    /**
     * 静音状态的读取入口。默认"不静音"，由 `GameViewModel` 在 init 中
     * 通过 [bindMutedProvider] 绑定到 `PrefsRepository.isMuted()`。
     *
     * 用函数而非字段：保证每次播放都读到最新值，不需要任何同步逻辑。
     */
    private var mutedProvider: () -> Boolean = { false }

    init {
        initialize()
    }

    /**
     * 绑定静音状态的数据源。应在应用启动早期调用一次（`GameViewModel.init`）。
     *
     * @param provider 返回当前是否静音。通常是 `prefsRepo::isMuted`。
     */
    fun bindMutedProvider(provider: () -> Boolean) {
        mutedProvider = provider
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
        if (mutedProvider() || !isLoaded) return
        soundPool?.play(swapId, 1f, 1f, 1, 0, 1f)
    }

    fun playMatch() {
        if (mutedProvider() || !isLoaded) return
        soundPool?.play(matchId, 1f, 1f, 1, 0, 1f)
    }

    fun playCombo() {
        if (mutedProvider() || !isLoaded) return
        soundPool?.play(comboId, 1f, 1f, 1, 0, 1f)
    }

    fun playTick() {
        if (mutedProvider() || !isLoaded) return
        soundPool?.play(tickId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
    }
}
