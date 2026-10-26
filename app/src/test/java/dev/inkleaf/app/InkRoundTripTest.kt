package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.anchoring.AnchorChainFactory
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.ink.InkLayerController
import dev.inkleaf.core.ink.InkResolver
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.rowsForDisplay
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.StrokeAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 应用级整链路测试：**导入 → 落库 → 读出 → 排版 → 落笔 → 回读解析 → 撤销**。
 *
 * ## 为什么一定要有它
 *
 * 这条链路上的每个组件都有自己的单元测试：`:core:data` 测存储（真实 SQLite），
 * `:core:document` 测排版（真实 `StaticLayout`），`:core:ink` 测锚定与渲染
 * （真实 Skia）。但"**它们拼起来还能跑**"是另一回事：
 * 各模块测试里那些手工构造的对象，在真实组合里可能是另一副样子 ——
 * 例如 `LineBox.anchor` 的块内偏移是由真实排版算出来的，
 * 而 `InkAnchorFactoryTest` 里的行盒是手写的。两者对"偏移"的理解一旦不一致，
 * 所有单元测试都会绿，而用户写下的每一笔都挂错行。
 *
 * 本机没有设备也没有模拟器（无虚拟化），因此这是**唯一**能跑通整条链路的地方。
 * 它替换不了"在手机上划一笔"，但能挡住"拼起来根本不工作"。
 *
 * ## 这个测试写出来之后立刻发现的一件事
 *
 * `saveInk` 内部会切到仓储自己的 IO 调度器（`dispatchers.onIo`），
 * 因此**落笔是异步的**：`endStroke()` 返回时数据可能还没进库。
 * 生产代码里这没问题（控制器落盘完成后才回调 `onChanged`，界面据此刷新），
 * 但测试必须等。第一版测试直接断言，结果全部为空 —— 这不是测试的毛病，
 * 而是"把异步当成同步"这个错误第一次被真的抓到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkRoundTripTest {

    private lateinit var container: AppContainer

    private val theme = DocumentTheme.light(textSizePx = 40f, lineSpacingMultiplier = 1.4f)

    private val metrics = PageLayoutMetrics(
        contentWidthPx = 900,
        contentHeightPx = 1400,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    /**
     * 刻意用**短行**：短到在任何字号下都不折行。
     * 折行会改变"块内偏移 ↔ 视觉行"的对应关系，那是另一个（同样重要但独立的）话题；
     * 这里要测的是整链路本身，先把它隔离掉。
     */
    private val body: String = (1..6).joinToString("\n") { "第 $it 行正文" }

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        runBlocking { container.load() }
    }

    @After
    fun tearDown() {
        container.store.close()
    }

    /** 走真实的导入三步：读 URI → 看一遍 → 落库。 */
    private fun importBody(body: String = this.body): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "导入测试.txt",
        )
        val inspection = container.importer.inspect(candidate)
        container.importer.commit(inspection)
    }

    /**
     * 走真实的读取路径：与导入同一个入口（DocumentCodec.sessionFromBytes）。
     *
     * 返回接口类型而不是重排实现：这个入口现在也能给出固定版式的会话
     * （PDF），而本测试全程只需要契约上的那几样。
     */
    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession, theme: DocumentTheme = this.theme): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    private fun controllerFor(meta: DocumentMeta, facts: LayoutFacts): InkLayerController {
        var counter = 0
        return InkLayerController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = RepositoryInkSink(container, meta.id),
            facts = { facts },
            onChanged = {},
            worker = Dispatchers.Unconfined,
            idFactory = { "ink-${counter++}" },
        )
    }

    private fun inkCount(meta: DocumentMeta): Int = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.count { it is Annotation.Ink }
    }

    /**
     * 等落盘发生。
     *
     * 必须等，不能直接断言：`saveInk` 会切到仓储的 IO 调度器，所以
     * `endStroke()` / `undo()` / `redo()` 返回时写操作可能还在路上。
     */
    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）：$what")
    }

    private fun readBack(meta: DocumentMeta, facts: LayoutFacts) = runBlocking {
        val snapshot = container.store.annotations.listByDoc(meta.id)
        InkResolver.resolve(
            inks = snapshot.annotations.filterIsInstance<Annotation.Ink>(),
            strokesOf = snapshot::strokesOf,
            brushesOf = snapshot::brushesOf,
            facts = facts,
        )
    }

    /**
     * 同一个排版事实、只把指纹换掉 —— 用来模拟"正文换了一版"。
     *
     * 这是用户重新导入/升级之后最真实的局面：排版本身没变，但文件的指纹变了。
     * 锚点链记的是"文件还是原来那一份"，这个前提一旦为假，全库锚点必然判孤儿。
     */
    private fun withFingerprint(facts: LayoutFacts, fingerprint: String): LayoutFacts =
        object : LayoutFacts by facts {
            override val fingerprint: String = fingerprint
        }

    private fun storedInk(meta: DocumentMeta): Annotation.Ink = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.filterIsInstance<Annotation.Ink>().single()
    }

    /**
     * 在 facts 的第 0 行中间划一条横线。
     *
     * 横向刻意**只覆盖行内文字的一部分**（30..150px）：笔迹若超出该行的文字范围，
     * "重排后仍在行盒内"这条断言就不再成立 —— 而那是断言本身太粗，不是笔迹错了。
     * 让笔迹落在文字范围内，才能把"跟着行走"这件事钉死。
     */
    private fun InkLayerController.writeOnFirstLine(facts: LayoutFacts) {
        val line = facts.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        beginStroke(30f, y, 1f)
        extendStroke(90f, y, 1f)
        extendStroke(150f, y, 1f)
        endStroke()
    }

    @Test
    fun `导入的文档能落库、能读出、能排版`() {
        val meta = importBody()

        // Robolectric 的 ContentResolver 对 file:// 返回的 DISPLAY_NAME 是整个路径，
        // 因此这里只断言"标题来自文件名"，不断言它等于某个干净的名字。
        assertTrue("标题应当来自文件名，实际=${meta.title}", meta.title.endsWith("导入源"))
        assertTrue("导入必须真的产生一份私有副本", meta.byteSize > 0)
        assertNotNull("落库之后必须能按 id 读回来", runBlocking { container.store.documents.byId(meta.id) })

        val session = sessionOf(meta)
        assertEquals("短行正文应当一行一块", 6, session.blocks.size)
        assertTrue("真实排版必须给出行盒", factsOf(session).lines.isNotEmpty())
        assertEquals(
            "指纹必须是导入时算出来的那一个（否则所有锚点在写入的那一刻就已经失效）",
            meta.sourceFingerprint,
            session.fingerprint,
        )
    }

    @Test
    fun `一笔写进库再读回来仍然锚定在同一行且坐标一致`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val line = facts.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f

        controllerFor(meta, facts).writeOnFirstLine(facts)
        awaitUntil("笔迹落盘") { inkCount(meta) == 1 }

        val snapshot = runBlocking { container.store.annotations.listByDoc(meta.id) }
        assertEquals(
            "笔迹行没能读回来：unreadableStroke=${snapshot.unreadableStrokeCount} " +
                "strokes=${snapshot.strokesByAnnotation} anns=${snapshot.annotations.map { it.id }} " +
                "brushes=${snapshot.brushesByAnnotation}",
            1,
            snapshot.strokesByAnnotation.size,
        )

        val resolution = readBack(meta, facts)
        assertEquals("刚写的一笔必须解得开：${resolution.records.map { it.reason }}", 1, resolution.resolvedCount)
        assertEquals(1, resolution.drawable.size)

        val drawn = resolution.drawable.single()
        assertEquals(30f, drawn.docPoints[0], 1e-2f)
        assertEquals(y, drawn.docPoints[1], 1e-2f)
        assertEquals(150f, drawn.docPoints[4], 1e-2f)
        assertEquals(
            "解析到的必须就是当初那一行",
            line.anchor.blockPath,
            resolution.records.single().line?.anchor?.blockPath,
        )
    }

    @Test
    fun `换字号重排之后笔迹仍然解析得开而且落在新行盒里`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val original = factsOf(session)
        controllerFor(meta, original).writeOnFirstLine(original)
        awaitUntil("笔迹落盘") { inkCount(meta) == 1 }
        val before = readBack(meta, original).drawable.single()

        // 字号 40 → 80：整篇排版全变，库里的锚点一点没动。
        val bigger = factsOf(session, theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val resolution = readBack(meta, bigger)

        assertEquals(
            "字号变化不该让笔记丢失：${resolution.records.map { it.reason }}",
            1,
            resolution.resolvedCount,
        )
        val drawn = resolution.drawable.single()
        val line = bigger.lines.first()
        for (i in drawn.docPoints.indices step 2) {
            val x = drawn.docPoints[i]
            val yy = drawn.docPoints[i + 1]
            assertTrue("x=$x 跑出了新行盒 ${line.rectDoc}", x in line.rectDoc.left..line.rectDoc.right)
            assertTrue("y=$yy 跑出了新行盒 ${line.rectDoc}", yy in line.rectDoc.top..line.rectDoc.bottom)
        }

        // 存的是行局部 em 坐标，所以字号翻倍时文档坐标必须以**行首**为原点等比放大：
        // x_new = left + 2 × (x_old − left)。这一条比"落在行盒内"强得多：
        // 它把"笔迹不是被重新居中、也不是被拉伸"这件事一起钉死了。
        val left = line.rectDoc.left
        var k = 0
        while (k < drawn.docPoints.size) {
            assertEquals(
                "第 ${k / 2} 个点的横向位置没有按 em 比例跟随行首放大",
                left + 2f * (before.docPoints[k] - left),
                drawn.docPoints[k],
                0.5f,
            )
            k += 2
        }
    }

    @Test
    fun `撤销之后库里读不到这条笔迹而重做之后又能读到`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val controller = controllerFor(meta, facts)
        controller.writeOnFirstLine(facts)
        awaitUntil("笔迹落盘") { inkCount(meta) == 1 }
        assertEquals(1, readBack(meta, facts).resolvedCount)

        controller.undo()
        awaitUntil("撤销落库") { inkCount(meta) == 0 }
        awaitUntil("撤销栈就绪") { controller.canRedo }
        assertEquals("撤销必须真的从库里读不到了（软删除也要被读路径过滤掉）", 0, readBack(meta, facts).records.size)

        controller.redo()
        awaitUntil("重做落库") { inkCount(meta) == 1 }
        assertEquals("重做之后必须重新能读到", 1, readBack(meta, facts).resolvedCount)
    }

    @Test
    fun `一笔跨两行会存成两条各自锚定的笔迹`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val first = facts.lines[0]
        val second = facts.lines[1]
        val firstY = (first.rectDoc.top + first.rectDoc.bottom) / 2f
        val secondY = (second.rectDoc.top + second.rectDoc.bottom) / 2f

        val controller = controllerFor(meta, facts)
        controller.beginStroke(60f, firstY, 1f)
        controller.extendStroke(300f, firstY, 1f)
        controller.extendStroke(60f, secondY, 1f)
        controller.extendStroke(300f, secondY, 1f)
        controller.endStroke()
        awaitUntil("跨行笔迹落盘") { inkCount(meta) == 2 }

        val resolution = readBack(meta, facts)
        assertEquals("跨两行 ⇒ 两条独立可解析的记录", 2, resolution.resolvedCount)
        assertEquals(
            listOf("blk[0]", "blk[1]"),
            resolution.records.map { it.line?.anchor?.blockPath },
        )
    }

    /**
     * "确认位置"的整链路：**改版判孤儿 → 用户指出那一行 → 笔记回到页面上**。
     *
     * 这是三态设计里唯一没有出路的那个状态（`Orphaned`，以及同一条代码路径上的
     * `Ambiguous`）的补救路径。它与"落笔"走同一条写入路径（同一个 `saveInk`、
     * 同一个锚点链构造器），所以这里测的不是"函数返回值对不对"，而是
     * **屏幕上会不会重新出现这一笔**。
     */
    @Test
    fun `改版之后判孤儿的笔记能被重新挂到用户指定的那一行`() {
        val meta = importBody()
        val original = factsOf(sessionOf(meta))
        controllerFor(meta, original).writeOnFirstLine(original)
        awaitUntil("笔迹落盘") { inkCount(meta) == 1 }

        val changed = withFingerprint(original, "fp-换了一版")
        val before = readBack(meta, changed)
        assertEquals("改版之后必须判孤儿（绝不猜位置）", 1, before.orphaned.size)
        assertTrue("画不出来的笔迹一个像素都不许画", before.drawable.isEmpty())
        assertNotNull(
            "解不开的时候仍然要说得清它当时标在哪句话上（这是用户唯一的线索）",
            before.records.single().originalText,
        )

        // 用户在第 4 行找到了它原来那句话，按下"挂到这一处"。
        val targetLine = changed.lines[3]
        val outcome = runBlocking {
            NoteWriter(container, meta.id).attachToCharOffset(
                annotationId = storedInk(meta).id,
                charOffset = changed.absoluteRangeOf(targetLine)!!.first,
                facts = changed,
            )
        }
        assertTrue("必须真的挂上了，实际=${outcome.message}", outcome.attached)
        assertTrue(
            "结果提示必须说清挂到了哪句话上，实际=${outcome.message}",
            outcome.message.startsWith("已把这条笔记挂到："),
        )

        val after = readBack(meta, changed)
        assertEquals(
            "重挂之后必须真的解得开：${after.records.map { it.reason }}",
            1,
            after.resolvedCount,
        )
        assertTrue("而且必须真的画出东西来", after.drawable.size == 1)
        assertEquals(
            "必须落在用户选的那一行上",
            targetLine.anchor.blockPath,
            after.records.single().line?.anchor?.blockPath,
        )

        // 墨迹要落在那一行的行盒里：重挂保留 em 形状，行的位置与字号由新行盒决定。
        val drawn = after.drawable.single()
        var i = 0
        while (i < drawn.docPoints.size) {
            val x = drawn.docPoints[i]
            val y = drawn.docPoints[i + 1]
            assertTrue("x=$x 跑出了行盒 ${targetLine.rectDoc}", x in targetLine.rectDoc.left..targetLine.rectDoc.right)
            assertTrue("y=$y 跑出了行盒 ${targetLine.rectDoc}", y in targetLine.rectDoc.top..targetLine.rectDoc.bottom)
            i += 2
        }

        // 库里存的也必须是"当前这一版"：指纹与引文都换成新行的。
        // 缺了这一步，笔记会在**下一次**打开文档时又变回孤儿（"点了一下当时有用"）。
        val stored = storedInk(meta).anchor!!
        assertEquals("fp-换了一版", stored.sourceFingerprint)
        assertEquals(changed.textOf(targetLine), stored.textQuote?.exact)
        assertEquals(targetLine.anchor, stored.structural)
    }

    @Test
    fun `重挂一条不存在的笔记会被明确拒绝而不是静默什么都不做`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))

        val outcome = runBlocking {
            NoteWriter(container, meta.id).attachToCharOffset("ink-不存在", 0, facts)
        }

        assertTrue("失败必须如实报告（界面据此决定不关面板）", !outcome.attached)
        assertTrue(
            "点了必须有回音：失败也要说清是哪一步失败，实际=${outcome.message}",
            outcome.message.contains("不在了"),
        )
    }

    /**
     * `Ambiguous`（多处文本都可能）走的是与 `Orphaned` **同一条**补救路径。
     *
     * 上面的孤儿用例覆盖了"文件换了版"；这里造的是一条"结构路径失效、引文命中两处"
     * 的笔记 —— 这正是 `Ambiguous` 的来历，也正是用户最常说"我的笔记不见了"的那一类。
     */
    @Test
    fun `多处都可能时也能挂到用户指定的那一行`() {
        // 两行一模一样的正文：引文无法区分它们（这是 Ambiguous 唯一的成因）。
        val meta = importBody(body = "同样的一句话\n同样的一句话")
        val facts = factsOf(sessionOf(meta))
        assertEquals("两行正文必须各成一块", 2, facts.lines.size)

        // 手工造一条"块路径不存在、但引文与指纹都对"的锚点链：
        // 规则 2a（结构型命中）落空 ⇒ 规则 2b（引文匹配）命中两行 ⇒ Ambiguous。
        val chain = AnchorChainFactory.build(
            normalizedText = facts.normalizedText,
            profile = facts.profile,
            fingerprint = facts.fingerprint,
            target = AnchorChainFactory.Target(
                blockPath = "blk[99]",
                blockStartOffset = 0,
                blockEndOffset = 6,
                absoluteStart = 0,
                absoluteEnd = 6,
            ),
            docRatio = 0f,
        )
        val pen = BrushSpec.DEFAULT_PEN
        runBlocking {
            container.store.annotations.saveInk(
                annotation = Annotation.Ink(
                    id = "ink-ambiguous",
                    docId = meta.id,
                    createdAt = 1L,
                    colorArgb = pen.colorArgb,
                    anchor = chain,
                    semantics = InkReflowSemantics.FOLLOWER,
                    strokeIds = listOf("s-manual"),
                ),
                strokes = listOf(
                    StrokeAnchor(
                        strokeId = "s-manual",
                        anchor = facts.lines[0].anchor,
                        semantics = InkReflowSemantics.FOLLOWER,
                        brushId = pen.name,
                        localPoints = floatArrayOf(0.1f, 0.6f, 2.0f, 0.6f),
                        localPressures = floatArrayOf(1f, 1f),
                    ),
                ),
                brushes = listOf(pen),
            )
        }

        val before = readBack(meta, facts)
        assertEquals("两处都可能 ⇒ 必须判 Ambiguous 并交给用户", 1, before.ambiguous.size)
        assertTrue("判为 Ambiguous 的笔迹一个像素都不许画", before.drawable.isEmpty())
        assertEquals(
            "解不开时也要说得清当时标在哪句话上",
            "同样的一句话",
            before.records.single().originalText,
        )

        // 用户看了一下，说"是第二处"。
        val target = facts.lines[1]
        val outcome = runBlocking {
            NoteWriter(container, meta.id).attachToCharOffset(
                annotationId = "ink-ambiguous",
                charOffset = facts.absoluteRangeOf(target)!!.first,
                facts = facts,
            )
        }

        assertTrue("必须真的挂上了，实际=${outcome.message}", outcome.attached)
        val after = readBack(meta, facts)
        assertEquals("重挂之后必须解开：${after.records.map { it.reason }}", 1, after.resolvedCount)
        assertEquals(
            "必须落在用户选的那一处（第二行，而不是第一行）",
            target.anchor.blockPath,
            after.records.single().line?.anchor?.blockPath,
        )
    }

    // ── 冻结语义：PINNED / FREE 的整链路（M3.1） ──────────────────────────────

    /**
     * 页边笔记换字号之后**一动不动**，正文笔迹跟着走。
     *
     * 这是 M3 §13 第 3 条那笔老账的验收。它必须走完整链路（落笔 → 库 → 读回来 →
     * 按新排版解析），因为"冻不冻得住"全靠库里那条冻结变换：只在内存里看，
     * 或者编码器漏写一节，症状都是"重开文档之后边注跑回了正文里"。
     *
     * 这条边注写在**第 0 行**旁边，而第 0 行永远在正文最上面 —— 所以它换字号之后
     * 本来就还在它记的那一行旁边，一个像素都不该动。写成别的行就会多走一步
     * "重新摆放"（见 `改字号之后固定的边注跟到它记的那一行旁边`）：那是两件事，
     * 这一条只钉"形状与笔宽冻住了"。
     */
    @Test
    fun `页边笔记换字号之后一动不动而正文笔迹跟着走`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val original = factsOf(session)
        val line = original.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        val controller = controllerFor(meta, original)

        // 第 0 条：写在第 0 行正文上的笔迹（跟随文字）。
        controller.writeOnFirstLine(original)
        awaitUntil("正文笔迹落盘") { inkCount(meta) == 1 }
        // 第 1 条：写在左边距里的边注（固定）。x < marginLeft(24) 才会被判成 PINNED。
        controller.beginStroke(4f, y, 1f)
        controller.extendStroke(10f, y + 6f, 1f)
        controller.extendStroke(16f, y + 12f, 1f)
        controller.endStroke()
        awaitUntil("边注落盘") { inkCount(meta) == 2 }

        val snapshot = runBlocking { container.store.annotations.listByDoc(meta.id) }
        val pinned = snapshot.annotations.filterIsInstance<Annotation.Ink>()
            .single { it.semantics == InkReflowSemantics.PINNED }
        val pinnedStroke = snapshot.strokesOf(pinned.id).single()
        assertNotNull("边注必须带着落笔那一刻的冻结几何进库", pinnedStroke.frozenMatrix)

        val before = readBack(meta, original)
        val pinnedBefore = before.drawable.single { it.strokeId == pinnedStroke.strokeId }
        val followerBefore = before.drawable.single { it.strokeId != pinnedStroke.strokeId }

        // 字号 40 → 80：整篇重排，库里存的锚点与冻结几何一个字节都没动。
        val bigger = factsOf(session, theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val after = readBack(meta, bigger)

        val pinnedAfter = after.drawable.single { it.strokeId == pinnedStroke.strokeId }
        assertArrayEquals(
            "边注必须一动不动：冻结几何就是它落笔那一刻在页面上的位置",
            pinnedBefore.docPoints,
            pinnedAfter.docPoints,
            0f,
        )
        assertEquals("粗细也要冻住（笔宽 = em × 那时的字号）", pinnedBefore.fontSizePx, pinnedAfter.fontSizePx, 0f)

        val followerAfter = after.drawable.single { it.strokeId != pinnedStroke.strokeId }
        assertTrue(
            "正文笔迹必须跟着放大后的行盒走，否则「跟随文字」就名不副实",
            Math.abs(followerAfter.docPoints[1] - followerBefore.docPoints[1]) > 1f,
        )
    }

    /**
     * 改版之后边注仍然画在原处。
     *
     * 硬规则"解不开就不画"防的是**猜一个位置画上去**；冻结边注的位置不是猜的
     * （它存在库里，与解析结果无关），所以它必须仍然出现在屏幕上 ——
     * 而且记录要如实说明"墨迹还在，只是不再挂在任何一行上"。
     */
    @Test
    fun `改版之后页边笔记仍然画在原处`() {
        val meta = importBody()
        val original = factsOf(sessionOf(meta))
        val line = original.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        val controller = controllerFor(meta, original)
        controller.beginStroke(4f, y, 1f)
        controller.extendStroke(16f, y + 8f, 1f)
        controller.endStroke()
        awaitUntil("边注落盘") { inkCount(meta) == 1 }

        val before = readBack(meta, original).drawable.single()
        val changed = withFingerprint(original, "fp-换了一版")
        val after = readBack(meta, changed)

        assertEquals("锚点必须判孤儿（绝不猜位置）", 1, after.orphaned.size)
        assertTrue("但冻结的墨迹必须还在屏幕上", after.records.single().drawn)
        assertArrayEquals(
            "而且就在它当时被写下的位置上",
            before.docPoints,
            after.drawable.single().docPoints,
            0f,
        )
        assertTrue(
            "它不属于「画不出来」那一类，不该出现在那张清单里",
            after.notDrawn.isEmpty(),
        )
        assertTrue(
            "原因要说清墨迹还在，否则用户会到处找一条其实就在眼前的笔记",
            after.records.single().reason.contains("仍画在原来的位置"),
        )
    }

    // ── 改挂：把一条**已经挂好的**笔记换一行（M3.2） ─────────────────────────

    /**
     * 库里那条笔迹行（本文件里都是"一条笔记"的场景）。
     * 改挂有没有保留冻结几何，要看它。
     */
    private fun storedStroke(meta: DocumentMeta): StrokeAnchor = runBlocking {
        val snapshot = container.store.annotations.listByDoc(meta.id)
        val ink = snapshot.annotations.filterIsInstance<Annotation.Ink>().single()
        snapshot.strokesOf(ink.id).single()
    }

    /**
     * 改挂一条**已经画在页面上**的边注：指向换成新行，墨迹整块挪到那一行旁边。
     *
     * 与"确认位置"走的是同一条写入路径（同一个 `NoteWriter`、同一套
     * `InkReanchor`），差别只在处境：这条笔记此刻看得见，用户是**主动**说
     * "它该记在那一行"。而这正是 M3.1 当时不给入口的那一类
     * （判据曾是 `state != RESOLVED && !drawn`）。
     *
     * 为什么必须走整链路：冻结语义（PINNED）的墨迹与锚点是两条轴，
     * 改挂只该改后者。重挂若顺手把 em 形状按新行换算一遍，症状就是
     * "改挂之后页边批注跑到正文里去了"（新行的 em 原点在正文栏左边界上）——
     * 而两侧各自的单元测试都会是绿的。所以这里断言的不是"坐标一个字节都不变"，
     * 而是"**没有按新行重算**"：横向留在页边、形状与笔宽不变，
     * 只有纵向整块平移了一下（M3.4 的落位，见 `PinnedPlacement`）。
     */
    @Test
    fun `改挂一条已经画出来的边注：指向换成新行而墨迹整块挪到那一行旁边`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val line = facts.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        val controller = controllerFor(meta, facts)
        // 左边距里的一条边注：x < marginLeft(24) 才会被判成 PINNED。
        controller.beginStroke(4f, y, 1f)
        controller.extendStroke(10f, y + 6f, 1f)
        controller.extendStroke(16f, y + 12f, 1f)
        controller.endStroke()
        awaitUntil("边注落盘") { inkCount(meta) == 1 }

        val before = readBack(meta, facts)
        val ink = storedInk(meta)
        assertEquals(InkReflowSemantics.PINNED, ink.semantics)
        assertNotNull("边注必须带着冻结几何进库", storedStroke(meta).frozenMatrix)

        val row = before.rowsForDisplay(facts).single()
        assertTrue("它此刻画得出来", row.drawn)
        assertTrue("画得出来也必须给得出改挂入口（判据是有没有笔迹可挂）", row.canReanchor)
        val beforeDrawn = before.drawable.single()

        // 用户从面板里按下「改挂」，搜索到第 4 行，点「挂到这一处」。
        val target = facts.lines[3]
        val outcome = runBlocking {
            NoteWriter(container, meta.id).attachToCharOffset(
                annotationId = ink.id,
                charOffset = facts.absoluteRangeOf(target)!!.first,
                facts = facts,
            )
        }
        assertTrue("必须真的挂上了，实际=${outcome.message}", outcome.attached)

        val after = readBack(meta, facts)
        assertEquals("改挂之后仍然解得开：${after.records.map { it.reason }}", 1, after.resolvedCount)
        assertEquals(
            "必须落在用户选的那一行上",
            target.anchor.blockPath,
            after.records.single().line?.anchor?.blockPath,
        )

        // 改挂改的是**指向**，不是"把它当成跟随文字的笔迹重算一遍" —— 后者正是
        // 老毛病"改挂之后页边批注跑到正文里去了"：新行的 em 原点是正文栏的左边界，
        // 于是 em 形状一旦按新行换算，边注就被搬进了正文里。
        // M3.4 之后它多了半句：固定的墨迹会**整块**挪到新那一行旁边（`PinnedPlacement`），
        // 但横向一个像素都不动、形状与笔宽也一个字都不改 —— 它始终是页边的那张便利贴。
        val moved = after.drawable.single()
        var dy: Float? = null
        var i = 0
        while (i < moved.docPoints.size) {
            assertEquals(
                "横向必须留在页边（不许被搬到正文栏里去）：${moved.docPoints[i]}",
                beforeDrawn.docPoints[i],
                moved.docPoints[i],
                0f,
            )
            val step = moved.docPoints[i + 1] - beforeDrawn.docPoints[i + 1]
            if (dy == null) dy = step else assertEquals("必须是整体平移", dy, step, 1e-3f)
            i += 2
        }
        assertEquals("笔宽也不许变（它是落笔那一刻的字号）", beforeDrawn.fontSizePx, moved.fontSizePx, 0f)
        assertTrue(
            "改挂之后它必须挨着新那一行：${after.records.single().reason}",
            after.records.single().reason.contains("已挪到目标行旁边的空白处"),
        )
        assertEquals(
            "落点就是那一行的顶边（页边里没有别的障碍）",
            target.rectDoc.top,
            moved.docPoints[1],
            1e-3f,
        )
        assertNotNull("冻结几何必须原样保留（清掉它等于把用户的墨迹挪走）", storedStroke(meta).frozenMatrix)
    }

    /**
     * 改字号之后固定的边注**跟到它记的那一行旁边**，而库里的数据一个字节都没动。
     *
     * 这是 M3.4 要回答的那笔账（方案 §6.3.3、M3 §20.6）：PINNED 的墨迹一冻结，
     * "形状不动"这件事就永远对了，可**位置只会越来越不对** —— 文字重排之后，
     * 原来贴着第 5 行的边注跑到第 3 行旁边去了。缺的那一步是"重新摆放到目标行附近的空白区"。
     *
     * 为什么必须走整链路：位置是"冻结几何 + 当前排版"算出来的，而冻结几何在库里。
     * 只在内存里看，编码器漏写冻结变换、或者仓储把锚点与笔迹的对应搞反，
     * 症状都是一样的"重开文档之后边注对着别的段落"。
     */
    @Test
    fun `改字号之后固定的边注跟到它记的那一行旁边`() {
        val meta = importBody()
        val original = factsOf(sessionOf(meta))
        // 写在第 5 行（最后一行）的左边距里：行号越大，重排后偏离得越远。
        val line = original.lines.last()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        val controller = controllerFor(meta, original)
        controller.beginStroke(4f, y, 1f)
        controller.extendStroke(10f, y + 6f, 1f)
        controller.extendStroke(16f, y + 12f, 1f)
        controller.endStroke()
        awaitUntil("边注落盘") { inkCount(meta) == 1 }

        val stroke = storedStroke(meta)
        val frozen = stroke.frozenMatrix
        assertNotNull("边注必须带着落笔那一刻的冻结几何进库", frozen)
        val before = readBack(meta, original)
        val beforeDrawn = before.drawable.single()
        assertEquals("前提：它记的就是最后一行", line.anchor.blockPath, before.records.single().line?.anchor?.blockPath)
        assertFalse("前提：它此刻就在那一行旁边（没有要挪的理由）", before.records.single().reason.contains("已挪到"))

        // 字号 40 → 80：整篇重排，最后一行被推下去一大截。
        val bigger = factsOf(
            sessionOf(meta),
            theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f),
        )
        val after = readBack(meta, bigger)
        val moved = after.drawable.single()
        val record = after.records.single()
        val row = record.line!!.rectDoc

        assertTrue(
            "前提：新排版下它确实离那一行很远（否则这个测试什么都没测）",
            Math.abs(beforeDrawn.docPoints[1] - row.top) > row.height,
        )
        assertEquals("边注要被摆到目标行旁边", row.top, moved.docPoints[1], 1e-3f)
        assertTrue("必须说得出为什么挪了：${record.reason}", record.reason.contains("已挪到目标行旁边的空白处"))

        // 只平移：横向一个像素都不动，形状与笔宽还是落笔那一刻的。
        var dy: Float? = null
        var i = 0
        while (i < moved.docPoints.size) {
            assertEquals("横向不许动", beforeDrawn.docPoints[i], moved.docPoints[i], 0f)
            val step = moved.docPoints[i + 1] - beforeDrawn.docPoints[i + 1]
            if (dy == null) dy = step else assertEquals("必须是整体平移", dy, step, 1e-3f)
            i += 2
        }
        assertEquals("笔宽用的是落笔时的字号", beforeDrawn.fontSizePx, moved.fontSizePx, 0f)

        // 库里一个字节都没动：打开一次文档不是一次改数据。
        val storedAgain = storedStroke(meta)
        assertEquals("冻结几何必须原样", frozen, storedAgain.frozenMatrix)
        assertArrayEquals("笔迹点也必须原样", stroke.localPoints, storedAgain.localPoints, 0f)

        // 幂等：同一个排版再读一次，位置一模一样（否则每打开一次就往下挪一点）。
        val again = readBack(meta, bigger).drawable.single()
        assertArrayEquals("同一个排版读两次必须给出同一个位置", moved.docPoints, again.docPoints, 0f)
    }
}
