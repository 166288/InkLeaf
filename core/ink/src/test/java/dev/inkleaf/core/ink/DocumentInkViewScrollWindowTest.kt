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
 * 滚动模式的绘制窗口（M35）。
 *
 * ## 这里改的是"谁来撑高"
 *
 * 滚动模式原本让 View "有多高报多高"，靠外层 `verticalScroll` 平移这个
 * 巨大的 View 完成滚动。一本书 17001 行 ≈ **155 万像素** —— hwui 要为它建
 * 显示列表与裁剪区，真机上崩在原生层（Scudo 堆校验失败 / `std::vector`
 * length_error，崩点每次不同）。分页模式只报一屏高，所以从没碰到过。
 *
 * 现在 View 只报一屏，窗口上沿由滚动偏移决定；**两套坐标因此必须是同一个口径**
 * （文档坐标），否则顶部会固定缺一条字、或者整块内容上下错位。
 * 这套测试钉的就是这个口径 —— 它不是"看起来对"，是可以逐像素验的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentInkViewScrollWindowTest {

    private val width = 900
    private val height = 1200

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics() = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height - 2 * MARGIN,
        marginLeftPx = MARGIN.toFloat(),
        marginRightPx = MARGIN.toFloat(),
        marginTopPx = MARGIN.toFloat(),
        marginBottomPx = MARGIN.toFloat(),
    )

    private fun layout() = DocumentCodec.buildSession(
        "scroll-window-doc",
        DocumentKind.TXT,
        (1..40).joinToString("\n") { "第 $it 行有一些文字，用来验证滚动绘制的窗口边界是否准确。" },
    ).layout(metrics(), theme)

    private fun render(layout: dev.inkleaf.core.document.ReflowableTextLayout, windowTopPx: Int): Bitmap {
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout, DocumentInkView.Mode.SCROLL, windowTopPx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun drawnPixels(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) != 0) count++
            }
        }
        return count
    }

    private fun firstDrawnRow(bitmap: Bitmap): Int =
        (0 until height).firstOrNull { y -> (0 until width).any { x -> bitmap.getPixel(x, y) != 0 } } ?: -1

    private fun lastDrawnRow(bitmap: Bitmap): Int =
        (height - 1 downTo 0).firstOrNull { y -> (0 until width).any { x -> bitmap.getPixel(x, y) != 0 } } ?: -1

    /**
     * 这条是崩溃的根因，不是版面细节。
     *
     * 用 `UNSPECIFIED` 量：旧实现会老老实实返回文档高度（这本书 155 万像素），
     * 新实现返回 0（没有下限就报 0），父容器给多大它就多大。
     * 用 `EXACTLY` 量的话新旧都是屏高，这条测试就失去区分力了。
     */
    @Test
    fun `滚动模式下视图高度不跟着文档长`() {
        val layout = layout()
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout, DocumentInkView.Mode.SCROLL, 0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertTrue(
            "文档 ${layout.totalHeightPx}px 高，视图却量成了 ${view.measuredHeight}px。" +
                "报成文档高度就是旧实现：hwui 会为这么高的 View 建显示列表，" +
                "真机上直接崩在原生层。",
            view.measuredHeight < layout.totalHeightPx,
        )
    }

    /**
     * 滚动到文档中间时，屏幕最上面那一条边距里必须**还是正文**。
     *
     * 窗口上沿是文档坐标，而视图顶部那 `marginTop` 一条显示的是窗口上沿
     * **之前**的内容（滚动模式下上边距是纸的一部分，不是要留白的边）。
     * 少了这个往回够，滚动时顶端会固定缺一条字 —— 而且是无字的空白，
     * 看起来像"排版漏了"。
     */
    @Test
    fun `滚动到中间时顶端的边距里仍然是正文`() {
        val layout = layout()
        val windowTop = layout.totalHeightPx / 3
        assertTrue("前提：文档要够长", layout.totalHeightPx > height * 2)
        val bitmap = render(layout, windowTop)

        assertTrue(
            "最上面 $MARGIN 像素是一条空白 —— 窗口上沿的单位错了（多减了一个上边距？）",
            drawnPixels(bitmap, 0, MARGIN) > 0,
        )
    }

    /** 反过来验一遍：窗口不能把可见范围掐短，底部不许空出一条。 */
    @Test
    fun `滚动到中间时底边也画满了正文`() {
        val layout = layout()
        val windowTop = layout.totalHeightPx / 3
        val bitmap = render(layout, windowTop)

        assertTrue(
            "最后一行像素在 ${lastDrawnRow(bitmap)}，视图高 $height：" +
                "底部空出了一条，说明可见范围算短了",
            lastDrawnRow(bitmap) > height - 2 * MARGIN,
        )
    }

    /**
     * 滚到底：最后一行必须落在屏幕内。
     *
     * 上界算错（比如忘了算下边距、或者用了 `totalHeightPx - height`）时，
     * 表现为"永远差最后几行滚不到"—— 那是滚动模式最典型的一种残废：
     * 界面能滚、看着也正常，就是有一截永远读不到。
     */
    @Test
    fun `滚到底时最后一行画得出来`() {
        val layout = layout()
        val marginPx = (layout.metrics.marginTopPx + layout.metrics.marginBottomPx).toInt()
        val bottomTop = (layout.totalHeightPx + marginPx - height).coerceAtLeast(0)
        assertTrue("前提：这份文档要真的能滚", bottomTop > 0)
        val bitmap = render(layout, bottomTop)

        assertTrue("滚到底了却一个像素都没有", lastDrawnRow(bitmap) > 0)
        assertTrue(
            "最后一行画在 ${lastDrawnRow(bitmap)}，离视图底部（$height）还远 —— " +
                "文档末尾有一截滚不到",
            lastDrawnRow(bitmap) > height - 2 * MARGIN,
        )
    }

    /** 同一个偏移重复提交不该重排：滚动时每帧都会走 `update`。 */
    @Test
    fun `重复提交同一个窗口不会触发重新测量`() {
        val layout = layout()
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout, DocumentInkView.Mode.SCROLL, 100)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        // 一次真实布局之后，`isLayoutRequested` 会被清掉。若重复提交真的触发了
        // requestLayout，这个标记会重新立起来。
        assertTrue("前提：一次正常布局之后不该还挂着测量请求", !view.isLayoutRequested)
        view.submit(layout, DocumentInkView.Mode.SCROLL, 100)
        assertTrue(
            "值没变却又排了一次布局 —— 滚动时每帧都会调到这里，等于每帧全树重排",
            !view.isLayoutRequested,
        )
        assertEquals(height, view.measuredHeight)
    }

    private companion object {
        const val MARGIN = 80
    }
}
