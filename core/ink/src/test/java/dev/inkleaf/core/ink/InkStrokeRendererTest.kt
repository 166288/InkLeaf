package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.inkleaf.core.model.BrushSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 渲染器的测试 —— 断言的是**像素**，不是"调用没抛异常"。
 *
 * 这里必须用 Robolectric 的 `NATIVE` 图形模式：`DRAW_PATH` 之类的模式会记住
 * 绘制指令但不会真的栅格化，于是"有没有画出东西""粗细对不对"这类断言
 * 在那种模式下**永远为真**，等于没测。
 *
 * 这也是本工程唯一能验证"笔迹真的会被画出来"的手段：本机没有设备/模拟器
 * （见 design/M2-实现记录与设计修订.md 的虚拟化说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkStrokeRendererTest {

    private val size = 200

    private fun bitmap() = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun opaquePixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }

    private fun horizontalLine(fromY: Float = 100f) = floatArrayOf(10f, fromY, 190f, fromY)

    /**
     * 一份"正在写"的最小实现：只把点摊平成 [WetStroke] 要的形状。
     *
     * 真实现是 [InkLayerController]（它要维护流水号与压力累加和）；这里只测渲染，
     * 用不上那些，但**必须走同一个入口** —— 否则"跟手那一笔"与"落笔之后那一笔"
     * 又变成两段各自被验证的代码。
     */
    private class FakeWet(
        private val points: FloatArray,
        override val wetBrush: BrushSpec,
        override val strokeSerial: Int = 1,
    ) : WetStroke {
        override val wetPointCount: Int get() = points.size / 2
        override val wetMeanPressure: Float = 1f
        override fun xAt(index: Int): Float = points[index * 2]
        override fun yAt(index: Int): Float = points[index * 2 + 1]
    }

    @Test
    fun `空笔迹不产生任何像素`() {
        val bitmap = bitmap()
        InkStrokeRenderer().draw(Canvas(bitmap), emptyList())

        assertEquals("没有笔迹就一个像素都不该被画上", 0, opaquePixels(bitmap))
    }

    @Test
    fun `一笔会在画布上留下该笔刷颜色的像素`() {
        val bitmap = bitmap()
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.RED, widthEm = 0.5f)

        InkStrokeRenderer().draw(
            Canvas(bitmap),
            listOf(
                DrawableStroke(
                    strokeId = "s1",
                    brush = pen,
                    docPoints = horizontalLine(),
                    fontSizePx = 40f,
                ),
            ),
        )

        // 笔宽 = 0.5em × 40px × 1.0（无压感缩放）= 20px，线心就在 y=100。
        assertEquals("线心必须是笔刷颜色", Color.RED, bitmap.getPixel(100, 100))
        assertTrue("一笔不该只画出一个像素", opaquePixels(bitmap) > 1000)
    }

    @Test
    fun `压力更大时同一笔会被画得更粗`() {
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.BLACK, widthEm = 0.3f, pressureScaleMax = 2f)

        val light = bitmap()
        InkStrokeRenderer().draw(
            Canvas(light),
            listOf(DrawableStroke("s1", pen, horizontalLine(), floatArrayOf(0.05f, 0.05f), fontSizePx = 100f)),
        )

        val heavy = bitmap()
        InkStrokeRenderer().draw(
            Canvas(heavy),
            listOf(DrawableStroke("s1", pen, horizontalLine(), floatArrayOf(1f, 1f), fontSizePx = 100f)),
        )

        assertTrue(
            "重压应当明显更粗：轻压=${opaquePixels(light)}px，重压=${opaquePixels(heavy)}px",
            opaquePixels(heavy) > opaquePixels(light) * 1.5f,
        )
    }

    @Test
    fun `跟手的一笔与落笔后的一笔走同一条绘制路径`() {
        val bitmap = bitmap()
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.BLUE, widthEm = 0.5f)

        InkStrokeRenderer().drawWet(
            canvas = Canvas(bitmap),
            stroke = FakeWet(horizontalLine(), pen),
            fontSizePx = 40f,
        )

        assertEquals(Color.BLUE, bitmap.getPixel(100, 100))
    }

    @Test
    fun `透明笔刷不产生像素`() {
        val bitmap = bitmap()

        // 橡皮的 colorArgb 就是 0：它在模型里是"擦除"，不是"画透明色"。
        // 若渲染层不过滤，用户会看到"画了但什么都没有"，然后以为是不灵敏。
        InkStrokeRenderer().draw(
            Canvas(bitmap),
            listOf(DrawableStroke("s1", BrushSpec.DEFAULT_ERASER, horizontalLine(), fontSizePx = 40f)),
        )

        assertEquals(0, opaquePixels(bitmap))
    }

    @Test
    fun `单点或不足两点的笔迹不画`() {
        val bitmap = bitmap()
        val renderer = InkStrokeRenderer()

        renderer.drawWet(Canvas(bitmap), FakeWet(floatArrayOf(100f, 100f), BrushSpec.DEFAULT_PEN), 40f)
        renderer.draw(
            Canvas(bitmap),
            listOf(DrawableStroke("s1", BrushSpec.DEFAULT_PEN, floatArrayOf(), floatArrayOf())),
        )

        assertEquals(0, opaquePixels(bitmap))
    }

    // ── 增量湿墨：写得越长不该越慢，但不能因此画歪 ──────────────────────────

    /** 一条起伏的折线（41 个点）：直线看不出贝塞尔的错，起伏才看得出。 */
    private fun wavyLine(): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0..40) {
            out.add(10f + i * 4f)
            out.add(100f + kotlin.math.sin(i / 3f) * 40f)
        }
        return out.toFloatArray()
    }

    /**
     * 画一帧。
     *
     * 每帧先把位图擦干净 —— 这是 `onDraw` 的真实前提：硬件加速下每帧都在
     * 自己的 `RenderNode` 里重放绘制命令，**从不**在上一帧的结果上叠加。
     * 少了这一步，"增量"会退化成"把旧像素留着"，测试反而帮它掩盖错误。
     */
    private fun Bitmap.frame(block: (Canvas) -> Unit) {
        eraseColor(Color.TRANSPARENT)
        block(Canvas(this))
    }

    @Test
    fun `逐步追加画出来的形状与一次画完一致`() {
        // 增量路径每帧只补一个新点。补的顺序一旦错一位，画出来就是"波形相位偏一格"——
        // 这种错肉眼在一条短线上几乎看不出来，所以这里比像素。
        // 而每帧擦干净画布之后，"漏补某一段"也会立刻暴露成墨量骤减。
        val points = wavyLine()
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.BLACK, widthEm = 0.2f)

        val oneShot = bitmap()
        InkStrokeRenderer().drawWet(Canvas(oneShot), FakeWet(points, pen, strokeSerial = 1), 40f)

        val stepByStep = bitmap()
        val renderer = InkStrokeRenderer()
        val total = points.size / 2
        for (n in 2..total) {
            stepByStep.frame { canvas ->
                renderer.drawWet(canvas, FakeWet(points.copyOf(n * 2), pen, strokeSerial = 1), 40f)
            }
        }

        val a = opaquePixels(oneShot)
        val b = opaquePixels(stepByStep)
        assertTrue("两条路径都得真的画出东西：一次=$a 增量=$b", a > 500 && b > 500)
        val off = kotlin.math.abs(a - b).toFloat() / a
        assertTrue(
            "增量与一次画完的墨量差得太远：一次=$a 增量=$b（相差 %.1f%%）".format(off * 100f),
            off <= 0.05f,
        )
    }

    @Test
    fun `增量是逐点补上去的，不会每帧从第一个点重画`() {
        // 这是 B 那一步的**成本**证据。像素测试只能证明画得对，证明不了"画得省"；
        // 而耗时断言在 JVM 上随 JIT 抖动，不能当门槛。
        // 追加次数不会撒谎：一条 41 点的笔迹，逐点追加应当只补 39 段；
        // 每帧从头重画则会补 1+2+…+39 = 780 段 —— 差了 20 倍。
        val points = wavyLine()
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.BLACK, widthEm = 0.2f)
        val renderer = InkStrokeRenderer()
        val canvasBitmap = bitmap()
        val total = points.size / 2

        for (n in 2..total) {
            canvasBitmap.frame { canvas ->
                renderer.drawWet(canvas, FakeWet(points.copyOf(n * 2), pen, strokeSerial = 1), 40f)
            }
        }

        assertEquals(
            "每个点只该补一次；从头重画会变成 780 次",
            total - 2,
            renderer.wetQuads,
        )
    }

    @Test
    fun `换一笔之后增量路径会重来，不会把上一笔接过来`() {
        val pen = BrushSpec.DEFAULT_PEN.copy(colorArgb = Color.BLACK, widthEm = 0.2f)

        // 同一台渲染器先画一笔（流水号 1），再"换一笔"画到别处（流水号 2）。
        // 第二笔是**新的一帧**：画布已经擦干净，所以位图上只剩湿墨层这一帧画的东西。
        val reused = bitmap()
        val renderer = InkStrokeRenderer()
        reused.frame { canvas ->
            renderer.drawWet(canvas, FakeWet(horizontalLine(50f), pen, strokeSerial = 1), 40f)
        }
        reused.frame { canvas ->
            renderer.drawWet(canvas, FakeWet(horizontalLine(150f), pen, strokeSerial = 2), 40f)
        }

        // 参照：一台全新的渲染器只画第二笔。
        val fresh = bitmap()
        InkStrokeRenderer().drawWet(Canvas(fresh), FakeWet(horizontalLine(150f), pen, strokeSerial = 2), 40f)

        assertEquals(
            "换笔之后必须与新开一台渲染器画得一模一样（多了就是上一笔的残影）",
            opaquePixels(fresh),
            opaquePixels(reused),
        )
        assertEquals("上一笔的位置不该有墨", Color.TRANSPARENT, reused.getPixel(100, 50))
    }

    // ── 叠印笔（荧光笔）───────────────────────────────────────────────────────
    //
    // 这一组守的是 M25 §3 S2 的三条口径里的前两条：**笔宽是行高口径**，
    // 以及**与六套纸色都要看一遍混色**。断言全部落在像素上 ——
    // "荧光笔压在字上还是不是字"这件事，只有像素说了算。

    /** 一条落在行心、笔宽覆盖整行的荧光笔（1.30em × 20px ≈ 26px）。 */
    private fun highlighterStroke() = DrawableStroke(
        strokeId = "s1",
        brush = BrushSpec.DEFAULT_HIGHLIGHTER,
        docPoints = horizontalLine(),
        fontSizePx = 20f,
    )

    @Test
    fun `荧光笔在空白处是半透明的，不是实色块`() {
        val bitmap = bitmap()

        InkStrokeRenderer().draw(Canvas(bitmap), listOf(highlighterStroke()))

        // 纸色不在这个 View 里（它在 Compose 主题那一层），所以这里的背景是透明的。
        // 半透明必须体现在 alpha 上 —— 若渲染层把它画成实色，
        // 合成到纸上就是"涂掉这一段"。
        assertEquals(
            "叠印笔必须保留它自己的半透明度（0x59 ≈ 0.35）",
            BrushSpec.HIGHLIGHTER_ALPHA,
            Color.alpha(bitmap.getPixel(100, 100)),
        )
    }

    @Test
    fun `荧光笔压在字上不改变字（正片叠底，不是把字涂掉）`() {
        val bitmap = bitmap()
        val canvas = Canvas(bitmap)
        // 一段"字"：不透明的黑条，正好压在笔迹线心上。
        canvas.drawRect(
            0f, 90f, size.toFloat(), 110f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK },
        )

        InkStrokeRenderer().draw(canvas, listOf(highlighterStroke()))

        // 正片叠底在黑色上得黑色（0 × 任何色 = 0），于是字一个像素都不变。
        // 换成普通的半透明覆盖（SRC_OVER）这里会变成暗黄 —— 那就是"把字涂了"。
        assertEquals(
            "叠在黑字上必须还是纯黑：荧光笔标的是这一段，不是盖掉这一段",
            Color.BLACK,
            bitmap.getPixel(100, 100),
        )
    }

    @Test
    fun `深色纸上荧光笔改用滤色，标记不会整个消失`() {
        // 同一笔、同一张黑纸，只切换"纸是否偏暗"这一个开关。
        val multiplied = bitmap().also {
            it.eraseColor(Color.BLACK)
            InkStrokeRenderer().draw(Canvas(it), listOf(highlighterStroke()))
        }
        val screened = bitmap().also {
            it.eraseColor(Color.BLACK)
            InkStrokeRenderer().apply { paperIsDark = true }
                .draw(Canvas(it), listOf(highlighterStroke()))
        }

        // 反证先立住：正片叠底在黑纸上必然是黑 —— 这正是"夜间纸上看不见标记"的原因。
        assertEquals(
            "前提：正片叠底在深色纸上会让标记消失（红通道应当≈0）",
            0,
            Color.red(multiplied.getPixel(100, 100)),
        )
        assertTrue(
            "深色纸上必须改用滤色把它提亮，实际=${Color.red(screened.getPixel(100, 100))}",
            Color.red(screened.getPixel(100, 100)) > 30,
        )
    }

    @Test
    fun `叠印笔与普通笔不共用画笔（同一颜色同一宽度也不串味）`() {
        // 混色模式长在 Paint 上，而 Paint 是缓存里共享的对象。触发条件是
        // "两支笔算出来的缓存键一模一样" —— 所以这里刻意让一支普通笔用
        // 与荧光笔**完全相同**的颜色与笔宽。它捡到那支画笔的话就会跟着正片叠底。
        //
        // 用**黑底 + 未声明深色纸**这个组合是有意的：只有在这个组合下
        // "普通笔捡到了正片叠底"才会暴露成"这一笔整个消失"（正片叠底在任何深色上
        // 都得深色）；换到白底上，正片叠底与覆盖看起来几乎一样，测不出这个 bug。
        val renderer = InkStrokeRenderer()
        val sameColor = BrushSpec.DEFAULT_HIGHLIGHTER.colorArgb
        val pen = BrushSpec.DEFAULT_PEN.copy(
            colorArgb = sameColor,
            widthEm = BrushSpec.DEFAULT_HIGHLIGHTER.widthEm,
        )

        val bitmap = bitmap().apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(bitmap)
        renderer.draw(
            canvas,
            listOf(DrawableStroke("h", BrushSpec.DEFAULT_HIGHLIGHTER, horizontalLine(50f), fontSizePx = 20f)),
        )
        renderer.draw(
            canvas,
            listOf(DrawableStroke("p", pen, horizontalLine(150f), fontSizePx = 20f)),
        )

        assertTrue(
            "普通笔是覆盖，不该捡到叠印笔的混合模式（捡到就会被压成黑）",
            Color.red(bitmap.getPixel(100, 150)) > 30,
        )
    }
}
