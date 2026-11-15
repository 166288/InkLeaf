package dev.inkleaf.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面位图与笔迹共用的那一个变换。
 *
 * 这层没有 Android 依赖，因此可以在 JVM 上把边界算到小数点后三位：
 * 屏幕上"差一点点"在阅读器里就是"字被划在下面那一行"，而那种错误
 * 只在真机上肉眼可见 —— 等到那时再查，已经分不清是映射错了还是事件坐标错了。
 */
class PdfInkMappingTest {

    private val eps = 1e-3f

    @Test
    fun `A4 页在竖屏视口里按宽度定，左右不留白，上下居中`() {
        val placement = PdfInkMapping.solve(
            viewWidthPx = 1440f,
            viewHeightPx = 2471f,
            pageWidthPx = 595f,
            pageHeightPx = 842f,
        )

        val expectedScale = 1440f / 595f
        assertTrue(placement.usable)
        assertEquals(expectedScale, placement.scale, eps)
        assertEquals("按宽度定，左右没有余量", 0f, placement.left, eps)
        assertEquals(
            "上下余量对半分",
            (2471f - 842f * expectedScale) / 2f,
            placement.top,
            eps,
        )
        assertEquals(1440f, placement.widthInViewPx, eps)
        assertEquals(842f * expectedScale, placement.heightInViewPx, eps)
    }

    @Test
    fun `文档坐标与视图坐标在第二页上往返一致`() {
        val placement = PdfInkMapping.solve(1440f, 2471f, 595f, 842f)
        val pageTop = 858f // 842 + PAGE_GAP_PX：第二页在文档坐标里的上边界
        val docX = 100f
        val docY = pageTop + 42f

        val viewX = placement.viewX(docX)
        val viewY = placement.viewY(docY, pageTop)

        assertEquals(docX, placement.docX(viewX), eps)
        assertEquals(docY, placement.docY(viewY, pageTop), eps)
        assertEquals("页偏移必须真的参与换算", 42f, placement.docY(viewY, pageTop) - pageTop, eps)
    }

    @Test
    fun `高页按高度定，左右居中`() {
        val placement = PdfInkMapping.solve(1440f, 1000f, 500f, 2000f)

        assertEquals(0.5f, placement.scale, eps)
        assertEquals(595f, placement.left, eps)
        assertEquals(0f, placement.top, eps)
        assertEquals(250f, placement.widthInViewPx, eps)
        assertEquals(1000f, placement.heightInViewPx, eps)
    }

    @Test
    fun `宽页按宽度定，上下居中`() {
        val placement = PdfInkMapping.solve(1440f, 1000f, 1000f, 500f)

        assertEquals(1.44f, placement.scale, eps)
        assertEquals(0f, placement.left, eps)
        assertEquals(140f, placement.top, eps)
        assertEquals(1440f, placement.widthInViewPx, eps)
        assertEquals(720f, placement.heightInViewPx, eps)
    }

    @Test
    fun `小页在视口里居中而不是被拉伸`() {
        val placement = PdfInkMapping.solve(1440f, 2471f, 200f, 400f)

        assertEquals(2471f / 400f, placement.scale, eps)
        assertEquals((1440f - 200f * (2471f / 400f)) / 2f, placement.left, eps)
        assertEquals(0f, placement.top, eps)
    }

    @Test
    fun `退化输入不产生 NaN，也不假装页面可画`() {
        val noView = PdfInkMapping.solve(0f, 0f, 595f, 842f)
        assertFalse("没有视口就没有可画的页面", noView.usable)
        assertEquals("兜底比例必须是有限值", 1f, noView.scale, eps)
        assertEquals(10f, noView.docX(10f), eps)
        assertEquals(20f, noView.docY(20f, 0f), eps)

        val noPage = PdfInkMapping.solve(1440f, 2471f, 0f, 0f)
        assertFalse(noPage.usable)
        assertTrue(noPage.widthInViewPx.isFinite())
        assertTrue(noPage.heightInViewPx.isFinite())
    }
}
