package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 搜索命中高亮的绘制测试 —— 断言的是**像素**与**顺序**。
 *
 * 这里有两件事只有真栅格化才能验证（与 [InkStrokeRendererTest] 同理）：
 *  1. 高亮**真的被画出来**（而不是"调用了 fillRect 但什么都不在画布上"）；
 *  2. 高亮画在**文字下面**。这一条尤其重要：画在上面时，命中的那几个字会被
 *     色块盖住，用户看到的不是"这里命中了"，而是"这里糊了一块颜色"。
 *     验证办法是让高亮色**不透明**：如果它画在文字上面，那一行里"正好等于
 *     文字颜色"的像素会被整片替换掉（内部像素），计数会掉到 0 附近；
 *     画在下面则一个都不会少。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentInkViewHighlightTest {

    private val width = 900
    private val height = 1200

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics() = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    private fun layout() = DocumentCodec.buildSession(
        "highlight-doc",
        DocumentKind.TXT,
        // 刻意写够多行，让它**不止一页**（下面那条"窗口之外"的测试需要多页文档）
        (1..30).joinToString("\n") { "第 $it 行有一些文字，用来做高亮的验证。" },
    ).layout(metrics(), theme)

    /** 第一行的行盒（高亮就打在它上面）。 */
    private fun firstLine(): Box = layout().lineBoxes().first().rectDoc

    /** 第 [index] 行的行盒。 */
    private fun lineBox(index: Int): Box = layout().lineBoxes()[index].rectDoc

    private fun countPixels(bitmap: Bitmap, color: Int, within: Box? = null): Int {
        val left = (within?.left ?: 0f).toInt().coerceIn(0, width)
        val top = (within?.top ?: 0f).toInt().coerceIn(0, height)
        val right = (within?.right ?: width.toFloat()).toInt().coerceIn(0, width)
        val bottom = (within?.bottom ?: height.toFloat()).toInt().coerceIn(0, height)
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (bitmap.getPixel(x, y) == color) count++
            }
        }
        return count
    }

    private fun render(
        windowTopPx: Int = 0,
        highlight: List<Box> = emptyList(),
        highlightColor: Int = Color.MAGENTA,
        notes: List<HighlightRect> = emptyList(),
    ): Bitmap {
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout(), DocumentInkView.Mode.PAGED, windowTopPx)
        view.highlightColor = highlightColor
        view.submitSearchHighlight(highlight)
        view.submitNoteHighlights(notes)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun `提交的高亮会真的画到画布上`() {
        val bitmap = render(highlight = listOf(firstLine()))

        assertTrue("高亮色必须出现在画布上", countPixels(bitmap, Color.MAGENTA) > 0)
    }

    @Test
    fun `不提交高亮时画布上没有高亮色`() {
        val bitmap = render()

        assertEquals("没要求高亮就不该画", 0, countPixels(bitmap, Color.MAGENTA))
    }

    @Test
    fun `高亮画在文字下面而不是盖住文字`() {
        // 不透明高亮色 + "正好等于文字颜色"的像素计数：
        // 画在文字下面 → 一行里文字颜色的内部像素一个都不会少；
        // 画在文字上面 → 它们会被整片替换掉。
        val line = firstLine()
        val plain = render()
        val highlighted = render(highlight = listOf(line))

        val textColor = theme.textColor
        val plainTextPixels = countPixels(plain, textColor, line)
        assertTrue("前提：这一行本来就有文字像素（$plainTextPixels）", plainTextPixels > 0)

        assertEquals(
            "高亮不得替换掉任何文字像素（否则就是画在文字上面了）",
            plainTextPixels,
            countPixels(highlighted, textColor, line),
        )
        assertTrue("同时高亮本身必须画出来", countPixels(highlighted, Color.MAGENTA, line) > 0)
    }

    @Test
    fun `清除高亮后画布恢复干净`() {
        val line = firstLine()
        val withHighlight = render(highlight = listOf(line))
        val cleared = render(highlight = emptyList())

        assertTrue(countPixels(withHighlight, Color.MAGENTA) > 0)
        assertEquals("清空之后不该还留着颜色", 0, countPixels(cleared, Color.MAGENTA))
        assertEquals(
            "清空高亮也不该改变文字像素",
            countPixels(withHighlight, theme.textColor),
            countPixels(cleared, theme.textColor),
        )
    }

    @Test
    fun `窗口之外的命中高亮不画`() {
        // 分页模式下窗口上边在文档中段：上半部分的高亮不该出现在这一页上。
        // 若不按窗口裁剪，翻到后面的页时仍会看到别处的色块。
        val layout = layout()
        assertTrue("前提：这份文档必须不止一页", layout.pages.size >= 2)
        val laterTop = layout.pages.last().topPx
        val bitmap = render(windowTopPx = laterTop, highlight = listOf(firstLine()))

        assertEquals("窗口之外的高亮不许画", 0, countPixels(bitmap, Color.MAGENTA))
    }

    // ── 我自己标的高亮（存库的那一层）────────────────────────────────────────
    //
    // 与搜索命中是两层：搜索命中是"此刻正在找的就是这里"（一次性、一个色），
    // 标注高亮是"我在书里标过这里"（库里的每一条自带颜色）。

    @Test
    fun `标注高亮会画到画布上而且每条用自己的颜色`() {
        val bitmap = render(
            notes = listOf(
                HighlightRect(lineBox(0), Color.MAGENTA),
                HighlightRect(lineBox(1), Color.GREEN),
            ),
        )

        assertTrue("第一条的颜色必须画出来", countPixels(bitmap, Color.MAGENTA) > 0)
        assertTrue("第二条的颜色必须画出来（每条自带颜色，不是一层一个色）", countPixels(bitmap, Color.GREEN) > 0)
    }

    @Test
    fun `标注高亮也画在文字下面`() {
        // 与搜索命中同一条判据：不透明色 + "正好等于文字颜色"的像素计数。
        // 底色画到文字上面，用户看到的就不是"这几个字标了"，而是"这里糊了一块颜色"。
        val line = firstLine()
        val plain = render()
        val marked = render(notes = listOf(HighlightRect(line, Color.MAGENTA)))

        val textColor = theme.textColor
        val plainTextPixels = countPixels(plain, textColor, line)
        assertTrue("前提：这一行本来就有文字像素（$plainTextPixels）", plainTextPixels > 0)

        assertEquals(
            "标注高亮不得替换掉任何文字像素",
            plainTextPixels,
            countPixels(marked, textColor, line),
        )
        assertTrue("同时底色本身必须画出来", countPixels(marked, Color.MAGENTA, line) > 0)
    }

    @Test
    fun `搜索命中画在标注高亮之上`() {
        // 两者重叠时，用户正在找的那一处必须压在上面：底色是长期存在的背景，
        // 被它盖住的搜索命中等于"翻了半天没看出哪里是命中"。
        val line = firstLine()
        val bitmap = render(
            highlight = listOf(line),
            highlightColor = Color.CYAN,
            notes = listOf(HighlightRect(line, Color.MAGENTA)),
        )

        assertTrue("搜索命中必须看得见", countPixels(bitmap, Color.CYAN) > 0)
        assertEquals("同一个矩形上不该还剩下底色的颜色", 0, countPixels(bitmap, Color.MAGENTA))
    }

    @Test
    fun `窗口之外的标注高亮不画`() {
        val layout = layout()
        assertTrue("前提：这份文档必须不止一页", layout.pages.size >= 2)
        val bitmap = render(
            windowTopPx = layout.pages.last().topPx,
            notes = listOf(HighlightRect(firstLine(), Color.MAGENTA)),
        )

        assertEquals("窗口之外的底色不许画", 0, countPixels(bitmap, Color.MAGENTA))
    }
}
