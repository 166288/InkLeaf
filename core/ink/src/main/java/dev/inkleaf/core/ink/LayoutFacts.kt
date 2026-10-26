package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.anchoring.InkSemanticsClassifier
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.ReflowableTextLayout
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.Selector

/**
 * 一次排版所提供的、锚定与渲染所需要的**全部事实**。
 *
 * 这是一个刻意做窄的接口：它把"笔迹层需要知道的关于文档的一切"收成一张短清单，
 * 于是笔迹层不持有 `DocumentSession`、不持有 `StaticLayout`、不自己去算任何坐标。
 * 方案 §8.4 要的"笔迹与文字不可能错位"落到代码上就是这个接口 ——
 * 笔迹层能用的几何只有 [lines]，而 [lines] 在重排格式下与绘制用的是同一份
 * `StaticLayout`、在固定版式下与页面位图用的是同一套页坐标（见 `PdfDocumentSession`）。
 *
 * 反过来说：**凡是没有从这个接口进来的几何量，笔迹层都不许用**。
 * 加一个字段之前先问"它能不能从 [lines] 推出来"——能推的就不要加。
 * （只有块这一层的两个方向 —— [absoluteRangeOf] / [structuralOfRange] —— 推不出来：
 * 一条**区间**标注不是任何一个行盒，它的位置就是链里的块内偏移。）
 */
interface LayoutFacts {

    /** 归一化全文。锚点链里的绝对字符偏移都是相对它的下标。 */
    val normalizedText: String

    /**
     * 当前排版的全部行盒。
     *
     * 顺序必须与排版顺序一致（自上而下）：锚定层用"点的纵坐标落在哪条行盒内"
     * 来决定归属，顺序错乱会让一笔挂到错误的行上。
     */
    val lines: List<LineBox>

    val profile: NormalizationProfile

    /** 源指纹。写入锚点链与解析校验都必须用同一个值。 */
    val fingerprint: String

    /**
     * 写入兜底选择器（ProgressHint）时用的全文进度 0..1。
     *
     * 这是**唯一**允许笔迹层使用的"百分比"：它只用于"找不回精确位置时把用户带到
     * 大概的地方"，绝不允许用它反推锚点（方案 §6.3.1 规则 3 明确禁止）。
     */
    val docRatio: Float

    /** 语义判定的几何常量（正文栏的横向范围）。 */
    val geometry: InkSemanticsClassifier.Geometry

    /** 某一行当前的文本。写入引文与解析校验必须用同一个来源。 */
    fun textOf(line: LineBox): String

    /**
     * 某一行**连同前后上下文**的文本窗口。校验引文前后缀用。
     *
     * 见 `AnchorResolver.resolve` 的 `contextOf`：前缀/后缀取自全文上下文，
     * 拿"只有这一行文字"去校验它们必然误判。
     */
    fun textWindow(line: LineBox): String

    /**
     * 该行文本在归一化全文里的绝对区间（闭区间）。
     *
     * 返回 null 表示取不到 —— 此时该段笔迹会被跳过并计数，而不是硬存一个会失效的锚点。
     */
    fun absoluteRangeOf(line: LineBox): IntRange?

    /**
     * 结构型选择器 → 归一化全文里的绝对区间（闭区间）。[absoluteRangeOf] 的另一种入参。
     *
     * 为什么要两个：一条**行**标注的链能对上某个行盒（走 [absoluteRangeOf] 的第一个重载），
     * 而一条**区间**标注（高亮）的链没有对应的行盒 —— 它自己那段偏移就是它的位置。
     * 少了这一条，高亮的"画在哪"就只能退回"它解到的那一整行"，
     * 于是用户标半句话，屏幕上会铺满一整行：这是对"我标了哪几个字"的谎报。
     */
    fun absoluteRangeOf(structural: Selector.Structural): IntRange?

    /**
     * 绝对区间 → 结构型选择器里的"块路径 + 块内偏移"（[absoluteRangeOf] 在块这一层的反向）。
     *
     * 存在的理由只有一个：高亮是**区间**标注，而链里存的是块内偏移。
     * 让每个调用点自己减 `block.absoluteStart` 也能算出来，但那是一处会悄悄错的地方 ——
     * 减错块之后得到的链依然"能解开"（落在块中某处的区间永远与某一行重叠），
     * 错误不会自己暴露，只会表现为"高亮标在了隔壁那一段上"。
     *
     * 区间两端都会被夹进**同一个块**：结构型选择器只有一个块路径，
     * 跨块的区间在第二个块里的那部分无处安放。跨块命中因此只标到第一个块的末尾，
     * 调用方应当知道这一点（见 `Highlights.kt`）。
     *
     * 返回 null 表示这个区间取不到块（文档为空、偏移越界、区间为空、夹完之后为空）。
     */
    fun structuralOfRange(range: IntRange): Selector.Structural?

    /**
     * 全文偏移 → 它所在的那一行。
     *
     * 用途只有一个：**手动重挂锚点**（"确认位置"）。用户是在搜索框里找到原来那句话的，
     * 搜索给的是**字符偏移**，而重挂要的是**行盒**。少了这一步，界面就只好自己
     * 去推算"偏移大概落在第几行"—— 那正是本工程已经吃过两次亏的错误模式
     * （两侧各判一遍，拼起来错，见 design/M3 记录 §15）。
     *
     * 规则见 [LineLookup.at]。
     *
     * 注意（Kotlin 接口委托的坑，写测试时当场撞到过）：用
     * `object : LayoutFacts by inner { ... }` 包一层时，**这个方法会连根转发给 `inner`**，
     * 在包装对象上改写 [lines] / [absoluteRangeOf] 影响不到它。只换字号、指纹、docRatio
     * 那类"与行盒无关"的字段是安全的（`:app` 里模拟"正文换了一版"就是这么用的）；
     * 一旦要改行盒，必须同时把这个方法也一起重写。
     */
    fun lineAtOffset(charOffset: Int): LineBox? = LineLookup.at(charOffset, lines, ::absoluteRangeOf)
}

/**
 * [LayoutFacts.lineAtOffset] 的实现本体。
 *
 * 抽成纯函数（只吃"行盒列表 + 取区间的方法"）不是为了好看，而是为了让边界能被直接测到：
 * "某些行取不到绝对区间"（空行）这类情形没法通过包一层 `LayoutFacts` 造出来 ——
 * 见 [LayoutFacts.lineAtOffset] 关于接口委托的说明。
 */
object LineLookup {

    /**
     * 先找绝对区间**包含** [charOffset] 的行（区间前闭后开，与全文偏移同一套口径）；
     * 都不包含（偏移落在块之间的换行符上、或落在空行上）时，取**字符距离**最近的一行 ——
     * 因此"落在换行符上"会命中紧邻的前一行（等距时取先出现的那一行）。
     *
     * 一行都取不到区间（全是空行）时返回 null：**不猜**。调用方据此告诉用户
     * "这里没有可挂的正文行"，而不是挂到某一行上再说。
     */
    fun at(charOffset: Int, lines: List<LineBox>, rangeOf: (LineBox) -> IntRange?): LineBox? {
        var nearest: LineBox? = null
        var nearestDistance = Int.MAX_VALUE
        for (line in lines) {
            val range = rangeOf(line) ?: continue
            val distance = when {
                charOffset < range.first -> range.first - charOffset
                charOffset > range.last -> charOffset - range.last
                else -> return line
            }
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = line
            }
        }
        return nearest
    }
}

/** [LayoutFacts] → [InkAnchorFactory.LineSource] 的适配。刻意写在这里，避免两处实现。 */
fun LayoutFacts.lineSource(): InkAnchorFactory.LineSource = object : InkAnchorFactory.LineSource {
    override fun textOf(line: LineBox): String = this@lineSource.textOf(line)
    override fun absoluteRangeOf(line: LineBox): IntRange? = this@lineSource.absoluteRangeOf(line)
}

/** [LayoutFacts] → [InkAnchorFactory.Document] 的适配。 */
fun LayoutFacts.document(): InkAnchorFactory.Document = InkAnchorFactory.Document(
    normalizedText = normalizedText,
    profile = profile,
    fingerprint = fingerprint,
    docRatio = docRatio,
)

/**
 * 两种版式共有的 [LayoutFacts]：**文本坐标**上的那些事。
 *
 * 重排格式（TXT / Markdown / EPUB）与固定版式（PDF）在这一层是完全一样的：
 * 归一化全文、块路径、行内区间、上下文窗口，全都是 `DocumentSession` 的契约。
 * 差别只有两件 —— 行盒从哪来、正文栏怎么取 —— 因此只有那两件由子类实现。
 *
 * 之所以抽出来而不是各写一份：`textWindow` 的上下文窗口宽度必须与
 * `AnchorChainFactory.DEFAULT_CONTEXT_CHARS` 对齐，这种"两处常量必须相等"的约定
 * 抄一遍就会漂一次（§15 记录过的错误模式），而漂了之后的症状是
 * "所有笔记都要确认" —— 看上去像锚点算法坏了，其实是这里多抄了一个数字。
 *
 * @param contextChars 上下文窗口在行的前后各取多少字符。必须 ≥
 *        `AnchorChainFactory.DEFAULT_CONTEXT_CHARS`（32），否则前缀/后缀会**恒定**不匹配，
 *        表现为"所有笔记都需确认"。取 64 留一倍余量。
 */
abstract class DocumentLayoutFacts(
    private val session: DocumentSession,
    override val docRatio: Float,
    private val contextChars: Int,
) : LayoutFacts {

    override val normalizedText: String = session.normalized.text

    override val profile: NormalizationProfile = session.profile

    override val fingerprint: String = session.fingerprint

    override fun textOf(line: LineBox): String = session.textOfLine(line)

    override fun absoluteRangeOf(line: LineBox): IntRange? =
        absoluteRangeOf(line.anchor.blockPath, line.anchor.startOffset, line.anchor.endOffset)

    override fun absoluteRangeOf(structural: Selector.Structural): IntRange? =
        absoluteRangeOf(structural.blockPath, structural.startOffset, structural.endOffset)

    /**
     * 块路径 + 块内偏移 → 绝对区间。两个 [absoluteRangeOf] 重载的**唯一**实现本体。
     *
     * 抽出来是为了"行"与"区间"两种链走同一条换算：抄第二份的话，
     * 两处夹取边界（块末尾、空区间）的写法迟早会分家，而分家之后
     * 症状是"高亮比行盒多半格"这种要靠眼睛发现的东西。
     */
    private fun absoluteRangeOf(blockPath: String, startOffset: Int, endOffset: Int): IntRange? {
        val blockIndex = DocumentSession.blockIndexFromPath(blockPath) ?: return null
        val block = session.blocks.getOrNull(blockIndex) ?: return null
        val start = (block.absoluteStart + startOffset)
            .coerceIn(block.absoluteStart, block.absoluteEnd)
        val end = (block.absoluteStart + endOffset)
            .coerceIn(start, block.absoluteEnd)
        // 空区间（例如零宽的行）不构成可引用文本：返回 null 让调用方明确跳过。
        return if (end <= start) null else start until end
    }

    override fun structuralOfRange(range: IntRange): Selector.Structural? {
        if (range.last < range.first) return null
        val start = range.first
        val end = range.last + 1
        if (start < 0 || end > normalizedText.length) return null

        // 偏移落在块之间的换行符上时，`blockIndexOfChar` 给的是**前一个**块
        // （二分查找取最后一个起点在它之前的块），与 `lineBoxes` 建行盒时的口径一致。
        val block = session.blocks.getOrNull(session.blockIndexOfChar(start)) ?: return null
        val localStart = (start - block.absoluteStart).coerceIn(0, block.span.length)
        val localEnd = (end - block.absoluteStart).coerceIn(localStart, block.span.length)
        if (localEnd <= localStart) return null

        return Selector.Structural(
            blockPath = block.blockPath,
            startOffset = localStart,
            endOffset = localEnd,
            // 基线相对位置取自区间起点所在的那一行：区间标注自己没有行局部坐标，
            // 但链里这个字段是行盒的形状的一部分（将来的装饰渲染要用），
            // 取不到行时退回 1.0（贴行盒底部），与 `Structural` 的默认值同义。
            baselineOffsetEm = lineAtOffset(start)?.anchor?.baselineOffsetEm ?: 1f,
        )
    }

    override fun textWindow(line: LineBox): String {
        val range = absoluteRangeOf(line) ?: return textOf(line)
        val from = (range.first - contextChars).coerceAtLeast(0)
        val to = (range.last + 1 + contextChars).coerceAtMost(normalizedText.length)
        return normalizedText.substring(from, to)
    }

    companion object {
        const val DEFAULT_CONTEXT_CHARS = 64
    }
}

/**
 * 重排格式的 [LayoutFacts]：行盒来自 [ReflowableTextLayout]（真实排版结果）。
 *
 * 这是"文档层"与"笔迹层"之间唯一的一处粘合，因此它的实现要尽量无脑：
 * 每一项都能在 `DocumentSession` / `ReflowableTextLayout` 上找到直接对应的东西，
 * 没有任何换算。凡是需要换算的地方都是 bug 的温床（§5.7）。
 */
class SessionLayoutFacts(
    session: DocumentSession,
    private val layout: ReflowableTextLayout,
    docRatio: Float,
    contextChars: Int = DocumentLayoutFacts.DEFAULT_CONTEXT_CHARS,
) : DocumentLayoutFacts(session, docRatio, contextChars) {

    /**
     * 行盒缓存。
     *
     * `lineBoxes()` 每次调用都会重新遍历全部段与行（对十万字的书是几万次循环），
     * 而笔迹层在一次落笔里要问好几遍"有哪些行"。缓存一份是必要的；
     * 生命周期绑定在本对象上，而本对象与一次具体的排版结果同生共死。
     */
    override val lines: List<LineBox> by lazy(LazyThreadSafetyMode.NONE) { layout.lineBoxes() }

    override val profile: NormalizationProfile = session.profile

    override val fingerprint: String = session.fingerprint

    /**
     * 正文栏。纵向取"整篇"，因为重排格式没有页眉页脚带（那是固定版式才有的东西）；
     * 横向用排版度量给的正文宽度，而不是行盒的墨迹盒 —— 后者对齐后的短行是窄的，
     * 会让"压在页边上"的判定抖动。
     */
    override val geometry: InkSemanticsClassifier.Geometry = InkSemanticsClassifier.Geometry(
        contentBox = Box(
            left = layout.metrics.marginLeftPx,
            top = 0f,
            right = layout.metrics.marginLeftPx + layout.metrics.contentWidthPx,
            bottom = layout.totalHeightPx.toFloat(),
        ),
    )
}

/**
 * 固定版式（PDF）的 [LayoutFacts]。
 *
 * 与重排格式的三点差别，每一点都是"固定"这两个字的直接后果：
 *
 *   1. 行盒来自文本层（[PdfDocumentSession.lineBoxes]），**不吃排版参数**：
 *      页面不会因为用户把应用字号调大而变宽，行盒也就不该动。
 *      [PageLayoutMetrics] 在这里只用到左边界（页的摆放位置），
 *      `contentWidthPx` / `contentHeightPx` 完全不参与 —— 它们描述的是"重排的容器"。
 *   2. 正文栏横向取**页宽**：PDF 的页边（扫描白边、书眉页码带）不是排版边距，
 *      拿它判"压在页边上"会得到一堆误判。落笔在页面之外仍然算页外（PINNED），
 *      这一条只依赖横向判据，因此在纵向取整篇不会引入抖动。
 *   3. [lines] 每建一次都要遍历全部页与行，所以与重排那份一样缓存一份。
 */
class PdfLayoutFacts(
    private val session: PdfDocumentSession,
    private val metrics: PageLayoutMetrics,
    docRatio: Float,
    contextChars: Int = DocumentLayoutFacts.DEFAULT_CONTEXT_CHARS,
) : DocumentLayoutFacts(session, docRatio, contextChars) {

    override val lines: List<LineBox> by lazy(LazyThreadSafetyMode.NONE) { session.lineBoxes(metrics) }

    override val geometry: InkSemanticsClassifier.Geometry = InkSemanticsClassifier.Geometry(
        contentBox = Box(
            left = metrics.marginLeftPx,
            top = 0f,
            right = metrics.marginLeftPx + session.maxPageWidthPx,
            bottom = session.stackHeightPx,
        ),
    )
}
