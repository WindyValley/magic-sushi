package top.windyvalley.magicsushi.ui.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 从屏幕顶部浮出的短暂提示条。
 *
 * ## 为什么不用 Toast
 *
 * Toast 的位置由系统决定（默认屏幕下方），应用无法指定。棋盘下移后视线
 * 集中在屏幕中部偏下，而 Toast 在更下方的系统区域弹出，容易被忽略 ——
 * 这正是用户反馈的问题。
 *
 * 自绘浮层能精确控制位置（顶部）、动画（浮出）和样式（与游戏配色一致）。
 *
 * ## 为什么不用 Snackbar
 *
 * Snackbar 需要 Scaffold + SnackbarHost 一整套脚手架，而本项目的 GameScreen
 * 是手写 Box + Column 布局，没有 Scaffold。为一条提示引入整套 Material
 * 脚手架会牵动整个屏幕的布局结构，风险远大于收益。
 *
 * ## 动画
 *
 * 入场：从上方 -24dp 滑下到 0，同时 alpha 0→1，[ENTER_MS] 毫秒。
 * 停留：[HOLD_MS] 毫秒。
 * 退场：alpha 1→0，[EXIT_MS] 毫秒（不带位移 —— 往回缩会显得犹豫）。
 *
 * @param message   提示文字。传 `null` 表示当前无提示（组件不渲染）。
 * @param token     每次要播提示时递增的计数器，作为动画的重置 key。
 *                  同一条文字连续提示两次时，靠它区分「这是新的一次」。
 * @param onDismiss 提示生命周期结束时回调，调用方据此把 message 复位为 null。
 */
@Composable
fun TopToast(
    message: String?,
    token: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 没有消息就什么都不画。刻意不用 AnimatedVisibility ——
    // 它需要额外的 enter/exit transition 配置，而这里的时序是「入场→停留→
    // 退场→通知调用方」的一次性流程，用手工 progress 更直观也更好调。
    if (message == null) return

    // 0 = 完全隐藏（在上方、透明），1 = 完全显示（到位、不透明）。
    //
    // ⚠️ key 是 token 而非 message：连续两次重排的文字完全相同，用 message
    // 作 key 时 `remember` 认为没变化、不重建状态，第二次提示的动画不会重跑。
    // 调用方每次发提示都递增 token，才能保证每次都重新播一遍。
    //
    // 同一帧内「先赋 null 再赋原值」是无效写法 —— Compose 只观察到最终值，
    // 中间那次 null 根本不会被看到。必须靠一个真的会变的值。
    var visible by remember(token) { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) ENTER_MS else EXIT_MS,
            easing = LinearOutSlowInEasing,
        ),
        label = "topToast.progress",
    )

    LaunchedEffect(token) {
        visible = true
        delay(ENTER_MS.toLong() + HOLD_MS)
        visible = false
        // 等退场动画播完再通知调用方，否则 message 立刻变 null，
        // 组件直接 return，退场动画一帧都看不到。
        delay(EXIT_MS.toLong())
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer {
                    // 位移只在入场时有意义：从上方滑下。
                    translationY = (progress - 1f) * SLIDE_DISTANCE_PX
                }
                .alpha(progress)
                .background(
                    color = ToastBg,
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** 入场时长（毫秒）。 */
private const val ENTER_MS = 220

/** 停留时长（毫秒）。比 Toast.LENGTH_SHORT（约 2s）略短，提示很短读得快。 */
private const val HOLD_MS = 1600L

/** 退场时长（毫秒）。比入场快 —— 消失不需要仪式感。 */
private const val EXIT_MS = 180

/**
 * 入场滑动距离（像素）。
 *
 * 写成裸像素而非 dp：`graphicsLayer` 的 `translationY` 单位就是像素，
 * 在那个作用域里换算 dp 需要 `with(density)`，为 24dp 左右的位移绕一圈
 * 不值得。不同密度下实际位移略有差异，但这是纯装饰性动画，无感。
 */
private const val SLIDE_DISTANCE_PX = 64f

/** 提示条背景：半透明深色，压在棋盘上也能看清文字。 */
private val ToastBg = Color(0xE6202020)
