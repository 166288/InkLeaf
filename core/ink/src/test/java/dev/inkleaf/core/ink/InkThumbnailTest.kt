package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Color
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 笔迹缩略图的测试（S3 的 L1）。
 *
 * 与 `InkStrokeRendererTest` 一样走 Robolectric 的 `NATIVE` 图形模式：
 * 不真的栅格化，"画出来了没有""有没有越界"这类断言永远为真。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkThumbnailTest {

    private fun anchor(
        points: FloatArray,
        brushId: String = BrushSpec.DEFAULT_PEN.name,
        id: String = "s1",
    ) = StrokeAnchor(
        strokeId = id,
        anchor = Selector.Structural("blk[0]", 0, 4, 1f),
        semantics = InkReflowSemantics.FOLLOWER,
        brushId = brushId,
        localPoints = points,
        localPressures = FloatArray(points.size / 2) { 1f },
    )

    /** 一条落在 em 空间里的横线（1em 见方的行盒里，这就是"划过一行"）。 */
    private fun line(vararg xy: Float) = floatArrayOf(*xy)

    /** 与背景不同的像素数。缩略图里"有墨"就是"有像素不等于底色"。 */
    private fun Bitmap.inkPixels(background: Int): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) != background) count++
            }
        }
        return count
    }

    @Test
    fun `没有可画的段就不给图（调用方据此不显示这一格）`() {
        assertNull("没有段", InkThumbnail.render(emptyList(), emptyList(), 120, Color.WHITE))
        assertNull(
            "边长 0 不该去建位图",
            InkThumbnail.render(listOf(anchor(line(0f, 0f, 1f, 0f))), emptyList(), 0, Color.WHITE),
        )
    }

    @Test
    fun `橡皮那种全透明的笔不会被画出来`() {
        // 橡皮在模型里是"擦除"（colorArgb == 0）。把它画进缩略图，
        // 用户看到的是一张空的方框，然后以为这条笔记是空的。
        val strokes = listOf(anchor(line(0f, 0f, 1f, 0.5f), brushId = BrushSpec.DEFAULT_ERASER.name))
        assertNull(
            InkThumbnail.render(strokes, listOf(BrushSpec.DEFAULT_ERASER), 120, Color.WHITE),
        )
    }

    @Test
    fun `一笔会真的画进小图里`() {
        val bitmap = InkThumbnail.render(
            strokes = listOf(anchor(line(0.2f, 0.5f, 3.8f, 0.5f))),
            brushes = listOf(BrushSpec.DEFAULT_PEN),
            sizePx = 120,
            backgroundArgb = Color.WHITE,
        )!!

        assertEquals(120, bitmap.width)
        assertEquals(120, bitmap.height)
        assertTrue("缩略图里必须有墨", bitmap.inkPixels(Color.WHITE) > 100)
    }

    @Test
    fun `笔迹不会画出留白之外（贴边的墨会被圆角裁掉，看着像断了）`() {
        val bitmap = InkThumbnail.render(
            strokes = listOf(anchor(line(0.2f, 0.5f, 3.8f, 0.9f))),
            brushes = listOf(BrushSpec.DEFAULT_PEN.copy(widthEm = 0.12f)),
            sizePx = 120,
            backgroundArgb = Color.WHITE,
        )!!

        // 四角必须还是纸色：留白是 10%，即最外 12px 那一圈里不该有墨。
        for (corner in listOf(0 to 0, 119 to 0, 0 to 119, 119 to 119)) {
            assertEquals(
                "角落 (${corner.first},${corner.second}) 不该有墨",
                Color.WHITE,
                bitmap.getPixel(corner.first, corner.second),
            )
        }
    }

    @Test
    fun `缩略图是等比的，不会把圈抻成椭圆`() {
        // 一个 5:1 的方框。缩略图要回答"这是不是我写的那个形状"，
        // 非等比缩放会让这个问题的答案变形。
        val box = line(0f, 0f, 5f, 0f, 5f, 1f, 0f, 1f, 0f, 0f)
        val bitmap = InkThumbnail.render(
            strokes = listOf(anchor(box)),
            brushes = listOf(BrushSpec.DEFAULT_PEN.copy(widthEm = 0.02f)),
            sizePx = 200,
            backgroundArgb = Color.WHITE,
        )!!

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == Color.WHITE) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val drawn = (maxX - minX + 1).toFloat() / (maxY - minY + 1)
        assertTrue(
            "长宽比应当保持 5:1，实际 %.2f".format(drawn),
            kotlin.math.abs(drawn - 5f) / 5f < 0.15f,
        )
    }

    @Test
    fun `深色纸上的荧光笔在缩略图里也看得见`() {
        // 深色纸必须走滤色：正片叠底在黑纸上无论什么颜色都是黑，
        // 缩略图会变成一张纯黑的小方块 —— 用户只会以为这条笔记是空的。
        val strokes = listOf(
            anchor(line(0.2f, 0.5f, 3.8f, 0.6f), brushId = BrushSpec.DEFAULT_HIGHLIGHTER.name),
        )
        val bitmap = InkThumbnail.render(
            strokes = strokes,
            brushes = listOf(BrushSpec.DEFAULT_HIGHLIGHTER),
            sizePx = 120,
            backgroundArgb = Color.BLACK,
        )!!

        assertTrue(
            "夜间纸上荧光笔必须看得见（混色方向由纸色决定）",
            bitmap.inkPixels(Color.BLACK) > 100,
        )
    }

    @Test
    fun `浅色纸上的荧光笔在缩略图里是半透明的黄，不是实色块`() {
        val bitmap = InkThumbnail.render(
            strokes = listOf(
                anchor(line(0.2f, 0.5f, 3.8f, 0.6f), brushId = BrushSpec.DEFAULT_HIGHLIGHTER.name),
            ),
            brushes = listOf(BrushSpec.DEFAULT_HIGHLIGHTER),
            sizePx = 120,
            backgroundArgb = Color.WHITE,
        )!!

        // 正片叠底 × 半透明：黄叠在白纸上还是黄，但**不是**纯黄（那是实色块）。
        val mid = bitmap.getPixel(60, 60)
        assertTrue("应当偏黄：红=${Color.red(mid)} 绿=${Color.green(mid)} 蓝=${Color.blue(mid)}",
            Color.red(mid) > 200 && Color.green(mid) > 180 && Color.blue(mid) < 200)
    }

    @Test
    fun `跨行切成多段的一笔会被纵向堆叠，而不是叠在一起`() {
        // 库里没有"这一段的 y 是多少"，所以间距是估计的（见 InkThumbnail 的类注释）。
        // 但**必须**堆叠：全部叠在同一处会得到一个糊掉的团，
        // 而一笔跨行是最常见的手写之一（圈一段话）。
        val single = InkThumbnail.render(
            strokes = listOf(anchor(line(0.2f, 0.5f, 3.8f, 0.5f), id = "a")),
            brushes = listOf(BrushSpec.DEFAULT_PEN),
            sizePx = 120,
            backgroundArgb = Color.WHITE,
        )!!
        val twoSegments = InkThumbnail.render(
            strokes = listOf(
                anchor(line(0.2f, 0.5f, 3.8f, 0.5f), id = "a"),
                anchor(line(0.2f, 0.5f, 3.8f, 0.5f), id = "a#1"),
            ),
            brushes = listOf(BrushSpec.DEFAULT_PEN),
            sizePx = 120,
            backgroundArgb = Color.WHITE,
        )!!

        assertTrue(
            "两段必须占更多地方（叠在一起就只是同一个团的墨量）",
            twoSegments.inkPixels(Color.WHITE) > single.inkPixels(Color.WHITE) * 1.5f,
        )
    }
}
