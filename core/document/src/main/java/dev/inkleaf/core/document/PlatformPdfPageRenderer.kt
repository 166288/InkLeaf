package dev.inkleaf.core.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * 页面位图渲染器 —— 阅读界面用的那一半（[PlatformPdfPageSource] 是导入用的那一半）。
 *
 * 为什么不复用页源：导入只需要**尺寸与文本层**，一页位图都不该在导入时渲染
 * （一本 500 页的 PDF 会把内存与时间都耗在用户还没打开的页上）。反过来，
 * 阅读界面只需要渲染**当前这一页**，不需要重新解析文本层。两件事的生命周期也不同：
 * 页源活一次 `inspect`，渲染器活一次阅读界面。
 *
 * 渲染尺寸由调用方按"视口能放多大"算好（[PdfPageFit]），本类只负责把页画满
 * 给定的位图。**不在这里缩放**：缩放是视图的职责，渲染器多一套缩放就会与
 * 行盒的坐标系分家（`PdfDocumentSession` 的类注释：坐标系的定义权只有一处）。
 *
 * `PdfRenderer` 不是线程安全的：所有调用都加锁。渲染本身在 IO 调度器上跑，
 * 因此锁不会卡住主线程，只是防止两页同时渲染时互相踩。
 */
class PlatformPdfPageRenderer(private val file: File) : Closeable {

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer: PdfRenderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

    /**
     * 渲染第 [index] 页到一张 `widthPx × heightPx` 的位图。
     *
     * 背景先填白：`PdfRenderer` 不会替我们铺纸，透明底在深色主题下会变成
     * "黑底黑字"（页面上本来有白底的 PDF 不受影响，没有白底的会）。
     *
     * @throws IllegalArgumentException 页号越界（调用方的错误，不该静默给一张空图）。
     */
    @Synchronized
    fun render(index: Int, widthPx: Int, heightPx: Int): Bitmap {
        require(index in 0 until pageCount) { "页号越界：$index，共 $pageCount 页" }
        require(widthPx > 0 && heightPx > 0) { "位图尺寸必须为正：${widthPx}x$heightPx" }

        renderer.openPage(index).use { page ->
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                // 两个方向分别缩放：调用方已经按等比算好尺寸，这里再"等比"一次
                // 只会因为取整误差在页脚留下一条一像素的白边。
                setScale(
                    widthPx.toFloat() / page.width.coerceAtLeast(1),
                    heightPx.toFloat() / page.height.coerceAtLeast(1),
                )
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /**
     * 关闭。
     *
     * 与 [render] 同一把锁：`PdfRenderer.close()` 若与一次渲染并发，原生层可能
     * 直接崩（use-after-free）。退出界面时最多等一页的渲染时间（几十毫秒）。
     */
    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * 页面在视口里的摆放 —— 纯函数，因此"缩放算错了"是一条 JVM 单测，而不是靠眼睛看。
 *
 * 规则只有一条：**等比缩放到视口内**（宽高都装得下），不裁切、不拉伸。
 * 竖屏手机上 A4 页按宽度定；横屏或宽页按高度定。PDF 是固定版式，
 * 页面比例是文件的事实，视图没有资格改它。
 */
object PdfPageFit {

    /** 退化输入的兜底比例。小于它等于"看不见"，而看不见与没渲染在用户眼里是同一件事。 */
    const val MIN_SCALE = 0.01f

    fun scale(
        availableWidthPx: Float,
        availableHeightPx: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
    ): Float {
        if (availableWidthPx <= 0f || availableHeightPx <= 0f) return 1f
        if (pageWidthPx <= 0f || pageHeightPx <= 0f) return 1f
        val byWidth = availableWidthPx / pageWidthPx
        val byHeight = availableHeightPx / pageHeightPx
        return minOf(byWidth, byHeight).coerceAtLeast(MIN_SCALE)
    }

    fun widthInView(pageWidthPx: Float, scale: Float): Float =
        (pageWidthPx * scale).coerceAtLeast(1f)

    fun heightInView(pageHeightPx: Float, scale: Float): Float =
        (pageHeightPx * scale).coerceAtLeast(1f)

    /** 渲染位图的像素尺寸：取整之后至少 1 像素。 */
    fun bitmapWidth(pageWidthPx: Float, scale: Float): Int =
        (pageWidthPx * scale).roundToInt().coerceAtLeast(1)

    fun bitmapHeight(pageHeightPx: Float, scale: Float): Int =
        (pageHeightPx * scale).roundToInt().coerceAtLeast(1)
}
