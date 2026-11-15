package dev.inkleaf.core.ink

/**
 * 笔记的 JSON 导出（方案 §7.4 的第四形态，优先级最低但用途最明确）。
 *
 * ## 它不是备份
 *
 * 备份是 `:core:data` 的全库 zip（含正文副本、笔迹点集、blob 文件，能原样恢复）。
 * 这份 JSON **不含墨迹点集**，也**不能导回本程序** —— 它是给"别的程序读"的：
 * 脚本统计、导入 Obsidian 的 Dataview、扔进数据库做分析。
 *
 * 为什么不像方案初稿那样连 `strokes[{anchor, input(base64)}]` 一起导出：
 * 那是把 `InkCodec` 的内部格式暴露成对外契约。一旦有人按它写了解析器，
 * 我们改一次编码版本就等于破坏一次外部生态 —— 而这里没有任何机制能通知那些人。
 * 要拿点集请用 zip 备份（它本来就是"给本程序自己"的格式，可以随版本演进）。
 *
 * ## 为什么手写 JSON 而不是 kotlinx.serialization
 *
 * 只需要一个方向（对象 → 字符串），字段少、结构平；而 `:core:ink` 目前没有
 * 引入序列化运行时。为一个导出格式拉一个依赖，会让"笔迹层"这个纯几何模块
 * 多一个与几何无关的包袱。代价是要自己保证转义正确 —— 所以转义被抽成
 * [escape] 并有单独的测试（引号/换行/控制字符/中文/emoji 各一条）。
 */
object NoteJson {

    /** 当前格式版本。**加字段不改它，删改字段必须加**（外部解析器据此判断）。 */
    const val VERSION: Int = 1

    /**
     * 生成整份 JSON。
     *
     * 字段顺序固定（人读的时候顺序就是心智模型），缩进两格 ——
     * 这份文件的第一读者很可能是用户自己用编辑器打开看一眼。
     */
    fun render(
        title: String,
        author: String?,
        docId: String,
        kind: String,
        notes: List<ExportableNote>,
        exportedAt: Long,
    ): String = buildString {
        append("{\n")
        append("  \"app\": \"InkLeaf\",\n")
        append("  \"version\": $VERSION,\n")
        append("  \"exportedAt\": $exportedAt,\n")
        append("  \"document\": {\n")
        append("    \"id\": ${quote(docId)},\n")
        append("    \"title\": ${quote(title)},\n")
        append("    \"author\": ${author?.let(::quote) ?: "null"},\n")
        append("    \"kind\": ${quote(kind)}\n")
        append("  },\n")
        append("  \"notes\": [")
        if (notes.isEmpty()) {
            append("]\n")
        } else {
            append("\n")
            notes.forEachIndexed { index, note ->
                append("    {")
                append("\"kind\": ${quote(note.kind)}, ")
                append("\"page\": ${note.pageNumber ?: "null"}, ")
                append("\"ratio\": ${ratio(note.ratio)}, ")
                append("\"createdAt\": ${note.createdAt}, ")
                append("\"color\": ${quote(colorHex(note.colorArgb))}")
                note.quote?.let { append(", \"quote\": ${quote(it)}") }
                note.noteText?.let { append(", \"text\": ${quote(it)}") }
                if (note.kind == "手写") append(", \"strokeCount\": ${note.strokeCount}")
                append("}")
                if (index != notes.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
        }
        append("}\n")
    }

    /**
     * 一个字符串值（带引号）。
     *
     * 转义表按 JSON 规范的最小集合：`"` `\` 与 0x00–0x1F 的控制字符。
     * 常见但**不必**转义的（中文、emoji）一律原样输出 —— 把中文转成 `\u` 序列
     * 会让文件变成一坨没人愿意打开的乱码，而 JSON 本来就是 UTF-8。
     */
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        append(escape(value))
        append('"')
    }

    fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }

    /** 进度保留三位小数：0.123456 这种精度对任何下游都没有意义，只会让 diff 变吵。 */
    private fun ratio(value: Float): String {
        val clamped = value.coerceIn(0f, 1f)
        val scaled = (clamped * 1000f).toInt()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
    }

    /** `#AARRGGBB`：比十进制整数更容易被别的工具直接当颜色用。 */
    private fun colorHex(argb: Int): String = "#%08X".format(argb)
}
