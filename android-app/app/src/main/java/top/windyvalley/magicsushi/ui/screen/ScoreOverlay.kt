package top.windyvalley.magicsushi.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import top.windyvalley.magicsushi.engine.HighScoreRules

/**
 * 屏幕底部分数条。
 * - 大字号：当前分（48sp）
 * - 小字号：最高分（24sp）
 * - 数字滚动动画
 * - 最高分更新时闪烁
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
    
    var previousHigh by remember { mutableStateOf(highScore) }
    var highJustChanged by remember { mutableStateOf(false) }
    LaunchedEffect(highScore) {
        // FIX_PLAN D8：判据不能只看「最高分变大了」。
        //
        // 设置迁到 DataStore 后最高分是异步装载的，冷启动时这里会先收到
        // 占位值 0、随后被真实值（比如 500）替换。那次 0 → 500 的跳变同样
        // 满足「变大了」，于是玩家一进游戏就会看到一次莫名的破纪录庆祝。
        //
        // 真正的区分点是**本局是否已经得过分**：异步装载发生在开局前，
        // 那时 currentScore 必然为 0。规则连同两个方向的用例都在
        // engine 的 HighScoreRulesTest 里锁死。
        if (HighScoreRules.shouldCelebrateHighScore(
                previousHigh = previousHigh,
                newHigh = highScore,
                currentScore = currentScore,
            )
        ) {
            highJustChanged = true
            kotlinx.coroutines.delay(1000)
            highJustChanged = false
        }
        previousHigh = highScore
    }
    
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
            AnimatedVisibility(
                visible = highJustChanged,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "新纪录！",
                    color = Color(0xFFE85D2F),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = highScore.toString(),
                color = if (highJustChanged) Color(0xFFE85D2F) else Color(0xFFFFE8C5),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
