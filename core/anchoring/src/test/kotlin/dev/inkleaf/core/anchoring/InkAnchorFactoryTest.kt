package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试用文档：n 个块，每块一行。
 *
 * 每块的文字里嵌了自己的序号，因此 `indexOf` 一定唯一命中 —— 这一点很关键：
 * 锚点链的引文匹配如果因为"两行文字相同"而命中两次，测试就会去测歧义分支，
 * 而这里要测的是正常闭环。
 */
private class Doc(val count: Int) {

    val texts: List<String> = List(count) { "第${it}行的文字内容" }
    val full: String = texts.joinToString("\n")
    val profile = NormalizationProfile()
    val fingerprint = "fp-ink-test"
    val source = Source(texts, full)

    /** 行盒：纵向 [top, top + 1.25×字号)，与"字号 + 行距"的真实量级一致。 */
    fun line(index: Int, top: Float, fontSize: Float = 40f, right: Float = 624f): LineBox = LineBox(
        anchor = Selector.Structural("blk[$index]", 0, texts[index].length, 1f),
        rectDoc = Box(24f, top, right, top + fontSize * 1.25f),
        baselineY = top + fontSize * 1.25f - fontSize * 0.2f,
        fontSizePx = fontSize,
    )

    fun lines(fontSize: Float = 40f, top: Float = 0f, right: Float = 624f): List<LineBox> =
        List(count) { line(it, top + it * fontSize * 1.25f, fontSize, right) }

    fun request(
        points: FloatArray,
        lineList: List<LineBox> = lines(),
        source: InkAnchorFactory.LineSource = this.source,
        semantics: InkReflowSemantics? = null,
    ) = InkAnchorFactory.Request(
        strokeId = "s1",
        docPoints = points,
        docPressures = FloatArray(points.size / 2) { 1f },
        brushId = "pen-black",
        lines = lineList,
        geometry = GEOMETRY,
        source = source,
        document = InkAnchorFactory.Document(full, profile, fingerprint, docRatio = 0.25f),
        semanticsOverride = semantics,
    )

    /**
     * 该行连同前后上下文的文本窗口。
     *
     * 这就是 `AnchorResolver.contextOf` 需要的输入：引文的 prefix/suffix 是从
     * 全文里取的，只有给足上下文才能校验它们。
     */
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

private val GEOMETRY = InkSemanticsClassifier.Geometry(
    contentBox = Box(24f, -100_000f, 900f, 100_000f),
)

/** 行局部 em → 文档坐标。渲染层要做的正是这件事。 */
private fun mapBack(line: LineBox, localPoints: FloatArray): FloatArray {
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

private fun bboxOf(points: FloatArray): Box {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    var i = 0
    while (i < points.size) {
        left = minOf(left, points[i])
        right = maxOf(right, points[i])
        top = minOf(top, points[i + 1])
        bottom = maxOf(bottom, points[i + 1])
        i += 2
    }
    return Box(left, top, right, bottom)
}

/**
 * 笔迹锚定的测试。
 *
 * 最重要的一条是 [InkAnchorFactoryTest.行局部 em 坐标能精确还原回文档坐标]：
 * 它把"L2 锚定"这个说法变成一个可以被证伪的等式。其余用例都围绕它展开 ——
 * 切段是否按行、重排后是否还在同一行、拿不到引文时是否明确跳过。
 */
class InkAnchorFactoryTest {

    @Test
    fun `单行笔迹得到一段跟随锚点`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 30f)

        val result = InkAnchorFactory.anchor(doc.request(points))

        assertNotNull(result)
        val anchored = result!!
        assertEquals(InkReflowSemantics.FOLLOWER, anchored.semantics)
        assertEquals(1, anchored.segments.size)
        assertEquals(0, anchored.skippedSegments)
        assertEquals(0, anchored.droppedSegments)
        assertEquals(Box(100f, 10f, 300f, 30f), anchored.bboxDoc)

        val segment = anchored.segments[0]
        assertEquals("s1", segment.stroke.strokeId)
        assertEquals("pen-black", segment.stroke.brushId)
        assertEquals("blk[0]", segment.stroke.anchor.blockPath)
        assertEquals(doc.profile, segment.chain.normalization)
        assertEquals(doc.fingerprint, segment.chain.sourceFingerprint)
        assertNotNull("单行笔迹应当能拿到引文", segment.chain.textQuote)
        assertTrue(
            "锚点链必须以进度兜底选择器结尾（否则孤儿态无处可去）",
            segment.chain.selectors.last() is Selector.ProgressHint,
        )
    }

    @Test
    fun `行局部 em 坐标能精确还原回文档坐标`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 30f)

        val segment = InkAnchorFactory.anchor(doc.request(points))!!.segments.single()
        val restored = mapBack(segment.line, segment.stroke.localPoints)

        for (i in points.indices) {
            assertEquals("第 $i 个分量必须逐位还原", points[i], restored[i], 1e-3f)
        }
    }

    @Test
    fun `字号翻倍时笔迹等比放大而不跑位`() {
        // Android 14+ 的非线性字号缩放（方案 §5.7）：字号会变，且不是所有字号
        // 都按同一倍率放大。但只要点存的是 em，映射回去就一定贴合新行盒 ——
        // 这条断言就是这个论断的可执行版本。
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val segment = InkAnchorFactory.anchor(doc.request(points))!!.segments.single()

        val bigger = doc.line(index = 0, top = 200f, fontSize = 80f)
        val moved = mapBack(bigger, segment.stroke.localPoints)

        var i = 0
        while (i < points.size) {
            // 原点 (24, 200)、字号 2× ⇒ x' = 24 + 2×(x-24)，y' = 200 + 2×y
            assertEquals(24f + 2f * (points[i] - 24f), moved[i], 1e-2f)
            assertEquals(200f + 2f * points[i + 1], moved[i + 1], 1e-2f)
            i += 2
        }
    }

    @Test
    fun `跨两行的一笔切成两段并各自锚定到自己的行`() {
        val doc = Doc(2)
        // y=10,20 落在第 0 行（0..50）；y=70,80 落在第 1 行（50..100）
        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 70f, 400f, 80f)

        val anchored = InkAnchorFactory.anchor(doc.request(points))!!

        assertEquals(2, anchored.segments.size)
        assertEquals("blk[0]", anchored.segments[0].stroke.anchor.blockPath)
        assertEquals("blk[1]", anchored.segments[1].stroke.anchor.blockPath)
        // 段 id 必须唯一（它是数据库主键）：首段沿用调用方的 id，其余派生。
        assertEquals("s1", anchored.segments[0].stroke.strokeId)
        assertEquals("s1#1", anchored.segments[1].stroke.strokeId)

        // 每段都要能独立还原出自己那几个点，且点数之和等于原始点数。
        val restored = anchored.segments.flatMap { segment ->
            val mapped = mapBack(segment.line, segment.stroke.localPoints)
            mapped.toList().chunked(2)
        }
        assertEquals(points.size / 2, restored.size)
        for ((index, pair) in restored.withIndex()) {
            assertEquals(points[index * 2], pair[0], 1e-3f)
            assertEquals(points[index * 2 + 1], pair[1], 1e-3f)
        }
    }

    @Test
    fun `一笔跨两行时语义仍按整体判定为跟随文字`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 70f, 400f, 80f)

        val anchored = InkAnchorFactory.anchor(doc.request(points))!!

        assertEquals(InkReflowSemantics.FOLLOWER, anchored.semantics)
        assertTrue(anchored.segments.all { it.stroke.semantics == InkReflowSemantics.FOLLOWER })
    }

    @Test
    fun `锚点链立刻能解析回同一行形成写入与解析的闭环`() {
        val doc = Doc(3)
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val segment = InkAnchorFactory.anchor(doc.request(points))!!.segments.single()

        val resolution = AnchorResolver.resolve(
            chain = segment.chain,
            currentSourceFingerprint = doc.fingerprint,
            candidates = doc.lines(),
            textOf = doc.source::textOf,
            currentProfile = doc.profile,
            contextOf = doc::textWindow,
        )

        assertTrue("写入后立刻解析应当直接命中，实际 $resolution", resolution is AnchorResolution.Resolved)
        val resolved = resolution as AnchorResolution.Resolved
        assertTrue("一致性必须 >= 0.9，实际 ${resolved.confidence}", resolved.confidence >= 0.9f)
        assertEquals("blk[0]", resolved.line.anchor.blockPath)
    }

    @Test
    fun `重排后仍解析到同一行且笔迹落在新行盒内`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val segment = InkAnchorFactory.anchor(doc.request(points))!!.segments.single()

        // 换字号（40→60）、换行位（下移 300px）、换栏宽（624→400）
        val reflowed = doc.lines(fontSize = 60f, top = 300f, right = 400f)

        val resolution = AnchorResolver.resolve(
            chain = segment.chain,
            currentSourceFingerprint = doc.fingerprint,
            candidates = reflowed,
            textOf = doc.source::textOf,
            currentProfile = doc.profile,
            contextOf = doc::textWindow,
        )

        assertTrue("重排后应当仍能找到那一行，实际 $resolution", resolution is AnchorResolution.Resolved)
        val line = (resolution as AnchorResolution.Resolved).line
        assertEquals("blk[0]", line.anchor.blockPath)

        // 这就是"跟随文字"的全部意义：笔迹必须落在新行盒里面。
        val box = bboxOf(mapBack(line, segment.stroke.localPoints))
        assertTrue("笔迹上边跑出行盒：$box vs ${line.rectDoc}", box.top >= line.rectDoc.top - 0.5f)
        assertTrue("笔迹下边跑出行盒：$box vs ${line.rectDoc}", box.bottom <= line.rectDoc.bottom + 0.5f)
        assertTrue("笔迹左边跑出行盒：$box vs ${line.rectDoc}", box.left >= line.rectDoc.left - 0.5f)
        assertTrue("笔迹右边跑出行盒：$box vs ${line.rectDoc}", box.right <= line.rectDoc.right + 0.5f)
    }

    @Test
    fun `换归一化口径后锚点判孤儿而不是硬凑`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val segment = InkAnchorFactory.anchor(doc.request(points))!!.segments.single()

        // 归一化口径变了（这里用版本号体现）⇒ 字符偏移整体平移，必须判孤儿
        val otherProfile = NormalizationProfile(version = 2)

        val resolution = AnchorResolver.resolve(
            chain = segment.chain,
            currentSourceFingerprint = doc.fingerprint,
            candidates = doc.lines(),
            textOf = doc.source::textOf,
            currentProfile = otherProfile,
        )

        assertTrue("口径不符必须判孤儿，实际 $resolution", resolution is AnchorResolution.Orphaned)
    }

    @Test
    fun `落在页边的笔迹判为固定并按最近一行锚定`() {
        val doc = Doc(2)
        // 完全在左侧页边（正文栏从 x=24 开始）
        val points = floatArrayOf(4f, 10f, 16f, 20f)

        val anchored = InkAnchorFactory.anchor(doc.request(points))!!

        assertEquals(InkReflowSemantics.PINNED, anchored.semantics)
        assertEquals(1, anchored.segments.size)
        assertEquals("blk[0]", anchored.segments.single().stroke.anchor.blockPath)
        assertTrue(anchored.segments.single().stroke.semantics == InkReflowSemantics.PINNED)
    }

    @Test
    fun `固定与自由笔迹存下落笔那一刻的冻结几何`() {
        // PINNED / FREE 的定义是"墨迹本身不平移不缩放"（方案 §6.3.3）。要做到这一点，
        // 库里必须有一份**绝对位置**：只存行局部 em 坐标的话，行被搬到别处时
        // 它们就只能跟着走 —— 那正是 M3 记录 §13 第 3 条记下的老账。
        val doc = Doc(2)
        val expected = doc.lines()[0].matrixInDoc()

        val pinned = InkAnchorFactory.anchor(doc.request(floatArrayOf(4f, 10f, 16f, 20f)))!!
        val free = InkAnchorFactory.anchor(
            doc.request(floatArrayOf(100f, 10f, 200f, 20f), semantics = InkReflowSemantics.FREE),
        )!!

        assertEquals(InkReflowSemantics.PINNED, pinned.semantics)
        assertEquals(InkReflowSemantics.FREE, free.semantics)
        assertEquals(
            "冻结几何必须就是落笔那一行的变换（笔迹与纸的相对位置全靠它）",
            expected,
            pinned.segments.single().stroke.frozenMatrix,
        )
        assertEquals(expected, free.segments.single().stroke.frozenMatrix)
    }

    @Test
    fun `跟随文字的笔迹不存冻结几何`() {
        // 存了就等于把"跟着文字走"写坏：解析时会优先用冻结几何画，
        // 于是下划线会留在原处、而它下面那行文字已经换行了。
        val doc = Doc(2)

        val follower = InkAnchorFactory.anchor(doc.request(floatArrayOf(100f, 10f, 200f, 20f)))!!

        assertEquals(InkReflowSemantics.FOLLOWER, follower.semantics)
        assertNull(follower.segments.single().stroke.frozenMatrix)
    }

    @Test
    fun `显式指定语义时以用户为准`() {
        val doc = Doc(2)
        val points = floatArrayOf(100f, 10f, 200f, 20f)

        val anchored = InkAnchorFactory.anchor(
            doc.request(points, semantics = InkReflowSemantics.FREE),
        )!!

        // 判定结果本身仍是 FOLLOWER（事实没变），但存下来的语义听用户的。
        assertEquals(InkReflowSemantics.FOLLOWER, anchored.verdict.semantics)
        assertEquals(InkReflowSemantics.FREE, anchored.semantics)
        assertEquals(InkReflowSemantics.FREE, anchored.segments.single().stroke.semantics)
    }

    @Test
    fun `跨六行以上的涂鸦判为自由笔迹但仍逐行留下锚点`() {
        val doc = Doc(6)
        // 每行两个点：少于两点会被当成噪声并回上一段，那样就测不到"六段"。
        val points = FloatArray(6 * 4)
        for (index in 0 until 6) {
            points[index * 4] = 100f + index
            points[index * 4 + 1] = index * 50f + 10f
            points[index * 4 + 2] = 120f + index
            points[index * 4 + 3] = index * 50f + 20f
        }

        val anchored = InkAnchorFactory.anchor(doc.request(points))!!

        assertEquals(InkReflowSemantics.FREE, anchored.semantics)
        assertEquals(6, anchored.segments.size)
        assertEquals(listOf("blk[0]", "blk[1]", "blk[2]", "blk[3]", "blk[4]", "blk[5]"),
            anchored.segments.map { it.stroke.anchor.blockPath })
    }

    @Test
    fun `点数不足或为奇数时拒绝锚定而不是猜`() {
        val doc = Doc(2)

        assertNull("只有一个点无法构成笔迹", InkAnchorFactory.anchor(doc.request(floatArrayOf(1f, 2f))))
        assertNull(
            "奇数个分量说明上游有 bug，宁可拒绝",
            InkAnchorFactory.anchor(doc.request(floatArrayOf(1f, 2f, 3f))),
        )
    }

    @Test
    fun `没有候选行时拒绝锚定`() {
        val doc = Doc(2)

        assertNull(
            InkAnchorFactory.anchor(
                doc.request(floatArrayOf(100f, 10f, 200f, 20f, 300f, 30f), lineList = emptyList()),
            ),
        )
    }

    @Test
    fun `拿不到绝对字符区间时跳过该段并计数`() {
        val doc = Doc(2)
        val source = Doc.Source(doc.texts, doc.full, unanchorable = setOf(1))
        // 笔迹整个落在第 1 行上
        val points = floatArrayOf(100f, 60f, 200f, 70f, 300f, 80f)

        val anchored = InkAnchorFactory.anchor(doc.request(points, source = source))!!

        assertTrue("没有引文的锚点在重排后必然判孤儿，不该存下来", anchored.segments.isEmpty())
        assertEquals(1, anchored.skippedSegments)
        assertEquals(1, anchored.totalSegments)
    }

    @Test
    fun `超过段数上限的部分被明确丢弃并计数`() {
        // 70 行各两点 ⇒ 70 段，上限 64 ⇒ 6 段被丢弃。
        // 断言"丢弃被计数"而不是"悄悄消失"：涂鸦丢墨迹是用户能看见的事，
        // 必须能在日志与统计里对上账。
        val doc = Doc(70)
        val points = FloatArray(70 * 4)
        for (index in 0 until 70) {
            points[index * 4] = 100f + index
            points[index * 4 + 1] = index * 50f + 10f
            points[index * 4 + 2] = 120f + index
            points[index * 4 + 3] = index * 50f + 20f
        }

        val anchored = InkAnchorFactory.anchor(doc.request(points))!!

        assertEquals(InkAnchorFactory.MAX_SEGMENTS_PER_STROKE, anchored.segments.size)
        assertEquals(6, anchored.droppedSegments)
        assertEquals(70, anchored.totalSegments)
    }
}
