package top.windyvalley.magicsushi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.HistoryRepository
import top.windyvalley.magicsushi.data.PrefsRepository
import top.windyvalley.magicsushi.data.SnapshotRepository

/**
 * MagicSushiApp — Application 类，同时充当极简的依赖容器。
 *
 * ## 为什么依赖放在这里而不是 Activity
 *
 * [SoundPlayer] 与 [PrefsRepository] 此前是 `MainActivity` 的
 * `by lazy` 属性，而 `GameViewModel` **跨配置变更存活**（`by viewModels()`）。
 * 这导致旋屏后出现悬垂引用：
 *
 * ```
 * 1. Activity#1 创建 → SoundPlayer#1 → VM 持有 SoundPlayer#1
 * 2. 旋屏 → Activity#2 创建 → SoundPlayer#2
 * 3. Activity#1.onDestroy() → soundPlayer#1.release()
 * 4. VM 仍持有 SoundPlayer#1 → 后续 play() 全部静默失败（音效消失）
 * ```
 *
 * 把两者提升到 Application 作用域后，生命周期与进程一致，VM 持有的
 * 引用永远有效，且不会因为多个 Activity 实例而重复创建 SoundPool。
 *
 * ## 关于 release()
 *
 * [SoundPlayer.release] 不再由 Activity 调用 —— Application 的存活期
 * 等于进程存活期，进程被杀时 SoundPool 随之回收。刻意不在
 * `onTerminate()` 里 release：该回调在真机上不保证被调用。
 *
 * ## 演进方向
 *
 * 这是手写的最小依赖容器。若后续引入 Hilt/Koin，把这两个属性替换为
 * `@Singleton` provider 即可，调用点无需改动。
 */
class MagicSushiApp : Application() {

    /**
     * 应用级协程作用域，供仓库层的异步写入使用。
     *
     * 用 [SupervisorJob]：某次落盘失败不该连带取消其他写入。
     * 生命周期等于进程，故刻意不做 cancel —— 没有比进程更长的宿主可以
     * 承接它，而设置项的写入必须能在 Activity 销毁后完成（例如
     * 退出前保存最高分）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 应用级持久化仓库。设置类状态（静音、最高分）的唯一数据源。 */
    val prefsRepo: PrefsRepository by lazy { PrefsRepository(this, appScope) }

    /**
     * 历史记录仓库（DataStore）。
     *
     * 与 [prefsRepo] 分开是按**数据性质**划分，不再是存储机制的差异：
     * 这个存最多 50 条对局记录，那个存设置类标量。两者现在都是 DataStore
     * （完成后不再有 SharedPreferences）。
     */
    val historyRepo: HistoryRepository by lazy { HistoryRepository(this) }

    /**
     * 对局快照仓库（断点续玩）。
     *
     * 与上面两个仓库按**数据生命周期**划分：这个存「一局进行中的现场」，
     * 单份、恢复即删；那两个存永久档案。详见 [SnapshotRepository] 的类文档。
     */
    val snapshotRepo: SnapshotRepository by lazy { SnapshotRepository(this) }

    /** 应用级音效播放器。静音状态从 [prefsRepo] 读取，自身不持有该状态。 */
    val soundPlayer: SoundPlayer by lazy { SoundPlayer(this) }

    /**
     * 预热设置缓存。
     *
     * ## 为什么必须在这里，而且必须同步
     *
     * `PrefsRepository` 对外暴露同步读接口（`isMuted()` / `getHighScore()`），
     * 底层却是异步的 DataStore。两者之间靠内存缓存衔接，而缓存必须在
     * **首帧渲染之前**装载完真实值，否则 UI 会先画出占位的最高分 0
     * 再跳到真实值。
     *
     * `Application.onCreate` 是进程里最早能跑业务代码的时机，早于任何
     * Activity 和 Composable。这段阻塞被系统启动窗口完整遮住
     * （见 `MainActivity` 的 `setKeepOnScreenCondition`），玩家看不到白屏。
     */
    override fun onCreate() {
        super.onCreate()
        prefsRepo.warmUp()
    }
}
