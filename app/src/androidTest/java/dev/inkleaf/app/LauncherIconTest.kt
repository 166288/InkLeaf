package dev.inkleaf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 启动图标：把图层真的渲染成 PNG，而不是"看起来资源文件在"。
 *
 * 这一版图标换成了位图定稿（`@mipmap/ic_launcher_art`），所以断言不再钉
 * 「哪一条路径在第几 dp」——那种断言一改设计就得整篇重写。这里只守三件
 * 与设计无关、但坏了就一定难看的事：
 *
 * 1. Manifest 指向的图标与三层资源**真的能加载**；
 * 2. 图案（墨色书体 + 绿叶）**落在可见圆内**，圆形遮罩下不会被削角；
 * 3. **书与叶两块都在**，且颜色能被区分（不是糊成一块）。
 *
 * 旧的「叶脉亮度」「书页与叶之间留缝」两条是给手绘 VectorDrawable 定坐标的，
 * 换位图后已无意义，随设计一并移除；保留的是"渲染出来给人看"。
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(id: Int, px: Int): Bitmap {
        val d: Drawable = context.getDrawable(id)!!
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun isGreen(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return g > r + 8 && g > b + 8 && g > 80
    }

    private fun isInk(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r + g + b) / 3 < 120
    }

    @Test
    fun `图标三层都能加载`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val icon = context.packageManager.getApplicationIcon(info)
        assertNotNull("应用图标解析不出来（Manifest 里缺 android:icon？）", icon)

        listOf(
            R.mipmap.ic_launcher_art,
            R.drawable.ic_launcher_foreground,
            R.mipmap.ic_launcher_monochrome,
        ).forEach { id ->
            val d = context.getDrawable(id)
            assertNotNull("图层 $id 加载失败", d)
            assertTrue("图层 $id 没有尺寸", d!!.intrinsicWidth > 0)
        }
    }

    @Test
    fun `图案落在可见圆内`() {
        // 可见圆半径约 36dp（108 画布、72dp 可见）。只量墨色/绿色像素，
        // 纸底不算——否则一张满幅纸底会被判成越界。
        val px = 432
        val bmp = render(R.mipmap.ic_launcher_art, px)
        val pixels = IntArray(px * px)
        bmp.getPixels(pixels, 0, px, 0, 0, px, px)

        var worst = 0f
        var count = 0
        for (y in 0 until px) {
            for (x in 0 until px) {
                val c = pixels[y * px + x]
                if (!isInk(c) && !isGreen(c)) continue
                count++
                val d = kotlin.math.hypot((x + 0.5f) - px / 2f, (y + 0.5f) - px / 2f)
                if (d > worst) worst = d
            }
        }
        assertTrue("图案层是空的", count > 0)

        val worstDp = worst * 108f / px
        println("INKLEAF_ICON_R worst=%.2f dp pixels=%d".format(worstDp, count))
        assertTrue(
            "图案最远处距中心 %.1f dp，超出可见半径 36 dp —— 圆形遮罩下会被裁".format(worstDp),
            worstDp <= 36.5f,
        )
    }

    @Test
    fun `书与叶都在且颜色分得开`() {
        val px = 432
        val bmp = render(R.mipmap.ic_launcher_art, px)
        val pixels = IntArray(px * px)
        bmp.getPixels(pixels, 0, px, 0, 0, px, px)

        var ink = 0
        var green = 0
        for (c in pixels) {
            if (isGreen(c)) green++ else if (isInk(c)) ink++
        }
        println("INKLEAF_ICON_PARTS ink=$ink green=$green")
        assertTrue("没找到墨色书体（$ink px）—— 图是不是没换上来？", ink > px * px / 20)
        assertTrue("没找到绿色叶片（$green px）—— 叶子丢了？", green > px * px / 200)
    }

    @Test
    fun `渲染出来供人看一眼`() {
        val out = File(context.getExternalFilesDir(null), "launcher-icon")
        out.mkdirs()
        listOf(512, 192, 72, 48).forEach { size ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            listOf(R.mipmap.ic_launcher_art, R.drawable.ic_launcher_foreground).forEach { id ->
                val d: Drawable = context.getDrawable(id)!!
                d.setBounds(0, 0, size, size)
                d.draw(canvas)
            }
            File(out, "icon-$size.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        println("INKLEAF_ICON=${out.absolutePath}")
    }
}
