package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import top.windyvalley.magicsushi.ui.theme.SushiBgDark

/**
 * 设置页（单层，无子页）。
 *
 * 两个分区：
 *  - **音效**    —— 静音开关
 *  - **数据**    —— 清空历史记录（带二次确认）
 *  - **关于**    —— 版本号
 *
 * ## 为什么这个页面值得单独存在
 *
 * `GameViewModel.toggleMute()` 从批次 A 起就实现完整、接线正确（
 * `SoundPlayer` 读 `PrefsRepository` 的 mutedProvider，`GameState.isMuted`
 * 由 mutedFlow 投影），但**从来没有任何 UI 调用它** —— 一个零入口的功能。
 * 玩家无法关掉音效，而代码看起来功能齐备。
 *
 * ## 为什么没有「清空最高分」单独按钮
 *
 * 最高分不是独立存储的数据，而是**历史记录的派生值**（见
 * `HighScoreDerivation`）—— 历史清空后 `max(emptyList()) = 0`，最高分自然
 * 归零，没有第二处需要清。
 *
 * 所以设置页只有一个清空项：「清空历史记录」，它清掉历史列表 + 未完成对局
 * 快照，最高分随之归零。
 *
 * ## 清空数据为什么要二次确认
 *
 * 这是一个**不可撤销**的删除。惯例与退出对局一致（见 [ExitConfirmDialog]）：
 * 破坏性操作一律先问一次，且确认框的默认结果（返回键 / 点外部）是取消。
 *
 * @param isMuted        当前是否静音（来自 `GameState.isMuted`，即 prefs 的投影）。
 * @param historyCount   当前历史记录条数。决定副标题文案和按钮是否可点。
 *                       不需要额外传最高分 —— 它是历史的派生值，
 *                       历史为空时必然为 0（见 `HighScoreDerivation`）。
 * @param versionName    版本号，由调用方从 BuildConfig 传入（UI 层不直接
 *                       依赖 BuildConfig，便于预览与测试）。
 * @param onToggleMute   切换静音。
 * @param onClearHistory 清空记录：历史 + 快照（最高分随之归零，已经过二次确认）。
 * @param onAbout        进入关于页（项目信息与版权声明）。
 * @param onBack         返回上一屏。
 */
@Composable
fun SettingsScreen(
    isMuted: Boolean,
    historyCount: Int,
    versionName: String,
    onToggleMute: () -> Unit,
    onClearHistory: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    // 清空历史的确认框是否显示。
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SushiBgDark)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color(0xFFFFE8C5),
                    )
                }
                Text(
                    text = "设置",
                    color = Color(0xFFFFE8C5),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ================= 音效 =================
                SectionTitle("音效")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "游戏音效",
                            color = Color(0xFFFFE8C5),
                            fontSize = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            // 开关标签描述的是「音效开着吗」，而底层字段是
                            // isMuted（静音吗）—— 两者相反。副标题明说当前
                            // 状态，避免玩家对着一个反义开关猜。
                            text = if (isMuted) "已关闭" else "已开启",
                            color = Color(0x99FFE8C5),
                            fontSize = 13.sp,
                        )
                    }
                    Switch(
                        // 开关的 checked 表示「音效开启」= !isMuted。
                        checked = !isMuted,
                        onCheckedChange = { onToggleMute() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF8BC34A),
                            uncheckedThumbColor = Color(0xFFFFE8C5),
                            uncheckedTrackColor = Color(0x33FFE8C5),
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ================= 数据 =================
                SectionTitle("数据")

                DangerRow(
                    title = "清空历史记录",
                    subtitle = if (historyCount > 0) "共 $historyCount 条" else "暂无记录",
                    // 只看 historyCount 就够了。
                    //
                    // 最高分是历史记录的派生值（HighScoreDerivation），
                    // 「历史空但仍有最高分」在结构上不可能出现 —— 曾经这里
                    // 要判 `historyCount > 0 || highScore > 0`，那是双份真相
                    // 时代的产物。
                    enabled = historyCount > 0,
                    onClick = { showClearConfirm = true },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ================= 关于 =================
                SectionTitle("关于")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = "版本",
                        color = Color(0xFFFFE8C5),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = versionName,
                        color = Color(0x99FFE8C5),
                        fontSize = 15.sp,
                    )
                }

                // 进关于页。
                //
                // 关于页是与本页**平级**的 `AppScreen.About`，不是嵌套子页 ——
                // `AppScreen.kt` 把「设置页的多级子页」列为该换
                // navigation-compose 的信号，而这里导航状态仍是扁平的
                // `when`，只是多了一条 Settings → About 的边。
                //
                // 从设置进入而非主菜单：关于页在设置里是通行习惯，且主菜单
                // 已有四个按钮，再加一个会稀释「开始游戏」的视觉权重。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAbout)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "关于本作",
                        color = Color(0xFFFFE8C5),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // 用矢量图而非字符 `›` —— 字符受系统字体限制，缺字形就是
                    // 豆腐块；ImageVector 是 APK 自带的路径数据，任何设备一致。
                    // KeyboardArrowRight 在 material-icons-core 里（与 ArrowBack
                    // 同一批），不增加依赖也不增加体积。
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        // 整行的语义由「关于本作」承载，箭头只是装饰。
                        contentDescription = null,
                        tint = Color(0x99FFE8C5),
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // ---- 清空的二次确认 ----
    //
    // 与 ExitConfirmDialog 同一套惯例：破坏性操作先问，返回键 / 点外部
    // 一律取消。文案里明说「不可恢复」—— 这没有撤销入口。
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            shape = RoundedCornerShape(16.dp),
            containerColor = Color(0xFF2A1810),
            title = {
                Text(
                    text = "清空历史记录？",
                    color = Color(0xFFFFE8C5),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Text(
                    // 三句话各有必要：
                    //  1. 不可恢复 —— 这是破坏性操作的核心告知
                    //  2. 未完成的对局也会清 —— 否则玩家会发现菜单上的
                    //     「继续上局」凭空消失，而确认框没提过
                    //  3. 最高分也会归零 —— ⚠️ 这句**必须**在。按钮叫
                    //     「清空历史记录」，玩家不会想到最高分也在范围内；
                    //     不说清楚就是让他在不知情的前提下丢掉长期成就。
                    text = "全部对局记录将被删除，不可恢复。\n" +
                        "未完成的对局也会一并清除。\n" +
                        "最高分也会归零。",
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    // 破坏性操作用描边而非实心：实心是「推荐操作」的视觉
                    // 语言，而这里推荐的是取消。
                    OutlinedButton(
                        onClick = {
                            onClearHistory()
                            showClearConfirm = false
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFEC407A),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "清空",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showClearConfirm = false },
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
}

/** 分区标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color(0xFFFFB347),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * 一行破坏性操作。
 *
 * `enabled = false` 时整行变暗且不可点 —— 没有记录可清时，让按钮可点然后
 * 弹一个「清空了 0 条」的确认框是在浪费玩家的一次决策。
 */
@Composable
private fun DangerRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Color(0xFFFFE8C5) else Color(0x66FFE8C5),
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0x99FFE8C5),
                fontSize = 13.sp,
            )
        }
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(0xFFEC407A),
                disabledContentColor = Color(0x66FFE8C5),
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.widthIn(min = 88.dp),
        ) {
            Text(text = "清空", fontSize = 14.sp)
        }
    }
}
