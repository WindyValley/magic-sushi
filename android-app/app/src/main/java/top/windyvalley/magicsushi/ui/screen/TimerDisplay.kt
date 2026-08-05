package top.windyvalley.magicsushi.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * TimerDisplay.kt — 屏幕顶部倒计时显示组件。
 *
 * ## 行为（v1.0.4 简化）
 *
 * - **大字号显示剩余秒数**（48sp，Color `0xFFFFB347` 默认橙色）。
 * - **最后 10 秒（remainingSeconds ∈ 1..10）闪烁**：
 *   - 数字颜色在红色 `#E85D2F` 与橙色 `#FFB347` 之间以 500ms 切换，
 *     `animateColorAsState` 做 300ms 颜色过渡。
 *   - 闪烁通过 `LaunchedEffect(remainingSeconds)` 驱动，每次
 *     `remainingSeconds` 进入 1..10 启动一个独立的 `while` 循环
 *     （避免 `LaunchedEffect` 被取消后状态卡住）。
 * - **+Ns 飘字（v1.0.4 移除）**：飘字已从本组件移到 `GameScreen` 顶层的
 *   独立 Box 浮层（`RewardOverlay`），原因：嵌入布局时飘字会撑高
 *   `Column` → 推挤下方棋盘。调用方仍传入 `lastRewardSeconds` 仅是
 *   为了无感升级；本组件不再消费。
 * - **0 秒显示"时间到"**：以灰色 `#666666` 替代数字文字。
 *
 * ## 数据契约
 *
 * - [remainingSeconds]    — 来自 `GameState.remainingSeconds`。
 * - [lastRewardSeconds]   — 来自 `GameState.lastRewardSeconds`。
 *                            v1.0.4 起**不再被本组件消费**，请改用
 *                            `RewardOverlay` 渲染飘字。
 *
 * ## 不依赖
 *
 * - 不引用任何 `engine` 包的内容（纯 UI），便于单独预览/测试。
 * - 不读 `GameViewModel`，由调用方从 `state` 取值传入。
 *
 * @param remainingSeconds    剩余秒数（≥ 0）。0 时显示"时间到"。
 * @param lastRewardSeconds   最近一次消除奖励秒数（v1.0.4 起不再使用，保留仅为兼容）。
 * @param modifier            标准 Compose modifier。
 */
@Composable
fun TimerDisplay(
    remainingSeconds: Int,
    @Suppress("UNUSED_PARAMETER") lastRewardSeconds: Int? = null,
    modifier: Modifier = Modifier,
) {
    // ----- 闪烁状态（最后 10 秒） -----
    // 用一个普通 var 记录"是否处于亮态"，LaunchedEffect 内部 while 循环
    // 以 500ms 翻转一次。重启条件：remainingSeconds 进入 1..10 时 LaunchedEffect
    // 被重启，重新开始 while 循环。
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds in 1..10) {
            while (remainingSeconds in 1..10) {
                blinkOn = !blinkOn
                delay(500)
            }
            // 退出闪烁区间时回到亮态默认色，避免卡在暗态
            blinkOn = true
        } else {
            // 不在闪烁区间时确保为亮态
            blinkOn = true
        }
    }

    // ----- 数字颜色 -----
    val color by animateColorAsState(
        targetValue = when {
            remainingSeconds == 0 -> Color(0xFF666666)                       // 灰色："时间到"
            remainingSeconds in 1..10 -> if (blinkOn) Color(0xFFE85D2F)      // 红
                                         else Color(0xFFFFB347)             // 橙
            else -> Color(0xFFFFB347)                                        // 默认橙
        },
        animationSpec = tween(durationMillis = 300),
        label = "timerColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (remainingSeconds == 0) "时间到" else remainingSeconds.toString(),
                color = color,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
