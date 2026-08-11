package top.windyvalley.magicsushi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.ui.navigation.AppScreen
import top.windyvalley.magicsushi.ui.navigation.AppScreenSaver
import top.windyvalley.magicsushi.ui.screen.AboutScreen
import top.windyvalley.magicsushi.ui.screen.GameScreen
import top.windyvalley.magicsushi.ui.screen.HistoryScreen
import top.windyvalley.magicsushi.ui.screen.MainMenuScreen
import top.windyvalley.magicsushi.ui.screen.SettingsScreen
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.viewmodel.GameViewModel
import top.windyvalley.magicsushi.viewmodel.GameViewModelFactory
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    // 依赖来自 Application 作用域，不再由 Activity 创建。
    // 此前 SoundPlayer 是本类的 by lazy 属性，而 GameViewModel 跨配置变更
    // 存活 —— 旋屏后旧 Activity 的 onDestroy() 会 release 掉 VM 仍在使用
    // 的 SoundPool，导致音效永久失效。
    private val app: MagicSushiApp
        get() = application as MagicSushiApp

    private val viewModel: GameViewModel by viewModels {
        GameViewModelFactory(app.prefsRepo, app.historyRepo, app.soundPlayer, app.snapshotRepo)
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
        // 必须在 super.onCreate() / setContent() 之前安装，
        // 否则启动窗口已经交接完毕，keepOnScreenCondition 拦不住。
        //
        // 预热（MagicSushiApp.onCreate → prefsRepo.warmUp）通常在此之前就
        // 已完成，此时条件立刻为 false、启动窗口不会额外停留。这里的守卫
        // 是为极端情况兜底：低端机首次安装要跑 SharedPreferences 迁移，
        // 那次 IO 可能明显更慢。
        //
        // 为什么不自绘一个 Compose 启动页：预热是主线程同步 IO，期间
        // composition 同样被冻住，自绘的页面一帧都画不出来，玩家只会看到
        // 白屏。系统启动窗口由 WindowManager 在进程起来前绘制，不受影响。
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !app.prefsRepo.isReady }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 观察 Activity 生命周期，让 VM 知道何时暂停/恢复
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // 切后台一律暂停：即使在菜单屏，暂停一个没在跑的 timer 也是
                // 无害的 no-op（onPause 只是置 phase + cancel）。
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()

                // ON_STOP 是「进程可能马上消失」前最后一个保证执行的回调。
                //
                // 从任务列表划掉应用时：
                //     ON_PAUSE → ON_STOP → （系统随时可杀，不保证 onDestroy）
                //
                // 所以对局快照必须在这里同步写完（onStopWithSnapshot 内部
                // 阻塞等落盘）。放 onDestroy 或 ViewModel.onCleared 都不行 ——
                // 那两个在划掉应用时通常根本不会执行。
                //
                // ⚠️ 不要因为「ON_PAUSE 已经暂停过了」就把这里合并进去：
                // ON_PAUSE 还会在弹对话框、切分屏等**不会杀进程**的场景触发，
                // 那些时候没必要付同步 IO 的代价。
                Lifecycle.Event.ON_STOP -> viewModel.onStopWithSnapshot()

                // 回到前台**不自动继续对局**，停在暂停态等玩家手动点「继续」。
                //
                // 玩家切回来注意力不在棋盘上（刚回完消息、刚看完通知），
                // 倒计时立刻跑起来等于凭空吃掉几秒 —— 这是 60 秒计时的游戏，
                // 几秒是实打实的损失。
                //
                // 这个接线点刻意保留（而非删掉调用）：让「什么都不做」是一个
                // 显式决定而非遗漏，将来要加恢复提示音之类的落点也在这里。
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
    // SoundPool。其生命周期随进程结束自然回收。
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
 * `AppScreen` 的成员都是 `data object`，但**不能**直接交给 `rememberSaveable` ——
 * 默认 saver 只接受能进 Bundle 的类型，`data object` 不在白名单里，会在首次
 * composition 的 `onRemembered` 阶段抛 `IllegalArgumentException`（冷启动即崩）。
 * 所以显式传 `stateSaver = AppScreenSaver`，把屏幕存成它的 `key` 字符串。
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
    var screen: AppScreen by rememberSaveable(stateSaver = AppScreenSaver) {
        mutableStateOf(AppScreen.Menu)
    }

    // 进游戏屏时是「开新局」还是「恢复上次对局」。
    //
    // ⚠️ 必须有这个开关：AppScreen.Game 分支里的 LaunchedEffect 会调
    // startGame()，那会用新棋盘覆盖刚恢复的现场。两种进入方式共用一个
    // 屏幕，但初始化动作互斥。
    //
    // 不用 rememberSaveable：它只在一次导航中有意义，配置变更后应回到
    // 默认的「开新局」—— 恢复动作此时早已完成（快照已被消费）。
    var resumeSavedRound by remember { mutableStateOf(false) }

    // 菜单上是否显示「继续上局」。
    //
    // 每次回到菜单都重新查一次：玩家可能刚打完一局（快照已被清），
    // 也可能刚被中断（快照刚写入）。
    var hasSavedRound by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        hasSavedRound = if (screen == AppScreen.Menu) {
            viewModel.hasRestorableSnapshot()
        } else {
            false
        }
    }

    // 把导航状态单向同步给 Activity（供 composition 外的生命周期观察者读）。
    LaunchedEffect(screen) { onScreenChanged(screen) }

    when (screen) {
        AppScreen.Menu -> {
            // 在菜单按返回键 = 退出 App。不拦截的话系统默认行为是 finish()，
            // 进程留在后台缓存 —— 与【退出游戏】按钮的语义不一致。
            BackHandler { onExitApp() }

            MainMenuScreen(
                onStartGame = {
                    resumeSavedRound = false
                    screen = AppScreen.Game
                },
                onHistory = { screen = AppScreen.History },
                onSettings = { screen = AppScreen.Settings },
                onExit = onExitApp,
                hasSavedRound = hasSavedRound,
                onContinueGame = {
                    resumeSavedRound = true
                    screen = AppScreen.Game
                },
            )
        }

        AppScreen.Game -> {
            // 进入游戏屏：恢复上次对局，或开新局。
            //
            // key 用 Unit：本 LaunchedEffect 随 AppScreen.Game 分支进入
            // composition 而启动、离开而取消，所以每次「菜单 → 游戏」都会
            // 重新执行一次。
            LaunchedEffect(Unit) {
                // 恢复失败（快照在这期间被清掉、或内容不可恢复）时兜底开新局
                // —— 绝不能让玩家停在一个空棋盘上。
                val restored = resumeSavedRound && viewModel.restoreSnapshot()
                if (!restored) viewModel.startGame()
            }

            // 游戏中按返回键 = 暂停，而不是直接退出。
            // 玩家在玩的时候误触返回键丢掉一局是很糟的体验。
            //
            // ⚠️ 结算面板（GAME_OVER）下不能仍走 onPause()：
            //
            // GameOverDialog 设了 dismissOnBackPress = false，所以返回键会
            // 穿透到这个 BackHandler。而 onPause() 现在对 GAME_OVER 是
            // no-op（见 PauseRules），返回键就变成**完全没反应** —— 玩家在
            // 结算面板上按返回键，界面纹丝不动，看起来像卡死了。
            //
            // 改前更糟：无条件 onPause() 会把结算面板换成暂停面板，那一局
            // 已经结束了却出现"暂停"。
            //
            // 这局已经结算过（onGameOver 里已入库），所以直接回菜单即可 ——
            // 与结算面板上的「返回菜单」按钮同义。走 onQuit 而不是直接改
            // screen，是为了复用它的收尾（清快照、置 IDLE）；成绩有幂等
            // 保护，不会重复入库。
            BackHandler {
                if (viewModel.state.value.phase == GamePhase.GAME_OVER) {
                    viewModel.onQuit(onRecorded = { screen = AppScreen.Menu })
                } else {
                    viewModel.onPause()
                }
            }

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

        AppScreen.Settings -> {
            BackHandler { screen = AppScreen.Menu }

            // isMuted 从 GameState 读 —— 它是 PrefsRepository 的投影
            // （见 GameViewModel 的 init），所以设置页切换后这里会自动跟上，
            // 不需要手工刷新。
            val gameState by viewModel.state.collectAsState()
            // 历史条数只用来在「清空历史记录」旁边显示 N 条 + 决定按钮是否
            // 可点，所以取当前快照即可。
            val records by historyRecords.collectAsState(initial = emptyList())

            SettingsScreen(
                isMuted = gameState.isMuted,
                historyCount = records.size,
                versionName = BuildConfig.VERSION_NAME,
                onToggleMute = { viewModel.toggleMute() },
                // 清空会连快照和最高分一起清（见 VM 的说明），所以本地那份
                // 「菜单是否显示继续上局」的缓存也要失效 —— 否则回到菜单
                // 仍会看到「继续上局」，点进去却恢复不出任何东西。
                onClearHistory = { viewModel.clearHistory { hasSavedRound = false } },
                onAbout = { screen = AppScreen.About },
                onBack = { screen = AppScreen.Menu },
            )
        }

        AppScreen.About -> {
            // 返回固定回设置页 —— 关于页只有一个入口（设置里的「关于本作」），
            // 所以不需要记来源。将来若主菜单也加入口，这里就得记了；那也是
            // 该换 navigation-compose 的信号之一（返回栈需求）。
            BackHandler { screen = AppScreen.Settings }

            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onBack = { screen = AppScreen.Settings },
            )
        }
    }
}
