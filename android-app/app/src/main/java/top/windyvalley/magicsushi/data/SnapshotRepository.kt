package top.windyvalley.magicsushi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.windyvalley.magicsushi.engine.GameSnapshot
import top.windyvalley.magicsushi.engine.GameSnapshotCodec

/**
 * 对局快照持久层 —— 断点续玩。
 *
 * ## 与另两个仓库的分工
 *
 * | 仓库 | 存什么 | 生命周期 |
 * |---|---|---|
 * | [PrefsRepository]   | 设置类标量（最高分、静音） | 永久 |
 * | [HistoryRepository] | 最多 50 条对局记录 | 永久 |
 * | 本类 | **一局进行中的现场** | 存一份，恢复即删 |
 *
 * 本类刻意独立：快照是**短命的单份数据**，语义上是「暂停时的存档」，与
 * 前两者的「持久档案」完全不同。混进 PrefsRepository 会让那个类的
 * `Setting<T>` 泛型设计被一个格式复杂、随时作废的字段污染。
 *
 * ## 为什么需要同步写（[saveBlocking]）
 *
 * 快照的写入时机是 `ON_STOP`，而从任务列表划掉应用时：
 *
 *     ON_PAUSE → ON_STOP → （系统随时可杀进程，不保证 onDestroy）
 *
 * `ON_STOP` 返回之后进程可能立即消失，异步落盘会被打断。所以这里提供
 * 阻塞版本，由调用方在 `ON_STOP` 里同步等到写完。
 *
 * 这与 [PrefsRepository.warmUp] 的取舍一致：一次性、有界、
 * 用户不可见 —— 此刻界面已经退到后台，卡几十毫秒无人感知。真正要避免的
 * 是「散落在交互路径上的不可控同步 IO」，不是「一次有明确理由的阻塞」。
 *
 * ## 这一层同样很薄
 *
 * 编解码在 engine 的 [GameSnapshotCodec] 里（纯函数、19 例单测覆盖）。
 * 这里只剩 DataStore 读写，没有分支逻辑 —— 与 [HistoryRepository] 一致：
 * **逻辑在能测的地方，IO 在不用测的地方。**
 */
private val Context.snapshotDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "magic_sushi_snapshot"
)

class SnapshotRepository(private val context: Context) {

    private val dataStore get() = context.snapshotDataStore

    /**
     * 读取快照。没有存档、格式损坏、读盘失败都返回 `null`。
     *
     * 任何异常都按「没有快照」处理 —— 断点续玩是增值功能，为它让游戏
     * 起不来是本末倒置（与 [HistoryRepository.records] 同一原则）。
     */
    suspend fun load(): GameSnapshot? {
        val prefs = dataStore.data
            .catch {
                android.util.Log.w(TAG, "读取对局快照失败，按无快照处理", it)
                emit(emptyPreferences())
            }
            .first()
        val text = prefs[KEY_SNAPSHOT] ?: return null
        return GameSnapshotCodec.decode(text)
    }

    /**
     * 同步保存快照，写完才返回。
     *
     * **只应在 `ON_STOP` 这类「返回后进程可能立即消失」的场景调用。**
     * 其他时机请用 [save]。
     *
     * 失败只记日志不抛 —— 丢一局残局远好过在退到后台时崩溃。
     */
    fun saveBlocking(snapshot: GameSnapshot) {
        try {
            runBlocking { write(snapshot) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "保存对局快照失败", t)
        }
    }

    /** 保存快照（挂起版本）。 */
    suspend fun save(snapshot: GameSnapshot) {
        try {
            write(snapshot)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "保存对局快照失败", t)
        }
    }

    private suspend fun write(snapshot: GameSnapshot) {
        val encoded = GameSnapshotCodec.encode(snapshot)
        dataStore.edit { it[KEY_SNAPSHOT] = encoded }
    }

    /**
     * 清除快照。
     *
     * 快照「恢复即消费」，所以恢复成功后必须调用它，否则玩家正常玩完
     * 这局、下次进游戏又会被拉回那个残局。
     *
     * 同步版本见 [clearBlocking]。
     */
    suspend fun clear() {
        try {
            dataStore.edit { it.remove(KEY_SNAPSHOT) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "清除对局快照失败", t)
        }
    }

    /**
     * 同步清除快照。
     *
     * 用于「这局已经结算入库」的场景：结算和清快照必须一起完成，否则
     * 进程在两者之间死掉，下次启动会恢复出一局**已经入过库**的残局。
     */
    fun clearBlocking() {
        try {
            runBlocking { dataStore.edit { it.remove(KEY_SNAPSHOT) } }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "清除对局快照失败", t)
        }
    }

    private companion object {
        const val TAG = "SnapshotRepository"
        val KEY_SNAPSHOT = stringPreferencesKey("current_round")
    }
}
