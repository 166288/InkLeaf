package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.document.ReflowableTextLayout
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 多指针：**手掌压着屏、笔在写**。三条用例，对应三处此前各自错误的判断。
 *
 * 起因是用户报的"写快了笔迹会中断"。查下来不是性能问题，是三个独立缺陷：
 *
 *   1. 坐标永远取 `event.x`（= 0 号指针）。手掌先落屏时 0 号是手掌，
 *      于是这一笔的坐标全程来自那只不动的手 → 线停在原地；
 *   2. `ACTION_POINTER_UP`（笔抬起、手掌还压着）没有分支，不触发落盘 → 这一笔白写；
 *   3. `ACTION_CANCEL` 直接清空 → 已经写出来的半截无声消失。
 *
 * 三条都是"输入语义"的错，与绘制快慢无关，所以它们能用 Robolectric 在
 * 纯逻辑层面测：只要控制器收到的坐标与落盘的次数对得上，就说明判断对了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PenPointersTest {

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

    /** 刻意只放 5 行：笔迹落在页面下方那片**空白**里，数像素时不会与文字混在一起。 */
    private fun layout(): ReflowableTextLayout = DocumentCodec.buildSession(
        "pen-pointer-doc",
        DocumentKind.TXT,
        (1..5).joinToString("\n") { "第 $it 行" },
    ).layout(metrics(), theme)

    private class RecordingSink : InkSink {
        val segments = ArrayList<InkAnchorFactory.Segment>()

        override suspend fun commit(
            annotationId: String,
            segment: InkAnchorFactory.Segment,
            brush: BrushSpec,
        ): Boolean {
            segments += segment
            return true
        }

        override suspend fun revoke(annotationIds: List<String>): Int = annotationIds.size
    }

    private val doc = TestDoc(2)
    private val sink = RecordingSink()

    private fun view(): DocumentInkView {
        val view = DocumentInkView(RuntimeEnvironment.getApplication())
        view.submit(layout(), DocumentInkView.Mode.PAGED, 0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun controller(lineTop: Float = 0f): InkLayerController = InkLayerController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        sink = sink,
        // 行盒来自 TestDoc：默认第 0 行覆盖文档坐标 y∈[0, 50]，正是我们落笔的位置。
        // 数像素那条用例把行挪到页面下方，好让笔迹落在没有文字的地方。
        facts = { doc.facts(lines = doc.lines(top = lineTop)) },
        onChanged = {},
        worker = Dispatchers.Unconfined,
    )

    // ── 事件构造 ────────────────────────────────────────────────────────────

    private class Ptr(val id: Int, val x: Float, val y: Float, val stylus: Boolean = false)

    private fun event(action: Int, actionIndex: Int = -1, vararg pointers: Ptr): MotionEvent {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = if (pointers[i].stylus) {
                    MotionEvent.TOOL_TYPE_STYLUS
                } else {
                    MotionEvent.TOOL_TYPE_FINGER
                }
            }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = if (pointers[i].stylus) 0.9f else 1f
                size = 1f
            }
        }
        val masked = if (actionIndex >= 0) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        return MotionEvent.obtain(
            0L,
            0L,
            masked,
            pointers.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun View.send(e: MotionEvent) {
        try {
            dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    /** 手掌先落在 (400, 300)，笔随后落在 (120, 30)。 */
    private fun palmThenPen(view: View) {
        view.send(event(MotionEvent.ACTION_DOWN, pointers = arrayOf(Ptr(0, 400f, 300f))))
        view.send(
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                pointers = arrayOf(Ptr(0, 400f, 300f), Ptr(1, 120f, 30f, stylus = true)),
            ),
        )
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    fun `手掌先落屏时坐标跟的是笔`() {
        val view = view()
        val controller = controller()
        view.attachInk(controller)

        palmThenPen(view)
        view.send(
            event(
                MotionEvent.ACTION_MOVE,
                pointers = arrayOf(Ptr(0, 400f, 300f), Ptr(1, 200f, 40f, stylus = true)),
            ),
        )

        val points = controller.inProgressPoints
        assertTrue("笔划了两个点，应当画得出一条线", points != null)
        // 视口 (120,30) → 文档 (96, 6)。若错跟 0 号指针，这里会是 376 / 276。
        assertEquals("起点必须是笔落下的位置", 120f - marginLeft, points!![0], 0.01f)
        assertEquals(30f - marginTop, points[1], 0.01f)
        assertEquals("终点跟着笔走", 200f - marginLeft, points[2], 0.01f)
        assertEquals(40f - marginTop, points[3], 0.01f)
    }

    @Test
    fun `手掌那一段不会被接到笔迹上`() {
        val view = view()
        val controller = controller()
        view.attachInk(controller)

        // 手掌先落屏会先起一笔（只有 1 个点，画不出线）。换到笔上时它必须被丢掉，
        // 否则屏幕上会多出一条从掌心 (400,300) 划到笔尖的直线，而且会真的落库。
        palmThenPen(view)

        assertEquals("换笔之后只剩笔这一个点", 1, controller.inProgressPointCount)
        val points = controller.inProgressPoints
        assertEquals("换笔之前没有可画的点", null, points)
    }

    @Test
    fun `笔抬起而手掌还压着时会落盘`() {
        val view = view()
        val controller = controller()
        view.attachInk(controller)

        palmThenPen(view)
        view.send(
            event(
                MotionEvent.ACTION_MOVE,
                pointers = arrayOf(Ptr(0, 400f, 300f), Ptr(1, 200f, 40f, stylus = true)),
            ),
        )
        // 笔抬起（1 号指针），手掌（0 号）仍在屏上。
        view.send(
            event(
                MotionEvent.ACTION_POINTER_UP,
                actionIndex = 1,
                pointers = arrayOf(Ptr(0, 400f, 300f), Ptr(1, 200f, 40f, stylus = true)),
            ),
        )

        assertEquals("这一笔必须真的写进库", 1, sink.segments.size)
        assertEquals("抬笔之后暂存里要留着它，直到干墨到场", 1, controller.pendingStrokes.size)
        assertFalse(controller.isWriting)

        // 手掌最后抬起：不能凭空再记一笔。
        view.send(event(MotionEvent.ACTION_UP, pointers = arrayOf(Ptr(0, 400f, 300f))))
        assertEquals(1, sink.segments.size)
    }

    @Test
    fun `系统取消也把已经写出来的半截留下`() {
        val view = view()
        val controller = controller()
        view.attachInk(controller)

        view.send(event(MotionEvent.ACTION_DOWN, pointers = arrayOf(Ptr(0, 100f, 30f, stylus = true))))
        view.send(event(MotionEvent.ACTION_MOVE, pointers = arrayOf(Ptr(0, 200f, 40f, stylus = true))))
        view.send(event(MotionEvent.ACTION_CANCEL, pointers = arrayOf(Ptr(0, 200f, 40f, stylus = true))))

        assertEquals("取消不是「这一笔不算数」，而是「先存下来」", 1, sink.segments.size)
        assertTrue("屏幕上也要留着，直到干墨到场", controller.pendingStrokes.isNotEmpty())
    }

    @Test
    fun `抬笔之后到干墨到场之前屏幕上一直有墨`() {
        // 用户报的"每次写完才显示"：此前的实现在抬笔那一刻清空点，而库里还没有它，
        // 于是有一段屏幕上什么都没有。这里数像素证明那条线一直在。
        val view = view()
        val controller = controller(lineTop = 596f)
        view.attachInk(controller)

        view.send(event(MotionEvent.ACTION_DOWN, pointers = arrayOf(Ptr(0, 100f, 620f, stylus = true))))
        view.send(event(MotionEvent.ACTION_MOVE, pointers = arrayOf(Ptr(0, 300f, 700f, stylus = true))))
        view.send(event(MotionEvent.ACTION_UP, pointers = arrayOf(Ptr(0, 300f, 700f, stylus = true))))

        val withPending = inkPixels(view)
        assertTrue("抬笔之后屏幕上必须有墨，实际像素=$withPending", withPending > 100)
        assertEquals("这一笔还在等干墨", 1, controller.pendingStrokes.size)

        // 干墨来了，但这一版里**没有这一笔**（库读晚了一步 / 刷新还没跑完）。
        // 以前这里断言"回执仍然会到、暂存该撤"—— 那正是用户报的"圈有时候消失、
        // 有时候又出现"：撤了暂存、干墨又没有它，屏幕上就空一段，等下次解析才回来。
        // 现在判据是内容：这一版没提到它，暂存就得留着。
        view.submitInk(emptyList(), InkCoverage(drawnRootIds = setOf("ink-别的笔")))

        assertEquals("这一版里没有它，暂存必须留着", 1, controller.pendingStrokes.size)
        assertTrue(
            "屏幕上不许少一个像素（撤掉之后=${inkPixels(view)}，撤掉之前=$withPending）",
            inkPixels(view) >= withPending,
        )
    }

    /** 数"不同于文字层"的像素：把两次绘制都落到空白区，只数右下角的落笔范围。 */
    private fun inkPixels(view: View): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var count = 0
        // 落笔在视图坐标 (100..300, 620..700)，取一个略大的窗口；这块区域在 5 行短文本下方，
        // 本来一个不透明像素都没有。
        for (y in 600 until 740) {
            for (x in 80 until 340) {
                if (bitmap.getPixel(x, y) != 0) count++
            }
        }
        bitmap.recycle()
        return count
    }
}
