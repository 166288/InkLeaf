package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.AnchorResolver
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.UnderlineStyle

/**
 * 下划线 / 波浪线：**结构型装饰**，不是墨迹。
 *
 * 这是 v1 里唯一一种"不是画上去、而是排版画出来"的批注：它没有笔迹点集，
 * 位置完全由锚点链决定，所以改字号、换设备、重排之后它一定还贴在那几个字下面
 * （墨迹做不到这一点，墨迹只能说"我尽量跟着走"）。
 *
 * 与高亮（[DrawableHighlight]）共用同一条解析路径不是巧合：两者都是"一条链 + 一段字符"，
 * 差别只在画什么 —— 高亮铺一条色带，下划线在基线上画一条线。
 * 因此它们的"解不开就不画"判据也必须一致：解不开的链画到猜出来的行上，
 * 用户看到的是"我的下划线跑到隔壁段去了"，比不画更糟。
 */

/**
 * 一条**可以直接画**的下划线：它落在哪一行的哪一段横线上。
 *
 * 为什么带 [baselineY] 与 [fontSizePx] 而不是一个矩形：
 * 线的粗细与它离基线的距离都以 **em** 存（[UnderlineStyle]），
 * em → px 必须用**那一行的**字号。把字号随笔迹带下来，
 * 渲染层就不需要再回去查行盒，也就不会出现"查错了行、线变粗一档"。
 */
data class DrawableUnderline(
    val annotationId: String,
    /** 文档坐标的横向范围（闭区间两端的 x）。 */
    val left: Float,
    val right: Float,
    /** 这一行的基线 y（文档坐标）。线的位置 = baselineY + offsetEm × fontSizePx。 */
    val baselineY: Float,
    /** 这一行的字号（px），em → px 换算用。 */
    val fontSizePx: Float,
    val colorArgb: Int,
    val style: UnderlineStyle,
)

/**
 * 下划线 → 可以画的线。**只有解析到行盒的才画**。
 *
 * 一条链可能覆盖多个视觉行（软换行的段落里，一段下划线横跨两行是正常的），
 * 所以这里按"与链的区间重叠的行"逐行产出，而不是一条链一条线 ——
 * 否则第二行会凭空少一截，用户会以为下划线被截断了。
 *
 * 横向范围取**行盒的宽度**，而不是精确到某几个字：文本层没有字距表
 * （`LayoutFacts` 也没有暴露逐字矩形），估出来的"半句话下划线"会骗人。
 * 这与锚定精度 L2（按行）是同一条边界，M11 的 PDF 高亮也踩在同一个精度上。
 */
fun underlineBands(
    underlines: List<Annotation.Underline>,
    facts: LayoutFacts,
): List<DrawableUnderline> {
    val out = ArrayList<DrawableUnderline>(underlines.size)

    for (underline in underlines) {
        // 一条下划线有**多条**链（每行一条，见 Annotation.Underline.anchors 的注释）。
        for (chain in underline.anchors) {
            val resolution = AnchorResolver.resolve(
                chain = chain,
                currentSourceFingerprint = facts.fingerprint,
                candidates = facts.lines,
                textOf = facts::textOf,
                currentProfile = facts.profile,
                contextOf = facts::textWindow,
            )
            if (resolution !is AnchorResolution.Resolved) continue

            // 链里存的那一段偏移才是"标了哪几个字"。取不到就退回到解到的那一行 ——
            // 下划线是行级装饰，退回整行不会改变它的语义（高亮不同：高亮必须精确）。
            val range = chain.structural?.let { facts.absoluteRangeOf(it) }
            val lines = if (range != null) {
                val overlapping = facts.lines.filter { line ->
                    val lineRange = facts.absoluteRangeOf(line) ?: return@filter false
                    lineRange.first <= range.last && lineRange.last >= range.first
                }
                overlapping.ifEmpty { listOf(resolution.line) }
            } else {
                listOf(resolution.line)
            }

            for (line in lines) {
                val left = line.rectDoc.left
                val right = line.rectDoc.right
                // 零宽的行（空行）画不出一条线：一条长度 0 的线在 Canvas 上不存在，
                // 而"标了但看不见"与"没标上"在用户眼里是同一件事。
                if (right - left < 1f) continue
                out += DrawableUnderline(
                    annotationId = underline.id,
                    left = left,
                    right = right,
                    baselineY = line.baselineY,
                    fontSizePx = line.fontSizePx.takeIf { it > 0f } ?: DEFAULT_FONT_SIZE_PX,
                    colorArgb = underline.colorArgb,
                    style = underline.style,
                )
            }
        }
    }
    return out
}

/**
 * 这一行的下划线区间（"把这一行画上下划线"要写进链的那一段）。
 *
 * 复用 [lineHighlightRange] 而不是新写一遍：两者要的都是"这一行去掉首尾空白之后
 * 真正有字的那一段"。各写一份的后果是"高亮从缩进处开始、下划线从字开始" ——
 * 同一行上两种装饰的起点差两个字，看起来像 bug，实际上是两处口径。
 */
fun LayoutFacts.underlineRangeOf(line: LineBox): IntRange? = lineHighlightRange(line)

private const val DEFAULT_FONT_SIZE_PX = 16f
