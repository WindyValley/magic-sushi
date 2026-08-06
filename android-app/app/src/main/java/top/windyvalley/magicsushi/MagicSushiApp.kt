package top.windyvalley.magicsushi

import android.app.Application
import top.windyvalley.magicsushi.audio.SoundPlayer
import top.windyvalley.magicsushi.data.PrefsRepository

/**
 * MagicSushiApp — Application 类，同时充当极简的依赖容器。
 *
 * ## 为什么依赖放在这里而不是 Activity（FIX_PLAN P0-2）
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

    /** 应用级持久化仓库。设置类状态（静音、最高分）的唯一数据源。 */
    val prefsRepo: PrefsRepository by lazy { PrefsRepository(this) }

    /** 应用级音效播放器。静音状态从 [prefsRepo] 读取，自身不持有该状态。 */
    val soundPlayer: SoundPlayer by lazy { SoundPlayer(this) }
}
