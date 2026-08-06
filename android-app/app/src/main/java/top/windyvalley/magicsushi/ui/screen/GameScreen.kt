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
                    onClick = { viewModel.onPause() },
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
                presentation = state.presentation,
                selectedTile = state.selectedTile,
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
                // 退出 = 重新开始（按用户偏好）
                viewModel.onRestart()
            },
        )
    }

    // 结束对话框
    if (state.phase == GamePhase.GAME_OVER) {
        GameOverDialog(
            finalScore = state.score,
            highScore = state.highScore,
            isNewRecord = state.isNewRecord,
            onRestart = { viewModel.onRestart() },
        )
    }
}