package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.StrokeAnchor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器的测试：**库里存的东西 + 当前排版 → 能不能画、画在哪、画不出来时说什么**。
 *
 * 这是整条链路里离用户最近的一步：锚定错了用户看不见（笔记还在屏幕上），
 * 解析错了用户一眼就能看见（笔记跑到别的段落上去了）。所以这里断言的重点
 * 不是"能不能解开"，而是"**解不开的时候有没有老实不画**"。
 */
class InkResolverTest {

    private val doc = TestDoc(2)
    private val pen = BrushSpec.DEFAULT_PEN

    /** 造一条"已经写进库"的笔记：锚点链来自真实的 [InkAnchorFactory]。 */
    private fun writtenStroke(
        docPoints: FloatArray,
        lines: List<LineBox> = doc.lines(),
        strokeId: String = "s1",
        annotationId: String = "ink-1",
        semantics: InkReflowSemantics? = null,
    ): Pair<Annotation.Ink, List<StrokeAnchor>> {
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

    /**
     * 造一条**跨行**的笔记：库里会是多段（一段 = 一行）。
     *
     * [writtenStroke] 里有 `segments.single()`，那是假定不跨行；跨行的笔迹用它
     * 会直接炸掉，所以这里单开一个。锚点仍然来自真实的 [InkAnchorFactory]，
     * 切段的规则不在这里另写一份。
     */
    private fun writtenAcrossLines(
        docPoints: FloatArray,
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
            ),
        )!!
        val segments = result.segments
        val ink = Annotation.Ink(
            id = annotationId,
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = segments.first().chain,
            semantics = segments.first().stroke.semantics,
            strokeIds = segments.map { it.stroke.strokeId },
        )
        return ink to segments.map { it.stroke }
    }

    @Test
    fun `第 200 行之后的正文笔迹也要画得出来`() {
        // 一本两千页的书有五万行。调用方曾把**全书**行盒原样递给"只扫前 200 行"的
        // 解析器，于是从第十页起，正文里写的字全被判孤儿 —— 屏幕上一片空白，
        // 而库里一个字节都没少。这条用例钉住"候选窗口要跟着这条笔记自己走"。
        val big = TestDoc(count = 260)
        val lines = big.lines()
        val line = lines[240]
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        val points = floatArrayOf(
            line.rectDoc.left + 10f, y,
            line.rectDoc.left + 60f, y + 2f,
            line.rectDoc.left + 120f, y,
        )
        val facts = big.facts(lines)
        val result = InkAnchorFactory.anchor(
            InkAnchorFactory.Request(
                strokeId = "s1",
                docPoints = points,
                docPressures = FloatArray(3) { 1f },
                brushId = pen.name,
                lines = lines,
                geometry = facts.geometry,
                source = facts.lineSource(),
                document = facts.document(),
            ),
        )!!
        val segment = result.segments.single()
        val ink = Annotation.Ink(
            id = "ink-1",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = segment.chain,
            semantics = segment.stroke.semantics,
            strokeIds = listOf(segment.stroke.strokeId),
        )

        val resolution = resolve(listOf(ink), listOf(segment.stroke), facts)

        val record = resolution.records.single()
        assertEquals(InkState.RESOLVED, record.state)
        assertTrue("第 240 行的笔迹必须画得出来，实际没画的理由=${record.reason}", record.drawn)
        assertEquals(1, resolution.drawable.size)
    }

    private fun resolve(
        inks: List<Annotation.Ink>,
        strokes: List<StrokeAnchor>,
        facts: LayoutFacts,
        brushes: List<BrushSpec> = listOf(pen),
    ) = InkResolver.resolve(
        inks = inks,
        // ★ 按**标注 id** 查，与 `AnnotationSnapshot.strokesOf(annotationId)` 的语义一致。
        // 若这里写成按笔迹 id 查，测试会全绿，而真实仓储（按标注 id 建索引）会画不出东西 ——
        // 这个坑真的踩过一次，见 design/M3 记录。
        strokesOf = { annotationId ->
            if (inks.any { it.id == annotationId }) strokes else emptyList()
        },
        brushesOf = { brushes },
        facts = facts,
    )

    @Test
    fun `刚写入的笔迹能解析回原来的文档坐标`() {
        val points = floatArrayOf(100f, 10f, 200f, 20f, 300f, 30f)
        val (ink, strokes) = writtenStroke(points)

        val resolution = resolve(listOf(ink), strokes, doc.facts())

        assertEquals(1, resolution.resolvedCount)
        assertTrue(resolution.ambiguous.isEmpty())
        assertTrue(resolution.orphaned.isEmpty())

        val drawn = resolution.drawable.single()
        assertEquals("s1", drawn.strokeId)
        for (i in points.indices) {
            assertEquals("解析回去的坐标必须逐位相同", points[i], drawn.docPoints[i], 1e-3f)
        }
    }

    @Test
    fun `跨行的一笔在接缝处接上，屏幕上不会留一道缝`() {
        // 一条竖直的线穿过两行：库里是两段（一段 = 一行）。
        // 两段各自是一条 Path，各自画自己的圆头笔帽 —— 帽半径盖不住采样间距，
        // 屏幕上就断开一截。真机上抓到的样子：146 个点切成 19 段、每段 8 个点，
        // 缺口 4~8px，整笔成了虚线（用户说的"在有文字的地方笔迹会中断"）。
        val points = ArrayList<Float>()
        var y = 5f
        while (y <= 95f) {
            points.add(150f)
            points.add(y)
            y += 12f
        }
        val (ink, strokes) = writtenAcrossLines(points.toFloatArray())
        assertEquals("这条线本来就该被切成两段（一段一行）", 2, strokes.size)

        val drawn = resolve(listOf(ink), strokes, doc.facts()).drawable
        assertEquals("两段都要画出来", 2, drawn.size)

        val first = drawn[0].docPoints
        val second = drawn[1].docPoints
        assertEquals(
            "第二段必须从第一段的末点接上 —— 少了这一个点，接缝处就少一截墨",
            first[first.size - 1],
            second[1],
            1e-3f,
        )
        assertEquals(first[first.size - 2], second[0], 1e-3f)
    }

    @Test
    fun `有笔迹点的笔记带出 hasStrokes（改挂的前提）`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val record = resolve(listOf(ink), strokes, doc.facts()).records.single()

        assertTrue(record.hasStrokes)
    }

    @Test
    fun `笔迹点丢了的笔记：说得出原因，但没有可挂的笔迹`() {
        // 存储不完整（锚点还在、笔迹行没了）是一种真实存在的坏数据。
        // 它一个像素都画不出来，而且**改挂也救不了它**（没有点可挂）——
        // 界面必须据此不给入口，所以这一条必须能从解析结果里读出来。
        val (ink, _) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val record = resolve(listOf(ink), strokes = emptyList(), facts = doc.facts()).records.single()

        assertFalse("一个像素都不该画", record.drawn)
        assertFalse("没有可挂的笔迹", record.hasStrokes)
        assertTrue("必须说清原因，而不是甩一个需要确认", record.reason.isNotBlank())
    }

    @Test
    fun `重排之后笔迹落在新行盒里面`() {
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val (ink, strokes) = writtenStroke(points)

        // 换字号、换行位、换栏宽 —— 库里的锚点一点没动。
        val reflowed = doc.lines(fontSize = 60f, top = 300f, right = 400f)
        val resolution = resolve(listOf(ink), strokes, doc.facts(reflowed))

        val drawn = resolution.drawable.single()
        val line = reflowed[0]
        for (i in points.indices step 2) {
            val x = drawn.docPoints[i]
            val y = drawn.docPoints[i + 1]
            assertTrue("x=$x 跑出了新行盒 ${line.rectDoc}", x in line.rectDoc.left..line.rectDoc.right)
            assertTrue("y=$y 跑出了新行盒 ${line.rectDoc}", y in line.rectDoc.top..line.rectDoc.bottom)
        }
    }

    @Test
    fun `指纹不符时判孤儿且一个像素都不画`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val resolution = resolve(listOf(ink), strokes, doc.facts(fingerprint = "另一个指纹"))

        assertTrue("解不开的笔迹必须不画：画错位置比暂时看不见更糟", resolution.drawable.isEmpty())
        assertEquals(1, resolution.orphaned.size)
        assertEquals(0, resolution.resolvedCount)
        assertTrue(
            "原因要说人话，不能只丢一个状态",
            resolution.orphaned.single().reason.contains("找不到原来的位置"),
        )
    }

    @Test
    fun `没有锚点链的旧数据判孤儿而不是崩`() {
        val ink = Annotation.Ink(
            id = "ink-old",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = null,
            semantics = InkReflowSemantics.FOLLOWER,
        )

        val resolution = resolve(listOf(ink), emptyList(), doc.facts())

        assertTrue(resolution.drawable.isEmpty())
        assertEquals(1, resolution.orphaned.size)
    }

    @Test
    fun `锚点解开了但笔迹点缺失时如实说明`() {
        val (ink, _) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        // 模拟"标注行在、笔迹行丢了"（崩溃在两次写之间、或导入包不完整）。
        val resolution = resolve(listOf(ink), emptyList(), doc.facts())

        assertEquals("锚点是解开了的，状态不该是孤儿", 1, resolution.resolvedCount)
        assertTrue("但画不出东西，所以不该有任何可画的笔迹", resolution.drawable.isEmpty())
        assertTrue(
            "必须说清是缺笔迹点，而不是让用户以为墨迹在屏幕上",
            resolution.records.single().reason.contains("笔迹点缺失"),
        )
    }

    @Test
    fun `缺少笔刷定义时退回默认笔而不是让笔记消失`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val resolution = resolve(listOf(ink), strokes, doc.facts(), brushes = emptyList())

        val drawn = resolution.drawable.single()
        assertEquals(BrushSpec.DEFAULT_PEN.name, drawn.brush.name)
    }

    @Test
    fun `多条笔记各自独立给出状态`() {
        val (good, goodStrokes) = writtenStroke(
            floatArrayOf(100f, 10f, 200f, 20f), strokeId = "s1", annotationId = "ink-1",
        )
        val (bad, badStrokes) = writtenStroke(
            floatArrayOf(100f, 60f, 200f, 70f), strokeId = "s2", annotationId = "ink-2",
        )

        val resolution = InkResolver.resolve(
            inks = listOf(good, bad),
            // 只把第 0 行当候选：第 1 行的那条笔记因此找不到位置。
            facts = doc.facts(lines = listOf(doc.line(0, 0f))),
            strokesOf = { annotationId ->
                when (annotationId) {
                    "ink-1" -> goodStrokes
                    "ink-2" -> badStrokes
                    else -> emptyList()
                }
            },
            brushesOf = { listOf(pen) },
        )

        assertEquals(1, resolution.resolvedCount)
        assertEquals(1, resolution.orphaned.size)
        assertNotNull(resolution.records.first { it.annotationId == "ink-1" }.line)
    }

    @Test
    fun `解析结果说清楚这一版画出了谁、库里又存着谁`() {
        // 抬笔暂存的交接靠这两个集合（见 `InkCoverage`）：只有"这一版里确实有它"
        // 才允许撤掉湿墨。少一个集合都会退回"提交过一版就撤"的老毛病 ——
        // 症状是刚画的圈闪一下没了、过一会儿又自己回来。
        val (good, goodStrokes) = writtenStroke(
            floatArrayOf(100f, 10f, 200f, 20f), strokeId = "s1", annotationId = "ink-1",
        )
        val (bad, badStrokes) = writtenStroke(
            floatArrayOf(100f, 60f, 200f, 70f), strokeId = "s2", annotationId = "ink-2",
        )

        val resolution = InkResolver.resolve(
            inks = listOf(good, bad),
            // 只认第 0 行：第二条因此解不开（画不出来）。
            facts = doc.facts(lines = listOf(doc.line(0, 0f))),
            strokesOf = { id -> if (id == "ink-1") goodStrokes else badStrokes },
            brushesOf = { listOf(pen) },
        )

        assertEquals("画出来的只有第一条", setOf("s1"), resolution.coverage.drawnRootIds)
        assertEquals(
            "库里两条都在 —— 第二条只是这一版画不出来，不能当成'还没轮到它'",
            setOf("s1", "s2"),
            resolution.coverage.storedRootIds,
        )
    }

    @Test
    fun `覆盖集合按根 id 归并跨行的段`() {
        // 跨行的一笔在库里是多条各带一段的笔迹（`s1`、`s1#1`），抬笔暂存里只有一个
        // 根 id。归并错了，这条笔迹就永远等不到回执 —— 湿墨会在屏幕上挂到下次刷新。
        val (ink, strokes) = writtenAcrossLines(floatArrayOf(100f, 10f, 200f, 20f, 100f, 60f, 200f, 70f))
        assertTrue("前提：这一笔确实被切成了多段", strokes.size > 1)

        val resolution = resolve(listOf(ink), strokes, doc.facts())

        assertEquals(setOf("s1"), resolution.coverage.drawnRootIds)
        assertEquals(setOf("s1"), resolution.coverage.storedRootIds)
    }

    @Test
    fun `笔迹是按标注 id 查回来的_而不是按笔迹 id`() {
        // 这是一个**接口契约**测试，而不是行为测试：
        // `AnnotationSnapshot.strokesOf(annotationId)` 的入参是标注 id。
        // 上游（仓储）与下游（解析器）各自自洽、各自测试全绿，只有把两者接起来
        // 才会发现"查询键反了"—— 症状是"锚点解析成功但屏幕上什么都不画"。
        // 所以这里把契约钉死：只认标注 id，用笔迹 id 查一律查不到。
        val (ink, strokes) = writtenStroke(
            floatArrayOf(100f, 10f, 200f, 20f), strokeId = "s1", annotationId = "ink-1",
        )

        val byAnnotationId = InkResolver.resolve(
            inks = listOf(ink),
            strokesOf = { id -> if (id == "ink-1") strokes else emptyList() },
            brushesOf = { listOf(pen) },
            facts = doc.facts(),
        )

        assertEquals("解析器必须按标注 id 取笔迹", 1, byAnnotationId.resolvedCount)
        assertEquals(1, byAnnotationId.drawable.size)
        assertEquals("s1", byAnnotationId.drawable.single().strokeId)
    }

    @Test
    fun `解不开的笔记必须带出当时标在哪句话上`() {
        // 解析已经说不出位置了，但库里还记着当时那句文字 —— 这是"确认位置"
        // 唯一能用的线索（拿它去搜索就能找到候选）。所以它在孤儿状态上也必须在。
        val (ink, strokes) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val resolution = resolve(listOf(ink), strokes, doc.facts(fingerprint = "另一个指纹"))

        val record = resolution.orphaned.single()
        assertEquals(doc.texts[0], record.originalText)
    }

    @Test
    fun `当时那句话过长时截断到上限且不带省略号`() {
        // 这个值会被填进搜索框当查询词：塞一个 `…` 进去，查询就永远命中不了。
        val longLine = "标".repeat(100)
        val chain = dev.inkleaf.core.anchoring.AnchorChainFactory.build(
            normalizedText = longLine,
            profile = doc.facts().profile,
            fingerprint = "另一个指纹",
            target = dev.inkleaf.core.anchoring.AnchorChainFactory.Target(
                blockPath = "blk[0]",
                blockStartOffset = 0,
                blockEndOffset = longLine.length,
                absoluteStart = 0,
                absoluteEnd = longLine.length,
            ),
            docRatio = 0.5f,
        )
        val ink = Annotation.Ink(
            id = "ink-long",
            docId = "doc-1",
            createdAt = 1L,
            colorArgb = pen.colorArgb,
            anchor = chain,
            semantics = InkReflowSemantics.FOLLOWER,
        )

        val record = resolve(listOf(ink), emptyList(), doc.facts(fingerprint = "另一个指纹")).records.single()

        assertEquals(InkResolver.MAX_ORIGINAL_TEXT_CHARS, record.originalText?.length)
        assertTrue("不能带省略号", record.originalText?.endsWith("…") == false)
    }

    @Test
    fun `行局部坐标到文档坐标的换算用的是解析到的那一行`() {
        val (ink, strokes) = writtenStroke(floatArrayOf(100f, 10f, 200f, 20f))

        val resolution = resolve(listOf(ink), strokes, doc.facts())

        // 行盒被整体平移之后，解析出的墨迹也必须整体平移同样的量，
        // 而形状（点与点的差）不变 —— 后者正是"存 em 而不存像素"的意义。
        val shifted = doc.facts(lines = doc.lines().map { it.copy(rectDoc = it.rectDoc.translate(0f, 500f), baselineY = it.baselineY + 500f) })
        val moved = resolve(listOf(ink), strokes, shifted).drawable.single()

        val original = resolution.drawable.single()
        var i = 0
        while (i < moved.docPoints.size) {
            assertEquals(original.docPoints[i], moved.docPoints[i], 1e-3f)
            assertEquals(original.docPoints[i + 1] + 500f, moved.docPoints[i + 1], 1e-3f)
            i += 2
        }
    }

    // ── 冻结语义：PINNED / FREE 的墨迹不随重排移动（M3.1） ─────────────────────

    @Test
    fun `冻结笔迹一动不动而跟随笔迹跟着行盒走`() {
        // 这是"固定在页面上"这五个字的定义，也是 M3 §13 第 3 条那笔老账的验收：
        // 同一份库数据 + 行盒整体下移 500px → 冻结的不动，跟随的跟着走。
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val (frozenInk, frozenStrokes) = writtenStroke(points, semantics = InkReflowSemantics.FREE)
        val (followerInk, followerStrokes) = writtenStroke(
            points, strokeId = "s2", annotationId = "ink-2",
        )

        val movedLines = doc.lines().map {
            it.copy(rectDoc = it.rectDoc.translate(0f, 500f), baselineY = it.baselineY + 500f)
        }
        val frozenBefore = resolve(listOf(frozenInk), frozenStrokes, doc.facts()).drawable.single()
        val frozenAfter = resolve(listOf(frozenInk), frozenStrokes, doc.facts(movedLines)).drawable.single()
        val followerBefore = resolve(listOf(followerInk), followerStrokes, doc.facts()).drawable.single()
        val followerAfter = resolve(listOf(followerInk), followerStrokes, doc.facts(movedLines)).drawable.single()

        assertArrayEquals("冻结笔迹一个像素都不许动", frozenBefore.docPoints, frozenAfter.docPoints, 0f)
        assertEquals(
            "冻结的是位置，形状当然也一模一样",
            frozenBefore.fontSizePx,
            frozenAfter.fontSizePx,
            0f,
        )
        assertEquals(
            "只有纵向平移，横坐标不该变",
            followerBefore.docPoints[0],
            followerAfter.docPoints[0],
            1e-3f,
        )
        assertEquals(
            "跟随笔迹必须跟着行盒走（这才对得起「跟随文字」这个语义）",
            followerBefore.docPoints[1] + 500f,
            followerAfter.docPoints[1],
            1e-3f,
        )
    }

    @Test
    fun `冻结笔迹在锚点解不开时照画_跟随笔迹一个像素都不画`() {
        // 硬规则"解不开就不画"防的是**猜一个位置画上去**。冻结笔迹的位置不是猜的：
        // 它存在库里、与解析结果无关，所以解不开时把它藏起来反而是错的
        // （用户会以为自己的边注丢了）。跟随笔迹没有这个属性，仍然不画。
        val points = floatArrayOf(100f, 10f, 200f, 20f)
        val (frozenInk, frozenStrokes) = writtenStroke(points, semantics = InkReflowSemantics.FREE)
        val (followerInk, followerStrokes) = writtenStroke(
            points, strokeId = "s2", annotationId = "ink-2",
        )
        val orphanFacts = doc.facts(fingerprint = "另一个指纹")

        val frozen = resolve(listOf(frozenInk), frozenStrokes, orphanFacts)
        val follower = resolve(listOf(followerInk), followerStrokes, orphanFacts)

        assertEquals(InkState.ORPHANED, frozen.records.single().state)
        assertTrue("冻结笔迹必须照画：它固定在这一页上", frozen.records.single().drawn)
        assertEquals(1, frozen.drawable.size)
        assertTrue(
            "原因要说清墨迹还在屏幕上，否则用户会去面板里找一条其实就在眼前的笔记",
            frozen.records.single().reason.contains("仍画在原来的位置"),
        )
        assertTrue("它不是「画不出来」的那一类", frozen.notDrawn.isEmpty())

        assertEquals(InkState.ORPHANED, follower.records.single().state)
        assertFalse(follower.records.single().drawn)
        assertTrue("跟随笔迹仍然一个像素都不画", follower.drawable.isEmpty())
        assertEquals(1, follower.notDrawn.size)
    }

    @Test
    fun `冻结笔迹的笔宽用落笔时的字号`() {
        // 笔宽以 em 存，画的时候要乘回 px。若冻结笔迹借用"当前正文字号"，
        // 用户改一次字号边注就会变粗 —— 位置冻住了、粗细却跟着动，说不通。
        val (ink, strokes) = writtenStroke(
            floatArrayOf(100f, 10f, 200f, 20f), semantics = InkReflowSemantics.FREE,
        )

        val drawn = resolve(listOf(ink), strokes, doc.facts(doc.lines(fontSize = 60f))).drawable.single()

        assertEquals("落笔时那一行的字号是 40px", 40f, drawn.fontSizePx, 0f)
    }

    @Test
    fun `旧版本记下的固定笔迹要说清它仍会跟着文字走`() {
        // 冻结几何是 M3.1 才有的；老数据里的 PINNED / FREE 没有它 —— 那个位置
        // 当时就没存，补不回来。界面必须说出来，不能让用户以为它不会动。
        val (ink, strokes) = writtenStroke(
            floatArrayOf(100f, 10f, 200f, 20f), semantics = InkReflowSemantics.FREE,
        )
        val legacy = strokes.map { it.copy(frozenMatrix = null) }

        val record = resolve(listOf(ink), legacy, doc.facts()).records.single()

        assertEquals(InkState.RESOLVED, record.state)
        assertTrue("必须说清缺的是冻结坐标", record.reason.contains("没有冻结坐标"))
        assertTrue("缺冻结坐标不影响它现在还画得出来", record.drawn)
    }

    // ── PINNED 落位（方案 §6.3.3 / M3 §20.6 那笔账） ─────────────────────────

    /** 一笔笔迹在文档坐标里的外接盒。测试自己算，免得借实现的口径。 */
    private fun boxOf(points: FloatArray): Box {
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

    @Test
    fun `压在正文上的固定笔迹被挪到空白处_而库里的数据一个字都不改`() {
        // 一笔写在正文栏里、盖住第 1 行的墨迹（用 override 造成这种处境的：
        // 段间空白里的边注在重排之后就是这样 —— 那一片空白被文字填上了）。
        val points = floatArrayOf(300f, 55f, 400f, 95f)
        val (ink, strokes) = writtenStroke(points, semantics = InkReflowSemantics.PINNED)
        val storedBefore = strokes.single().localPoints.copyOf()
        val facts = doc.facts()

        val before = resolve(listOf(ink), strokes, facts)
        assertTrue(
            "前提：它落笔时确实压着正文（否则这个测试什么都没测）",
            boxOf(points).coverageRatio(facts.lines[1].rectDoc) >= 0.25f,
        )
        assertTrue(
            "前提：它此刻确实被挪了（落笔位置 → 屏幕上的位置）",
            boxOf(before.drawable.single().docPoints).coverageRatio(facts.lines[1].rectDoc) < 0.25f,
        )

        val after = resolve(listOf(ink), strokes, facts)
        val moved = after.drawable.single()
        val record = after.records.single()

        assertEquals(InkState.RESOLVED, record.state)
        assertTrue(
            "落位之后不压字",
            boxOf(moved.docPoints).coverageRatio(facts.lines[1].rectDoc) < 0.25f,
        )
        assertTrue(
            "面板里必须说得出它为什么不在我写的地方：${record.reason}",
            record.reason.contains("已挪到目标行旁边的空白处"),
        )
        assertTrue(
            "只挪位置，形状与笔宽仍是落笔那一刻的",
            record.reason.contains("形状与笔宽仍是落笔那一刻的"),
        )

        // 只平移不缩放：所有点加的是**同一个** dy，x 一个像素都不动。
        var dy: Float? = null
        var i = 0
        while (i < moved.docPoints.size) {
            assertEquals("横向一个像素都不该动", points[i], moved.docPoints[i], 0f)
            val step = moved.docPoints[i + 1] - points[i + 1]
            if (dy == null) dy = step else assertEquals("必须是整体平移", dy, step, 1e-3f)
            i += 2
        }
        assertTrue("必须真的挪了", dy != null && dy != 0f)
        assertEquals("落笔时的字号就是笔宽（重排不许把边注变粗变细）", 40f, moved.fontSizePx, 0f)

        // 落位**不进库**：库里存的还是落笔那一刻的 em 点，打开一次文档不是一次改数据。
        assertArrayEquals(
            "落位是渲染时的事，库里的笔迹点必须原样",
            storedBefore,
            strokes.single().localPoints,
            0f,
        )
    }

    @Test
    fun `贴着目标行的固定笔迹一动不动`() {
        // 这一条守的是"已经贴在目标行旁边的笔记不许被无谓地挪"：
        // 改挂换了指向之后，判据是"它还挨着新那一行吗"，而不是"它的坐标必须逐位不变"。
        // 左边距里的边注贴着第 1 行，没压着任何字 —— 落位不许碰它。
        val points = floatArrayOf(4f, 60f, 16f, 90f)
        val (ink, strokes) = writtenStroke(points, semantics = InkReflowSemantics.PINNED)
        val facts = doc.facts()

        val resolution = resolve(listOf(ink), strokes, facts)
        val drawn = resolution.drawable.single()

        assertArrayEquals("没压着字的笔记必须逐位停在落笔的地方", points, drawn.docPoints, 1e-3f)
        assertFalse(
            "什么都不该说：它就在自己该在的地方",
            resolution.records.single().reason.contains("已挪到"),
        )
        assertFalse(
            "也不该说放不下",
            resolution.records.single().reason.contains("找不到空白"),
        )
    }

    @Test
    fun `旧版本记下的固定笔迹不参与落位`() {
        // 老数据没有冻结坐标：它的位置是**借当前行盒**算出来的，本来就会跟着文字走。
        // 给它补一次落位，等于给一笔"其实会动"的墨迹编一个固定位置。
        // 缺的那份信息不可逆，只能如实说出来（见 `frozenNote`）。
        val (ink, strokes) = writtenStroke(
            floatArrayOf(300f, 55f, 400f, 95f), semantics = InkReflowSemantics.PINNED,
        )
        val legacy = strokes.map { it.copy(frozenMatrix = null) }

        val resolution = resolve(listOf(ink), legacy, doc.facts())

        val record = resolution.records.single()
        assertNotNull("它照样画得出来（沿用老办法）", resolution.drawable.single())
        assertTrue("必须说清缺的是冻结坐标", record.reason.contains("没有冻结坐标"))
        assertFalse("但不许给它补位置", record.reason.contains("已挪到"))
        // 位置就是"当前行盒 × em 点"，一个 dy 都没加。
        val expected = doc.mapToDoc(doc.lines()[1], legacy.single().localPoints)
        assertArrayEquals(expected, resolution.drawable.single().docPoints, 1e-3f)
    }

    @Test
    fun `FREE 笔迹不参与落位`() {
        // FREE 的定义是"不跟随文字，只随页面滚动"：与行盒无关的墨迹没有
        // "目标行附近的空白"这回事。它就算盖着字（这里是刻意的极端），也不许被搬走。
        val points = floatArrayOf(300f, 55f, 400f, 95f)
        val (ink, strokes) = writtenStroke(points, semantics = InkReflowSemantics.FREE)

        val resolution = resolve(listOf(ink), strokes, doc.facts())

        assertEquals(InkReflowSemantics.FREE, resolution.records.single().semantics)
        assertArrayEquals(points, resolution.drawable.single().docPoints, 1e-3f)
        assertFalse(resolution.records.single().reason.contains("已挪到"))
    }
}
