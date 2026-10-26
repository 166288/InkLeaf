package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.InkLayerController
import dev.inkleaf.core.ink.InkResolver
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.lineHighlightRange
import dev.inkleaf.core.ink.underlineBands
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 本次补完的三件事的整链路：**下划线 / 书签 / 整笔擦除**。
 *
 * 三者的共同点是"库里有记录，但以前没有入口"（下划线、书签）或"有记录却救不回来"
 * （擦除的撤销）。所以这里测的不是纯函数，而是"点下去之后库里到底发生了什么"：
 *
 *  - 下划线：写进去的链能在当前排版里解析回**同一行的基线**；
 *  - 书签：不依赖正文也能存在，但必须带上页号与进度（否则它回不到"刚才那一眼"）；
 *  - 擦除：删掉之后笔迹真的没了；撤销之后**连点集一起**回来，并且仍能解析出笔画。
 *
 * 最后一条是本次最容易做错的地方：仓储的 delete 对笔迹是硬删，
 * 只把标注行 undelete 回来会得到一条"有锚点、没有墨迹"的空壳 ——
 * 而它在界面上表现为"撤销成功了，但我的字没回来"。这个断言就是为了挡它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecorRoundTripTest {

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
        val source = File(container.context.cacheDir, "装饰导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "装饰测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    private fun writer(meta: DocumentMeta) = NoteWriter(
        container = container,
        docId = meta.id,
        idFactory = { "note-1" },
        highlightIdFactory = { "hl-1" },
        underlineIdFactory = { "ul-1" },
        bookmarkIdFactory = { "bm-1" },
        now = { 1_234L },
    )

    private fun notesOf(meta: DocumentMeta): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）：$what")
    }

    @Test
    fun `下划线写进去之后解析回同一行的基线`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val line = facts.lines[1]
        val range = facts.lineHighlightRange(line)!!

        val outcome = runBlocking { writer(meta).addUnderline(range.first, range.last + 1, facts) }
        assertTrue(outcome.message, outcome.added)

        val underline = notesOf(meta).filterIsInstance<Annotation.Underline>().single()
        assertEquals("一行一条链", 1, underline.anchors.size)
        assertTrue("默认是直线，不是波浪", !underline.style.wavy)

        val band = underlineBands(listOf(underline), facts).single()
        assertEquals("必须贴着这一行的基线", line.baselineY, band.baselineY, 0.01f)
        assertEquals("横向范围来自行盒", line.rectDoc.left, band.left, 0.01f)
    }

    @Test
    fun `波浪线把样式存进标注而不是界面开关`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val range = facts.lineHighlightRange(facts.lines[0])!!

        runBlocking { writer(meta).addUnderline(range.first, range.last + 1, facts, wavy = true) }

        val stored = notesOf(meta).filterIsInstance<Annotation.Underline>().single()
        assertTrue("关掉开关之后这条线仍然是波浪的", stored.style.wavy)
    }

    @Test
    fun `下划线能删掉也能撤销回来`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val range = facts.lineHighlightRange(facts.lines[0])!!
        val note = writer(meta)
        runBlocking { note.addUnderline(range.first, range.last + 1, facts) }

        val id = notesOf(meta).filterIsInstance<Annotation.Underline>().single().id
        val deleted = runBlocking { note.deleteTypedNote(id) }
        assertTrue(deleted.message, deleted.changed)
        assertTrue("软删除之后不该再出现在清单里", notesOf(meta).none { it.id == id })

        val restored = runBlocking { note.restoreTypedNote(id) }
        assertTrue(restored.message, restored.changed)
        assertEquals(1, notesOf(meta).count { it.id == id })
    }

    @Test
    fun `书签带上页号与进度`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val offset = facts.absoluteRangeOf(facts.lines[2])!!.first

        val outcome = runBlocking { writer(meta).addBookmark(offset, facts, pageKey = "p7") }
        assertTrue(outcome.message, outcome.added)

        val bookmark = notesOf(meta).filterIsInstance<Annotation.Bookmark>().single()
        assertEquals("页号是 PDF 的兜底入口，丢了它书签只能回到大概位置", "p7", bookmark.progressHint.pageKey)
        assertNotNull("有正文时必须建出链（重排后才回得去）", bookmark.anchor)
        assertTrue("提示里要有那一行的摘录", bookmark.progressHint.nearbyText.isNotBlank())
    }

    @Test
    fun `书签能删掉也能撤销回来`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val note = writer(meta)
        runBlocking { note.addBookmark(0, facts) }

        val id = notesOf(meta).filterIsInstance<Annotation.Bookmark>().single().id
        assertTrue(runBlocking { note.deleteTypedNote(id) }.changed)
        assertTrue(notesOf(meta).none { it.id == id })
        assertTrue(runBlocking { note.restoreTypedNote(id) }.changed)
        assertEquals(1, notesOf(meta).count { it.id == id })
    }

    @Test
    fun `擦掉一笔之后撤销能把点集一起带回来`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val note = writer(meta)

        // 先真实地写一笔（走 InkLayerController → RepositoryInkSink → 库）。
        val controller = InkLayerController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = RepositoryInkSink(container, meta.id),
            facts = { facts },
            onChanged = {},
            worker = Dispatchers.Unconfined,
            idFactory = { "ink-erase" },
        )
        val line = facts.lines.first()
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        controller.beginStroke(30f, y, 1f)
        controller.extendStroke(90f, y, 1f)
        controller.endStroke()
        awaitUntil("笔迹落盘") { notesOf(meta).any { it is Annotation.Ink } }

        val id = notesOf(meta).filterIsInstance<Annotation.Ink>().single().id
        val before = runBlocking { container.store.annotations.listByDoc(meta.id) }
        assertEquals(1, before.strokesOf(id).size)

        // 橡皮手里只有**笔迹 id**（`DrawableStroke.strokeId`），所以这里也必须传它。
        // 传标注 id 曾经也能"过"—— 那时候 eraseInk 按标注 id 查，而渲染层传的是笔迹 id，
        // 接缝两边的单测各自都绿。设备测试（InkToolsOnDeviceTest）才把它照出来。
        val strokeId = before.strokesOf(id).single().strokeId
        val erased = runBlocking { note.eraseInk(strokeId) }
        assertTrue(erased.message, erased.changed)
        assertNotNull("擦除必须留下撤销现场", erased.erased)
        assertEquals("擦掉的应当是这条笔迹所属的标注", id, erased.erased!!.annotation.id)
        assertTrue("删掉之后标注不该还在", notesOf(meta).none { it.id == id })

        val restored = runBlocking { note.restoreErasedInk(erased.erased!!) }
        assertTrue(restored.message, restored.changed)

        val after = runBlocking { container.store.annotations.listByDoc(meta.id) }
        val ink = after.annotations.filterIsInstance<Annotation.Ink>().single()
        assertEquals("点集必须一起回来，否则就是一条画不出来的空壳", 1, after.strokesOf(ink.id).size)

        // 回来之后还必须能画：只断言"库里有"是不够的，解析失败同样看不见。
        val resolution = InkResolver.resolve(
            inks = listOf(ink),
            strokesOf = after::strokesOf,
            brushesOf = after::brushesOf,
            facts = facts,
        )
        assertEquals(1, resolution.drawable.size)
    }

    @Test
    fun `擦除一条已经不存在的笔迹不会谎报成功`() {
        val meta = importBody()
        val outcome = runBlocking { writer(meta).eraseInk("does-not-exist") }
        assertTrue("不存在的 id 必须是失败", !outcome.changed)
        assertNull(outcome.erased)
    }
}
