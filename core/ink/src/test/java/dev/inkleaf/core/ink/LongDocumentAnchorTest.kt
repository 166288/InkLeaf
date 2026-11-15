package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.TextAnchorChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长文档后半本的锚点回归（M42，评审 §6.1 的 P0）。
 *
 * `AnchorResolver.resolve` 默认只扫前 200 行；把**全书**行盒原样递进去时，
 * 第 200 行之后的标注一律判孤儿。高亮、下划线、打字批注三条路径曾经都没有收窗，
 * 于是长书从第十页开始，正文里标的东西再也画不出来 —— 屏幕上不会报任何错。
 *
 * 这里造 250 行、取第 220 行，断言三条路径都能落到正文上。
 * 用 250 而不是几千：200 是那条硬边界，跨过去一行就够说明问题。
 */
class LongDocumentAnchorTest {

    private val doc = TestDoc(250)

    private fun chainFor(index: Int, facts: LayoutFacts = doc.facts()): TextAnchorChain {
        val range = facts.absoluteRangeOf(doc.lines()[index])!!
        val chain = facts.chainForRange(range.first, range.last + 1)
        assertNotNull("第 $index 行必须建得出链", chain)
        return chain!!
    }

    private fun highlight(chain: TextAnchorChain) = Annotation.Highlight(
        id = "hl-long",
        docId = "doc-1",
        createdAt = 1L,
        colorArgb = 0x66FFD54F.toInt(),
        anchor = chain,
    )

    @Test
    fun `第 220 行的高亮仍然画得出来`() {
        val facts = doc.facts()
        val chain = chainFor(220, facts)

        val band = highlightRanges(listOf(highlight(chain)), facts).single()

        assertEquals(
            "第220行的文字内容",
            facts.normalizedText.substring(band.range.first, band.range.last + 1),
        )
    }

    @Test
    fun `第 220 行的下划线仍然画得出来`() {
        val facts = doc.facts()
        val chain = chainFor(220, facts)
        val underline = Annotation.Underline(
            id = "u-long",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0xFF000000.toInt(),
            anchors = listOf(chain),
        )

        val bands = underlineBands(listOf(underline), facts)

        assertTrue("第 220 行的下划线不能被判孤儿", bands.isNotEmpty())
    }

    @Test
    fun `第 220 行的打字批注仍然能定位`() {
        val facts = doc.facts()
        val chain = chainFor(220, facts)
        val note = Annotation.MarginNote(
            id = "n-long",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = 0xFF000000.toInt(),
            noteText = "第 220 行上的批注",
            anchor = chain,
        )

        val row = typedNoteRows(listOf(note), facts).single()

        assertEquals(InkState.RESOLVED, row.state)
        assertNotNull("定位到行才给得出跳转偏移", row.charOffset)
        assertEquals("第220行的文字内容", row.excerpt)
    }
}
