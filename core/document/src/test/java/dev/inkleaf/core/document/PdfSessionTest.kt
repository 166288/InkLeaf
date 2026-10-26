package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定版式会话的测试：**页 → 文本层 → 块 → 行盒 → 文档坐标**。
 *
 * 这是 PDF 唯一可以在本机完整验证的一层（平台阅读器不可验证，见 design/M5 §8），
 * 所以这里要钉住的不是"平台能不能取到文本层"（那是实现方的事），
 * 而是**取到文本层之后坐标怎么算**：页偏移、行盒、块路径、指纹。
 *
 * 为什么值得单独钉：这一层的错误症状全部是"笔记画在别的行上"或"笔记整批失配"，
 * 而且都要等到用户在某台设备上导入真实 PDF 才会暴露。
 */
class PdfSessionTest {

    private val metrics = PageLayoutMetrics(
        contentWidthPx = 600,
        contentHeightPx = 800,
        marginLeftPx = 12f,
        marginRightPx = 12f,
        marginTopPx = 8f,
        marginBottomPx = 8f,
    )

    /** 一行文本层：行高按字号的 1.25 倍算，基线放在行顶 + 一个字号处。 */
    private fun line(
        text: String,
        top: Float,
        fontSize: Float = 40f,
        left: Float = 24f,
        right: Float = 624f,
    ) = PdfTextLine(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = top + fontSize * 1.25f,
        baselineYPx = top + fontSize,
        fontSizePx = fontSize,
    )

    private fun page(
        index: Int,
        lines: List<PdfTextLine> = emptyList(),
        height: Float = 800f,
        width: Float = 640f,
    ) = PdfPageFacts(index = index, widthPx = width, heightPx = height, lines = lines)

    private fun sourceOf(pages: List<PdfPageFacts>) = PdfPageSource.Factory { FakePdfSource(pages) }

    // ── 块与文本坐标 ─────────────────────────────────────────────────────────

    @Test
    fun `一行一块：块路径与 TXT 同形，仍然是全局 blk 下标`() {
        val session = PdfDocumentSession.build(
            "d1",
            listOf(page(0, listOf(line("第一行", 0f), line("第二行", 60f)))),
        )

        assertEquals(2, session.blocks.size)
        assertEquals("blk[0]", session.blocks[0].blockPath)
        assertEquals("blk[1]", session.blocks[1].blockPath)
        assertEquals("第一行", session.blocks[0].text)
        assertEquals("第二行", session.blocks[1].text)
        // 第二块起点 = 第一行长度 + 一个换行；块偏移就是归一化全文的下标，不做任何换算。
        assertEquals(4, session.blocks[1].absoluteStart)
        assertEquals("nf:pdf-1", session.profile.profileId)
    }

    @Test
    fun `空行不产块，也不会把它后面的行错位一格`() {
        val session = PdfDocumentSession.build(
            "d1",
            listOf(page(0, listOf(line("甲", 0f), line("", 60f), line("乙", 120f)))),
        )

        assertEquals("空行没有可锚定的文字，不该产生块", 2, session.blocks.size)
        assertEquals("乙", session.blocks[1].text)

        val boxes = session.lineBoxes(metrics)
        assertEquals("空行也不该产生行盒", 2, boxes.size)
        assertEquals("blk[1]", boxes[1].anchor.blockPath)
        assertEquals("乙", session.textOfLine(boxes[1]))
        // 关键断言：第二行盒对应的是**第三**条文本层行（y=120），不是空行那条（y=60）。
        assertEquals(120f, boxes[1].rectDoc.top, 1e-3f)
    }

    // ── 页与文档坐标 ─────────────────────────────────────────────────────────

    @Test
    fun `行盒带页偏移：第二页的行整体下移一个页步长`() {
        val session = PdfDocumentSession.build(
            "d1",
            listOf(
                page(0, listOf(line("第一页的行", 0f))),
                page(1, listOf(line("第二页的行", 10f)), height = 900f),
            ),
        )

        // 页高不一致时按最大页高排：第二页的起点至少让第一页站得下。
        assertEquals(900f + PdfDocumentSession.PAGE_GAP_PX, session.stridePx, 1e-3f)
        assertEquals(session.stridePx, session.pageTopPx(1), 1e-3f)
        assertTrue("第二页不能压在第一页上", session.pageTopPx(1) >= session.maxPageHeightPx)

        val boxes = session.lineBoxes(metrics)
        assertEquals(2, boxes.size)
        // 横向：页左边界 + 页内 x。纵向：页偏移 + 页内 y。
        assertEquals(12f + 24f, boxes[0].rectDoc.left, 1e-3f)
        assertEquals(0f, boxes[0].rectDoc.top, 1e-3f)
        assertEquals(session.stridePx + 10f, boxes[1].rectDoc.top, 1e-3f)
        assertEquals(12f + 624f, boxes[1].rectDoc.right, 1e-3f)
        // 整篇高度：视图的滚动范围。
        assertEquals(session.pageTopPx(1) + 900f, session.stackHeightPx, 1e-3f)
    }

    @Test
    fun `字符偏移能反查页号`() {
        val session = PdfDocumentSession.build(
            "d1",
            listOf(
                page(0, listOf(line("甲", 0f), line("乙", 60f))),
                page(1, listOf(line("丙", 0f))),
            ),
        )

        // 归一化全文 = 甲\n乙\n丙：偏移 0/2 在第一页，4 在第二页。
        assertEquals(0, session.pageIndexOfChar(0))
        assertEquals(0, session.pageIndexOfChar(2))
        assertEquals(1, session.pageIndexOfChar(4))
        assertEquals(0, session.pageIndexOfBlock(0))
        assertEquals(1, session.pageIndexOfBlock(2))
    }

    // ── 固定版式与重排的差别 ─────────────────────────────────────────────────

    @Test
    fun `字号来自文本层：应用字号再大也一个行盒都不动`() {
        val session = PdfDocumentSession.build("d1", listOf(page(0, listOf(line("正文", 100f, fontSize = 40f)))))

        val boxes = session.lineBoxes(metrics)
        assertEquals(40f, boxes.single().fontSizePx, 1e-3f)
        // 基线的相对位置用 em 表示：基线在行顶下方一个字号处。
        assertEquals(1f, boxes.single().anchor.baselineOffsetEm, 1e-3f)
        assertEquals(140f, boxes.single().baselineY, 1e-3f)
        assertEquals(100f, boxes.single().rectDoc.top, 1e-3f)

        // 主题字号只作为"平台没给字号"时的兜底，它**不**参与正常路径。
        assertEquals(40f, session.fontSizePxOf("blk[0]", DocumentTheme.light(textSizePx = 80f)), 1e-3f)
    }

    @Test
    fun `固定版式没有重排：layout 当场抛异常而不是给一份空排版`() {
        val session = PdfDocumentSession.build("d1", listOf(page(0, listOf(line("正文", 0f)))))

        val error = assertThrows(UnsupportedOperationException::class.java) {
            session.layout(metrics, DocumentTheme.light(textSizePx = 40f))
        }
        assertTrue("失败信息必须点明该用哪个方法", error.message!!.contains("lineBoxes"))
    }

    // ── 扫描件 ───────────────────────────────────────────────────────────────

    @Test
    fun `没有文本层的页仍然能立会话，只是没有块和行盒`() {
        val session = PdfDocumentSession.build("d1", listOf(page(0), page(1, height = 900f)))

        assertEquals(0, session.blocks.size)
        assertFalse(session.hasTextLayer)
        assertTrue(session.lineBoxes(metrics).isEmpty())
        // 页位置仍然算得出来：M5b 的按页批注要挂在这里。
        assertEquals(900f + PdfDocumentSession.PAGE_GAP_PX, session.pageTopPx(1), 1e-3f)
        // 偏移反查页号取不到时返回 -1，而不是 0（0 是"第一页"，会被当真）。
        assertEquals(-1, session.pageIndexOfChar(0))
    }

    @Test
    fun `扫描件照样能构造会话，并且把结论写进证据里`() {
        val built = DocumentCodec.sessionFromBytes(
            docId = "d1",
            kind = DocumentKind.PDF,
            bytes = byteArrayOf(1, 2, 3),
            pdf = sourceOf(listOf(page(0), page(1))),
        )

        assertEquals(DocumentCodec.PDF_TEXT_LAYER_LABEL, built.decoded.charsetName)
        assertTrue("扫描件是正常文件，不是失败：判定结论要说给用户听", built.decoded.evidence.contains("扫描件"))
        assertFalse(built.decoded.lowConfidence)
    }

    // ── 指纹：导入与打开必须是同一个 ─────────────────────────────────────────

    @Test
    fun `指纹只看文本层与口径，不看文档 id`() {
        val pages = listOf(page(0, listOf(line("甲乙丙丁戊己庚辛壬癸子丑寅卯", 0f))))
        val a = PdfDocumentSession.build("d1", pages)
        val b = PdfDocumentSession.build("d2", pages)
        val c = PdfDocumentSession.build("d1", listOf(page(0, listOf(line("换了一段正文", 0f)))))

        assertEquals("同一份文本层必须给同一个指纹，否则重开文档笔记会整批失配", a.fingerprint, b.fingerprint)
        assertNotEquals(a.fingerprint, c.fingerprint)
    }

    @Test
    fun `导入与打开走同一个入口时指纹一致`() {
        val pages = listOf(page(0, listOf(line("甲乙丙丁戊己庚辛壬癸子丑寅卯", 0f))))
        val direct = PdfDocumentSession.build("d1", pages)

        val built = DocumentCodec.sessionFromBytes(
            docId = "d1",
            kind = DocumentKind.PDF,
            bytes = byteArrayOf(1, 2, 3),
            pdf = sourceOf(pages),
        )

        assertEquals(direct.fingerprint, built.session.fingerprint)
        assertEquals(direct.rawText, built.decoded.text)
        assertEquals(DocumentKind.PDF, built.session.kind)
    }

    // ── 失败路径 ─────────────────────────────────────────────────────────────

    @Test
    fun `页序号与列表位置不一致时拒绝，而不是把行盒挂到别的页上`() {
        val error = assertThrows(PdfFormatException::class.java) {
            PdfDocumentSession.build("d1", listOf(page(3)))
        }

        assertEquals(PdfFormatException.Failure.INCONSISTENT, error.failure)
    }

    @Test
    fun `没有页源时明确拒绝：接线漏了必须是响亮的失败`() {
        val error = assertThrows(PdfFormatException::class.java) {
            DocumentCodec.sessionFromBytes("d1", DocumentKind.PDF, byteArrayOf(1))
        }

        assertEquals(PdfFormatException.Failure.NO_PAGE_SOURCE, error.failure)
    }

    @Test
    fun `一页都读不出来时判文件坏了，而不是判成扫描件`() {
        val error = assertThrows(PdfFormatException::class.java) {
            DocumentCodec.sessionFromBytes(
                docId = "d1",
                kind = DocumentKind.PDF,
                bytes = byteArrayOf(1),
                pdf = sourceOf(emptyList()),
            )
        }

        assertEquals(PdfFormatException.Failure.BROKEN, error.failure)
        assertTrue("要说清是文件的问题", error.message!!.contains("读不到任何页面"))
    }

    @Test
    fun `有文字没有行盒的页：文字进正文，但不产生行盒，也不影响下一页的行`() {
        val session = PdfDocumentSession.build(
            "d1",
            listOf(
                PdfPageFacts(
                    index = 0,
                    widthPx = 640f,
                    heightPx = 800f,
                    lines = emptyList(),
                    // 平台给得出整页文字、给不出每行包围盒时的退路（见 PdfPageFacts）。
                    textOverride = "第一页的文字层\n第二行",
                ),
                page(1, listOf(line("第二页的行", 0f))),
            ),
        )

        assertEquals("第一页的文字层\n第二行\n第二页的行", session.rawText)
        assertTrue("有文字就算有文本层，哪怕没有行盒", session.hasTextLayer)
        assertEquals(3, session.blocks.size)

        // 关键：第 1 页的「有字无盒」占住了它在正文里的位置，
        // 第 2 页的行偏移因此没有错位（错位的症状是笔记画在别的行上）。
        assertEquals(1, session.pageIndexOfChar(session.blocks.last().absoluteStart))

        val boxes = session.lineBoxes(metrics)
        assertEquals("只有第 2 页那一行有几何", 1, boxes.size)
        assertEquals("第二页的行", session.textOfLine(boxes.single()))
        assertEquals(1, session.pageIndexOfBlock(session.blocks.lastIndex))
    }
}

/** 测试用的假页源：把给它的页原样发出来。见 design/M5 §8 关于"接缝要窄"的说明。 */
private class FakePdfSource(private val pages: List<PdfPageFacts>) : PdfPageSource {

    override val pageCount: Int get() = pages.size

    override fun page(index: Int): PdfPageFacts = pages[index]

    override val textLayerEvidence: String get() = "测试用假页源"

    override fun close() = Unit
}
