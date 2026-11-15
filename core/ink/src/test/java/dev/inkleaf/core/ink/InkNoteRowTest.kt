package dev.inkleaf.core.ink

import dev.inkleaf.core.model.InkReflowSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "笔记状态"行的测试。
 *
 * 这里守的是一件事：**列表里显示的位置必须与画出来的位置来自同一份判定**。
 * 如果界面自己按锚点字段推算"跳到哪句话"，它就会与渲染层给出不同的答案，
 * 而症状恰好是最刺眼的那种："这里说在第三段，跳过去第三段上什么都没有"。
 */
class InkNoteRowTest {

    private val doc = TestDoc(3)

    private fun resolutionOf(vararg records: InkResolution.Record) =
        InkResolution(drawable = emptyList(), records = records.toList())

    private fun record(
        line: dev.inkleaf.core.model.LineBox?,
        state: InkState = InkState.AMBIGUOUS,
        reason: String = "引文匹配到多个位置",
        originalText: String? = null,
        drawn: Boolean = false,
        hasStrokes: Boolean = true,
    ) = InkResolution.Record(
        annotationId = "ink-1",
        state = state,
        semantics = InkReflowSemantics.FOLLOWER,
        line = line,
        confidence = 0.72f,
        reason = reason,
        originalText = originalText,
        drawn = drawn,
        hasStrokes = hasStrokes,
    )

    @Test
    fun `解到某一行的笔记带上那一行的原文与跳转位置`() {
        val lines = doc.lines()
        val rows = resolutionOf(record(lines[1])).rowsForDisplay(doc.facts(lines))

        val row = rows.single()
        assertEquals(doc.texts[1], row.excerpt)
        assertEquals(doc.full.indexOf(doc.texts[1]), row.charOffset)
        assertTrue(row.canJump)
    }

    @Test
    fun `跳转位置是全文偏移而不是块内偏移`() {
        // 界面的跳转入口吃的是全文偏移（与恢复阅读位置同一个单位）。
        // 若这里错给块内偏移，症状是"跳到文章开头附近"——观众看不出来是错的。
        val lines = doc.lines()
        val row = resolutionOf(record(lines[2])).rowsForDisplay(doc.facts(lines)).single()

        assertEquals(doc.full.indexOf(doc.texts[2]), row.charOffset)
        assertTrue("第三块的全文偏移必须大于零", (row.charOffset ?: 0) > 0)
    }

    @Test
    fun `孤儿笔记没有原文也没有可跳的位置`() {
        val rows = resolutionOf(record(null, state = InkState.ORPHANED, reason = "结构路径不存在"))
            .rowsForDisplay(doc.facts())

        val row = rows.single()
        assertNull(row.excerpt)
        assertNull(row.charOffset)
        assertFalse(row.canJump)
    }

    @Test
    fun `过长的原文被截断并带省略号`() {
        val lines = doc.lines()
        val row = resolutionOf(record(lines[0])).rowsForDisplay(doc.facts(lines), maxExcerptChars = 3).single()

        assertEquals(doc.texts[0].take(3) + "…", row.excerpt)
    }

    @Test
    fun `状态解释与置信度原样带出来`() {
        // 状态行与列表必须用同一句话解释同一个状态，否则用户会以为是两回事。
        val lines = doc.lines()
        val row = resolutionOf(record(lines[0], state = InkState.RESOLVED, reason = "结构路径与引文都对上了"))
            .rowsForDisplay(doc.facts(lines)).single()

        assertEquals(InkState.RESOLVED, row.state)
        assertEquals(InkReflowSemantics.FOLLOWER, row.semantics)
        assertEquals(0.72f, row.confidence, 0.0001f)
        assertEquals("结构路径与引文都对上了", row.reason)
    }

    @Test
    fun `空行原文不会变成空字符串而是 null`() {
        // 空行（块内没有文字）不该在界面上显示成一对空引号。
        val lines = doc.lines()
        val facts = object : LayoutFacts by doc.facts(lines) {
            override fun textOf(line: dev.inkleaf.core.model.LineBox): String = "   "
        }
        val row = resolutionOf(record(lines[0])).rowsForDisplay(facts).single()

        assertNull(row.excerpt)
        assertTrue("原文缺失不影响跳转能力", row.canJump)
    }

    @Test
    fun `解不开的笔记带出当时标在哪句话上`() {
        // 这是"确认位置"唯一的线索：解析已经说不出位置了，但库里还记着当时那句文字。
        val rows = resolutionOf(
            record(null, state = InkState.ORPHANED, originalText = "他在门口站了很久"),
        ).rowsForDisplay(doc.facts())

        val row = rows.single()
        assertEquals("他在门口站了很久", row.originalText)
        assertNull("孤儿笔记没有解析到的原文", row.excerpt)
        assertTrue("解不开的笔记必须能手动重挂", row.canReanchor)
    }

    @Test
    fun `已经贴好的笔记也能改挂：判据是有没有笔迹可挂`() {
        // M3.1 时这里的判据是"解不开且画不出来"，理由是**面板当时只列画不出来的笔记** ——
        // 给已经画好的笔记塞一个按钮，它看起来像装饰。
        // 面板扩成"本页笔记"之后，这个按钮对已经画好的笔记终于有了确切含义：
        // **改挂**（把这条笔记改挂到另一行上）。于是判据换成"库里有没有笔迹可挂"，
        // 而它必须**不**依赖状态 —— 挂得好好的笔记也可能挂错了地方。
        val lines = doc.lines()
        val resolved = resolutionOf(record(lines[0], state = InkState.RESOLVED))
            .rowsForDisplay(doc.facts(lines)).single()
        val ambiguous = resolutionOf(record(null, state = InkState.AMBIGUOUS))
            .rowsForDisplay(doc.facts(lines)).single()

        assertTrue("挂得好好的笔记也要能改挂", resolved.canReanchor)
        assertTrue(ambiguous.canReanchor)
    }

    @Test
    fun `画在原处的冻结笔记也能改挂：改的是指向而不是位置`() {
        // 冻结语义（PINNED / FREE）的墨迹不依赖解析结果：锚点解不开时它照样画在原处。
        // 它同样能改挂 —— 改挂改的是"它记在哪一行"，墨迹不会跟着移（见 `InkReanchor`）。
        // 而"画在原处"这件事必须继续从 `drawn` 里读得出来：状态行与面板按它筛。
        val row = resolutionOf(record(null, state = InkState.ORPHANED, drawn = true))
            .rowsForDisplay(doc.facts()).single()

        assertTrue("drawn 必须原样带到界面上", row.drawn)
        assertTrue(row.canReanchor)
    }

    @Test
    fun `没有笔迹点的笔记不给改挂入口`() {
        // 库里没有笔迹行时重挂无从挂起。界面必须据此**不给**入口 ——
        // 给一个点下去必然失败的按钮，比不给按钮更糟。
        val row = resolutionOf(
            record(null, state = InkState.ORPHANED, hasStrokes = false),
        ).rowsForDisplay(doc.facts()).single()

        assertFalse(row.hasStrokes)
        assertFalse(row.canReanchor)
    }

    @Test
    fun `有笔迹点时 hasStrokes 与 canReanchor 一起为真`() {
        val row = resolutionOf(record(doc.lines()[0], state = InkState.RESOLVED))
            .rowsForDisplay(doc.facts()).single()

        assertTrue(row.hasStrokes)
        assertTrue(row.canReanchor)
    }

    @Test
    fun `画不出来的笔记才是面板要列的那一类`() {
        // 面板与状态行都用 `notDrawn` 筛，而不是用状态 —— 两条轴混在一起就会出现
        // "状态行说有 1 条画不出来，点开面板是空的"。
        val resolvedButDrawn = record(doc.lines()[0], state = InkState.RESOLVED, drawn = true)
        val stuck = resolutionOf(
            record(null, state = InkState.ORPHANED, drawn = false),
            resolvedButDrawn,
        )

        assertEquals(1, stuck.notDrawn.size)
        assertEquals(InkState.ORPHANED, stuck.notDrawn.single().state)
    }
}
