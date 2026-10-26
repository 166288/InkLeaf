package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.InkState
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.TypedNoteKind
import dev.inkleaf.core.ink.highlightRanges
import dev.inkleaf.core.ink.typedNoteRows
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.HighlightRenderMode
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 高亮的整链路测试：**标 → 落库 → 读出 → 按当前排版解析 → 画出来的区间 → 改挂**。
 *
 * 高亮是全工程唯一一种"链指的是一段字符、不是某一行"的标注，因此这条链路里最容易
 * 悄悄走样的一处是**区间坐标系**：界面手里是"归一化全文的哪一段"，链里存的是
 * "块内偏移"。两者一旦不是一个坐标系，高亮会稳稳地标在隔壁那一段上 ——
 * 而写入与解析各自的单元测试全是绿的。
 *
 * 第二条要钉死的是它相对边注多出来的那个承诺：**换字号之后仍然只盖住那几个字**。
 * 边注只要跟到正确的行就算对，高亮还必须跟到正确的字。
 *
 * 本机没有设备也没有模拟器（无虚拟化），所以这是唯一能跑通整条链路的地方：
 * 它替换不了"在手机上点一下工具栏图标"，但能挡住"点下去什么都没画出来"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightRoundTripTest {

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

    private fun importBody(body: String = this.body): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "高亮导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "高亮测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession, theme: DocumentTheme = this.theme): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    /** 一行正文在归一化全文里的起点（界面传给写入出口的正是这个值）。 */
    private fun offsetOfLine(facts: LayoutFacts, index: Int): Int =
        facts.absoluteRangeOf(facts.lines[index])?.first
            ?: throw AssertionError("第 $index 行取不到绝对区间，测试前提不成立")

    private fun writer(meta: DocumentMeta, noteId: String = "note-1", highlightId: String = "hl-1") =
        NoteWriter(
            container = container,
            docId = meta.id,
            idFactory = { noteId },
            highlightIdFactory = { highlightId },
            now = { 1_234L },
        )

    private fun notesOf(meta: DocumentMeta): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    private fun highlightsOf(meta: DocumentMeta): List<Annotation.Highlight> =
        notesOf(meta).filterIsInstance<Annotation.Highlight>()

    private fun rowOf(meta: DocumentMeta, facts: LayoutFacts) =
        typedNoteRows(notesOf(meta), facts).single()

    @Test
    fun `标一整行：落库、读回、解析回那一行，区间就是这一行的字`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val target = 2

        val outcome = runBlocking {
            writer(meta).addHighlight(offsetOfLine(facts, target), offsetOfLine(facts, target + 1) - 1, facts)
        }
        assertTrue("必须真的标上了，实际=${outcome.message}", outcome.added)
        assertTrue(
            "结果提示必须念出标中的那一段（这是用户唯一的确认手段），实际=${outcome.message}",
            outcome.message.startsWith("已把这一处标成高亮："),
        )

        val stored = highlightsOf(meta).single()
        assertEquals("hl-1", stored.id)
        assertEquals(meta.id, stored.docId)
        assertEquals(1_234L, stored.createdAt)
        assertEquals(
            "高亮的颜色就是荧光笔的颜色（同一个视觉语言）",
            BrushSpec.DEFAULT_HIGHLIGHTER.colorArgb,
            stored.colorArgb,
        )
        assertEquals(
            "链里的指纹必须是导入时算出来的那一个，否则高亮在写下的那一刻就已经是孤儿",
            meta.sourceFingerprint,
            stored.anchor.sourceFingerprint,
        )
        assertEquals("高亮没有正文：它标的是别人的话", null, stored.noteText)

        val band = highlightRanges(highlightsOf(meta), facts).single()
        assertEquals(
            "画出来的区间必须是标中的那一段（这里就是这一整行的字）",
            facts.absoluteRangeOf(facts.lines[target]),
            band.range,
        )

        val row = rowOf(meta, facts)
        assertEquals(InkState.RESOLVED, row.state)
        assertEquals(TypedNoteKind.HIGHLIGHT, row.kind)
        assertEquals("必须解析回当初那一行，而不是隔壁那一行", facts.textOf(facts.lines[target]), row.excerpt)
        assertEquals(offsetOfLine(facts, target), row.charOffset)
    }

    @Test
    fun `标一处命中：换字号之后仍然只盖住那几个字`() {
        val meta = importBody()
        val session = sessionOf(meta)
        val original = factsOf(session)
        // 第 2 行「第 2 行正文」里的「正文」两个字：块内偏移 5..6。
        val start = offsetOfLine(original, 2) + 5
        val endExclusive = start + 2
        assertEquals("正文", original.normalizedText.substring(start, endExclusive))

        val outcome = runBlocking { writer(meta).addHighlight(start, endExclusive, original) }
        assertTrue("必须真的标上了，实际=${outcome.message}", outcome.added)
        assertTrue("提示里要念出标中的字，实际=${outcome.message}", outcome.message.contains("正文"))

        // 字号 40 → 80：整篇重排，库里那条锚点一个字节都没动。
        val bigger = factsOf(session, theme = DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val band = highlightRanges(highlightsOf(meta), bigger).single()

        assertEquals(
            "重排后必须仍然标着同一段字（这是高亮相对边注多出来的那个承诺）",
            "正文",
            bigger.normalizedText.substring(band.range.first, band.range.last + 1),
        )

        val layout = session.layout(metrics, DocumentTheme.light(textSizePx = 80f, lineSpacingMultiplier = 1.4f))
        val rects = layout.rectsOfCharRange(band.range.first, band.range.last + 1)
        assertEquals("这一处只该画出一个矩形", 1, rects.size)
        // 「正文」在这一行的**尾部**，所以它右端与行盒右端齐平，窄是窄在左端：
        // 行首那两个字的宽度没有被涂上。
        val lineRect = factsOf(session).absoluteRangeOf(original.lines[2])?.let { layout.lineBoxes()[2].rectDoc }
        assertTrue(
            "色带必须窄于整行：标两个字却铺满一行，是对「我标了哪几个字」的谎报",
            lineRect != null && rects.single().left > lineRect.left + 1f && rects.single().right <= lineRect.right,
        )

        val row = typedNoteRows(notesOf(meta), bigger).single()
        assertEquals("面板里也必须还挂在同一行上：${row.reason}", InkState.RESOLVED, row.state)
        assertEquals(original.textOf(original.lines[2]), row.excerpt)
    }

    @Test
    fun `同一处标两遍会被拒绝`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 1) + 2
        val end = start + 2

        assertTrue(runBlocking { writer(meta).addHighlight(start, end, facts) }.added)
        val second = runBlocking { writer(meta, highlightId = "hl-2").addHighlight(start, end, facts) }

        assertFalse("同一段标两遍只会多出一条看不出差别的高亮", second.added)
        assertTrue("拒绝也要说清为什么，实际=${second.message}", second.message.contains("已经是高亮"))
        assertEquals("库里不该多出第二条", 1, highlightsOf(meta).size)
    }

    @Test
    fun `没有文字的区间标不上`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        // 「第 1 行正文」里的那个空格：它是一段真实存在的区间，但没有字可标。
        assertEquals(" ", facts.normalizedText.substring(1, 2))

        val outcome = runBlocking { writer(meta).addHighlight(1, 2, facts) }

        assertFalse("空白的区间必须被拒绝", outcome.added)
        assertTrue("拒绝的理由要说明白，实际=${outcome.message}", outcome.message.contains("没有文字"))
        assertTrue("库里不该多出任何东西", highlightsOf(meta).isEmpty())
    }

    @Test
    fun `高亮会出现在打字批注清单里，而且能改挂`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 1) + 2
        runBlocking { writer(meta).addHighlight(start, start + 2, facts) }
        val id = highlightsOf(meta).single().id

        val outcome = runBlocking { writer(meta).attachToCharOffset(id, offsetOfLine(facts, 5), facts) }

        assertTrue("高亮也能从同一个出口改挂，实际=${outcome.message}", outcome.attached)
        assertEquals("改挂之后仍然只有这一条高亮", 1, highlightsOf(meta).size)
        assertEquals(
            "链里的引文必须换成新那一行的文字",
            facts.textOf(facts.lines[5]),
            highlightsOf(meta).single().anchor.textQuote?.exact,
        )
        assertEquals("重挂之后的区间就是新那一行", facts.absoluteRangeOf(facts.lines[5]), highlightRanges(highlightsOf(meta), facts).single().range)
        assertEquals(InkState.RESOLVED, rowOf(meta, facts).state)
        assertEquals(facts.textOf(facts.lines[5]), rowOf(meta, facts).excerpt)
    }

    @Test
    fun `换了一版正文之后高亮不画，清单里说需确认`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 3) + 2
        runBlocking { writer(meta).addHighlight(start, start + 2, facts) }

        // 库里那条链来自另一个版本（指纹不同 ⇒ 规则 1 直接判孤儿）。
        val otherVersion = object : LayoutFacts by facts {
            override val fingerprint: String get() = "fp-另一个版本"
        }

        assertTrue(
            "解不开就不画：高亮没有第二条轴，画到猜出来的行上会让用户以为它跑掉了",
            highlightRanges(highlightsOf(meta), otherVersion).isEmpty(),
        )
        val row = rowOf(meta, otherVersion)
        assertEquals(InkState.ORPHANED, row.state)
        assertFalse("说不清位置 → 界面给的是「确认位置」", row.located)
        assertTrue("内容没丢，丢的是它与正文的联系", row.originalText != null)
    }

    @Test
    fun `高亮的样式改动能存回来`() {
        // 高亮是全工程唯一带 `HighlightStyle` 的标注（渲染模式、吸附阈值、外扩比例）。
        // 这一条测的是"改一处样式不会在读回时被打回默认值"—— 样式漂了不会报错，
        // 只会在某次打开文档时换一种画法。
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 0) + 2
        runBlocking { writer(meta).addHighlight(start, start + 2, facts) }

        val style = HighlightStyle(
            renderMode = HighlightRenderMode.UNDER_TEXT,
            snapCoverageThreshold = 0.5f,
            bleed = 0.2f,
        )
        runBlocking {
            container.store.annotations.upsert(highlightsOf(meta).single().copy(style = style))
        }

        assertEquals(style, highlightsOf(meta).single().style)
    }
}
