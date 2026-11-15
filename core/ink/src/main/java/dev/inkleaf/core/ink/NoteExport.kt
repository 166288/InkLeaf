package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.TextAnchorChain
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出用的一条笔记。
 *
 * 刻意不复用 `Annotation`：导出格式要稳定，而领域模型会演进。
 * [NoteMarkdown.fromAnnotations] 是唯一一处映射，格式本身只认识这个扁平结构。
 */
data class ExportableNote(
    val kind: String,
    /** 锚点里存下的那句原文。没有可引用文字的笔记（书签、笔迹）为 null。 */
    val quote: String?,
    val noteText: String?,
    /** 固定版式的页号（1 起）。重排格式没有页身份，为 null。 */
    val pageNumber: Int?,
    /** 阅读进度（0..1），用来给没有页号的笔记排序与标注位置。 */
    val ratio: Float,
    val createdAt: Long,
    val colorArgb: Int,
    /** 手写笔迹的段数；其它类型为 0。 */
    val strokeCount: Int = 0,
)

/**
 * 把一本书的笔记导出成 Markdown。
 *
 * 为什么选 Markdown 而不是 PDF / JSON：
 *
 *  - 用户能直接读、直接粘进任何笔记软件；JSON 只有本程序能读。
 *  - 不依赖排版：引用的是锚点里**存下来的原文**，不是按当前字号重新排出来的样子。
 *    换设备、换字号之后导出的内容不会漂。
 *  - 与全库备份（zip）分工明确：备份用来恢复，Markdown 用来带走。
 *
 * 位置按页号（没有页号时按进度）排序，同一位置的按创建时间排 ——
 * 导出文件因此与用户在书里看到的前后顺序一致，而不是随机顺序。
 */
object NoteMarkdown {

    fun fromAnnotations(
        annotations: List<Annotation>,
        strokesOf: (String) -> Int = { 0 },
    ): List<ExportableNote> = annotations.mapNotNull { annotation ->
        when (annotation) {
            is Annotation.Highlight -> annotation.toExportable("高亮")
            is Annotation.MarginNote -> annotation.toExportable("边注")
            is Annotation.Underline -> annotation.toExportable(
                kind = "下划线",
                chain = annotation.anchors.first(),
            )
            is Annotation.Bookmark -> ExportableNote(
                kind = "书签",
                quote = annotation.anchor?.textQuote?.exact,
                noteText = annotation.noteText,
                pageNumber = pageNumberOf(annotation.progressHint.pageKey),
                ratio = annotation.progressHint.docRatio.coerceIn(0f, 1f),
                createdAt = annotation.createdAt,
                colorArgb = annotation.colorArgb,
            )
            is Annotation.Ink -> ExportableNote(
                kind = "手写",
                quote = annotation.anchor?.textQuote?.exact,
                noteText = annotation.noteText,
                pageNumber = pageNumberOf(annotation.anchor?.progressHint?.pageKey)
                    ?: annotation.pages.firstNotNullOfOrNull(::pageNumberOf),
                ratio = (annotation.anchor?.progressHint?.docRatio ?: 0f).coerceIn(0f, 1f),
                createdAt = annotation.createdAt,
                colorArgb = annotation.colorArgb,
                strokeCount = strokesOf(annotation.id),
            )
        }
    }.sortedWith(
        compareBy(
            { it.pageNumber == null },
            { it.pageNumber ?: 0 },
            { it.ratio },
            { it.createdAt },
        ),
    )

    fun render(
        title: String,
        author: String?,
        notes: List<ExportableNote>,
        exportedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(exportedAt).atZone(zone))
        val counts = notes.groupingBy { it.kind }.eachCount()

        return buildString {
            appendLine("# ${title.trim().ifEmpty { "未命名文档" }}")
            appendLine()
            if (!author.isNullOrBlank()) appendLine("- 作者：${author.trim()}")
            appendLine("- 导出时间：$time")
            appendLine(
                "- 笔记：" + if (notes.isEmpty()) {
                    "无"
                } else {
                    listOf("高亮", "边注", "下划线", "书签", "手写")
                        .mapNotNull { kind -> counts[kind]?.let { "$kind $it" } }
                        .joinToString(" · ")
                },
            )
            appendLine()
            if (notes.isEmpty()) {
                appendLine("还没有笔记。")
                return@buildString
            }

            var section: String? = null
            for (note in notes) {
                if (note.kind != section) {
                    section = note.kind
                    appendLine("## $section")
                    appendLine()
                }
                val location = buildString {
                    if (note.pageNumber != null) append("第 ${note.pageNumber} 页")
                    if (note.ratio > 0f) {
                        if (isNotEmpty()) append(" · ")
                        append("${(note.ratio * 100).toInt()}%")
                    }
                }
                if (location.isNotEmpty()) {
                    appendLine("### $location")
                    appendLine()
                }
                note.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                    // 逐行加引用前缀：引文可能跨行，只给第一行加会让后面的行变成普通正文。
                    quote.trim().lines().forEach { line -> appendLine("> $line") }
                    appendLine()
                }
                note.noteText?.takeIf { it.isNotBlank() }?.let { text ->
                    appendLine(text.trim())
                    appendLine()
                }
                if (note.kind == "手写") {
                    appendLine(
                        if (note.strokeCount > 0) {
                            "- 手写笔迹：${note.strokeCount} 段"
                        } else {
                            "- 手写笔迹（段数未记录）"
                        },
                    )
                    appendLine()
                }
            }
        }
    }

    private fun Annotation.Highlight.toExportable(kind: String): ExportableNote =
        toExportable(kind, anchor)

    private fun Annotation.MarginNote.toExportable(kind: String): ExportableNote =
        toExportable(kind, anchor)

    private fun Annotation.toExportable(kind: String, chain: TextAnchorChain): ExportableNote =
        ExportableNote(
            kind = kind,
            quote = chain.textQuote?.exact,
            noteText = noteText,
            pageNumber = pageNumberOf(chain.progressHint.pageKey),
            ratio = chain.progressHint.docRatio.coerceIn(0f, 1f),
            createdAt = createdAt,
            colorArgb = colorArgb,
        )

    private fun Annotation.Underline.toExportable(kind: String, chain: TextAnchorChain): ExportableNote =
        ExportableNote(
            kind = kind,
            quote = chain.textQuote?.exact,
            noteText = noteText,
            pageNumber = pageNumberOf(chain.progressHint.pageKey),
            ratio = chain.progressHint.docRatio.coerceIn(0f, 1f),
            createdAt = createdAt,
            colorArgb = colorArgb,
        )

    private fun pageNumberOf(pageKey: String?): Int? =
        pageKey?.removePrefix("p")?.toIntOrNull()
}
