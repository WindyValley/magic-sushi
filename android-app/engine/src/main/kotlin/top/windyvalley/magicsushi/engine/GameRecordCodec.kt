package top.windyvalley.magicsushi.engine

/**
 * [GameRecord] 列表的序列化 / 反序列化。
 *
 * ## 为什么在 engine 而不是 app 的 Repository 里
 *
 * 这是纯字符串处理，没有任何 Android 依赖，放这里可以直接单测。
 * app 层的 `HistoryRepository` 于是只剩一层薄薄的 DataStore 读写 ——
 * 那部分没有分支逻辑，不测也不亏。
 *
 * ## 为什么手写而不用 kotlinx.serialization / JSON
 *
 * 三个字段的定长记录，为它引入一个序列化框架不值得（依赖体积、
 * 编译插件、KSP 配置）。`org.json` 倒是 Android 内置，但它在
 * engine module 里不可用（那里没有 android.jar，这是故意的）。
 *
 * 格式：每条记录一行 `score,timestampMillis,isNewRecord`，行间用 `\n`。
 *
 * ```
 * 1200,1717000000000,true
 * 800,1716900000000,false
 * ```
 *
 * ## 容错原则：坏数据丢弃而非崩溃
 *
 * 存储可能被外部改坏、可能是旧版本格式、可能被截断。任何一行解析失败
 * 就跳过那一行，其余照常读出。历史记录不是关键数据，为它崩溃是本末倒置。
 *
 * ## 字段演进契约（**加字段前必读**）
 *
 * 早期实现用 `parts.size != 3` 判定合法行。那意味着一旦给 [GameRecord]
 * 加第 4 个字段，新版本会把**所有老数据**（每行只有 3 段）判成非法并
 * 静默丢弃 —— 玩家升级后历史记录凭空清空，没有崩溃、没有日志、查不出来。
 *
 * 现在的规则是 **append-only + 缺失即默认值**：
 *
 * 1. **新字段只能追加在行尾**，不能插在中间，也不能改已有字段的顺序或含义
 * 2. 解析只要求字段数 **≥ [MIN_FIELDS]**，多出来的字段忽略
 *    （老版本读新数据 → 不认识的尾部字段直接跳过，向后兼容）
 * 3. 读取新字段时用 [fieldOrNull] 取值，取不到就用默认值
 *    （新版本读老数据 → 缺失字段回落默认值，向前兼容）
 *
 * 这样两个方向都不会丢数据。演进时请同步在
 * `GameRecordCodecTest` 里加一条跨版本用例（已有两条可参照）。
 */
object GameRecordCodec {

    private const val FIELD_SEPARATOR = ","
    private const val RECORD_SEPARATOR = "\n"

    /**
     * 一行至少要有的字段数 —— 即 v1 格式的三个字段。
     *
     * ⚠️ 这个值**永远不要往上调**。它代表「最老的、仍需被读出的格式」，
     * 调大就等于宣布放弃比它更老的数据。加字段时只需让新字段可缺失，
     * 不需要改这里。
     */
    private const val MIN_FIELDS = 3

    /**
     * 序列化为单个字符串。空列表返回空串。
     */
    fun encode(records: List<GameRecord>): String =
        records.joinToString(RECORD_SEPARATOR) { r ->
            listOf(
                r.score.toString(),
                r.timestampMillis.toString(),
                if (r.isNewRecord) "1" else "0",
            ).joinToString(FIELD_SEPARATOR)
        }

    /**
     * 反序列化。无法解析的行被静默跳过（见类注释的容错原则）。
     *
     * 空串 / 空白串返回空列表。
     */
    fun decode(raw: String?): List<GameRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull { line -> decodeLine(line) }
    }

    private fun decodeLine(line: String): GameRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val parts = trimmed.split(FIELD_SEPARATOR)
        // ≥ 而非 == ：多出的尾部字段是「更新版本写的、本版本还不认识的」，
        // 忽略即可，不能因此丢掉整行（见类注释的字段演进契约）。
        if (parts.size < MIN_FIELDS) return null

        val score = parts[0].trim().toIntOrNull() ?: return null
        val timestamp = parts[1].trim().toLongOrNull() ?: return null
        val isNewRecord = when (parts[2].trim()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> return null
        }

        // 负分或负时间戳说明数据已损坏。
        if (score < 0 || timestamp < 0) return null

        return GameRecord(
            score = score,
            timestampMillis = timestamp,
            isNewRecord = isNewRecord,
        )
    }

    /**
     * 按下标取字段，越界或空白返回 null。
     *
     * 加新字段时这样用（以第 4 个字段 maxCombo 为例）：
     *
     * ```kotlin
     * val maxCombo = fieldOrNull(parts, 3)?.toIntOrNull() ?: 0
     * ```
     *
     * 关键在于**缺失不算失败**：老数据没有这一段，回落默认值 0，
     * 而不是把整行判为非法。
     */
    @Suppress("unused") // 供将来加字段时使用，先把正确姿势固定下来
    private fun fieldOrNull(parts: List<String>, index: Int): String? =
        parts.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
}
