package top.windyvalley.magicsushi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import top.windyvalley.magicsushi.engine.GameHistory
import top.windyvalley.magicsushi.engine.GameRecord
import top.windyvalley.magicsushi.engine.GameRecordCodec

/**
 * 历史记录持久层。
 *
 * ## 为什么用 DataStore 而不是 SharedPreferences
 *
 * [PrefsRepository] 在构造函数里调 `getSharedPreferences()`，那是**主线程
 * 同步读盘**。它只存两个标量（最高分、静音）时代价可忽略；历史记录是
 * 最多 50 条的字符串，再走同步 IO 就说不过去了。
 *
 * DataStore 的读是 `Flow`、写是 `suspend`，天然在 IO 线程，且带事务与
 * 损坏恢复。
 *
 * ## 这一层刻意做得很薄
 *
 * 排序裁剪（[GameHistory]）和编解码（[GameRecordCodec]）都在 engine module
 * 里，是可单测的纯函数。这里只剩 DataStore 读写 —— 没有分支逻辑，
 * 所以不为它写单测（要测就得上 Robolectric 或仪器测试，性价比太低）。
 *
 * 换句话说：**逻辑在能测的地方，IO 在不用测的地方。**
 */
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "magic_sushi_history"
)

class HistoryRepository(private val context: Context) {

    private val dataStore get() = context.historyDataStore

    /**
     * 历史记录流，已按展示顺序排好（分数降序，同分新的在前）。
     *
     * 读盘失败（文件损坏、权限问题）时发射空列表而非抛异常 ——
     * 历史记录不是关键数据，为它让游戏起不来是本末倒置。
     */
    val records: Flow<List<GameRecord>> = dataStore.data
        .catch {
            // DataStore 读取异常（IOException / 损坏）→ 当作没有历史。
            android.util.Log.w("HistoryRepository", "读取历史记录失败，按空列表处理", it)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs ->
            GameHistory.normalize(GameRecordCodec.decode(prefs[KEY_RECORDS]))
        }

    /**
     * 追加一局成绩。
     *
     * 裁剪规则见 [GameHistory.insert]：保留**分数最高**的
     * [GameHistory.MAX_RECORDS] 条，满库后低分局不会被保存。
     *
     * 用 `edit` 的事务性读改写，避免并发写丢数据（例如极端情况下
     * game over 与 quit 几乎同时触发）。
     */
    suspend fun addRecord(record: GameRecord) {
        dataStore.edit { prefs ->
            val existing = GameRecordCodec.decode(prefs[KEY_RECORDS])
            val updated = GameHistory.insert(existing, record)
            prefs[KEY_RECORDS] = GameRecordCodec.encode(updated)
        }
    }

    /**
     * 读一次当前历史（不订阅）。供需要即时判断的场合使用。
     */
    suspend fun getRecordsOnce(): List<GameRecord> = records.first()

    /** 清空历史。目前没有 UI 入口，留给调试与将来的「清除数据」功能。 */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY_RECORDS) }
    }

    companion object {
        private val KEY_RECORDS = stringPreferencesKey("records")
    }
}
