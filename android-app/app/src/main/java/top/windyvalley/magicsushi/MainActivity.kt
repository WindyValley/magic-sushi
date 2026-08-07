package top.windyvalley.magicsushi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import top.windyvalley.magicsushi.ui.navigation.AppScreen
import top.windyvalley.magicsushi.ui.screen.GameScreen
import top.windyvalley.magicsushi.ui.screen.HistoryScreen
import top.windyvalley.magicsushi.ui.screen.MainMenuScreen
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.viewmodel.GameViewModel
import top.windyvalley.magicsushi.viewmodel.GameViewModelFactory
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    // FIX_PLAN P0-2：依赖来自 Application 作用域，不再由 Activity 创建。
    // 此前 SoundPlayer 是本类的 by lazy 属性，而 GameViewModel 跨配置变更
    // 存活 —— 旋屏后旧 Activity 的 onDestroy() 会 release 掉 VM 仍在使用
    // 的 SoundPool，导致音效永久失效。
    private val app: MagicSushiApp
        get() = application as MagicSushiApp

    private val viewModel: GameViewModel by viewModels {
        GameViewModelFactory(app.prefsRepo, app.historyRepo, app.soundPlayer)
    }

    /**
     * 当前是否停留在游戏屏。
     *
     * 供生命周期观察者判断「回到前台是否该恢复倒计时」。
     *
     * ## 为什么这个状态在 Activity 而不只在 Composable 里
     *
     * 生命周期观察者在 `onCreate` 里注册，运行在 composition 之外 ——
     * 它读不到 `setContent` 内部的 `rememberSaveable` 变量。而它恰恰需要
     * 知道当前在哪个屏：玩家在菜单时切后台再回来，不该触发 `onResume()`
     * 让倒计时空跑（详见 [GameViewModel.onSystemResume]）。
     *
     * 于是导航状态有两个持有者：composition 内的 `screen`（驱动渲染）
     * 和这个字段（供 composition 外的观察者查询）。后者由前者的
     * `LaunchedEffect` 单向同步，**不反向驱动 UI**，避免两个真相互相打架。
     */
    private var isOnGameScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 观察 Activity 生命周期，让 VM 知道何时暂停/恢复
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // 切后台一律暂停：即使在菜单屏，暂停一个没在跑的 timer 也是
                // 无害的 no-op（onPause 只是置 phase + cancel）。
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()

                // 恢复要看当前在哪个屏 —— 在菜单/历史屏时不能恢复倒计时。
                Lifecycle.Event.ON_RESUME -> viewModel.onSystemResume(isOnGameScreen)

                else -> { /* no-op for other events */ }
            }
        })

        setContent {
            MagicSushiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        viewModel = viewModel,
                        historyRecords = app.historyRepo.records,
                        onScreenChanged = { isOnGameScreen = it is AppScreen.Game },
                        onExitApp = {
                            // 退出游戏 = 真正结束进程（用户明确要求）。
                            //
                            // finishAffinity() 清掉整个 task（不只当前 Activity），
                            // exitProcess(0) 确保进程真的终止 —— 只调 finish 的话
                            // 进程会留在后台缓存，玩家从最近任务点回来会看到旧状态。
                            finishAffinity()
                            exitProcess(0)
                        },
                    )
                }
            }
        }
    }

    // 刻意不再重写 onDestroy() 调用 soundPlayer.release()：
    // SoundPlayer 现在是 Application 作用域的单例，被多个 Activity 实例
    // 共享。在此处 release 会让旋屏后存活的 ViewModel 拿到已销毁的
    // SoundPool（FIX_PLAN P0-2）。其生命周期随进程结束自然回收。
}

/**
 * 应用根 Composable：三屏导航（批次 C，Task C4）。
 *
 * ## 为什么导航状态用 rememberSaveable
 *
 * `remember` 在旋屏后会丢失，玩家会从游戏屏被弹回菜单 —— 而 `GameViewModel`
 * 跨配置变更存活，对局还在，只是看不见了。`rememberSaveable` 让屏幕状态与
 * VM 的存活期对齐。
 *
 * `AppScreen` 的成员都是 `data object`，Compose 能直接存进 Bundle
 * （走 Parcelable/Serializable 之外的默认 Saver 时 object 单例天然可省），
 * 无需自定义 Saver。
 *
 * ## 为什么 Game 屏用 startGame() 而不是依赖 phase 判断
 *
 * 改动前 `GameScreen` 内部有 `LaunchedEffect(Unit) { if (phase == IDLE) startGame() }`。
 * 这在单屏时代够用，但导航之后有个漏洞：从游戏屏退到菜单再进来，phase
 * 可能是 GAME_OVER 或 PAUSED（不是 IDLE），于是**不会开新局** ——
 * 玩家点【开始游戏】却看到上一局的死棋盘和结束弹窗。
 *
 * 现在改成：进入 Game 屏时无条件 `startGame()`。语义直白 ——
 * 点【开始游戏】就是要新的一局，不必推断上一局停在哪个 phase。
 */
@Composable
private fun AppRoot(
    viewModel: GameViewModel,
    historyRecords: kotlinx.coroutines.flow.Flow<List<top.windyvalley.magicsushi.engine.GameRecord>>,
    onScreenChanged: (AppScreen) -> Unit,
    onExitApp: () -> Unit,
) {
    var screen: AppScreen by rememberSaveable { mutableStateOf(AppScreen.Menu) }

    // 把导航状态单向同步给 Activity（供 composition 外的生命周期观察者读）。
    LaunchedEffect(screen) { onScreenChanged(screen) }

    when (screen) {
        AppScreen.Menu -> {
            // 在菜单按返回键 = 退出 App。不拦截的话系统默认行为是 finish()，
            // 进程留在后台缓存 —— 与【退出游戏】按钮的语义不一致。
            BackHandler { onExitApp() }

            MainMenuScreen(
                onStartGame = { screen = AppScreen.Game },
                onHistory = { screen = AppScreen.History },
                onExit = onExitApp,
            )
        }

        AppScreen.Game -> {
            // 进入游戏屏即开新局。key 用 Unit：本 LaunchedEffect 随
            // AppScreen.Game 分支进入 composition 而启动、离开而取消，
            // 所以每次「菜单 → 游戏」都会重新执行一次。
            LaunchedEffect(Unit) { viewModel.startGame() }

            // 游戏中按返回键 = 暂停，而不是直接退出。
            // 玩家在玩的时候误触返回键丢掉一局是很糟的体验。
            BackHandler { viewModel.onPause() }

            GameScreen(
                viewModel = viewModel,
                // 批次 C：退出的语义从「结束进程」变为「回主菜单」。
                // VM 侧完全不用动 —— onQuit 只负责把成绩写完再回调，
                // 「回调里做什么」一直是 UI 的决定。
                onQuit = { screen = AppScreen.Menu },
            )
        }

        AppScreen.History -> {
            BackHandler { screen = AppScreen.Menu }

            HistoryScreen(
                records = historyRecords,
                onBack = { screen = AppScreen.Menu },
            )
        }
    }
}
