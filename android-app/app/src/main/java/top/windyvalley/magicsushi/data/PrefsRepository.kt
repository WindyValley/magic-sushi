package top.windyvalley.magicsushi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.windyvalley.magicsushi.engine.HighScoreRules

/**
 * 设置类持久化仓库（DataStore 实现，FIX_PLAN D8）。
 *
 * 存储：历史最高分、静音开关。
 *
 * ## 为什么从 SharedPreferences 迁过来
 *
 * 旧实现在构造函数里 `context.getSharedPreferences(...)`，那是**主线程
 * 同步读盘**；且 `MutableStateFlow(getHighScore())` 又各读一次。触发链路是
 * `MainActivity` → `GameViewModelFactory` → `by lazy` → 构造即读盘。
 *
 * 迁到 DataStore 后读写都在 IO 线程，且与 [HistoryRepository] 统一了范式
 * （此前同一个 app 里两套持久化机制并存）。
 *
 * ## 关键约束：同步读接口必须保留
 *
 * DataStore 只提供 `Flow` 和 `suspend`，但有个调用点**不能改成 suspend**：
 *
 * ```kotlin
 * soundPlayer.bindMutedProvider(prefsRepo::isMuted)   // 每次播音效都同步调
 * ```
 *
 * 音效播放在渲染热路径上，不可能为读一个 Boolean 挂起。此外 ViewModel 的
 * `_state` 初值和 `startGame()` 也需要同步取值。
 *
 * 所以设计是 **DataStore 落盘 + 内存热缓存**：
 *
 * - 落盘/订阅走 DataStore（异步、事务、损坏恢复）
 * - [isMuted] / [getHighScore] 读内存缓存（同步、无锁、纳秒级）
 * - 缓存由 [warmUp] 预热，之后由 DataStore 的 Flow 单向刷新
 *
 * 于是原有 5 个同步调用点**一处都不用改**。
 *
 * ## 为什么 warmUp 用 runBlocking，这不算把问题搬回来
 *
 * D8 要消除的是**散落、重复、不可控**的主线程 IO —— 旧实现里任何一次
 * `getXxx()` 都可能触发读盘。而 [warmUp] 是：一次性的、边界明确的、
 * 且被系统启动窗口完整遮住的（见 `MainActivity` 的
 * `setKeepOnScreenCondition`）。
 *
 * 代价换来的是「UI 永远不会看到占位值」——没有它，冷启动会先渲染
 * 最高分 0 再跳到真实值，玩家看到数字闪变。
 *
 * ## 加新设置字段怎么做（**扩展指引**）
 *
 * 旧实现每加一个字段要写 4 段样板：常量、`getXxx()`、`setXxx()`、
 * `MutableStateFlow` + 对外 `Flow`，且极易漏掉 `_xxxFlow.value = x`
 * 导致 UI 不刷新（D5 修过这类 bug）。
 *
 * 现在只需两步：
 *
 * ```kotlin
 * // 1. 声明键（伴生对象里）
 * private val KEY_VOLUME = intPreferencesKey("volume")
 *
 * // 2. 声明字段（一行）
 * val volume = setting(KEY_VOLUME, default = 100)
 * ```
 *
 * 读 `volume.value`（同步）、订阅 `volume.flow`、写 `volume.set(80)`。
 * 缓存同步、Flow 投影、预热都由 [Setting] 自动接管，没有手工同步的机会。
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "magic_sushi_settings",
    produceMigrations = { ctx ->
        // 老玩家的最高分/静音存在 magic_sushi_prefs.xml 里。不迁移就等于
        // 升级即清零 —— 对玩了很久的人是实打实的数据丢失。
        //
        // SharedPreferencesMigration 只在 DataStore 首次创建时跑一次，
        // 迁完自动记账，不会重复执行。keysToMigrate 显式列出，避免把
        // 无关的历史遗留键一并搬进来。
        listOf(
            SharedPreferencesMigration(
                context = ctx,
                sharedPreferencesName = LEGACY_PREFS_NAME,
                keysToMigrate = setOf(LEGACY_KEY_HIGH_SCORE, LEGACY_KEY_MUTED),
            )
        )
    }
)

private const val LEGACY_PREFS_NAME = "magic_sushi_prefs"
private const val LEGACY_KEY_HIGH_SCORE = "high_score"
private const val LEGACY_KEY_MUTED = "muted"

class PrefsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val dataStore get() = context.settingsDataStore

    /**
     * 读流。DataStore 损坏/IO 异常时回落空配置而非抛出 ——
     * 设置项丢了顶多回到默认值，为它让游戏起不来是本末倒置
     * （与 [HistoryRepository] 的策略一致）。
     */
    private val prefsFlow: Flow<Preferences> = dataStore.data
        .catch {
            android.util.Log.w("PrefsRepository", "读取设置失败，按默认值处理", it)
            emit(emptyPreferences())
        }

    /**
     * 一个设置项：同步读 + Flow 订阅 + 异步写，三者共用一份缓存。
     *
     * 泛型持有 DataStore 的 `Preferences.Key<T>`，所以新增字段无需
     * 重复任何读写样板（见类注释的扩展指引）。
     */
    inner class Setting<T>(
        private val key: Preferences.Key<T>,
        private val default: T,
    ) {
        private val _flow = MutableStateFlow(default)

        /** 供 UI 订阅。 */
        val flow: Flow<T> = _flow.asStateFlow()

        /**
         * 当前值（**同步**，读内存缓存）。
         *
         * [warmUp] 之前返回 [default]。启动窗口保证 UI 不会在预热完成前
         * 渲染，因此实践中读到的总是真实值。
         */
        val value: T get() = _flow.value

        /**
         * 写入并落盘。
         *
         * **缓存同步更新，落盘异步。** 这是热缓存必须有的语义：
         * 调用方紧接着 `value` 读回来必须看到新值。
         *
         * ⚠️ 曾经这里只 `dataStore.edit{}`、缓存等 [collectInto] 的订阅回灌，
         * 结果 `saveHighScore()` 之后立刻 `getHighScore()` 读到的还是旧值
         * —— 最高分表现为「不更新」。订阅回灌是**兜底**（同步别处的写入），
         * 不能当作本次写入的生效路径。
         */
        suspend fun set(newValue: T) {
            _flow.value = newValue
            dataStore.edit { it[key] = newValue }
        }

        /** 从 [Preferences] 快照取值，缺失则取默认值。 */
        fun read(prefs: Preferences): T = prefs[key] ?: default

        /** 把 DataStore 的变更单向投影进缓存。 */
        internal fun collectInto(scope: CoroutineScope) {
            scope.launch {
                prefsFlow.map { read(it) }.collect { _flow.value = it }
            }
        }

        internal fun seed(prefs: Preferences) {
            _flow.value = read(prefs)
        }

        /**
         * 只更新内存缓存，不落盘。
         *
         * 供 [saveHighScore] 这类「先做同步守卫、再异步落盘」的写入路径使用：
         * 缓存必须在函数返回前生效，否则紧随其后的同步读会拿到旧值。
         */
        internal fun setCachedValue(newValue: T) {
            _flow.value = newValue
        }

        /** 在一次 `edit` 事务里就地改值，供 [Setting] 之外的组合写入复用。 */
        internal fun put(prefs: MutablePreferences, newValue: T) {
            prefs[key] = newValue
        }

        internal val rawKey: Preferences.Key<T> get() = key
    }

    // ========================================================================
    // 设置项声明 —— 加字段只需在这里加一行
    // ========================================================================

    /** 历史最高分。写入请走 [saveHighScore]（含只升不降规则）。 */
    val highScore = Setting(KEY_HIGH_SCORE, default = 0)

    /** 静音开关。 */
    val muted = Setting(KEY_MUTED, default = false)

    private val allSettings = listOf(highScore, muted)

    // ========================================================================
    // 预热
    // ========================================================================

    @Volatile
    private var _isReady: Boolean = false

    /**
     * 缓存是否已装载真实值。供启动窗口判断何时可以放行
     * （`MainActivity.setKeepOnScreenCondition { !prefsRepo.isReady }`）。
     */
    val isReady: Boolean get() = _isReady

    /**
     * 同步预热缓存并启动订阅。**必须在 UI 首帧之前调用一次**
     * （`MagicSushiApp.onCreate`）。
     *
     * 这里的 `runBlocking` 是有意的、有界的一次性阻塞，详见类注释。
     * 首次运行还会触发 `SharedPreferencesMigration` 搬运老数据。
     */
    fun warmUp() {
        if (_isReady) return
        val snapshot = runBlocking { prefsFlow.first() }
        allSettings.forEach { it.seed(snapshot) }
        allSettings.forEach { it.collectInto(scope) }
        _isReady = true
    }

    // ========================================================================
    // 兼容旧调用点的同步接口
    //
    // 这几个方法让 GameViewModel / SoundPlayer 的 5 处调用无需改动。
    // ========================================================================

    /** 当前最高分（同步）。 */
    fun getHighScore(): Int = highScore.value

    /** 当前是否静音（同步）。 */
    fun isMuted(): Boolean = muted.value

    /** 供 UI 订阅的最高分流。 */
    val highScoreFlow: Flow<Int> get() = highScore.flow

    /** 供 UI 订阅的静音流。 */
    val mutedFlow: Flow<Boolean> get() = muted.flow

    /**
     * 保存新最高分（**只升不降**，规则见
     * [HighScoreRules.isNewRecord]）。
     *
     * 缓存**同步**更新，落盘异步 —— 调用方（`GameViewModel.onGameOver`）
     * 紧接着就会读 `getHighScore()`，等协程跑完再更新会让最高分表现为
     * 「不更新」：弹窗里是对的（那读的是 state），但回菜单再进或杀进程
     * 重开就丢了。
     *
     * 事务内再比较一次：调用方的判断基于稍旧的缓存值，而事务内读到的是
     * 权威值，可防并发写覆盖（例如 game over 与 quit 几乎同时触发）。
     */
    fun saveHighScore(score: Int) {
        // 同步守卫 + 同步更新缓存：只升不降的语义在内存里也必须成立。
        if (!HighScoreRules.isNewRecord(score, highScore.value)) return
        highScore.setCachedValue(score)

        scope.launch {
            dataStore.edit { prefs ->
                val current = highScore.read(prefs)
                if (HighScoreRules.isNewRecord(score, current)) {
                    highScore.put(prefs, score)
                }
            }
        }
    }

    /**
     * 设置静音状态。
     *
     * 缓存同步更新（[Setting.set] 保证），落盘异步。这一点对
     * `toggleMute()` 是必需的 —— 它按 `!isMuted()` 计算目标值，
     * 若缓存滞后，连续两次切换会读到同一个旧值而失效。
     */
    fun setMuted(isMuted: Boolean) {
        muted.setCachedValue(isMuted)
        scope.launch { muted.set(isMuted) }
    }

    /**
     * 重置最高分为 0。
     *
     * ## 为什么不能复用 [saveHighScore]
     *
     * 那个方法有「只升不降」守卫（[HighScoreRules.isNewRecord]），
     * `saveHighScore(0)` 会被守卫直接挡掉、静默无效。重置是一个**语义上
     * 不同的动作** —— 玩家显式要求清空，而非用新成绩去刷纪录，所以它需要
     * 独立的入口而不是给 saveHighScore 加个 force 参数（那会让守卫变成
     * 可选的，早晚被误用）。
     *
     * ## 为什么是 suspend
     *
     * 与 [saveHighScore]（同步返回、异步落盘）刻意不同：设置页面清空数据后
     * 要给玩家明确反馈，UI 需要能等它真的落盘。而 saveHighScore 的调用点
     * 在结算路径上，不能被挂起。
     */
    suspend fun resetHighScore() {
        highScore.set(0)
    }

    /**
     * 挂起直到此前发起的写入都已落盘。
     *
     * 供「退出前必须确保成绩保存」这类场景使用（`GameViewModel.onQuit`）。
     *
     * 实现依赖 DataStore 的 `edit` 内部串行化：发起一次读并等它完成，
     * 说明排在它之前的写入都已结束。这比暴露 Job 列表简单，也不用担心
     * 漏等某个协程。
     */
    suspend fun awaitPendingWrites() {
        dataStore.data.first()
    }

    companion object {
        // 键名与旧 SharedPreferences 保持一致，让 SharedPreferencesMigration
        // 能原样搬过来（它按 key 字符串匹配）。改名就等于丢老数据。
        private val KEY_HIGH_SCORE = intPreferencesKey(LEGACY_KEY_HIGH_SCORE)
        private val KEY_MUTED = booleanPreferencesKey(LEGACY_KEY_MUTED)
    }
}
