package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.document.ReflowableTextLayout
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.UnderlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 渲染视图的**触摸坐标**与**装饰绘制**。
 *
 * 这个文件存在的直接原因是一个真实缺陷：`DocumentInkView` 把 `event.x/event.y`
 * 直接当成了文档坐标，而绘制时画布被 `translate(marginLeft, marginTop)` 平移过。
 * 后果是每一笔都偏移一个页边距 —— 写字时笔迹出现在手指的右下角、锚定挂到偏上的行。
 * 所有单元测试都绿，因为锚定逻辑本身没错；只有把"点下去 → 落到哪个坐标"测出来，
 * 这个错误才会暴露。
 *
 * 同理，下划线是**画出来**的东西：只断言"输入了一个 DrawableUnderline"证明不了
 * 屏幕上有一条线，必须数像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentInkViewTouchTest {

    private val width = 900
    private val height = 1200
    private val marginLeft = 24f
    private val marginTop = 24f

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics() = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height,
        marginLeftPx = marginLeft,
        marginRightPx = marginLeft,
        marginTopPx = marginTop,
        marginBottomPx = marginTop,
    )

    private fun layout(): ReflowableTextLayout = DocumentCodec.buildSession(
        "touch-doc",
        DocumentKind.TXT,
        (1..40).joinToString("\n") { "第 $it 行有一些文字，用来验证触摸与装饰。" },
    ).layout(metrics(), theme)

    private fun view(layout: ReflowableTextLayout = layout()): DocumentInkView {
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout, DocumentInkView.Mode.PAGED, 0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun controller(): InkLayerController = InkLayerController(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        sink = object : InkSink {
            override suspend fun commit(
                annotationId: String,
                segment: dev.inkleaf.core.anchoring.InkAnchorFactory.Segment,
                brush: BrushSpec,
            ): Boolean = true

            override suspend fun revoke(annotationIds: List<String>): Int = annotationIds.size
        },
        // 本文件只测"点落在哪个坐标"，抬笔之后的锚定是另一件事（见 InkRoundTripTest）。
        facts = { null },
        onChanged = {},
    )

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `触摸点会被换算成文档坐标`() {
        val view = view()
        val controller = controller()
        view.attachInk(controller)

        // 视图上 (200, 300) → 文档上 (200 - 24, 300 - 24)。
        // 必须再送一个 MOVE：控制器的 `inProgressPoints` 只在有**两个**点之后才有值
        // （一个点画不出线，那是它自己的契约，不是缺陷）。
        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        touch(view, MotionEvent.ACTION_MOVE, 210f, 310f)

        val points = controller.inProgressPoints
        assertNotNull(points)
        assertEquals("x 必须减掉左边距", 200f - marginLeft, points!![0], 0.01f)
        assertEquals("y 必须减掉上边距", 300f - marginTop, points[1], 0.01f)
    }

    @Test
    fun `翻页时 y 会加回窗口偏移`() {
        // 分页模式下手势坐标是相对**当前页**的，而文档坐标是相对整篇的：
        // 少加 windowTop 会让第 2 页以后写的字全部锚到第 1 页。
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        val page = layout().pages[1]
        view.submit(layout(), DocumentInkView.Mode.PAGED, page.topPx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val controller = controller()
        view.attachInk(controller)
        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 110f, 110f)

        val expected = 100f - marginTop + page.topPx
        assertEquals(expected, controller.inProgressPoints!![1], 0.01f)
    }

    /**
     * 关掉书写开关之后，**已经抬笔、还在等干墨**的那一笔必须继续画在屏幕上。
     *
     * 这条守的是用户报的"我画的圈有时候会消失，有时候又出现"里的第一半：
     * 开关原先直接摘掉控制器，而 `onDraw` 是"没有控制器就整层湿墨都不画"。
     * 于是点一下笔图标，刚抬笔的那一笔就从屏幕上消失，等解析回来才重新出现。
     * 开关该管的是"这一下触摸算不算写字"，不是"已经写下的墨还画不画"。
     */
    @Test
    fun `关掉书写开关时已经抬笔的墨照样画`() {
        val view = view()
        val magenta = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.MAGENTA, widthEm = 0.15f)
        val controller = inkController(brush = magenta)
        view.attachInk(controller, enabled = true)

        // 一笔横线：抬笔之后它进暂存（`facts` 解得出位置，所以会真的落盘）。
        controller.beginStroke(200f, 300f, 1f)
        controller.extendStroke(300f, 300f, 1f)
        controller.extendStroke(400f, 300f, 1f)
        controller.endStroke()
        assertTrue("前提：这一笔已经抬笔并在暂存里", controller.pendingStrokes.isNotEmpty())

        // 用户点了一下书写开关 —— 退出书写。
        view.attachInk(controller, enabled = false)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        assertTrue(
            "退出书写不该让刚写的墨消失（数到的洋红像素=${countMagenta(bitmap)}）",
            countMagenta(bitmap) > 50,
        )
    }

    @Test
    fun `关掉书写开关之后触摸不再落笔`() {
        val view = view()
        val controller = inkController()
        view.attachInk(controller, enabled = true)
        view.attachInk(controller, enabled = false)

        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        touch(view, MotionEvent.ACTION_MOVE, 210f, 310f)

        assertEquals("开关关着时这一串事件属于翻页手势，不该变成笔迹", null, controller.inProgressPoints)
    }

    @Test
    fun `下划线真的画到画布上`() {
        val l = layout()
        val line = l.lineBoxes()[1]
        val underline = DrawableUnderline(
            annotationId = "ul-1",
            left = line.rectDoc.left,
            right = line.rectDoc.right,
            baselineY = line.baselineY,
            fontSizePx = line.fontSizePx,
            colorArgb = Color.MAGENTA,
            // 线条刻意画粗一点：抗锯齿会把 2px 线的边缘混成半透明，
            // 精确等于 MAGENTA 的像素只剩几十个 —— 那是取样问题，不是"没画"。
            style = UnderlineStyle(thicknessEm = 0.12f),
        )
        val view = view(l)
        view.submitUnderlines(listOf(underline))

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        var magenta = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) == Color.MAGENTA) magenta++
            }
        }
        assertTrue("屏幕上必须真的有一条线，实际像素=$magenta", magenta > 100)

        // 线必须落在基线附近，而不是行盒顶边（差半行会看起来"划在字的上面"）。
        // 注意：baselineY 是**文档坐标**，而位图是**视图坐标** —— 两者差一次
        // `translate(marginLeft, marginTop)`，所以预期行要把上边距加回来。
        val expectedY = marginTop +
            line.baselineY + UnderlineStyle(thicknessEm = 0.12f).baselineOffsetEm * line.fontSizePx
        var bestRow = -1
        var bestCount = 0
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) if (bitmap.getPixel(x, y) == Color.MAGENTA) count++
            if (count > bestCount) {
                bestCount = count
                bestRow = y
            }
        }
        assertTrue(
            "线最密的那一行应当贴着基线：expected≈${expectedY.toInt()}，actual=$bestRow",
            kotlin.math.abs(bestRow - expectedY) <= 3f,
        )
    }

    @Test
    fun `橡皮点中笔迹会回调那一条`() {
        val view = view()
        val stroke = DrawableStroke(
            strokeId = "ink-1",
            brush = BrushSpec.DEFAULT_PEN,
            docPoints = floatArrayOf(100f, 200f, 300f, 200f),
            fontSizePx = 48f,
        )
        view.submitInk(listOf(stroke))
        view.attachEraser(true)

        var erased: String? = null
        view.onErase = { erased = it.strokeId }

        // 文档 (200,200) → 视图 (224,224)
        touch(view, MotionEvent.ACTION_DOWN, 200f + marginLeft, 200f + marginTop)
        touch(view, MotionEvent.ACTION_UP, 200f + marginLeft, 200f + marginTop)

        assertEquals("ink-1", erased)
    }

    @Test
    fun `橡皮没点中时会明确说出来`() {
        val view = view()
        view.submitInk(emptyList())
        view.attachEraser(true)

        var missed = false
        view.onEraseMiss = { missed = true }

        touch(view, MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(view, MotionEvent.ACTION_UP, 10f, 10f)

        assertTrue("静默失败是最难排查的一种反馈", missed)
    }

    @Test
    fun `橡皮关闭时不会擦掉任何东西`() {
        val view = view()
        view.submitInk(
            listOf(
                DrawableStroke(
                    strokeId = "ink-1",
                    brush = BrushSpec.DEFAULT_PEN,
                    docPoints = floatArrayOf(100f, 200f, 300f, 200f),
                    fontSizePx = 48f,
                ),
            ),
        )
        view.attachEraser(false)

        var erased: String? = null
        view.onErase = { erased = it.strokeId }
        // 没有挂控制器也没有橡皮：本 View 不消费事件，也就不会回调。
        touch(view, MotionEvent.ACTION_DOWN, 200f + marginLeft, 200f + marginTop)
        touch(view, MotionEvent.ACTION_UP, 200f + marginLeft, 200f + marginTop)

        assertEquals(null, erased)
    }

    /**
     * 一个**能真的落盘**的控制器：`facts` 用假文档，于是抬笔之后的锚定、提交、
     * settle 全部跑得通 —— 与 `controller()` 那个"只测坐标、故意不给排版"的版本
     * 是两件事，所以单开一个而不是改掉它。
     */
    private fun inkController(brush: BrushSpec = BrushSpec.DEFAULT_PEN): InkLayerController {
        val facts = TestDoc(2).facts()
        return InkLayerController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            sink = object : InkSink {
                override suspend fun commit(
                    annotationId: String,
                    segment: dev.inkleaf.core.anchoring.InkAnchorFactory.Segment,
                    brush: BrushSpec,
                ): Boolean = true

                override suspend fun revoke(annotationIds: List<String>): Int = annotationIds.size
            },
            facts = { facts },
            onChanged = {},
            worker = kotlinx.coroutines.Dispatchers.Unconfined,
            idFactory = { "ink-1" },
        ).also { it.brush = brush }
    }

    /**
     * 数"像洋红"的像素，而不是精确等于 `Color.MAGENTA`：
     * 抗锯齿会把边缘混成半透明，一条 7px 的线里精确值只剩中间那一道。
     * 那是取样问题，不是"没画"。
     */
    private fun countMagenta(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 200 && g < 80 && b > 200) count++
            }
        }
        return count
    }
}
