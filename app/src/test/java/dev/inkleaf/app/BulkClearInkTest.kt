package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.InkLayerController
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 一键清除"画不出来的笔迹"（M17）。
 *
 * 这条路的关键不在"能不能删"，而在**删了之后能不能救回来**：
 * 仓储对笔迹是硬删（笔迹文件不能悬空），所以撤销必须靠删除前取出的现场
 * 原样写回去 —— 这和单笔擦除是同一套机制，但批量时多了两件事要钉：
 *
 *  1. 一批里混进一个坏 id，不能连累整批；
 *  2. 撤销要**逐条**恢复，并且每一条的点集都要一起回来。
 *     少了点集就是"撤销成功，但我的字没回来"——上次单笔擦除踩过一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BulkClearInkTest {

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
        val source = File(container.context.cacheDir, "批量清除导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "批量清除测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    private fun writer(meta: DocumentMeta) = NoteWriter(container = container, docId = meta.id)

    private fun notesOf(meta: DocumentMeta): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    private fun inkIds(meta: DocumentMeta): List<String> =
        notesOf(meta).filterIsInstance<Annotation.Ink>().map { it.id }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）：$what")
    }

    /** 在 [lineIndex] 那一行上落一笔。 */
    private fun drawStroke(meta: DocumentMeta, facts: LayoutFacts, lineIndex: Int, inkId: String) {
        val controller = InkLayerController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            sink = RepositoryInkSink(container, meta.id),
            facts = { facts },
            onChanged = {},
            worker = Dispatchers.Unconfined,
            idFactory = { inkId },
        )
        val line = facts.lines[lineIndex]
        val y = (line.rectDoc.top + line.rectDoc.bottom) / 2f
        controller.beginStroke(30f, y, 1f)
        controller.extendStroke(90f, y, 1f)
        controller.endStroke()
        awaitUntil("笔迹落盘：$inkId") { inkIds(meta).contains(inkId) }
    }

    @Test
    fun `一键清除后库里干净，撤销之后每一条都连点集一起回来`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        drawStroke(meta, facts, 0, "ink-a")
        drawStroke(meta, facts, 1, "ink-b")
        assertEquals(2, inkIds(meta).size)

        val note = writer(meta)
        val outcome = runBlocking { note.eraseInkAnnotations(inkIds(meta)) }

        assertEquals("两条都该被清掉", 2, outcome.changedCount)
        assertEquals("撤销现场要留两条", 2, outcome.erased.size)
        assertTrue("库里不该还有笔迹", inkIds(meta).isEmpty())
        assertTrue("提示里要有数字，否则用户不知道发生了什么", outcome.message.contains("2"))

        val restored = runBlocking { note.restoreErasedInkBatch(outcome.erased) }
        assertTrue(restored.message, restored.changed)
        assertEquals("两条都要回来", 2, inkIds(meta).size)

        // 只断言"库里有标注"是不够的：没有点集就是一条画不出来的空壳，
        // 而它在界面上表现为"撤销成功了，但我的字没回来"。
        val snapshot = runBlocking { container.store.annotations.listByDoc(meta.id) }
        snapshot.annotations.filterIsInstance<Annotation.Ink>().forEach { ink ->
            assertEquals("标注 ${ink.id} 的笔迹行没回来", 1, snapshot.strokesOf(ink.id).size)
        }
    }

    @Test
    fun `坏 id 不会连累整批`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        drawStroke(meta, facts, 0, "ink-only")
        val good = inkIds(meta).single()

        val outcome = runBlocking {
            writer(meta).eraseInkAnnotations(listOf(good, "does-not-exist"))
        }

        assertEquals("真实存在的那个必须被清掉", 1, outcome.changedCount)
        assertEquals(1, outcome.erased.size)
        assertTrue("坏 id 不该让整批回滚", inkIds(meta).isEmpty())
    }

    @Test
    fun `空列表什么都不做`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        drawStroke(meta, facts, 0, "ink-keep")

        val outcome = runBlocking { writer(meta).eraseInkAnnotations(emptyList()) }

        assertEquals(0, outcome.changedCount)
        assertTrue(outcome.erased.isEmpty())
        assertEquals("一条都不能动", 1, inkIds(meta).size)
    }

    @Test
    fun `重复的 id 只删一次`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        drawStroke(meta, facts, 0, "ink-dup")
        val id = inkIds(meta).single()

        val outcome = runBlocking { writer(meta).eraseInkAnnotations(listOf(id, id, id)) }

        assertEquals("同一条笔迹不能算三次", 1, outcome.changedCount)
        assertEquals(1, outcome.erased.size)
    }
}
