package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode

/**
 * 时间归零时的结算弹窗。
 * - 大字号显示最终分
 * - 显示最高分
 * - 新纪录时显示"新纪录！" + 庆祝动画
 * - "再玩一次" 按钮（主操作）
 * - "返回主菜单" 按钮（次操作）
 */
@Composable
fun GameOverDialog(
    finalScore: Int,
    highScore: Int,
    isNewRecord: Boolean,
    onRestart: () -> Unit,
    onBackToMenu: () -> Unit = {},  // ✅ 新增参数
    onDismiss: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "celebrate")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isNewRecord) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "celebrateScale"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF2A1810),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isNewRecord) "🎉 新纪录！" else "游戏结束",
                    color = if (isNewRecord) Color(0xFFE85D2F) else Color(0xFFFFE8C5),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.scale(if (isNewRecord) scale else 1f)
                )
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "本次得分",
                    color = Color(0xCCFFFFFF),
                    fontSize = 16.sp
                )
                Text(
                    text = finalScore.toString(),
                    color = Color(0xFFFFB347),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "历史最高",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp
                )
                Text(
                    text = highScore.toString(),
                    color = Color(0xFFFFE8C5),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // 主操作：再玩一次（实心橙）
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE85D2F),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "再玩一次",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // ✅ 次操作：返回主菜单（透明 + 描边）
                OutlinedButton(
                    onClick = onBackToMenu,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFE8C5)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "返回主菜单",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    )
}
