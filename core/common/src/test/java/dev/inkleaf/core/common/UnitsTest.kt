package dev.inkleaf.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun `毫米与像素互为逆运算`() {
        val dpi = 320
        val px = Units.mmToPx(10f, dpi)
        assertEquals(10f, Units.pxToMm(px, dpi), 1e-4f)
    }

    @Test
    fun `一英寸等于二十五点四毫米`() {
        // 160 dpi 是 Android 的基准密度（1px = 1dp = 1/160 inch）
        assertEquals(160f, Units.mmToPx(Units.MM_PER_INCH, 160), 1e-3f)
    }

    @Test
    fun `不同密度下同一物理尺寸得到不同像素`() {
        val low = Units.mmToPx(10f, 160)
        val high = Units.mmToPx(10f, 480)
        assertEquals(3f, high / low, 1e-3f)
    }

    @Test
    fun `sp 与像素互为逆运算`() {
        val scaledDensity = 2.75f
        val px = Units.spToPx(16f, scaledDensity)
        assertEquals(16f, Units.pxToSp(px, scaledDensity), 1e-4f)
    }

    @Test
    fun `scaledDensity 为零时返回零而不是无穷`() {
        // Inf / NaN 一旦进入锚点数据就会被序列化并永久污染，必须在这里挡住
        assertEquals(0f, Units.pxToSp(100f, 0f), 0f)
        assertEquals(0f, Units.pxToEm(100f, 0f), 0f)
    }

    @Test
    fun `em 换算使用行自身的字号`() {
        assertEquals(40f, Units.emToPx(2f, 20f), 1e-4f)
        assertEquals(0.5f, Units.pxToEm(20f, 40f), 1e-4f)
    }

    @Test
    fun `clamp 夹紧到区间`() {
        assertEquals(5f, Units.clamp(100f, -5f, 5f), 0f)
        assertEquals(-5f, Units.clamp(-100f, -5f, 5f), 0f)
        assertEquals(1f, Units.clamp(1f, -5f, 5f), 0f)
    }
}
