package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语义判定的测试。
 *
 * 这里断言的是**判定规则的边界**，不是"某个输入输出对"——语义判定的价值全在
 * 边界上：多一行少一行、多一点少一点，结果从 FOLLOWER 翻成 FREE 是否合理。
 */
class InkSemanticsClassifierTest {

    private val content = Box(left = 24f, top = 0f, right = 900f, bottom = 4000f)
    private val geometry = InkSemanticsClassifier.Geometry(contentBox = content)

    private fun line(index: Int, top: Float, height: Float = 50f) = LineBox(
        anchor = Selector.Structural("blk[$index]", 0, 10, 1f),
        rectDoc = Box(24f, top, 624f, top + height),
        baselineY = top + height - 10f,
        fontSizePx = 40f,
    )

    /** 行带首尾相接（`bottom(i) == top(i+1)`），与 StaticLayout 的真实行为一致。 */
    private fun lines(count: Int, height: Float = 50f) = List(count) { line(it, it * height, height) }

    @Test
    fun `压在某一行的文字上判为跟随文字`() {
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 300f, 40f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(1, verdict.coveredLines)
        assertEquals(1f, verdict.textBandRatio, 1e-4f)
        assertEquals(0f, verdict.marginRatio, 1e-4f)
    }

    @Test
    fun `跨两行但仍在文字上的短笔迹判为跟随文字`() {
        // 关键点：判据是"与文字行带整体的重合度"，不是"与单行的最大重合度"。
        // 一笔跨两行时与**每一行**的重合度都只有 50%，若按单行判就会误判成自由笔迹。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 40f, 300f, 80f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(2, verdict.coveredLines)
        assertEquals(1f, verdict.textBandRatio, 1e-4f)
    }

    @Test
    fun `恰好压在两行交界处的笔迹仍判为跟随文字`() {
        // 行带首尾相接 ⇒ 垂直方向不存在"行间空白"。
        // 这条断言守着 InkSemanticsClassifier 的文档里那条与方案的偏差：
        // 只有当行带相接时，"落在行缝里"才等价于"落在文字上"。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 48f, 300f, 52f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(2, verdict.coveredLines)
        assertEquals(0f, verdict.marginRatio, 1e-4f)
    }

    @Test
    fun `跨六行以上的涂鸦判为自由笔迹`() {
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 300f, 280f), lines(6), geometry)

        assertEquals(InkReflowSemantics.FREE, verdict.semantics)
        assertEquals(6, verdict.coveredLines)
    }

    @Test
    fun `大部分落在左右页边空白的笔迹判为固定在页面上`() {
        // 完全在左侧页边：连一行都不相交。
        val verdict = InkSemanticsClassifier.classify(Box(0f, 10f, 20f, 120f), lines(3), geometry)

        assertEquals(InkReflowSemantics.PINNED, verdict.semantics)
        assertEquals(0, verdict.coveredLines)
        assertEquals(1f, verdict.marginRatio, 1e-4f)
    }

    @Test
    fun `压在正文上但伸进页边的笔迹仍判为跟随文字`() {
        // 一半在正文、一半在页边：marginRatio = 0.5 < 0.70，不应该被判成边注。
        val verdict = InkSemanticsClassifier.classify(Box(14f, 10f, 214f, 40f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(0.05f, verdict.marginRatio, 1e-3f)
    }

    @Test
    fun `落在页眉带里的笔迹判为自由笔迹`() {
        val withHeader = InkSemanticsClassifier.Geometry(
            contentBox = content,
            headerBand = Box(24f, 0f, 900f, 30f),
        )

        val verdict = InkSemanticsClassifier.classify(Box(100f, 2f, 300f, 25f), lines(3), withHeader)

        assertEquals(InkReflowSemantics.FREE, verdict.semantics)
    }

    @Test
    fun `完全水平的一笔（下划线）按贴着文字处理`() {
        // 这是手写笔记里最常见的一类，也是最不该被判成自由笔迹的一类：
        // 判成 FREE 意味着重排后它不跟着文字走，用户会看到一条飘走的横线。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 300f, 10f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(1, verdict.coveredLines)
        assertEquals(1f, verdict.textBandRatio, 0.001f)
    }

    @Test
    fun `完全垂直且只横跨一行的笔迹按贴着文字处理`() {
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 100f, 40f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(1, verdict.coveredLines)
    }

    @Test
    fun `落在页边空白里的一竖（退化盒）按固定在页面上处理`() {
        // 撑开只沿退化的一维做，所以"横向落在页边"的判定仍然按真实墨迹算。
        val verdict = InkSemanticsClassifier.classify(Box(10f, 10f, 10f, 40f), lines(3), geometry)

        assertEquals(InkReflowSemantics.PINNED, verdict.semantics)
        assertEquals(1f, verdict.marginRatio, 0.001f)
    }

    @Test
    fun `点一下留下的一笔按它所在的那一行处理`() {
        // 点是个退化盒（宽高都为 0）。撑开后它落在哪一行就跟哪一行走，
        // 这比"按自由笔迹处理"更接近用户的意思：他是在那一行上点的。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 100f, 10f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FOLLOWER, verdict.semantics)
        assertEquals(1, verdict.coveredLines)
    }

    @Test
    fun `退化盒撑开后仍然判不出来的落到自由笔迹`() {
        // 一条很长的竖线（跨 7 行）：撑开也救不了它，兜底仍然是"不动"。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 100f, 340f), lines(7), geometry)

        assertEquals(InkReflowSemantics.FREE, verdict.semantics)
        assertEquals(7, verdict.coveredLines)
    }

    @Test
    fun `正文之下的退化盒落到自由笔迹而不是随便贴一行`() {
        // 第六行之下没有行了：撑开后与任何行带都不相交，必须落到 FREE。
        val verdict = InkSemanticsClassifier.classify(Box(100f, 900f, 300f, 900f), lines(3), geometry)

        assertEquals(InkReflowSemantics.FREE, verdict.semantics)
        assertEquals(0, verdict.coveredLines)
    }

    @Test
    fun `没有候选行时落在正文区域的笔迹按自由笔迹处理`() {
        val verdict = InkSemanticsClassifier.classify(Box(100f, 10f, 300f, 40f), emptyList(), geometry)

        assertEquals(InkReflowSemantics.FREE, verdict.semantics)
        assertEquals(0, verdict.coveredLines)
    }
}
