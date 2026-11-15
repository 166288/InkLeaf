package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.StrokeAnchor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量解析的两件事：**该省的时候真的省了**，以及**省了之后结果没变**。
 *
 * 这两条必须一起测。只测"省了"（计数变小）会让"少算了不该省的"悄悄通过；
 * 只测"结果一样"则永远测不出缓存有没有生效 —— 结果本来就该永远一样。
 */
class InkResolveCacheTest {

    private val doc = TestDoc(2)
    private val pen = BrushSpec.DEFAULT_PEN

    private fun writtenStroke(
        docPoints: FloatArray,
        semantics: InkReflowSemantics? = null,
        strokeId: String = "s1",
        annotationId: String = "ink-1",
    ): Pair<Annotation.Ink, List<StrokeAnchor>> {
        val lines = doc.lines()
        val result = InkAnchorFactory.anchor(
            InkAnchorFactory.Request(
                strokeId = strokeId,
                docPoints = docPoints,
                docPressures = FloatArray(docPoints.size / 2) { 1f },
                brushId = pen.name,
                lines = lines,
                geometry = doc.facts(lines).geometry,
                source = doc.facts(lines).lineSource(),
                document = doc.facts(lines).document(),
                semanticsOverride = semantics,
            ),
        )!!
        val segment = result.segments.single()
        val ink = Annotation.Ink(
            id = annotationId,
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = segment.chain,
            semantics = segment.stroke.semantics,
            strokeIds = listOf(segment.stroke.strokeId),
        )
        return ink to listOf(segment.stroke)
    }

    private fun resolveWith(
        cache: InkResolveCache,
        inks: List<Annotation.Ink>,
        strokes: Map<String, List<StrokeAnchor>>,
        facts: LayoutFacts = doc.facts(),
    ): InkResolution = cache.resolve(
        inks = inks,
        strokesOf = { strokes[it] ?: emptyList() },
        brushesOf = { listOf(pen) },
        facts = facts,
    )

    @Test
    fun `第二轮不重算任何一条_而结果与第一轮逐点相同`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(300f, 20f, 400f, 35f))
        val facts = doc.facts()
        val cache = InkResolveCache()
        val map = mapOf(ink.id to strokes)

        val first = resolveWith(cache, listOf(ink), map, facts)
        assertEquals("第一轮必须真的算过", 1, cache.lastComputedCount)

        val second = resolveWith(cache, listOf(ink), map, facts)
        assertEquals("同一份内容不该再算一遍", 0, cache.lastComputedCount)

        assertEquals(first.drawable.size, second.drawable.size)
        assertArrayEquals(
            "缓存不能改变结果（逐点比：差一点也是差）",
            first.drawable.single().docPoints,
            second.drawable.single().docPoints,
            0f,
        )
        assertEquals(first.records.single().state, second.records.single().state)
    }

    @Test
    fun `多写一条只多算一条`() {
        val (first, firstStrokes) = writtenStroke(floatArrayOf(300f, 20f, 400f, 35f), annotationId = "ink-1")
        val facts = doc.facts()
        val cache = InkResolveCache()
        val map = mutableMapOf(first.id to firstStrokes)

        resolveWith(cache, listOf(first), map, facts)
        assertEquals(1, cache.lastComputedCount)

        // 写到第 500 条时的处境：前 499 条一个字都没变，只有新的一笔是新的。
        repeated(6) { i ->
            val (ink, strokes) = writtenStroke(
                floatArrayOf(300f + i, 20f, 400f + i, 35f),
                strokeId = "s-$i",
                annotationId = "ink-extra-$i",
            )
            map[ink.id] = strokes
        }
        val all = listOf(first) + repeatedInks(map, "ink-extra-")
        val after = resolveWith(cache, all, map, facts)
        assertEquals("只该重算新加的那几条", 6, cache.lastComputedCount)
        assertEquals(all.size, after.records.size)
    }

    @Test
    fun `排版换了就一条都不能留`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(300f, 20f, 400f, 35f))
        val cache = InkResolveCache()
        val map = mapOf(ink.id to strokes)

        resolveWith(cache, listOf(ink), map, doc.facts())
        assertEquals(1, cache.lastComputedCount)

        // 新的 facts 对象 = 新的排版（字号/行距/页宽变了）。每条笔记的结论都
        // 依赖行盒，所以一条都不能留 —— 这是缓存唯一的失效条件，也是最贵的错。
        val relaid = doc.facts(lines = doc.lines(fontSize = 44f))
        resolveWith(cache, listOf(ink), map, relaid)
        assertEquals("换了排版必须全部重算", 1, cache.lastComputedCount)
    }

    @Test
    fun `删掉的笔记不会一直占着缓存`() {
        val (a, aStrokes) = writtenStroke(floatArrayOf(300f, 20f, 400f, 35f), annotationId = "ink-a")
        val (b, bStrokes) = writtenStroke(
            floatArrayOf(300f, 70f, 400f, 85f), strokeId = "s-b", annotationId = "ink-b",
        )
        val facts = doc.facts()
        val cache = InkResolveCache()
        val map = mapOf(a.id to aStrokes, b.id to bStrokes)

        resolveWith(cache, listOf(a, b), map, facts)
        assertEquals(2, cache.lastComputedCount)

        // 删掉 b 再解析：b 的结果不该被算（它已经不在快照里）。
        resolveWith(cache, listOf(a), map, facts)
        assertEquals("留下的那条不必重算", 0, cache.lastComputedCount)

        // 再把它加回来（撤销删除）：必须重新解析，而不是翻出旧结论。
        // 旧结论在这里恰好也正确，但当它期间被改过（撤销后重写）就会是错的 ——
        // 所以「删过就清掉」是语义要求，不是性能优化。
        resolveWith(cache, listOf(a, b), map, facts)
        assertEquals("被删过的笔记回来必须重算", 1, cache.lastComputedCount)
    }

    /**
     * 这条是整套缓存里最容易写错、也最难在界面上认出来的地方。
     *
     * 落位（PINNED 笔迹被挪到目标行旁边的空白）是**就地**改点数组的。
     * 如果缓存把同一个对象直接交给 [InkResolver.assemble]，第二轮就会在
     * 已经挪过的点上再挪一次 —— 每抬一次笔，那条边注就往下走一点。
     * 用户看到的是"这条笔记自己会漂"，而每一轮都只漂一点点，很难归因。
     */
    @Test
    fun `参与落位的笔迹_反复解析不会一轮一轮越挪越远`() {
        // 与 InkResolverTest 里"压在正文上"的是同一组坐标：它落笔时确实盖住了第 2 行，
        // 于是落位会把它挪到旁边空白 —— 这正是会就地改点的那种情况。
        val points = floatArrayOf(300f, 55f, 400f, 95f)
        val (ink, strokes) = writtenStroke(points, semantics = InkReflowSemantics.PINNED)
        val facts = doc.facts()
        val cache = InkResolveCache()
        val map = mapOf(ink.id to strokes)

        val first = resolveWith(cache, listOf(ink), map, facts).drawable.single().docPoints
        val firstDy = first[1] - points[1]
        assertTrue("前提：第一轮确实被挪了（否则这个测试什么都没测）", firstDy != 0f)

        repeat(5) {
            val again = resolveWith(cache, listOf(ink), map, facts).drawable.single().docPoints
            assertEquals(
                "第 ${it + 2} 轮必须还在同一个位置：漂移就是这里漏了复制点数组",
                first[1],
                again[1],
                1e-3f,
            )
        }

        // 注：这里**不**拿 `strokes.single().localPoints` 跟 `points` 比 —— 前者是
        // 行局部 em 坐标、后者是文档坐标，比了也说明不了什么（第一版就这么写错了）。
        // 真正能抓到"就地平移污染缓存"的是上面那条 repeat：只要漏了复制，
        // 第 2 轮就会在第一轮的位置上再挪一次，逐轮累积。
    }

    private fun repeated(times: Int, block: (Int) -> Unit) = repeat(times) { block(it) }

    /** 上面 `repeated` 造出来的笔迹，按 id 顺序取回来（map 的键序不保证）。 */
    private fun repeatedInks(
        map: Map<String, List<StrokeAnchor>>,
        prefix: String,
    ): List<Annotation.Ink> = map.keys
        .filter { it.startsWith(prefix) }
        .sorted()
        .map { id ->
            // 这里只需要"能过解析"的最小骨架：真正被断言的是计数与结果条数。
            val strokes = map.getValue(id)
            Annotation.Ink(
                id = id,
                docId = "doc-1",
                createdAt = 1L,
                colorArgb = pen.colorArgb,
                anchor = null,
                semantics = InkReflowSemantics.FOLLOWER,
                strokeIds = strokes.map { it.strokeId },
            )
        }
}
