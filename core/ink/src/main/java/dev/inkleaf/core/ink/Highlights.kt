package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.AnchorChainFactory
import dev.inkleaf.core.anchoring.AnchorResolver
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 高亮：**区间**打字批注 —— 恰好一条锚点链、没有笔迹，而且链指的是**一段字符**，
 * 不是"某一行"。它与 [TypedNoteKind.HIGHLIGHT] 那一行列表模型是一件事的两面：
 * 前者回答"画在哪几个字下面"，后者回答"笔记面板里怎么显示它"。
 *
 * 为什么区间这一档值得单独一个文件：全工程其它所有锚点（手写笔迹、边注）都是
 * **行级**的（L2），只有高亮存的是块内的一段偏移。差别只在两处，但两处都必须
 * 收口：写入方向（[chainForRange]）与读出方向（[highlightRanges]）。
 * 各写各的就会得到"标的是半句话、画出来是一整行"这种要靠眼睛发现的东西。
 */

/**
 * 把"用户标中了 [start], [endExclusive) 这一段文字"变成一条锚点链。
 *
 * 与 [chainForLine]（选行 → 链）的关系：**行是区间的特例**。两者的链形状
 * 都由 [AnchorChainFactory.build] 定义（结构型 → 引文 → 进度兜底），
 * 这里只负责把"全文里的这一段"换算成它要求的两个坐标系：
 * 块内偏移（进结构型选择器）与全文偏移（抽引文用）。
 *
 * 返回 null 表示这一段取不到可引用的文本：区间为空、越界、或者落在没有块的空白处
 * （见 [LayoutFacts.structuralOfRange]）。调用方必须当场拒绝 ——
 * 没有引文的锚点重排后必然判不回 `Resolved`，写下去只会得到一条
 * "看着标上了、下次打开就没了"的高亮。
 *
 * @param pageKey 固定版式（PDF）的页键，重排格式传 null。理由同 [chainForLine]。
 */
fun LayoutFacts.chainForRange(
    start: Int,
    endExclusive: Int,
    pageKey: String? = null,
): TextAnchorChain? {
    if (endExclusive <= start) return null
    if (start < 0 || endExclusive > normalizedText.length) return null

    val structural = structuralOfRange(start until endExclusive) ?: return null

    return AnchorChainFactory.build(
        normalizedText = normalizedText,
        profile = profile,
        fingerprint = fingerprint,
        target = AnchorChainFactory.Target(
            blockPath = structural.blockPath,
            blockStartOffset = structural.startOffset,
            blockEndOffset = structural.endOffset,
            absoluteStart = start,
            absoluteEnd = endExclusive,
            baselineOffsetEm = structural.baselineOffsetEm,
        ),
        docRatio = docRatio,
        pageKey = pageKey,
        // 兜底提示里给用户看的那句话取这一段文字本身（截断口径与引文上限一致），
        // 与 [chainForLine] 取"这一行的文字"是同一件事。
        nearbyText = normalizedText.substring(start, endExclusive).take(AnchorChainFactory.MAX_EXACT_CHARS),
    )
}

/**
 * "把当前这一行标成高亮"要用的区间：这一行的绝对区间**去掉首尾空白**。
 *
 * 为什么要去掉：行盒的区间是排版给出的，它包含段首缩进（Markdown / EPUB 里的
 * 全角空格）与任何行尾空白。直接拿它去画底色，得到的是一条从缩进处就开始的色带 ——
 * 用户看到的是"这一行被涂了"，而不是"这一行的字被标了"。
 *
 * 返回 null 表示这一行**没有可标的字**（全空白行）：调用方据此拒绝，
 * 而不是写一条引文是几个空格的链（那会在重排后匹配到任意一处空白）。
 */
fun LayoutFacts.lineHighlightRange(line: LineBox): IntRange? {
    val range = absoluteRangeOf(line) ?: return null
    val text = normalizedText
    var start = range.first
    var end = range.last
    while (start <= end && text[start].isWhitespace()) start++
    while (end >= start && text[end].isWhitespace()) end--
    return if (end < start) null else start..end
}

/**
 * 一条**可以画出来**的高亮：画在哪个区间、用什么颜色。
 *
 * 与 [DrawableStroke] 同一个分工：这里面没有任何几何 —— 区间要变成矩形
 * 只能由排版层完成（`ReflowableTextLayout.rectsOfCharRange`），
 * 因为只有它知道每个字画在哪。这里给的区间与行盒同源（[LayoutFacts]），
 * 所以"底色盖住哪几个字"与"文字画在哪"不可能对不上。
 */
data class DrawableHighlight(
    val annotationId: String,
    /** 归一化全文里的闭区间。 */
    val range: IntRange,
    val colorArgb: Int,
)

/**
 * 高亮 → 可以画的底色。**只有解析到某一行的才画**。
 *
 * 判据刻意比笔迹严：笔迹有一条"冻结几何"的例外（`InkResolver` 里
 * PINNED / FREE 的墨迹即使锚点解不开也照样画在原处），因为那笔墨迹的坐标
 * 是落笔那一刻冻下来的，位置本身不依赖锚点。高亮没有第二条轴 ——
 * 它的位置**就是**锚点，解不开就只剩"我大概标过这一段"，
 * 画到猜出来的行上会让用户以为"我的高亮跑到别处去了"。
 * 所以 [AnchorResolution.Ambiguous] 与 `Orphaned` 都不画，
 * 它们在笔记面板里如实说明（`TypedNoteRow.state`），并给出「确认位置」。
 *
 * 区间取**链里存的那一段**（[LayoutFacts.absoluteRangeOf] 会把它夹进它所属的块，
 * 所以它绝不会漏到别的段上），而不是"解到的那一行"：
 *
 *  - 一个软换行的段落里，一段高亮横跨两个视觉行是**正常**的 ——
 *    按"解到的那一行"裁剪，第二行的那半截底色会凭空消失（用户会以为高亮被截断了）；
 *  - 一行里被标中半句话时，铺满整行是对"我标了哪几个字"的谎报。
 *
 * 也就是说：**画出来的区间就是链里存的那一段**，不做第二次推算。
 * （"没有结构型选择器所以只能按行画"这种情况不存在：`Resolved` 必然带着结构型选择器，
 * 见下面的实现注释。）
 */
fun highlightRanges(
    highlights: List<Annotation.Highlight>,
    facts: LayoutFacts,
): List<DrawableHighlight> {
    val out = ArrayList<DrawableHighlight>(highlights.size)

    for (highlight in highlights) {
        // 与 `typedNoteRows` 是**同一次**解析调用的同一组参数：同一份链在两条路径上
        // 必须得到同一个结论，否则会出现"面板说已定位、正文里却什么都没画"。
        val resolution = AnchorResolver.resolve(
            chain = highlight.anchor,
            currentSourceFingerprint = facts.fingerprint,
            candidates = facts.lines,
            textOf = facts::textOf,
            currentProfile = facts.profile,
            contextOf = facts::textWindow,
        )
        // 只有解开了的才画（理由见 KDoc）：Ambiguous / Orphaned 都停在面板里。
        if (resolution !is AnchorResolution.Resolved) continue

        // 这里看似可以写 `?: 整行`，但那是一段永远不会被走到的分支：
        // `Resolved` 只可能来自规则 2a（结构型 + 引文双重命中），而 2a 的前提就是
        // 链里**有**结构型选择器，且它的块路径与解到的那一行是同一个（否则不会重叠）。
        // 既然同一个块路径既能用来找行，就能用来取区间 —— 取不到只说明库里的链与
        // 当前排版对不上（块号越界等），此时宁可这一个像素不画，也不要画到猜出来的地方。
        val stored = highlight.anchor.structural?.let { facts.absoluteRangeOf(it) } ?: continue

        out += DrawableHighlight(highlight.id, stored, highlight.colorArgb)
    }
    return out
}
