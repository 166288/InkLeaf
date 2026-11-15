package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.model.UnderlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下划线 / 波浪线的纯逻辑：**写进去的那一行 → 解析回哪一行 → 画在什么位置**。
 *
 * 与 [HighlightsTest] 是同一套假文档、同一套问题，因为两者共用同一条解析链
 * （`AnchorResolver`）。差别只在产物：高亮要一个区间，下划线要一条贴着基线的线。
 */
class UnderlinesTest {

    private val doc = TestDoc(3)

    private fun underline(
        chains: List<TextAnchorChain>,
        id: String = "ul-1",
        wavy: Boolean = false,
        color: Int = 0xFF1F5FA8.toInt(),
    ) = Annotation.Underline(
        id = id,
        docId = "doc-1",
        createdAt = 1L,
        colorArgb = color,
        anchors = chains,
        style = UnderlineStyle(wavy = wavy),
    )

    @Test
    fun `一行下划线画在这一行的基线上`() {
        val facts = doc.facts()
        val line = facts.lines[1]
        val chain = facts.chainForRange(
            doc.absoluteRangeOf(line)!!.first,
            doc.absoluteRangeOf(line)!!.last + 1,
        )
        assertNotNull("普通正文行必须建得出链", chain)

        val band = underlineBands(listOf(underline(listOf(chain!!))), facts).single()

        assertEquals("横向范围就是行盒", line.rectDoc.left, band.left, 0.01f)
        assertEquals(line.rectDoc.right, band.right, 0.01f)
        assertEquals("纵向基准是基线，不是行盒底边", line.baselineY, band.baselineY, 0.01f)
        assertEquals("字号随笔迹带下来，用于 em → px", line.fontSizePx, band.fontSizePx, 0.01f)
        assertEquals("画的是哪一条标注必须带回去（删除时要用）", "ul-1", band.annotationId)
    }

    @Test
    fun `波浪线的样式留在标注上`() {
        val facts = doc.facts()
        val line = facts.lines[0]
        val range = doc.absoluteRangeOf(line)!!
        val chain = facts.chainForRange(range.first, range.last + 1)!!

        val band = underlineBands(listOf(underline(listOf(chain), wavy = true)), facts).single()
        assertTrue("波浪是标注的属性，不是渲染时的开关", band.style.wavy)
    }

    @Test
    fun `一条标注的多条链各自画一行`() {
        // 下划线的模型就是"每行一条链"。只画第一条，会让跨行的下划线少一半。
        val facts = doc.facts()
        val chains = listOf(0, 1).map { index ->
            val range = doc.absoluteRangeOf(facts.lines[index])!!
            facts.chainForRange(range.first, range.last + 1)!!
        }

        val bands = underlineBands(listOf(underline(chains)), facts)
        assertEquals(2, bands.size)
        assertEquals(
            "两条线必须落在两条不同的基线上",
            false,
            bands[0].baselineY == bands[1].baselineY,
        )
    }

    @Test
    fun `解析不到的行不画`() {
        // 与高亮同一条规则：位置就是锚点，解不开就一个像素都不画。
        val facts = doc.facts()
        val line = facts.lines[0]
        val range = doc.absoluteRangeOf(line)!!
        val chain = facts.chainForRange(range.first, range.last + 1)!!

        // 换一份指纹不同的排版：链里的源指纹对不上，锚点解不开。
        val changed = doc.facts(fingerprint = "fp-changed")
        assertEquals(0, underlineBands(listOf(underline(listOf(chain))), changed).size)
    }

    @Test
    fun `零宽的行不画线`() {
        val facts = doc.facts(lines = doc.lines().mapIndexed { index, line ->
            if (index == 0) line.copy(rectDoc = line.rectDoc.copy(right = line.rectDoc.left)) else line
        })
        val chain = facts.chainForRange(0, doc.texts[0].length)!!
        assertTrue("长度为 0 的线在 Canvas 上不存在", underlineBands(listOf(underline(listOf(chain))), facts).isEmpty())
    }

    @Test
    fun `画线用的区间与高亮同一口径`() {
        val facts = doc.facts()
        val line = facts.lines[1]
        assertEquals(
            "两者都必须去掉首尾空白，否则一个从缩进起、一个从字起",
            facts.lineHighlightRange(line),
            facts.underlineRangeOf(line),
        )
    }
}
