package dev.inkleaf.core.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.StrokeAnchor

/**
 * 笔迹缩略图：把一条手写笔记画成一张小图（S3 的 L1）。
 *
 * ## 它回答的是什么问题
 *
 * "我写在哪 / 这几条笔记里哪一条是我要找的那个圈"。笔记清单里每条手写到今天
 * 只是几行字（"笔迹 3 段 · 笔刷 1 种"），认不出来；给它一张图，扫一眼就认得出。
 * 这是手写检索里**成本最低、收益最高**的一层 —— 它不需要任何模型，
 * 只需要"把已经存下来的墨再画一遍"。
 *
 * ## 为什么能不靠排版结果画出来
 *
 * [StrokeAnchor.localPoints] 是**行局部 em 空间**的点（原点 = 行盒左上角，
 * 单位 = em）。em 是个相对单位，所以在 em 空间里"字号恒为 1" ——
 * 这一笔的形状（长宽比、笔锋相对于字的大小）与"这一行当时是多少像素"无关。
 *
 * 于是缩略图可以完全不碰排版：把点等比缩放进一个小方框即可。
 * 笔宽同理 —— [DrawableStroke] 里的 `fontSizePx` 直接给缩放比，
 * 因为 1 em 缩放之后就是 `scale` 个像素，比"假设一个字号再除一次"少一处会错的地方。
 *
 * ## 一处明确的失真（不装）
 *
 * 库里**没有**存"这一段在页面上处于第几行、那一行的 y 是多少"（那要由当前
 * 排版结果算出来，而笔记清单没有排版结果）。所以一笔跨行被切成多段时，
 * 段与段之间的**真实纵向间距无从还原**。
 *
 * 这里的做法是**按存储顺序纵向堆叠**，间距固定 [SEGMENT_GAP_EM]：
 * 段的顺序就是 `InkAnchorFactory.splitIntoRuns` 自上而下切出来的顺序，
 * 所以堆叠与真实版面**同向**（不会上下颠倒）；但间距是估计的。
 * 形状与笔宽是准的 —— 这是缩略图，不是重绘。
 *
 * 若要精确的版面，正确的做法是打开阅读器（那里有排版结果，`InkResolver` 会给出
 * 真实坐标），缩略图只负责"让你认出该点开哪一条"。
 */
object InkThumbnail {

    /** 默认边长。120px 是"看得清一个圈、又不至于让清单变卡"的尺寸。 */
    const val DEFAULT_SIZE_PX = 120

    /**
     * 段与段之间的空白（em）。
     *
     * 取 0.4 而不是真实行距的间隙：行距是**设置项**（1.2～2.0 倍可变），
     * 而缩略图里没有它，编一个数只会让人以为它是真的。
     */
    private const val SEGMENT_GAP_EM = 0.4f

    /** 四周留白占边长的比例。笔迹贴着边画会被圆角裁掉，看着像断了。 */
    private const val PADDING_RATIO = 0.10f

    /** 一段可画的笔迹：点、压力、以及它当时用的那支笔（三者缺一画不出原样）。 */
    private data class Segment(
        val points: FloatArray,
        val pressures: FloatArray,
        val brush: BrushSpec,
    )

    /**
     * 画一张缩略图。
     *
     * @param strokes 该条笔记的全部段（[dev.inkleaf.core.data.AnnotationSnapshot.strokesOf]）。
     * @param brushes 该条笔记自带的笔刷调色板（同上，`brushesOf`）。
     *        必须带进来：笔宽与颜色只存在这里，笔迹本身只存 `brushId`。
     * @param backgroundArgb 底色。传**当前纸色**，否则荧光笔的混色方向会判错
     *        （见 [InkStrokeRenderer.isDarkPaper]）。
     * @return null 表示"没什么可画"（没有段、或全部是橡皮那样的透明笔）。
     *         调用方据此决定要不要显示这一格，而不是显示一个空白框。
     */
    fun render(
        strokes: List<StrokeAnchor>,
        brushes: List<BrushSpec>,
        sizePx: Int = DEFAULT_SIZE_PX,
        backgroundArgb: Int,
    ): Bitmap? {
        if (sizePx <= 0) return null

        // ① 取笔刷。与 InkResolver.brushOf 同一套兜底：名字匹配不上就退回第一支、
        //    再退回默认钢笔。宁可用"可能不对的宽度"画出来，也不要凭空少一笔 ——
        //    少一笔会让用户以为笔记丢了。
        val drawable = ArrayList<Segment>(strokes.size)
        for (stroke in strokes) {
            val brush = brushes.firstOrNull { it.name == stroke.brushId }
                ?: brushes.firstOrNull()
                ?: BrushSpec.DEFAULT_PEN
            if (brush.colorArgb == 0) continue // 橡皮：它在模型里是"擦除"，不画
            if (stroke.localPoints.size < 4) continue // 至少要两个点
            drawable += Segment(stroke.localPoints, stroke.localPressures, brush)
        }
        if (drawable.isEmpty()) return null

        // ② 纵向堆叠（见类注释：真实行距无从还原，这里是估计）。
        val stacked = ArrayList<FloatArray>(drawable.size)
        var cursorY = 0f
        for (segment in drawable) {
            val points = segment.points
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var i = 1
            while (i < points.size) {
                if (points[i] < minY) minY = points[i]
                if (points[i] > maxY) maxY = points[i]
                i += 2
            }
            // 退化成一点的段（完全水平的一笔）撑出一个最小高度，
            // 否则它在堆叠里会被压成一条没有厚度的线、下一格也贴上来。
            val height = (maxY - minY).coerceAtLeast(0.2f)
            val shift = cursorY - minY
            val moved = FloatArray(points.size)
            var k = 0
            while (k < points.size) {
                moved[k] = points[k]
                moved[k + 1] = points[k + 1] + shift
                k += 2
            }
            stacked += moved
            cursorY += height + SEGMENT_GAP_EM
        }

        // ③ 等比缩放进方框。必须**等比**：非等比会把一个圈抻成椭圆，
        //    而"这是不是我写的那个圈"正是缩略图要回答的问题。
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (points in stacked) {
            var i = 0
            while (i < points.size) {
                if (points[i] < minX) minX = points[i]
                if (points[i] > maxX) maxX = points[i]
                if (points[i + 1] < minY) minY = points[i + 1]
                if (points[i + 1] > maxY) maxY = points[i + 1]
                i += 2
            }
        }
        val width = (maxX - minX).coerceAtLeast(0.001f)
        val height = (maxY - minY).coerceAtLeast(0.001f)
        val inner = sizePx * (1f - 2f * PADDING_RATIO)
        val scale = inner / maxOf(width, height)

        // 居中：贴着一边画会让"这一笔在页上的位置"看起来像是有含义的，
        // 而缩略图里它没有任何含义。
        val offsetX = (sizePx - width * scale) / 2f - minX * scale
        val offsetY = (sizePx - height * scale) / 2f - minY * scale

        val drawables = ArrayList<DrawableStroke>(drawable.size)
        for (index in drawable.indices) {
            val (_, pressures, brush) = drawable[index]
            val points = stacked[index]
            val mapped = FloatArray(points.size)
            var k = 0
            while (k < points.size) {
                mapped[k] = points[k] * scale + offsetX
                mapped[k + 1] = points[k + 1] * scale + offsetY
                k += 2
            }
            drawables += DrawableStroke(
                strokeId = "thumb-$index",
                brush = brush,
                docPoints = mapped,
                // 压力序列照原样带过来：笔宽是"均值压力 × widthEm"，
                // 全部给 1 会让压感笔在缩略图里统一变粗，与屏幕上不一样。
                // 长度对不上（旧数据）时退成全 1 —— DrawableStroke 会直接抛，
                // 而"整条笔记因为一段压力数组长度不对就显示不出来"太贵。
                docPressures = if (pressures.size == points.size / 2) {
                    pressures
                } else {
                    FloatArray(points.size / 2) { 1f }
                },
                // 缩放之后 1em = scale 个像素（em 空间里字号就是 1，见类注释）。
                fontSizePx = scale,
            )
        }

        // ④ 画。走的是与屏幕同一个 [InkStrokeRenderer]：
        //    缩略图与页面一旦分成两套绘制代码，"缩略图里的荧光笔和页面上不一样"
        //    就只是时间问题。
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundArgb)
        val renderer = InkStrokeRenderer()
        renderer.paperIsDark = InkStrokeRenderer.isDarkPaper(backgroundArgb)
        renderer.draw(canvas, drawables)
        return bitmap
    }
}
