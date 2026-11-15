package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox

/**
 * 笔迹重排语义判定 —— 方案 §6.3.3 的可执行版本。
 *
 * 为什么这件事必须自动化：用户不会在画下划线之前先声明"这是一条下划线"。
 * 分类错了的后果是不对称的 —— 把边注误判成跟随文字，重排后它会**压到正文上**；
 * 把下划线误判成自由笔迹，它只是**不跟着动**。所以两类都判不出来时，
 * 默认落到 [InkReflowSemantics.FREE]（"不动"比"乱动"安全）。
 *
 * ## 与方案的一处偏差（有据）
 *
 * §6.3.3 把"落在**段间空白区**"也列为 PINNED 的判据。在当前的重排排版里
 * 这条判据**无法成立**：`StaticLayout` 在 `setIncludePad(false)` 下相邻行的
 * `getLineBottom(i) == getLineTop(i+1)`，行带是**首尾相接**的，
 * 垂直方向上根本不存在"行间空白"可以判。因此本实现只用**横向**判据
 * （左右页边空白）判定 PINNED。这一点由 `PaginationTest` 里的相邻行连续性
 * 断言守着：如果哪天行带不再相接，那条断言会先失败，而不是让这里的判定静默变形。
 *
 * ## 退化包围盒（完全水平 / 完全垂直 / 一个点）
 *
 * 面积比例为 0 的盒子**不放弃判定**，而是先沿退化的那一维撑到
 * [DEGENERATE_MIN_EXTENT_PX] 再走同一套规则。原因是手写笔记里最常见的一类
 * 恰恰是"完全水平的一笔"——下划线。若因为 `height == 0` 就判 FREE，
 * 这条下划线在重排后**不跟着文字走**，用户看到的就是一条飘走的横线，
 * 而它明明贴在某一行上。撑开之后仍然判不出来的（例如离正文很远的竖线），
 * 照旧落到 FREE —— 兜底那条"不动比乱动安全"没有被取消，只是不再无差别地
 * 吞掉"其实能判出来"的情况。
 */
object InkSemanticsClassifier {

    /** 与行带的面积重合度下限：达到它才算"贴着文字"。 */
    const val FOLLOWER_MIN_BAND_RATIO = 0.60f

    /** 跟随文字的笔迹不允许跨太多行，否则"跟随"本身就失去意义。 */
    const val FOLLOWER_MAX_COVERED_LINES = 3

    /** 落在左右页边空白里的面积占比下限。 */
    const val PINNED_MIN_MARGIN_RATIO = 0.70f

    /** 跨这么多行以上的一律按自由笔迹处理（§6.3.3）。 */
    const val FREE_MIN_COVERED_LINES = 6

    /** 页眉/页脚带的覆盖判定阈值。 */
    const val BAND_MIN_COVERAGE = 0.60f

    /**
     * 退化包围盒撑开后的最小厚度。
     *
     * 取 1px 而不是"按行高折算"：文档坐标的量级里正文是几十像素，
     * 1px 是"几乎为零但确实不为零"的下限，既能让面积比例有意义，
     * 又不会把一笔横向的墨迹撑成一条竖带（那会改变"横向伸进页边"的判定）。
     */
    const val DEGENERATE_MIN_EXTENT_PX = 1f

    /**
     * 版式事实。
     *
     * [contentBox] 是正文栏（含左右边距的**内侧**边界），它不是"某一行"——
     * 而是"文字可能出现的横向范围"。行盒的 `rectDoc` 横向只覆盖该行的实际
     * 文字（短行很窄），拿它判"是不是落在页边空白"会把短行右侧的空白
     * 误判成正文。这是刻意区分这两个矩形的唯一原因。
     */
    data class Geometry(
        val contentBox: Box,
        val headerBand: Box? = null,
        val footerBand: Box? = null,
    )

    data class Verdict(
        val semantics: InkReflowSemantics,
        /** 与笔迹包围盒相交的行数（退化包围盒按撑开后的盒子算，见 [inflateDegenerate]）。 */
        val coveredLines: Int,
        /** 包围盒被所有行带覆盖的面积比例（"有多少墨迹压在文字上"）。 */
        val textBandRatio: Float,
        /** 包围盒落在左右页边空白里的面积比例。 */
        val marginRatio: Float,
        /** 判定理由，直接给测试与调试用；不面向用户。 */
        val reason: String,
    )

    fun classify(bbox: Box, lines: List<LineBox>, geometry: Geometry): Verdict {
        // 退化包围盒先撑到最小可判定厚度，再走下面同一套规则（理由见类注释）。
        val box = inflateDegenerate(bbox)
        val bands = lines.map { bandOf(it, geometry.contentBox) }
        val covered = bands.count { it.intersects(box) }
        val textBandRatio = bandCoverage(box, bands)
        val marginRatio = marginRatioOf(box, geometry.contentBox)

        fun verdict(semantics: InkReflowSemantics, reason: String) =
            Verdict(semantics, covered, textBandRatio, marginRatio, reason)

        val header = geometry.headerBand?.let { box.coverageRatio(it) } ?: 0f
        if (header >= BAND_MIN_COVERAGE) {
            return verdict(InkReflowSemantics.FREE, "大部分落在页眉带内（${pct(header)}）")
        }

        val footer = geometry.footerBand?.let { box.coverageRatio(it) } ?: 0f
        if (footer >= BAND_MIN_COVERAGE) {
            return verdict(InkReflowSemantics.FREE, "大部分落在页脚带内（${pct(footer)}）")
        }

        if (covered >= FREE_MIN_COVERED_LINES) {
            return verdict(InkReflowSemantics.FREE, "跨 $covered 行，跨度过大")
        }

        if (marginRatio >= PINNED_MIN_MARGIN_RATIO) {
            return verdict(InkReflowSemantics.PINNED, "${pct(marginRatio)} 的面积落在左右页边空白")
        }

        if (textBandRatio >= FOLLOWER_MIN_BAND_RATIO && covered < FOLLOWER_MAX_COVERED_LINES) {
            return verdict(
                InkReflowSemantics.FOLLOWER,
                "与文字行带重合 ${pct(textBandRatio)}，跨 $covered 行",
            )
        }

        return verdict(InkReflowSemantics.FREE, "既不贴合单行也不在页边空白，按自由笔迹处理")
    }

    /** 行的"文字带"：纵向取该行的高，横向取整个正文栏。 */
    private fun bandOf(line: LineBox, content: Box): Box = Box(
        left = content.left,
        top = line.rectDoc.top,
        right = content.right,
        bottom = line.rectDoc.bottom,
    )

    /**
     * 把退化的包围盒撑到最小可判定厚度。
     *
     * 只撑**退化的一维**，并且关于原位置**对称**撑开：完全水平的笔迹横向范围
     * 保持不变，所以"横向伸进页边"的判定仍按真实墨迹计算；对称撑开则保证
     * 无论笔迹压在行的上沿还是下沿，判定结果都一样（不会因为偏向一侧而翻面）。
     */
    private fun inflateDegenerate(box: Box): Box {
        val half = DEGENERATE_MIN_EXTENT_PX / 2f
        val left: Float
        val right: Float
        if (box.width <= 0f) {
            val center = (box.left + box.right) / 2f
            left = center - half
            right = center + half
        } else {
            left = box.left
            right = box.right
        }
        val top: Float
        val bottom: Float
        if (box.height <= 0f) {
            val center = (box.top + box.bottom) / 2f
            top = center - half
            bottom = center + half
        } else {
            top = box.top
            bottom = box.bottom
        }
        return if (left == box.left && right == box.right && top == box.top && bottom == box.bottom) {
            box
        } else {
            Box(left, top, right, bottom)
        }
    }

    private fun bandCoverage(bbox: Box, bands: List<Box>): Float {
        if (bbox.area <= 0f) return 0f
        var sum = 0f
        for (band in bands) {
            if (band.intersects(bbox)) sum += band.intersection(bbox).area
        }
        // 上限夹到 1：调用方若传进互相重叠的行盒，这里不该报出 >100% 的比例。
        return (sum / bbox.area).coerceIn(0f, 1f)
    }

    private fun marginRatioOf(bbox: Box, content: Box): Float {
        if (bbox.area <= 0f) return 0f
        val leftWidth = (minOf(bbox.right, content.left) - bbox.left).coerceAtLeast(0f)
        val rightWidth = (bbox.right - maxOf(bbox.left, content.right)).coerceAtLeast(0f)
        return ((leftWidth + rightWidth) * bbox.height / bbox.area).coerceIn(0f, 1f)
    }

    private fun pct(ratio: Float): String = "${(ratio * 100).toInt()}%"
}
