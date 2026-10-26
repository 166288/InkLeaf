package dev.inkleaf.core.document

import dev.inkleaf.core.model.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台文本内容 → 行盒 的映射测试。
 *
 * 这一层能测的原因是它**不碰 Android**：`PdfPageTextContent` 是平台对象，
 * 但映射只需要"文字 + 矩形"，而矩形用本工程的 [Box]。于是"一段文字与几个盒怎么配对"
 * 这件事可以在 JVM 上逐条钉死 —— 它是 PDF 锚点精度的上游。
 */
class PdfTextLineMapperTest {

    @Test
    fun `一行一个盒：文字与几何原样搬过来`() {
        val lines = PdfTextLineMapper.map(
            text = "他说：走吧。",
            boxes = listOf(Box(72f, 100f, 372f, 124f)),
        )

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("他说：走吧。", line.text)
        assertEquals(72f, line.left, 0.001f)
        assertEquals(100f, line.top, 0.001f)
        assertEquals(372f, line.right, 0.001f)
        assertEquals(124f, line.bottom, 0.001f)
        // 字号是估算：行高 24 × 0.82。
        assertEquals(24f * PdfTextLineMapper.LINE_HEIGHT_TO_EM, line.fontSizePx, 0.001f)
        // 基线在盒底之上，且落在盒内。
        assertTrue(line.baselineYPx < line.bottom)
        assertTrue(line.baselineYPx > line.top)
    }

    @Test
    fun `折成两行的一段文字按盒数拆开，各自拿到自己的盒`() {
        val lines = PdfTextLineMapper.map(
            text = "第一行\n第二行",
            boxes = listOf(Box(72f, 100f, 372f, 124f), Box(72f, 124f, 360f, 148f)),
        )

        assertEquals(2, lines.size)
        assertEquals(listOf("第一行", "第二行"), lines.map { it.text })
        assertEquals(100f, lines[0].top, 0.001f)
        assertEquals(124f, lines[1].top, 0.001f)
        assertEquals(360f, lines[1].right, 0.001f)
    }

    @Test
    fun `只有一个盒时不给行盒：折行没有几何可分，不猜`() {
        val lines = PdfTextLineMapper.map(
            text = "第一行\n第二行",
            boxes = listOf(Box(10f, 10f, 210f, 60f)),
        )

        assertTrue("行数与盒数对不上就不产生行盒", lines.isEmpty())
    }

    @Test
    fun `盒数与行数对不上时不给行盒，而不是按顺序硬配`() {
        val lines = PdfTextLineMapper.map(
            text = "甲\n乙\n丙",
            boxes = listOf(Box(0f, 0f, 10f, 10f), Box(20f, 20f, 30f, 30f)),
        )

        assertTrue("宁可这一页不能按行锚定，也不要行盒与文字错位", lines.isEmpty())
    }

    @Test
    fun `空白文字不产生行盒`() {
        assertTrue(PdfTextLineMapper.map("   \n\t", listOf(Box(0f, 0f, 1f, 1f))).isEmpty())
        assertTrue(PdfTextLineMapper.map("", emptyList()).isEmpty())
    }

    @Test
    fun `有文字但没有包围盒时不抛异常，也不产生行盒`() {
        // 实测 Android 17 的 PdfRenderer 会给出这种内容：文字在，bounds 为空。
        // 没有几何就没有行盒；抛异常会把整份 PDF 变成「打不开」。
        assertTrue(PdfTextLineMapper.map("有文字但没有盒", emptyList()).isEmpty())
    }

    @Test
    fun `退化的盒不会让字号变成零或负数`() {
        val lines = PdfTextLineMapper.map("字", listOf(Box(5f, 5f, 5f, 5f)))

        val line = lines.single()
        assertTrue("右边界必须大于左边界", line.right > line.left)
        assertTrue("下边界必须大于上边界", line.bottom > line.top)
        assertTrue("字号必须为正", line.fontSizePx > 0f)
        assertTrue("基线必须是有限数", line.baselineYPx.isFinite())
    }

    @Test
    fun `CRLF 的换行符不会留在文字里`() {
        val lines = PdfTextLineMapper.map(
            text = "上\r\n下",
            boxes = listOf(Box(0f, 0f, 100f, 20f), Box(0f, 20f, 100f, 40f)),
        )

        assertEquals(listOf("上", "下"), lines.map { it.text })
    }

    @Test
    fun `盒里的文字被 trimEnd 只去掉行尾空白，不吞掉行首缩进`() {
        val lines = PdfTextLineMapper.map(
            text = "    缩进的一行",
            boxes = listOf(Box(0f, 0f, 100f, 20f)),
        )

        assertEquals("    缩进的一行", lines.single().text)
    }
}
