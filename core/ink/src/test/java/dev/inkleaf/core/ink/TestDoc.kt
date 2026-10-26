package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkSemanticsClassifier
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector

/**
 * 测试用的假文档：n 个块，每块一行，文字里嵌了块号（保证 `indexOf` 唯一命中）。
 *
 * 刻意与 `InkAnchorFactoryTest` 里的同类工具长得像而不共用：那两个模块的测试
 * 必须能各自独立运行（`:core:anchoring` 的测试不该依赖 `:core:ink` 的测试源码集），
 * 而这点重复比"让纯逻辑模块的测试依赖 Android 模块"便宜得多。
 */
internal class TestDoc(val count: Int = 2) {

    val texts: List<String> = List(count) { "第${it}行的文字内容" }
    val full: String = texts.joinToString("\n")

    fun line(index: Int, top: Float, fontSize: Float = 40f, right: Float = 624f): LineBox = LineBox(
        anchor = Selector.Structural("blk[$index]", 0, texts[index].length, 1f),
        rectDoc = Box(24f, top, right, top + fontSize * 1.25f),
        baselineY = top + fontSize * 1.25f - fontSize * 0.2f,
        fontSizePx = fontSize,
    )

    fun lines(fontSize: Float = 40f, top: Float = 0f, right: Float = 624f): List<LineBox> =
        List(count) { line(it, top + it * fontSize * 1.25f, fontSize, right) }

    fun textOf(line: LineBox): String = texts.getOrElse(indexOf(line)) { "" }

    fun absoluteRangeOf(line: LineBox): IntRange? {
        val text = texts.getOrElse(indexOf(line)) { return null }
        val at = full.indexOf(text)
        return if (at < 0) null else at until (at + text.length)
    }

    fun textWindow(line: LineBox, context: Int = 64): String {
        val range = absoluteRangeOf(line) ?: return textOf(line)
        return full.substring(
            (range.first - context).coerceAtLeast(0),
            (range.last + 1 + context).coerceAtMost(full.length),
        )
    }

    fun facts(
        lines: List<LineBox> = lines(),
        fingerprint: String = "fp-ink-test",
        textWindowOf: ((LineBox) -> String)? = null,
    ): LayoutFacts = object : LayoutFacts {
        override val normalizedText: String get() = full
        override val lines: List<LineBox> get() = lines
        override val profile: NormalizationProfile get() = NormalizationProfile()
        override val fingerprint: String get() = fingerprint
        override val docRatio: Float get() = 0.25f
        override val geometry: InkSemanticsClassifier.Geometry
            get() = InkSemanticsClassifier.Geometry(
                contentBox = Box(24f, -100_000f, 900f, 100_000f),
            )

        override fun textOf(line: LineBox): String = this@TestDoc.textOf(line)
        override fun textWindow(line: LineBox): String =
            textWindowOf?.invoke(line) ?: this@TestDoc.textWindow(line)

        override fun absoluteRangeOf(line: LineBox): IntRange? = this@TestDoc.absoluteRangeOf(line)

        // 块这一层的两个方向：假文档的块就是"每块一行、块内偏移从 0 起"，
        // 所以换算就是"块号 ↔ 该行文本在 full 里的位置"。与真实 `DocumentLayoutFacts`
        // （那里靠 `session.blocks`）走的是同一个定义，只是数据来源不同。
        override fun absoluteRangeOf(structural: Selector.Structural): IntRange? {
            val index = blockIndexOf(structural.blockPath) ?: return null
            val text = texts.getOrNull(index) ?: return null
            val at = full.indexOf(text)
            if (at < 0) return null
            val start = (at + structural.startOffset).coerceIn(at, at + text.length)
            val end = (at + structural.endOffset).coerceIn(start, at + text.length)
            return if (end <= start) null else start until end
        }

        override fun structuralOfRange(range: IntRange): Selector.Structural? {
            if (range.last < range.first) return null
            val start = range.first
            val end = range.last + 1
            if (start < 0 || end > full.length) return null
            // 假文档里每块一行：起点落在哪一行的文本里就是哪一个块。
            val index = texts.indexOfFirst { text ->
                val at = full.indexOf(text)
                at >= 0 && start >= at && start <= at + text.length
            }
            if (index < 0) return null
            val text = texts[index]
            val at = full.indexOf(text)
            val localStart = (start - at).coerceIn(0, text.length)
            val localEnd = (end - at).coerceIn(localStart, text.length)
            if (localEnd <= localStart) return null
            return Selector.Structural("blk[$index]", localStart, localEnd, 1f)
        }
    }

    /** 行局部 em → 文档坐标。与渲染层做的事一致。 */
    fun mapToDoc(line: LineBox, localPoints: FloatArray): FloatArray {
        val m = line.matrixInDoc()
        val out = FloatArray(localPoints.size)
        var i = 0
        while (i < localPoints.size) {
            out[i] = m.mapX(localPoints[i], localPoints[i + 1])
            out[i + 1] = m.mapY(localPoints[i], localPoints[i + 1])
            i += 2
        }
        return out
    }

    private fun indexOf(line: LineBox): Int =
        line.anchor.blockPath.substringAfter("blk[").substringBefore("]").toInt()

    private fun blockIndexOf(blockPath: String): Int? =
        blockPath.substringAfter("blk[", "").substringBefore("]", "").toIntOrNull()
}
