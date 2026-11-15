package dev.inkleaf.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageGeometry] 的单测。
 *
 * 这里用的都是**真机的真实数字**，不是随手编的：
 *
 * - 手机：AVD `Pixel_6_Pro`，411 × 891 dp，560 dpi → `density = 3.5`，
 *   绘制区 1440 × 2900 px，17 sp 正文字号 = 59.5 px，4 mm 边距 = 88.19 px；
 * - 平板：800 × 1280 dp，320 dpi → `density = 2.0`，1600 × 2560 px。
 *
 * M8 之前正文只占手机屏宽的 51%（= 12 个汉字），这些用例就是那条缺陷的回归网：
 * 谁再把"屏宽 − 2 × 毫米边距"直接当正文宽，第 1 条就会红。
 */
class PageGeometryTest {

    private val phoneW = 1440
    private val phoneH = 2900
    private val phoneDensity = 3.5f
    private val phoneText = 59.5f
    private val phone4mm = 4f / 25.4f * 560f // = 88.189

    private val tabletW = 1600
    private val tabletH = 2560
    private val tabletDensity = 2f
    private val tabletText = 34f

    private fun solvePhone(margin: Float = phone4mm) =
        PageGeometry.solve(phoneW, phoneH, margin, phoneText, phoneDensity)

    private fun solveTablet(margin: Float = 4f / 25.4f * 320f) =
        PageGeometry.solve(tabletW, tabletH, margin, tabletText, tabletDensity)

    // ── 手机：铺满 ────────────────────────────────────────────────────────────

    @Test
    fun `手机竖屏_默认四毫米_正文占宽至少八成`() {
        val m = solvePhone()
        val ratio = m.contentWidthPx.toFloat() / phoneW
        assertTrue("正文占宽只有 ${"%.1f".format(ratio * 100)}%，应当 ≥ 80%", ratio >= 0.8f)
    }

    @Test
    fun `手机竖屏_正文宽度就是屏宽减去两侧边距`() {
        val m = solvePhone()
        assertEquals(phoneW - 2 * phone4mm, m.contentWidthPx.toFloat(), 1.5f)
        assertEquals(phone4mm, m.marginLeftPx, 0.5f)
        assertEquals(m.marginLeftPx, m.marginRightPx, 0.001f)
    }

    @Test
    fun `手机竖屏_用户把边距调成零_仍然留最小留白`() {
        val m = solvePhone(margin = 0f)
        val minEdge = PageGeometry.MIN_EDGE_DP * phoneDensity
        assertEquals(minEdge, m.marginLeftPx, 0.001f)
    }

    @Test
    fun `手机竖屏_用户把边距拉到四十毫米_正文仍然有十二个字`() {
        val huge = 40f / 25.4f * 560f // ≈ 881 px，比屏宽的一半还大
        val m = solvePhone(margin = huge)
        val em = m.contentWidthPx / phoneText
        assertTrue("正文只剩 ${"%.1f".format(em)} 个字宽，应当 ≥ ${PageGeometry.MIN_CONTENT_EM}", em >= PageGeometry.MIN_CONTENT_EM - 0.05f)
        assertTrue("边距必须让位（不能超过屏宽一半）", m.marginLeftPx < phoneW / 2f)
    }

    @Test
    fun `旧的十六毫米默认值_在手机上也不再挤成一条`() {
        val old = 16f / 25.4f * 560f
        val m = solvePhone(margin = old)
        val em = m.contentWidthPx / phoneText
        assertTrue("正文 ${"%.1f".format(em)} 个字宽", em >= PageGeometry.MIN_CONTENT_EM - 0.05f)
    }

    // ── 平板：限制行宽 ────────────────────────────────────────────────────────

    @Test
    fun `平板_正文行宽封顶四十个字`() {
        val m = solveTablet()
        val em = m.contentWidthPx / tabletText
        assertTrue("一行 ${"%.1f".format(em)} 个字宽，应当 ≤ ${PageGeometry.MAX_LINE_EM}", em <= PageGeometry.MAX_LINE_EM + 0.05f)
        assertTrue("封顶之后不该只剩一点点", em >= PageGeometry.MIN_CONTENT_EM)
    }

    @Test
    fun `平板_行宽封顶之后正文居中_左右边距相等且大于用户设置`() {
        val m = solveTablet()
        val userMargin = 4f / 25.4f * 320f
        assertEquals(m.marginLeftPx, m.marginRightPx, 0.001f)
        assertTrue("居中留白应当大于用户设置", m.marginLeftPx > userMargin)
        assertEquals(tabletW.toFloat(), m.contentWidthPx + 2 * m.marginLeftPx, 1.5f)
    }

    // ── 不变式 ────────────────────────────────────────────────────────────────

    @Test
    fun `任何情形_正文框加边距都等于屏幕尺寸`() {
        val cases = listOf(
            phoneW to solvePhone(),
            phoneW to solvePhone(margin = 0f),
            phoneW to solvePhone(margin = 881f),
            tabletW to solveTablet(),
            360 to PageGeometry.solve(360, 640, 0f, 40f, 2f),
        )
        for ((width, m) in cases) {
            assertTrue("内容宽必须为正：${m.contentWidthPx}", m.contentWidthPx > 0)
            assertTrue("内容高必须为正：${m.contentHeightPx}", m.contentHeightPx > 0)
            assertTrue("边距不能为负：${m.marginLeftPx}", m.marginLeftPx >= 0f)
            assertTrue("上下边距不能为负：${m.marginTopPx}", m.marginTopPx >= 0f)
            assertEquals(
                "宽度 ${width}px 时：内容 ${m.contentWidthPx} + 两侧 ${2 * m.marginLeftPx}",
                width.toFloat(),
                m.contentWidthPx + 2 * m.marginLeftPx,
                1.5f,
            )
        }
    }

    @Test
    fun `字号变大_正文宽度不会变小`() {
        val small = PageGeometry.solve(phoneW, phoneH, phone4mm, 42f, phoneDensity)
        val big = PageGeometry.solve(phoneW, phoneH, phone4mm, 112f, phoneDensity)
        assertTrue(
            "字号从 42px 变到 112px，正文宽从 ${small.contentWidthPx} 变成 ${big.contentWidthPx}",
            big.contentWidthPx >= small.contentWidthPx,
        )
        // 大字号下仍然保证 12 个字宽（这条是"边距让位"的直接体现）
        assertTrue(big.contentWidthPx / 112f >= PageGeometry.MIN_CONTENT_EM - 0.05f)
    }

    @Test
    fun `上下边距最多各占四分之一高度`() {
        val m = PageGeometry.solve(phoneW, phoneH, 881f, phoneText, phoneDensity)
        assertTrue(m.marginTopPx <= phoneH * PageGeometry.MAX_VERTICAL_EDGE_RATIO + 0.5f)
        assertEquals(m.marginTopPx, m.marginBottomPx, 0.001f)
        assertTrue("正文至少占一半高度", m.contentHeightPx >= phoneH / 2)
    }

    @Test
    fun `宽度极小时不崩溃_正文宽度至少一像素`() {
        val m = PageGeometry.solve(3, 3, 100f, 59.5f, 3.5f)
        assertTrue("内容宽必须为正：${m.contentWidthPx}", m.contentWidthPx >= 1)
        assertTrue("内容高必须为正：${m.contentHeightPx}", m.contentHeightPx >= 1)
        assertTrue("边距不能为负：${m.marginLeftPx}", m.marginLeftPx >= 0f)
        assertTrue("上下边距不能为负：${m.marginTopPx}", m.marginTopPx >= 0f)
    }

    @Test
    fun `零尺寸输入不产生负数`() {
        val m = PageGeometry.solve(0, 0, 0f, 0f, 0f)
        assertEquals(1, m.contentWidthPx)
        assertEquals(1, m.contentHeightPx)
        assertTrue(m.marginLeftPx >= 0f)
        assertTrue(m.marginTopPx >= 0f)
    }

    @Test
    fun `density 为零时没有最小留白_但正文仍然非空`() {
        val m = PageGeometry.solve(1440, 2900, 0f, 59.5f, 0f)
        assertEquals(0f, m.marginLeftPx, 0.001f)
        assertEquals(1440, m.contentWidthPx)
    }

    // ── 让位口径（M33）──────────────────────────────────────────────────────

    @Test
    fun `让位口径下纵向留白只由两个常量决定，与毫米边距无关`() {
        // 同一块屏、同一个边距设置，只把"毫米"从 4 mm 拉到 16 mm：
        // 横向会跟着变，纵向**必须**一模一样 —— 否则用户调一次边距，
        // 一页少的行数取决于边距而不是版面。
        val narrow = PageGeometry.solve(
            phoneW, phoneH, 45f, phoneText, phoneDensity,
            topReservePx = 170f, bottomReservePx = 30f,
        )
        val wide = PageGeometry.solve(
            phoneW, phoneH, 180f, phoneText, phoneDensity,
            topReservePx = 170f, bottomReservePx = 30f,
        )
        assertEquals("顶部留白就是顶栏 + 空隙", 170f, narrow.marginTopPx, 0.001f)
        assertEquals("底部留白就是那口气", 30f, narrow.marginBottomPx, 0.001f)
        assertEquals(narrow.marginTopPx, wide.marginTopPx, 0.001f)
        assertEquals(narrow.marginBottomPx, wide.marginBottomPx, 0.001f)
        assertTrue("毫米变大会让正文变窄（横向仍按边距走）", wide.contentWidthPx < narrow.contentWidthPx)
    }

    @Test
    fun `让位口径下正文高度等于屏高减去两条留白`() {
        val top = 170f
        val bottom = 30f
        val m = PageGeometry.solve(
            phoneW, phoneH, 45f, phoneText, phoneDensity,
            topReservePx = top, bottomReservePx = bottom,
        )
        assertEquals((phoneH - top - bottom).toInt(), m.contentHeightPx)
    }

    @Test
    fun `顶栏再高也不会把正文挤没`() {
        // 横屏 + 荒谬的让位值：上下加起来最多让出 40%，正文至少留 60%。
        val m = PageGeometry.solve(
            2900, 900, 45f, 59.5f, 3.5f,
            topReservePx = 4000f, bottomReservePx = 4000f,
        )
        assertTrue("正文不能消失：${m.contentHeightPx}", m.contentHeightPx >= 1)
        assertTrue(
            "正文至少留 ${((1 - PageGeometry.MAX_RESERVED_RATIO) * 100).toInt()}% 的高度" +
                "（实际 ${m.contentHeightPx} / 900）",
            m.contentHeightPx >= (900 * (1 - PageGeometry.MAX_RESERVED_RATIO)).toInt() - 1,
        )
        // 压缩必须等比：两头都荒谬时，谁也不能独吞这份预算。
        assertEquals(
            "上下让出的比例应当保持不变",
            m.marginTopPx,
            m.marginBottomPx,
            0.001f,
        )
    }

    @Test
    fun `不传让位参数时仍然是原来的对称边距口径`() {
        // 兼容性：排版预览与其它调用点不传这两个参数，行为必须与 M33 之前一致。
        val m = PageGeometry.solve(phoneW, phoneH, 45f, phoneText, phoneDensity)
        assertEquals(m.marginTopPx, m.marginBottomPx, 0.001f)
        assertEquals(45f, m.marginTopPx, 0.001f)
    }
}
