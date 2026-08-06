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
 */
object GameRecordCodec {

    private const val FIELD_SEPARATOR = ","
    private const val RECORD_SEPARATOR = "\n"

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
        if (parts.size != 3) return null

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
}
