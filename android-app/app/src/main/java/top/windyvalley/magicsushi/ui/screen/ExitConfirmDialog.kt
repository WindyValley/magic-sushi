package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * 退出对局的二级确认弹窗。
 *
 * ## 为什么需要二级确认
 *
 * 暂停面板的第三个按钮曾经直接执行「保留并返回首页」—— 一个按钮绑死一种
 * 语义，玩家没有选择权：想彻底结束这局的人被迫留下一个快照，下次进菜单
 * 还得再面对一次「继续上局」。而把按钮改叫「退出」又会让想保留的人不敢点。
 *
 * 根因是**退出**本身不是一个动作，而是一个岔路口。所以这里不再猜玩家的
 * 意图，而是问他。
 *
 * ## 文案为什么不叫「丢弃本局」
 *
 * 「丢弃」会让玩家以为**成绩也作废了**，而实际行为并非如此：手动退出算
 * 正式成绩，分数照样入历史、照样参与最高分结算（见 `RoundSettlement`）。
 * 被丢弃的只有「继续玩下去的机会」。
 *
 * 所以叫「结束本局」，并把「成绩计入历史」放在正文里说明 —— 玩家点之前
 * 就知道会发生什么，而不是点完之后才发现。
 *
 * ## 说明写正文，按钮只放动作
 *
 * 该说明曾经直接写进按钮（「结束本局并退出（成绩计入历史）」），15 个字
 * 在按钮里过于拥挤，窄屏还会折行。按钮标签只承载**动作**，解释性内容
 * 一律放正文 —— 那里有整行宽度可用，也不必为了塞字而压小字号。
 *
 * ## 0 分时为什么只有一个退出选项
 *
 * 0 分意味着一次消除都没完成，快照里除了初始棋盘什么都没有，而初始棋盘
 * 随时能重开一个。此时给出「保留进度」是个假选项：点了它，菜单会多一个
 * 「继续上局」，点进去与「开始新游戏」几乎无差别。
 *
 * 所以 0 分直接按不保留处理（用户决定），弹窗退化为「退出 / 取消」——
 * 不摆一个点了等于没点的按钮。判据见 `RoundExitOptions.canKeepProgress`。
 *
 * @param canKeepProgress  是否值得保留进度。由
 *   `RoundExitOptions.canKeepProgress` 算出，决定弹窗是三按钮还是两按钮。
 * @param currentScore     当前分数，展示在正文里帮玩家决策。
 * @param remainingSeconds 剩余秒数，同上。
 * @param onKeepAndExit    保留进度并退出（挂起本局，不结算）。
 *                         `canKeepProgress == false` 时不会被调用。
 * @param onFinishAndExit  结束本局并退出（结算入库、清快照）。
 * @param onCancel         取消，回到暂停面板。
 */
@Composable
fun ExitConfirmDialog(
    canKeepProgress: Boolean,
    currentScore: Int,
    remainingSeconds: Int,
    onKeepAndExit: () -> Unit,
    onFinishAndExit: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        // 返回键 / 点外部一律视为取消 —— 二级确认的默认结果必须是「什么都
        // 不发生」。误触退出丢掉一局正是这个弹窗要防的事。
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color(0xFF2A1810),
        title = {
            Text(
                text = "退出这一局？",
                color = Color(0xFFFFE8C5),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (canKeepProgress) {
                        "当前 $currentScore 分，还剩 ${remainingSeconds}s。\n" +
                            "可以先保留这一局，之后从首页继续；\n" +
                            "结束本局则成绩计入历史。"
                    } else {
                        // 0 分（或这局已无法恢复）：说清「不保留」是因为
                        // 没东西可保留，避免玩家以为是功能坏了。
                        "这一局还没有得分，退出后不会保留。"
                    },
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                if (canKeepProgress) {
                    // 主操作：保留。放在最上面且用实心按钮 —— 大多数中途
                    // 退出的玩家是想回来的。
                    Button(
                        onClick = onKeepAndExit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8BC34A),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "保留进度",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 结束本局。
                //
                // ⚠️ 文案必须短。曾经叫「结束本局并退出（成绩计入历史）」，
                // 15 个字在按钮里过于拥挤（窄屏还会折行或被截断）。
                // 「成绩计入历史」属于**说明**而非标签，已挪到正文，
                // 那里有完整的一行可用。按钮只留动作本身。
                //
                // 有分 / 0 分文案不同：0 分时没有成绩可言，说「结束本局」
                // 反而像在暗示有什么东西被结算了，直接叫「退出」。
                OutlinedButton(
                    onClick = onFinishAndExit,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFB347),
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (canKeepProgress) "结束本局" else "退出",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 取消：视觉最弱，但点击区域与其他按钮同宽 —— 后悔的人
                // 不该需要瞄准。
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color(0xCCFFFFFF),
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "取消",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
    )
}
