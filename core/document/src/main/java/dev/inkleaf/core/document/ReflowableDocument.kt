package dev.inkleaf.core.document

import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.StaticLayout
import android.text.TextPaint
import dev.inkleaf.core.anchoring.NormalizedText
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.TocItem
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 页尾调整的最多迭代次数。
 *
 * 三条规则会互相触发（挪一行解掉孤行，又造出寡行），但每次迭代都只往回挪，
 * 所以两三轮必然收敛。上限存在的意义不是"算得更好"，而是**保证终止**：
 * 版面规则不值得为它冒一个死循环的风险。
 */
private const val MAX_BREAK_ADJUSTMENTS = 3

/**
 * 文档会话：一份"已解析、可排版、可锚定"的文档。
 *
 * 它是文档层的唯一对外接口。渲染层、锚点解析、笔记面板都只通过它取数据 ——
 * 这样"行盒从哪来""块路径怎么算"这类问题上永远只有一个答案。
 */
interface DocumentSession {

    val docId: String
    val kind: DocumentKind

    /** 该文档采用的归一化口径。它与锚点链里存的口径必须一致，否则锚点坐标系不同。 */
    val profile: NormalizationProfile

    /** 归一化全文。所有锚点偏移都是相对它的下标。 */
    val normalized: NormalizedText

    val blocks: List<TextBlock>
    val toc: List<TocItem>

    /** 源指纹。锚点解析的规则 1 靠它判断"这还是不是同一份文本"。 */
    val fingerprint: String

    /** 某个归一化字符偏移落在哪个块里。空文档返回 -1。 */
    fun blockIndexOfChar(offset: Int): Int

    /** 在某排版参数下取得（带缓存）排版结果。 */
    fun layout(metrics: PageLayoutMetrics, theme: DocumentTheme): ReflowableTextLayout

    /** 取某一行当前的文本。锚点解析的文本校验用（行盒本身不带文本）。 */
    fun textOfLine(line: LineBox): String

    /** 行盒按 [blockPath] 反查字号（em → px 换算必须有它）。 */
    fun fontSizePxOf(blockPath: String, theme: DocumentTheme): Float

    companion object {
        private val BLOCK_INDEX = Regex("^blk\\[(\\d+)]$")

        fun blockIndexFromPath(blockPath: String): Int? =
            BLOCK_INDEX.find(blockPath)?.groupValues?.get(1)?.toIntOrNull()
    }
}

/**
 * 一个排版段。
 *
 * 为什么要把文档切成多段而不是整篇一个 `StaticLayout`：
 * 一个 300 万字的 TXT 会让单个 StaticLayout 持有数万行的行信息，
 * 在低端设备上是明确的内存风险，而且排版耗时无法分片。
 * 段与段之间只在**块边界**切开，所以块路径（`blk[i]`）仍然是全局的，
 * 锚点坐标系不受分段影响 —— 这正是"分段"可行的原因。
 */
class TextLayoutSection(
    val index: Int,
    /** 该段覆盖的块区间 [start, endExclusive)。 */
    val blockStart: Int,
    val blockEndExclusive: Int,
    /** 该段在归一化全文里的字符区间。 */
    val charStart: Int,
    val charEndExclusive: Int,
    /** 该段在整个文档里的纵向起点（累计高度）。 */
    val topPx: Int,
    val layout: StaticLayout,
) {
    /**
     * 段末行的"欠高"：这一行本该占的行距余量，`StaticLayout` 没有算进 `height`。
     *
     * ## 为什么必须补
     *
     * `StaticLayout.getLineBottom(last)` 只到末行字形盒的底边 ——
     * `setLineSpacing` 的那个倍数余量是**由下一行的 `lineTop` 顶出去的**，
     * 不是记在上一行的 `lineBottom` 里；`setIncludePad(false)` 下连 descent pad 都不含。
     *
     * 段内看不出差别（下一行会补上），但**段尾没有下一行**。于是每个排版段都短一截，
     * 实测 48px 字号 / 1.4 倍行距下短 23px。后果有两个，都不轻：
     *
     * 1. 段末行往下那 23px 落不进任何行盒 —— 在那儿写字，笔迹锚不到这一行；
     * 2. 段间距若加在 `layout.height` 上，会被这一截吃掉：0.5em（24px）的段间距
     *    只剩 1px 的视觉差别，滑杆推到头也看不出变化。
     *
     * M33 之前只有一个大段，只有文末最后一行受影响，看不出来；段间距把
     * "一个自然段一个排版段"变成常态之后，它就成了每个段落边界上的固定亏损。
     */
    private val lastLineShortfall: Int = run {
        val count = layout.lineCount
        if (count == 0) return@run 0
        val last = count - 1
        val top = layout.getLineTop(last)
        val actual = layout.getLineBottom(last) - top
        // 末行"本该占"的步进：字形高度 × 行距倍数 + 行距附加。
        // 这正是 StaticLayout 内部推下一行 top 的公式，所以对同字号的段
        // 与 `getLineTop(n) - getLineTop(n - 1)` 完全一致。
        //
        // 为什么不去借上一行的步进：段落里可以混字号（标题与正文同段、
        // 行内 `CodeSpan`），借上一行会把小字号的末行拉成标题那么高。
        val advance = ceil(actual * layout.spacingMultiplier + layout.spacingAdd).toInt()
        (advance - actual).coerceAtLeast(0)
    }

    val heightPx: Int get() = layout.height + lastLineShortfall
    val lineCount: Int get() = layout.lineCount

    fun lineTop(line: Int): Int = topPx + layout.getLineTop(line)

    fun lineBottom(line: Int): Int =
        topPx + layout.getLineBottom(line) +
            if (line == layout.lineCount - 1) lastLineShortfall else 0

    fun lineBaseline(line: Int): Int = topPx + layout.getLineBaseline(line)
}

/**
 * 一页里某一段的连续行区间。
 *
 * 为什么一页要由多个这样的区间组成：一页可能横跨两个排版段（M33 起，
 * 段间距让段落边界成为切段点，一段一页放不下时必然跨段）。
 * 只有"一页 = 一段的一段行"这个假设成立时，才敢把页记成三个整数。
 */
data class PageLineSpan(
    val sectionIndex: Int,
    val lineStart: Int,
    val lineEndExclusive: Int,
) {
    val lineCount: Int get() = lineEndExclusive - lineStart
}

/**
 * 一页。
 *
 * [topPx] 是这一页首行在全文坐标系里的纵向位置。绘制时把它平移到视口顶部即可，
 * 因此分页模式与滚动模式共用同一套坐标，不需要两套渲染代码（§8.4）。
 *
 * ## 为什么是"行区间列表"而不是"段号 + 行区间"
 *
 * 段间距（M33）让排版段变短（一个自然段一段），一页自然要跨好几段。
 * 记成列表之后，"这一页覆盖了哪些行"仍然是精确的：分页不再受排版段边界影响，
 * 而那本来就是个实现细节（60k 字符预算），不该在读者眼里变成"凭空多出一页"。
 */
data class PageSlice(
    val index: Int,
    /** 这一页覆盖的各段行区间，按文档顺序。空页不会出现在结果里。 */
    val spans: List<PageLineSpan>,
    val topPx: Int,
    val heightPx: Int,
    val charStart: Int,
    val charEndExclusive: Int,
) {
    val sectionIndex: Int get() = spans.first().sectionIndex
    val lineStart: Int get() = spans.first().lineStart

    /** 首段内的结束行（跨段时等于该段行数）。只为兼容"一页一段"的调用方。 */
    val lineEndExclusive: Int get() = spans.first().lineEndExclusive

    /** 这一页一共几行。跨段时**不能**用 `lineEndExclusive - lineStart` 算。 */
    val lineCount: Int get() = spans.sumOf { it.lineCount }

    val isEmpty: Boolean get() = spans.isEmpty()
}

/**
 * 排版结果。
 *
 * 关键性质：**行盒与绘制共用同一个 `StaticLayout` 对象**。
 * 这是把"笔迹与文字错位"变成结构上不可能的那一步（§8.4）：
 * 画出来的文字和给出的行盒永远出自同一份排版数据，中间没有任何推算环节。
 */
class ReflowableTextLayout internal constructor(
    private val session: DocumentSession,
    val metrics: PageLayoutMetrics,
    val theme: DocumentTheme,
    val sections: List<TextLayoutSection>,
) {
    val totalHeightPx: Int
        get() = sections.lastOrNull()?.let { it.topPx + it.heightPx } ?: 0

    val lineCount: Int
        get() = sections.sumOf { it.lineCount }

    /**
     * 按内容高度切页（M33 起带"版面规则"）。
     *
     * 贪心切法：一行放不下就换页。刻意不做"两端对齐后再分页"这类花哨处理，
     * 因为分页结果一旦依赖更复杂的全局优化，就无法解释"为什么这行被挪到下一页"，
     * 而读书时这种不可解释的跳动是最刺眼的。
     *
     * 但"纯贪心"会把一段的第一行或最后一行单独留在页尾/页首 —— 这就是
     * **寡行与孤行**（[adjustBreak] 里的两条规则）。它们不需要"全局优化"，
     * 只需要"回头看一行再决定切在哪"，所以既保住了可解释性，又消掉了
     * 排版上最扎眼的一类毛病。
     */
    val pages: List<PageSlice> by lazy { computePages() }

    private fun computePages(): List<PageSlice> {
        val pageHeight = metrics.contentHeightPx
        if (pageHeight <= 0 || sections.isEmpty()) return emptyList()

        // ── 先把所有排版段接到一条"全局行流"上 ──────────────────────────────
        //
        // 为什么要一条全局流：页不能再被排版段边界切开（M33）。段间距让
        // "一个自然段 = 一个排版段"，一页放不下时就必然要跨段；而跨段分页
        // 本来也是对的 —— 60k 字符的段预算是个实现细节，不该在读者眼里
        // 变成"每隔六十万字凭空多出一页"。
        val total = lineCount
        if (total == 0) return emptyList()
        val sectionOf = IntArray(total)
        val lineOf = IntArray(total)
        val blockOf = IntArray(total)
        var g = 0
        for (section in sections) {
            val layout = section.layout
            for (line in 0 until layout.lineCount) {
                sectionOf[g] = section.index
                lineOf[g] = line
                // 寡行/孤行/标题规则全部以"这一行属于哪一段"为前提，一次算好。
                blockOf[g] = session.blockIndexOfChar(section.charStart + layout.getLineStart(line))
                g++
            }
        }

        val blockFirst = HashMap<Int, Int>()
        val blockLast = HashMap<Int, Int>()
        for (i in 0 until total) {
            val b = blockOf[i]
            if (!blockFirst.containsKey(b)) blockFirst[b] = i
            blockLast[b] = i
        }

        fun topOf(i: Int) = sections[sectionOf[i]].lineTop(lineOf[i])
        fun bottomOf(i: Int) = sections[sectionOf[i]].lineBottom(lineOf[i])
        fun charStartOf(i: Int) = sections[sectionOf[i]].let {
            it.charStart + it.layout.getLineStart(lineOf[i])
        }
        fun charEndOf(i: Int) = sections[sectionOf[i]].let {
            it.charStart + it.layout.getLineEnd(lineOf[i])
        }

        val out = ArrayList<PageSlice>()
        var start = 0
        while (start < total) {
            val pageTop = topOf(start)
            var end = start
            while (end < total && bottomOf(end) - pageTop <= pageHeight) end++
            // 一行都放不下时也要强制放一行，否则会死循环。
            // 这种极端情况只会出现在"视口比一行还矮"时，交给渲染层忽略即可。
            if (end == start) end = start + 1

            end = adjustBreak(blockOf, blockFirst, blockLast, start, end)

            out.add(
                PageSlice(
                    index = out.size,
                    spans = groupSpans(sectionOf, lineOf, start, end),
                    topPx = pageTop,
                    heightPx = bottomOf(end - 1) - pageTop,
                    charStart = charStartOf(start),
                    charEndExclusive = charEndOf(end - 1),
                ),
            )
            start = end
        }
        return out
    }

    /** 把全局行区间 `[start, end)` 按所属排版段拆成若干 [PageLineSpan]。 */
    private fun groupSpans(
        sectionOf: IntArray,
        lineOf: IntArray,
        start: Int,
        end: Int,
    ): List<PageLineSpan> {
        val out = ArrayList<PageLineSpan>(2)
        var i = start
        while (i < end) {
            val section = sectionOf[i]
            var j = i
            while (j < end && sectionOf[j] == section) j++
            out.add(PageLineSpan(section, lineOf[i], lineOf[j - 1] + 1))
            i = j
        }
        return out
    }

    /**
     * 把贪心切出来的页尾往后挪一点，避开版面禁忌（M33）。
     *
     * 三条规则，都不改变"这一页能装多少行"的前提，只决定**从哪一行切**：
     *
     * 1. **标题不与后文分离**：页尾最后一行是标题 → 整条标题推到下一页。
     *    标题孤零零挂在页脚上，翻页后正文才开始，读者会以为这一页是空的。
     * 2. **寡行**（orphan）：页尾最后一行是某段的第一行，且这一段还有下文
     *    → 这一行跟着段落一起走。否则读者读到"段首一句"就得翻页。
     * 3. **孤行**（widow）：下一页第一行是某段的最后一行 → 上一页多带一行下去，
     *    让那一页开头至少有两行本段文字。只有一行挂在页首是最典型的排版事故。
     *
     * 规则之间会互相触发（挪了一行可能又造出寡行），所以最多迭代三轮；
     * 任何一次调整都不许把这一页挪到空（`candidate > pageStart`），
     * 也不许越过页首 —— 宁可留一个小毛病，也不能出现空白页死循环。
     */
    private fun adjustBreak(
        blockOf: IntArray,
        blockFirst: Map<Int, Int>,
        blockLast: Map<Int, Int>,
        pageStart: Int,
        greedyEnd: Int,
    ): Int {
        var candidate = greedyEnd
        repeat(MAX_BREAK_ADJUSTMENTS) {
            val next = adjustBreakOnce(blockOf, blockFirst, blockLast, pageStart, candidate)
            if (next == candidate || next <= pageStart) return candidate
            candidate = next
        }
        return candidate
    }

    /** 一次调整：返回**第一个**命中规则给出的新页尾。没命中就原样返回。 */
    private fun adjustBreakOnce(
        blockOf: IntArray,
        blockFirst: Map<Int, Int>,
        blockLast: Map<Int, Int>,
        pageStart: Int,
        end: Int,
    ): Int {
        if (end - pageStart <= 1) return end
        val last = end - 1
        val lastBlock = blockOf[last]
        val nextBlock = if (end < blockOf.size) blockOf[end] else Int.MIN_VALUE

        // ① 标题不许落在页尾
        if (session.blocks.getOrNull(lastBlock)?.kind == BlockKind.HEADING) {
            val headingStart = blockFirst[lastBlock] ?: last
            if (headingStart > pageStart) return headingStart
        }

        // ② 寡行：页尾是段首、且这一段在下一页还有
        if (last == blockFirst[lastBlock] && nextBlock == lastBlock && last > pageStart) {
            return last
        }

        // ③ 孤行：下一页开头是段末，且这一段从上一页延伸过来
        if (nextBlock == lastBlock && blockLast[lastBlock] == end && end - 1 > pageStart) {
            return end - 1
        }
        return end
    }

    /**
     * 产生行盒。
     *
     * 每个行盒携带一个结构型选择器（块路径 + 块内字符区间 + 基线相对位置），
     * 这就是"L2 行级锚定"的全部内容。基线相对位置用 em 表示，
     * 因此字号变化后笔迹会等比跟随，而不是按像素固定偏移。
     */
    fun lineBoxes(): List<LineBox> {
        val out = ArrayList<LineBox>(lineCount)
        for (section in sections) {
            val layout = section.layout
            for (line in 0 until layout.lineCount) {
                val globalStart = section.charStart + layout.getLineStart(line)
                val globalEnd = section.charStart + layout.getLineEnd(line)
                val blockIndex = session.blockIndexOfChar(globalStart)
                if (blockIndex < 0) continue
                val block = session.blocks[blockIndex]

                // 块内偏移必须夹在块内：一个视觉行可能跨块（例如空行被并进同一行），
                // 越界的块内偏移会让结构型选择器指向不存在的字符。
                val localStart = (globalStart - block.absoluteStart).coerceIn(0, block.span.length)
                val localEnd = (globalEnd - block.absoluteStart).coerceIn(localStart, block.span.length)

                val fontSizePx = TextSpanBuilder.fontSizePxOf(block, theme)
                val top = section.lineTop(line)
                val bottom = section.lineBottom(line)
                val baseline = section.lineBaseline(line)

                out.add(
                    LineBox(
                        anchor = Selector.Structural(
                            blockPath = block.blockPath,
                            startOffset = localStart,
                            endOffset = localEnd,
                            baselineOffsetEm = if (fontSizePx > 0f) (baseline - top) / fontSizePx else 1f,
                        ),
                        rectDoc = Box(
                            left = metrics.marginLeftPx + layout.getLineLeft(line),
                            top = top.toFloat(),
                            right = metrics.marginLeftPx + layout.getLineRight(line),
                            bottom = bottom.toFloat(),
                        ),
                        baselineY = baseline.toFloat(),
                        fontSizePx = fontSizePx,
                    ),
                )
            }
        }
        return out
    }

    /**
     * 分页模式下"这一页"在文档坐标里的纵向窗口：`[页首, 页首 + 这一页的实际高度)`。
     *
     * ## 为什么绘制必须剪进这个窗口（M32）
     *
     * 视图比页高：底部还要留出一整块页边距，而绘制只是把文档平移到视图里，
     * 没有"页"这层裁剪。于是不剪的话：
     *
     * - 上一页的最后一行会画进**上页边距** —— 只露出下半截字；
     * - 下一页的第一行会画进**下页边距** —— 只露出上半截字。
     *
     * 页边距越小、字号越大，露出来的越少；默认 4 mm 边距配 17 sp 时是"多一整行"，
     * 调到小边距 + 大字号就变成半截字 —— 用户把它描述成"换页时字被切了一半"。
     * 这不是分页算错（`分页覆盖全部行且不重叠` 一直是绿的），而是**绘制越界**。
     *
     * ## 为什么用"这一页的实际高度"而不是正文框高度
     *
     * 页与页首尾相接，但一页很少刚好填满正文框：最后一行放不下就留给下一页，
     * 于是这一页的高度可以比正文框**矮**将近一行。用正文框高度当窗口下界，
     * 那个差额里正好能塞下下一页第一行的上半截 —— 剪了等于没剪。
     * 只有 `PageSlice.heightPx` 是"本页最后一行画到哪"的准确答案。
     *
     * 找不到（[docTopPx] 不是任何一页的首行，例如排版刚换过、页码还没夹紧）时
     * 返回 null，调用方退回整屏高 —— 宁可多露一行，也不要因为查不到就画不出东西。
     */
    fun pageWindowAt(docTopPx: Int): IntRange? {
        val list = pages
        if (list.isEmpty()) return null
        // 页首严格递增（下一页的顶边就是上一页的底边），可以二分。
        var lo = 0
        var hi = list.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val top = list[mid].topPx
            when {
                top == docTopPx -> return list[mid].let { it.topPx until (it.topPx + it.heightPx) }
                top < docTopPx -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    /** 全文坐标 → 页序号。找不到（例如视口为 0）返回 0。 */
    fun pageIndexOfCharOffset(offset: Int): Int {
        val list = pages
        if (list.isEmpty()) return 0
        val hit = list.firstOrNull { offset >= it.charStart && offset < it.charEndExclusive }
        if (hit != null) return hit.index
        // 落在块的间隙（空行）里：取第一个起点在它之后的页
        return list.firstOrNull { it.charStart >= offset }?.index ?: list.last().index
    }

    fun pageContainingLine(sectionIndex: Int, line: Int): Int? =
        pages.firstOrNull { page ->
            page.spans.any {
                it.sectionIndex == sectionIndex && line in it.lineStart until it.lineEndExclusive
            }
        }?.index

    /**
     * 全文坐标 → 滚动模式下的纵向位置（**文档坐标系**，px）。
     *
     * 滚动模式没有"页"这个单位，目录跳转唯一的定位量就是像素。返回的是文档坐标，
     * 调用方要加上 [metrics] 的上边距才是视图内坐标（见 `DocumentInkView` 的
     * `translate(marginLeft, marginTop - windowTop)`）—— 加这一步而不是在这里替调用方算，
     * 是因为"文档坐标 → 视图坐标"的换算只应该有一个地方知道。
     *
     * 与 [pageIndexOfCharOffset] 一致：落在块间隙（空行）上时取**之后**的位置，
     * 因为"跳到空行上"对用户等于没跳。
     */
    fun scrollYOfCharOffset(offset: Int): Int {
        if (sections.isEmpty()) return 0
        val section = sections.lastOrNull { it.charStart <= offset } ?: sections.first()
        val layout = section.layout
        if (layout.text.isEmpty() || layout.lineCount == 0) return section.topPx
        val local = (offset - section.charStart).coerceIn(0, layout.text.length)
        val line = layout.getLineForOffset(local).coerceIn(0, layout.lineCount - 1)
        return section.lineTop(line)
    }

    /**
     * [scrollYOfCharOffset] 的逆：滚动位置（**文档坐标系**的 px）→ 该处正文的第一个字符偏移。
     *
     * 一个把像素变成"读到哪里了"，另一个把"读到哪里了"变成像素。两者必须互为
     * 逆运算，否则反复旋转屏幕、换字号、在分页与滚动之间切换时，阅读位置会
     * 一点一点漂走 —— 每次漂一行，十次之后就找不回来了。
     *
     * 注意这是**近似逆**：一行里有很多字符，像素位置只对应到"行的第一个字符"。
     * 这正是滚动模式能做到的最好精度，也是它足够用的精度（恢复阅读位置不需要
     * 精确到字符）。
     */
    fun charOffsetOfScrollY(y: Int): Int {
        if (sections.isEmpty()) return 0
        val section = sections.lastOrNull { it.topPx <= y } ?: sections.first()
        val layout = section.layout
        if (layout.text.isEmpty() || layout.lineCount == 0) return section.charStart
        val localY = (y - section.topPx).coerceIn(0, (section.heightPx - 1).coerceAtLeast(0))
        val line = layout.getLineForVertical(localY).coerceIn(0, layout.lineCount - 1)
        return section.charStart + layout.getLineStart(line)
    }

    /**
     * 一段字符区间在**文档坐标**里的矩形，一个视觉行一个。
     *
     * 用途是全文搜索的命中高亮（[TextSearch] 给出区间，这里给出"涂在哪"）。
     * 之所以按行返回多个矩形而不是一个包围盒：一段跨了换行的文字，若只给一个
     * 包围盒，高亮会盖住两行之间那一整片本不该高亮的区域 —— 页面上看起来像
     * "整段被涂了"，用户会以为搜索命中了整整一段。
     *
     * 首行从命中起点量起、末行到命中终点结束（用 `getPrimaryHorizontal` 取字符边界），
     * 中间的行取整行。这是"只涂命中的字"能做到的精确度，也是它应该有的精确度：
     * 高亮是给用户看"找到的是哪几个字"的，涂多一个字的宽度都会让人怀疑结果。
     *
     * 与 [lineBoxes] 用同一套几何（同一个 `marginLeftPx`、同一组 `lineTop/lineBottom`），
     * 所以高亮框与行盒不可能对不上。
     */
    fun rectsOfCharRange(start: Int, endExclusive: Int): List<Box> {
        if (endExclusive <= start) return emptyList()
        val out = ArrayList<Box>()
        for (section in sections) {
            val layout = section.layout
            val textLength = layout.text.length
            val sectionEnd = section.charStart + textLength
            if (sectionEnd <= start) continue
            if (section.charStart >= endExclusive) break
            if (textLength == 0 || layout.lineCount == 0) continue

            val localStart = (maxOf(start, section.charStart) - section.charStart).coerceIn(0, textLength)
            val localEnd = (minOf(endExclusive, sectionEnd) - section.charStart).coerceIn(localStart, textLength)
            if (localEnd <= localStart) continue

            val firstLine = layout.getLineForOffset(localStart).coerceIn(0, layout.lineCount - 1)
            val lastLine = layout.getLineForOffset(localEnd - 1).coerceIn(firstLine, layout.lineCount - 1)
            for (line in firstLine..lastLine) {
                val left = if (line == firstLine) {
                    layout.getPrimaryHorizontal(localStart)
                } else {
                    layout.getLineLeft(line)
                }
                val right = if (line != lastLine) {
                    layout.getLineRight(line)
                } else if (layout.getLineForOffset(localEnd) == line) {
                    layout.getPrimaryHorizontal(localEnd)
                } else {
                    // 命中终点恰好落在行末（即换行处）时，`getPrimaryHorizontal(localEnd)`
                    // 给的是**下一行的起点**（那个偏移属于下一行），拿它当右边界会得到
                    // 一个负宽矩形，整个高亮会消失 —— 而这种"命中整行时反而不高亮"
                    // 的错，恰恰会在用户搜一整行原文时出现。
                    layout.getLineRight(line)
                }
                if (right <= left) continue
                out.add(
                    Box(
                        left = metrics.marginLeftPx + left,
                        top = section.lineTop(line).toFloat(),
                        right = metrics.marginLeftPx + right,
                        bottom = section.lineBottom(line).toFloat(),
                    ),
                )
            }
        }
        return out
    }
}

/**
 * 重排格式的文档会话实现（TXT / Markdown / EPUB 提取后的正文）。
 *
 * 固定版式（PDF）不走这里：它没有"重排"，行盒来自 PDF 自己的文本层。
 */
class ReflowableDocumentSession(
    override val docId: String,
    override val kind: DocumentKind,
    override val profile: NormalizationProfile,
    override val normalized: NormalizedText,
    val split: SplitResult,
    override val fingerprint: String,
    /** 归一化偏移 → 图片的书内路径。空表示这份文档没有图（M31）。 */
    val images: Map<Int, String> = emptyMap(),
    /** 取图器。null 时图片位置留白。 */
    val imageProvider: DocumentImageProvider? = null,
) : DocumentSession {

    override val blocks: List<TextBlock> get() = split.blocks

    override val toc: List<TocItem> get() = split.toc

    private var cachedLayout: ReflowableTextLayout? = null
    private var cachedKey: String? = null

    override fun blockIndexOfChar(offset: Int): Int = blockIndexOf(blocks, offset)

    override fun layout(metrics: PageLayoutMetrics, theme: DocumentTheme): ReflowableTextLayout {
        val key = buildString {
            append(metrics.contentWidthPx).append('|')
            append(metrics.contentHeightPx).append('|')
            append(theme.textSizePx).append('|')
            append(theme.lineSpacingMultiplier).append('|')
            append(theme.textColor).append('|')
            // 字体变了行盒必须重建：缓存键里不放它，换字体就会复用旧字体的断行。
            append(System.identityHashCode(theme.typeface)).append('|')
            append(theme.typeface?.style ?: -1)
            // 排版开关（M33）：段间距改变行高、两端对齐与禁则改变断行结果，
            // 三者都是"排版输入"。漏一个，用户改了设置却看到旧版面 ——
            // 而且是那种"看起来没生效"的错，最难被发现。
            append('|').append(theme.paragraphSpacingEm)
            append('|').append(theme.justifyText)
            append('|').append(theme.cjkPunctuation)
        }
        cachedLayout?.let { if (cachedKey == key) return it }
        return buildLayout(metrics, theme).also {
            cachedLayout = it
            cachedKey = key
        }
    }

    override fun textOfLine(line: LineBox): String {
        val blockIndex = DocumentSession.blockIndexFromPath(line.anchor.blockPath) ?: return ""
        val block = blocks.getOrNull(blockIndex) ?: return ""
        val start = (block.absoluteStart + line.anchor.startOffset)
            .coerceIn(block.absoluteStart, block.absoluteEnd)
        val end = (block.absoluteStart + line.anchor.endOffset)
            .coerceIn(start, block.absoluteEnd)
        return normalized.substring(start, end)
    }

    override fun fontSizePxOf(blockPath: String, theme: DocumentTheme): Float {
        val index = DocumentSession.blockIndexFromPath(blockPath) ?: return theme.textSizePx
        val block = blocks.getOrNull(index) ?: return theme.textSizePx
        return TextSpanBuilder.fontSizePxOf(block, theme)
    }

    private fun buildLayout(metrics: PageLayoutMetrics, theme: DocumentTheme): ReflowableTextLayout {
        if (blocks.isEmpty() || normalized.isEmpty) {
            return ReflowableTextLayout(this, metrics, theme, emptyList())
        }

        val spannable: Spannable = TextSpanBuilder.build(
            normalizedText = normalized.text,
            blocks = blocks,
            kind = kind,
            theme = theme,
            imageProvider = imageProvider,
            imageMaxWidthPx = metrics.contentWidthPx,
            // 单张图不超过一页的 90%：图比一页还高时，分页会为它单独挤出一页，
            // 而那一页里除了图什么都没有 —— 读起来像"突然翻到一张海报"。
            imageMaxHeightPx = (metrics.contentHeightPx * IMAGE_MAX_HEIGHT_RATIO)
                .toInt()
                .coerceAtLeast(1),
        )

        // 一个 TextPaint 贯穿全部段：段与段之间字体度量必须完全一致，
        // 否则跨段边界的笔迹换算会出现微小但累积的偏差。
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = theme.textColor
            textSize = theme.textSizePx
            // 正文字体（M29）。null = 系统默认，不设即默认。
            theme.typeface?.let { typeface = it }
        }

        val sections = ArrayList<TextLayoutSection>()
        var cursor = 0
        var top = 0
        var sectionIndex = 0

        // 段间距（M33）：段与段之间的几何留白。
        // 它必须体现为"下一段的 topPx 多让出一截"，而不是行内的 span —— 理由见
        // `DocumentTheme.paragraphSpacingEm`：`LineHeightSpan` 的改动会被后面的
        // 每一行继承，段边界反而消失。
        val paragraphGapPx = (theme.textSizePx * theme.paragraphSpacingEm).roundToInt()

        while (cursor < blocks.size) {
            val first = cursor
            var charEnd = blocks[first].absoluteEnd
            var next = first + 1
            // 三种情况下切段：字符预算到顶、或者跨过了段落边界（要让出段间距）。
            var breakForParagraph = false
            while (next < blocks.size &&
                (blocks[next].absoluteEnd - blocks[first].absoluteStart) <= SECTION_CHAR_BUDGET
            ) {
                if (paragraphGapPx > 0 &&
                    TextSpanBuilder.needsParagraphGap(blocks[next - 1], blocks[next])
                ) {
                    breakForParagraph = true
                    break
                }
                charEnd = blocks[next].absoluteEnd
                next++
            }

            val charStart = blocks[first].absoluteStart
            val piece = spannable.subSequence(charStart, charEnd)
            val builder = StaticLayout.Builder
                .obtain(piece, 0, piece.length, paint, metrics.contentWidthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, theme.lineSpacingMultiplier)
                .setIncludePad(false)
            // 按**词组**断行，而不是按字断行。
            //
            // 中文没有空格，默认断行器可以在任意两字之间插一个换行 ——
            // 于是"计算机"会变成行尾的"计算"加行首的"机"。
            // `LINE_BREAK_WORD_STYLE_PHRASE` 让断行器优先在词组边界断开，
            // 这是排版质量上最容易被读者察觉、也最便宜的一处改进。
            //
            // 只在 API 33+ 做：`LineBreakConfig` 是 T 才有的类。低版本保持原样 ——
            // 悄悄降级到"按字断行"仍然能读，而为了它去写一套自研断行不值得。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val breakConfig = LineBreakConfig.Builder()
                    .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                // 中文禁则的**断行层**（M33）：严格断行不允许 `。，」` 这类收尾标点
                // 出现在行首 —— 系统的 ICU 断行器认识完整的禁则表，比自己写一套
                // "看前一个字是什么"的规则可靠得多。低版本保持原样（按字断行仍可读）。
                if (theme.cjkPunctuation) {
                    breakConfig.setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
                }
                builder.setLineBreakConfig(breakConfig.build())
            }
            // 两端对齐（M33）。API 35 起才有"字间对齐"（inter-character），
            // 那才是中文该用的 —— inter-word 只拉单词之间的空隙，纯中文里没有空格可拉，
            // 等于没对齐。低版本退到 inter-word：混排的英文段落仍然受益。
            if (theme.justifyText) {
                builder.setJustificationMode(
                    if (Build.VERSION.SDK_INT >= 35) {
                        Layout.JUSTIFICATION_MODE_INTER_CHARACTER
                    } else {
                        Layout.JUSTIFICATION_MODE_INTER_WORD
                    },
                )
            }
            val layout = builder.build()

            val section = TextLayoutSection(
                index = sectionIndex,
                blockStart = first,
                blockEndExclusive = next,
                charStart = charStart,
                charEndExclusive = charEnd,
                topPx = top,
                layout = layout,
            )
            sections.add(section)
            // 用 section.heightPx 而不是 layout.height：后者到末行的 lineBottom 就停了，
            // 少了一截行距余量（见 TextLayoutSection.lastLineShortfall）。
            top += section.heightPx
            // 段间距让在**两段之间**：加在这一段之后，下一段的 topPx 就自然低下去。
            // 加在段前与加在段后是同一件事，但写在"这一段之后"能保证最后一段
            // 后面不会多留一截空白（文档末尾不该有一段没用的空隙）。
            if (breakForParagraph) top += paragraphGapPx
            cursor = next
            sectionIndex++
        }

        return ReflowableTextLayout(this, metrics, theme, sections)
    }

    companion object {
        /**
         * 单个排版段的字符预算。
         *
         * 6 万字符约等于 1500~2000 行中文，单段排版耗时在 10ms 量级、内存可控。
         * 取更大的值收益很小（段内还是顺序排版），风险却线性上升。
         */
        const val SECTION_CHAR_BUDGET = 60_000

        /** 单张图最多占一页正文高度的多少。见 `buildLayout` 的调用点。 */
        const val IMAGE_MAX_HEIGHT_RATIO = 0.9f

        /** 二分查找：最后一个 `absoluteStart <= offset` 的块。 */
        fun blockIndexOf(blocks: List<TextBlock>, offset: Int): Int {
            if (blocks.isEmpty()) return -1
            var lo = 0
            var hi = blocks.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (blocks[mid].absoluteStart <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
