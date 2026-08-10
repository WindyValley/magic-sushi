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
 * - 退出：onQuit()
 *
 * ## 「退出」只是打开二级确认，本身不执行退出
 *
 * [onQuit] 的调用方（`GameScreen`）不会直接结束或挂起对局，而是弹出
 * `ExitConfirmDialog` 让玩家在「保留进度」和「结束本局」之间选。
 *
 * 这个按钮曾经叫「保留并返回首页」并直接执行挂起 —— 一个按钮绑死一种
 * 语义，想彻底结束这局的玩家被迫留下快照。现在文案回归中性的「退出」，
 * 因为**去留由下一步的确认弹窗决定**，此处不该预设玩家的意图。
 *
 * 真正结束一局有三条路：倒计时归零、点「重新开始」、在确认弹窗上选
 * 「结束本局」。挂起（可恢复）只有一条：确认弹窗上选「保留进度」。
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
                        text = "退出",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    )
}