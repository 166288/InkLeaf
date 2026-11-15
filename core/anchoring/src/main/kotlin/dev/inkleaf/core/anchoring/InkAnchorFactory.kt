package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.StrokeAnchor

/**
 * 笔迹锚定器：把"用户写下来的一笔"变成"可持久化、可重新解析的行级锚点"。
 *
 * 这是 L2 锚定从纸面走到代码的那一步。输入是**文档坐标**下的原始点
 * （渲染层唯一知道的东西），输出是**行局部 em 坐标**下的点 + 一条完整的
 * [dev.inkleaf.core.model.TextAnchorChain]。中间没有任何"猜"的环节：
 * 行盒来自真实排版（§5.7），em 换算来自行盒自己的字号（`LineBox.matrixInDoc`）。
 *
 * ## 为什么要按行切段
 *
 * 一笔可以横跨多行（圈一段话、画一条长下划线）。而锚定的单位是**行**：
 * "第 3 段第 2 行"这个坐标只有在"这一段的点确实属于第 2 行"时才成立。
 * 所以跨行笔迹必须切成多段，每段各自锚定 —— 这也是"行级锚定"这个名字的由来。
 * 段数上限 [MAX_SEGMENTS_PER_STROKE] 防止一次涂鸦产生上千个段，
 * 超出的部分被**明确丢弃并计数**（[Result.droppedSegments]），不静默。
 *
 * ## 已知边界（不装）
 *
 * 1. 落在**空行**上的笔迹拿不到 `TextQuote`（没有文字可引），因此它的锚点链
 *    只有结构型 + 进度兜底。这类锚点在重排后会被 [AnchorResolver] 判为
 *    `Orphaned` —— 这是诚实的：我们确实无法证实"这一笔还在原来那一行"。
 *    曲线救国（拿邻行文字当引文）会制造假阳性，比丢位置更糟。
 * 2. 行盒字号为 0 时 `docToEmSpace()` 不可逆，该段被跳过并计数。
 *    （真实排版里字号恒为正，这是防御而不是常态。）
 */
object InkAnchorFactory {

    /** 一次落笔最多切成多少段。超过的部分丢弃并计数。 */
    const val MAX_SEGMENTS_PER_STROKE = 64

    /** 少于这么多点的段视为噪声，并入相邻段（阈值取 2 = "至少一条线段"）。 */
    const val MIN_POINTS_PER_SEGMENT = 2

    /**
     * 行信息提供者。
     *
     * 两个方法都必须答得出来：行盒本身**不携带文本**（它是排版结果的产物），
     * 而锚点链需要"引文"来在重排后重新定位。取文本的方法与
     * [AnchorResolver.resolve] 的 `textOf` 是同一个，这样"写入时引用什么"
     * 与"解析时校验什么"永远是同一份文字 —— 否则锚点在写入的那一刻就已经坏了。
     */
    interface LineSource {

        /** 该行当前的文本。 */
        fun textOf(line: LineBox): String

        /**
         * 该行文本在**归一化全文**里的绝对字符区间（闭区间，`first..last` 都包含）。
         *
         * 返回 null 表示调用方无法确定这个区间。此时该段会被跳过并计数：
         * 没有绝对区间就构造不出引文，而"没有引文的锚点在重排后必然判孤儿"，
         * 存下来只会给用户制造一条假笔记。
         */
        fun absoluteRangeOf(line: LineBox): IntRange?
    }

    /** 文档级常量。这些在一次落笔里不会变，但每个段都需要。 */
    data class Document(
        val normalizedText: String,
        val profile: NormalizationProfile,
        val fingerprint: String,
        val docRatio: Float,
    )

    data class Request(
        val strokeId: String,
        /** 文档坐标下的点，x0,y0,x1,y1,… */
        val docPoints: FloatArray,
        val docPressures: FloatArray,
        val brushId: String,
        /** 候选行。调用方负责限定范围（当前章节 ± 1 章），这里不做全书扫描。 */
        val lines: List<LineBox>,
        val geometry: InkSemanticsClassifier.Geometry,
        val source: LineSource,
        val document: Document,
        /** 用户显式指定语义时以用户为准（§6.3.3 最后一行）。 */
        val semanticsOverride: InkReflowSemantics? = null,
    )

    /**
     * 一个已锚定的段。
     *
     * [chain] 与 [stroke] 一起构成一条**独立可解析**的记录：段自带完整锚点链，
     * 所以"一笔跨两行"不会退化成"一条笔记只能锚在一行上"。
     */
    data class Segment(
        val stroke: StrokeAnchor,
        val chain: dev.inkleaf.core.model.TextAnchorChain,
        /** 该段所属的行盒。渲染时用它做 em → 文档坐标的换算。 */
        val line: LineBox,
    )

    data class Result(
        val semantics: InkReflowSemantics,
        val verdict: InkSemanticsClassifier.Verdict,
        val bboxDoc: Box,
        val segments: List<Segment>,
        /** 因为拿不到绝对区间或字号退化而被跳过的段数。 */
        val skippedSegments: Int,
        /** 因为超过 [MAX_SEGMENTS_PER_STROKE] 而被丢弃的段数。 */
        val droppedSegments: Int,
    ) {
        val totalSegments: Int get() = segments.size + skippedSegments + droppedSegments
    }

    /**
     * 锚定一笔。
     *
     * @return null 表示**无法锚定**（点太少、点数为奇数、没有候选行）。
     *         调用方应当明确告诉用户"这一笔没能记下来"，而不是悄悄丢掉。
     */
    fun anchor(request: Request): Result? {
        val points = request.docPoints
        // 点必须是 x,y 成对。奇数长度意味着上游有 bug，这里宁可拒绝也不猜。
        if (points.size < 4 || points.size % 2 != 0) return null
        if (request.lines.isEmpty()) return null

        val bbox = bboxOf(points)
        val verdict = InkSemanticsClassifier.classify(bbox, request.lines, request.geometry)
        val semantics = request.semanticsOverride ?: verdict.semantics

        val runs = splitIntoRuns(points, request.lines)
        if (runs.isEmpty()) return null

        val segments = ArrayList<Segment>(minOf(runs.size, MAX_SEGMENTS_PER_STROKE))
        var skipped = 0
        var dropped = 0
        var index = 0

        for (run in runs) {
            if (index >= MAX_SEGMENTS_PER_STROKE) {
                dropped++
                continue
            }
            val line = request.lines[run.lineIndex]
            val absolute = request.source.absoluteRangeOf(line)
            val em = line.docToEmSpace()
            val count = run.count

            if (absolute == null || em == null) {
                skipped++
                continue
            }

            val localPoints = FloatArray(count * 2)
            val localPressures = FloatArray(count)
            for (k in 0 until count) {
                val source = (run.points.first + k) * 2
                localPoints[k * 2] = em.mapX(points[source], points[source + 1])
                localPoints[k * 2 + 1] = em.mapY(points[source], points[source + 1])
                localPressures[k] = request.docPressures.getOrElse(run.points.first + k) { 1f }
            }

            // ★ 冻结几何：PINNED / FREE 的含义是"贴在页面的这个位置上"，
            // 不随文本行移动（方案 §6.3.3）。它们照样存一份 em 坐标（重挂要用），
            // 但**同时**存下落笔那一刻的 em → 文档坐标变换：绘制时用它而不是用
            // "当前解析到的那一行"的变换，否则行被搬到别处时它们会跟着走 ——
            // 那就与"固定"相反了（这正是 M3 记录 §13 第 3 条的老账）。
            val frozenMatrix = if (semantics == InkReflowSemantics.FOLLOWER) null else line.matrixInDoc()

            val chain = requireNotNull(
                AnchorChainFactory.forLine(
                    target = line,
                    source = request.source,
                    document = request.document,
                ),
            ) {
                // 上面刚判过 absolute != null，走到这里说明"选行 → 链"这一处
                // 对"这一行有没有文字"的判据与这里不一致 —— 那是必须当场炸出来的分歧，
                // 不能靠一条兜底链盖过去（盖过去的症状是笔记悄悄挂在错误的行上）。
                "行 ${line.anchor.blockPath} 取得到绝对区间，却构造不出锚点链"
            }

            segments.add(
                Segment(
                    stroke = StrokeAnchor(
                        strokeId = segmentId(request.strokeId, index),
                        // build() 保证链里有结构型选择器（它总是第一个），这里取出来
                        // 单独存进 StrokeAnchor：数据库按行检索笔迹靠它，不必解链。
                        anchor = chain.structural!!,
                        semantics = semantics,
                        brushId = request.brushId,
                        localPoints = localPoints,
                        localPressures = localPressures,
                        frozenMatrix = frozenMatrix,
                    ),
                    chain = chain,
                    line = line,
                ),
            )
            index++
        }

        return Result(
            semantics = semantics,
            verdict = verdict,
            bboxDoc = bbox,
            segments = segments,
            skippedSegments = skipped,
            droppedSegments = dropped,
        )
    }

    /** 单段笔迹沿用调用方给的 id；多段时派生 `id#n`，保证每段 id 唯一（它是主键）。 */
    private fun segmentId(strokeId: String, index: Int): String =
        if (index == 0) strokeId else "$strokeId#$index"

    /**
     * 一段连续的点，以及它归属的行在 [Request.lines] 里的下标。
     *
     * 这两个下标**必须分开存**：点序列是扁平的（一个浮点数组），而"点属于哪一行"
     * 是另一层信息。合并成一个"段号"会让 `lines[段号]` 这种写法看起来合法 ——
     * 段号是**点**的下标，不是**行**的下标，取错就是一次越界或一行错挂。
     */
    private data class Run(val lineIndex: Int, val points: IntRange) {
        val count: Int get() = points.last - points.first + 1
    }

    /**
     * 把点序列按"落在哪一行"切成连续段。
     *
     * 取行规则：先看纵向是否落在某条行盒内；都不落在（页边空白、行盒之间的
     * 缝隙）则取纵向距离最近的一条。后者正是边注（PINNED）能挂上
     * "它旁边那一行"的原因 —— 边注从来不落在行盒里面。
     */
    private fun splitIntoRuns(points: FloatArray, lines: List<LineBox>): List<Run> {
        val n = points.size / 2
        val owner = IntArray(n) { assignLine(points[it * 2 + 1], lines) }

        val raw = ArrayList<Run>()
        var start = 0
        for (i in 1..n) {
            if (i == n || owner[i] != owner[start]) {
                raw.add(Run(owner[start], start..(i - 1)))
                start = i
            }
        }
        return mergeNoise(raw)
    }

    /**
     * 合并两种"假分段"：
     *   1. 点数不足 [MIN_POINTS_PER_SEGMENT] 的段是噪声（例如笔尖在行缝里抖了一下），
     *      并回前一段；
     *   2. 归属于**同一行**但被中间的点拆开的段（笔尖短暂越过行边界又回来），
     *      并成一段 —— 否则一笔会变成三段，渲染时看不出差别但存储与笔记列表会变脏。
     *
     * 合并后段的 `lineIndex` 取"被并进的那一段"的行：噪声点的归属本来就不确定，
     * 让它们跟着邻近的实段走，比给它们各自开一段更接近事实。
     */
    private fun mergeNoise(raw: List<Run>): List<Run> {
        val merged = ArrayList<Run>()
        for (run in raw) {
            val last = merged.lastOrNull()
            val tooSmall = run.count < MIN_POINTS_PER_SEGMENT
            val sameLine = last != null && last.lineIndex == run.lineIndex
            if (last != null && (tooSmall || sameLine)) {
                merged[merged.size - 1] = Run(last.lineIndex, last.points.first..run.points.last)
            } else {
                merged.add(run)
            }
        }
        // 首段若仍是噪声（没有"前一段"可并），并进后一段。
        if (merged.size > 1 && merged[0].count < MIN_POINTS_PER_SEGMENT) {
            val first = merged.removeAt(0)
            merged[0] = Run(merged[0].lineIndex, first.points.first..merged[0].points.last)
        }
        return merged
    }

    private fun assignLine(y: Float, lines: List<LineBox>): Int {
        for (i in lines.indices) {
            val rect = lines[i].rectDoc
            if (y >= rect.top && y < rect.bottom) return i
        }
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in lines.indices) {
            val rect = lines[i].rectDoc
            val distance = if (y < rect.top) rect.top - y else y - rect.bottom
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun bboxOf(points: FloatArray): Box {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var i = 0
        while (i < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
            i += 2
        }
        return Box(left, top, right, bottom)
    }
}
