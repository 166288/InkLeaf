package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.InkState
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.typedNoteRows
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.Side
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * 打字批注（边注）的整链路测试：**建 → 落库 → 读出 → 按当前排版解析 → 改挂**。
 *
 * 走真实链路，而不是分别测 `NoteWriter` 与 `typedNoteRows` 的返回值：
 * 这两头各自绿、拼起来错，正是本工程已经吃过两次亏的错误模式（M3c §15）。
 * 具体到边注，最容易拼错的一处是**字符偏移的口径** ——
 * 界面给的是"当前读到哪"，锚点写的是"归一化全文里的哪一段"，
 * 两者一旦不是一个坐标系，边注就会稳稳地挂在隔壁那一行上（而单测全是绿的）。
 *
 * 本机没有设备也没有模拟器（无虚拟化），所以这是唯一能跑通整条链路的地方。
 * 它替换不了"在手机上点一下工具栏图标"，但能挡住"点下去什么都不会发生"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypedNoteRoundTripTest {

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

    /** 短行，不折行：折行会改变"行 ←→ 块内偏移"的对应，那是另一个独立话题。 */
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
        val source = File(container.context.cacheDir, "边注导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "边注测试.txt",
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
     * 一行正文在**归一化全文**里的起点。
     *
     * 这不是测试在图省事：界面传给写入出口的正是这个值
     * （`ReaderScreen` 里 `layout.charOffsetOfScrollY(...)` 给的就是它），
     * 所以用它当入口才叫"整链路"。
     */
    private fun offsetOfLine(facts: LayoutFacts, index: Int): Int =
        facts.absoluteRangeOf(facts.lines[index])?.first
            ?: throw AssertionError("第 $index 行取不到绝对区间，测试前提不成立")

    private fun writer(meta: DocumentMeta, id: String = "note-1") =
        NoteWriter(container, meta.id, idFactory = { id }, now = { 1_234L })

    private fun notesOf(meta: DocumentMeta): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    private fun storedMarginNote(meta: DocumentMeta): Annotation.MarginNote =
        notesOf(meta).filterIsInstance<Annotation.MarginNote>().single()

    @Test
    fun `写一条边注：落库、读回、解析到当前读到的那一行`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val target = 2

        val outcome = runBlocking {
            writer(meta).addMarginNote("这里要再想一遍", offsetOfLine(facts, target), facts)
        }
        assertTrue("必须真的写下了，实际=${outcome.message}", outcome.added)
        assertTrue(
            "结果提示必须说清挂到了哪句话上（这是用户唯一的确认手段），实际=${outcome.message}",
            outcome.message.startsWith("已把边注挂在："),
        )

        val stored = storedMarginNote(meta)
        assertEquals("note-1", stored.id)
        assertEquals(meta.id, stored.docId)
        assertEquals(1_234L, stored.createdAt)
        assertEquals("边注正文必须原样进库", "这里要再想一遍", stored.noteText)
        assertEquals("v1 的边注默认在右侧", Side.RIGHT, stored.side)
        assertEquals(
            "链里的指纹必须是导入时算出来的那一个，否则边注在写下的那一刻就已经是孤儿",
            meta.sourceFingerprint,
            stored.anchor.sourceFingerprint,
        )
        assertEquals(facts.textOf(facts.lines[target]), stored.anchor.textQuote?.exact)

        val row = typedNoteRows(notesOf(meta), facts).single()
        assertEquals(InkState.RESOLVED, row.state)
        assertEquals("必须解析回当初那一行，而不是隔壁那一行", facts.textOf(facts.lines[target]), row.excerpt)
        assertEquals(offsetOfLine(facts, target), row.charOffset)
        assertEquals("这里要再想一遍", row.noteText)
        assertTrue(row.located)
    }

    @Test
    fun `空内容的边注不会写进库`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))

        val outcome = runBlocking { writer(meta).addMarginNote("   ", offsetOfLine(facts, 0), facts) }

        assertFalse("空白内容必须被拒绝", outcome.added)
        assertTrue("拒绝也要说清为什么，实际=${outcome.message}", outcome.message.contains("空"))
        assertTrue("库里不该多出任何东西", notesOf(meta).isEmpty())
    }

    @Test
    fun `换字号之后边注仍然挂在同一句话上`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val original = factsOf(session)
        runBlocking { writer(meta).addMarginNote("字号变了也要跟着走", offsetOfLine(original, 1), original) }

        // 字号 40 → 80：整篇重排，库里那条锚点一个字节都没动。
        val bigger = factsOf(session, theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val row = typedNoteRows(notesOf(meta), bigger).single()

        assertEquals(
            "重排后必须仍然解得开：${row.reason}",
            InkState.RESOLVED,
            row.state,
        )
        assertEquals(
            "必须还挂在同一句话上 —— 边注是打字批注，跟着文字走是它唯一该有的行为",
            original.textOf(original.lines[1]),
            row.excerpt,
        )
        assertEquals("字号变了，跳转偏移也要按新排版给", offsetOfLine(bigger, 1), row.charOffset)
    }

    @Test
    fun `改挂一条边注：换的是它指向的那一行`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val from = 0
        val to = 4
        runBlocking { writer(meta).addMarginNote("记错行了", offsetOfLine(facts, from), facts) }
        val id = storedMarginNote(meta).id

        val outcome = runBlocking {
            writer(meta).attachToCharOffset(id, offsetOfLine(facts, to), facts)
        }

        assertTrue("必须真的挂上了，实际=${outcome.message}", outcome.attached)
        assertTrue(
            "成功提示的口径必须与手写笔迹一样，实际=${outcome.message}",
            outcome.message.startsWith("已把这条笔记挂到："),
        )

        val stored = storedMarginNote(meta)
        assertEquals(
            "链里的引文必须换成新那一行",
            facts.textOf(facts.lines[to]),
            stored.anchor.textQuote?.exact,
        )
        assertEquals(facts.lines[to].anchor, stored.anchor.structural)
        assertEquals(
            "改挂只改指向：用户写的那句话一个字都不许动",
            "记错行了",
            stored.noteText,
        )

        val row = typedNoteRows(notesOf(meta), facts).single()
        assertEquals(InkState.RESOLVED, row.state)
        assertEquals(facts.textOf(facts.lines[to]), row.excerpt)
        assertEquals(offsetOfLine(facts, to), row.charOffset)
        assertNotNull("库里的引文是「确认位置」要用的那条线索", row.originalText)
    }

    /**
     * 库里没有这条笔记时必须**明确拒绝**，而不是静默什么都不做。
     *
     * 这条路径原来只找笔迹（`filterIsInstance<Annotation.Ink>`），
     * 于是给一条边注改挂会得到"这条笔记已经不在了"——一句不实的提示。
     * 现在它按 id 在全部标注里找，所以这一条测的是"找不到"与"找错了类型"的区分。
     */
    @Test
    fun `改挂一条不存在的边注会被明确拒绝`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))

        val outcome = runBlocking {
            writer(meta).attachToCharOffset("note-不存在", offsetOfLine(facts, 0), facts)
        }

        assertFalse(outcome.attached)
        assertTrue("失败也要说清是哪一步，实际=${outcome.message}", outcome.message.contains("不在了"))
    }

    @Test
    fun `边注与笔迹能被同一个出口区分开`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        runBlocking { writer(meta).addMarginNote("这是一条边注", offsetOfLine(facts, 0), facts) }
        val noteId = storedMarginNote(meta).id

        // 同一个方法、同一个 id 空间：入口按**库里那条标注的真实类型**分派，
        // 界面不需要（也不该）知道自己手上是墨迹还是文字。
        val outcome = runBlocking { writer(meta).attachToCharOffset(noteId, offsetOfLine(facts, 5), facts) }

        assertTrue("边注也能从同一个出口改挂，实际=${outcome.message}", outcome.attached)
        assertEquals(
            "改挂之后仍然只有这一条边注（没有多写出一条笔迹或副本）",
            1,
            notesOf(meta).size,
        )
        assertEquals(facts.textOf(facts.lines[5]), typedNoteRows(notesOf(meta), facts).single().excerpt)
    }
}
