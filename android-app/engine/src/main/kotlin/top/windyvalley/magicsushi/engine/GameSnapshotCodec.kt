package top.windyvalley.magicsushi.engine

/**
 * [GameSnapshot] 的字符串编解码。
 *
 * ## 格式
 *
 *     第 1 行：score,combo,remainingSeconds,boardSize
 *     第 2 行：id:type|id:type|...      （共 size*size 格，行优先）
 *
 * 空格写作 `-`。`type` 用 [SushiType.ordinal] 而非 `name`，理由见下。
 *
 * ## 为什么不用 kotlinx.serialization / JSON
 *
 * 与 [GameRecordCodec] 同一个判断：结构固定、字段少，引入序列化框架的
 * 收益抵不上依赖成本。项目已有一套 CSV 风格 codec，保持一致。
 *
 * ## append-only 兼容（吸取 GameRecordCodec 的教训）
 *
 * 第 1 行的字段数用 `>= MIN_HEADER_FIELDS` 判断，**不是 `==`**。
 *
 * [GameRecordCodec] 曾用 `parts.size != 3` 做校验，导致「加第 4 个字段就
 * 静默清空所有老玩家历史」—— 不崩不报错，日志查不到。快照的后果轻一些
 * （丢一局残局而非全部历史），但同样的坑不该踩第二次。
 *
 * 所以：**多余字段忽略，缺失字段用默认值**。未来加「连击倒计时」之类的
 * 字段时，老快照仍能解出来。
 *
 * ## 为什么 type 存 ordinal 而不是 name
 *
 * 快照是**短命数据**（下次打开就被消费掉），不像历史记录要跨版本长期保存。
 * ordinal 更紧凑。而 [SushiType] 的顺序与 drawable 资源命名绑定
 * （`sushi_1.png` .. `sushi_6.png`），本身就不能随意重排 —— 真要重排，
 * 丢一局残局是可接受代价，而历史记录丢不起。
 *
 * ## 解析失败一律返回 null
 *
 * 快照是「有则用，无则新开一局」的可选数据。任何一格解析失败就丢弃整个
 * 快照 —— **绝不返回半个棋盘**。部分恢复出来的棋盘可能带非法状态（比如
 * 悬空的 tile、已经三连的格子），比不恢复更糟。
 */
object GameSnapshotCodec {

    /** 第 1 行至少要有的字段数。多余的忽略，缺失的用默认值。 */
    private const val MIN_HEADER_FIELDS = 4

    /** 空格的表示。 */
    private const val EMPTY_CELL = "-"

    private const val CELL_SEPARATOR = "|"
    private const val FIELD_SEPARATOR = ","
    private const val ID_TYPE_SEPARATOR = ":"

    /**
     * 编码为字符串。
     */
    fun encode(snapshot: GameSnapshot): String {
        val size = snapshot.board.size
        val header = listOf(
            snapshot.score,
            snapshot.combo,
            snapshot.remainingSeconds,
            size,
        ).joinToString(FIELD_SEPARATOR)

        val cells = buildList {
            for (row in 0 until size) {
                for (col in 0 until size) {
                    val tile = snapshot.board.grid.getOrNull(row)?.getOrNull(col)
                    add(
                        if (tile == null) EMPTY_CELL
                        else "${tile.id}$ID_TYPE_SEPARATOR${tile.type.ordinal}"
                    )
                }
            }
        }.joinToString(CELL_SEPARATOR)

        return "$header\n$cells"
    }

    /**
     * 解码。任何格式问题都返回 `null`（丢弃整个快照）。
     */
    fun decode(text: String): GameSnapshot? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null

        val header = lines[0].split(FIELD_SEPARATOR)
        // ⚠️ >= 而非 ==：见类文档「append-only 兼容」。
        if (header.size < MIN_HEADER_FIELDS) return null

        val score = header[0].trim().toIntOrNull() ?: return null
        val combo = header[1].trim().toIntOrNull() ?: return null
        val remainingSeconds = header[2].trim().toIntOrNull() ?: return null
        val size = header[3].trim().toIntOrNull() ?: return null

        // 尺寸必须合理：0 会得到空棋盘，负数或过大值是明显的脏数据。
        // 上限放宽到 32 而不是硬编码 7 —— 万一以后支持不同尺寸棋盘，
        // 老快照不该因为这个校验被无谓丢弃。
        if (size <= 0 || size > 32) return null
        if (score < 0 || combo < 0 || remainingSeconds < 0) return null

        val cells = lines[1].split(CELL_SEPARATOR)
        if (cells.size != size * size) return null

        val types = SushiType.entries
        val grid = ArrayList<List<SushiTile?>>(size)
        var index = 0
        for (row in 0 until size) {
            val rowCells = ArrayList<SushiTile?>(size)
            for (col in 0 until size) {
                val raw = cells[index++].trim()
                if (raw == EMPTY_CELL) {
                    rowCells.add(null)
                    continue
                }
                val parts = raw.split(ID_TYPE_SEPARATOR)
                if (parts.size < 2) return null
                val id = parts[0].toIntOrNull() ?: return null
                val typeOrdinal = parts[1].toIntOrNull() ?: return null
                // id 必须是正数：TileIdGenerator.next() 从 1 起，0 和负数
                // 都不是合法身份（见 TileIdGenerator 文档）。
                if (id <= 0) return null
                val type = types.getOrNull(typeOrdinal) ?: return null
                rowCells.add(
                    SushiTile(id = id, type = type, row = row, col = col)
                )
            }
            grid.add(rowCells)
        }

        return GameSnapshot(
            board = Board(size = size, grid = grid),
            score = score,
            combo = combo,
            remainingSeconds = remainingSeconds,
        )
    }
}
