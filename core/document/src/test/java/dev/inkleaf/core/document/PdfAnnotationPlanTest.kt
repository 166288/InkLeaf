package dev.inkleaf.core.document

import dev.inkleaf.core.model.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写回计划的纯逻辑测试。
 *
 * 这些断言为什么值钱：写回真机代码（`PdfRendererPreV` + `addPageAnnotation`）需要
 * S Extensions ≥ 18，而本项目的模拟器读到的扩展版本是 0 —— 那段代码在本机
 * **一行都执行不到**。所以"页内坐标算得对不对、越界怎么裁、什么该跳过"这些
 * 最容易错也最难在用户那边复现的东西，只能靠这一层钉死。
 *
 * 每个用例都对应一种会真的发生的坏数据，不是凑覆盖率。
 */
class PdfAnnotationPlanTest {

    /** 两页，各 600×800 point，页间距 16（与 `PdfDocumentSession.PAGE_GAP_PX` 一致）。 */
    private val page0 = PdfPageBox(topDoc = 0f, widthPt = 600f, heightPt = 800f)
    private val page1 = PdfPageBox(topDoc = 816f, widthPt = 600f, heightPt = 800f)
    private val pages = listOf(page0, page1)

    // ── 归页与坐标换算 ──────────────────────────────────────────────────────

    @Test
    fun `第二页上的高亮减去页偏移后落在页内坐标里`() {
        // 文档 y = 816 + 100 = 916：这是第二页上方 100 point 处。
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource(
                    annotationId = "h1",
                    rects = listOf(Box(left = 40f, top = 916f, right = 500f, bottom = 940f)),
                    colorArgb = 0xFF00FF00.toInt(),
                    label = "高亮",
                ),
            ),
            inks = emptyList(),
            notes = emptyList(),
        )

        val mark = plan.marks.single() as PdfMark.Highlight
        assertEquals("应当归到第二页", 1, mark.pageIndex)
        val rect = mark.rects.single()
        // 页内坐标的 y 必须减去页偏移：不减的话批注会被画到页面下方 816 point 处，
        // 也就是整页之外 —— 而这是写回里最容易犯、又在本机看不到的错。
        assertEquals(100f, rect.top, 0.01f)
        assertEquals(124f, rect.bottom, 0.01f)
        // 横向不减任何东西：PDF 的左边界就是 0。
        assertEquals(40f, rect.left, 0.01f)
        assertEquals(500f, rect.right, 0.01f)
    }

    @Test
    fun `跨页缝的一条下划线只保留前一页那段并说明不了的部分被丢弃`() {
        // 第一个矩形在第一页底部，第二个在第二页顶部：同一个标注跨越了页缝。
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource(
                    annotationId = "u1",
                    rects = listOf(
                        Box(10f, 790f, 500f, 795f),
                        Box(10f, 820f, 500f, 825f),
                    ),
                    colorArgb = 0xFF0000FF.toInt(),
                    label = "下划线",
                ),
            ),
            inks = emptyList(),
            notes = emptyList(),
        )

        val mark = plan.marks.single() as PdfMark.Highlight
        assertEquals(0, mark.pageIndex)
        assertEquals("两页的页内坐标各自独立，跨页的那一段不能合并到同一页", 1, mark.rects.size)
    }

    @Test
    fun `落在页与页之间空隙里的标注被跳过而不是被压到某一页的边上`() {
        // 第一页 0..800，第二页 816..1616，中间有 16 point 的空隙。中心 808 落在空隙里。
        // 这里刻意钉住"没有兜底"：曾经有一版写法是"找不到就甩给最后一页"，
        // 结果一个位置无意义的矩形会被压到页面底边，变成一个点不中也删不掉的注释。
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource("h2", listOf(Box(10f, 802f, 100f, 814f)), 0xFF00FF00.toInt(), "高亮"),
            ),
            inks = emptyList(),
            notes = emptyList(),
        )
        assertTrue("页缝里的标注不该被写进任何一页", plan.marks.isEmpty())
        assertEquals("h2", plan.skipped.single().annotationId)
    }

    @Test
    fun `高亮超出页面右边时被裁到页内`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource("h3", listOf(Box(550f, 100f, 700f, 120f)), 0xFF00FF00.toInt(), "高亮"),
            ),
            inks = emptyList(),
            notes = emptyList(),
        )
        val rect = (plan.marks.single() as PdfMark.Highlight).rects.single()
        assertEquals("右边不能超出页宽", 600f, rect.right, 0.01f)
        assertEquals(550f, rect.left, 0.01f)
    }

    @Test
    fun `裁完没有面积的标注被跳过并给出理由`() {
        // 整段落在页右侧之外：裁完宽度为 0。写回去会变成一个点不中也看不见的注释。
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource("h4", listOf(Box(700f, 100f, 780f, 120f)), 0xFF00FF00.toInt(), "高亮"),
            ),
            inks = emptyList(),
            notes = emptyList(),
        )
        assertTrue(plan.marks.isEmpty())
        val skipped = plan.skipped.single()
        assertEquals("h4", skipped.annotationId)
        assertTrue("理由要说清是哪一类", skipped.reason.contains("高亮"))
    }

    // ── 手写 ────────────────────────────────────────────────────────────────

    @Test
    fun `手写按全部点的纵向中心归页，并含住笔宽`() {
        // 全部点在第二页（中心 y ≈ 900+）。笔宽 12，所以图章边界要向外扩 6：
        // 只按中心线算外接盒的话，粗笔的边会被图章裁掉一截。
        val stroke = PdfDocStroke(
            points = floatArrayOf(100f, 900f, 200f, 900f),
            widthPt = 12f,
            colorArgb = 0xFFB3261E.toInt(),
        )
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = listOf(PdfInkSource("i1", listOf(stroke))),
            notes = emptyList(),
        )

        val mark = plan.marks.single() as PdfMark.Ink
        assertEquals(1, mark.pageIndex)
        val bounds = mark.bounds
        // 页内 y = 900 - 816 = 84。半笔宽 6 → 78 .. 90。
        assertEquals(78f, bounds.top, 0.01f)
        assertEquals(90f, bounds.bottom, 0.01f)
        assertEquals(94f, bounds.left, 0.01f)
        assertEquals(206f, bounds.right, 0.01f)
        // 点本身被换算到页内坐标。
        val points = mark.strokes.single().points
        assertEquals(84f, points[1], 0.01f)
        assertEquals(84f, points[3], 0.01f)
    }

    @Test
    fun `整段落在页面之外的手写被跳过`() {
        val stroke = PdfDocStroke(floatArrayOf(-50f, 900f, -10f, 900f), widthPt = 4f, colorArgb = 0xFF000000.toInt())
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = listOf(PdfInkSource("i2", listOf(stroke))),
            notes = emptyList(),
        )
        assertTrue(plan.marks.isEmpty())
        assertEquals("i2", plan.skipped.single().annotationId)
    }

    @Test
    fun `没有笔迹的手写被跳过而不是产出一个空图章`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = listOf(PdfInkSource("i3", emptyList())),
            notes = emptyList(),
        )
        assertTrue(plan.marks.isEmpty())
        assertEquals("i3", plan.skipped.single().annotationId)
    }

    // ── 批注与书签 ──────────────────────────────────────────────────────────

    @Test
    fun `推型批注的框比行盒往下多留一段并且不出页`() {
        // 贴着页底的一行：往下扩时必须被页高截住，否则批注框有一半在纸外。
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = emptyList(),
            notes = listOf(
                PdfNoteSource("n1", Box(40f, 770f, 400f, 790f), "这段要记住", 0xFF795548.toInt()),
            ),
        )
        val mark = plan.marks.single() as PdfMark.Note
        assertEquals("这段要记住", mark.text)
        assertNotNull(mark.box)
        assertTrue("不能超过页高", mark.box.bottom <= 800f + 0.01f)
        assertTrue("要比行盒高，才像一张便签", mark.box.height >= 20f)
    }

    @Test
    fun `没有文字的批注不写回`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = emptyList(),
            notes = listOf(PdfNoteSource("n2", Box(0f, 10f, 100f, 20f), "   ", 0xFF000000.toInt())),
        )
        assertTrue(plan.marks.isEmpty())
        assertTrue(plan.skipped.single().reason.contains("没有文字"))
    }

    @Test
    fun `书签被跳过且理由说清是 PDF 没有这种注释类型`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = emptyList(),
            inks = emptyList(),
            notes = emptyList(),
            bookmarks = listOf("b1", "b2"),
        )
        assertEquals(2, plan.skipped.size)
        val reason = plan.skipped.first().reason
        assertTrue("理由要提到书签", reason.contains("书签"))
        assertTrue("理由要提到注释类型，否则用户以为书签坏了", reason.contains("注释类型"))
    }

    // ── 摘要（用户看到的那句话） ─────────────────────────────────────────────

    @Test
    fun `摘要给出各类数量而不是只说成功`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = pages,
            bands = listOf(
                PdfBandSource("h1", listOf(Box(10f, 100f, 200f, 120f)), 0xFF00FF00.toInt(), "高亮"),
            ),
            inks = listOf(
                PdfInkSource("i1", listOf(PdfDocStroke(floatArrayOf(10f, 900f, 60f, 900f), 4f, 0xFF000000.toInt()))),
            ),
            notes = listOf(PdfNoteSource("n1", Box(10f, 10f, 100f, 30f), "记一句", 0xFF000000.toInt())),
            bookmarks = listOf("b1"),
        )
        val summary = plan.summary()
        assertTrue(summary, summary.contains("1 条高亮"))
        assertTrue(summary, summary.contains("1 处手写"))
        assertTrue(summary, summary.contains("1 条批注"))
        assertTrue("要老实说有一条写不了", summary.contains("1 条写不了"))
    }

    @Test
    fun `空计划也要说清什么也没写`() {
        val plan = PdfAnnotationPlanner.plan(pages, emptyList(), emptyList(), emptyList())
        assertTrue(plan.isEmpty)
        val summary = plan.summary()
        assertFalse("不能是空字符串：空字符串会被界面显示成一片空白", summary.isBlank())
        assertTrue(summary, summary.contains("还没有任何标注"))
    }
}
