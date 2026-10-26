package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.AnchorChainFactory
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字批注（边注 / 高亮）的测试：**建链 → 解析 → 列表面板要显示的那一行**。
 *
 * 这里有两件事必须钉死，因为它们在真机上很难发现：
 *
 *  1. **写入方向与读取方向是同一个口径**：`chainForLine` 造出来的链必须能被
 *     `typedNoteRows` 解回同一行。两者一旦漂开，症状是"边注记下来了，
 *     下次打开就找不到位置"—— 而两条路径各自的测试都是绿的。
 *  2. **同一个状态在两种笔记里说同一句话**：打字批注与手写笔迹共用
 *     `AnchorResolver`，也就必须共用 `AnchorReason`。用户是在同一个面板里
 *     看到这两类笔记的，"找不到原来的位置"与"锚点失效"会让他以为遇到两种毛病。
 */
class TypedNotesTest {

    private val doc = TestDoc(3)

    private fun marginNote(chain: dev.inkleaf.core.model.TextAnchorChain, id: String = "note-1") =
        Annotation.MarginNote(
            id = id,
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0,
            noteText = "这里要再想一遍",
            anchor = chain,
            side = Side.RIGHT,
        )

    @Test
    fun `建出来的链能被解析回同一行`() {
        val facts = doc.facts()
        val target = facts.lines[1]

        val chain = facts.chainForLine(target)
        assertNotNull("普通正文行必须建得出链", chain)

        val row = typedNoteRows(listOf(marginNote(chain!!)), facts).single()

        assertEquals(InkState.RESOLVED, row.state)
        assertEquals(TypedNoteKind.MARGIN_NOTE, row.kind)
        assertEquals("解析到的那一行必须就是当时那一行", doc.textOf(target), row.excerpt)
        assertEquals(
            "跳到原文用的偏移必须就是那一行的起点",
            doc.absoluteRangeOf(target)!!.first,
            row.charOffset,
        )
        assertTrue("说得清它在哪一行", row.located)
        assertTrue("打字批注永远可以改挂（链就是它的全部几何）", row.canReanchor)
        assertEquals("批注正文必须原样带出来", "这里要再想一遍", row.noteText)
    }

    @Test
    fun `没有文字的行建不出链`() {
        val facts = doc.facts()
        // 块路径指向不存在的块 ⇒ 取不到文字 ⇒ 取不到绝对区间。
        val blank = LineBox(
            anchor = Selector.Structural("blk[99]", 0, 0, 1f),
            rectDoc = target0().rectDoc,
            baselineY = target0().baselineY,
            fontSizePx = 40f,
        )

        assertNull("空行必须建不出链，否则会写进一条重排后必然变孤儿的记录", facts.chainForLine(blank))
    }

    private fun target0(): LineBox = doc.lines().first()

    @Test
    fun `改版之后边注判孤儿但内容还在`() {
        val facts = doc.facts()
        val chain = facts.chainForLine(facts.lines[0])!!
        // 只换指纹：模拟"正文换了一版"（锚点链记的是"文件还是原来那一份"）。
        val changed = doc.facts(fingerprint = "fp-换了一版")

        val row = typedNoteRows(listOf(marginNote(chain)), changed).single()

        assertEquals(InkState.ORPHANED, row.state)
        assertFalse("说不清它在哪一行", row.located)
        assertEquals("内容不能跟着一起消失", "这里要再想一遍", row.noteText)
        assertNotNull("当时标在哪句话上是用户唯一的线索", row.originalText)
        assertTrue(
            "原因必须与手写笔迹用同一句话说清，实际=${row.reason}",
            row.reason.startsWith(AnchorReason.ORPHANED_CAUSE),
        )
        assertFalse("解不开就没有可跳的位置", row.canJump)
        assertTrue("解不开也照样能改挂（这正是它的出路）", row.canReanchor)
    }

    @Test
    fun `多处都可能时交给用户而不是自己挑一处`() {
        // 两行一模一样的正文：引文无法区分它们（这是 Ambiguous 唯一的成因）。
        val identical = List(2) { doc.line(0, it * 50f) }
        val facts = doc.facts(lines = identical)

        // 手工造一条"块路径不存在、但引文与指纹都对"的链：规则 2a（结构型命中）
        // 落空 ⇒ 规则 2b（引文匹配）命中两行 ⇒ Ambiguous。
        val chain = AnchorChainFactory.build(
            normalizedText = facts.normalizedText,
            profile = facts.profile,
            fingerprint = facts.fingerprint,
            target = AnchorChainFactory.Target(
                blockPath = "blk[99]",
                blockStartOffset = 0,
                blockEndOffset = doc.texts[0].length,
                absoluteStart = 0,
                absoluteEnd = doc.texts[0].length,
            ),
            docRatio = 0f,
        )

        val row = typedNoteRows(listOf(marginNote(chain)), facts).single()

        assertEquals(InkState.AMBIGUOUS, row.state)
        assertFalse(row.located)
        assertEquals("两处都可能，行内不能挑一处显示", null, row.excerpt)
        assertNull(row.charOffset)
        assertTrue(
            "必须说清有几处可能，实际=${row.reason}",
            row.reason == AnchorReason.ambiguous(2),
        )
    }

    @Test
    fun `只管打字批注，笔迹与其它标注不走这里`() {
        val facts = doc.facts()
        val chain = facts.chainForLine(facts.lines[0])!!
        val ink = Annotation.Ink(
            id = "ink-1",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0,
            anchor = chain,
            semantics = InkReflowSemantics.FOLLOWER,
        )
        val underline = Annotation.Underline(
            id = "ul-1",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0,
            anchors = listOf(chain),
        )
        val bookmark = Annotation.Bookmark(
            id = "bm-1",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0,
            progressHint = Selector.ProgressHint(docRatio = 0.1f),
        )
        val highlight = Annotation.Highlight(
            id = "hl-1",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0,
            anchor = chain,
        )

        val rows = typedNoteRows(listOf(ink, underline, bookmark, highlight), facts)

        assertEquals(
            "只有恰好一条锚点链的标注才走这条路：笔迹有自己的解析器，" +
                "下划线（多行锚点）与书签（不绑文本）还没有创建入口，" +
                "也就还没有「在列表里算哪一行」这个问题的答案",
            listOf("hl-1"),
            rows.map { it.annotationId },
        )
        assertEquals(TypedNoteKind.HIGHLIGHT, rows.single().kind)
    }

    @Test
    fun `摘录过长会截断而且截断在面板这一侧`() {
        val facts = doc.facts()
        val target = facts.lines[0]
        val row = typedNoteRows(listOf(marginNote(facts.chainForLine(target)!!)), facts, maxExcerptChars = 4).single()

        assertEquals("第0行的…", row.excerpt)
        assertTrue(
            "链里的引文是锚点的一部分，不该被面板的显示参数改短",
            row.originalText!!.length > 4,
        )
    }
}
