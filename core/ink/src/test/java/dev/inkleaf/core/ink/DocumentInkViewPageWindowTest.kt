package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
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
 * 分页绘制窗口（M32）：一页只画这一页的字，上下页边距里不许露相邻页的半行。
 *
 * ## 这一条只能靠像素验
 *
 * 分页本身一直是自洽的（`分页覆盖全部行且不重叠` 是绿的）：页首尾相接、
 * 每页高度不超过正文框。问题出在**绘制**：视图比页高（底部要留页边距那一条纸），
 * 而绘制只是把文档平移进来，没有"页"这层裁剪 —— 于是
 *
 * ```
 * 上一页最后一行 → 画进上页边距（只露下半截）
 * 下一页第一行   → 画进下页边距（只露上半截）
 * ```
 *
 * 页边距越小、字号越大露得越多。默认 4 mm 边距配 17 sp 是"多一行"，
 * 小边距 + 大字号就变成半截字 —— 用户的原话是"换页时字体被切了一半"。
 *
 * 所以这里断言的不是坐标，而是**画布上的像素落在哪几行**：
 * 第 0 页的下方、第 1 页的上方，都必须一个像素都没有。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentInkViewPageWindowTest {

    private val width = 900
    private val height = 1200

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics() = PageLayoutMetrics(
        contentWidthPx = width,
        // 与真实界面同构：视图高度 = 正文框 + 上下页边距。
        // 页边距按真机的实际像素取（4 mm 在 3 倍密度上约 70 px）：
        // 太窄的话「页边距装不下一行」这件事在测试里根本复现不出来。
        contentHeightPx = height - 2 * MARGIN,
        marginLeftPx = MARGIN.toFloat(),
        marginRightPx = MARGIN.toFloat(),
        marginTopPx = MARGIN.toFloat(),
        marginBottomPx = MARGIN.toFloat(),
    )

    private fun layout() = DocumentCodec.buildSession(
        "page-window-doc",
        DocumentKind.TXT,
        // 写够多行，保证不止一页（下面每条断言都需要"有邻居的页"）。
        (1..40).joinToString("\n") { "第 $it 行有一些文字，用来验证分页绘制的窗口边界是否准确。" },
    ).layout(metrics(), theme)

    /** 画一页。视图高度固定为 [height]，页首放在 [windowTopPx]。 */
    private fun render(windowTopPx: Int): Bitmap {
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout(), DocumentInkView.Mode.PAGED, windowTopPx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** 数一数这几行里有几个"画了东西"的像素（视图自己不画纸，空白处是全透明）。 */
    private fun drawnPixels(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) != 0) count++
            }
        }
        return count
    }

    /** 有像素的最上面 / 最下面一行，用来判断"画到哪为止"。 */
    private fun firstDrawnRow(bitmap: Bitmap): Int =
        (0 until height).firstOrNull { y -> (0 until width).any { x -> bitmap.getPixel(x, y) != 0 } } ?: -1

    private fun lastDrawnRow(bitmap: Bitmap): Int =
        (height - 1 downTo 0).firstOrNull { y -> (0 until width).any { x -> bitmap.getPixel(x, y) != 0 } } ?: -1

    @Test
    fun `第 0 页的下页边距里不会露出下一页的半行字`() {
        val layout = layout()
        assertTrue("前提：这份文档要不止一页", layout.pages.size >= 2)
        val page = layout.pages[0]
        val bitmap = render(page.topPx)

        val pageBottom = MARGIN + page.heightPx
        assertTrue("前提：这一页必须真的画出了字", drawnPixels(bitmap, MARGIN, pageBottom) > 0)
        assertEquals(
            "页首是 $MARGIN、本页高 ${page.heightPx}，视图高 $height：" +
                "页边距那 $MARGIN 行里不该有任何字",
            0,
            drawnPixels(bitmap, pageBottom, height),
        )
    }

    @Test
    fun `翻到第二页时上页边距里不会露出上一页的半截字`() {
        val layout = layout()
        assertTrue("前提：这份文档要不止一页", layout.pages.size >= 2)
        val page = layout.pages[1]
        val bitmap = render(page.topPx)

        assertTrue("前提：这一页必须真的画出了字", drawnPixels(bitmap, MARGIN, MARGIN + page.heightPx) > 0)
        assertEquals(
            "页首 $MARGIN 以上是上页边距，那里不该有上一页最后一行的下半截",
            0,
            drawnPixels(bitmap, 0, MARGIN),
        )
    }

    @Test
    fun `本页的字一个像素都没被剪掉`() {
        val layout = layout()
        val page = layout.pages[1]
        val bitmap = render(page.topPx)

        // 反过来验一遍：窗口不能剪过头。整页的字都画出来了（不是"剪干净了也没字"）。
        assertTrue(
            "本页整页都应当是空白的 —— 窗口剪过头了",
            drawnPixels(bitmap, MARGIN, MARGIN + page.heightPx) > 1000,
        )
        assertTrue("页首以上不该有像素（被剪掉的上半截字）", firstDrawnRow(bitmap) >= MARGIN)
        assertTrue(
            "最后一行像素必须落在本页之内（$MARGIN + ${page.heightPx}）",
            lastDrawnRow(bitmap) < MARGIN + page.heightPx,
        )
    }

    private companion object {
        const val MARGIN = 80
    }
}
