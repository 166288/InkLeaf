package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.DrawableStroke
import dev.inkleaf.core.ink.InkLayerController
import dev.inkleaf.core.ink.InkResolver
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 套索移动（S1）的整链路测试：**落笔 → 解析 → 平移重新锚定 → 落库 → 回读 → 撤销**。
 *
 * ## 为什么它必须单独有一条
 *
 * `LassoAndMoveTest` 测的是 `InkMove.plan`（纯函数，不碰库）。但移动真正的风险
 * 在**写库那一半**：它是"写一份新的 + 撤一份旧的"两步写，而这两步之间任何一处
 * 走样都不会被纯函数测试发现 ——
 *
 * - 只撤旧的没写新的 ⇒ 墨没了。用户看到的是"我拖了一下，字消失了"。
 * - 只写新的没撤旧的 ⇒ 墨变两份。屏幕上看着挪过去了，库里却多一条，
 *   于是笔记清单里凭空多一行，撤销也撤不干净。
 * - 新记录沿用了新的创建时间 ⇒ 清单顺序跳一下，用户会以为笔记丢了。
 *
 * 这三条全都是"纯函数测试全绿、用户当场翻车"的类型。
 *
 * ## 本机没有设备也没有模拟器（无虚拟化）
 *
 * 所以这里跑不了"用手指画个圈再拖一下"，但"拖完之后库里变成什么样"必须测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoveInkRoundTripTest {

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

    /** 与 InkRoundTripTest 同一份正文：短行，不折行。 */
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

    private fun importBody(): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "移动导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "移动测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession): LayoutFacts =
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

    private fun writer(meta: DocumentMeta): NoteWriter {
        var seq = 0
        return NoteWriter(
            container = container,
            docId = meta.id,
            idFactory = { "moved-${++seq}" },
            highlightIdFactory = { "hl-${++seq}" },
            now = { 1_234L },
        )
    }

    /** 等落盘发生：saveInk 会切到仓储自己的 IO 调度器。 */
    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）：$what")
    }

    private fun inkCount(meta: DocumentMeta): Int = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.count { it is Annotation.Ink }
    }

    private fun storedInk(meta: DocumentMeta): Annotation.Ink = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.filterIsInstance<Annotation.Ink>().single()
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

    /** 行盒的纵向中点。移动用的 dy 就是两行的中点之差。 */
    private fun centerY(facts: LayoutFacts, index: Int): Float {
        val rect = facts.lines[index].rectDoc
        return (rect.top + rect.bottom) / 2f
    }

    /** 在第 [index] 行中间划一条横线，等它落盘。 */
    private fun writeOnLine(meta: DocumentMeta, facts: LayoutFacts, index: Int) {
        val y = centerY(facts, index)
        val controller = controllerFor(meta, facts)
        controller.beginStroke(30f, y, 1f)
        controller.extendStroke(90f, y, 1f)
        controller.extendStroke(150f, y, 1f)
        controller.endStroke()
        awaitUntil("笔迹落盘") { inkCount(meta) == 1 }
    }

    @Test
    fun `挪一笔：库里换成一条新记录并锚到了目标行，创建时间沿用旧的`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        writeOnLine(meta, facts, 0)
        val before = storedInk(meta)
        assertEquals("前提：写在了第 0 行", "blk[0]", readBack(meta, facts).records.single().line?.anchor?.blockPath)

        val drawn = readBack(meta, facts).drawable
        val outcome = runBlocking {
            writer(meta).moveInk(drawn, dx = 0f, dy = centerY(facts, 1) - centerY(facts, 0), facts = facts)
        }

        assertTrue("移动必须真的改了库，实际=${outcome.message}", outcome.changed)
        assertTrue("提示里必须给出路（怎么撤销），实际=${outcome.message}", outcome.message.contains("撤销"))

        awaitUntil("新记录落盘、旧记录撤掉") { storedInk(meta).id != before.id }
        assertEquals("写新的 + 撤旧的 ⇒ 总数仍然是 1，不是 2 也不是 0", 1, inkCount(meta))

        val after = storedInk(meta)
        assertNotEquals("移动是一条**新**记录：撤销要能分清哪份是新写的", before.id, after.id)
        assertEquals("创建时间必须沿用旧的，否则清单里的顺序会跳", before.createdAt, after.createdAt)
        assertEquals("墨色也该跟着走", before.colorArgb, after.colorArgb)

        val resolution = readBack(meta, facts)
        assertEquals("挪完之后必须解得开：${resolution.records.map { it.reason }}", 1, resolution.resolvedCount)
        assertEquals(
            "重新锚定必须落在**新的那一行**上，而不是还指着旧行",
            "blk[1]",
            resolution.records.single().line?.anchor?.blockPath,
        )
    }

    @Test
    fun `撤销移动：旧的那条原样回来，新的那条撤掉`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        writeOnLine(meta, facts, 0)
        val before = storedInk(meta)

        val drawn = readBack(meta, facts).drawable
        val moved = runBlocking {
            writer(meta).moveInk(drawn, dx = 0f, dy = centerY(facts, 2) - centerY(facts, 0), facts = facts)
        }
        awaitUntil("移动落盘") { storedInk(meta).id != before.id }
        val movedId = storedInk(meta).id

        val undone = runBlocking { writer(meta).undoMove(moved.moved!!) }

        assertTrue("撤销必须真的改了库，实际=${undone.message}", undone.changed)
        awaitUntil("撤销落盘") { storedInk(meta).id == before.id }
        assertEquals("回来的是原来那一条记录，不是又一条新的", before.id, storedInk(meta).id)
        assertTrue("新建的那条必须撤掉，不能留下两份墨", inkAnnotationsOf(meta).none { it.id == movedId })
        assertEquals("总数回到 1", 1, inkCount(meta))

        val resolution = readBack(meta, facts)
        assertEquals("撤销后必须解得开：${resolution.records.map { it.reason }}", 1, resolution.resolvedCount)
        assertEquals(
            "位置也要回到原来那一行",
            "blk[0]",
            resolution.records.single().line?.anchor?.blockPath,
        )
    }

    /**
     * 拖到正文下方的空白处。
     *
     * 这一条钉的是"移动不等于吸附到某行"：`assignLine` 对落在任何行盒之外的点会
     * 回落到**纵向最近的一行**，但回落只决定"锚在哪一行"，**不改落点** ——
     * em 空间与文档坐标之间是仿射变换，来回一趟位置分毫不差。
     * 否则"把批注拖到页边留白"会变成"被吸回正文里"，那是谎报位置。
     */
    @Test
    fun `拖到正文外的空白处：墨停在松手的那个位置，不会吸回某一行`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        writeOnLine(meta, facts, 0)
        val y0 = centerY(facts, 0)
        val dy = 400f

        val drawn = readBack(meta, facts).drawable
        val outcome = runBlocking { writer(meta).moveInk(drawn, dx = 0f, dy = dy, facts = facts) }

        assertTrue("移动到页边留白是合法的，实际=${outcome.message}", outcome.changed)
        awaitUntil("移动落盘") { inkCount(meta) == 1 }

        val resolution = readBack(meta, facts)
        assertEquals("挪完必须解得开：${resolution.records.map { it.reason }}", 1, resolution.resolvedCount)
        val moved = resolution.drawable.single()
        for (i in moved.docPoints.indices step 2) {
            assertEquals(
                "第 ${i / 2} 个点必须停在拖到的位置上（松手在哪就画在哪），而不是被吸附到行里",
                drawn.single().docPoints[i + 1] + dy,
                moved.docPoints[i + 1],
                0.5f,
            )
        }
        assertTrue("留白在正文下方，所以纵向最近的一行不该还是第 0 行", y0 + dy > centerY(facts, 0))
    }

    /**
     * 整体拒绝：**有一笔挪过去之后取不到引文**。
     *
     * 真实版式里每一行都有文字，所以这个分支平时走不到 —— 但它是"硬记下来会在
     * 下次打开时变成孤儿"的唯一防线（`AnchorChainFactory.forLine` 需要绝对区间）。
     * 用一个取不到区间的版式把它逼出来，比让它永远没人跑要好。
     */
    @Test
    fun `挪过去取不到引文时整体拒绝，库里一点没动`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        writeOnLine(meta, facts, 0)
        val before = storedInk(meta)

        val noQuote = object : LayoutFacts by facts {
            override fun absoluteRangeOf(line: dev.inkleaf.core.model.LineBox): IntRange? = null
        }

        val drawn = readBack(meta, facts).drawable
        val outcome = runBlocking {
            writer(meta).moveInk(drawn, dx = 0f, dy = centerY(facts, 1) - centerY(facts, 0), facts = noQuote)
        }

        assertFalse("挪不动时必须如实说没挪，实际=${outcome.message}", outcome.changed)
        assertTrue(
            "拒绝要说清为什么（否则用户只会以为拖动这个功能坏了），实际=${outcome.message}",
            outcome.message.contains("没有移动"),
        )
        assertEquals("库里必须还是原来那一条", before.id, storedInk(meta).id)
        assertEquals("总数没变 —— 既没挪走，也没多出一份", 1, inkCount(meta))
        assertEquals(
            "位置也没变：整体拒绝，不会只挪一半",
            "blk[0]",
            readBack(meta, facts).records.single().line?.anchor?.blockPath,
        )
    }

    @Test
    fun `源记录找不到的笔迹不参与移动：不会变成复制`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        writeOnLine(meta, facts, 0)
        val y = centerY(facts, 0)

        // 一条屏幕上有、库里没有的笔迹（例如解析出来的临时段）。
        // 它挪得动（几何上成立），但找不到要撤掉的那条旧记录 ——
        // 这时写一份新的就是**复制**，屏幕上会平白多出一条一模一样的墨。
        val phantom = DrawableStroke(
            strokeId = "库里没有的这一笔",
            brush = BrushSpec.DEFAULT_PEN,
            docPoints = floatArrayOf(30f, y, 90f, y, 150f, y),
            fontSizePx = 40f,
        )

        val outcome = runBlocking {
            writer(meta).moveInk(
                listOf(phantom),
                dx = 0f,
                dy = centerY(facts, 1) - y,
                facts = facts,
            )
        }

        assertFalse("找不到源记录就该不动手，实际=${outcome.message}", outcome.changed)
        assertTrue("要说清为什么没挪，实际=${outcome.message}", outcome.message.contains("找不到"))
        assertEquals("库里必须还是只有原来那一笔：不能挪，也不能复制", 1, inkCount(meta))
        assertEquals(
            "原来那一笔还在原来那一行",
            "blk[0]",
            readBack(meta, facts).records.single().line?.anchor?.blockPath,
        )
    }

    private fun inkAnnotationsOf(meta: DocumentMeta): List<Annotation.Ink> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.filterIsInstance<Annotation.Ink>()
    }
}
