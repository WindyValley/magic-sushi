package top.windyvalley.magicsushi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import top.windyvalley.magicsushi.engine.GameHistory
import top.windyvalley.magicsushi.engine.GameRecord
import top.windyvalley.magicsushi.engine.HistoryFormatter
import top.windyvalley.magicsushi.ui.theme.MagicSushiTheme
import top.windyvalley.magicsushi.ui.theme.SushiBgDark

/**
 * HistoryScreen — 历史记录界面（批次 C，Task C3）。
 *
 * ## 数据流
 *
 * 直接订阅 `HistoryRepository.records`。**不经过 GameViewModel** ——
 * 历史记录与当前对局无关，从 VM 转一手只会让 VM 承担无关职责。
 *
 * ## 排序不在这里做
 *
 * 列表已由 `GameHistory.normalize()` 在数据层排好（分数降序，同分新的在前）。
 * UI 再排一次就出现了两个排序真相，将来改规则必然漏掉一处。
 * 这里只按下标渲染排名。
 *
 * ## 三态而非两态
 *
 * DataStore 首次读取是异步的，`Flow` 在第一帧还没发射值。若把「还没读到」
 * 和「读到了空列表」合并成一个状态，进入本屏会先闪一下「还没有游戏记录」
 * 再跳成列表。所以用 `null` 表示「加载中」，`emptyList()` 表示「确实没有」。
 *
 * @param records Flow of 历史记录。初始值 `null` 代表尚未读到（加载中）。
 * @param onBack  返回菜单。
 */
@Composable
fun HistoryScreen(
    records: Flow<List<GameRecord>>,
    onBack: () -> Unit,
) {
    // initialValue = null 是「加载中」的信号，不是「空历史」。
    //
    // 类型推断依赖 Flow<out T> 的协变：Flow<List<GameRecord>> 也是
    // Flow<List<GameRecord>?>，所以 initialValue 可以是 null。
    val loaded: List<GameRecord>? by records.collectAsStateWithLifecycle(
        initialValue = null,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SushiBgDark)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "历史记录",
            color = Color(0xFFFFE8C5),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "保留分数最高的 ${GameHistory.MAX_RECORDS} 局",
            color = Color(0x99FFE8C5),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val current = loaded
            when {
                // 加载中：DataStore 还没发第一个值。
                current == null -> CircularProgressIndicator(
                    color = Color(0xFFE85D2F),
                )

                // 确实没有记录。
                current.isEmpty() -> Text(
                    text = "还没有游戏记录\n去玩一局吧 🍣",
                    color = Color(0x99FFE8C5),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(current) { index, record ->
                        HistoryRow(index = index, record = record)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE85D2F),
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "返回",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 单条历史记录。
 *
 * 布局：排名 | 分数（+ 新纪录标记） | 时间
 *
 * @param index 0-based 下标，用于渲染排名
 */
@Composable
private fun HistoryRow(index: Int, record: GameRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0x14FFE8C5),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 排名。固定宽度让分数列对齐 —— 奖牌 emoji 与数字的字宽不同，
        // 不固定宽度时前三行的分数会比后面缩进得多。
        Text(
            text = HistoryFormatter.rankLabel(index),
            color = Color(0xFFFFE8C5),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.score.toString(),
                    color = Color(0xFFFFB347),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (record.isNewRecord) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "🏆 新纪录",
                        color = Color(0xFFFFD700),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Text(
            text = HistoryFormatter.formatTimestamp(record.timestampMillis),
            color = Color(0x99FFE8C5),
            fontSize = 13.sp,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    val sample = listOf(
        GameRecord(score = 2480, timestampMillis = 1786000000000L, isNewRecord = true),
        GameRecord(score = 1920, timestampMillis = 1785900000000L, isNewRecord = false),
        GameRecord(score = 1350, timestampMillis = 1785800000000L, isNewRecord = false),
        GameRecord(score = 640, timestampMillis = 1785700000000L, isNewRecord = false),
    )
    MagicSushiTheme {
        HistoryScreen(records = flowOf(sample), onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenEmptyPreview() {
    MagicSushiTheme {
        HistoryScreen(records = flowOf(emptyList()), onBack = {})
    }
}
