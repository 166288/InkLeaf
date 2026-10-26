package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfPageFacts
import dev.inkleaf.core.document.PdfTextLine
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.StrokeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定版式（PDF）的行盒接入锚定链的测试。
 *
 * 这一层要回答的问题是：**PDF 的文本层能不能和 TXT 一样地落笔、解回**。
 * 如果 PDF 走的是另一套锚定逻辑，那么 PDF 的笔记就会变成另一种东西
 * （不能重挂、搜索不到、面板里显示不出来），而那种分叉不会在编译期被发现。
 *
 * 因此这里不造假的 [LayoutFacts]，而是用真的 [PdfDocumentSession]（真的页、真的文本层、
 * 真的坐标），只在最外面套一层 `PdfLayoutFacts` —— 与 `:app` 将来要走的路完全一致。
 */
class PdfLayoutFactsTest {

    private val metrics = PageLayoutMetrics(
        contentWidthPx = 600,
        contentHeightPx = 800,
        marginLeftPx = 12f,
        marginRightPx = 12f,
        marginTopPx = 8f,
        marginBottomPx = 8f,
    )

    private val pen = BrushSpec.DEFAULT_PEN

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

    private fun factsOf(session: PdfDocumentSession): PdfLayoutFacts =
        PdfLayoutFacts(session, metrics, docRatio = 0f)

    /** 造一条"已经写进库"的笔记：锚点链来自真实的 [InkAnchorFactory]（与 TXT 同一条路）。 */
    private fun written(
        facts: LayoutFacts,
        docPoints: FloatArray,
        annotationId: String = "ink-1",
    ): Pair<Annotation.Ink, List<StrokeAnchor>> {
        val result = InkAnchorFactory.anchor(
            InkAnchorFactory.Request(
                strokeId = "s1",
                docPoints = docPoints,
                docPressures = FloatArray(docPoints.size / 2) { 1f },
                brushId = pen.name,
                lines = facts.lines,
                geometry = facts.geometry,
                source = facts.lineSource(),
                document = facts.document(),
            ),
        )!!
        val segment = result.segments.single()
        val ink = Annotation.Ink(
            id = annotationId,
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = segment.chain,
            semantics = segment.stroke.semantics,
            strokeIds = listOf(segment.stroke.strokeId),
        )
        return ink to listOf(segment.stroke)
    }

    private fun resolve(
        inks: List<Annotation.Ink>,
        strokes: List<StrokeAnchor>,
        facts: LayoutFacts,
    ) = InkResolver.resolve(
        inks = inks,
        strokesOf = { annotationId -> if (inks.any { it.id == annotationId }) strokes else emptyList() },
        brushesOf = { listOf(pen) },
        facts = facts,
    )

    // ── 整链：落笔 → 解回 ────────────────────────────────────────────────────

    @Test
    fun `PDF 上落一笔能逐位解回原处`() {
        val session = PdfDocumentSession.build(
            "doc-1",
            listOf(page(0, listOf(line("第一行的正文", 0f), line("第二行的正文", 60f)))),
        )
        val facts = factsOf(session)
        assertEquals("文本层必须给出两行", 2, facts.lines.size)

        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 30f)
        val (ink, strokes) = written(facts, points)

        val resolution = resolve(listOf(ink), strokes, facts)

        assertEquals(1, resolution.resolvedCount)
        val drawn = resolution.drawable.single()
        for (i in points.indices) {
            assertEquals("PDF 的坐标换算必须与 TXT 一样精确", points[i], drawn.docPoints[i], 1e-3f)
        }
        assertEquals("blk[0]", ink.anchor!!.structural!!.blockPath)
    }

    @Test
    fun `跨页：第二页上的笔迹解回第二页的坐标，并且记得自己在哪一块`() {
        val session = PdfDocumentSession.build(
            "doc-1",
            listOf(
                page(0, listOf(line("第一页的正文", 0f))),
                page(1, listOf(line("第二页的正文", 0f)), height = 900f),
            ),
        )
        val facts = factsOf(session)
        val secondPageLine = facts.lines[1]
        assertTrue("第二页的行盒必须在第一页下方", secondPageLine.rectDoc.top >= 900f)

        val y = secondPageLine.rectDoc.top + 10f
        val points = floatArrayOf(100f, y, 200f, y + 10f)
        val (ink, strokes) = written(facts, points)

        val resolution = resolve(listOf(ink), strokes, facts)

        val drawn = resolution.drawable.single()
        for (i in points.indices) {
            assertEquals(points[i], drawn.docPoints[i], 1e-3f)
        }
        assertEquals("跨页的笔记必须落在第二页那一块上", "blk[1]", ink.anchor!!.structural!!.blockPath)
        assertEquals(1, session.pageIndexOfBlock(1))
    }

    // ── 硬规则在固定版式下同样成立 ───────────────────────────────────────────

    @Test
    fun `指纹不符时一个像素都不画：PDF 与重排共用同一条硬规则`() {
        val session = PdfDocumentSession.build("doc-1", listOf(page(0, listOf(line("第一行的正文", 0f)))))
        val facts = factsOf(session)
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val (ink, strokes) = written(facts, points)

        // 同一份文本层、同一套行盒，只是"源指纹变了"（例如文件被换成了另一个版本）。
        val changed = object : LayoutFacts by facts {
            override val fingerprint: String = "另一个指纹"
        }

        val resolution = resolve(listOf(ink), strokes, changed)

        assertTrue("画错位置比暂时看不见更糟", resolution.drawable.isEmpty())
        assertEquals(1, resolution.orphaned.size)
        assertTrue(resolution.orphaned.single().reason.contains("找不到原来的位置"))
    }

    @Test
    fun `扫描页没有行盒：落笔拿不到锚点，也不给出一条假笔记`() {
        val session = PdfDocumentSession.build("doc-1", listOf(page(0), page(1)))
        val facts = factsOf(session)

        assertTrue(facts.lines.isEmpty())
        assertNull(
            "没有行盒时应当明确返回 null，交给界面告诉用户这一笔没记下来",
            InkAnchorFactory.anchor(
                InkAnchorFactory.Request(
                    strokeId = "s1",
                    docPoints = floatArrayOf(100f, 10f, 200f, 20f),
                    docPressures = floatArrayOf(1f, 1f),
                    brushId = pen.name,
                    lines = facts.lines,
                    geometry = facts.geometry,
                    source = facts.lineSource(),
                    document = facts.document(),
                ),
            ),
        )
    }

    // ── 页坐标下的语义与冻结几何 ─────────────────────────────────────────────

    @Test
    fun `写在页面之外的笔迹按页边处理，并且是冻结几何`() {
        val session = PdfDocumentSession.build("doc-1", listOf(page(0, listOf(line("第一行的正文", 0f)))))
        val facts = factsOf(session)

        // 正文栏右边界 = 12 + 640 = 652；这一笔整个落在页面右侧之外。
        val points = floatArrayOf(700f, 5f, 760f, 15f, 800f, 25f)
        val (ink, strokes) = written(facts, points)

        assertEquals(InkReflowSemantics.PINNED, ink.semantics)
        assertNotNull(
            "页边批注的几何是落笔那一刻的事实，必须冻结（否则改字号它会跟着正文跑）",
            strokes.single().frozenMatrix,
        )
        assertEquals("blk[0]", ink.anchor!!.structural!!.blockPath)
    }

    @Test
    fun `阅读界面的零边距度量：行盒就是页内坐标加页偏移`() {
        val session = PdfDocumentSession.build(
            "doc-1",
            listOf(
                page(0, listOf(line("第一页的正文", 10f))),
                page(1, listOf(line("第二页的正文", 20f)), height = 900f),
            ),
        )
        val zero = PageLayoutMetrics(
            contentWidthPx = session.maxPageWidthPx.toInt(),
            contentHeightPx = session.maxPageHeightPx.toInt(),
            marginLeftPx = 0f,
            marginRightPx = 0f,
            marginTopPx = 0f,
            marginBottomPx = 0f,
        )

        val boxes = session.lineBoxes(zero)

        assertEquals(2, boxes.size)
        assertEquals("零边距下行盒不再额外平移", 24f, boxes[0].rectDoc.left, 1e-3f)
        assertEquals(10f, boxes[0].rectDoc.top, 1e-3f)
        assertEquals(
            "第二页的行盒必须带上页偏移（视图按这个值摆页）",
            session.pageTopPx(1) + 20f,
            boxes[1].rectDoc.top,
            1e-3f,
        )
        assertEquals(24f, boxes[1].rectDoc.left, 1e-3f)
    }
}
