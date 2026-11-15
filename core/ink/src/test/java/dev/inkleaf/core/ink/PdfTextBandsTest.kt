package dev.inkleaf.core.ink

import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfPageFacts
import dev.inkleaf.core.document.PdfTextLine
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字符区间 → PDF 页上色带的映射（M11）。
 *
 * 这一层要回答的问题只有一个：**"库里存的那一段字"到底画在纸上的哪一处**。
 * 它是 PDF 高亮与搜索命中共用的出口 —— 两条路各写一份"区间怎么变成矩形"，
 * 就会出现"搜索跳到这里、高亮画在上一行"这种只能靠眼睛发现的分叉。
 *
 * PDF 的文本层只给行盒、没有字距表，所以色带按**整行**铺。这不是近似：
 * 本工程的锚定精度就是 L2（行级），一行就是能承诺的最小单位。测试因此断言
 * "色带矩形 == 那一行的行盒"，而不是猜一个"字宽 × 字数"出来。
 */
class PdfTextBandsTest {

    // 与阅读界面完全一致的度量：PDF 的文档坐标 = 页内坐标加页偏移，没有额外边距。
    private fun metricsOf(session: PdfDocumentSession) = PageLayoutMetrics(
        contentWidthPx = session.maxPageWidthPx.toInt(),
        contentHeightPx = session.maxPageHeightPx.toInt(),
        marginLeftPx = 0f,
        marginRightPx = 0f,
        marginTopPx = 0f,
        marginBottomPx = 0f,
    )

    private fun line(text: String, top: Float, fontSize: Float = 40f) = PdfTextLine(
        text = text,
        left = 24f,
        top = top,
        right = 624f,
        bottom = top + fontSize * 1.25f,
        baselineYPx = top + fontSize,
        fontSizePx = fontSize,
    )

    private fun page(index: Int, lines: List<PdfTextLine> = emptyList(), height: Float = 800f) =
        PdfPageFacts(index = index, widthPx = 640f, heightPx = height, lines = lines)

    private fun factsOf(pages: List<PdfPageFacts>): PdfLayoutFacts {
        val session = PdfDocumentSession.build("doc-1", pages)
        return PdfLayoutFacts(session, metricsOf(session), docRatio = 0f)
    }

    @Test
    fun `一行内的区间只给一个色带，矩形就是那一行的行盒`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f), line("第二行的正文", 60f)))))
        val first = facts.lines[0]
        val range = facts.absoluteRangeOf(first)!!

        val bands = PdfTextBands.ofRange(
            range = (range.first + 1)..(range.first + 3),
            colorArgb = 0x66FFD54F,
            kind = PdfBandKind.HIGHLIGHT,
            facts = facts,
        )

        assertEquals("一行只该有一条色带", 1, bands.size)
        assertEquals("按行铺：色带矩形就是行盒", first.rectDoc, bands.single().rect)
        assertEquals(0x66FFD54F, bands.single().colorArgb)
        assertEquals(PdfBandKind.HIGHLIGHT, bands.single().kind)
    }

    @Test
    fun `跨两行的区间给两条色带，顺序与行序一致`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f), line("第二行的正文", 60f)))))
        val first = facts.absoluteRangeOf(facts.lines[0])!!
        val second = facts.absoluteRangeOf(facts.lines[1])!!

        val bands = PdfTextBands.ofRange(
            range = (first.first + 2)..(second.first + 2),
            colorArgb = 0x66FFD54F,
            kind = PdfBandKind.HIGHLIGHT,
            facts = facts,
        )

        assertEquals(2, bands.size)
        assertEquals(facts.lines[0].rectDoc, bands[0].rect)
        assertEquals(facts.lines[1].rectDoc, bands[1].rect)
        assertTrue("第二条必须在第一条下方", bands[1].rect.top >= bands[0].rect.bottom)
        assertNotEquals("两条色带不是同一个矩形", bands[0].rect, bands[1].rect)
    }

    @Test
    fun `第二页的区间带着页偏移，不会画回第一页`() {
        val facts = factsOf(
            listOf(
                page(0, listOf(line("第一页的正文", 0f))),
                page(1, listOf(line("第二页的正文", 20f)), height = 900f),
            ),
        )
        val second = facts.absoluteRangeOf(facts.lines[1])!!

        val band = PdfTextBands.ofRange(
            range = second,
            colorArgb = 0x6656CCF2,
            kind = PdfBandKind.SEARCH,
            facts = facts,
        ).single()

        assertEquals(facts.lines[1].rectDoc, band.rect)
        assertTrue("第二页的行盒必须带页偏移", band.rect.top >= 900f)
    }

    @Test
    fun `空区间与倒置区间不产生色带`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f)))))
        val range = facts.absoluteRangeOf(facts.lines[0])!!

        assertTrue(PdfTextBands.ofRange(IntRange(5, 4), 0, PdfBandKind.HIGHLIGHT, facts).isEmpty())
        assertTrue(
            "越界的区间也不该凭空画出一条",
            PdfTextBands.ofRange(IntRange(range.last + 100, range.last + 200), 0, PdfBandKind.HIGHLIGHT, facts)
                .isEmpty(),
        )
    }

    @Test
    fun `高亮按自己的颜色出带，搜索命中走固定色`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f)))))
        val range = facts.absoluteRangeOf(facts.lines[0])!!

        val highlight = DrawableHighlight(
            annotationId = "h1",
            range = range,
            colorArgb = 0x80FF8A65.toInt(),
        )
        val highlightBands = PdfTextBands.ofHighlights(listOf(highlight), facts)
        assertEquals(1, highlightBands.size)
        assertEquals(0x80FF8A65.toInt(), highlightBands.single().colorArgb)
        assertEquals(PdfBandKind.HIGHLIGHT, highlightBands.single().kind)

        val searchBands = PdfTextBands.ofSearch(
            ReaderSearch.TextHit(range.first, range.last + 1),
            facts,
        )
        assertEquals(1, searchBands.size)
        assertEquals(PdfTextBands.SEARCH_COLOR_ARGB, searchBands.single().colorArgb)
        assertEquals(PdfBandKind.SEARCH, searchBands.single().kind)

        assertTrue("没有当前命中时不该留下一条旧色带", PdfTextBands.ofSearch(null, facts).isEmpty())
        assertTrue(PdfTextBands.ofHighlights(emptyList(), facts).isEmpty())
    }

    @Test
    fun `搜索命中跨行时每一行各有一条色带`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f), line("第二行的正文", 60f)))))
        val first = facts.absoluteRangeOf(facts.lines[0])!!
        val second = facts.absoluteRangeOf(facts.lines[1])!!

        val bands = PdfTextBands.ofSearch(
            ReaderSearch.TextHit(first.first + 2, second.first + 2),
            facts,
        )

        assertEquals("跨行的命中按行拆开", 2, bands.size)
        assertEquals(facts.lines[0].rectDoc, bands[0].rect)
        assertEquals(facts.lines[1].rectDoc, bands[1].rect)
        assertTrue(bands.all { it.kind == PdfBandKind.SEARCH })
    }
}
