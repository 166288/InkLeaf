package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox

/**
 * 一条要画在 PDF 页上的色带（高亮 / 搜索命中）。
 *
 * [rect] 是**文档坐标**，与 `PdfDocumentSession` 的页偏移同一套坐标系；
 * 视图用画笔迹的同一个变换画它，所以色带与文字不会错开。
 */
data class PdfBand(
    val rect: Box,
    val colorArgb: Int,
    val kind: PdfBandKind,
)

enum class PdfBandKind { HIGHLIGHT, SEARCH }

/**
 * 字符区间 → PDF 页上的色带。
 *
 * ## 为什么按**整行**铺，而不是像重排格式那样按字精确铺
 *
 * 重排格式的排版结果是自己的 `StaticLayout`，`rectsOfCharRange` 知道每个字画在哪。
 * PDF 的文本层只给**行盒**（一段文字的包围盒），没有字距表 —— 想按字铺就只能
 * 按字符数比例猜 x，而猜出来的底色会与字错开半格。本工程的锚定精度本来就是
 * **L2（行级）**，所以这里的答案不是"猜得更细"，而是"如实铺满这一行"：
 * 用户看到的是"这一行标了"，与库里存的锚点含义完全一致。
 *
 * 一条跨行的搜索命中会得到多个色带（每行一个），行与行之间的空隙不着色 ——
 * 这正是"按行"的样子，也让"命中跨行"这件事在界面上可见。
 *
 * ## 为什么放在 `:core:ink`
 *
 * 与 [highlightRanges] 同一个理由：它需要 `LayoutFacts` 才能把区间换成行盒，
 * 而"哪些行算被覆盖"必须只有一处判据。界面只负责把 [PdfBand] 交给视图绘制，
 * 不自己判断区间与行的关系。
 */
object PdfTextBands {

    /**
     * 搜索命中的色带颜色（浅蓝，与高亮的琥珀色区分）。
     *
     * 半透明：PDF 页是位图，文字在色带**下面**。用 `MULTIPLY` 画时彩色部分会与
     * 白底相乘、与黑字相乘仍为黑 —— 字始终看得见（见 `PdfInkPageView`）。
     */
    const val SEARCH_COLOR_ARGB: Int = 0x6656CCF2

    /**
     * 与 [range] 有交集的全部行盒（文档顺序）。
     *
     * 判据用**半开区间**：`range` 覆盖到某一行的最后一个字时，不会把下一行也算进来。
     * [LayoutFacts.absoluteRangeOf] 取不到的行（空行、扫描页的行）不参与：
     * 它们没有可引用的文字，也就没有"标中这一段"这回事。
     */
    fun linesOverlapping(range: IntRange, facts: LayoutFacts): List<LineBox> {
        if (range.last < range.first) return emptyList()
        return facts.lines.filter { line ->
            val lineRange = facts.absoluteRangeOf(line) ?: return@filter false
            range.first <= lineRange.last && range.last + 1 > lineRange.first
        }
    }

    /** 一段区间 → 每行一个色带。 */
    fun ofRange(
        range: IntRange,
        colorArgb: Int,
        kind: PdfBandKind,
        facts: LayoutFacts,
    ): List<PdfBand> = linesOverlapping(range, facts).map { line ->
        PdfBand(rect = line.rectDoc, colorArgb = colorArgb, kind = kind)
    }

    /** 已解析的高亮 → 色带。顺序与 [highlightRanges] 一致。 */
    fun ofHighlights(
        highlights: List<DrawableHighlight>,
        facts: LayoutFacts,
    ): List<PdfBand> = highlights.flatMap { highlight ->
        ofRange(highlight.range, highlight.colorArgb, PdfBandKind.HIGHLIGHT, facts)
    }

    /** 当前搜索命中 → 色带。没有命中（null）时为空。 */
    fun ofSearch(
        hit: ReaderSearch.TextHit?,
        facts: LayoutFacts,
        colorArgb: Int = SEARCH_COLOR_ARGB,
    ): List<PdfBand> {
        if (hit == null) return emptyList()
        return ofRange(hit.start until hit.endExclusive, colorArgb, PdfBandKind.SEARCH, facts)
    }
}
