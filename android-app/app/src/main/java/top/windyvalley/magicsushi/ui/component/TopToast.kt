package top.windyvalley.magicsushi.ui.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .graphicsLayer {
                    // 位移只在入场时有意义：从上方滑下。
                    translationY = (progress - 1f) * SLIDE_DISTANCE_PX

                    // 轻微缩放让浮出有「弹出来」的质感，而不只是平移。
                    // 0.92→1.0 的幅度刻意很小 —— 大了会显得廉价。
                    val s = 0.92f + 0.08f * progress
                    scaleX = s
                    scaleY = s

                    alpha = progress
                }
                // 外层琥珀色描边 + 内层深棕底：与游戏的暖色寿司美术同一套色系。
                //
                // 之前是纯深色圆角矩形 + 白字，那是 Material 默认 Snackbar 的
                // 长相，跟满屏暖色寿司放在一起像是另一个 App 的控件。
                .background(
                    color = ToastBorder,
                    shape = RoundedCornerShape(CORNER_RADIUS_DP.dp),
                )
                .padding(BORDER_WIDTH_DP.dp)
                .background(
                    color = ToastBg,
                    // 内圈半径要比外圈小一个边宽，否则内层的角会「顶」出外层，
                    // 描边在四个角上看起来忽然变窄。
                    shape = RoundedCornerShape((CORNER_RADIUS_DP - BORDER_WIDTH_DP).dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // 图标承担「这是什么事」的第一眼识别，比读完整句话快。
            //
            // ⚠️ 用矢量图而非字符。这里换过两轮：
            //   1. `🔀`（U+1F500）—— 在 API 34 模拟器上降级成橙底白 X 方块，
            //      看着像「关闭/错误」，与「已自动重排」的意思完全相反。
            //   2. `⇄`（U+21C4）—— 字体覆盖比 emoji 好，但性质没变：
            //      仍然赌用户系统有这个字形。定制 ROM、精简字体包、字体
            //      子集化都可能缺，缺了就是豆腐块。
            //
            // `ImageVector` 的路径数据打进 APK，渲染不经过字体系统，任何
            // 设备一致。`Refresh` 在 material-icons-core 里（随 material3
            // 传递依赖），不增加依赖也不增加体积。
            Icon(
                imageVector = Icons.Filled.Refresh,
                // 紧跟的文字已说明是什么事，重复念一遍是噪音。
                contentDescription = null,
                tint = ToastBorder,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = ToastText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
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

/** 圆角半径（dp）。偏大的圆角配暖色调更亲和，接近棋子的圆润感。 */
private const val CORNER_RADIUS_DP = 18

/** 描边宽度（dp）。1.5 太细看不出，3 太重像加了黑框，2 刚好。 */
private const val BORDER_WIDTH_DP = 2

/**
 * 提示条底色：深棕，取自 `SushiBgDark` 系。
 *
 * 不用纯黑/深灰 —— 那是 Material 默认 Snackbar 的长相，压在满屏暖色寿司上
 * 像是另一个 App 的控件。带棕调的深色与背景同宗，又足够暗以保证白字对比度。
 *
 * 0xF2 而非全不透明：透出一点下方棋盘，浮层感更明显，同时仍能读清文字。
 */
private val ToastBg = Color(0xF23A2318)

/**
 * 描边色：琥珀，取自 `SushiSecondary`（0xFFFFB347）。
 *
 * 描边是这次改版的关键 —— 没有它，深色块压在暖色背景上边界很脏；有了亮色
 * 描边，浮层与背景之间有了清晰的分界，看着像一枚「牌子」而不是一团阴影。
 */
private val ToastBorder = Color(0xFFFFB347)

/** 文字色：暖白。纯白配棕底偏冷，掺一点黄更协调。 */
private val ToastText = Color(0xFFFFF6E8)
