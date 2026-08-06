package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.windyvalley.magicsushi.engine.GamePhase
import top.windyvalley.magicsushi.engine.GameState
import top.windyvalley.magicsushi.ui.canvas.GameCanvas
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
 * 初始化：第一次进入时调用 [GameViewModel.startGame] 让 IDLE → PLAYING。
 * 重玩：弹窗的"重玩"按钮调 [GameViewModel.onRestart]。
 */
@Composable
fun GameScreen(viewModel: GameViewModel) {
    val state by viewModel.state.collectAsState()
    var showPauseDialog by remember { mutableStateOf(false) }

    // 第一次进入：IDLE → PLAYING
    LaunchedEffect(Unit) {
        if (state.phase == GamePhase.IDLE) {
            viewModel.startGame()
        }
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
                    onClick = { showPauseDialog = true },
                ) {
                    Text(
                        text = "❚❚",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TimerDisplay(
                    remainingSeconds = state.remainingSeconds,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 中间棋盘
            GameCanvas(
                board = state.board,
                selectedTile = state.selectedTile,
                animFrame = state.animFrame,
                onTileTap = viewModel::onTileTapped,
                onDragEnd = viewModel::onDragEnd,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 底部：得分 + 静音
            ScoreOverlay(
                currentScore = state.score,
                highScore = state.highScore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 暂停对话框：phase == PAUSED 或显式点击暂停按钮
    if (showPauseDialog || state.phase == GamePhase.PAUSED) {
        PauseDialog(
            currentScore = state.score,
            remainingSeconds = state.remainingSeconds,
            onResume = {
                showPauseDialog = false
                viewModel.onResume()
            },
            onRestart = {
                showPauseDialog = false
                viewModel.onRestart()
            },
            onQuit = {
                showPauseDialog = false
                // 退出 = 重新开始（按用户偏好）
                viewModel.onRestart()
            },
        )
    }

    // +Ns 飘字顶层浮层（v1.0.4 独立化）—— 在棋盘之上、Dialog 之下
    // 消费 VM 的一次性事件流而非 state 字段：连续两次同值奖励（如两次 +5s）
    // 用 state 字段会因"值未变化"漏播飘字（FIX_PLAN D2）。
    RewardOverlay(
        events = viewModel.events,
    )

    // 结束对话框
    if (state.phase == GamePhase.GAME_OVER) {
        GameOverDialog(
            finalScore = state.score,
            highScore = state.highScore,
            isNewRecord = state.isNewRecord,
            onRestart = {
                // 修 δ 发现的 bug：如果用户是在 pause dialog 弹出期间被 game over，
                // 点重玩要同时清掉 showPauseDialog 标志
                showPauseDialog = false
                viewModel.onRestart()
            },
        )
    }
}