package top.windyvalley.magicsushi.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 屏幕底部分数条。
 * - 大字号：当前分（48sp）
 * - 小字号：最高分（24sp）
 * - 数字滚动动画
 *
 * ## 为什么这里没有「破纪录」闪烁效果
 *
 * 曾经有：最高分变大时闪一次橙色 + 显示「新纪录！」1 秒（连同 engine 里的
 * `HighScoreRules.shouldCelebrateHighScore` 一套判据和 6 条测试）。
 *
 * 但它**在当前架构下不可能被看到**。`state.highScore` 上升只有一个写入点
 * （`GameViewModel.recordCurrentRound`），而那个函数只被 `onGameOver` /
 * `onRestart` / `onQuit` 调用 —— 三者都意味着本局已经结束。最高分变化的
 * 那一刻 `phase` 已是 `GAME_OVER`，`GameOverDialog` 全屏盖在分数条上面，
 * 1 秒的动画在遮挡下跑完。
 *
 * 换句话说：游戏进行中最高分**永远不变**，它只在结算时更新。这个效果等的
 * 事件，在它可见的时候从不发生。
 *
 * 玩家真正看到的「🎉 新纪录！」在 [GameOverDialog] 里，走的是
 * `state.isNewRecord`（结算时同步写入，不经 Flow）。那条路径是有效的。
 *
 * ⚠️ 若将来改成「游戏中实时更新最高分」（比如当前分超过纪录就立刻顶上去），
 * 这个效果才有意义，届时再加回来 —— 且要注意最高分是异步 Flow 派生的，
 * 冷启动 `0 → 真实值` 的跳变不能误判成破纪录。
 */
@Composable
fun ScoreOverlay(
    currentScore: Int,
    highScore: Int,
    modifier: Modifier = Modifier
) {
    // 分数滚动动画：只在**上升**时播，下降时直接跳到位。
    //
    // ## 为什么要区分方向
    //
    // 游戏进行中分数是单调递增的（唯一的加分点是 GameViewModel 里
    // `score = it.score + totalScore`），所以分数**下降**只有一个来源：
    // 新开一局把 state 重置回 0。
    //
    // 那次 300ms 的回滚动画在玩家眼里是「上一局的成绩在倒数」—— 一个
    // 已经结束的对局还在界面上动，语义是错的。真正该动的只有得分那一刻。
    //
    // ## 为什么不用 animateIntAsState
    //
    // 那个 API 只接受目标值，方向判断得靠它内部的上一帧状态，拿不到也
    // 干预不了。换成 Animatable 后「上升播动画 / 下降直接跳」是一行显式
    // 的 if，读代码的人不需要推断框架行为。
    val scoreAnim = remember { Animatable(currentScore, Int.VectorConverter) }
    LaunchedEffect(currentScore) {
        if (currentScore < scoreAnim.value) {
            // 重置（新开一局）：立刻归位，不播回滚动画。
            scoreAnim.snapTo(currentScore)
        } else {
            scoreAnim.animateTo(currentScore, animationSpec = tween(durationMillis = 300))
        }
    }
    val animatedScore = scoreAnim.value
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC2A1810))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "分数",
                color = Color(0xCCFFFFFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = animatedScore.toString(),
                color = Color(0xFFFFB347),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Column(
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "最高分",
                color = Color(0xCCFFFFFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = highScore.toString(),
                color = Color(0xFFFFE8C5),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
