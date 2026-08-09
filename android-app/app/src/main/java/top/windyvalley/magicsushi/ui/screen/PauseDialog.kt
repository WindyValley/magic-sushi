package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * 暂停对话框。
 *
 * 显示当前分数、剩余时间，并提供三个操作：
 * - 继续：onResume()
 * - 重玩：onRestart()（放弃这一局，开新局）
 * - 保留并返回首页：onQuit()
 *
 * ## 「保留并返回首页」不是「退出」
 *
 * 这个按钮**不结算成绩**，只把当前对局存成快照后回菜单，玩家可以从
 * 菜单的「继续上局」原样回来。所以文案刻意不叫「退出」——那会让玩家
 * 以为这局作废了，从而不敢点。
 *
 * 真正结束一局只有三条路：倒计时归零、点「重新开始」、在结算弹窗上
 * 返回菜单。
 *
 * 暂停时游戏计时器已停止，恢复时由 GameViewModel.onResume() 重新启动。
 * 从后台切回来也停在暂停态，需玩家手动点「继续」（不自动恢复）。
 */
@Composable
fun PauseDialog(
    currentScore: Int,
    remainingSeconds: Int,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onResume,  // 点击外部等同于继续
        properties = DialogProperties(
            dismissOnBackPress = true,    // 返回键等同继续
            dismissOnClickOutside = false  // 但点外部继续（不能误操作）
        ),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF2A1810),
        title = {
            Text(
                text = "⏸ 暂停",
                color = Color(0xFFFFE8C5),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                
                // 分数
                Text(
                    text = "当前分数",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp
                )
                Text(
                    text = currentScore.toString(),
                    color = Color(0xFFFFB347),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 剩余时间
                Text(
                    text = "剩余时间",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp
                )
                Text(
                    text = "${remainingSeconds}s",
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
                // 主操作：继续（实心橙）
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8BC34A),  // 绿色（积极）
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "继续",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 次操作：重玩（描边）
                OutlinedButton(
                    onClick = onRestart,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFB347)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "重新开始",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 退出（透明文字按钮）
                Button(
                    onClick = onQuit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFFAB47BC)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "保留并返回首页",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    )
}