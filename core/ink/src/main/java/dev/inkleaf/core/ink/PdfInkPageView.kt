package dev.inkleaf.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.inkleaf.core.model.LineBox
import kotlin.math.hypot

/**
 * 固定版式（PDF）的一页 + 这一页上的墨迹，**同一个 View、一次 `onDraw`、一次画布变换**。
 *
 * 与 [DocumentInkView] 是同一条纪律的两种版式：
 *
 *   - 重排格式的"文档"是一条可以滚动的长纸，View 的高度由排版决定；
 *   - PDF 的"文档"是一叠页，界面一次只看一页。但**坐标系没有变**：笔迹仍然存在
 *     `PdfDocumentSession` 定义的文档坐标里（页内坐标 + 页偏移），本 View 只负责
 *     把当前这一页摆到屏幕上，并且用**同一个**变换把笔迹画上去。
 *
 * 于是翻页时笔迹跟着页走，不需要"翻页后重新算一遍笔迹位置"——重算就是第二套坐标，
 * 而第二套坐标迟早会与第一套差几个像素。
 *
 * ## 坐标
 *
 * 文档坐标（PDF point）→ 视图坐标（屏幕像素）只有这一次变换：
 *
 * ```
 * viewX = pageRect.left + docX * scale
 * viewY = pageRect.top  + (docY - pageTopPx) * scale
 * ```
 *
 * [PdfPagePlacement] 就是这两个式子，触摸事件走它的逆运算。页面位图用同一个
 * `pageRect` 绘制，因此"位图画在哪"与"笔迹画在哪"不可能不一致。
 *
 * ## 为什么位图与墨迹不分成两个 View
 *
 * 两个 View 就有两套生命周期：位图先到、笔迹后到，或者翻页时一个已经换了页、
 * 另一个还在旧页。合并之后这种窗口不存在（与 [DocumentInkView] 的类注释同一条理由）。
 *
 * ## 触摸
 *
 * [attachInk] 传 null（阅读模式）时本 View **不消费**任何事件：事件继续上浮给
 * Compose 的翻页热区。传了控制器（书写模式）才接管。仲裁在"模式"这一层，
 * 而不是"判断这是不是手写笔"—— 手指划线也是允许的（方案 §6.2）。
 */
class PdfInkPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var pageBitmap: Bitmap? = null
    private var pageWidthPx = 0f
    private var pageHeightPx = 0f
    private var pageTopPx = 0f

    /**
     * 跟手墨迹的字号估计（文档单位）。
     *
     * 已经落盘的笔迹自带"它所属行"的字号；正在写的那一笔还没有锚点，只能先用
     * 当前页第一行的字号。两者不一致时，抬笔瞬间笔宽会跳一下 —— 那正是用户
     * 最容易注意到的瑕疵，所以这里宁可多传一个参数。
     */
    private var fontSizePx = DrawableStroke.DEFAULT_FONT_SIZE_PX

    private var placement = PdfInkMapping.solve(0f, 0f, 0f, 0f)
    private var strokes: List<DrawableStroke> = emptyList()
    private var bands: List<PdfBand> = emptyList()
    private var underlines: List<DrawableUnderline> = emptyList()
    private var pageLines: List<LineBox> = emptyList()
    private var controller: InkLayerController? = null

    /** 书写开关：只管输入，不管绘制（见 [attachInk]）。 */
    private var inkEnabled: Boolean = false

    /** 橡皮模式。与取笔、点行互斥（三者都是"这一下要做什么"的答案）。 */
    private var eraserEnabled = false

    /**
     * 只接受手写笔（含笔的橡皮头）。
     *
     * 开着时手指事件原样交回，继续上浮给 Compose 的翻页热区 ——
     * 于是"笔写字、手指翻页"在同一次触摸里各做各的事。
     * 判据只能是事件里的 tool type，不是"这台设备有没有笔"。
     */
    private var stylusOnly = false

    /**
     * 书写时的**翻页分区**（M25）：手指落在左右各 1/3 不落笔，事件上浮给翻页热区。
     *
     * 规则与 [DocumentInkView] 完全一致（同一个 1/3、同一个"最近一次输入是笔"的前提）——
     * 两边不一样的话，重排格式与 PDF 的手感就会分裂。
     */
    private var pageTurnZones = false

    /** 最近一次**落笔**的输入是笔吗（手指被分区放行时不变）。 */
    private var lastInputWasStylus = false

    var onErase: ((DrawableStroke) -> Unit)? = null
    var onEraseMiss: (() -> Unit)? = null
    private var lastErasedId: String? = null
    private var erasedSomething = false

    /** 这一笔正跟着哪根指针（id，不是下标）。见 [PenPointers]。 */
    private var activePointerId: Int = PenPointers.NONE

    /** 跟手延迟埋点：`eventTime` → 这一帧 `onDraw`。 */
    private val latency = InkLatencyProbe("view=pdf")

    /**
     * 「点一行标高亮」模式。它与取笔模式互斥：同一块屏幕不能同时解释成
     * "这一笔要写下来"和"这一下要标这一行"。
     */
    private var lineTapEnabled = false

    /**
     * 点中一行（或点在行与行之间的空隙上，此时参数为 null）。
     *
     * 刻意把"没点中"也回调出去：界面上必须能回答"我点了为什么没反应"，
     * 而静默什么都不发生是最难排查的一种反馈。
     */
    var onLineTap: ((LineBox?) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * 色带画笔：`MULTIPLY` + 半透明。
     *
     * PDF 的文字在**位图里**，我们没有办法把色带画到字下面（重排格式可以）。
     * 正片叠底让"白底 × 颜色 = 颜色、黑字 × 颜色 = 黑字"，于是色带像荧光笔，
     * 而不是一层把字糊掉的半透明矩形。
     */
    private val bandPaint = Paint().apply { blendMode = BlendMode.MULTIPLY }

    private val renderer = InkStrokeRenderer()
    private val pageRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /**
     * 提交**当前要显示的那一页**。
     *
     * @param bitmap 已经按视口渲染好的页位图；null 表示这一页还没渲染好，本 View 什么也不画。
     * @param pageWidthPx 页宽（文档单位 = PDF point），不是位图宽度。
     * @param pageHeightPx 页高（文档单位 = PDF point）。
     * @param pageTopPx 这一页在文档坐标里的上边界（`PdfDocumentSession.pageTopPx`）。
     * @param fontSizePx 当前页正文的字号估计（文档单位），只用于跟手墨迹的笔宽。
     * @param lines 当前页的行盒（文档坐标）。「点一行标高亮」靠它命中，别的模式不用。
     */
    fun submitPage(
        bitmap: Bitmap?,
        pageWidthPx: Float,
        pageHeightPx: Float,
        pageTopPx: Float,
        fontSizePx: Float,
        lines: List<LineBox> = emptyList(),
    ) {
        val pageChanged = this.pageTopPx != pageTopPx ||
            this.pageWidthPx != pageWidthPx ||
            this.pageHeightPx != pageHeightPx
        this.pageBitmap = bitmap
        this.pageWidthPx = pageWidthPx
        this.pageHeightPx = pageHeightPx
        this.pageTopPx = pageTopPx
        this.pageLines = lines
        this.fontSizePx = if (fontSizePx.isFinite() && fontSizePx > 0f) {
            fontSizePx
        } else {
            DrawableStroke.DEFAULT_FONT_SIZE_PX
        }
        // 翻页时把"还没抬起来的那一笔"丢掉：它的点是上一页的坐标，
        // 留着会让下一页的抬笔把它锚到新页上（那正是"笔记跑到别的页"）。
        // 暂存层同理 —— 它同样是上一页的坐标，画在新页上就是错位的墨。
        if (pageChanged) {
            controller?.cancelStroke()
            controller?.discardPending()
        }
        refreshPlacement()
        invalidate()
    }

    /** 提交**全部**已解析的笔迹（文档坐标）。不按页过滤：画布会裁到当前页。 */
    fun submitInk(strokes: List<DrawableStroke>, coverage: InkCoverage = InkCoverage.EMPTY) {
        this.strokes = strokes
        // 与 [DocumentInkView.submitInk] 同一个回执，判据也一样：
        // 只有这一版解析里确实有那一笔，抬笔留下的湿墨才撤（见 `acknowledgeCommitted`）。
        controller?.acknowledgeCommitted(coverage)
        invalidate()
    }

    /** 提交色带（文档坐标）；视图用画墨迹的同一个变换画，顺序即叠放顺序（高亮在下、搜索在上）。 */
    fun submitBands(bands: List<PdfBand>) {
        this.bands = bands
        invalidate()
    }

    /** 提交下划线 / 波浪线（文档坐标）。 */
    fun submitUnderlines(underlines: List<DrawableUnderline>) {
        this.underlines = underlines
        invalidate()
    }

    /** 挂上/摘掉橡皮。传 false = 退出橡皮模式（本 View 不再消费擦除手势）。 */
    fun attachEraser(enabled: Boolean) {
        this.eraserEnabled = enabled
        invalidate()
    }

    /**
     * 挂上/摘掉"只用手写笔"。
     *
     * 与 [attachInk] / [attachEraser] 正交：那几个管做什么，这个管谁能做。
     */
    fun attachStylusOnly(enabled: Boolean) {
        this.stylusOnly = enabled
    }

    /**
     * 打开/关闭书写时的翻页分区（见 [pageTurnZones]）。
     *
     * 与 [DocumentInkView.attachPageTurnZones] 是同一件事的两处实现 ——
     * 规则写在两边的注释里，靠同一句"1/3"与"最近一次输入是笔"保持一致。
     */
    fun attachPageTurnZones(enabled: Boolean) {
        this.pageTurnZones = enabled
    }

    /**
     * 渲染/输入调优（M26）。与 [DocumentInkView.configureInk] 同名同参，
     * 但这里只有 `unbuffered` 生效：PDF 视图没有静态层缓存
     * （页位图本身就是缓存），`staticLayer` 收下但不动作 ——
     * 两个 View 保持同一套调用方式，调用方不必分叉。
     */
    fun configureInk(staticLayer: Boolean, unbuffered: Boolean) {
        useUnbuffered = unbuffered
    }

    /** 书写是否走无缓冲派发。默认开（与 M25 之前的行为一致）。 */
    private var useUnbuffered: Boolean = true

    /**
     * 这一下落在翻页区（左右各 1/3）吗。
     *
     * 这个 1/3 必须与界面层的翻页热区完全一致（见 `PdfReaderContent`
     * 里的 `detectTapGestures`）。
     */
    private fun inPageTurnZone(x: Float): Boolean {
        if (width <= 0) return false
        return x < width / 3f || x > width * 2f / 3f
    }

    /**
     * 把当前页（位图 + 色带 + 下划线 + 墨迹）渲染成一张位图。
     *
     * 与屏幕共用 [onDraw]：导出的图与看到的页必须是同一段代码画的，
     * 否则"导出的 PNG 没有我写的字"这类问题只会在导出结果里出现。
     * 底色不画（页位图本身带白底），所以这里不需要背景参数。
     */
    fun renderToBitmap(): Bitmap? {
        if (width <= 0 || height <= 0 || pageBitmap == null) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * 挂上/摘掉「点一行标高亮」。
     *
     * 与 [attachInk] 一样按**模式**仲裁，而不是按事件来自手指还是笔：
     * 同一块屏幕不能同时解释成两种意图。
     */
    fun attachLineTaps(enabled: Boolean) {
        this.lineTapEnabled = enabled
        invalidate()
    }

    /**
     * 挂上/摘掉取笔控制权。
     *
     * [enabled] = false = 阅读模式：本 View 不消费触摸，点按继续走 Compose 的翻页热区。
     * 但控制器**照样挂着**：暂存层（"抬笔了、干墨还没到场"的那一笔）是已经写下的墨，
     * 关掉书写开关不该让它消失 —— 与 [DocumentInkView.attachInk] 同一个理由。
     */
    fun attachInk(controller: InkLayerController?, enabled: Boolean = controller != null) {
        if (!enabled && controller != null && controller.isWriting) controller.cancelStroke()
        this.controller = controller
        this.inkEnabled = enabled
        invalidate()
    }

    /** 文档坐标 → 视图坐标。与 [onDraw] 里的变换严格一致（测试与"跳到某条笔记"共用）。 */
    fun docToView(docX: Float, docY: Float): PointF =
        PointF(placement.viewX(docX), placement.viewY(docY, pageTopPx))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshPlacement()
    }

    private fun refreshPlacement() {
        placement = PdfInkMapping.solve(
            viewWidthPx = width.toFloat(),
            viewHeightPx = height.toFloat(),
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
        )
    }

    /**
     * 单帧耗时埋点。与 [DocumentInkView.draw] 同一口径 —— 挂在外层 `draw` 上，
     * 免得 `onDraw` 里每个早退分支各记一次、迟早漏一个。
     */
    override fun draw(canvas: Canvas) {
        val startedNs = if (canvas.isHardwareAccelerated) System.nanoTime() else 0L
        super.draw(canvas)
        if (startedNs != 0L) latency.onFrame(System.nanoTime() - startedNs)
    }

    override fun onDraw(canvas: Canvas) {
        // 同 [DocumentInkView.onDraw]：只统计真实屏幕那一帧，导出 PNG 不算。
        if (canvas.isHardwareAccelerated) latency.onDrawn(SystemClock.uptimeMillis())
        val bitmap = pageBitmap ?: return
        val current = placement
        if (!current.usable) return

        pageRect.set(
            current.left,
            current.top,
            current.left + current.widthInViewPx,
            current.top + current.heightInViewPx,
        )
        canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint)

        canvas.save()
        // 墨迹只画在纸上：页边之外的笔画虽然也会被锚成 PINNED（M3.4），
        // 但当前这一页的视图没有义务把隔壁页的内容也画出来。
        canvas.clipRect(pageRect)

        // ── 唯一的画布变换 ────────────────────────────────────────────────
        // 文档坐标 → 视图坐标。位图用的是同一个 pageRect，笔迹用的是这次变换，
        // 两者共享 left/top/scale 三个数，因此不可能错开。
        canvas.translate(current.left, current.top)
        canvas.scale(current.scale, current.scale)
        canvas.translate(0f, -pageTopPx)

        // ① 色带（高亮在下、搜索在上，顺序由调用方给定）。
        //    它画在位图**之上**：PDF 的字在位图里，没有"文字下面"这一层可画。
        //    MULTIPLY 保证字仍然清晰（见 bandPaint 的注释）。
        for (band in bands) {
            bandPaint.color = band.colorArgb
            canvas.drawRect(band.rect.left, band.rect.top, band.rect.right, band.rect.bottom, bandPaint)
        }

        // ② 下划线 / 波浪线。
        //    同重排格式：画在文字之上、墨迹之下。PDF 的字在位图里，
        //    "文字之上"就是"位图之上"，用同一个变换落到基线上。
        renderer.drawUnderlines(canvas, underlines)

        // ③ 墨迹：已落盘的在下、暂存的居中、正在写的在上。
        //    三层共用同一个字号估计（[fontSizePx]），因此抬笔那一帧笔宽不跳。
        controller?.maintainPending()
        renderer.draw(canvas, strokes)
        val pending = controller?.pendingStrokes
        if (!pending.isNullOrEmpty()) {
            renderer.drawPending(canvas, pending, fontSizePx)
        }
        drawInProgress(canvas)

        canvas.restore()
    }

    private fun drawInProgress(canvas: Canvas) {
        val controller = controller ?: return
        // 增量追加：每帧只算新增的那一小段，与这一笔已经写了多长无关。
        renderer.drawWet(canvas, controller, fontSizePx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!placement.usable) return super.onTouchEvent(event)

        // 只用手写笔时，手指不消费事件：继续上浮给翻页热区。
        if (stylusOnly && !isStylusEvent(event)) return super.onTouchEvent(event)

        val stylus = isStylusEvent(event)
        val down = event.actionMasked == MotionEvent.ACTION_DOWN
        if (stylus && down) lastInputWasStylus = true

        // 翻页分区（M25）：与 `DocumentInkView` 同一套规则 ——
        // 手指落在左右各 1/3 不落笔（事件上浮给翻页热区），中间照常写。
        // 笔不参与分区。橡皮开着时分区让位（理由同 `DocumentInkView`）。
        if (!stylus && down && pageTurnZones && !eraserEnabled &&
            lastInputWasStylus && inPageTurnZone(event.x)
        ) {
            return super.onTouchEvent(event)
        }

        // 三个模式互斥，仲裁顺序写死：橡皮 > 取笔 > 点行。
        // 顺序而不是"谁后开谁优先"：模式本身已经互斥，这里只是兜底 ——
        // 界面若因为某条路径漏关了一个开关，宁可让橡皮生效（它不改数据），
        // 也不要让一次点击被解释成"写一笔"。
        if (eraserEnabled) return handleErase(event)
        val controller = controller
        if (inkEnabled && controller != null) {
            if (!stylus && down) lastInputWasStylus = false
            return handlePen(event, controller)
        }
        if (lineTapEnabled) return handleLineTap(event)
        // 阅读模式：不消费任何事件，点按继续上浮给 Compose 的翻页热区。
        return super.onTouchEvent(event)
    }

    /**
     * 橡皮：按下即擦，拖动连擦。
     *
     * 容差要换算回**文档单位**（PDF point）：手指的 24dp 在屏幕上是一个固定物理距离，
     * 但页被缩放显示，所以"屏幕上 24dp"在页坐标里是 `24dp / scale`。
     * 直接拿屏幕像素当页坐标比，会让放大后的页"擦不掉"、缩小后的页"擦太远"。
     */
    /**
     * 这一次触摸是不是手写笔。`TOOL_TYPE_ERASER` 是笔尾的橡皮头，也算笔。
     */
    private fun isStylusEvent(event: MotionEvent): Boolean =
        PenPointers.isStylus(event, event.actionIndex)

    private fun handleErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastErasedId = null
                erasedSomething = false
                eraseAt(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                eraseAt(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                eraseAt(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                if (!erasedSomething) onEraseMiss?.invoke()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun eraseAt(viewX: Float, viewY: Float) {
        val scale = placement.scale.takeIf { it > 0f } ?: return
        val tolerance = StrokeHitTest.DEFAULT_TOLERANCE_DP * resources.displayMetrics.density / scale
        val hit = StrokeHitTest.nearest(
            strokes = strokes,
            x = toDocX(viewX),
            y = toDocY(viewY),
            tolerancePx = tolerance,
        )
        if (hit == null || hit.strokeId == lastErasedId) return
        lastErasedId = hit.strokeId
        erasedSomething = true
        onErase?.invoke(hit)
    }

    private fun handlePen(event: MotionEvent, controller: InkLayerController): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 不让父容器（Compose 的翻页手势）把这一笔抢走。
                parent?.requestDisallowInterceptTouchEvent(true)
                // 跟住**那一支笔**，而不是第 0 号指针（见 [PenPointers]）。
                activePointerId = PenPointers.initialId(event)
                val index = event.findPointerIndex(activePointerId).coerceAtLeast(0)
                // 手写笔走无缓冲派发；手指不走（理由与 [DocumentInkView] 相同）。
                if (useUnbuffered && PenPointers.isStylus(event, index)) requestUnbufferedDispatch(event)
                controller.beginStroke(
                    toDocX(event.getX(index)),
                    toDocY(event.getY(index)),
                    event.getPressure(index),
                )
                latency.onInput(event.eventTime)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (PenPointers.isStylus(event, event.actionIndex)) {
                    val current = event.findPointerIndex(activePointerId)
                    if (current < 0 || !PenPointers.isStylus(event, current)) {
                        switchToStylus(event, controller, event.actionIndex)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                for (i in 0 until event.historySize) {
                    controller.extendStroke(
                        toDocX(event.getHistoricalX(index, i)),
                        toDocY(event.getHistoricalY(index, i)),
                        event.getHistoricalPressure(index, i),
                    )
                }
                controller.extendStroke(
                    toDocX(event.getX(index)),
                    toDocY(event.getY(index)),
                    event.getPressure(index),
                )
                latency.onInput(event.eventTime)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 笔抬起、手掌还压着：此前这条落进 default，于是不会落盘。
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    if (controller.isWriting) finishStroke(event, controller)
                    activePointerId = PenPointers.NONE
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                // 同 DocumentInkView：没有在写就不要补点落盘（那一下可能只是手掌）。
                if (controller.isWriting) finishStroke(event, controller)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 与 [DocumentInkView] 同一条规则：取消不丢笔。
                val points = controller.inProgressPointCount
                if (points >= 2) {
                    Log.i(TAG, "$TAG_CANCEL 取消时按抬笔处理 points=$points view=pdf")
                    controller.endStroke()
                    latency.reportOnStrokeEnd()
                } else if (points > 0) {
                    Log.i(TAG, "$TAG_CANCEL 取消，点数不足没有落盘 points=$points view=pdf")
                    controller.cancelStroke()
                }
                activePointerId = PenPointers.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 抬笔的公共尾巴：[ACTION_UP] 与 [ACTION_POINTER_UP] 走同一条。 */
    private fun finishStroke(event: MotionEvent, controller: InkLayerController) {
        val index = event.findPointerIndex(activePointerId)
            .let { if (it >= 0) it else event.actionIndex }
        if (index in 0 until event.pointerCount) {
            controller.extendStroke(
                toDocX(event.getX(index)),
                toDocY(event.getY(index)),
                event.getPressure(index),
            )
        }
        controller.endStroke()
        latency.reportOnStrokeEnd()
        activePointerId = PenPointers.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    /**
     * 手掌先落屏、笔后到：换到笔上并丢掉手掌那一段。
     *
     * 与 [DocumentInkView.switchToStylus] 同一套判断，理由也在那边。
     */
    private fun switchToStylus(event: MotionEvent, controller: InkLayerController, index: Int) {
        controller.cancelStroke()
        activePointerId = event.getPointerId(index)
        controller.beginStroke(
            toDocX(event.getX(index)),
            toDocY(event.getY(index)),
            event.getPressure(index),
        )
        invalidate()
    }

    override fun performClick(): Boolean {
        // 覆写以配合无障碍：本 View 消费了触摸事件，不覆写会让 lint 报 ClickableViewAccessibility。
        return super.performClick()
    }

    /**
     * 「点一行」手势。
     *
     * 只认"按下 → 抬起且几乎没移动"：拖动是滚动/翻页的常见手势，在标高亮模式下
     * 把它解释成"拖过几行就标几行"会误标，也超出 L2（一行）的语义。
     * 返回 true 表示这一串事件由本 View 负责，翻页热区不会同时收到它。
     */
    private fun handleLineTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (hypot(event.x - downX, event.y - downY) <= touchSlop) {
                    performClick()
                    onLineTap?.invoke(lineAt(toDocX(event.x), toDocY(event.y)))
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> return true
        }
        return true
    }

    /**
     * 文档坐标落在哪一行的行盒里（`top <= y < bottom`，与锚定层的判据一致）。
     *
     * 横向也要求落在页内：点在页边之外的空白处不该标中最靠近的那一行。
     */
    private fun lineAt(docX: Float, docY: Float): LineBox? {
        if (docX < 0f || docX > pageWidthPx) return null
        return pageLines.firstOrNull { docY >= it.rectDoc.top && docY < it.rectDoc.bottom }
    }

    private fun toDocX(viewX: Float): Float = placement.docX(viewX)

    private fun toDocY(viewY: Float): Float = placement.docY(viewY, pageTopPx)

    private companion object {
        const val TAG = "INKLEAF_INK"

        /** 取消的埋点标签，与 [DocumentInkView.TAG_CANCEL] 同一个名字：日志里能一起 grep。 */
        const val TAG_CANCEL = "INKLEAF_INK_CANCEL"
    }
}
