package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizationProfiles
import dev.inkleaf.core.anchoring.NormalizedText
import dev.inkleaf.core.anchoring.SourceFingerprint
import dev.inkleaf.core.anchoring.TextNormalizer
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.TocItem

/**
 * 固定版式（PDF）的文档会话。
 *
 * 与 [ReflowableDocumentSession] 的差别是**坐标系**上的差别，不是实现细节上的差别：
 *
 *   - 重排格式：行盒由 `StaticLayout` 现算，字号来自主题，改字号 → 行盒全变；
 *   - 固定版式：行盒由 PDF 自己的文本层给出，字号来自文本层，
 *     改应用字号**一个行盒都不动**（页就是页，纸不会因为读者把字调大而变大）。
 *
 * 因此这个类**不复用** `StaticLayout` 那套东西，而是把文本层的行直接变成 [LineBox]。
 * 唯一与重排路径共享的是"块"的定义与指纹算法 —— 锚点链、行盒选择器、解析链
 * 在两种版式下必须是同一套语义，否则 PDF 的笔记会长成另一种东西。
 *
 * ## 文档坐标系
 *
 * 页内坐标是稳定量（固定版式不会重排），所以文档坐标 = 页内坐标 + 页偏移：
 *
 * ```
 * x = metrics.marginLeftPx + 页内 x
 * y = pageTopPx(页号)    + 页内 y      pageTopPx(i) = i * stridePx
 * ```
 *
 * **坐标系的定义权在这里**：视图（M5b 的翻页视图）必须用 [pageTopPx] 去摆页面位图，
 * 不允许自己另算一套"页间距" —— 两套算法一旦差几个像素，症状就是
 * "笔迹与它标注的那一行错开一点"，而用户只会怀疑自己没记准。
 * 缩放同理：它是视图层叠在文档坐标之上的变换（与页左边界一样），不改变坐标系。
 *
 * ## 块与块路径
 *
 * 一行 = 一块（与 TXT 同一条规则，走同一个 [BlockSplitter.split]），块路径仍然是
 * 从 0 开始的全局 `blk[i]`。**刻意不在路径里加页键**：块路径的解析只有一个地方
 * （[DocumentSession.blockIndexFromPath]），加页键就会分叉出第二种路径格式，
 * 而"两种格式的解析"正是本工程反复吃过亏的错误模式；页号该出现的地方是
 * [Selector.ProgressHint.pageKey]（写入进度兜底时带出）。
 *
 * ## 没有文本层的页
 *
 * 扫描页的行列表为空 → 这些页**没有块、也没有行盒**：落在它上面的笔迹无法按行锚定。
 * 这不是错误（"强制只读打开"要能进得来），但界面必须如实说出来，
 * 见 [hasTextLayer] 与 `ScannedPdfAssessment`。
 */
class PdfDocumentSession private constructor(
    override val docId: String,
    /** 全部页，按页序。页内尺寸与文本层都在这里，不会随排版参数改变。 */
    val pages: List<PdfPageFacts>,
    /** 文本层原文（未归一化，按行拼接）。与导入时的 `DecodedText.text` 是同一个值。 */
    val rawText: String,
    override val normalized: NormalizedText,
    override val fingerprint: String,
    private val split: SplitResult,
    /** 展平的文本层行（页序 + 行序），下标与 [blockIndexOfLine] 对齐。 */
    private val flatLines: List<PdfTextLine>,
    /** 文本层行 → 全局块下标；-1 表示这一行在归一化之后是空行（没有块可挂）。 */
    private val blockIndexOfLine: IntArray,
    /** 全局块下标 → 页下标。**没有** -1：每一块都来自某一行，也就必然属于某一页。 */
    private val pageOfBlock: IntArray,
    /** 全局块下标 → [flatLines] 下标。 */
    private val lineOfBlock: IntArray,
) : DocumentSession {

    override val kind: DocumentKind get() = DocumentKind.PDF

    override val profile: NormalizationProfile get() = NormalizationProfiles.FIXED_LAYOUT

    override val blocks: List<TextBlock> get() = split.blocks

    /** 目录：先用正文启发式（与 TXT 同一条规则）。PDF 自带的 outline 要等平台 API 确认，见 design/M5 §8。 */
    override val toc: List<TocItem> get() = split.toc

    /**
     * 页步长：页高不一致时按**最大**页高排布，保证任何一页都不会压到下一页。
     *
     * 用最大值而不是各页自己的高度，是为了让 [pageTopPx] 是一个可以逐个累加的量
     * （视图滚动到第 n 页只需要一次乘法）。
     */
    val stridePx: Float = pages.maxOfOrNull { it.heightPx }?.plus(PAGE_GAP_PX) ?: 0f

    /** 最高的一页的高度（不含页间空隙）。 */
    val maxPageHeightPx: Float = pages.maxOfOrNull { it.heightPx } ?: 0f

    /** 最宽的一页的宽度。视图的横向基准宽度就是它。 */
    val maxPageWidthPx: Float = pages.maxOfOrNull { it.widthPx } ?: 0f

    /** 整篇（全部页叠起来）的高度。视图的滚动范围就是它。 */
    val stackHeightPx: Float
        get() = if (pages.isEmpty()) 0f else pageTopPx(pages.size - 1) + maxPageHeightPx

    /** 取到了文本层吗（有页但一行都没有时为 false：扫描件）。 */
    val hasTextLayer: Boolean get() = blocks.isNotEmpty()

    /** 第 [index] 页在文档坐标里的上边界。见类注释：坐标系的定义权在这里。 */
    fun pageTopPx(index: Int): Float {
        require(index in pages.indices) { "页号越界：$index，共 ${pages.size} 页" }
        return index * stridePx
    }

    /** 某个字符偏移落在第几页。取不到返回 -1。 */
    fun pageIndexOfChar(offset: Int): Int {
        val blockIndex = blockIndexOfChar(offset)
        if (blockIndex < 0) return -1
        return pageOfBlock[blockIndex]
    }

    /** 某个块来自第几页。 */
    fun pageIndexOfBlock(blockIndex: Int): Int =
        pageOfBlock.getOrElse(blockIndex) { -1 }

    override fun blockIndexOfChar(offset: Int): Int = ReflowableDocumentSession.blockIndexOf(blocks, offset)

    /**
     * 固定版式没有重排：**这里必然抛异常**。
     *
     * 为什么不返回一个空排版：空排版会让调用方拿到"零行"的排版结果继续往下走，
     * 表现为"页面一片空白且没有任何提示" —— 而真相是调用方用错了方法。
     * 宁可当场炸掉，也不要一个看起来正常但什么都没有的结果。
     * 行盒请用 [lineBoxes]。
     */
    override fun layout(metrics: PageLayoutMetrics, theme: DocumentTheme): ReflowableTextLayout =
        throw UnsupportedOperationException(
            "固定版式（PDF）没有重排：行盒来自 PDF 文本层，请用 PdfDocumentSession.lineBoxes。",
        )

    override fun textOfLine(line: LineBox): String {
        val blockIndex = DocumentSession.blockIndexFromPath(line.anchor.blockPath) ?: return ""
        val block = blocks.getOrNull(blockIndex) ?: return ""
        val start = (block.absoluteStart + line.anchor.startOffset)
            .coerceIn(block.absoluteStart, block.absoluteEnd)
        val end = (block.absoluteStart + line.anchor.endOffset)
            .coerceIn(start, block.absoluteEnd)
        return normalized.substring(start, end)
    }

    /**
     * 字号来自**文本层**，不来自主题 —— 固定版式下"应用字号"不参与排版。
     *
     * 主题只作为兜底：平台偶尔会给出 0 或非有限字号（取不到），此时用一个常量把它撑住，
     * 好过让 em → px 除零。这个兜底值只影响笔迹宽度的换算比例，不影响位置：
     * 位置是落笔时冻结的几何（M3.1），与当前字号无关。
     */
    override fun fontSizePxOf(blockPath: String, theme: DocumentTheme): Float {
        val blockIndex = DocumentSession.blockIndexFromPath(blockPath) ?: return theme.textSizePx
        val lineIndex = lineOfBlock.getOrElse(blockIndex) { -1 }
        if (lineIndex < 0) return theme.textSizePx
        return usableFontSize(flatLines[lineIndex].fontSizePx, theme)
    }

    /**
     * 行盒：文本层 → [LineBox]，坐标加上页偏移与左边界。
     *
     * 没有块的行（空行、扫描页的行）**不产生行盒**：行盒的一半用途就是
     * "这一行是哪段文字的哪一段"（文本型选择器靠它），空行给不出这个答案。
     */
    fun lineBoxes(metrics: PageLayoutMetrics): List<LineBox> {
        if (flatLines.isEmpty()) return emptyList()
        val out = ArrayList<LineBox>(flatLines.size)
        var cursor = 0
        for ((pagePosition, page) in pages.withIndex()) {
            val pageTop = pageTopPx(pagePosition)
            for (line in page.lines) {
                val blockIndex = blockIndexOfLine[cursor]
                cursor++
                if (blockIndex < 0) continue
                val block = blocks[blockIndex]
                val fontSizePx = usableFontSize(line.fontSizePx, theme = null)
                out.add(
                    LineBox(
                        anchor = Selector.Structural(
                            blockPath = block.blockPath,
                            // 一行 = 一块，所以块内区间就是整块；块内偏移因此永远落在同一行内，
                            // 与重排格式的块内偏移语义相同（换行位置变化不会让它跨行）。
                            startOffset = 0,
                            endOffset = block.span.length,
                            baselineOffsetEm = baselineOffsetEm(line, fontSizePx),
                        ),
                        rectDoc = Box(
                            left = metrics.marginLeftPx + line.left,
                            top = pageTop + line.top,
                            right = metrics.marginLeftPx + line.right,
                            bottom = pageTop + line.bottom,
                        ),
                        baselineY = pageTop + line.baselineYPx,
                        fontSizePx = fontSizePx,
                    ),
                )
            }
        }
        return out
    }

    private fun baselineOffsetEm(line: PdfTextLine, fontSizePx: Float): Float {
        val em = (line.baselineYPx - line.top) / fontSizePx
        // 基线跑到行盒之外（平台给的数据自相矛盾）时退回 1em（≈ 行底基线），
        // 而不是把一个荒谬的值存进锚点链 —— 锚点链是要持久化的。
        return if (em.isFinite() && em > 0f) em else 1f
    }

    private fun usableFontSize(raw: Float, theme: DocumentTheme?): Float =
        if (raw.isFinite() && raw > 0f) raw else theme?.textSizePx ?: FALLBACK_FONT_SIZE_PX

    companion object {

        /**
         * 页与页之间的空隙。
         *
         * 它是**坐标系的一部分**而不是视图的偏好：页内坐标转文档坐标要加页偏移，
         * 而页偏移里含这个空隙。视图换一个值，页面位图就会与行盒错开。
         */
        const val PAGE_GAP_PX = 16f

        /**
         * 平台没给字号时的兜底（只影响 em → px 的换算比例，不影响位置）。
         * 取 16px：常见正文的最小可读值，宁可算小也不要算大 —— 算大只会让笔迹偏细。
         */
        const val FALLBACK_FONT_SIZE_PX = 16f

        /** 文本层原文：按页拼，页内按行。`DecodedText.text` 与 [PdfDocumentSession.rawText] 都用它，只此一处。 */
        fun rawTextOf(pages: List<PdfPageFacts>): String {
            val sb = StringBuilder()
            for (page in pages) {
                val text = page.text
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
            return sb.toString()
        }

        /**
         * 构造会话。
         *
         * 页序与列表位置必须一致（[PdfFormatException.Failure.INCONSISTENT]）：行盒的纵向位置
         * 是按列表位置累加的，而页对象自称的页号会进入进度提示与界面提示。
         * 两者一旦不一致，笔记就会挂到"另一页上看起来差不多的一行"。
         */
        fun build(docId: String, pages: List<PdfPageFacts>): PdfDocumentSession {
            pages.forEachIndexed { position, page ->
                if (page.index != position) {
                    throw PdfFormatException(
                        PdfFormatException.Failure.INCONSISTENT,
                        "第 $position 个页对象自称是第 ${page.index} 页：页序与列表位置必须一致，" +
                            "否则行盒会挂到别的页上",
                    )
                }
            }

            val flat = ArrayList<PdfTextLine>()
            for (page in pages) flat.addAll(page.lines)

            val raw = rawTextOf(pages)
            val normalized = TextNormalizer.normalize(raw, NormalizationProfiles.FIXED_LAYOUT)
            val split = BlockSplitter.split(DocumentKind.PDF, normalized)

            // 文本层行 → 块：走**已经设计好的**偏移映射（`NormalizedText.normalizedIndexOf`），
            // 与 EPUB 的章节标记是同一条机制（见 `ChapterMark` 的注释）。
            // 刻意不按"第几个非空行"配对：那要求归一化阶段对空行的判断与这里完全一致，
            // 而只要有一处不一致（例如某行只由全角空格组成），后面全部错位一格。
            val blockIndexOfLine = IntArray(flat.size) { -1 }
            val lineOfBlock = IntArray(split.blocks.size) { -1 }

            // 每页在 raw 里的起点。空页不占位（与 [rawTextOf] 的拼接规则逐字对应）；
            // 有字无行盒的页占位但不会产生行，因此**下一页的行偏移不会错位**。
            val pageRawStart = IntArray(pages.size) { -1 }
            run {
                var cursor = 0
                for (position in pages.indices) {
                    val text = pages[position].text
                    if (text.isEmpty()) continue
                    if (cursor > 0) cursor += 1
                    pageRawStart[position] = cursor
                    cursor += text.length
                }
            }

            var flatIndex = 0
            for (position in pages.indices) {
                val start = pageRawStart[position]
                if (start < 0) continue
                var lineCursor = start
                for (line in pages[position].lines) {
                    val index = flatIndex++
                    val offset = normalized.normalizedIndexOf(lineCursor)
                    lineCursor += line.text.length + 1
                    val blockIndex = ReflowableDocumentSession.blockIndexOf(split.blocks, offset)
                    if (blockIndex < 0) continue
                    val block = split.blocks[blockIndex]
                    if (!block.span.contains(offset)) continue
                    blockIndexOfLine[index] = blockIndex
                    lineOfBlock[blockIndex] = index
                }
            }

            val pageOfBlock = IntArray(split.blocks.size) { -1 }
            for (index in 0 until flat.size) {
                val blockIndex = blockIndexOfLine[index]
                if (blockIndex < 0) continue
                pageOfBlock[blockIndex] = pageOfLine(pages, index)
            }

            val fingerprint = SourceFingerprint.compute(
                normalizedText = normalized.text,
                profile = NormalizationProfiles.FIXED_LAYOUT,
                blockCount = split.blockCount,
            )

            return PdfDocumentSession(
                docId = docId,
                pages = pages,
                rawText = raw,
                normalized = normalized,
                fingerprint = fingerprint,
                split = split,
                flatLines = flat,
                blockIndexOfLine = blockIndexOfLine,
                pageOfBlock = pageOfBlock,
                lineOfBlock = lineOfBlock,
            )
        }

        /** 展平后的第 [lineIndex] 行属于哪一页。 */
        private fun pageOfLine(pages: List<PdfPageFacts>, lineIndex: Int): Int {
            var remaining = lineIndex
            for ((position, page) in pages.withIndex()) {
                if (remaining < page.lines.size) return position
                remaining -= page.lines.size
            }
            return -1
        }
    }
}
