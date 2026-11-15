package dev.inkleaf.core.ink

import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfPageFacts
import dev.inkleaf.core.document.PdfTextLine
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 高亮的锚点链（M11）。
 *
 * PDF 与重排格式共用 `chainForRange` / `highlightRanges`，差别只有两处，两处都必须
 * 在这里钉住：
 *
 *   1. **页键**要写进进度兜底选择器（`ProgressHint.pageKey`）—— PDF 没有可重排的
 *      字符偏移，链完全解不开时"第 3 页"是唯一还能指路的东西；
 *   2. **一行 = 一块**，而一条高亮链只能容纳一个块。跨行的命中硬塞进一条链只会
 *      标到第一行末尾 —— 这正是 `NoteWriter.addHighlightsByLine` 按行拆的理由。
 */
class PdfHighlightAnchorTest {

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

    private fun highlightOf(chain: dev.inkleaf.core.model.TextAnchorChain) = Annotation.Highlight(
        id = "h1",
        docId = "doc-1",
        createdAt = 1L,
        colorArgb = 0x66FFD54F,
        anchor = chain,
        style = HighlightStyle(),
    )

    @Test
    fun `第二页上的高亮：链记住页键，解回第二页那一行`() {
        val facts = factsOf(
            listOf(
                page(0, listOf(line("第一页的正文", 0f))),
                page(1, listOf(line("第二页的正文", 20f)), height = 900f),
            ),
        )
        val target = facts.lines[1]
        val range = facts.lineHighlightRange(target)!!

        val chain = facts.chainForRange(range.first, range.last + 1, pageKey = "p2")
        assertNotNull("文本型 PDF 的高亮必须能建链", chain)
        assertEquals("第二页那一行是第二块", "blk[1]", chain!!.structural!!.blockPath)
        assertEquals("页键要进兜底提示", "p2", chain.progressHint.pageKey)

        val resolved = highlightRanges(listOf(highlightOf(chain)), facts).single()
        assertEquals("解回来的区间就是标中的那一行", range, resolved.range)

        val band = PdfTextBands.ofHighlights(listOf(resolved), facts).single()
        assertEquals("色带落在第二页的行盒上", target.rectDoc, band.rect)
        assertTrue("第二页的行盒必须带页偏移", band.rect.top >= 900f)
    }

    @Test
    fun `跨行的区间硬塞进一条链只会标到第一行末尾 —— 所以必须按行拆`() {
        val facts = factsOf(listOf(page(0, listOf(line("第一行的正文", 0f), line("第二行的正文", 60f)))))
        val first = facts.lineHighlightRange(facts.lines[0])!!
        val second = facts.lineHighlightRange(facts.lines[1])!!

        val chain = facts.chainForRange(first.first, second.last + 1, pageKey = "p1")!!
        val resolved = highlightRanges(listOf(highlightOf(chain)), facts).single()

        assertEquals("结构型选择器只能容纳第一个块", first, resolved.range)
        assertNotEquals("不能把第二行也标上", second, resolved.range)
        assertTrue("第二行的那几个字一个都不在链里", resolved.range.last < second.first)
    }
}
