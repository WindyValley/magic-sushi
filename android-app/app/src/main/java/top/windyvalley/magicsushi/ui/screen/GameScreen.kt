package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.windyvalley.magicsushi.BuildConfig
import top.windyvalley.magicsushi.engine.GameEvent
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.engine.GameState
import top.windyvalley.magicsushi.engine.RoundExitOptions
import top.windyvalley.magicsushi.ui.canvas.GameCanvas
import top.windyvalley.magicsushi.ui.component.TopToast
import top.windyvalley.magicsushi.ui.theme.SushiBgDark
import top.windyvalley.magicsushi.ui.theme.SushiSecondary
import top.windyvalley.magicsushi.viewmodel.GameViewModel

/**
 * GameScreen — Magic Sushi 主屏（T-UI-008）。
 *
 * 把 [GameViewModel.state] 接到四个 UI 组件：
 *  - [TimerDisplay]   顶部倒计时 + 消除 +Ns 飘字
 *  - [GameCanvas]     中间 7×7 棋盘（点击 + 拖动）
 *  - [ScoreOverlay]   底部当前分 + 最高分 + 静音按钮
 *  - [PauseDialog]    暂停时弹出（phase == PAUSED）
 *  - [GameOverDialog] 结束时弹出（phase == GAME_OVER）
 *
 * 状态机驱动：VM 内部维护 phase (IDLE / PLAYING / PAUSED / GAME_OVER)，
 * 本 Composable 只负责把 phase 转成对应的 Dialog 可见性。
 *
 * 开局时机：**不在本 Composable 内**。由调用方（MainActivity 的
 * AppScreen.Game 分支）在进入游戏屏时调 [GameViewModel.startGame]。
 * 重玩：弹窗的"重玩"按钮调 [GameViewModel.onRestart]。
 */
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    /**
     * 退出游戏。由调用方（Activity）决定语义 —— 批次 C 起是「回主菜单」。
     *
     * 此回调在**成绩已写入历史之后**被调用，可以安全执行 exitProcess
     * 这类不可逆操作。
     */
    onQuit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // 退出确认弹窗是否显示。
    //
    // 这是**纯 UI 状态**，刻意不放进 GameState：它不影响对局，VM 也不需要
    // 知道玩家正在犹豫。（对比 `showPauseDialog` 那个曾经的 bug —— 那个变量
    // 的问题不是「局部」，而是它取代了 phase 成为暂停的判据却从不调用
    // onPause()。这里没有对应风险：确认弹窗没有任何 VM 侧的对偶状态。）
    var showExitConfirm by remember { mutableStateOf(false) }

    // 死局自动重排提示。
    //
    // 从 Toast 改成自绘顶部浮层：Toast 的位置由系统决定（屏幕下方），棋盘
    // 下移后视线在中部偏下，Toast 在更下方弹出容易被忽略。
    //
    // 订阅 events 而非读 state 字段 —— 这是瞬时通知，连续两次重排必须提示
    // 两次，而 state 字段在「同一个 true 连续赋值」时不会触发重组。
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is GameEvent.BoardReshuffled) {
                toastMessage = "局面无解，已自动重排"
                // 递增 token 让 TopToast 知道「这是新的一次」，即使文字相同。
                toastToken++
            }
        }
    }

    // 离开暂停态时复位。
    //
    // 不复位会残留：玩家打开确认弹窗 → 切后台 → ON_STOP 存快照 → 回到前台
    // 仍是 PAUSED（不自动继续，见 onSystemResume），此时弹窗还在，尚可接受；
    // 但若这期间对局以别的方式结束（倒计时归零走不到这里，因为暂停时计时器
    // 已停；真正的来源是 restoreSnapshot / startGame 重置了 phase），
    // 弹窗会盖在一个已经不存在的对局上。
    LaunchedEffect(state.phase) {
        if (state.phase != GamePhase.PAUSED) showExitConfirm = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SushiBgDark)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部行：倒计时居屏幕正中，暂停 + 调试入口浮在左侧。
            //
            // ## 为什么用 Box 而不是 Row
            //
            // 原本是 `Row { 暂停按钮; Spacer(8dp); TimerDisplay(weight(1f)) }`
            // —— 计时器拿到的是「暂停按钮右侧的剩余宽度」并在其中居中，于是
            // 数字落在剩余空间的中点而非屏幕中点，右偏约半个按钮宽。既不居中
            // 也不靠边，看着像没对齐。
            //
            // Box 把两者解耦：计时器相对**整个屏幕宽度**居中，左侧控件叠在
            // 它上面用 align 钉住，不参与宽度计算。以后左边再加控件也不会
            // 推动数字。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                TimerDisplay(
                    remainingSeconds = state.remainingSeconds,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 左侧控件组，叠在计时器之上钉在左边。
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { viewModel.onPause() },
                        modifier = Modifier
                            .size(PAUSE_BUTTON_DP.dp)
                            .background(PauseButtonBg, CircleShape)
                            .border(PAUSE_BORDER_DP.dp, SushiSecondary, CircleShape),
                    ) {
                        // 用矢量图而非字符 `❚❚` —— 字符受系统字体限制（U+275A
                        // 在精简字体包里常缺），矢量图打进 APK，任何设备一致。
                        // core 里没有 Pause，暂停符号是两个矩形，手写最省。
                        //
                        // ## 尺寸为什么回到 24dp
                        //
                        // 加背景圆之前一路调到 32dp 才勉强够看（20 → 28 → 32）
                        // —— 那时「显眼」全靠图标本身的白色面积去顶。
                        //
                        // 有了背景圆之后，承担存在感的是整个 48dp 的圆（底色 +
                        // 琥珀描边），图标反而该缩回 Material 标准的 24dp 留
                        // 呼吸空间：48dp 圆的内切正方形约 34dp，24dp 图标四周
                        // 各留 5dp，不顶描边。
                        //
                        // 图标继续放大反而更糟：贴着描边会让圆看起来被撑破。
                        Icon(
                            imageVector = PauseIcon,
                            contentDescription = "暂停",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 调试入口：长按扳手强制造死局并立刻重排。
                    //
                    // ## 关于「release 包里有没有这段代码」
                    //
                    // `BuildConfig.DEBUG` 是编译期常量，release 下这个分支
                    // **永不执行**。但本项目 `isMinifyEnabled = false`（见
                    // build.gradle.kts 的说明：混淆需要单独一轮序列化验证，
                    // 尚未做），所以 R8 不运行，方法体和
                    // `DeadlockEngine.forceDeadlock` 的引用**仍然在 APK 里**。
                    //
                    // 已用 dexdump 核实：release 的 classes2.dex 里
                    // `forceDeadlock` 和 `debugForceDeadlock` 两个方法都存在。
                    //
                    // 这可以接受 —— 玩家碰不到这个入口（分支不执行），多出的
                    // 死代码不到 1KB。等哪天开启 minify，它们会自动被消除。
                    //
                    // ⚠️ 不要因为「反正 R8 会删」就往这里塞敏感逻辑，当前它
                    // 不会删。
                    //
                    // ## 为什么不用 combinedClickable
                    //
                    // 它需要 `@OptIn(ExperimentalFoundationApi)`，而本项目此前
                    // 零处 opt-in —— 不为一个调试入口引入第一个实验 API 依赖。
                    // `detectTapGestures` 是稳定 API。
                    if (BuildConfig.DEBUG) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { viewModel.debugForceDeadlock() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 用矢量图而非 emoji `🐞`。虽然这个入口只在 debug
                            // 包出现、字体缺失影响有限，但没有理由在这里破例
                            // —— 全 app 统一「图标即矢量图」，少一处例外就少
                            // 一处将来照抄错模式的源头。
                            //
                            // 换用扳手（Build）而非虫子：语义是「调试工具」，
                            // 比「有 bug」更准确。core 里就有，零成本。
                            //
                            // 刻意**不加**背景圆：它 alpha 0.35 就是要低调，
                            // 加背景等于把调试功能提到与暂停按钮同级。
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = "调试：强制死局",
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 顶部行与棋盘之间的弹性空隙。
            //
            // 之前棋盘紧贴顶部行，整体偏上，屏幕下方留一大片空白 —— 顶部弹出的
            // 提示离棋盘太远，视线在棋盘上时不容易注意到。
            //
            // 用 weight 而不是固定 dp：不同屏幕比例下都能保持棋盘位置稳定，
            // 写死 dp 在短屏上会把棋盘挤出可视区。
            Spacer(modifier = Modifier.weight(1f))

            // 中间棋盘
            GameCanvas(
                presentation = state.presentation,
                selectedTile = state.selectedTile,
                onTileTap = viewModel::onTileTapped,
                onDragEnd = viewModel::onDragEnd,
                modifier = Modifier.fillMaxWidth(),
            )

            // 棋盘与得分卡之间是固定间距，不是 weight。
            //
            // 得分卡要「跟着棋盘」而不是「贴着屏幕底」—— 它显示的是这一局的
            // 即时状态，属于棋盘的一部分，离远了要移动视线才能看到分数变化。
            //
            // 曾经这里是 `weight(2f)`：棋盘上方 1 份、下方 2 份，得分卡被挤到
            // 屏幕最下缘。那样棋盘确实居中偏上，但得分卡看着像被遗弃在角落。
            Spacer(modifier = Modifier.height(20.dp))

            // 底部：得分 + 静音
            ScoreOverlay(
                currentScore = state.score,
                highScore = state.highScore,
                modifier = Modifier.fillMaxWidth(),
            )

            // 剩余空间全部收在得分卡下方。
            //
            // 与上方的 weight(1f) 配合，「棋盘 + 得分卡」作为一个整体在竖直
            // 方向居中偏上：上方 1 份、下方 2 份。这样棋盘位置与改动前基本
            // 一致（原来也是 1:2，只是得分卡在那 2 份的另一侧），而得分卡
            // 现在紧跟棋盘。
            Spacer(modifier = Modifier.weight(2f))
        }

        // 顶部浮出提示。
        //
        // 放在 Column 之后、同一个 Box 内 —— Box 里后声明的在上层，所以它
        // 浮在棋盘上方而不被遮住。挂在 Box 上而非 Column 里，是为了不参与
        // Column 的垂直排布（否则提示出现/消失会把棋盘顶来顶去）。
        TopToast(
            message = toastMessage,
            token = toastToken,
            onDismiss = { toastMessage = null },
            modifier = Modifier.padding(top = 56.dp),
        )
    }

    // 暂停对话框：phase == PAUSED 是唯一判据。
    //
    // 修 bug：这里曾是 `showPauseDialog || state.phase == PAUSED`，其中
    // showPauseDialog 是 GameScreen 的局部 remember 状态，由暂停按钮直接
    // 置 true —— 但**从不调用 viewModel.onPause()**。结果对话框弹出来了，
    // VM 里的 timerJob 却照常每秒递减，swapJob 也没取消：看起来暂停了，
    // 实际没停。phase == PAUSED 那半个条件只对系统级暂停（切后台，
    // MainActivity 的 ON_PAUSE）生效，所以「两个暂停来源只有一个真暂停」。
    //
    // 现在暂停按钮直接调 viewModel.onPause()，phase 成为唯一真相，
    // 局部状态连同它引发的同步问题一起删除。
    if (state.phase == GamePhase.PAUSED) {
        PauseDialog(
            currentScore = state.score,
            remainingSeconds = state.remainingSeconds,
            onResume = { viewModel.onResume() },
            onRestart = { viewModel.onRestart() },
            onQuit = {
                // 暂停面板的退出按钮不再直接执行任何退出动作 —— 它只是
                // 打开二级确认。
                //
                // 此前这里直接调 onSuspendToMenu（保留快照），于是「退出」
                // 这一个按钮绑死了一种语义：想彻底结束这局的玩家被迫留下
                // 一个快照，下次进菜单还要再面对一次「继续上局」。而按钮
                // 若改叫「退出」，想保留的人又不敢点。
                //
                // 根因是「退出」不是一个动作而是一个岔路口，所以改为问玩家。
                // 两条路分别对应 VM 里已有的两种语义（onSuspendToMenu /
                // onQuit），没有引入第三种退出路径。
                showExitConfirm = true
            },
        )
    }

    // 退出二级确认。
    //
    // 叠在 PauseDialog 之上（两者都在 PAUSED 下渲染）—— 取消后玩家回到
    // 暂停面板，而不是直接回到棋盘：取消的语义是「我不退出了」，不是
    // 「我要继续玩」。后者由暂停面板的「继续」表达。
    if (state.phase == GamePhase.PAUSED && showExitConfirm) {
        ExitConfirmDialog(
            // 「值不值得保留」的判断在 engine 的纯函数里（有单测覆盖），
            // UI 只负责把对局现场喂进去。刻意不让 VM 直接给一个
            // `showKeepButton` 之类的布尔 —— 那会把「画几个按钮」的决定
            // 挪进 VM。
            canKeepProgress = RoundExitOptions.canKeepProgress(
                score = state.score,
                remainingSeconds = state.remainingSeconds,
                boardHasTiles = state.board.grid.flatten().any { it != null },
                alreadyRecorded = state.roundFinalized,
            ),
            currentScore = state.score,
            remainingSeconds = state.remainingSeconds,
            onKeepAndExit = {
                showExitConfirm = false
                // 挂起：只写快照、不结算。成绩留到这局真正结束时再算。
                // 同步落盘后才回调，保证菜单查 hasRestorableSnapshot 时
                // 快照已经在盘上（否则表现为「刚退出却没有继续按钮」）。
                viewModel.onSuspendToMenu(onSuspended = onQuit)
            },
            onFinishAndExit = {
                showExitConfirm = false
                // 结束本局：结算入库 + 清快照。
                //
                // 0 分时这是唯一的出口 —— 用户决定「0 分退出默认丢弃本局
                // 不保留」。注意 onQuit 内部对 0 分也会清快照（见其
                // hadUnsettledScore == false 分支），所以「不保留」是
                // 落实到盘上的，不只是不写新快照。
                viewModel.onQuit(onRecorded = onQuit)
            },
            onCancel = { showExitConfirm = false },
        )
    }

    // 结束对话框
    if (state.phase == GamePhase.GAME_OVER) {
        GameOverDialog(
            finalScore = state.score,
            highScore = state.highScore,
            isNewRecord = state.isNewRecord,
            onRestart = { viewModel.onRestart() },
            // 修 bug：这个参数一直存在（带默认空实现），但 GameScreen
            // 从未传过 —— 于是「返回菜单」按钮点了没任何反应。
            //
            // 成绩此时已由 onGameOver 写入历史（recordCurrentRound 幂等），
            // 所以这里 onQuit 不会重复写。
            onBackToMenu = {
                viewModel.onQuit(onRecorded = onQuit)
            },
        )
    }
}
/**
 * 暂停按钮的直径。
 *
 * 48dp = Material 的最小交互尺寸。这里让**可见圆与触摸区重合**，而不是
 * 画一个更小的圆再靠透明内边距凑触摸区 —— 那样玩家会点到圆外的空白却
 * 触发暂停，或者以为点了没反应（实际点在了描边外的透明区）。
 *
 * 试过 44dp（iOS HIG 的下限）：显式给 `IconButton` 传 44dp 会把它默认的
 * 48dp 触摸区一起缩掉，低于 Material 无障碍下限，不值得为 4dp 观感让步。
 */
private const val PAUSE_BUTTON_DP = 48

/** 描边宽度。与 Toast 的 2dp 一致 —— 同一套视觉语言。 */
private const val PAUSE_BORDER_DP = 2

/**
 * 暂停按钮底色：比背景稍亮的半透明棕。
 *
 * 取 `0x66` alpha 而非实心：实心色块在满屏暖色棋盘上方会像一个「贴上去的
 * 补丁」，半透明让它读起来是背景的一部分被提亮，而不是另一层。
 *
 * 色相与 `ToastBg`（`0xF23A2318`）同源 —— 提示条和暂停按钮是这个界面上
 * 唯二的浮层控件，共用一套配色才不像拼凑的。
 */
private val PauseButtonBg = Color(0x663A2318)

/**
 * 暂停图标（两个竖条），手写路径数据。
 *
 * `material-icons-core` 里没有 Pause（它在 extended 包，那个 AAR 有几千个
 * 图标，为一个符号引进来不划算）。暂停就是两个矩形，手写比加依赖省得多。
 *
 * 24x24 视口，与 Material 图标的栅格一致 —— 这样它和同一行的其他图标
 * 视觉大小才匹配。
 */
private val PauseIcon: ImageVector = ImageVector.Builder(
    name = "Pause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.White)) {
        // 左竖条
        moveTo(6f, 19f)
        horizontalLineTo(10f)
        verticalLineTo(5f)
        horizontalLineTo(6f)
        verticalLineTo(19f)
        close()
        // 右竖条
        moveTo(14f, 5f)
        verticalLineTo(19f)
        horizontalLineTo(18f)
        verticalLineTo(5f)
        horizontalLineTo(14f)
        close()
    }
}.build()
