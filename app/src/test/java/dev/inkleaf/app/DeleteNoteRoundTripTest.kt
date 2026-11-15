package dev.inkleaf.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.ReaderSearch
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.ink.highlightRanges
import dev.inkleaf.core.ink.typedNoteRows
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain
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
 * 删除一条打字批注的整链路测试：**删 → 落库 → 重新解析 → 清单 / 搜索 / 正文底色 → 撤销**。
 *
 * 删除本身没有几何，所以这条链路上唯一会悄悄走样的是"删完之后，各处还是不是
 * 从库里读"：面板的行、搜索的命中、正文里的底色全都是**派生**出来的，
 * 只要有一处偷偷留了一份内存副本，症状就是"清单里没了，正文里那片黄还在"——
 * 而删除自己的单元测试照样是绿的。
 *
 * 第二条要钉死的是**撤销的边界**：
 *
 * - 撤销只对"刚到手的这一条"有效，重复撤销必须如实说"没有可撤销的"，不能谎报；
 * - 手写笔迹不走这个出口：一笔跨行时是**几段标注**，按段删等于只擦掉一笔的一部分，
 *   而笔迹的撤销救不回墨迹（删除会硬删笔迹行）—— 那是"整笔擦除"，还没做。
 *
 * 本机没有设备也没有模拟器（无虚拟化），所以这里跑不了"在面板里点一下删除"，
 * 但"点下去之后会发生什么"必须测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeleteNoteRoundTripTest {

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

    private fun importBody(): DocumentMeta = runBlocking {
        val source = File(container.context.cacheDir, "删除导入源.txt")
        source.writeText(body, Charsets.UTF_8)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(source),
            fallbackTitle = "删除测试.txt",
        )
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun sessionOf(meta: DocumentMeta): DocumentSession = runBlocking {
        val bytes = container.store.documents.readBytes(meta)
        DocumentCodec.sessionFromBytes(meta.id, meta.kind, bytes, meta.encoding).session
    }

    private fun factsOf(session: DocumentSession): LayoutFacts =
        SessionLayoutFacts(session, session.layout(metrics, theme), docRatio = 0f)

    /** 一行正文在归一化全文里的起点（界面传给写入出口的正是这个值）。 */
    private fun offsetOfLine(facts: LayoutFacts, index: Int): Int =
        facts.absoluteRangeOf(facts.lines[index])?.first
            ?: throw AssertionError("第 $index 行取不到绝对区间，测试前提不成立")

    /** id 递增：一个测试里写两条笔记时，固定 id 会让第二条 upsert 掉第一条。 */
    private fun writer(meta: DocumentMeta): NoteWriter {
        var noteSeq = 0
        var highlightSeq = 0
        return NoteWriter(
            container = container,
            docId = meta.id,
            idFactory = { "note-${++noteSeq}" },
            highlightIdFactory = { "hl-${++highlightSeq}" },
            now = { 1_234L },
        )
    }

    private fun notesOf(meta: DocumentMeta): List<Annotation> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations
    }

    private fun highlightsOf(meta: DocumentMeta): List<Annotation.Highlight> =
        notesOf(meta).filterIsInstance<Annotation.Highlight>()

    @Test
    fun `删掉一条边注：清单、搜索和库里都不再有它，另一条不受影响`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val writer = writer(meta)
        runBlocking {
            writer.addMarginNote("记一句悄悄话", offsetOfLine(facts, 1), facts)
            writer.addMarginNote("另一条边注", offsetOfLine(facts, 4), facts)
        }
        val notes = notesOf(meta)
        assertEquals(2, notes.size)
        val target = notes.first { (it as Annotation.MarginNote).noteText == "记一句悄悄话" }

        val searchBefore = ReaderSearch.search(facts.normalizedText, typedNoteRows(notes, facts), "悄悄话")
        assertTrue(
            "删之前必须搜得到我自己写的批注（否则这条测试证明不了什么）",
            searchBefore.hits.any { it is ReaderSearch.NoteHit },
        )

        val outcome = runBlocking { writer.deleteTypedNote(target.id) }

        assertTrue("删除必须真的改了库，实际=${outcome.message}", outcome.changed)
        assertTrue(
            "结果提示必须告诉用户怎么撤销 —— 界面上没有确认对话框，这句话是唯一的安全网，实际=${outcome.message}",
            outcome.message.contains("撤销"),
        )
        assertTrue("库里不该再有这一条", notesOf(meta).none { it.id == target.id })
        assertEquals("另一条边注不受影响", 1, notesOf(meta).size)

        val rowsAfter = typedNoteRows(notesOf(meta), facts)
        assertTrue("面板里也不该再有它", rowsAfter.none { it.annotationId == target.id })
        val searchAfter = ReaderSearch.search(facts.normalizedText, rowsAfter, "悄悄话")
        assertTrue(
            "删掉之后搜索不该再返回它（正文里没有这几个字，剩不下任何命中）",
            searchAfter.isEmpty,
        )
    }

    @Test
    fun `撤销删除：边注原样回来，还在原来那一行上`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val writer = writer(meta)
        runBlocking { writer.addMarginNote("记一句悄悄话", offsetOfLine(facts, 2), facts) }
        val before = notesOf(meta).single()
        val rowBefore = typedNoteRows(listOf(before), facts).single()

        runBlocking { writer.deleteTypedNote(before.id) }
        val undone = runBlocking { writer.restoreTypedNote(before.id) }

        assertTrue("撤销必须真的把那条放回来，实际=${undone.message}", undone.changed)
        val back = notesOf(meta).single()
        assertEquals("回来的必须是原来那一条，不是一条新记录", before.id, back.id)
        assertEquals(
            "用户写的那句话必须原样还在",
            (before as Annotation.MarginNote).noteText,
            (back as Annotation.MarginNote).noteText,
        )
        assertEquals("软删除没动锚点，撤销也就不该动它", before.anchor, back.anchor)

        val rowAfter = typedNoteRows(notesOf(meta), facts).single()
        assertEquals("位置也必须回到原来那一行", rowBefore.charOffset, rowAfter.charOffset)
        assertEquals(rowBefore.excerpt, rowAfter.excerpt)

        val second = runBlocking { writer.restoreTypedNote(before.id) }
        assertFalse("重复撤销不该谎报成功", second.changed)
        assertTrue(
            "拒绝也要说清现在是什么状态，实际=${second.message}",
            second.message.contains("没有可撤销"),
        )
    }

    @Test
    fun `删掉一条高亮：正文里不再有底色，库里也不再返回它`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 1) + 2
        val writer = writer(meta)
        runBlocking { writer.addHighlight(start, start + 2, facts) }
        assertEquals("前提：库里有一条能被解析出来的高亮", 1, highlightRanges(highlightsOf(meta), facts).size)

        val outcome = runBlocking { writer.deleteTypedNote(highlightsOf(meta).single().id) }

        assertTrue("删除必须真的改了库，实际=${outcome.message}", outcome.changed)
        assertTrue("提示里要念对类型，实际=${outcome.message}", outcome.message.contains("高亮"))
        assertTrue(
            "底色是从库里算出来的：库里没有了，正文里就该什么都没了",
            highlightRanges(highlightsOf(meta), facts).isEmpty(),
        )
        assertTrue("面板里也不该再有它", typedNoteRows(notesOf(meta), facts).isEmpty())
    }

    @Test
    fun `删掉之后同一处可以重新标：去重只挡重复，不挡重标`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val start = offsetOfLine(facts, 3) + 2
        val writer = writer(meta)
        runBlocking { writer.addHighlight(start, start + 2, facts) }
        val first = highlightsOf(meta).single()

        runBlocking { writer.deleteTypedNote(first.id) }
        val again = runBlocking { writer.addHighlight(start, start + 2, facts) }

        // 这两件事必须一起成立：删掉之后能重标（否则那条"已经是高亮了"就是死路），
        // 但重标出来的是一条**新**记录（不要把已删的旧行悄悄复活）。
        assertTrue("删掉之后同一处必须能再标，实际=${again.message}", again.added)
        assertEquals(1, highlightsOf(meta).size)
        assertNotEquals("重标的是新记录", first.id, highlightsOf(meta).single().id)
    }

    @Test
    fun `笔迹不走这个出口：拒绝并指向橡皮`() {
        val meta = importBody()
        val ink = inkAnnotation(meta.id)
        runBlocking {
            container.store.annotations.saveInk(
                ink,
                listOf(stroke("s-1")),
                listOf(BrushSpec.DEFAULT_PEN),
            )
        }

        val outcome = runBlocking { writer(meta).deleteTypedNote(ink.id) }

        assertFalse("一笔跨行时是几段标注，按段删是另一件事（整笔擦除）", outcome.changed)
        assertTrue(
            "拒绝要说清该用哪个入口，实际=${outcome.message}",
            outcome.message.contains("橡皮"),
        )
        assertEquals("笔迹必须原样还在", 1, notesOf(meta).size)
    }

    @Test
    fun `撤销一条本来就没删的笔记不会谎报成功`() {
        val meta = importBody()
        val facts = factsOf(sessionOf(meta))
        val writer = writer(meta)
        runBlocking { writer.addMarginNote("一条还没删的边注", offsetOfLine(facts, 0), facts) }
        val live = notesOf(meta).single()

        val outcome = runBlocking { writer.restoreTypedNote(live.id) }

        assertFalse("没有删除可撤销时必须如实说", outcome.changed)
        assertTrue(
            "实际=${outcome.message}",
            outcome.message.contains("没有可撤销"),
        )
        assertEquals("空操作不该动库里任何东西", 1, notesOf(meta).size)
    }

    private fun inkAnnotation(docId: String) = Annotation.Ink(
        id = "ink-1",
        docId = docId,
        createdAt = 1L,
        colorArgb = 0xFF000000.toInt(),
        anchor = TextAnchorChain(
            selectors = listOf(
                Selector.Structural("blk[0]", 0, 4, 1.0f),
                Selector.ProgressHint(0.0f, null, "第 1 行正文"),
            ),
            normalization = NormalizationProfile(),
            sourceFingerprint = "0123456789abcdef",
        ),
        semantics = InkReflowSemantics.FOLLOWER,
        strokeIds = listOf("s-1"),
    )

    private fun stroke(id: String) = StrokeAnchor(
        strokeId = id,
        anchor = Selector.Structural("blk[0]", 0, 4, 1.0f),
        semantics = InkReflowSemantics.FOLLOWER,
        brushId = BrushSpec.DEFAULT_PEN.name,
        localPoints = floatArrayOf(0f, 0f, 1f, 1f),
        localPressures = floatArrayOf(0.5f, 0.5f),
    )
}
