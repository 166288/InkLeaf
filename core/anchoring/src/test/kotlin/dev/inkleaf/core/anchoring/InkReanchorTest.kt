package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Affine2D
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动重挂锚点的测试。
 *
 * 这里守两件事，都不是"能不能跑通"：
 *
 * 1. **重挂之后必须真的解得开。** 判据不是字段对不对，而是把新链交给
 *    [AnchorResolver] 能不能得到 `Resolved` —— 重挂的全部意义就在这里，
 *    字段对了但解析不出来等于没救回来（而且症状是"点了一下，笔记还是没出现"）。
 * 2. **点一个都不许动。** 笔迹的形状属于行局部空间，重挂只换"接在哪一行"。
 *    一旦这里掺进"按位置换算"，用户选中的那一行就会变成一堆近似值。
 */
class InkReanchorTest {

    private val doc = ReanchorDoc(3)

    private fun strokeOnLine(
        line: LineBox,
        points: FloatArray = floatArrayOf(0.1f, 0.8f, 0.5f, 0.85f),
        strokeId: String = "s1",
        semantics: InkReflowSemantics = InkReflowSemantics.FOLLOWER,
        frozenMatrix: Affine2D? = null,
    ) = StrokeAnchor(
        strokeId = strokeId,
        anchor = line.anchor,
        semantics = semantics,
        brushId = "pen-black",
        localPoints = points,
        localPressures = FloatArray(points.size / 2) { 1f },
        frozenMatrix = frozenMatrix,
    )

    private fun document(fingerprint: String = doc.fingerprint) =
        InkAnchorFactory.Document(doc.full, doc.profile, fingerprint, docRatio = 0.25f)

    @Test
    fun `重挂之后交给解析器必须解开`() {
        val lines = doc.lines()
        val stroke = strokeOnLine(lines[0])

        val outcome = InkReanchor.attach(
            strokes = listOf(stroke),
            target = lines[2],
            source = doc.source,
            document = document(),
        )
        val done = outcome as InkReanchor.Outcome.Done

        val resolution = AnchorResolver.resolve(
            chain = done.chain,
            currentSourceFingerprint = doc.fingerprint,
            candidates = lines,
            textOf = doc.source::textOf,
            currentProfile = doc.profile,
            contextOf = doc::textWindow,
        )

        assertTrue(
            "重挂之后必须解得开，实际=$resolution",
            resolution is AnchorResolution.Resolved,
        )
        val resolved = resolution as AnchorResolution.Resolved
        assertEquals("必须落在用户选的那一行上", "blk[2]", resolved.line.anchor.blockPath)
        assertEquals("结构型锚点与标注链必须同源", done.chain.structural, resolved.line.anchor)
    }

    @Test
    fun `重挂只换接在哪一行点一个都不动`() {
        val lines = doc.lines()
        val points = floatArrayOf(0.1f, 0.8f, 0.42f, 0.85f, 0.9f, 0.8f)
        // 一笔边注：PINNED + 落笔时的冻结几何（新版写下的边注都是这样）。
        val frozen = Affine2D.emToDoc(fontSizePx = 24f, originLeft = 60f, originTop = 400f)
        val stroke = strokeOnLine(
            lines[0],
            points,
            semantics = InkReflowSemantics.PINNED,
            frozenMatrix = frozen,
        )

        val done = InkReanchor.attach(
            strokes = listOf(stroke),
            target = lines[1],
            source = doc.source,
            document = document(),
        ) as InkReanchor.Outcome.Done

        val moved = done.strokes.single()
        assertEquals("笔迹 id 必须原样保留（它是主键）", "s1", moved.strokeId)
        assertEquals("笔刷不能变", "pen-black", moved.brushId)
        assertEquals("语义分类与挂到哪一行是两件事，不许顺手动它", InkReflowSemantics.PINNED, moved.semantics)
        assertArrayEquals("em 坐标必须逐位不变", points, moved.localPoints, 0f)
        assertArrayEquals("压力也必须原样带走", stroke.localPressures, moved.localPressures, 0f)
        assertEquals("结构型锚点要换成新那一行", lines[1].anchor, moved.anchor)
        assertEquals(
            "冻结几何必须原样保留：重挂改的是它指向哪一行，不是它在哪；" +
                "在这里清掉它，等于用户点一下让笔记回来，笔记却被挪到别处去了",
            frozen,
            moved.frozenMatrix,
        )
    }

    @Test
    fun `重挂写进链里的是当前文档的指纹与口径`() {
        // 这正是孤儿能被救回来的原因：旧链记的是"文件还是原来那一份"这个已经为假的前提。
        val lines = doc.lines()
        val stroke = strokeOnLine(lines[0])

        val done = InkReanchor.attach(
            strokes = listOf(stroke),
            target = lines[1],
            source = doc.source,
            document = document(fingerprint = "fp-换了一版"),
        ) as InkReanchor.Outcome.Done

        assertEquals("fp-换了一版", done.chain.sourceFingerprint)
        assertEquals(doc.profile, done.chain.normalization)

        // 用新指纹解析：旧链会判孤儿，新链必须解开。
        val resolution = AnchorResolver.resolve(
            chain = done.chain,
            currentSourceFingerprint = "fp-换了一版",
            candidates = lines,
            textOf = doc.source::textOf,
            currentProfile = doc.profile,
            contextOf = doc::textWindow,
        )
        assertTrue(resolution is AnchorResolution.Resolved)
    }

    @Test
    fun `引文取的是目标行自己的文字`() {
        val lines = doc.lines()

        val done = InkReanchor.attach(
            strokes = listOf(strokeOnLine(lines[0])),
            target = lines[2],
            source = doc.source,
            document = document(),
        ) as InkReanchor.Outcome.Done

        assertEquals(
            "引文必须是被挂上去的那一行的文字，否则下一次解析会拿旧文字去校验新行",
            doc.texts[2],
            done.chain.textQuote?.exact,
        )
        assertEquals("块内偏移也必须来自目标行", lines[2].anchor, done.chain.structural)
    }

    @Test
    fun `多段笔迹全部挂到同一行且共用同一条链`() {
        val lines = doc.lines()
        val strokes = listOf(
            strokeOnLine(lines[0], strokeId = "s1"),
            strokeOnLine(lines[1], strokeId = "s2"),
        )

        val done = InkReanchor.attach(strokes, lines[2], doc.source, document()) as InkReanchor.Outcome.Done

        assertEquals(2, done.strokes.size)
        assertEquals(
            "两段的检索锚点必须一样（都指向被挂上的那一行）",
            listOf(lines[2].anchor, lines[2].anchor),
            done.strokes.map { it.anchor },
        )
        assertEquals(listOf("s1", "s2"), done.strokes.map { it.strokeId })
    }

    @Test
    fun `没有笔迹点时拒绝而不是写一条空笔记`() {
        val outcome = InkReanchor.attach(emptyList(), doc.lines()[0], doc.source, document())

        val rejected = outcome as InkReanchor.Outcome.Rejected
        assertTrue("要说清为什么，不能只说失败了", rejected.reason.contains("没有笔迹点"))
    }

    @Test
    fun `没有文字的行会被拒绝并说清原因`() {
        val lines = doc.lines()
        // 空行：拿不到引文。挂上去的锚点重排后必然又判孤儿 ——
        // 与其写一条"看起来挂上了、下次打开又消失"的记录，不如当场说清。
        val blank = lines[1]
        val source = ReanchorDoc.Source(doc.texts, doc.full, unanchorable = setOf(1))

        val outcome = InkReanchor.attach(
            strokes = listOf(strokeOnLine(lines[0])),
            target = blank,
            source = source,
            document = document(),
        )

        val rejected = outcome as InkReanchor.Outcome.Rejected
        assertTrue("要说清是没有文字这一条卡住了", rejected.reason.contains("没有文字"))
    }
}

/**
 * 测试用文档：n 个块，每块一行，文字里嵌了序号（保证引文唯一命中）。
 *
 * 与 `InkAnchorFactoryTest` 里的同类工具刻意不共用：那个是那个文件私有的，
 * 而"两个测试各自独立可读"比省 30 行重复更值钱（本工程已定的取向）。
 */
private class ReanchorDoc(val count: Int) {

    val texts: List<String> = List(count) { "第${it}行的文字内容" }
    val full: String = texts.joinToString("\n")
    val profile = NormalizationProfile()
    val fingerprint = "fp-reanchor"
    val source = Source(texts, full)

    fun line(index: Int, top: Float, fontSize: Float = 40f, right: Float = 624f): LineBox = LineBox(
        anchor = Selector.Structural("blk[$index]", 0, texts[index].length, 1f),
        rectDoc = Box(24f, top, right, top + fontSize * 1.25f),
        baselineY = top + fontSize * 1.25f - fontSize * 0.2f,
        fontSizePx = fontSize,
    )

    fun lines(fontSize: Float = 40f, top: Float = 0f, right: Float = 624f): List<LineBox> =
        List(count) { line(it, top + it * fontSize * 1.25f, fontSize, right) }

    /** 该行连同前后 64 字符的窗口 —— 引文前后缀校验需要的正是它。 */
    fun textWindow(line: LineBox, context: Int = 64): String {
        val index = line.anchor.blockPath.substringAfter("blk[").substringBefore("]").toInt()
        val text = texts[index]
        val at = full.indexOf(text)
        if (at < 0) return ""
        return full.substring(maxOf(0, at - context), minOf(full.length, at + text.length + context))
    }

    class Source(
        private val texts: List<String>,
        private val doc: String,
        /** 这些行"取不到绝对区间"，用来模拟空行（行盒有，文字没有）。 */
        private val unanchorable: Set<Int> = emptySet(),
    ) : InkAnchorFactory.LineSource {

        override fun textOf(line: LineBox): String = texts.getOrElse(indexOf(line)) { "" }

        override fun absoluteRangeOf(line: LineBox): IntRange? {
            val index = indexOf(line)
            val text = texts.getOrElse(index) { return null }
            if (index in unanchorable) return null
            val at = doc.indexOf(text)
            return if (at < 0) null else at until (at + text.length)
        }

        private fun indexOf(line: LineBox): Int =
            line.anchor.blockPath.substringAfter("blk[").substringBefore("]").toInt()
    }
}
