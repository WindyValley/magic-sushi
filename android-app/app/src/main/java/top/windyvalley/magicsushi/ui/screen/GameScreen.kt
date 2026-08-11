package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
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
            // 顶部行：暂停按钮 + TimerDisplay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.onPause() },
                ) {
                    Text(
                        text = "❚❚",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // 调试入口：长按 🐞 强制造死局并立刻重排。
                //
                // ## 关于「release 包里有没有这段代码」
                //
                // `BuildConfig.DEBUG` 是编译期常量，release 下这个分支**永不
                // 执行**。但本项目 `isMinifyEnabled = false`（见 build.gradle.kts
                // 的说明：混淆需要单独一轮序列化验证，尚未做），所以 R8 不运行，
                // 方法体和 `DeadlockEngine.forceDeadlock` 的引用**仍然在 APK 里**。
                //
                // 已用 dexdump 核实：release 的 classes2.dex 里 `forceDeadlock`
                // 和 `debugForceDeadlock` 两个方法都存在。
                //
                // 这可以接受 —— 玩家碰不到这个入口（分支不执行），多出的死代码
                // 不到 1KB。等哪天开启 minify，它们会自动被消除。
                //
                // ⚠️ 不要因为「反正 R8 会删」就往这里塞敏感逻辑，当前它不会删。
                //
                // ## 为什么不用 combinedClickable
                //
                // 它需要 `@OptIn(ExperimentalFoundationApi)`，而本项目此前零处
                // opt-in —— 不为一个调试入口引入第一个实验 API 依赖。
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
                        Text(
                            text = "🐞",
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                TimerDisplay(
                    remainingSeconds = state.remainingSeconds,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 顶部行与棋盘之间的弹性空隙。
            //
            // 之前棋盘紧贴顶部行，整体偏上，屏幕下方留一大片空白 —— 顶部弹出的
            // 提示离棋盘太远，视线在棋盘上时不容易注意到。
            //
            // 用 weight 而不是固定 dp：不同屏幕比例下都能保持棋盘居中偏下，
            // 写死 dp 在短屏上会把棋盘挤出可视区。上下 1:2 让棋盘略微偏上于
            // 正中，给底部得分区留出呼吸空间。
            Spacer(modifier = Modifier.weight(1f))

            // 中间棋盘
            GameCanvas(
                presentation = state.presentation,
                selectedTile = state.selectedTile,
                onTileTap = viewModel::onTileTapped,
                onDragEnd = viewModel::onDragEnd,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(2f))

            // 底部：得分 + 静音
            ScoreOverlay(
                currentScore = state.score,
                highScore = state.highScore,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))
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