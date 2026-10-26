package dev.inkleaf.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面在视口里的缩放规则。
 *
 * 用真实的纸面尺寸算期望值：A4 = 595 × 842 pt，Pixel 6 Pro 的可用区按
 * 1440 × 2400 px 估。这样"竖屏按宽、横屏按高"不是一句注释，而是一条会红的断言。
 */
class PdfPageFitTest {

    private val a4Width = 595f
    private val a4Height = 842f

    @Test
    fun `竖屏手机：A4 页按宽度铺满，上下留白`() {
        val scale = PdfPageFit.scale(1440f, 2400f, a4Width, a4Height)

        assertEquals(1440f / 595f, scale, 0.0001f)
        assertEquals(1440f, PdfPageFit.widthInView(a4Width, scale), 0.01f)
        // 842 × (1440/595) ≈ 2038，确实装得下 2400 高。
        assertTrue(PdfPageFit.heightInView(a4Height, scale) < 2400f)
    }

    @Test
    fun `横屏：按高度定，宽度两侧留白`() {
        val scale = PdfPageFit.scale(2400f, 1200f, a4Width, a4Height)

        assertEquals(1200f / 842f, scale, 0.0001f)
        assertEquals(1200f, PdfPageFit.heightInView(a4Height, scale), 0.01f)
        assertTrue(PdfPageFit.widthInView(a4Width, scale) < 2400f)
    }

    @Test
    fun `横向长页：按宽度定，不裁切`() {
        val scale = PdfPageFit.scale(1000f, 500f, 1000f, 400f)

        assertEquals(1f, scale, 0.0001f)
    }

    @Test
    fun `视口或页面尺寸退化时给出可用的兜底比例`() {
        assertEquals(1f, PdfPageFit.scale(0f, 100f, a4Width, a4Height), 0f)
        assertEquals(1f, PdfPageFit.scale(100f, 0f, a4Width, a4Height), 0f)
        assertEquals(1f, PdfPageFit.scale(100f, 100f, 0f, a4Height), 0f)
        assertEquals(1f, PdfPageFit.scale(100f, 100f, a4Width, 0f), 0f)
        assertEquals(1f, PdfPageFit.scale(-5f, 100f, a4Width, a4Height), 0f)
    }

    @Test
    fun `极小视口不会得到零或负的缩放`() {
        val scale = PdfPageFit.scale(0.001f, 0.001f, a4Width, a4Height)

        assertTrue(scale >= PdfPageFit.MIN_SCALE)
        assertTrue(PdfPageFit.widthInView(a4Width, scale) >= 1f)
        assertTrue(PdfPageFit.heightInView(a4Height, scale) >= 1f)
    }

    @Test
    fun `位图尺寸取整之后至少一像素`() {
        assertEquals(1, PdfPageFit.bitmapWidth(0.4f, 1f))
        assertEquals(1, PdfPageFit.bitmapHeight(0.4f, 1f))
        assertEquals(1440, PdfPageFit.bitmapWidth(a4Width, 1440f / a4Width))
    }

    @Test
    fun `渲染尺寸与显示尺寸一致（不糊也不多占内存）`() {
        val scale = PdfPageFit.scale(1440f, 2400f, a4Width, a4Height)
        val bitmapWidth = PdfPageFit.bitmapWidth(a4Width, scale)
        val viewWidth = PdfPageFit.widthInView(a4Width, scale)

        assertEquals(viewWidth, bitmapWidth.toFloat(), 0.51f)
    }
}
