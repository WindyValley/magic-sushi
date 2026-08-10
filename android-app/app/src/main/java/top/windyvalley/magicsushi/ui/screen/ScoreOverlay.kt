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
    // 分数滚动动画。
    //
    // ## 分数下降有两种，只有一种该播动画
    //
    // 游戏进行中分数单调递增（唯一加分点是 GameViewModel 里
    // `score = it.score + totalScore`），所以下降只来自「开新局」。
    // 但开新局的两条路径，玩家的视线位置不同：
    //
    //   结算面板点「再来一局」  屏幕上正显示着本局成绩，归零动画是
    //                          「成绩清空、新的一局开始」的反馈 → 要播
    //
    //   主菜单进游戏            玩家刚从菜单过来，屏幕上不该有上一局的
    //                          任何残留 → 首帧就该是 0，连跳变都不该有
    //
    // 第二条路径由 GameViewModel.onQuit 负责：回菜单时就把 score 清零，
    // 所以进游戏时首帧读到的已经是 0，这里不会看到下降。
    //
    // 于是这里剩下的下降**只有**「再来一局」那一种，正常播动画即可 ——
    // 不需要在 UI 层区分来源。判据放在状态离开对局时，比放在动画里更早
    // 也更稳。
    //
    // ## 为什么用 Animatable 而不是 animateIntAsState
    //
    // 初值需要显式控制。animateIntAsState 首次组合时直接取目标值，而这里
    // 要的是「记住当前值，后续变化才动画」—— 语义一样但拿不到句柄，将来
    // 若要再加方向判断也无处下手。
    val scoreAnim = remember { Animatable(currentScore, Int.VectorConverter) }
    LaunchedEffect(currentScore) {
        scoreAnim.animateTo(currentScore, animationSpec = tween(durationMillis = 300))
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
