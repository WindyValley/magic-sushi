package top.windyvalley.magicsushi.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * RewardOverlay.kt — 屏幕顶层独立浮层，用于渲染消除奖励飘字（+Ns）。
 *
 * ## 设计动机（v1.0.4）
 *
 * 旧版 `+Ns` 飘字嵌在 `TimerDisplay` 的 `Column` 内，飘字出现时
 * `Column` 被撑高 → 顶层 Box 不变（Box 不参与父布局测量）→ 但
 * Column 中心点保持不变、向下扩张 → 视觉上棋盘被向下挤。
 *
 * 新版把飘字提到 `GameScreen` 顶层的独立 `Box` 浮层（`fillMaxSize`
 * + `Alignment.TopCenter`），飘字在棋盘**之上**淡入淡出，**不参与
 * 任何父布局的测量**，因此永远不会挤压棋盘。
 *
 * ## 行为
 *
 * - 监听 `lastRewardSeconds` 变化（`null` / `0` / 负数不触发）。
 * - 触发时显示 `+Ns` 文字（24sp，绿色 `#8BC34A`），从下方滑入并向上
 *   飘出 + 渐隐，1.5s 后自动消失。
 * - 背景为半透明深色 `#CC2A1810` 圆角胶囊。
 *
 * ## 位置
 *
 * 默认挂在屏幕顶部中心（`Alignment.TopCenter`），并用 `offset(y = 100.dp)`
 * 下推到 timer 数字下方、棋盘上方的中间区域。如需调整，调用方用
 * `modifier` 覆盖。
 *
 * ## 数据契约
 *
 * - [lastRewardSeconds]   — 来自 `GameState.lastRewardSeconds`。
 *                            `null` / `0` / 负数都不触发飘字；
 *                            `> 0` 时显示对应 `+Ns`。
 * - [modifier]            — 标准 Compose modifier（覆盖 `Box` 的位置用）。
 *
 * @param lastRewardSeconds   最近一次消除奖励秒数。
 * @param modifier            标准 Compose modifier。
 */
@Composable
fun RewardOverlay(
    lastRewardSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    // ----- +Ns 飘字：维持 1.5 秒后消失 -----
    // 当 lastRewardSeconds 变化且 > 0 时：把数值存入本地 state、显示飘字，
    // 1.5s 后隐藏。null / 0 / 负数都不触发（保持现状）。
    var showReward by remember { mutableStateOf(false) }
    var rewardValue by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastRewardSeconds) {
        if (lastRewardSeconds != null && lastRewardSeconds > 0) {
            rewardValue = lastRewardSeconds
            showReward = true
            delay(1500)
            showReward = false
        }
    }

    // 屏幕顶层独立浮层：fillMaxSize 占据全屏做定位锚点，
    // 但内部只有 AnimatedVisibility 包裹的 Text；不参与任何父布局测量，
    // 因此不会挤压棋盘。
    Box(
        modifier = modifier
            .fillMaxSize()
            // 默认位置：屏幕顶部 + 100dp，让飘字出现在 timer 数字和棋盘之间
            .padding(top = 100.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = showReward,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            Text(
                text = "+${rewardValue}s",
                color = Color(0xFF8BC34A),                                 // 绿色
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp),       // v1.0.5: 取消半透明深色胶囊背景，仅保留文字 + 内边距
            )
        }
    }
}
