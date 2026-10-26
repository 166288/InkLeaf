package dev.inkleaf.core.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import dev.inkleaf.core.document.ReflowableTextLayout

/**
 * 一条要画的**标注高亮**（文档坐标的矩形 + 它自己的颜色）。
 *
 * 为什么不复用 [DocumentInkView.submitSearchHighlight] 的 `List<Box>`：
 * 搜索命中只有一个色（当前那一条），而我自己标的高亮**每一条都有自己的颜色**
 * （颜色存在库里那条标注上）。合成一层就得给搜索命中编一个颜色或者给高亮丢掉颜色，
 * 两种都错。
 */
data class HighlightRect(
    val box: dev.inkleaf.core.model.Box,
    val colorArgb: Int,
)

/**
 * 文档 + 墨迹的**唯一**渲染 View（方案 §8.4）。
 *
 * 「一个 View、一次 `onDraw`、一次画布变换」不是为了少写代码，而是为了
 * 从结构上消灭一整类 bug：文字与笔迹一旦分处两个 View（或两套坐标系），
 * 就必然存在"这帧文字用新排版、笔迹用旧排版"的窗口，表现是滚动或翻页时
 * 笔迹滞后一格。合并之后，两者共用同一个 `Canvas.translate`，
 * 数学上不可能出现不同的变换。
 *
 * 坐标约定（本类的全部接口都遵守它）：
 *   - **文档坐标**：以文档左上角为原点，1 单位 = 1 px。行盒（`LineBox.rectDoc`）、
 *     笔迹点、分页位置全在这套坐标里。它与"字号/栏宽"无关，因此改字号后
 *     行盒会变、文档坐标的数值也会变（这正是笔迹要存 em 而不能存文档坐标的原因）。
 *   - **视图坐标**：本 View 的左上角为原点。两者之间只差一个平移
 *     （`marginLeft`, `marginTop - windowTop`）。
 */
class DocumentInkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { PAGED, SCROLL }

    private var layout: ReflowableTextLayout? = null
    private var mode: Mode = Mode.PAGED

    /**
     * 绘制窗口上边在**文档坐标系**里的位置。
     *
     * PAGED 模式由页码决定（那一页首行的文档 y）；SCROLL 模式由滚动偏移决定
     * （[setWindowTop]）。两种模式同一个口径，`clipAndTranslate` 才能只有一份实现。
     */
    private var windowTopPx: Int = 0

    private val inkRenderer = InkStrokeRenderer()
    private var inkController: InkLayerController? = null

    /**
     * 书写开关（用户点那个笔图标）。**只管输入，不管绘制**。
     *
     * 这两件事原先捆在一个字段上（关掉书写 = `attachInk(null)`），代价是：
     * 关掉的那一瞬间，`onDraw` 里"没有控制器就整层湿墨都不画"直接早退，
     * 于是刚抬笔、还等着干墨到场的那一笔**从屏幕上消失**，等解析回来才重新出现。
     * 用户点一下书写开关正好落在这个窗口里，看到的就是"我画的圈有时候会消失"。
     *
     * 开关该决定的是"这一下触摸算不算写字"，不该决定"已经写下的墨还画不画"。
     */
    private var inkEnabled: Boolean = false

    private var committedStrokes: List<DrawableStroke> = emptyList()

    /**
     * 静态层的录制结果（正文 + 高亮 + 下划线 + 已落盘墨迹）。null = 还没录过。
     *
     * 只在硬件画布上用。软件画布（导出 PNG）直接现画 —— 见 `onDraw` 里的注释。
     */
    private var staticLayer: RenderNode? = null

    /** 静态层内容脏了。任何会改变静态层的提交都要置位，否则屏幕会停在旧的一版。 */
    private var staticDirty: Boolean = true

    // ── 渲染/输入调优（M26）。两项都能从设置里改，默认与 M25 的最优路径一致。 ──

    /** 静态层是否录进 RenderNode。默认开；低端机或排查显示问题时可关（见 configureInk）。 */
    private var useStaticLayer: Boolean = USE_STATIC_LAYER

    /** 手写笔（书写）与套索（笔 + 手指）是否走无缓冲派发。关掉可避开个别设备的事件抖动。 */
    private var useUnbuffered: Boolean = true

    /**
     * 应用层调渲染/输入参数。值没变就不触发重录 —— 它在 `AndroidView` 的
     * `update` 里每次重组都会被调到。
     *
     * 这里**刻意没有**"脏区刷新"开关：带区域的 `invalidate(l,t,r,b)` 自 API 21 起
     * 矩形就被系统忽略（API 28 起整个方法弃用，官方建议直接调 `invalidate()`），
     * 算包围盒只是白算，所以不做。
     */
    fun configureInk(staticLayer: Boolean, unbuffered: Boolean) {
        if (useStaticLayer != staticLayer) {
            useStaticLayer = staticLayer
            staticDirty = true
        }
        useUnbuffered = unbuffered
    }

    /** 静态层变了：重录一帧，并请求重画。 */
    private fun invalidateStatic() {
        staticDirty = true
        invalidate()
    }

    /**
     * 搜索命中高亮（文档坐标）。由 [submitSearchHighlight] 提交。
     *
     * 它是**一次性**的显示状态，不是文档的一部分：颜色与矩形都由调用方给，
     * 本 View 不负责"命中了哪些字"（那是 [dev.inkleaf.core.document.TextSearch] 的事）。
     */
    private var highlightRects: List<dev.inkleaf.core.model.Box> = emptyList()

    /**
     * 我自己标的高亮（文档坐标 + 每条自己的颜色）。由 [submitNoteHighlights] 提交。
     *
     * 与 [highlightRects] 分开存、分开画，而不是合成一层：两者是**两种东西** ——
     * 上面那一层是"搜索现在命中在哪"（一次性、随查询消失），
     * 这一层是"我在书里标过哪些地方"（文档的一部分、颜色来自库里那条标注）。
     * 合成一层就要给搜索命中编一个"标注 id"，而它根本没有。
     */
    private var noteHighlightRects: List<HighlightRect> = emptyList()

    /**
     * 我自己画的下划线 / 波浪线（文档坐标）。由 [submitUnderlines] 提交。
     *
     * 与高亮分开一层不是因为配色，而是因为**画法**：高亮是一个矩形，
     * 下划线是一条带样式（直/波浪、粗细、基线偏移）的线。合成一层就得给矩形
     * 编一个"样式"字段，然后让画矩形的代码去判断该不该画成波浪 —— 那正是
     * 一类"看起来像高亮、其实是下划线"的渲染错。
     */
    private var noteUnderlines: List<DrawableUnderline> = emptyList()

    /**
     * 橡皮模式：本 View 只做命中测试，不改任何数据。
     *
     * 判据与"怎么删"分开：这里回答"擦到了哪一笔"，删除与撤销在界面层
     * （软删除 + 可撤销）。渲染层拿不到库，也不该拿 —— 它连 `Annotation` 都不认识。
     */
    private var eraserEnabled = false

    /**
     * 只接受手写笔（含笔的橡皮头）。
     *
     * 开着时手指不再落笔，触摸事件**原样返回 false**，于是它继续上浮给
     * Compose 的翻页/滚动手势 —— "笔写字、手指翻页"这两种输入因此在同一次
     * 触摸里各做各的事，不需要界面层再判断一遍。
     *
     * 为什么判断放在 View 里而不是界面里：只有 `MotionEvent.getToolType()` 知道
     * 这一下是笔还是手指，而它只在事件分发时才有。界面拿到的是"某个坐标被点了"，
     * 那个信息已经丢了。放在上面判，就只能拿设备有没有手写笔来猜 ——
     * 而"有手写笔的设备"和"这一次是用手写笔点的"是两件事。
     */
    private var stylusOnly = false

    /**
     * 书写时的**翻页分区**（M25）。
     *
     * 开着时：手指落在左右各 1/3 里**不落笔**，事件上浮给翻页手势；
     * 落在中间照常落笔。笔在哪儿都能写。
     *
     * 为什么要有这个：书写模式原本整个屏幕都不翻页（"翻页与书写会落在同一区域"），
     * 于是"写着写着想看下一页"只能先关掉笔刷 —— 那个动作太重。
     * 而"笔写字、手指翻页"本来就是行业惯例（GoodNotes / Notability / OneNote 都是）。
     *
     * 为什么不直接用 [stylusOnly]：它是"手指**完全**不书写"这个更强也更极端的选项。
     * 分区保留了"手指也能写字"（有些人不习惯用笔），同时把左右两侧让给翻页。
     *
     * 为什么还额外看 [lastInputWasStylus]：纯用手指书写的人，落在左侧 1/3 的一笔
     * 会被当成翻页 —— 那是误触。只有在**最近一次输入是笔**的时候才启用分区：
     * 那说明这个人正用笔写，翻页意图本来就该用手指表达。
     */
    private var pageTurnZones = false

    /**
     * 最近一次**落笔**的输入是笔吗。
     *
     * 笔按下时置 true，手指真的落笔时置 false；
     * 手指被翻页分区放行时**不改**（那一笔没写，不该影响判断）。
     */
    private var lastInputWasStylus = false

    /** 擦到某一笔时回调。参数是**已解析的笔迹**（带着它的标注 id）。 */
    var onErase: ((DrawableStroke) -> Unit)? = null

    /** 擦除没命中时的回调：静默失败是最难排查的一种反馈。 */
    var onEraseMiss: (() -> Unit)? = null

    /** 搜索高亮的颜色。 */
    var highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR

    private val highlightPaint = Paint()

    /**
     * 套索那根虚线。
     *
     * 用虚线而不是实线：实线在屏幕上就是"又一条笔迹"，而它其实是一个
     * **选择框** —— 用户要能一眼分清"这是我画的圈"与"这是我写的字"。
     */
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF1F5FA8.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val lassoPathShape = Path()

    /** 橡皮手势的起点与"这一次拖动里已经擦过的那一笔"。 */
    private var downX = 0f
    private var downY = 0f
    private var lastErasedId: String? = null
    private var erasedSomething = false

    /**
     * 这一笔正跟着哪根指针。
     *
     * 必须是 id 而不是下标：多指事件里下标会随别的指针抬起而变，而 id 在整个
     * 手势里不变。`findPointerIndex` 拿它换回当前下标（见 [PenPointers] 的注释）。
     */
    private var activePointerId: Int = PenPointers.NONE

    /** 跟手延迟埋点：`eventTime` → 这一帧 `onDraw`。见 [InkLatencyProbe]。 */
    private val latency = InkLatencyProbe("view=doc")

    /**
     * 提交新的排版结果与新窗口位置。
     *
     * 一次性提交（而不是三个 setter）是为了让"排版结果"与"窗口位置"永远成对更新：
     * 分别设置会让某一帧出现"新字号 + 旧页码"的组合，视觉上就是翻页时内容闪一下。
     *
     * 重复提交同一组值会直接早退：`AndroidView` 的 `update` 每次重组都会调到这里，
     * 而它现在带着滚动偏移 —— 滚动时每帧都来一次，不早退就会每帧 `requestLayout`。
     */
    fun submit(layout: ReflowableTextLayout?, mode: Mode, windowTopPx: Int) {
        val nextTop = windowTopPx.coerceAtLeast(0)
        if (this.layout === layout && this.mode == mode && this.windowTopPx == nextTop) return
        this.layout = layout
        this.mode = mode
        this.windowTopPx = nextTop
        requestLayout()
        invalidateStatic()
    }

    /**
     * 滚动模式：只移动绘制窗口，不重新提交排版。
     *
     * 与 [submit] 的分界是"排版有没有变"：排版变了要重新测量、静态层整块重录；
     * 滚动只改窗口位置，测量结果不变（View 永远一屏高），变的只是
     * 静态层里那一刀裁剪落在哪 —— 所以它只需要重录，不需要 `requestLayout`。
     *
     * **必须同步调用**。界面层是在滚动手势的回调里直接调它的：走重组再传进来
     * 会晚一帧，滚动时表现为"画面追不上手指"。
     */
    fun setWindowTop(px: Int) {
        if (mode != Mode.SCROLL) return
        val next = px.coerceAtLeast(0)
        if (next == windowTopPx) return
        windowTopPx = next
        // 静态层是按旧窗口剪的，窗口一动它就过期了。
        staticDirty = true
        invalidate()
    }

    /**
     * 告诉 View 当前纸色 —— **只**用来决定荧光笔往哪个方向混色（见
     * [InkStrokeRenderer.paperIsDark]），View 自己不画纸（纸是 Compose 主题画的，
     * 导出 PNG 时才由 [renderToBitmap] 手动画一次）。
     *
     * 为什么是"传纸色"而不是"传是否深色"：调用方手里就只有纸色（六套主题的
     * `paperArgb`），让它自己先判一次深浅，等于把同一个判定写成两份。
     * 判定阈值见 [DARK_PAPER_LUMA]。
     *
     * 反复调用是安全的（值没变就什么都不做）—— 它在 `AndroidView` 的 `update`
     * 里每帧都可能被调到。
     */
    fun setPaper(paperArgb: Int) {
        val dark = isDarkPaper(paperArgb)
        if (dark == inkRenderer.paperIsDark) return
        inkRenderer.paperIsDark = dark
        // 静态层里那些荧光笔是用旧的混合模式录进去的，必须重录。
        invalidateStatic()
        invalidate()
    }

    /**
     * 提交**已解析、已换算到文档坐标**的墨迹。
     *
     * 注意入参是 [DrawableStroke] 而不是库里的标注：解析（锚点 → 行 → 像素）
     * 在 [InkResolver] 里做完了。View 不认识锚点，也就不可能用错坐标系。
     *
     * @param coverage 这一版解析"覆盖了哪些笔迹"（[InkResolution.coverage]）。
     *        它是给暂存层的回执：**只有这一版里确实有某一笔**，那一笔的湿墨才允许撤。
     *        不传 = 对暂存不做任何判断 —— 调用方拿不到解析结果时宁可留着湿墨，
     *        也不要凭"提交过一版"就把它撤掉（原因见 [InkLayerController.acknowledgeCommitted]）。
     */
    fun submitInk(strokes: List<DrawableStroke>, coverage: InkCoverage = InkCoverage.EMPTY) {
        committedStrokes = strokes
        rebuildSelectedStrokes()
        inkController?.acknowledgeCommitted(coverage)
        invalidateStatic()
    }

    /**
     * 提交搜索命中的高亮矩形（文档坐标）。
     *
     * 传空列表就是清除。与 [submitInk] 一样，入参已经是"能直接画的东西"：
     * 字符区间 → 矩形的换算在排版层完成（`ReflowableTextLayout.rectsOfCharRange`），
     * 因为只有排版层知道每个字画在哪。
     */
    fun submitSearchHighlight(rects: List<dev.inkleaf.core.model.Box>) {
        highlightRects = rects
        invalidateStatic()
    }

    /**
     * 提交**我自己标的高亮**（文档坐标 + 每条的颜色）。传空列表就是清除。
     *
     * 与 [submitSearchHighlight] 一样，入参已经是"能直接画的东西"：
     * 字符区间 → 矩形的换算在排版层完成。差别只有一处：这里的每一条自带颜色，
     * 因为颜色是**库里那条标注**的属性（用户标的时候用的那个），
     * 而搜索命中是界面的临时状态，只有一个色。
     */
    fun submitNoteHighlights(rects: List<HighlightRect>) {
        noteHighlightRects = rects
        invalidateStatic()
    }

    /** 提交下划线 / 波浪线（文档坐标）。传空列表就是清除。 */
    fun submitUnderlines(underlines: List<DrawableUnderline>) {
        noteUnderlines = underlines
        invalidateStatic()
    }

    /**
     * 挂上/摘掉橡皮。
     *
     * 与 [attachInk] 一样按**模式**仲裁：橡皮与书写互斥，否则同一处按下
     * 既可能被理解成"写一笔"也可能被理解成"擦一笔"。界面的三个模式
     * （写 / 高亮 / 擦）因此只允许一个为真，判断在界面，执行在这里。
     */
    fun attachEraser(enabled: Boolean) {
        eraserEnabled = enabled
        invalidate()
    }

    /**
     * 套索模式（S1）：这一串触摸是"画一个圈"，不是"写一笔"。
     *
     * 与 [eraserEnabled] 同级、同样互斥：圈选、书写、擦除三者对同一次按下
     * 有完全不同的解释，界面只允许一个为真。
     */
    private var lassoEnabled = false

    /**
     * 正在画的那个圈（**文档坐标**，抬笔时交给命中判定）。
     *
     * 视图坐标那一份不再单独存列表：虚线直接**增量**写进 [lassoPathShape]，
     * 每帧只追加新点，不再整条重建（见 [handleLasso]）。
     */
    private val lassoDoc = ArrayList<Float>()

    /**
     * 已被选中的笔迹 id。
     *
     * 由 [setSelection] 从外面灌进来：命中判定在 `StrokeHitTest`（纯函数），
     * 而"选中了谁"是一个界面状态，View 不该自己造一份。
     */
    private var selection: Set<String> = emptySet()

    /**
     * 选中笔迹的**物化列表**（拖动预览直接画它）。
     *
     * 在 [setSelection] / [submitInk] 时重建，而不是每帧从 `committedStrokes` 过滤：
     * 拖动预览每帧都要用它，过滤 + 复制点数组曾经是套索不跟手的主要原因。
     */
    private var selectedStrokes: List<DrawableStroke> = emptyList()

    /** 拖动中的位移（文档坐标）。松手之前它只影响画面，不动数据。 */
    private var moveDx = 0f
    private var moveDy = 0f

    /**
     * 套索画圈时上一个**已写入**的点（视图坐标）。
     *
     * 用于采样抽稀：与上一点距离小于 [MIN_LASSO_STEP] 的新点直接丢弃。
     * 高采样率设备一帧能给几十个点，全收只会让 Path 与抬笔时的命中判定一起变慢，
     * 而人眼与人手都分辨不出 1.5px 以内的抖动。
     */
    private var lastLassoX = 0f
    private var lastLassoY = 0f

    /** 这次按下是不是在拖选中的东西（而不是在画一个新的圈）。 */
    private var draggingSelection = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    /** 圈画完了：参数是**文档坐标**的闭合折线（x0,y0,x1,y1,…）。 */
    var onLasso: ((FloatArray) -> Unit)? = null

    /** 拖动松手了：[dx] / [dy] 是文档坐标里的位移。 */
    var onMove: ((Float, Float) -> Unit)? = null

    /**
     * 挂上/摘掉"只用手写笔"。
     *
     * 与 [attachInk] / [attachEraser] 是一组：那两个管**做什么**，这个管**谁能做**。
     * 分开是因为它们可以任意组合：笔+只认笔、橡皮+只认笔、笔+手指也行。
     */
    fun attachStylusOnly(enabled: Boolean) {
        stylusOnly = enabled
    }

    /**
     * 挂上/摘掉套索（S1）。
     *
     * 套索与书写、橡皮互斥，且**优先于它们**：开着的时候这一串事件一律被当成
     * "画圈 / 拖动"，绝不会变成一笔新的墨 —— 否则用户画圈的同时屏幕上多一条
     * 莫名其妙的线，而那条线还要再擦一次。
     */
    fun attachLasso(enabled: Boolean) {
        if (!enabled) {
            lassoPathShape.reset()
            lassoDoc.clear()
            draggingSelection = false
            moveDx = 0f
            moveDy = 0f
        }
        lassoEnabled = enabled
        invalidate()
    }

    /**
     * 告诉 View 现在选中了哪些笔迹。
     *
     * 传空集就是取消选中（连拖动中的位移一起清掉）—— "取消"必须是同一个入口，
     * 分成"清空选择"与"结束拖动"两个动作，迟早会有一个状态忘了清。
     */
    fun setSelection(strokeIds: Set<String>) {
        // `AndroidView` 的 update 每次重组都会调到这里：相同选择直接跳过，
        // 免得每帧都重建选中缓存、还多刷一帧。
        if (selection == strokeIds) return
        selection = strokeIds
        rebuildSelectedStrokes()
        if (strokeIds.isEmpty()) {
            moveDx = 0f
            moveDy = 0f
            draggingSelection = false
        }
        invalidate()
    }

    /** 把 `selection` 物化成可直接绘制的笔迹列表（拖动预览用）。 */
    private fun rebuildSelectedStrokes() {
        selectedStrokes = if (selection.isEmpty()) {
            emptyList()
        } else {
            committedStrokes.filter { it.strokeId in selection }
        }
    }

    /**
     * 打开/关闭书写时的**翻页分区**（见 [pageTurnZones]）。
     *
     * 由界面层决定"什么时候该有分区"（分页模式 + 书写开启），
     * View 只负责"这一下落在哪一侧"。
     */
    fun attachPageTurnZones(enabled: Boolean) {
        pageTurnZones = enabled
    }

    /**
     * 当前是不是"只认手写笔"（只读）。
     *
     * 为什么要把这个状态读出来：设置里的 `stylusOnly` 变成 true，到 View 真的收到
     * 之间还隔着一次 Compose 重组。设备测试如果 sleep 一段时间再落笔，等于赌
     * "重组已经跑完了"—— 赌输了会红在"手指居然画出了笔迹"这种最像产品 bug 的地方。
     * 让测试等这个值，比等一段时间诚实。
     */
    val stylusOnlyEnabled: Boolean
        get() = stylusOnly

    /**
     * 把当前页渲染成一张位图（导出 PNG 用）。
     *
     * 走 `draw(Canvas)` 而不是自己重画一遍：**导出必须与屏幕上看到的是同一段代码**，
     * 否则"导出的图少了墨迹"或"下划线错位"这类问题只会在导出结果里出现，
     * 而导出结果恰恰是最不容易被反复检查的东西。
     *
     * @param backgroundArgb 底色。View 自己不知道纸色（那是 Compose 主题的事），
     *        不给底色会导出成透明 PNG —— 粘到白色笔记软件里看似正常，
     *        粘到深色背景上就变成一片看不见的黑字。
     */
    fun renderToBitmap(backgroundArgb: Int): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundArgb)
        draw(canvas)
        return bitmap
    }

    /**
     * 挂上取笔控制权。
     *
     * [enabled] = false 表示"现在不是书写模式"：本 View 不再消费触摸事件，
     * 事件继续上浮给 Compose 的翻页/滚动手势。**书写模式与翻页模式的仲裁就在这里**，
     * 而不是靠"判断是不是手写笔"—— 用户可能用不支持手写笔的设备，也可能
     * 用手指划线（方案 §6.2 允许）。
     *
     * 但控制器本身（[controller]）请**一直挂着**：它持有"抬笔了、干墨还没到场"
     * 的暂存笔迹，那是已经写下的墨，关掉书写开关不该让它消失（见 [inkEnabled]）。
     * 传 null 只有一种场合：这个 View 根本不显示笔迹（PDF 没有文本层的页）。
     */
    fun attachInk(controller: InkLayerController?, enabled: Boolean = controller != null) {
        // 关掉书写的那一刻还没抬笔的这一笔：它等不到 ACTION_UP 了，
        // 而 `onDraw` 每帧都会照着 inProgressPoints 画 —— 留着就是一条
        // 永远抹不掉的半截线。宁可丢掉这半截（用户还没写完），也不要留个幽灵。
        if (!enabled && controller != null && controller.isWriting) controller.cancelStroke()
        inkController = controller
        inkEnabled = enabled
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 静态层是按视图尺寸录的，尺寸一变就得重录（旋转、分屏、改字号后的重排）。
        staticDirty = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        // 两种模式都只报**一屏**。
        //
        // 旧实现让滚动模式"有多高报多高"，靠外层 `verticalScroll` 平移这个巨大的
        // View 来完成滚动。一本书 17001 行 ≈ 155 万像素高，hwui 要为它建显示列表
        // 与裁剪区，真机上直接在原生层崩掉（Scudo 堆校验失败 / std::vector
        // length_error，崩点每次不同 —— 典型的堆损坏）。
        //
        // 现在 View 只负责"画窗口里那一段"（窗口由 [submit] / [setWindowTop]
        // 给出），滚动偏移由界面层持有：这是纵向虚拟化，代价是滚动状态搬到界面层
        // （见 `ReaderScrollState`），收益是 View 的高度、显示列表、导出位图
        // 全部与文档长度**脱钩**。
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    /**
     * 单帧耗时的埋点挂在 `draw` 而不是 `onDraw` 上：`onDraw` 里有几处早退
     * （没有排版、高度为 0），挂在里面就得在每个出口各记一次，迟早漏一个。
     */
    override fun draw(canvas: Canvas) {
        val startedNs = if (canvas.isHardwareAccelerated) System.nanoTime() else 0L
        super.draw(canvas)
        if (startedNs != 0L) latency.onFrame(System.nanoTime() - startedNs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 埋点只统计真实屏幕那一帧：导出 PNG 走的是软件画布，把它算进来
        // 会把 p95 拉成"导出要多久"，与跟手无关。
        if (canvas.isHardwareAccelerated) latency.onDrawn(SystemClock.uptimeMillis())
        val current = layout ?: return
        val windowTop = windowTopPx.toFloat()
        val windowBottom = windowTop + height.toFloat()
        if (windowBottom <= windowTop) return

        // ── ① 静态层：高亮 + 正文 + 下划线 + 已落盘的墨迹 ─────────────────────
        // 这一层的内容只在 submit 系列被调用时才会变，而每个 MOVE 都会让整屏重画一帧。
        // 于是把它录进一个 RenderNode：变的时候录一次，之后每帧只是重放显示列表，
        // 不再跑 `StaticLayout.draw` 与每一笔的 `drawPath`。这是"写字时每帧要干的活"
        // 从"页上有什么"里摘出来的那一步。
        //
        // 软件画布（`renderToBitmap` 导出 PNG）不录：RenderNode 只能进硬件画布，
        // 硬画会得到一张空图 —— 而"导出的图没有内容"是最难发现的一种错。
        if (useStaticLayer && canvas.isHardwareAccelerated) {
            val node = staticLayer ?: RenderNode("inkleaf-doc").also { staticLayer = it }
            if (staticDirty || node.width != width || node.height != height) {
                node.setPosition(0, 0, width, height)
                val recorder = node.beginRecording(width, height)
                paintStatic(recorder, current, windowTop, windowBottom)
                node.endRecording()
                staticDirty = false
            }
            canvas.drawRenderNode(node)
        } else {
            paintStatic(canvas, current, windowTop, windowBottom)
        }

        // ── ①′ 套索层：选中笔迹的"挪过去之后"预览 + 正在画的那个圈 ──────────────
        // 排在湿墨层**之前**：湿墨层那一段在没有控制器时会提前 return，
        // 而套索不依赖书写控制器（只读模式下也要能圈选与拖动）。
        if (lassoEnabled || selection.isNotEmpty()) {
            paintLassoOverlay(canvas, current, windowTop)
        }

        // ── ② 湿墨层：暂存 + 正在写 ────────────────────────────────────────────
        // 这一层每帧都变，必须现画。它与静态层共用**同一个**画布变换，
        // 因此"字在哪、墨在哪"仍然只由一个 translate 决定（类注释那条纪律）。
        val ink = inkController ?: return
        ink.maintainPending()
        canvas.save()
        clipAndTranslate(canvas, current, windowTop)
        val pending = ink.pendingStrokes
        if (pending.isNotEmpty()) {
            inkRenderer.drawPending(canvas, pending, current.theme.textSizePx)
        }
        // 跟手墨迹还没有锚点，拿不到"它所属行"的字号，用整篇正文字号作估计：
        // 与落盘后（按真实行盒字号）画出来的粗细一致，不会"抬笔瞬间变粗/变细"。
        inkRenderer.drawWet(canvas, ink, current.theme.textSizePx)
        canvas.restore()
    }

    /**
     * 套索层（S1）。
     *
     * 两样东西的坐标系**不同**，这不是笔误：
     *
     * - "挪过去之后"的预览画在**文档坐标**里 —— 它是笔迹，必须跟着滚动与翻页走，
     *   否则预览停在屏幕上的某个位置，一滚就与真的墨错开。
     * - 正在画的那个圈画在**视图坐标**里 —— 它是手指此刻划过的轨迹，
     *   属于屏幕而不属于文档；画进文档坐标会在滚动时"跟着纸跑"。
     */
    private fun paintLassoOverlay(canvas: Canvas, current: ReflowableTextLayout, windowTop: Float) {
        val shifting = (moveDx != 0f || moveDy != 0f) && selectedStrokes.isNotEmpty()
        if (shifting) {
            canvas.save()
            clipAndTranslate(canvas, current, windowTop)
            // 预览 = 同一批笔迹 + 一次平移。逐点复制一份 FloatArray 再画在数学上
            // 完全等价，却要在 60Hz 上为每笔每帧分配几 KB —— 那是套索拖动不跟手的
            // 主要原因。平移画布零分配，而且笔迹形状缓存（path()）照样用得上。
            canvas.translate(moveDx, moveDy)
            inkRenderer.draw(canvas, selectedStrokes)
            canvas.restore()
        }
        // 虚线已经在 handleLasso 里增量写好了，这里只负责画（不重建路径）。
        if (lassoDoc.size >= 4) {
            canvas.drawPath(lassoPathShape, lassoPaint)
        }
    }

    /**
     * 画静态层：① 我标高亮 ② 搜索命中 ③ 正文 ④ 下划线 ⑤ 已落盘的墨迹。
     *
     * 抽成方法是为了让"录进 RenderNode"与"直接画到软件画布"走**同一段代码** ——
     * 导出与屏幕一旦分成两份实现，症状只会在导出里出现，而导出最少被检查。
     */
    private fun paintStatic(
        canvas: Canvas,
        current: ReflowableTextLayout,
        windowTop: Float,
        windowBottom: Float,
    ) {
        // 分页模式下，一切**跟着文字走**的东西（高亮 / 正文 / 下划线）必须剪到
        // 本页的窗口里。窗口之外就是上下页边距，那里露出来的只可能是
        // **相邻页的行** —— 上边距露出上一页最后一行的下半截、下边距露出下一页
        // 第一行的上半截，读者看到的就是"换页时字被切了一半"（M32）。
        //
        // 窗口取 `pageWindowAt(...)`（本页的**实际**首尾行），不是正文框高度：
        // 一页很少刚好填满正文框，差出来的那点高度正好能塞下下一页的半行字。
        //
        // 墨迹不剪：用户在页边距里写的字是**他的东西**，不能因为翻页就看不见。
        // 所以这里只收文字层的纵向范围，⑤ 单独再开一次 save/restore。
        //
        // 滚动模式没有"页"，窗口就是屏幕：但它的窗口上沿是**文档坐标的滚动量**，
        // 而视图顶部那 `marginTop` 一条显示的是窗口上沿**之前**的正文（上边距
        // 在滚动模式下是纸的一部分，不是要留白的边），所以可见的文档范围要
        // 往回够一个上边距。少了这一项，滚动时顶端会固定缺一条字。
        val pageWindow = if (mode == Mode.PAGED) current.pageWindowAt(windowTopPx) else null
        val textWindowTop = pageWindow?.first?.toFloat()
            ?: (windowTop - current.metrics.marginTopPx)
        val textWindowBottom = pageWindow
            ?.let { (it.last + 1).toFloat() }
            ?.coerceAtMost(windowBottom)
            ?: (textWindowTop + height)

        canvas.save()
        clipAndTranslate(canvas, current, windowTop)
        // 此时画布已经是**文档坐标**（translate 之后），
        // 而文档 y = windowTop 恰好画在视图 y = marginTop 处，
        // 所以这一刀剪的就是"窗口首行顶边到末行底边"。
        canvas.clipRect(0f, textWindowTop, width.toFloat(), textWindowBottom)

        // ① 我自己标的高亮（最底下的一层色）。
        //    高亮必须画在**文字下面**：画在上面时字会被色块盖住，用户看到的
        //    就不是"这几个字标了"，而是"这里糊了一块颜色"。
        //    它排在搜索命中之下：底色说的是"我在书里标过这里"，
        //    而搜索命中说的是"此刻正在找的就是这里" —— 两者重叠时要让后者更显眼。
        if (noteHighlightRects.isNotEmpty()) {
            for (rect in noteHighlightRects) {
                val box = rect.box
                if (box.bottom <= textWindowTop || box.top >= textWindowBottom) continue
                highlightPaint.color = rect.colorArgb
                canvas.drawRect(box.left, box.top, box.right, box.bottom, highlightPaint)
            }
        }

        // ② 搜索命中高亮
        if (highlightRects.isNotEmpty()) {
            highlightPaint.color = highlightColor
            for (rect in highlightRects) {
                if (rect.bottom <= textWindowTop || rect.top >= textWindowBottom) continue
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, highlightPaint)
            }
        }

        // ③ 文本
        for (section in current.sections) {
            val sectionTop = section.topPx.toFloat()
            val sectionBottom = sectionTop + section.heightPx
            if (sectionBottom <= textWindowTop || sectionTop >= textWindowBottom) continue
            canvas.save()
            canvas.translate(0f, sectionTop)
            section.layout.draw(canvas)
            canvas.restore()
        }

        // ④ 下划线 / 波浪线。
        //    画在文字**之上**、墨迹之下：它贴着基线，不压字身；而墨迹是用户手写的
        //    东西，永远该盖在排版装饰上面（否则"写在字上的批注被一条线穿过"）。
        //    与高亮相反 —— 高亮在文字下，因为它要透出字；下划线在文字上，
        //    因为它要盖住基线（画在下面会被字的下缘挡住，中文尤其明显）。
        inkRenderer.drawUnderlines(canvas, noteUnderlines)

        canvas.restore()

        // ⑤ 已落盘的墨迹。暂存与正在写的那一笔在下一层（它们每帧都变）。
        //
        // 单独开一次裁剪：墨迹**不**剪进正文窗口（理由见本方法开头）。
        // 剪进去的代价是"写在页边距里的批注翻页就不见了"，那比多露半行字严重得多。
        canvas.save()
        clipAndTranslate(canvas, current, windowTop)
        inkRenderer.draw(canvas, committedStrokes, windowTop, windowBottom)
        canvas.restore()
    }

    /**
     * 剪到正文区并施加**唯一**的那次变换（文档坐标 → 视图坐标）。
     *
     * marginTopPx 参与进来，是为了让"分页用的可用高度"与"实际绘制位置"
     * 用同一个事实：页首留白写在排版度量里，绘制时就必须照它平移。
     */
    private fun clipAndTranslate(canvas: Canvas, current: ReflowableTextLayout, windowTop: Float) {
        val metrics = current.metrics
        canvas.clipRect(
            metrics.marginLeftPx,
            0f,
            (width - metrics.marginRightPx).coerceAtLeast(metrics.marginLeftPx),
            height.toFloat(),
        )
        canvas.translate(metrics.marginLeftPx, metrics.marginTopPx - windowTop)
    }

    // ── 取笔 ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只用手写笔时，手指不落笔 —— 返回 false 让事件继续上浮给翻页手势。
        // 注意它必须排在最前面：橡皮、书写、以及"正在写的那一笔"都要受这条约束，
        // 否则会出现"笔在写，手掌按上去却把笔迹擦了"这种最糟的组合。
        if (stylusOnly && !isStylusEvent(event)) return false

        val stylus = isStylusEvent(event)
        val down = event.actionMasked == MotionEvent.ACTION_DOWN

        // 笔一落下就记下来：它是"这个人正在用笔写"的证据，
        // 翻页分区据此才敢把手指的左右两侧让出去。
        if (stylus && down) lastInputWasStylus = true

        // 翻页分区（M25）：手指落在左右各 1/3 → 不落笔，事件上浮给翻页手势。
        // 笔不参与分区 —— 笔在哪儿都是写字。
        //
        // 橡皮开着的时候分区**让位**：擦除是一个明确选了工具的动作，
        // 不该因为落在页边 1/3 就擦不到 —— "想擦却擦不掉"比"误翻一页"糟糕得多。
        if (!stylus && down && pageTurnZones && !eraserEnabled &&
            lastInputWasStylus && inPageTurnZone(event.x)
        ) {
            return false
        }

        // 套索排在橡皮与书写之前：它要把这一串事件**全部**吃掉，
        // 否则画圈的同时会落一笔墨（还有可能被翻页分区放行去翻页）。
        if (lassoEnabled) return handleLasso(event)

        if (eraserEnabled) return handleErase(event)
        // 书写开关关着：这一串事件不是笔迹，交回给上层的翻页/滚动手势。
        // 判断排在控制器之前 —— 控制器还在（它要负责把暂存画完），但不该吃触摸。
        if (!inkEnabled) return super.onTouchEvent(event)
        val controller = inkController ?: return super.onTouchEvent(event)

        // 手指真的落笔了 → 最近一次输入不是笔（翻页分区随之收起，
        // 免得纯用手指写字的人被左侧 1/3 的误触坑到）。
        if (!stylus && down) lastInputWasStylus = false

        // ── 坐标换算（这里曾经是错的）─────────────────────────────────────
        // 触摸事件给的是**视图坐标**，而笔迹的全部数据（行盒、落笔点、锚点）
        // 都在**文档坐标**里。两者差一个 translate(marginLeft, marginTop - windowTop) ——
        // 也就是绘制时那一次变换。此前这里直接把 event.x / event.y 当成文档坐标，
        // 于是写了字会偏出页边距那么多，锚定也会挂到偏上两行的位置。
        // 换算必须与 onDraw 里的 translate 严格互逆（toDocX / toDocY）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 不让父容器（Compose 的滚动/翻页手势）把这一笔抢走。
                parent?.requestDisallowInterceptTouchEvent(true)
                // 跟住**那一支笔**，而不是"第 0 号指针"。手掌先落屏时，
                // 0 号是手掌、笔在 1 号，跟错了这一笔就停在原地（见 PenPointers）。
                activePointerId = PenPointers.initialId(event)
                val index = event.findPointerIndex(activePointerId).coerceAtLeast(0)
                // 手写笔走无缓冲派发：这是低延迟路径的关键一步（方案 §6.2）。
                // 手指不走这条路：手指事件需要经过系统的滑动/长按判定，
                // 强行无缓冲会让"手指划线"与"手指滚动"的仲裁变得不可预测。
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
                // 新的指针按下。只有一种情况要理它：来的是笔，而当前跟的不是笔。
                if (PenPointers.isStylus(event, event.actionIndex)) {
                    val current = event.findPointerIndex(activePointerId)
                    if (current < 0 || !PenPointers.isStylus(event, current)) {
                        switchToStylus(event, controller, event.actionIndex)
                    }
                }
                // 一律返回 true（消费掉）：还回 false 会让互操作层认为本 View
                // 不再消费这一串事件，滚动模式下 verticalScroll 可能就此接管。
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                // 跟的指针已经不在这一串事件里（id 复用或系统吞了离开事件）：
                // 这一帧没有可用的坐标，跳过比拿别人的坐标画一笔好。
                if (index < 0) return true
                // history 里的点也必须收：高采样率手写笔一帧可能有几十个点，
                // 只取当前位置会把一笔画成折线。
                // （不做"脏区刷新"：带区域的 invalidate 自 API 21 起矩形就被系统
                // 忽略，重绘范围由 SurfaceFlinger 按实际损伤决定。真正的省法是
                // 静态层 RenderNode 缓存，那已经在 onDraw 里做了。）
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
                // 笔抬起来、手掌还压着屏 —— 这就是"写快了断笔"最常见的一种：
                // 此前这条落进 default，于是**不会**触发落盘，这一笔白写。
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    if (controller.isWriting) finishStroke(event, controller)
                    activePointerId = PenPointers.NONE
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                // 没有在写就不要"补一个点再落盘"：抬笔这一下可能只是手掌，
                // 而笔（这一笔）已经在 POINTER_UP 里结束了。
                if (controller.isWriting) finishStroke(event, controller)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统任何一次取消（边缘返回手势、通知横幅、托盘判定）都在这里。
                // 此前直接 cancelStroke()，把已经写出来的半截**无声抹掉** ——
                // 那正是最典型的"笔迹中断"。
                // 现在：点数够就当作抬笔落盘。用户看到的是"字还在"，
                // 我们拿到的是 INKLEAF_INK_CANCEL 这条线索（点数、原因）。
                val points = controller.inProgressPointCount
                if (points >= 2) {
                    Log.i(TAG, "$TAG_CANCEL 取消时按抬笔处理 points=$points view=doc")
                    controller.endStroke()
                    latency.reportOnStrokeEnd()
                } else if (points > 0) {
                    Log.i(TAG, "$TAG_CANCEL 取消，点数不足没有落盘 points=$points view=doc")
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

    /**
     * 抬笔：把活动指针的当前位置补上，然后交给控制器落盘。
     *
     * 单独一个方法是因为有两条来路：[ACTION_UP]（最后一个指针抬起）与
     * [ACTION_POINTER_UP]（笔抬起、别的指针还压着）。两者要做的事一模一样。
     */
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
     * 手掌先落屏、笔后到：换到笔上，并且**丢掉手掌那一段**。
     *
     * 为什么不接着用已有的点：那些点的坐标来自掌心，接到笔尖上会在屏幕上多出
     * 一条"从掌心划到笔尖"的直线 —— 比少一截更难看，而且它会被真的落库。
     * 手掌压屏本来就不该留下墨，丢掉的正是它。
     *
     * 这条规则只对"当前跟的不是笔"生效（调用处已判），所以用手指正常书写时
     * 再碰到笔尾也不受影响 —— 那种情况下换过去同样是对的。
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

    /**
     * 套索：画一个圈选中笔迹，然后把它拖到别处（S1）。
     *
     * 两拍，靠"按下时有没有已经选中东西、并且按在它的框里"区分：
     *
     * 1. **画圈**：按下 → 拖出一条自由路径 → 抬笔自动闭合，交给 [onLasso]。
     *    路径同时记两份（视图坐标用来画跟手的线，文档坐标用来做命中判定）——
     *    与落笔那一段同一个理由：触摸给的是视图坐标，笔迹活在文档坐标里。
     * 2. **拖动**：已经有选中、并且按在选中的包围盒里 → 这一串事件是"把它挪过去"。
     *    松手才回调 [onMove]，中间只改 [moveDx] / [moveDy] 让画面跟着走 ——
     *    每移动一个像素就重新锚定一次既慢又会让撤销栈爆炸。
     *
     * 按在别处（不在选中框里）就重新画圈：换一次选择不应该需要先取消。
     */
    private fun handleLasso(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // 套索是"跟手"最敏感的手势：虚线要贴着指尖走。无缓冲派发能省掉
                // 系统缓冲区那一帧延迟；这里**手指也走**（书写只给笔走），因为套索
                // 已经 disallowIntercept，不必再和滚动/长按抢这一串事件。
                if (useUnbuffered) requestUnbufferedDispatch(event)
                val docX = toDocX(event.x)
                val docY = toDocY(event.y)
                draggingSelection = selection.isNotEmpty() && selectionBounds(docX, docY)
                if (draggingSelection) {
                    dragStartX = docX
                    dragStartY = docY
                    moveDx = 0f
                    moveDy = 0f
                } else {
                    // 按在别处就是"重新画一个圈"，上一次的预览到此为止 ——
                    // 留着它会与新画的圈同时出现在屏幕上。
                    moveDx = 0f
                    moveDy = 0f
                    lassoPathShape.reset()
                    lassoPathShape.moveTo(event.x, event.y)
                    lassoDoc.clear()
                    lassoDoc += docX
                    lassoDoc += docY
                    lastLassoX = event.x
                    lastLassoY = event.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingSelection) {
                    // 拖动不收历史点：预览只关心"现在在哪"。
                    // （整屏 invalidate：重绘范围由系统按实际损伤决定，见书写 MOVE 的注释。）
                    moveDx = toDocX(event.x) - dragStartX
                    moveDy = toDocY(event.y) - dragStartY
                    invalidate()
                } else {
                    // 历史点全收（快速移动时一帧多个位置），但只**追加**进 Path，
                    // 不再每帧 reset 重建 —— 点数随手势时长增长，重建是 O(n²)。
                    // 抽稀：离上一个已收的点太近的直接丢（见 lastLassoX 的注释）。
                    for (i in 0 until event.historySize) {
                        val x = event.getHistoricalX(i)
                        val y = event.getHistoricalY(i)
                        if (acceptLassoPoint(x, y)) {
                            lassoPathShape.lineTo(x, y)
                            lassoDoc += toDocX(x)
                            lassoDoc += toDocY(y)
                        }
                    }
                    val x = event.x
                    val y = event.y
                    if (acceptLassoPoint(x, y)) {
                        lassoPathShape.lineTo(x, y)
                        lassoDoc += toDocX(x)
                        lassoDoc += toDocY(y)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (draggingSelection) {
                    draggingSelection = false
                    val dx = moveDx
                    val dy = moveDy
                    // 位移太小就不算一次移动：手指按上去抖一下不该触发重新锚定，
                    // 那会白写一批记录、还把撤销窗口占掉。
                    if (kotlin.math.abs(dx) > 1f || kotlin.math.abs(dy) > 1f) {
                        // 预览**故意不清**：真正落库是异步的（要重新锚定），
                        // 这里一撤，中间那几帧会把墨弹回原位置，看起来像抖了一下。
                        // 由外面写完之后用 setSelection(emptySet()) 一并收掉。
                        onMove?.invoke(dx, dy)
                    } else {
                        moveDx = 0f
                        moveDy = 0f
                    }
                } else {
                    val polygon = lassoDoc.toFloatArray()
                    lassoDoc.clear()
                    lassoPathShape.reset()
                    if (polygon.size >= 6) onLasso?.invoke(polygon)
                }
                // 抬笔一定整屏：选择集要变、虚线要撤、预览要交接给真实落库，
                // 这些都发生在这一帧之后，脏区算不准也不必算。
                invalidate()
                return true
            }

            // 多指也算"断了"：套索是单指手势，第二根手指按上来之后 event.x 会在
            // 指针索引重排时突然跳到另一根手指上，画出来的圈会莫名其妙拐一个弯。
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                // 中途断了就是没画完：清掉，不回调。半个圈交给命中判定
                // 会选中一批用户根本没打算选的东西。
                lassoDoc.clear()
                lassoPathShape.reset()
                draggingSelection = false
                moveDx = 0f
                moveDy = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    /**
     * 采样抽稀：这个点要不要收进圈里。
     *
     * 与上一个**已收**的点距离小于 [MIN_LASSO_STEP] 就丢。收到就更新
     * [lastLassoX] / [lastLassoY]。命中判定与显示用同一条折线，
     * 所以"显示抽稀"同时就是"命中抽稀"，两者永远不会不一致。
     */
    private fun acceptLassoPoint(x: Float, y: Float): Boolean {
        val dx = x - lastLassoX
        val dy = y - lastLassoY
        if (dx * dx + dy * dy < MIN_LASSO_STEP * MIN_LASSO_STEP) return false
        lastLassoX = x
        lastLassoY = y
        return true
    }

    /** ([docX], [docY]) 是否落在选中笔迹的整体包围盒里（含一点余量，好按）。 */
    private fun selectionBounds(docX: Float, docY: Float): Boolean {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (stroke in committedStrokes) {
            if (stroke.strokeId !in selection) continue
            val b = stroke.pointBounds()
            if (b.left < left) left = b.left
            if (b.top < top) top = b.top
            if (b.right > right) right = b.right
            if (b.bottom > bottom) bottom = b.bottom
        }
        if (left == Float.MAX_VALUE) return false
        val pad = 24f
        return docX >= left - pad && docX <= right + pad &&
            docY >= top - pad && docY <= bottom + pad
    }

    /**
     * 橡皮：按下 → 抬起（或拖动）时按命中判据擦掉整笔。
     *
     * 为什么拖动也要擦：用户对橡皮的预期是"划过去就掉"，只认点击会让
     * 一段长划线必须点十几次。连续擦除用 [lastErasedId] 去重 —— 同一条笔迹
     * 在一次拖动里只回调一次，否则手指停在同一笔上会反复删除同一个 id。
     */
    private fun handleErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
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
                // "这一下没擦到东西"必须说出来：橡皮最容易让人怀疑的点就是
                // "我明明点在线上"—— 静默什么都不发生，用户只会重复点。
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

    /**
     * 这一次触摸是不是手写笔。
     *
     * `TOOL_TYPE_ERASER` 也算：那是笔尾的橡皮头，用户心里的"这支笔"的一部分。
     * 不把它算进来，开着"只用手写笔"时用笔尾擦不掉任何东西。
     *
     * [MotionEvent.getActionIndex] 而不是固定 0 号指针：多指时按下的是第几根手指
     * 由 actionIndex 给出，而"手指"与"笔"可能同时在屏上（手掌压着屏写字）。
     */
    private fun isStylusEvent(event: MotionEvent): Boolean =
        PenPointers.isStylus(event, event.actionIndex)

    /**
     * 这一下落在翻页区（左右各 1/3）吗。
     *
     * 这个 1/3 必须与界面层的翻页热区**完全一致**（见 `ReflowablePagedBody`
     * 里的 `detectTapGestures`）—— 不一致的话，手指落在"看起来是热区"的地方
     * 却不翻页，或者落在"看起来不是热区"的地方反而翻了页。
     */
    private fun inPageTurnZone(x: Float): Boolean {
        if (width <= 0) return false
        return x < width / 3f || x > width * 2f / 3f
    }

    private fun eraseAt(viewX: Float, viewY: Float) {
        // 容差按 dp 换算：同一个"手指宽度"在任何密度下都该是同一个物理距离。
        val tolerance = StrokeHitTest.DEFAULT_TOLERANCE_DP * resources.displayMetrics.density
        val hit = StrokeHitTest.nearest(
            strokes = committedStrokes,
            x = toDocX(viewX),
            y = toDocY(viewY),
            tolerancePx = tolerance,
        )
        if (hit == null) {
            return
        }
        if (hit.strokeId == lastErasedId) return
        lastErasedId = hit.strokeId
        erasedSomething = true
        onErase?.invoke(hit)
    }

    override fun performClick(): Boolean {
        // 覆写以配合无障碍：本 View 自身没有点击语义，但消费了触摸事件，
        // 不覆写会让 lint 报 ClickableViewAccessibility。
        return super.performClick()
    }

    // 视图坐标 → 文档坐标。与 onDraw 里的 translate 严格互为逆运算。
    private fun toDocX(viewX: Float): Float = viewX - (layout?.metrics?.marginLeftPx ?: 0f)

    private fun toDocY(viewY: Float): Float =
        viewY - (layout?.metrics?.marginTopPx ?: 0f) + windowTopPx

    companion object {
        /**
         * 静态层是否录进 `RenderNode`。
         *
         * 真机实测（25079RPDCC，一屏正文、页上无已落盘笔迹、60 点一笔，各跑 3~4 次）：
         *
         * | 版本 | 输入→绘制 p50 | 单帧绘制 p50 |
         * | --- | --- | --- |
         * | A（每帧重建整条笔迹路径、无静态层） | 8ms | 未埋点 |
         * | B（增量湿墨 + 形状缓存，静态层关） | 8ms | 2.8ms |
         * | B 全量（静态层开） | 9ms | 0.18ms |
         *
         * 两列讲的是相反的故事，决定权在第二列：**单帧绘制省了 2.6ms（15 倍）**，
         * 代价是 p50 多 1ms（每帧一次显示列表重放的固定开销）。
         *
         * 第一列看不出收益，恰恰是因为 2.8ms 还塞得进 16.6ms 的帧预算 ——
         * 现在是"不卡"，但那 2.8ms 已经吃掉预算的 17%，页上笔迹一多、正文一长就没了。
         * 用 1ms 换 2.6ms，净赚，而且余量与页面内容脱钩。
         *
         * 实测见 `design/M20-书写实时性与断笔方案.md` §9。哪天在极简页面上量到
         * p50 明显变差，把这里改回 `false` 即可 —— 关掉不影响功能，
         * 软件画布走的本来就是同一条直接画的路。
         */
        private const val USE_STATIC_LAYER = true

        /**
         * 搜索命中的默认高亮色：半透明琥珀。
         *
         * 半透明是必须的（不能用不透明色块盖住字），琥珀是在浅色与深色主题下
         * 都还看得清字的少数几个选择之一。
         */
        const val DEFAULT_HIGHLIGHT_COLOR: Int = 0x66FFD54F.toInt()

        /**
         * "这张纸算深色吗"的判定与阈值都在 [InkStrokeRenderer] 里。
         *
         * 拿过来而不是各写一份：屏幕、导出的 PNG、笔记清单里的缩略图必须给出
         * **同一个答案**，而这类不一致两边都画得出来、只是不一样 —— 最难查的一种。
         */
        private fun isDarkPaper(argb: Int): Boolean = InkStrokeRenderer.isDarkPaper(argb)

        /** 取消的埋点标签。它回答的是"这一笔为什么断" —— 见 onTouchEvent 的 CANCEL 分支。 */
        const val TAG_CANCEL: String = "INKLEAF_INK_CANCEL"

        /** 套索虚线脏区的固定余量：线宽 + 虚线 + 抗锯齿，12px 足够。 */
        /** 圈的两个相邻采样点之间的最小步长（px）。小于它的点直接丢弃。 */
        private const val MIN_LASSO_STEP = 1.5f

        private const val TAG = "INKLEAF_INK"
    }
}
