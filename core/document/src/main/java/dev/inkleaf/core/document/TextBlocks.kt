package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizedText
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.TocItem

/**
 * 半开区间 `[start, end)`。
 *
 * 刻意不用 `IntRange`：IntRange 是闭区间，用它表示半开区间是 off-by-one 的经典来源
 * （`until` 与 `..` 的一个字符之差，在锚点系统里就是"笔记差一个字"）。
 */
data class CharSpan(val start: Int, val end: Int) {
    init {
        require(start <= end) { "CharSpan 起点不能大于终点：$start > $end" }
        require(start >= 0) { "CharSpan 起点不能为负：$start" }
    }

    val length: Int get() = end - start
    val isEmpty: Boolean get() = start == end

    fun contains(offset: Int): Boolean = offset in start until end

    override fun toString(): String = "[$start,$end)"
}

/**
 * 块类型。
 *
 * 这个分类存在的原因是**结构型选择器需要块路径**（§6.3.1）。
 * 块切分越稳定，锚点在"改字号、换设备、重排"之后越不容易失配。
 * 因此切分规则必须只依赖文本本身，不依赖排版结果。
 */
enum class BlockKind {
    PARAGRAPH,
    HEADING,

    /** Markdown 围栏代码块内部的行。 */
    CODE,

    /** 围栏本身（``` / ~~~），渲染时隐藏。 */
    CODE_FENCE,
    QUOTE,
    LIST_ITEM,
    RULE,
    IMAGE,
}

/**
 * 一个可锚定的文本块。
 *
 * [span] 是它在**归一化全文**里的区间。注意这里刻意不重新拼接文本：
 * 块文本就是归一化全文的一个切片，所以"块的绝对偏移"与"渲染层拿到的字符偏移"
 * 天然是同一个坐标系，不需要任何换算 —— 换算就是漂移的来源。
 */
data class TextBlock(
    val index: Int,
    val kind: BlockKind,
    /** 标题层级 1..6；非标题为 0。 */
    val headingLevel: Int,
    val span: CharSpan,
    val text: String,
    /**
     * 需要被渲染层"隐藏"的语法标记区间（绝对偏移）。
     *
     * 为什么不直接把标记从文本里删掉：**span 不能改变文本长度**。
     * 一旦删掉 `## ` 三个字符，块内所有偏移都会平移 3，锚点全部错位。
     * 所以标记留在文本里、由渲染层置为透明色 —— 视觉上干净，坐标系不动。
     */
    val markerSpans: List<CharSpan> = emptyList(),
    /**
     * 图片块指向的书内路径（EPUB 的 ZIP 条目名）；非图片块为 null。
     *
     * 它挂在块上而不是按"字符偏移 → href"另存一张表：块是排版与锚定的单位，
     * 图属于块，就不该在别处再存一份"这张图在哪"的平行事实。
     */
    val imageHref: String? = null,
) {
    val absoluteStart: Int get() = span.start
    val absoluteEnd: Int get() = span.end

    /**
     * 结构型选择器用的块路径。
     *
     * 重排格式与固定版式格式用的是同一个全局标识（`blk[i]`）——
     * **刻意不给固定版式加页键**，理由见 `Selector.Structural` 的注释。
     */
    val blockPath: String get() = "blk[$index]"
}

data class SplitResult(
    val blocks: List<TextBlock>,
    val toc: List<TocItem>,
) {
    val blockCount: Int get() = blocks.size

    companion object {
        val EMPTY = SplitResult(emptyList(), emptyList())
    }
}

/**
 * 外部来源的章节标记（EPUB 的 spine；将来 PDF 的书签）。
 *
 * [charOffset] 是**未归一化原文**里的偏移。刻意不用"第几个非空行"这种计数方式：
 * 那依赖"提取器与切分器对空行的判断完全一致"，而这个耦合一旦不成立
 * （例如某一行只由全角空格组成：提取器认为有内容，`isBlank()` 认为没有），
 * 章节会整体错位。偏移经 `NormalizedText.normalizedIndexOf` 落到归一化文本上，
 * 再取"结束位置在其之后的第一个块"，落点因此是确定的。
 */
data class ChapterMark(
    val title: String,
    val charOffset: Int,
    /** 层级，1 起。spine 生成的目录恒为 1；nav / ncx 的目录按嵌套深度给。 */
    val level: Int = 1,
)

/**
 * 块切分器。
 *
 * 一条贯穿全类的规则：**只按行切，不按语义合并跨行内容**。
 * 理由：本工程锚定精度是 L2（行级）。把多行合并成一个块会让"块内偏移"
 * 跨越换行位置，而重排后换行位置一定会变 —— 那样块内偏移就失去了意义。
 * 按行切则每一行的块内偏移永远落在同一行内，重排后仍然有效。
 *
 * 对中文 TXT 这是完全正确的（一行一段）；对"硬折行"的英文 TXT 会显得
 * 每行都是一段（缩进略碎），这是已知取舍，记录在方案 §5.6。
 */
object BlockSplitter {

    /** TXT 里判定为章节标题的行长上限。 */
    const val MAX_TXT_HEADING_CHARS = 40

    /** 章节标记之后还能再跟多少字符（防止把"第一章里提到的那个人最后走了"当标题）。 */
    const val MAX_TXT_HEADING_TAIL_CHARS = 24

    private const val SENTENCE_PUNCTUATION = "。！？；：!?;:…"

    private val TXT_CHAPTER_PATTERNS = listOf(
        Regex("^第[0-9零一二三四五六七八九十百千万两]+[章节節回卷篇部集]"),
        Regex("^(Chapter|CHAPTER|Part|PART|CHAPTER)\\s+([0-9]+|[IVXLCDM]+)"),
        Regex("^(序章|序言|前言|楔子|引子|后记|後記|尾声|尾聲|终章|終章|番外|附录|附錄|自序|代序)"),
    )

    private val MD_HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val MD_QUOTE = Regex("^>\\s?")
    private val MD_LIST = Regex("^([-*+]|\\d{1,9}[.)])\\s+")
    private val MD_FENCE = Regex("^(`{3,}|~{3,})")
    private val MD_IMAGE_ONLY = Regex("^!\\[[^\\]]*\\]\\([^)]*\\)$")

    fun split(kind: DocumentKind, normalized: NormalizedText): SplitResult = when (kind) {
        DocumentKind.MARKDOWN -> splitMarkdown(normalized)
        // TXT / EPUB（提取后的正文）/ PDF（提取后的文本）都按行切
        DocumentKind.TXT, DocumentKind.EPUB, DocumentKind.PDF -> splitPlainText(normalized)
    }

    /**
     * 用外部章节标记替换启发式目录。
     *
     * 只在**有明确章节信息**时调用（EPUB 的 spine）。TXT 没有这种信息，
     * 只能靠 [txtHeadingLevel] 猜，所以 TXT 不走这里 ——
     * "用确定的信息覆盖猜测"是单向的，反过来做没意义。
     */
    fun tocFromChapters(
        split: SplitResult,
        normalized: NormalizedText,
        chapters: List<ChapterMark>,
    ): List<TocItem> {
        val out = ArrayList<TocItem>(chapters.size)
        chapters.forEachIndexed { index, chapter ->
            val offset = normalized.normalizedIndexOf(chapter.charOffset)
            val block = split.blocks.firstOrNull { it.span.end > offset } ?: return@forEachIndexed
            out.add(
                TocItem(
                    id = tocId(block.index) + "-ch$index",
                    title = chapter.title.trim().take(80).ifBlank { "第 ${block.index + 1} 段" },
                    level = chapter.level.coerceIn(1, 6),
                    blockIndex = block.index,
                    charOffset = block.absoluteStart,
                ),
            )
        }
        return out
    }

    // ── 纯文本 ───────────────────────────────────────────────────────────────

    fun splitPlainText(normalized: NormalizedText): SplitResult {
        val text = normalized.text
        if (text.isEmpty()) return SplitResult.EMPTY

        val blocks = ArrayList<TextBlock>()
        val toc = ArrayList<TocItem>()

        for (line in lineSpans(text)) {
            if (line.isEmpty) continue
            val raw = text.substring(line.start, line.end)
            if (raw.isBlank()) continue

            val trimmed = raw.trim()
            val level = txtHeadingLevel(trimmed)
            val kind = when {
                level > 0 -> BlockKind.HEADING
                // 提取器给每张图留了一行占位字符（见 IMAGE_PLACEHOLDER）：
                // 它是一张图，不是一段两字都不到的文字，版式（缩进、行距）也不该按段落算。
                isImagePlaceholderLine(raw) -> BlockKind.IMAGE
                else -> BlockKind.PARAGRAPH
            }
            val block = TextBlock(
                index = blocks.size,
                kind = kind,
                headingLevel = level,
                span = line,
                text = raw,
            )
            blocks.add(block)
            if (kind == BlockKind.HEADING) {
                toc.add(
                    TocItem(
                        id = tocId(block.index),
                        title = trimmed.take(80),
                        level = level,
                        blockIndex = block.index,
                        charOffset = line.start,
                    ),
                )
            }
        }
        return SplitResult(blocks, toc)
    }

    /**
     * TXT 章节判定。
     *
     * 三重约束共同工作，缺一条都会大量误判：
     *   1. 行长上限 —— 标题是短的；
     *   2. 不含句读 —— "第一章里提到的那个人最后走了。" 是正文，句号就是判据；
     *   3. 标记之后的尾巴很短 —— "第一章 起风" 成立，"第一章的写作背景与"
     *      这种被截断的长句不成立。
     * 这是启发式，不追求完美：误判的后果只是目录里多一项，而漏判的后果是
     * 目录不可用。因此三条约束里第 3 条放宽（24 字符）而不是收紧。
     */
    private fun txtHeadingLevel(trimmed: String): Int {
        if (trimmed.isEmpty() || trimmed.length > MAX_TXT_HEADING_CHARS) return 0
        if (trimmed.any { it in SENTENCE_PUNCTUATION }) return 0
        for (pattern in TXT_CHAPTER_PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            if (match.range.first != 0) continue
            val tail = trimmed.substring(match.range.last + 1).trim()
            if (tail.length <= MAX_TXT_HEADING_TAIL_CHARS) return 1
        }
        return 0
    }

    // ── Markdown ─────────────────────────────────────────────────────────────

    fun splitMarkdown(normalized: NormalizedText): SplitResult {
        val text = normalized.text
        if (text.isEmpty()) return SplitResult.EMPTY

        val blocks = ArrayList<TextBlock>()
        val toc = ArrayList<TocItem>()
        var inFence = false
        var fenceChar = ' '

        for (line in lineSpans(text)) {
            if (line.isEmpty) continue
            val raw = text.substring(line.start, line.end)
            if (raw.isBlank()) continue

            // 标记的绝对起点：行首可能有 0..3 个缩进空格
            val contentStart = line.start + (raw.length - raw.trimStart().length)
            val trimmed = raw.trim()

            // 围栏优先于其它一切判定：代码块里的 "# 注释" 不是标题
            val fence = MD_FENCE.find(trimmed)
            if (fence != null) {
                val marker = trimmed[0]
                if (!inFence) {
                    inFence = true
                    fenceChar = marker
                } else if (marker == fenceChar) {
                    inFence = false
                }
                blocks.add(
                    TextBlock(blocks.size, BlockKind.CODE_FENCE, 0, line, raw, listOf(line)),
                )
                continue
            }

            if (inFence) {
                blocks.add(TextBlock(blocks.size, BlockKind.CODE, 0, line, raw))
                continue
            }

            val heading = MD_HEADING.find(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length
                // 隐藏 "#" 与其后的一个空格；文本长度不变，只是不可见
                val markerEnd = minOf(contentStart + level + 1, line.end)
                blocks.add(
                    TextBlock(
                        index = blocks.size,
                        kind = BlockKind.HEADING,
                        headingLevel = level,
                        span = line,
                        text = raw,
                        markerSpans = listOf(CharSpan(contentStart, markerEnd)),
                    ),
                )
                toc.add(
                    TocItem(
                        id = tocId(blocks.size - 1),
                        title = trimmed.dropWhile { it == '#' }.trim().take(80),
                        level = level,
                        blockIndex = blocks.size - 1,
                        charOffset = line.start,
                    ),
                )
                continue
            }

            if (isRule(trimmed)) {
                blocks.add(TextBlock(blocks.size, BlockKind.RULE, 0, line, raw, listOf(line)))
                continue
            }

            if (MD_IMAGE_ONLY.matches(trimmed)) {
                blocks.add(TextBlock(blocks.size, BlockKind.IMAGE, 0, line, raw))
                continue
            }

            val quote = MD_QUOTE.find(trimmed)
            if (quote != null) {
                val markerEnd = minOf(contentStart + quote.value.length, line.end)
                blocks.add(
                    TextBlock(
                        index = blocks.size,
                        kind = BlockKind.QUOTE,
                        headingLevel = 0,
                        span = line,
                        text = raw,
                        markerSpans = listOf(CharSpan(contentStart, markerEnd)),
                    ),
                )
                continue
            }

            val list = MD_LIST.find(trimmed)
            if (list != null) {
                // 列表标记**不隐藏**：它是列表语义的一部分（有序号、有层级），
                // 藏掉之后用户就看不出这是第几项了。
                blocks.add(TextBlock(blocks.size, BlockKind.LIST_ITEM, 0, line, raw))
                continue
            }

            blocks.add(TextBlock(blocks.size, BlockKind.PARAGRAPH, 0, line, raw))
        }

        return SplitResult(blocks, toc)
    }

    private fun isRule(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val c = trimmed[0]
        if (c != '-' && c != '*' && c != '_') return false
        return trimmed.all { it == c || it == ' ' }
    }

    // ── 公共 ─────────────────────────────────────────────────────────────────

    /**
     * 按 `\n` 切出每一行的区间（不含换行符本身）。
     *
     * 末尾若以 `\n` 结束会产生一个空区间，调用方负责跳过 —— 这里刻意不在
     * 切分器里做"是否为空"的判断，让"空行"这个事实对调用方可见。
     */
    fun lineSpans(text: String): List<CharSpan> {
        val out = ArrayList<CharSpan>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                out.add(CharSpan(start, i))
                start = i + 1
            }
            i++
        }
        out.add(CharSpan(start, text.length))
        return out
    }

    private fun tocId(blockIndex: Int) = "toc-$blockIndex"
}
