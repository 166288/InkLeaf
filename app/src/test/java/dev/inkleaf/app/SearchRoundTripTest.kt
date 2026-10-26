package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.ReaderSearch
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.TypedNoteKind
import dev.inkleaf.core.ink.TypedNoteRow
import dev.inkleaf.core.ink.typedNoteRows
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 搜索清单的整链路测试：**写下一条边注 → 落库 → 读回 → 按当前排版解析 → 搜到它**。
 *
 * 为什么非要从"写下来"这一步开始：搜索清单里那条边注的位置（`charOffset`）是**解析结果**，
 * 不是库里存的字段。写侧与读侧一旦用的不是同一套口径，症状就是"清单里搜得到、
 * 点下去跳到别的行上"—— 而 `ReaderSearch` 的单测与 `NoteWriter` 的单测都会是绿的。
 * 这个工程已经在"两侧各自绿、拼起来错"上吃过两次亏（M3c §15）。
 *
 * 本机没有设备（无虚拟化），所以这是唯一能跑通整条链路的地方：手机上的
 * "点清单里的一条"点不了，但"点下去会跳到哪个偏移"必须测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchRoundTripTest {

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

    /** 短行、不折行：折行会改变"行 ←→ 块内偏移"的对应，那是另一个独立话题。 */
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

    private fun importBody(body: String = this.body): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "搜索导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "搜索测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    /** 与导入同一个入口读回来（`DocumentCodec.sessionFromBytes`）。 */
    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession, theme: DocumentTheme = this.theme): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    /**
     * 只换指纹的一层包装：模拟"正文换了一版"。
     *
     * 行盒与区间原样转发（这正是这个测试要的：文字没变，只是"文件不再是那一份"）。
     * 注意 `LayoutFacts.lineAtOffset` 会被**连根转发**给内层 —— 见那个方法自己的注释；
     * 这里不依赖它在包装层上被改写，所以安全。
     */
    private fun withFingerprint(facts: LayoutFacts, fingerprint: String): LayoutFacts =
        object : LayoutFacts by facts {
            override val fingerprint: String = fingerprint
        }

    private fun offsetOfLine(facts: LayoutFacts, index: Int): Int =
        facts.absoluteRangeOf(facts.lines[index])?.first
            ?: throw AssertionError("第 $index 行取不到绝对区间，测试前提不成立")

    private fun writer(meta: DocumentMeta, id: String) =
        NoteWriter(container, meta.id, idFactory = { id }, now = { 1_234L })

    /** 界面列表与搜索用的是同一份东西：按当前排版解析过的批注行。 */
    private fun rows(meta: DocumentMeta, facts: LayoutFacts): List<TypedNoteRow> = runBlocking {
        typedNoteRows(container.store.annotations.listByDoc(meta.id).annotations, facts)
    }

    @Test
    fun `写在库里的边注能被搜到，位置就是界面跳过去的那个偏移`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val facts = factsOf(session)
        val target = 3
        runBlocking { writer(meta, "note-1").addMarginNote("别忘了核对这一行", offsetOfLine(facts, target), facts) }

        val result = ReaderSearch.search(session.normalized.text, rows(meta, facts), "核对")

        assertEquals("正文里没有这个词，命中的只可能是边注", 0, result.textCount)
        assertEquals(1, result.noteCount)
        val hit = result.hits.single() as ReaderSearch.NoteHit
        assertEquals("note-1", hit.annotationId)
        assertEquals(TypedNoteKind.MARGIN_NOTE, hit.kind)
        assertEquals(
            "跳转偏移必须是**当前排版**下那一行的起点，否则点下去会跳到隔壁行",
            offsetOfLine(facts, target),
            hit.charOffset,
        )
        assertEquals("清单里要显示它挂在哪一行，用户靠这句话确认是不是这一条", facts.textOf(facts.lines[target]), hit.row.excerpt)
        assertEquals("正文里一处都没有时才退到批注上", 0, result.defaultIndex)
    }

    @Test
    fun `正文命中与边注命中混在一份清单里，按正文位置排`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val facts = factsOf(session)
        runBlocking { writer(meta, "note-1").addMarginNote("这一行要再想想", offsetOfLine(facts, 2), facts) }

        val result = ReaderSearch.search(session.normalized.text, rows(meta, facts), "行")

        assertEquals("六行正文各一处", 6, result.textCount)
        assertEquals(1, result.noteCount)
        assertEquals(7, result.count)
        // 每行是"第 N 行正文"，所以正文命中在本行第 4 个字上（0 起）；边注挂在这一行的行首。
        assertEquals(
            listOf(
                offsetOfLine(facts, 0) + 4,
                offsetOfLine(facts, 1) + 4,
                offsetOfLine(facts, 2),
                offsetOfLine(facts, 2) + 4,
                offsetOfLine(facts, 3) + 4,
                offsetOfLine(facts, 4) + 4,
                offsetOfLine(facts, 5) + 4,
            ),
            result.hits.map { it.charOffset },
        )
        assertTrue("第 3 条是那条边注（它排在所挂那一行的正文命中前面）", result.hits[2] is ReaderSearch.NoteHit)
        assertEquals("默认跳的是正文里第一处，而不是清单里第一条", 0, result.defaultIndex)
    }

    @Test
    fun `换字号之后边注还是能被搜到，跳转偏移按新排版给`() {
        val meta = importBody()
        val original = factsOf(sessionOf(meta))
        runBlocking { writer(meta, "note-1").addMarginNote("字号变了也要找得到我", offsetOfLine(original, 1), original) }

        // 字号 40 → 80：整篇重排，库里那条锚点一个字节都没动。
        val bigger = factsOf(sessionOf(meta), theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val session = sessionOf(meta)

        val result = ReaderSearch.search(session.normalized.text, rows(meta, bigger), "找得到我")

        assertEquals(1, result.noteCount)
        assertEquals(
            "搜出来的位置必须按**新排版**算：旧偏移在新排版下会把用户带到别的行",
            offsetOfLine(bigger, 1),
            result.hits.single().charOffset,
        )
    }

    @Test
    fun `对不上正文的边注照样搜得到，只是跳不过去`() {
        val meta = importBody()
        val original = factsOf(sessionOf(meta))
        runBlocking { writer(meta, "note-1").addMarginNote("这句话我改过正文", offsetOfLine(original, 0), original) }

        // 只换指纹：模拟"正文换了一版"（库里那条锚点记的是"文件还是原来那一份"）。
        val changed = withFingerprint(original, "fp-换了一版")
        val session = sessionOf(meta)

        val result = ReaderSearch.search(session.normalized.text, rows(meta, changed), "改过正文")

        assertEquals("内容在库里，就必须在清单里出现", 1, result.noteCount)
        val hit = result.hits.single() as ReaderSearch.NoteHit
        assertNull("解析不到行，就没有可跳的位置", hit.charOffset)
        assertFalse(hit.row.located)
        assertEquals("没有可跳的就不许假装有", -1, result.defaultIndex)
        assertTrue(
            "点不动的那一条要能把为什么点不动带出来，否则它就是一行死链",
            hit.row.reason.isNotBlank(),
        )
    }
}
