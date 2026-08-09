package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.ui.theme.SushiBgDark

/**
 * MainMenuScreen — 开始界面（批次 C，Task C2）。
 *
 * 三个入口：【开始游戏】【历史记录】【退出游戏】。
 *
 * ## 为什么它是启动落地屏
 *
 * 改动前 [MainActivity] 直接 `setContent { GameScreen(...) }`，冷启动即开局。
 * 玩家没有「先看历史记录」或「先不玩」的机会，且 `GameViewModel.init` 里的
 * `startGame()` 让倒计时在玩家还没准备好时就开始跑。
 *
 * ## 本屏刻意不持有 ViewModel
 *
 * 菜单不需要游戏状态。把 VM 的引用挡在门外，能保证「在菜单里不会误改对局
 * 状态」这件事由类型系统保证，而不是靠自觉。最高分之类的展示如果将来要加，
 * 应该单独传一个 `Int` 参数进来，而不是把整个 VM 递过来。
 *
 * ## 配色沿用现有设计
 *
 * 背景 [SushiBgDark]（深暖棕），主按钮橙 `0xFFE85D2F`，
 * 次按钮描边 + 米色文字 `0xFFFFE8C5` —— 与 [PauseDialog] / [GameOverDialog]
 * 一致，避免菜单看起来像另一个 App。
 *
 * @param onStartGame 点【开始游戏】。调用方负责导航到 Game 屏并开新局。
 * @param onHistory   点【历史记录】。
 * @param onExit      点【退出游戏】。真正结束进程（用户明确要求），
 *                    由调用方（Activity）执行 `finishAffinity()` + `exitProcess`。
 */
@Composable
fun MainMenuScreen(
    onStartGame: () -> Unit,
    onHistory: () -> Unit,
    onExit: () -> Unit,
    hasSavedRound: Boolean = false,
    onContinueGame: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SushiBgDark)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            // ---- 标题区 ----
            Text(
                text = "🍣",
                fontSize = 72.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Magic Sushi",
                color = Color(0xFFFFE8C5),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "三连成寿司",
                color = Color(0x99FFE8C5),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(56.dp))

            // ---- 继续上次对局（仅在有快照时出现）----
            //
            // 有快照时它是**主操作**（实心橙），「开始游戏」降级为次操作：
            // 玩家上次是被中断的，最可能想接着玩那一局。若把「开始游戏」
            // 继续放在主位，很容易误触而丢掉残局 —— 而快照是恢复即消费的，
            // 丢了就没了。
            if (hasSavedRound) {
                Button(
                    onClick = onContinueGame,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE85D2F),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp),
                ) {
                    Text(
                        text = "继续上局",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // ---- 开始新游戏 ----
            //
            // 有快照时降为描边样式（次操作）：点它会开新局，那个残局就
            // 永久消失了 —— 视觉上不该和「继续上局」等权重。
            if (hasSavedRound) {
                OutlinedButton(
                    onClick = onStartGame,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFFE8C5),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp),
                ) {
                    Text(
                        text = "开始新游戏",
                        fontSize = 17.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                Button(
                    onClick = onStartGame,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE85D2F),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp),
                ) {
                    Text(
                        text = "开始游戏",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ---- 次操作：历史记录 ----
            OutlinedButton(
                onClick = onHistory,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFFE8C5),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp),
            ) {
                Text(
                    text = "历史记录",
                    fontSize = 17.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ---- 退出：低视觉权重，避免误触 ----
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color(0x99FFE8C5),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp),
            ) {
                Text(
                    text = "退出游戏",
                    fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainMenuScreenPreview() {
    MagicSushiTheme {
        MainMenuScreen(onStartGame = {}, onHistory = {}, onExit = {})
    }
}

/** 有中断对局时的菜单：「继续上局」占主位，「开始新游戏」降为次操作。 */
@Preview(showBackground = true, name = "菜单 - 有中断对局")
@Composable
private fun MainMenuScreenWithSavedRoundPreview() {
    MagicSushiTheme {
        MainMenuScreen(
            onStartGame = {},
            onHistory = {},
            onExit = {},
            hasSavedRound = true,
            onContinueGame = {},
        )
    }
}
