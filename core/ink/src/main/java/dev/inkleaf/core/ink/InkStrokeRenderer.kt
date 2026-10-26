package dev.inkleaf.core.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.inkleaf.core.model.BrushSpec

/**
 * 一笔已经换算到**文档坐标**、可以直接画的笔迹。
 *
 * 为什么带 [fontSizePx]：笔宽以 **em** 存储（[BrushSpec.widthEm]，见 §5.7 ——
 * px 笔宽在 Android 14+ 的非线性字号缩放下比例会错）。em → px 必须靠**该行的**
 * 字号，而这个字号只有行盒知道。把它随笔迹带下来，渲染层就不需要再回去查行盒，
 * 也就不会再出现"查错了行、笔宽错一档"这种错。
 */
class DrawableStroke(
    val strokeId: String,
    val brush: BrushSpec,
    /** 文档坐标，x0,y0,x1,y1,… */
    val docPoints: FloatArray,
    /** 与点数等长的压力序列，取值 0..1。 */
    val docPressures: FloatArray = FloatArray(docPoints.size / 2) { 1f },
    /** 这一笔所属行的字号（px），用于 em → px 的笔宽换算。 */
    val fontSizePx: Float = DEFAULT_FONT_SIZE_PX,
) {
    init {
        require(docPoints.size % 2 == 0) { "笔迹点必须是 x,y 成对，实际=${docPoints.size}" }
        require(docPressures.size == docPoints.size / 2) { "压力序列长度必须等于点数" }
    }

    /**
     * 形状只算一次。
     *
     * 每个 MOVE 都会让整屏重画一帧，而一帧要重画**页上所有**已落盘的笔迹。
     * 此前每一笔每帧都重新 `StrokeGeometry.polyline` 建一次 Path ——
     * 页上笔迹越多，写一个字要付的钱越多（这正是"写快了就拖"的一半）。
     * 笔迹点在落盘之后不会变，所以形状也不会变：算一次就够了。
     *
     * 仍然走 [StrokeGeometry.polyline]（唯一实现）：缓存的是**结果**，不是算法。
     * 于是屏幕上画的和 PDF 导出写的是同一段代码，不会出现"导出比屏幕细一档"。
     */
    internal fun path(): Path {
        cachedPath?.let { return it }
        return StrokeGeometry.polyline(docPoints, Path()).also { cachedPath = it }
    }

    /** 整笔的粗细（em → px 之后）。同样只算一次：均值要遍历全部压力值。 */
    internal val strokeWidthPx: Float by lazy {
        StrokeGeometry.widthOf(brush, fontSizePx, StrokeGeometry.meanPressure(docPressures))
    }

    /** 点集的包围盒（文档坐标），用于视口裁剪。 */
    internal fun pointBounds(): android.graphics.RectF {
        cachedBounds?.let { return it }
        val r = android.graphics.RectF()
        if (docPoints.size >= 2) {
            var minX = docPoints[0]
            var maxX = minX
            var minY = docPoints[1]
            var maxY = minY
            var i = 2
            while (i < docPoints.size) {
                val x = docPoints[i]
                val y = docPoints[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
            r.set(minX, minY, maxX, maxY)
        }
        cachedBounds = r
        return r
    }

    private var cachedPath: Path? = null
    private var cachedBounds: android.graphics.RectF? = null

    companion object {
        const val DEFAULT_FONT_SIZE_PX = 16f
    }
}

/**
 * 一笔"正在变长"的笔迹给渲染层的最小接口。
 *
 * 为什么是接口而不是 `FloatArray`：每一帧把整条折线拷成数组，是这条路径上
 * 最容易忽略的一笔开销（长笔画时每帧几 KB 分配，而它就在 60Hz 上）。
 * 让渲染层按需取点，就只需要"最后几个点" —— 增量追加的实现正好只用得着它们。
 *
 * 由 [InkLayerController] 实现（正在写的那一笔）。
 */
interface WetStroke {
    /** 流水号：每 `beginStroke` 一次加一。变了就说明换了一笔，路径要重来。 */
    val strokeSerial: Int

    val wetPointCount: Int

    /** 整笔均值压力（0..1）。实现方应当维护累加和，而不是每帧遍历。 */
    val wetMeanPressure: Float

    val wetBrush: BrushSpec

    fun xAt(index: Int): Float

    fun yAt(index: Int): Float
}

/**
 * 笔迹渲染器。
 *
 * 自研而不是用 androidx.ink 的 [androidx.ink.strokes.InProgressStrokesView]，
 * 理由写在 :core:ink 的 build.gradle.kts 顶部（一句话：没有真机验证不了"更快"，
 * 只能确定"更复杂"，而笔迹数据格式与渲染引擎解耦，换回来不需要迁移数据）。
 *
 * 做成有状态的类而不是 `object`：它持有 [Path] 与按（颜色 × 粗细）缓存的 [Paint]。
 * 每帧给每一笔新建 Path/Paint 会造成可见的 GC 抖动，而渲染层就在 60Hz 的路径上。
 * 约束：**只在主线程用**（Android 的 View 绘制本来就在主线程）。
 */
class InkStrokeRenderer {

    private val path = Path()
    private val paints = HashMap<Long, Paint>(8)

    /**
     * 叠印笔（荧光笔）的画笔，**单独一张缓存表**。
     *
     * 不混进 [paints]：那张表的键只有（颜色 × 笔宽）两位，而叠印笔身上还挂着
     * 一个[混合模式]—— 同一个颜色×宽度如果被一支普通笔和一支荧光笔共用，
     * 谁后画谁就把对方的混合模式改掉（Paint 是共享对象），症状是
     * "换了一支笔，先前写的字突然变成半透明"。分表之后两边的键互不干涉，
     * 也就不需要在 64 位的 key 里再抠一个比特位出来。
     */
    private val overprintPaints = HashMap<Long, Paint>(4)

    /**
     * 纸色是否偏暗 —— 决定荧光笔往哪个方向混（M25 §3 S2"与六套纸色都要看一遍混色"）。
     *
     * | 纸色 | 模式 | 为什么 |
     * | --- | --- | --- |
     * | 浅（纸白 / 米黄 / 淡绿） | `MULTIPLY` 正片叠底 | 黄叠白还是黄；叠在黑字上 `0 × 黄 = 0`，字一个像素都不变 —— 这就是"标亮"而不是"涂掉"。 |
     * | 深（夜间 / 纯黑） | `SCREEN` 滤色 | 正片叠底在任何深色上都是深色，标记会**整个消失**；滤色则把暗纸提亮成标记色，而浅色的字只会更亮，照样读得出。 |
     *
     * 普通笔不看这个开关（它们始终是"覆盖"，[Paint] 不设 [blendMode]）。
     *
     * 由 `DocumentInkView.setPaper` 设置 —— 纸色住在 Compose 主题里，渲染层拿不到，
     * 只能由外面告诉它（这是类注释那条"颜色事实只有一份"纪律的唯一例外口子，
     * 而且传进来的是**结论**（暗 / 亮），不是一份新的颜色事实）。
     */
    var paperIsDark: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // 混色方向变了，缓存里的叠印画笔全部作废：混合模式长在 Paint 上，改不掉。
            overprintPaints.clear()
        }

    // ── 正在书写的那一笔（增量路径，见 [drawWet]）────────────────────────────
    private val wetPath = Path()
    private val wetTail = Path()
    private var wetSerial: Int = -1
    private var wetEmitted: Int = 0
    private var wetPointCount: Int = 0
    private var wetStarted: Boolean = false

    /**
     * 当前这一笔往定型路径里累计追加了几段二次贝塞尔。
     *
     * 只给测试看 —— 它是"增量真的是增量"的**硬证据**：逐点追一条 41 点的笔迹，
     * 这个数应当是 39（每个点补一次），而不是 780（每帧从第一个点重画一遍）。
     *
     * 用次数而不是耗时来断言，是因为耗时随机器、JIT、GC 抖，
     * 而"漏补"与"重画"在次数上是 20 倍的差距，不会撒谎。
     */
    internal var wetQuads: Int = 0
        private set

    /**
     * 下划线的专用画笔。
     *
     * 单开一支而不是走 [paints] 缓存：下划线的颜色/粗细组合与笔迹**不共享**
     * （同色同宽在两边出现的概率不低，但语义不同 —— 一支是"写字"，一支是"画装饰线"，
     * 共用会让"改了下划线样式，笔画变粗"这种串味 bug 变得可能）。
     */
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 画一批已锚定、已换算到文档坐标的笔迹。
     *
     * 调用方必须已经把画布变换设成"文档坐标 → 视图坐标"（`DocumentInkView`
     * 的那一次 `translate`）。本方法不做任何坐标变换 —— 这正是笔迹不会与文字错位的原因。
     *
     * [visibleTop] / [visibleBottom] 是**文档坐标**里的可见范围：完全在范围外的笔迹
     * 直接跳过。这不只是省像素 —— 画布的 clip 会省掉填充，但**不会**省掉
     * `drawPath` 的调用本身，滚动模式下那是每帧几十上百次。
     */
    fun draw(
        canvas: Canvas,
        strokes: List<DrawableStroke>,
        visibleTop: Float = Float.NEGATIVE_INFINITY,
        visibleBottom: Float = Float.POSITIVE_INFINITY,
    ) {
        for (stroke in strokes) {
            if (stroke.brush.colorArgb == 0) continue
            val width = stroke.strokeWidthPx
            if (visibleTop > Float.NEGATIVE_INFINITY || visibleBottom < Float.POSITIVE_INFINITY) {
                val b = stroke.pointBounds()
                val half = width / 2f + 1f
                if (b.bottom + half < visibleTop || b.top - half > visibleBottom) continue
            }
            canvas.drawPath(stroke.path(), paintFor(stroke.brush, width))
        }
    }

    /**
     * 画"正在书写"的那一笔：**只追加新增的那一小段**。
     *
     * 此前每帧都从第一个点重画一遍整条折线（O(n)/帧、整笔 O(n²)），
     * 写得越长越慢 —— 用户看到的就是"线追不上笔，然后突然补齐"。
     *
     * 现在的形状是：`wetPath` 里放已经定型的二次贝塞尔段，`wetTail` 只放
     * "最后一个中点到最新一点"这一小段直线。每来一个新点只多算一个 `quadTo`
     * 和一条两点的线，与这一笔已经写了多长无关。
     *
     * 两条路径之间用圆头笔帽接上（`Paint.Cap.ROUND`），接缝重叠、不会断开。
     *
     * 与 [draw] 一样，调用方必须先设好画布变换。
     */
    fun drawWet(canvas: Canvas, stroke: WetStroke, fontSizePx: Float) {
        val count = stroke.wetPointCount
        if (count < 2 || stroke.wetBrush.colorArgb == 0) return
        val paint = paintFor(
            stroke.wetBrush,
            StrokeGeometry.widthOf(stroke.wetBrush, fontSizePx, stroke.wetMeanPressure),
        )

        if (stroke.strokeSerial != wetSerial || count < wetPointCount) {
            // 换了一笔（或点数变少：撤销/重来）。重置，但**不重建**对象。
            wetPath.reset()
            wetTail.reset()
            wetSerial = stroke.strokeSerial
            wetEmitted = 0
            wetStarted = false
            wetQuads = 0
        }
        wetPointCount = count

        if (count == 2) {
            // 只有两个点：一条直线，没有贝塞尔可言。
            canvas.drawLine(stroke.xAt(0), stroke.yAt(0), stroke.xAt(1), stroke.yAt(1), paint)
            return
        }

        if (!wetStarted) {
            wetPath.moveTo(stroke.xAt(0), stroke.yAt(0))
            wetStarted = true
        }

        // 补齐"已经能定型"的段：quad i 需要 p[i] 与 p[i+1]，所以 i 最多到 count-2。
        var i = wetEmitted + 1
        val lastQuad = count - 2
        while (i <= lastQuad) {
            val cx = stroke.xAt(i)
            val cy = stroke.yAt(i)
            val mx = (cx + stroke.xAt(i + 1)) / 2f
            val my = (cy + stroke.yAt(i + 1)) / 2f
            wetPath.quadTo(cx, cy, mx, my)
            wetEmitted = i
            wetQuads++
            i++
        }

        // 尾巴：最后一个中点 → 最新一点。
        wetTail.reset()
        val tailFromX = (stroke.xAt(count - 2) + stroke.xAt(count - 1)) / 2f
        val tailFromY = (stroke.yAt(count - 2) + stroke.yAt(count - 1)) / 2f
        wetTail.moveTo(tailFromX, tailFromY)
        wetTail.lineTo(stroke.xAt(count - 1), stroke.yAt(count - 1))

        canvas.drawPath(wetPath, paint)
        canvas.drawPath(wetTail, paint)
    }

    /**
     * 画"已抬笔、干墨还没到场"的若干笔（[PendingInk]）。
     *
     * 它们已经不会再变长，所以形状与粗细都只算一次，跟已落盘的笔迹同一条路径。
     */
    fun drawPending(canvas: Canvas, strokes: List<PendingInk>, fontSizePx: Float) {
        for (stroke in strokes) {
            if (stroke.brush.colorArgb == 0 || stroke.pointCount < 2) continue
            canvas.drawPath(
                stroke.path(),
                paintFor(stroke.brush, StrokeGeometry.widthOf(stroke.brush, fontSizePx, stroke.meanPressure)),
            )
        }
    }

    /**
     * 画下划线 / 波浪线。
     *
     * 与笔迹共用同一个**画布变换**（调用方已经把画布设成文档坐标），
     * 但不用 [Paint.Style.STROKE] 的折线缓存：下划线的粗细是**逐行**变的
     * （em 宽 × 该行字号），而笔迹的 Paint 缓存按"颜色 + 像素宽度"分键，
     * 两者混用会让缓存里多出一堆只出现一次的键。
     *
     * 波浪线用二次贝塞尔而不是 `sin` 采样：一段正弦在放大后会看出折角，
     * 而二次贝塞尔在两个极值点之间天然平滑，代码也只有几行。
     * 波长取 0.5em —— 比它短会密到看不清"波浪"，比它长（1em 以上）
     * 在中文行宽下只剩一两个波峰，看起来像一条歪掉的直线。
     */
    fun drawUnderlines(canvas: Canvas, bands: List<DrawableUnderline>) {
        if (bands.isEmpty()) return
        for (band in bands) {
            if (band.colorArgb == 0 || band.right - band.left < 1f) continue

            // 位置与粗细都走 [StrokeGeometry]：PDF 导出要把同一条线写成一个贴基线的
            // 细高亮带，两处必须算出同一个 y —— 差一两个 px 在中文行高下就压到下一行了。
            val (y, thickness) = StrokeGeometry.underlineOf(band)
            underlinePaint.apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = band.colorArgb
                strokeWidth = thickness
            }

            if (!band.style.wavy) {
                canvas.drawLine(band.left, y, band.right, y, underlinePaint)
                continue
            }

            val amplitude = (thickness * 1.6f).coerceAtLeast(1.2f)
            val wavelength = (band.fontSizePx * 0.5f).coerceAtLeast(6f)
            val half = wavelength / 2f
            path.reset()
            path.moveTo(band.left, y)
            var x = band.left
            var up = true
            while (x < band.right) {
                val nextX = (x + half).coerceAtMost(band.right)
                // 控制点放在波峰/波谷：二次贝塞尔的中点会落在控制点与弦的中点之间，
                // 因此控制点要放**两倍**振幅，画出来的波峰才恰好是 amplitude。
                val cy = if (up) y - amplitude * 2f else y + amplitude * 2f
                path.quadTo((x + nextX) / 2f, cy, nextX, y)
                x = nextX
                up = !up
            }
            canvas.drawPath(path, underlinePaint)
        }
    }

    /**
     * 按（颜色、**已经算好的笔宽**）取画笔。
     *
     * 颜色含 alpha 位（[BrushSpec.colorArgb] 是一个完整的 ARGB），所以"换透明度"
     * 本来就会换一支画笔 —— 早先注释里"透明度不进 key"那句是错的，这次一并改掉。
     *
     * 缓存没有上限，但 key 的空间由"用户有几个笔刷 × 几档字号"决定，实际只有几十个。
     *
     * 入参是算好的宽度而不是（字号 + 压力）：那两个值每帧都要重算一遍，
     * 而按笔宽查表是 O(1)。笔宽的唯一来源仍然是 [StrokeGeometry.widthOf]。
     */
    private fun paintFor(brush: BrushSpec, width: Float): Paint {
        if (brush.overprint) return overprintPaintFor(brush, width)
        val key = (brush.colorArgb.toLong() shl 32) or (width.toRawBits().toLong() and 0xFFFFFFFFL)
        paints[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = brush.colorArgb
            strokeWidth = width
        }
        paints[key] = paint
        return paint
    }

    /**
     * 叠印笔（荧光笔）的画笔。与 [paintFor] 只差一个 [blendMode]，见 [overprintPaints]。
     *
     * 混色方向取 [paperIsDark]（换纸时缓存已在 setter 里清空，这里不需要再判断）。
     */
    private fun overprintPaintFor(brush: BrushSpec, width: Float): Paint {
        val key = (brush.colorArgb.toLong() shl 32) or (width.toRawBits().toLong() and 0xFFFFFFFFL)
        overprintPaints[key]?.let { return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = brush.colorArgb
            strokeWidth = width
            blendMode = if (paperIsDark) BlendMode.SCREEN else BlendMode.MULTIPLY
        }
        overprintPaints[key] = paint
        return paint
    }

    companion object {
        /** "这张纸算深色吗"的亮度阈值（相对亮度，0..1），见 [isDarkPaper]。 */
        const val DARK_PAPER_LUMA = 0.5f

        /**
         * 这张纸算深色吗 —— 决定叠印笔（荧光笔）往哪个方向混色。
         *
         * 判定放在这里而不是各个 View 里：这是**唯一一份**"什么算深色纸"的事实。
         * 判成两份的话，屏幕与导出的 PNG / 笔记缩略图会在夜间纸上画出不同的荧光笔，
         * 而那种不一致最难查（两边都画得出来，只是不一样）。
         *
         * 阈值取 0.5 是因为六套纸色在这个量上分得很开，根本不存在临界的情况：
         * 纸白 `#FBF9F4`（0.96）、米黄 `#F3E8D5`（0.89）、淡绿 `#E4EDE2`（0.89）
         * 都在 0.85 以上，夜间 `#14161A`（0.008）与纯黑（0）都在 0.02 以下。
         * 中间那一段（0.02 ~ 0.85）本来就没有纸色落在里面 —— 阈值取 0.2 或 0.8
         * 结果完全一样，取正中值是为了让"看起来应该算深色"的直觉与代码一致。
         *
         * 用亮度而不是"是不是黑色"：六套纸色里有米黄与淡绿这样的**有彩色**，
         * 只看某个通道会把它们判错，判错一次就是"荧光笔在米黄纸上消失"。
         */
        fun isDarkPaper(paperArgb: Int): Boolean {
            val r = ((paperArgb ushr 16) and 0xFF) / 255f
            val g = ((paperArgb ushr 8) and 0xFF) / 255f
            val b = (paperArgb and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b < DARK_PAPER_LUMA
        }
    }
}
