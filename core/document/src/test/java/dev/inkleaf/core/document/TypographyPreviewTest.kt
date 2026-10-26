package dev.inkleaf.core.document

import dev.inkleaf.core.model.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页试读区的排版。
 *
 * 这里测的不是"预览好不好看"，而是三条会在真机上被用户撞见的事实：
 *
 * 1. **字号变大，一行真的变少** —— 否则预览在骗人；
 * 2. **边距变大，一行真的变少**（在边距还没被 `PageGeometry` 让位之前）；
 * 3. **预览用的是真排版** —— 首行缩进落在 `getParagraphLeft` 上，而不是 Compose
 *    的近似排版。
 *
 * 走 `NATIVE` 图形模式（真 Skia），与 `TextIndentTest` 一致：`LEGACY` 下的字形宽度
 * 是假的，字号与字数的关系会失真，测出来的"事实"不能搬到真机上用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypographyPreviewTest {

    private fun preview(
        width: Int = 1080,
        height: Int = 900,
        textSizePx: Float = 51f,
        lineSpacing: Float = 1.45f,
        marginPx: Float = 0f,
        density: Float = 3f,
    ) = TypographyPreview.build(
        widthPx = width,
        heightPx = height,
        textSizePx = textSizePx,
        lineSpacingMultiplier = lineSpacing,
        userMarginPx = marginPx,
        density = density,
        // 测试固定用"纸张"主题的墨色：这些用例测的是版面几何，
        // 不是配色；固定一个墨色才能让"字数"这类断言稳定。
        inkArgb = 0xFF1B1B1B.toInt(),
        dark = false,
    )

    @Test
    fun `字号变大，一行排下的字变少`() {
        val small = preview(textSizePx = 42f).charsPerLine
        val large = preview(textSizePx = 72f).charsPerLine

        assertTrue("42 px 字号下应当至少排下 10 个字（实际 $small）", small >= 10)
        assertTrue("字号从 42 涨到 72，一行应当明显变少：$small → $large", large < small)
    }

    @Test
    fun `边距变大，一行排下的字变少`() {
        // 1080 px 宽、51 px 字号：正文至少 12 个字 = 612 px，边距最多 (1080-612)/2 = 234 px。
        // 这里取 0 与 200，两边都还没撞到让位上限，比的是边距本身的效果。
        val narrow = preview(marginPx = 200f).charsPerLine
        val wide = preview(marginPx = 0f).charsPerLine

        assertTrue("边距 200 px 时一行应当比无边距时少：$narrow vs $wide", narrow < wide)
    }

    @Test
    fun `边距大到会挤掉正文时，由引擎让位而不是把正文挤没`() {
        // 40 mm 在 1080 px 宽、480 dpi 上约 756 px 每边 —— 两边加起来比屏幕还宽。
        // 真书里不会出现这种版面，但滑杆能拉到 40 mm，用户就可能拉到底。
        val textSizePx = 51f
        val squeezed = preview(marginPx = 756f, textSizePx = textSizePx)

        // 引擎保证的是**宽度**：正文至少 12 em 宽（= 612 px），不是"排得下 12 个字形"。
        // 一字不差地排下 12 个字还需要标点避头尾配合，实测是 10 个 —— 差别来自
        // 断行器为了不让"。"落到行首而提前一格断行。
        assertEquals(
            "边距拉到底，正文宽度应当正好停在 ${12} em",
            PageGeometry.MIN_CONTENT_EM * textSizePx,
            squeezed.metrics.contentWidthPx.toFloat(),
            1f,
        )
        assertTrue(
            "这么窄的一行仍要能读（至少 10 个字，实际 ${squeezed.charsPerLine}）",
            squeezed.charsPerLine >= 10,
        )
    }

    @Test
    fun `预览用的是真排版：第一段首行有缩进`() {
        val p = preview()
        val first = p.layout.sections.first().layout
        assertTrue(
            "第一段是中文自然段，预览里也必须有首行缩进" +
                "（getParagraphLeft = ${first.getParagraphLeft(0)}）",
            first.getParagraphLeft(0) > 0f,
        )
    }

    @Test
    fun `第一行的字数是数出来的，不是算出来的`() {
        val p = preview()
        val first = p.layout.sections.first().layout
        val start = first.getLineStart(0)
        val rawEnd = first.getLineEnd(0)

        // 这一行是软换行（段落还没完），行尾没有空白，所以可见字数就是区间长度。
        // Android 的 `getLineEnd` 对**硬换行**不含那个 `\n`，对软换行却可能带上
        // 行尾空格 —— 所以实现里仍然要从尾部剥空白，这条用例钉的是"剥完之后对得上"。
        assertFalse(
            "前提：这一行不是段落末尾（否则测不到软换行）",
            first.text[rawEnd - 1] == '\n',
        )
        assertEquals(
            "数出来的字数应当等于这一行的可见字符数",
            rawEnd - start,
            p.charsPerLine,
        )
    }

    @Test
    fun `行尾的换行与空格都不算字`() {
        // 直接造一个短布局，绕开样本（样本里没有行尾空格）。
        // 在 JVM 上这是真 Skia 的一行，`getLineEnd` 会把那个空格也算进去。
        val text = "abc \n"
        val paint = android.text.TextPaint().apply {
            textSize = 40f
            color = 0xFF000000.toInt()
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, 1000)
            .build()

        val raw = layout.getLineEnd(0) - layout.getLineStart(0)
        assertTrue("前提：原始区间里确实带着空白（$raw）", raw >= 4)
        assertEquals("a b c 三个字，空格与换行都不算", 3, TypographyPreview.charsOnLine(layout, 0))
    }

    @Test
    fun `越界的行号返回 0，不抛异常`() {
        val p = preview()
        assertEquals(0, TypographyPreview.charsOnLine(p.layout.sections.first().layout, -1))
        assertEquals(0, TypographyPreview.charsOnLine(p.layout.sections.first().layout, 99_999))
    }

    @Test
    fun `预览只画完整的行，收口落在行与行之间`() {
        val p = preview()

        assertTrue("应当至少画出一行（实际 ${p.visibleTextHeightPx}）", p.visibleTextHeightPx > 0)
        assertTrue(
            "不能超出正文可用高度 ${p.metrics.contentHeightPx}（实际 ${p.visibleTextHeightPx}）",
            p.visibleTextHeightPx <= p.metrics.contentHeightPx,
        )

        // 关键：它必须正好等于某一行的底边。随便取个数字也能"不超过高度"，
        // 但那样裁出来就是半行字。
        val bottoms = p.layout.sections.flatMap { section ->
            (0 until section.layout.lineCount).map { section.topPx + section.layout.getLineBottom(it) }
        }
        assertTrue(
            "收口（${p.visibleTextHeightPx}）必须与某个行底对齐，行底有：$bottoms",
            p.visibleTextHeightPx in bottoms,
        )
    }

    @Test
    fun `窄到放不下一个字也不崩`() {
        val tiny = preview(width = 40, height = 60, textSizePx = 90f)
        assertTrue("字数不能为负", tiny.charsPerLine >= 0)
        assertTrue("正文宽度仍然为正", tiny.metrics.contentWidthPx > 0)
    }

    @Test
    fun `正文占屏宽的比例不会超过百分之百`() {
        val p = preview(marginPx = 0f)
        assertTrue("比例必须落在 0..1：${p.widthRatio}", p.widthRatio in 0f..1f)
        // 无边距时仍会留 12 dp 的最小边距（`PageGeometry.MIN_EDGE_DP`），
        // 所以占比是 1008/1080 ≈ 0.93，不是 1.0。
        assertTrue("无边距时正文应当铺满（实际 ${p.widthRatio}）", p.widthRatio > 0.9f)
        assertEquals(
            "三个框加起来必须正好是预览区宽度",
            1080f,
            p.metrics.contentWidthPx + p.metrics.marginLeftPx + p.metrics.marginRightPx,
            1f,
        )
    }
}
