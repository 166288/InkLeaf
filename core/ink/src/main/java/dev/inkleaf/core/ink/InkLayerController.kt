package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 一笔锚定之后的落盘出口。
 *
 * 本模块只依赖这个接口，不依赖 `:core:data` —— 于是"笔迹怎么存"与"笔迹怎么画"
 * 互不牵连，而本模块的测试可以完全不拉起数据库。
 * 由 `:app` 用 `AnnotationRepository` 实现。
 */
interface InkSink {

    /**
     * 落盘**一段**笔迹。
     *
     * 一段 = 一条独立可解析的记录（自带完整锚点链），因为一笔跨行时必须切成多段。
     *
     * @param annotationId 由调用方生成，用于撤销。**不能**由实现自己生成：
     *        撤销是按标注 id 的批量操作，id 必须在写入成功之前就已经知道。
     * @return 是否成功。失败必须返回 false（不要抛异常吞掉），控制器会把这一笔
     *         留在"未落盘"状态并告诉用户，而不是假装写成功了。
     */
    suspend fun commit(annotationId: String, segment: InkAnchorFactory.Segment, brush: BrushSpec): Boolean

    /** 撤销：删除这些标注。返回实际删掉的条数。 */
    suspend fun revoke(annotationIds: List<String>): Int
}

/**
 * 一笔**已经抬笔、但墨迹还没从库里解析回来**的暂存。
 *
 * 存在的原因是一个结构性的空档：抬笔之后要走"锚定 → 逐段写库 → 整库重读 →
 * 全量解析 → 重组 → `submitInk`"这一长串，才轮到屏幕上有东西。此前的实现
 * 在抬笔的那一刻就把点清空了，于是这段时间里**根本没有任何可画的东西** ——
 * 用户说的"写完才显示"正是它：不是画得慢，是中间那段空白没有墨。
 *
 * 这里的点与正在书写时的那一份是同一个东西，只是归属换了一层：
 * 抬笔时把点**移交**给暂存（而不是丢掉），屏幕因此不需要知道"库里还没有它"。
 *
 * 撤下（[InkLayerController.acknowledgeCommitted]）的判据见那里的注释 ——
 * 关键点是"等到干墨真的到场"而不是"写完就算"。
 */
class PendingInk internal constructor(
    /** 与锚定请求用的同一个 id。它不进库（进库的是按行切出来的分段），只用于日志与去重。 */
    val strokeId: String,
    /** 文档坐标，x0,y0,… */
    val points: FloatArray,
    /** 与点数等长的压力序列。 */
    val pressures: FloatArray,
    val brush: BrushSpec,
) {
    /** 锚定与落盘是否已经有结论（成功或失败）。由后台线程写。 */
    @Volatile
    internal var settled: Boolean = false

    /** 结论是"没有存下来"。失败的暂存要立刻撤 —— 留着的是一条永远不会消失的幽灵。 */
    @Volatile
    internal var failed: Boolean = false

    /**
     * 干墨（或"库里有它、但这版画不出来"的结论）已经到场。
     *
     * 只由 `acknowledgeCommitted` 写、只在主线程读写，所以不需要 `@Volatile`：
     * 它不像 [settled] 那样要跨线程发布。
     */
    internal var acked: Boolean = false

    val pointCount: Int get() = points.size / 2

    /** 整笔均值压力。落盘之后不会再变，所以只算一次（每帧都要拿它换笔宽）。 */
    internal val meanPressure: Float by lazy { StrokeGeometry.meanPressure(pressures) }

    /**
     * 这一笔的形状。只算一次：暂存里的点不会再变，而它每帧都要被画。
     *
     * 与已落盘的笔迹走同一个 [StrokeGeometry.polyline] —— 抬笔那一帧屏幕上
     * 那条线因此一个像素都不变，不会出现"抬笔之后字变细/变位置"。
     */
    internal fun path(): android.graphics.Path {
        cachedPath?.let { return it }
        return StrokeGeometry.polyline(points, android.graphics.Path()).also { cachedPath = it }
    }

    private var cachedPath: android.graphics.Path? = null
}

/**
 * 笔迹层控制器：把"落笔 → 抬笔"变成"锚定 → 落盘"，并维护撤销/重做。
 *
 * ## 它刻意不做的事
 *
 * - **不做坐标换算**：输入是文档坐标（渲染层反算出来的），输出交给
 *   [InkAnchorFactory]。中间没有第三套坐标。
 * - **不做绘制**：它只提供 [inProgressPoints] 给渲染器。绘制路径只有一条
 *   （`DocumentInkView.onDraw`），控制器不得碰 Canvas。
 * - **不做锚点解析**：解析在 [InkResolver]，因为它的输入是"库里的东西 + 当前排版"，
 *   与"正在写的这一笔"没有关系。
 *
 * ## 线程
 *
 * 采集方法（[beginStroke] / [extendStroke] / [endStroke]）必须在主线程调用
 * （它们直接读写渲染层要用的点数组）。锚定与落盘在 [worker] 上做，
 * 完成后回调 [onChanged] —— 回调也在 [worker] 线程，实现方自己负责切回主线程
 * （`DocumentInkView` 的实现用 `post`）。
 */
class InkLayerController(
    private val scope: CoroutineScope,
    private val sink: InkSink,
    /** 取当前排版事实。排版未就绪时返回 null —— 此时落笔会被明确丢弃并告知。 */
    private val facts: () -> LayoutFacts?,
    /** 数据变了（落盘成功 / 撤销 / 重做）时回调，实现方据此重新解析并重绘。 */
    private val onChanged: () -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val idFactory: () -> String = { "ink-" + UUID.randomUUID().toString() },
) : WetStroke {

    /** 当前笔刷。切换笔刷是纯 UI 状态，不需要重建控制器。 */
    var brush: BrushSpec = BrushSpec.DEFAULT_PEN

    /**
     * 最近一次"没能落盘"的原因，供界面显示一行提示。
     *
     * 存在的理由：丢弃一笔墨迹（排版未就绪、点数不足、拿不到引文）如果完全静默，
     * 用户看到的就是"写了但没显示，再写一次又有了"这种无法复现的怪事。
     */
    var lastNotice: String? = null
        private set

    private val points = ArrayList<Float>(256)
    private val pressures = ArrayList<Float>(128)

    /**
     * 已抬笔、等干墨到场的笔。只由主线程增删（见 [maintainPending] 的注释）。
     *
     * 顺序是落笔顺序：先抬笔的先画在下面，与"后来写的盖住先写的"一致。
     */
    private val pending = ArrayList<PendingInk>(2)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 正在书写的一笔（文档坐标，x0,y0,…）；点数不足两点时为 null。 */
    val inProgressPoints: FloatArray? get() = if (points.size >= 4) points.toFloatArray() else null

    val inProgressPressures: FloatArray? get() = if (points.size >= 4) pressures.toFloatArray() else null

    /** 正在书写的一笔用的笔刷；没有在写时为 null。 */
    val inProgressBrush: BrushSpec? get() = if (points.isEmpty()) null else brush

    val isWriting: Boolean get() = points.isNotEmpty()

    /** 正在书写的点数（不是坐标个数）。取消时要按它判断"值不值得落盘"。 */
    val inProgressPointCount: Int get() = points.size / 2

    /**
     * 这一笔的流水号：每 [beginStroke] 一次加一。
     *
     * 渲染层靠它判断"还是同一笔吗"—— 是就只追加新点，不是就把增量路径清掉重来。
     * 没有它，渲染层只能靠"点数变少了"来猜，而"撤销后又写一笔同样长的"就猜错了。
     */
    private var serial: Int = 0

    /** 压力累加和：让均值压力是 O(1)，而不是每帧把整条压力序列加一遍。 */
    private var pressureSum: Float = 0f

    // ── WetStroke（渲染层每帧来取，见 InkStrokeRenderer.drawWet）──────────────

    override val strokeSerial: Int get() = serial

    override val wetPointCount: Int get() = points.size / 2

    override val wetMeanPressure: Float
        get() = if (points.isEmpty()) 1f else (pressureSum / (points.size / 2f)).coerceIn(0f, 1f)

    override val wetBrush: BrushSpec get() = brush

    override fun xAt(index: Int): Float = points[index * 2]

    override fun yAt(index: Int): Float = points[index * 2 + 1]

    /**
     * 已经抬笔、但还没被干墨接管的笔。渲染层照它画，与 [inProgressPoints] 使用同一套字号估计。
     *
     * 只读快照：调用方通常在 `onDraw` 里遍历，返回内部列表会在遍历途中被改。
     */
    val pendingStrokes: List<PendingInk> get() = pending.toList()

    /**
     * 干墨到场的回执：`submitInk` 把新解析出来的笔迹交给渲染层时调它。
     *
     * ## 为什么撤暂存的时机是"这里"而不是"落盘成功"
     *
     * 落盘成功只意味着"库里有了"，而屏幕上那一条要等 `listByDoc → 解析 → 重组 →
     * submitInk` 走完才真的画出来。若在落盘那一刻就撤暂存，中间这几十上百毫秒
     * 屏幕上又会空一次 —— 那正是我们要消灭的空档。
     * 这与 WPF `DynamicRenderer` 的做法同一条道理（动态层等主层渲染完成之后才清），
     * `androidx.ink` 的 `InProgressStrokesView` 也是等调用方画好干墨再
     * `removeFinishedStrokes`。
     *
     * ## 判据是"这一版里有没有它"，不是"提交过几版"（M21）
     *
     * 这里原先记一个提交计数：结论之后只要再提交过一版，就把暂存撤掉。那条规则
     * 默认了"提交的那一版一定包含这一笔"，而这个假设在真机上是错的 ——
     * `submitInk` 由 Compose 的 `update` 块调用，任何一次重组（点一下书写开关、
     * 顶栏自动隐藏、状态行到期消失）都会拿**当时手上那一版**再提交一次；
     * 如果刷新还没跑完，那一版里根本没有刚抬笔的圈。于是：
     * 抬笔 → 圈还在暂存里画着 → 用户切一下书写开关 → 暂存被这次"陈旧提交"撤掉，
     * 而干墨那一版也不含它 → 圈凭空消失，等下一次解析回来又自己出现。
     * 笔画越长、切出来的段越多（圈是最典型的一类），落盘窗口越宽，越容易撞上。
     *
     * 所以回执必须带上**内容**：只有解析结果里确实有这一笔（[InkCoverage]），
     * 才允许撤。宁可多留一帧湿墨，也不能在"库里有了、屏幕上还没有"的缝里把它撤掉。
     */
    fun acknowledgeCommitted(coverage: InkCoverage) {
        if (pending.isEmpty()) return
        for (entry in pending) {
            if (!entry.settled || entry.failed) continue
            if (entry.strokeId in coverage.drawnRootIds) {
                // 干墨真的在屏幕上了。
                entry.acked = true
            } else if (entry.strokeId in coverage.storedRootIds) {
                // 库里确实有它，而这一版解析**明确画不出来**：撤掉湿墨，
                // 让状态行去解释（"有 N 条笔迹暂时画不出来"）。
                // 留着湿墨会让界面自相矛盾：面板说画不出来，屏幕上却明摆着一份。
                entry.acked = true
            }
            // 两者都没有：这一版还没轮到它（库读可能早于落盘），暂存必须留着。
        }
        maintainPending()
    }

    /**
     * 清掉不需要再画的暂存。**只能主线程调用**（`pending` 不是并发容器）。
     *
     * 两条撤除理由：
     *   - 失败了：库里没有它，留着就是一条永远不消失的幽灵；
     *   - 有结论且这一版解析已经交代过它（画出来了，或明确画不出来）。
     *
     * 由渲染层在 `onDraw` 开头调：那一帧正好是"要不要再画它"的判断点，
     * 早于此没有意义，晚于此就多画一帧。
     */
    fun maintainPending() {
        if (pending.isEmpty()) return
        pending.removeAll { it.settled && (it.failed || it.acked) }
    }

    /**
     * 丢掉全部暂存，不落盘。
     *
     * 只有一个调用场景：PDF 翻页。暂存里的点是**上一页**的坐标，画到新页上
     * 就是"笔记跑到别的页"。这跟 `PdfInkPageView.submitPage` 里取消在写的那一笔同理。
     */
    fun discardPending() {
        pending.clear()
    }

    fun clearNotice() {
        lastNotice = null
    }

    fun beginStroke(x: Float, y: Float, pressure: Float) {
        points.clear()
        pressures.clear()
        pressureSum = 0f
        // 换笔的流水号：渲染层据此把增量路径清掉重来（否则会把上一笔的尾巴接过来）。
        serial++
        addPoint(x, y, pressure)
    }

    /**
     * 追加一个点。
     *
     * 未 [beginStroke] 就直接 [extendStroke] 是**允许**的：真实设备上
     * 触摸事件被父容器吃掉中间的 DOWN 是常见现象。此时把这一次当作起点，
     * 而不是丢弃 —— 丢弃的表现是"写着写着断了一截"。
     */
    fun extendStroke(x: Float, y: Float, pressure: Float) {
        if (points.isEmpty()) {
            lastNotice = null
        }
        addPoint(x, y, pressure)
    }

    fun cancelStroke() {
        points.clear()
        pressures.clear()
        pressureSum = 0f
    }

    /**
     * 抬笔：锚定 + 落盘。
     *
     * 点数不足两点时**什么都不做**（不是错误：笔尖点一下也会产生 DOWN/UP）。
     *
     * ## 抬笔时点不消失
     *
     * 这些点从这里移交给 [pending]，而不是被清掉 —— 后者会让屏幕在"库里有了"
     * 之前的那一长串步骤里没有任何墨可画（用户报的"写完才显示"）。
     * 交接是原子的：同一帧里 `points` 清空、`pending` 多一条，`onDraw` 画的是两者之和，
     * 因此屏幕上不可能出现空档。
     */
    fun endStroke() {
        if (points.size < 4) {
            cancelStroke()
            onChanged()
            return
        }
        val docPoints = points.toFloatArray()
        val docPressures = pressures.toFloatArray()
        val usedBrush = brush
        points.clear()
        pressures.clear()
        pressureSum = 0f

        val snapshot = facts()
        if (snapshot == null) {
            // 没有排版就没有坐标系。此时"先把点存下来以后再锚"是错的方向：
            // 文档坐标会随排版变化，存下来的是将来必然失效的坐标。
            lastNotice = "排版还没准备好，这一笔没有存下来（请稍后再写）。"
            onChanged()
            return
        }

        val strokeId = idFactory()
        val entry = PendingInk(strokeId, docPoints, docPressures, usedBrush)
        pending.add(entry)

        scope.launch {
            val result = withContext(worker) {
                InkAnchorFactory.anchor(
                    InkAnchorFactory.Request(
                        strokeId = strokeId,
                        docPoints = docPoints,
                        docPressures = docPressures,
                        brushId = usedBrush.name,
                        lines = snapshot.lines,
                        geometry = snapshot.geometry,
                        source = snapshot.lineSource(),
                        document = snapshot.document(),
                        // 荧光笔**只**跟随文字（M25 §3 S2"不参与覆盖文字"）：它标的是
                        // "这一段"，不是"页面上这一块"。让它按普通笔的判定走，
                        // 横向长笔画会被判成 PINNED，然后被 PinnedPlacement 挪到
                        // 行旁边的空白处避让文字 —— 标亮就变成了"旁边多了一条黄杠"。
                        //
                        // 固定 FOLLOWER 顺带满足模型不变量：FOLLOWER 的 frozenMatrix
                        // 必须是 null，而工厂正是按 semantics 决定它的。
                        semanticsOverride = if (usedBrush.overprint) {
                            InkReflowSemantics.FOLLOWER
                        } else {
                            null
                        },
                    ),
                )
            }
            if (result == null) {
                lastNotice = "这一笔的位置算不出来，没有存下来。"
                settle(entry, failed = true)
                return@launch
            }
            val segments = result.segments
            if (segments.isEmpty()) {
                lastNotice = if (result.skippedSegments > 0) {
                    "这一笔落在了拿不到文字的行上，没有存下来。"
                } else {
                    "这一笔没有可锚定的内容，没有存下来。"
                }
                settle(entry, failed = true)
                return@launch
            }

            val annotations = ArrayList<String>(segments.size)
            for (segment in segments) {
                val annotationId = idFactory()
                if (sink.commit(annotationId, segment, usedBrush)) {
                    annotations += annotationId
                }
            }
            if (annotations.isEmpty()) {
                lastNotice = "写入失败，这一笔没有存下来。"
                settle(entry, failed = true)
                return@launch
            }
            undoStack.addLast(Entry(annotations, segments, usedBrush))
            redoStack.clear()
            lastNotice = if (result.droppedSegments > 0) {
                "这一笔太长，有 ${result.droppedSegments} 段被丢弃。"
            } else {
                null
            }
            settle(entry, failed = false)
        }
    }

    /**
     * 给一笔暂存写下结论，然后通知界面。
     *
     * [PendingInk.failed] 先写、[PendingInk.settled] 后写：后者充当发布屏障，
     * 主线程读到 `settled == true` 时就一定读得到那个结论。
     */
    private fun settle(entry: PendingInk, failed: Boolean) {
        entry.failed = failed
        entry.settled = true
        onChanged()
    }

    /**
     * 撤销**一次落笔**（而不是一段）。
     *
     * 一笔跨行时会产生多条标注；撤销必须把它们当作一件事 ——
     * 用户的心智模型是"我刚写了一笔"，不是"我刚写了三段"。
     */
    fun undo() {
        // 撤销的是"最近一笔"，而那笔很可能还留在暂存里等干墨。不清掉它，
        // 用户会看到"撤销了但字还在"——直到下一次提交才消失。
        pending.clear()
        val entry = undoStack.removeLastOrNull() ?: return
        scope.launch {
            val removed = sink.revoke(entry.annotationIds)
            if (removed > 0) {
                redoStack.addLast(entry)
                lastNotice = null
            } else {
                // 删除失败就把撤销栈恢复原状：栈与实际存储不一致会让后续撤销全部错位。
                undoStack.addLast(entry)
                lastNotice = "撤销失败，这一笔还在。"
            }
            onChanged()
        }
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        scope.launch {
            val annotations = ArrayList<String>(entry.segments.size)
            for (segment in entry.segments) {
                val annotationId = idFactory()
                if (sink.commit(annotationId, segment, entry.brush)) annotations += annotationId
            }
            if (annotations.isEmpty()) {
                redoStack.addLast(entry)
                lastNotice = "重做失败。"
            } else {
                undoStack.addLast(Entry(annotations, entry.segments, entry.brush))
                lastNotice = null
            }
            onChanged()
        }
    }

    private fun addPoint(x: Float, y: Float, pressure: Float) {
        points.add(x)
        points.add(y)
        // 压力下限 0.01 而不是 0：0 压力会让笔宽退化成一条看不见的线，
        // 而"看不见"与"没写"在用户眼里是同一件事。
        val p = if (pressure.isFinite()) pressure.coerceIn(0.01f, 1f) else 1f
        pressures.add(p)
        // 均值压力每帧都要用（换笔宽），所以在这里累加，而不是每帧遍历。
        pressureSum += p
    }

    /** 一次落笔产生的标注集合。重做要能原样再写一遍，所以段本身也要留着。 */
    private class Entry(
        val annotationIds: List<String>,
        val segments: List<InkAnchorFactory.Segment>,
        val brush: BrushSpec,
    )
}
