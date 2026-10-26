package dev.inkleaf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 仿真翻页的**几何**（M33）。
 *
 * ## 为什么在 JVM 上画，而不是在设备上截
 *
 * 整段动画 320 ms，而 `adb exec-out screencap` 一次要 100~200 ms ——
 * 连拍十张也抓不到中间帧（实测抓到的是动画前后两个稳态）。录屏又没有可用的抽帧工具。
 *
 * 于是换成"自己把那一帧画出来"：`drawPageCurl` 是一个纯 `DrawScope` 扩展函数，
 * 不依赖组合、不依赖帧时钟。给它一张纯色位图和一个进度，画到 Bitmap 上，
 * 就能逐像素问它"你把旧页画到哪了"。
 *
 * ## 守的是什么
 *
 * 卷边的本质是**右缘是一条弧**（中间带缩得少、上下两端缩得多），
 * 而不是整页等比缩小 —— 后者看起来是"页面缩到左边"，不是"纸被卷走"。
 * 这三条断言分别守住：旧页确实让出了右边的位置、让出的是弧不是直线、动画结束后不残留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCurlTest {

    private val w = 400
    private val h = 240

    /** 画一张 0xFFFF0000 的"旧页"，返回结果画布。 */
    private fun draw(progress: Float): Bitmap {
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = androidx.compose.ui.graphics.Canvas(canvas),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawPageCurl(source.asImageBitmap(), progress)
        }
        return out
    }

    /** 某一行里旧页（红色）最靠右的列号，没有就是 -1。 */
    private fun rightmostRed(bitmap: Bitmap, y: Int): Int {
        for (x in w - 1 downTo 0) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) > 0 && Color.red(c) > 120 && Color.green(c) < 80) return x
        }
        return -1
    }

    @Test
    fun `动画进行到一半时右半边已经让出来了`() {
        val midway = draw(0.5f)
        val mid = rightmostRed(midway, h / 2)
        assertTrue("中间那一行应当还留着旧页（否则没东西可卷）：$mid", mid > 0)
        assertTrue(
            "右半边必须让给新页，否则读者在动画里看不到下一章；实际右缘在 $mid/$w",
            mid < w * 0.75f,
        )
    }

    @Test
    fun `右缘是弧不是直线：中间的带比上下两端探得更右`() {
        val midway = draw(0.5f)
        val top = rightmostRed(midway, 4)
        val middle = rightmostRed(midway, h / 2)
        val bottom = rightmostRed(midway, h - 5)
        assertTrue("顶部与中间都要有旧页", top >= 0 && middle >= 0)
        assertTrue(
            "中间的带应当比顶部探出更多（这就是「卷」与「等比缩小」的全部差别）：" +
                "top=$top middle=$middle bottom=$bottom",
            middle - top > 6 && middle - bottom > 6,
        )
    }

    @Test
    fun `卷完之后旧页一个像素都不剩`() {
        // 残留会让下一页的第一帧带着上一页的鬼影，而且它是"翻页闪一下"的经典来源。
        val done = draw(1f)
        for (y in listOf(4, h / 2, h - 5)) {
            assertEquals("进度 1.0 时第 $y 行不该还有旧页", -1, rightmostRed(done, y))
        }
    }

    @Test
    fun `起始那一帧还是完整的一页`() {
        // 动画从整页开始、往卷起走。开头就缺一块会让人以为内容丢了。
        val start = draw(0f)
        val mid = rightmostRed(start, h / 2)
        assertTrue("进度 0 时旧页应当铺满整宽，实际右缘 $mid/$w", mid >= w - 2)
    }
}
