package dev.inkleaf.core.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 正文里图片的占位字符（U+FFFC OBJECT REPLACEMENT CHARACTER）。
 *
 * 为什么用**一个字符**表示一张图，而不是把图片信息存在文本之外：
 * 全工程的文字坐标就是字符下标（块路径、块内偏移、锚点全建在它上面）。
 * 图片若"不占字符"，它前后文字的偏移就会与排版里的偏移错开一位，
 * 锚点、选中、搜索高亮全部要各自算一次偏移换算 —— 而偏移换算就是漂移的来源。
 * 占一个字符之后，图片在文本里有确定的位置，其它一切照旧。
 *
 * 选 U+FFFC 而不是随便某个可见字符：Unicode 对它的定义就是"这里是一个内嵌对象"，
 * 各路字体都不会给它字形，`isWhitespace` 为 false（不会被空白折叠吃掉）。
 */
const val IMAGE_PLACEHOLDER: Char = '\uFFFC'

/** 一行是否是图片占位（去掉首尾空白后只剩下占位字符）。 */
fun isImagePlaceholderLine(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.length == 1 && trimmed[0] == IMAGE_PLACEHOLDER
}

/**
 * 正文图片的取图接口。
 *
 * `:core:document` 不认识 EPUB 之外的东西，也不该认识"文件在哪"——
 * 排版层只要问一句"这个 href 的图，按这个宽度能给我一张吗"，
 * 拿不到就返回 null（版面留白，不影响读字）。
 *
 * 为什么返回 [Drawable] 而不是 [Bitmap]：它要直接交给 `ImageSpan`，
 * 而 ImageSpan 只认 Drawable。中间少一层包装，少一处"尺寸被密度缩放"的机会。
 */
interface DocumentImageProvider {
    /**
     * @param href 书内路径（ZIP 条目名，已归一化）。
     * @param maxWidthPx 可用的最大宽度（正文栏宽）。
     * @param maxHeightPx 可用的最大高度（一页的正文高度）。
     * @return 缩放后的图；取不到（条目不存在、解码失败、超出内存预算）返回 null。
     */
    fun drawable(href: String, maxWidthPx: Int, maxHeightPx: Int): Drawable?
}

/**
 * EPUB 内嵌图片的解码器。
 *
 * 两条硬约束：
 *
 *  1. **按目标尺寸解码，不解原图**。书里一张 3000×4000 的插图原样解出来是 48 MB，
 *     几页下来就 OOM。先读尺寸算 `inSampleSize`，再缩到栏宽 —— 屏幕上本来也只画这么大。
 *  2. **总内存有预算**。没有预算的解码器在"图多的书"上必然 OOM，
 *     而 OOM 不会挑时候。超预算的图退回 null：正文照常读，只是那一格空着。
 *
 * 缓存不回收位图：布局里的 `ImageSpan` 正引用着它们，回收会让下一次绘制崩在
 * "trying to use a recycled bitmap"上。缓存只负责"别重复解码"，释放交给 GC。
 */
class EpubImageProvider(
    /** 条目名 → 压缩后的原始字节。只放正文真正引用到的图（见 `EpubExtractor`）。 */
    private val entries: Map<String, ByteArray>,
    /** 解码后位图的缓存上限（KB）。默认 48 MB，约 8~40 张插图。 */
    private val budgetKb: Int = DEFAULT_BUDGET_KB,
) : DocumentImageProvider {

    private val cache = object : LruCache<String, Drawable>(budgetKb) {
        override fun sizeOf(key: String, value: Drawable): Int =
            if (value is FixedSizeDrawable) value.byteCount / 1024 else 1

        // 位图由布局里的 span 持有，被挤出缓存不等于没人用。
        // 这里刻意什么都不做：回收它就是崩溃。
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Drawable, newValue: Drawable?) = Unit
    }

    override fun drawable(href: String, maxWidthPx: Int, maxHeightPx: Int): Drawable? {
        if (maxWidthPx <= 0 || maxHeightPx <= 0) return null
        val key = "$href|$maxWidthPx|$maxHeightPx"
        cache.get(key)?.let { return it }

        val bytes = entries[href] ?: return null
        val bitmap = decodeScaled(bytes, maxWidthPx, maxHeightPx) ?: return null
        // 超预算的图不缓存、不返回：宁可留白，也不要在第 30 张图上 OOM。
        val costKb = (bitmap.byteCount / 1024).coerceAtLeast(1)
        if (cache.size() + costKb > budgetKb) {
            bitmap.recycle()
            return null
        }
        val drawable = FixedSizeDrawable(bitmap)
        cache.put(key, drawable)
        return drawable
    }

    private fun decodeScaled(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 先按 2 的幂降采样到"不小于目标尺寸"，再精确缩放。
        // 一步到位地只做降采样会得到一张偏小的图（看起来糊），
        // 只做精确缩放则要在内存里先摊开原图（该 OOM 的还是 OOM）。
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx &&
            bounds.outHeight / (sample * 2) >= maxHeightPx
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val raw = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?: return null
        if (raw.width <= 0 || raw.height <= 0) {
            raw.recycle()
            return null
        }

        val scale = min(
            1f,
            min(maxWidthPx.toFloat() / raw.width, maxHeightPx.toFloat() / raw.height),
        )
        if (scale >= 0.999f) return raw
        val width = (raw.width * scale).roundToInt().coerceAtLeast(1)
        val height = (raw.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = runCatching { Bitmap.createScaledBitmap(raw, width, height, true) }.getOrNull()
        if (scaled == null || scaled === raw) return raw
        raw.recycle()
        return scaled
    }

    companion object {
        const val DEFAULT_BUDGET_KB = 48 * 1024
    }
}

/**
 * 尺寸恒等于位图像素尺寸的 Drawable。
 *
 * 为什么不用 `BitmapDrawable`：它会按屏幕密度换算 intrinsic size，
 * 同一张图在 2.0 与 2.75 密度的机器上占的版面宽度不同，
 * 而我们的宽度上限是**排版算出来的像素**，与密度无关。
 * 这里只要一个老老实实"多少像素就多宽"的壳。
 */
private class FixedSizeDrawable(private val bitmap: Bitmap) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    val byteCount: Int get() = bitmap.byteCount

    init {
        // **必须**显式给 bounds。`ImageSpan` 在测量与绘制时读的是 `getBounds()`，
        // 不是 `getIntrinsicWidth()` —— 不设的话它量到 (0,0,0,0)，
        // 于是那一行的高度是 0，图既不占位也画不出来（M31 实测踩到）。
        setBounds(0, 0, bitmap.width, bitmap.height)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Drawable 的旧接口，带 alpha 的版本在上面")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height
}
