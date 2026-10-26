package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.TextAnchorChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮（**区间**打字批注）的纯逻辑测试：建链 ↔ 解析 ↔ 要画的区间。
 *
 * 高亮是全工程唯一一种"链指的不是某一行、而是一段字符"的标注，所以这里要钉死
 * 两件事 —— 两件在真机上都不容易发现：
 *
 *  1. **写进去的区间就是画出来的区间**。少一个"上下文窗口"、多一个块内偏移，
 *     都不会让任何东西报错，只会让色带左右偏一两个字。
 *  2. **解不开就不画**。高亮没有笔迹，位置就是锚点：Ambiguous / Orphaned 时
 *     一个像素都不画，并留给笔记面板去说清为什么。
 *
 * 用假文档（[TestDoc]）而不是真排版：这两个结论与字号、栏宽、字体都无关，
 * 用真排版测它们只会让失败信息变得难读（失败时说不清是锚定错了还是排版变了）。
 * "换字号之后还标着同一段字"的整链路版本在 `:app` 的 `HighlightRoundTripTest` 里。
 */
class HighlightsTest {

    /** 3 个块，每块一行 8 个字：`第0行的文字内容` / `第1行的文字内容` / `第2行的文字内容`。 */
    private val doc = TestDoc(3)

    private fun highlight(chain: TextAnchorChain, id: String = "hl-1") = Annotation.Highlight(
        id = id,
        docId = "doc-1",
        createdAt = 1L,
        colorArgb = 0x66FFD54F.toInt(),
        anchor = chain,
    )

    @Test
    fun `整行高亮的区间就是这一行的字`() {
        val facts = doc.facts()
        val line = facts.lines[1]

        val chain = facts.chainForLine(line)
        assertNotNull("普通正文行必须建得出链", chain)

        val band = highlightRanges(listOf(highlight(chain!!)), facts).single()

        assertEquals("行是区间的特例：整行高亮就是这一行的绝对区间", doc.absoluteRangeOf(line), band.range)
        assertEquals("第1行的文字内容", facts.normalizedText.substring(band.range.first, band.range.last + 1))
    }

    @Test
    fun `区间高亮只盖住标中的那几个字`() {
        val facts = doc.facts()
        // 第 1 块从全文偏移 9 起（每块 8 个字 + 一个换行）：13..14 是「文字」两个字。
        val start = 13
        val endExclusive = 15
        assertEquals("文字", facts.normalizedText.substring(start, endExclusive))

        val chain = facts.chainForRange(start, endExclusive)
        assertNotNull("落在块内的区间必须建得出链", chain)

        val band = highlightRanges(listOf(highlight(chain!!)), facts).single()

        assertEquals("画出来的必须正好是标中的那两个字符", start..(endExclusive - 1), band.range)
    }

    @Test
    fun `区间链能被解析回它所在的那一行`() {
        // 写入方向（chainForRange）与读取方向（typedNoteRows）必须给出同一个结论：
        // 面板里那行说"已定位到第 N 段"，正文里就必须在那一行上有底色。
        val facts = doc.facts()

        val chain = facts.chainForRange(13, 15)!!
        val row = typedNoteRows(listOf(highlight(chain)), facts).single()

        assertEquals(InkState.RESOLVED, row.state)
        assertEquals(TypedNoteKind.HIGHLIGHT, row.kind)
        assertEquals("解析到的必须是第 1 行", "第1行的文字内容", row.excerpt)
        assertEquals("跳转偏移给的是那一行的起点", doc.absoluteRangeOf(facts.lines[1])!!.first, row.charOffset)
        assertNull("高亮没有正文（它标的是别人的话）", row.noteText)
    }

    @Test
    fun `换字号后同一个区间仍然标着同一段字`() {
        // 排版换了（行盒全变），但归一化文本与锚点都没变：区间必须原地不动。
        val small = doc.facts(lines = doc.lines(fontSize = 40f))
        val big = doc.facts(lines = doc.lines(fontSize = 80f))

        val chain = small.chainForRange(13, 15)!!
        val band = highlightRanges(listOf(highlight(chain)), big).single()

        assertEquals("文字", big.normalizedText.substring(band.range.first, band.range.last + 1))
    }

    @Test
    fun `区间落在块之间的换行符上时建不出链`() {
        val facts = doc.facts()
        // 偏移 8 就是第 0 块与第 1 块之间的那个换行符：它不是任何一段可引用的文字，
        // 写成锚点会在重排后匹配到任意一处换行。
        assertNull(facts.chainForRange(8, 9))

        val blank = LineBox(
            anchor = Selector.Structural("blk[99]", 0, 5, 1f),
            rectDoc = Box(0f, 0f, 100f, 20f),
            baselineY = 16f,
            fontSizePx = 40f,
        )
        assertNull("块号取不到的行也给不出可标的区间", facts.lineHighlightRange(blank))
    }

    @Test
    fun `空区间不会建出链`() {
        val facts = doc.facts()

        assertNull(facts.chainForRange(5, 5))
        assertNull("越界的区间也不该建出链（宁可拒绝，也不要写一条指到别处的链）", facts.chainForRange(0, 9_999))
    }

    @Test
    fun `只有引文没有结构型选择器时不画`() {
        // 规则 2b：引文能对上，但说不准到底是哪一处 —— 这正是"猜出来的位置"。
        // 高亮没有第二条轴（不像笔迹有冻结几何），所以一个像素都不画。
        val facts = doc.facts()
        val chain = facts.chainForRange(13, 15)!!
        val withoutStructural = chain.copy(
            selectors = chain.selectors.filterNot { it is Selector.Structural },
        )

        assertTrue(
            "前提：这条链必须仍然能被解析（否则测的就不是 2b 那条路）",
            highlightRanges(listOf(highlight(chain)), facts).isNotEmpty(),
        )
        assertTrue(
            "没有结构型选择器时只能靠引文猜，不画",
            highlightRanges(listOf(highlight(withoutStructural)), facts).isEmpty(),
        )
    }

    @Test
    fun `只剩进度兜底时不画`() {
        val facts = doc.facts()
        val chain = facts.chainForRange(13, 15)!!
        val onlyHint = chain.copy(
            selectors = chain.selectors.filterIsInstance<Selector.ProgressHint>(),
        )

        assertTrue(highlightRanges(listOf(highlight(onlyHint)), facts).isEmpty())
    }

    @Test
    fun `指纹变了就不画`() {
        // 库里的链来自另一个版本（或另一份文档）：规则 1 直接判孤儿，一个像素都不画。
        val facts = doc.facts()
        val chain = facts.chainForRange(13, 15)!!
        val otherVersion = object : LayoutFacts by facts {
            override val fingerprint: String get() = "fp-另一个版本"
        }

        assertTrue(highlightRanges(listOf(highlight(chain)), otherVersion).isEmpty())
    }

    @Test
    fun `解不开的那几条不画，其余照常画`() {
        val facts = doc.facts()
        val good = highlight(facts.chainForRange(13, 15)!!, id = "hl-good")
        val broken = highlight(
            facts.chainForRange(0, 4)!!.copy(
                selectors = facts.chainForRange(0, 4)!!.selectors.filterIsInstance<Selector.ProgressHint>(),
            ),
            id = "hl-broken",
        )

        val bands = highlightRanges(listOf(good, broken), facts)

        assertEquals("只该剩下能解开的那一条", 1, bands.size)
        assertEquals("hl-good", bands.single().annotationId)
        assertEquals("颜色必须从库里那条标注带出来", 0x66FFD54F.toInt(), bands.single().colorArgb)
    }
}
