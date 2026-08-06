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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import top.windyvalley.magicsushi.engine.GameEvent

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
 * - 订阅 [events]，只消费 [GameEvent.TimeReward]。
 * - 每收到一个事件就显示 `+Ns` 文字（24sp，绿色 `#8BC34A`），从下方滑入
 *   并向上飘出 + 渐隐，1.5s 后自动消失。
 * - 背景为半透明深色 `#CC2A1810` 圆角胶囊。
 *
 * ## 为什么用事件流而不是 state 字段（FIX_PLAN D2）
 *
 * 旧版签名是 `lastRewardSeconds: Int?`，靠 `LaunchedEffect(lastRewardSeconds)`
 * 的 key 变化触发飘字。但 `+5s` 是本游戏最常见的奖励值 —— 连续两次消除
 * 都奖励 5 秒时，字段值 `5 → 5` 没有变化，`LaunchedEffect` 不重启，
 * **第二次飘字直接不显示**。改为 collect `SharedFlow` 后，每次 emit 都是
 * 独立投递，与值是否重复无关。
 *
 * ## 位置
 *
 * 默认挂在屏幕顶部中心（`Alignment.TopCenter`），并用 `offset(y = 100.dp)`
 * 下推到 timer 数字下方、棋盘上方的中间区域。如需调整，调用方用
 * `modifier` 覆盖。
 *
 * ## 数据契约
 *
 * - [events]              — 来自 `GameViewModel.events`。本组件只关心
 *                            [GameEvent.TimeReward]，其余类型忽略。
 *                            VM 侧已保证 `seconds > 0`。
 * - [modifier]            — 标准 Compose modifier（覆盖 `Box` 的位置用）。
 *
 * @param events    一次性游戏事件流。
 * @param modifier  标准 Compose modifier。
 */
@Composable
fun RewardOverlay(
    events: SharedFlow<GameEvent>,
    modifier: Modifier = Modifier,
) {
    // ----- +Ns 飘字：维持 1.5 秒后消失 -----
    // collect 事件流：同值连续触发也会各自走一遍（这正是改用事件流的原因）。
    // 若上一次飘字还在显示期间就来了新事件，rewardValue 会被更新并重新计时，
    // 视觉上表现为飘字数值刷新 —— 符合"最近一次奖励"的语义。
    var showReward by remember { mutableStateOf(false) }
    var rewardValue by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        events.filterIsInstance<GameEvent.TimeReward>().collect { event ->
            rewardValue = event.seconds
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
