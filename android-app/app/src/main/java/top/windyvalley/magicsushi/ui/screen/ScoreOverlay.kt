package top.windyvalley.magicsushi.ui.screen

import androidx.compose.animation.core.animateIntAsState
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
    val animatedScore by animateIntAsState(
        targetValue = currentScore,
        animationSpec = tween(durationMillis = 300),
        label = "currentScore"
    )
    
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
