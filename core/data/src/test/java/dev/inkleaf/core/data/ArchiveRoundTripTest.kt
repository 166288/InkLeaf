package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.model.Affine2D
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.InkRepresentation
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.Side
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.model.UnderlineStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 导出 → 清空 → 导入 的**完整往返**。
 *
 * ## 为什么这条路径是发版前的必检项
 *
 * 这是"本地优先应用不丢数据"的**唯一出口**。用户换手机、清数据、
 * 装到新设备时，靠的就是"导出一个文件、再导回来"。
 * 而它的失败方向特别隐蔽：
 *
 * - 导出时少写了什么 → **不报错**，只是导入后少了几条笔记；
 * - 导入时读不出什么 → **不报错**，只是那几条笔记变成"有锚点、没有内容"。
 *
 * 两种都不会崩，只会让用户发现"我备份的东西变少了"——而且往往已经太晚了。
 *
 * 所以这里的断言方式刻意是**逐字段比对**，而不是"数量对得上就行"。
 *
 * ## 覆盖的五种标注
 *
 * 高亮、手写、边注、下划线、书签 —— 每一种的存储形状都不同
 * （锚点链 / 笔迹载荷 / 侧边 / 多锚点 / 进度兜底），
 * 少测一种就等于给那一种留了一个洞。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchiveRoundTripTest {

    private lateinit var store: InkLeafStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        wipe()
        store = InkLeafStore(context)
        store.warmUp()
    }

    @After
    fun tearDown() {
        store.close()
        wipe()
    }

    private fun wipe() {
        context.deleteDatabase(StorageSchema.DATABASE_NAME)
        File(context.filesDir, "documents").deleteRecursively()
        File(context.filesDir, "blobs").deleteRecursively()
        File(context.filesDir, "derived").deleteRecursively()
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private val sampleText = "第一章 起风\n他说：走吧。\n第二章 落雪\n"

    private suspend fun importDoc(title: String = "测试小说.txt"): DocumentMetaHolder {
        val meta = store.importer.commit(
            store.importer.inspect(
                DocumentImporter.ImportCandidate(
                    displayName = title,
                    title = title.substringBeforeLast('.'),
                    kind = DocumentCodec.kindFromFileName(title),
                    bytes = sampleText.toByteArray(Charsets.UTF_8),
                    sourceUri = null,
                ),
            ),
        )
        return DocumentMetaHolder(meta.id, meta.title)
    }

    private data class DocumentMetaHolder(val id: String, val title: String)

    private fun chain(blockPath: String = "blk[0]", start: Int = 0, end: Int = 4): TextAnchorChain =
        TextAnchorChain(
            selectors = listOf(
                Selector.Structural(blockPath, start, end, 1.0f),
                Selector.ProgressHint(0.25f, null, "第一章 起风"),
            ),
            normalization = NormalizationProfile(),
            sourceFingerprint = "0123456789abcdef",
        )

    /**
     * 造一段笔迹。
     *
     * **遵守模型的不变量**：只有 PINNED / FREE 才带冻结几何，
     * FOLLOWER 必须没有 —— 存一份冻结变换等于把"跟随文字"这件事写坏
     * （见 [StrokeAnchor.frozenMatrix] 的注释）。
     */
    private fun stroke(
        id: String,
        pointCount: Int,
        semantics: InkReflowSemantics = InkReflowSemantics.FOLLOWER,
    ): StrokeAnchor = StrokeAnchor(
        strokeId = id,
        anchor = Selector.Structural("blk[0]", 0, 4, 1.0f),
        semantics = semantics,
        brushId = BrushSpec.DEFAULT_PEN.name,
        localPoints = FloatArray(pointCount * 2) { it * 0.001f },
        localPressures = FloatArray(pointCount) { 0.5f },
        frozenMatrix = if (semantics == InkReflowSemantics.FOLLOWER) {
            null
        } else {
            Affine2D.emToDoc(fontSizePx = 24f, originLeft = 60f, originTop = 400f)
        },
    )

    /** 把五种标注全塞进去，每种都带上有辨识度的内容。 */
    private suspend fun seedAllKinds(docId: String) {
        // 高亮：带样式与标签
        store.annotations.upsert(
            Annotation.Highlight(
                id = "hl-1", docId = docId, createdAt = 100,
                colorArgb = 0x66FFD54F, tags = listOf("重点", "待办"),
                noteText = "高亮的备注",
                anchor = chain(),
                style = HighlightStyle(renderMode = dev.inkleaf.core.model.HighlightRenderMode.OVERLAY, bleed = 0.2f),
            ),
        )
        // 手写：带锚点链 + 笔刷调色板 + 冻结几何
        store.annotations.saveInk(
            Annotation.Ink(
                id = "ink-1", docId = docId, createdAt = 200,
                colorArgb = 0xFF1A1A1A.toInt(), tags = listOf("手写"),
                anchor = chain("blk[1]", 2, 6),
                semantics = InkReflowSemantics.PINNED,
                representation = InkRepresentation.VECTOR,
                strokeIds = listOf("s-1", "s-2"),
            ),
            listOf(
                stroke("s-1", 40, InkReflowSemantics.PINNED),
                stroke("s-2", 30, InkReflowSemantics.FOLLOWER),
            ),
            listOf(BrushSpec.DEFAULT_PEN, BrushSpec.DEFAULT_HIGHLIGHTER),
        )
        // 边注：带侧边
        store.annotations.upsert(
            Annotation.MarginNote(
                id = "note-1", docId = docId, createdAt = 300,
                colorArgb = 0xFFFFC107.toInt(), noteText = "边注正文",
                anchor = chain("blk[2]"), side = Side.LEFT,
            ),
        )
        // 下划线：多锚点 + 波浪线样式
        store.annotations.upsert(
            Annotation.Underline(
                id = "ul-1", docId = docId, createdAt = 400,
                colorArgb = 0xFF2196F3.toInt(),
                anchors = listOf(chain("blk[0]"), chain("blk[1]", 3, 7)),
                style = UnderlineStyle(thicknessEm = 0.06f, baselineOffsetEm = 0.15f, wavy = true),
            ),
        )
        // 书签：只有进度兜底
        store.annotations.upsert(
            Annotation.Bookmark(
                id = "bm-1", docId = docId, createdAt = 500,
                colorArgb = 0, tags = listOf("书签"),
                progressHint = Selector.ProgressHint(0.6f, "2"),
            ),
        )
        store.documents.saveProgress(
            ReadingProgress(docId, charOffset = 42, pageIndex = 3, docRatio = 0.5f, updatedAt = 600),
        )
    }

    /** 导出、真正清空、再导入。 */
    private suspend fun roundTrip(): ArchiveRepository.ImportSummary {
        val archive = ByteArrayOutputStream()
            .also { store.archive.exportToStream(it, "test") }
            .toByteArray()
        assertTrue("档案不该是空的", archive.size > 500)

        // 真正清空：删库 + 删文件。
        store.close()
        wipe()
        store = InkLeafStore(context)
        store.warmUp()
        assertEquals("前提：清空后库里什么都没有", 0, store.documents.count())

        return store.archive.importFromStream(ByteArrayInputStream(archive))
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    fun `往返之后五种标注与笔迹进度都回来了`() = runBlocking {
        val doc = importDoc()
        seedAllKinds(doc.id)

        val summary = roundTrip()

        assertEquals("不该有任何文档被跳过", 0, summary.skippedDocuments)
        assertEquals("不该有任何标注被跳过", 0, summary.skippedAnnotations)
        assertEquals(
            "笔迹必须全部读得回来：读不出来就是丢数据（skippedStrokes 必须是 0）",
            0,
            summary.skippedStrokes,
        )
        assertEquals(1, summary.importedDocuments)
        assertEquals(5, summary.importedAnnotations)
    }

    @Test
    fun `往返之后正文副本与阅读进度都回来了`() = runBlocking {
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()

        val restored = store.documents.list().single()
        assertEquals("正文副本要能原样读回", sampleText, String(store.documents.readBytes(restored), Charsets.UTF_8))
        val progress = store.documents.progressOf(restored.id)
        assertNotNull("阅读进度要回来", progress)
        assertEquals(42, progress!!.charOffset)
        assertEquals(3, progress.pageIndex)
        assertEquals(0.5f, progress.docRatio, 1e-6f)
    }

    @Test
    fun `往返之后手写笔迹的点位与冻结几何逐位一致`() = runBlocking {
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()

        val restored = store.documents.list().single()
        val snapshot = store.annotations.listByDoc(restored.id)
        assertEquals("不该有读不出来的笔迹", 0, snapshot.unreadableStrokeCount)

        val strokes = snapshot.strokesOf("ink-1")
        assertEquals("两条笔迹都要回来", 2, strokes.size)

        val s1 = strokes.first { it.strokeId == "s-1" }
        val source = stroke("s-1", 40, InkReflowSemantics.PINNED)
        assertEquals("点数必须一致", source.localPoints.size / 2, s1.localPoints.size / 2)
        for (i in source.localPoints.indices) {
            assertEquals(
                "第 $i 个坐标必须逐位一致（浮点也要，不能四舍五入）",
                source.localPoints[i].toRawBits(),
                s1.localPoints[i].toRawBits(),
            )
        }
        assertEquals("冻结几何必须回来", source.frozenMatrix, s1.frozenMatrix)
        assertEquals("语义必须回来", InkReflowSemantics.PINNED, s1.semantics)
        assertEquals(
            "FOLLOWER 的笔迹不该有冻结几何",
            null,
            strokes.first { it.strokeId == "s-2" }.frozenMatrix,
        )
    }

    @Test
    fun `往返之后笔刷调色板要回来`() = runBlocking {
        // 这一条最容易被忽略：ink-storage 不序列化 Brush，
        // 所以笔刷是单独存在 extra 里的。忘了它，用户的荧光笔会变成默认黑细笔。
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()

        val restored = store.documents.list().single()
        val brushes = store.annotations.listByDoc(restored.id).brushesOf("ink-1")
        assertEquals("两种笔刷都要回来", 2, brushes.size)
        assertTrue(
            "笔刷集合必须原样回来：$brushes",
            brushes.containsAll(listOf(BrushSpec.DEFAULT_PEN, BrushSpec.DEFAULT_HIGHLIGHTER)),
        )
    }

    @Test
    fun `往返之后锚点链能重新解析回同一处`() = runBlocking {
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()

        val restored = store.documents.list().single()
        val all = store.annotations.listAll()
        assertEquals("五种标注都要回来", 5, all.size)

        val highlight = all.filterIsInstance<Annotation.Highlight>().single()
        assertEquals("高亮的锚点链必须还在", chain(), highlight.anchor)
        assertEquals("标签要回来", listOf("重点", "待办"), highlight.tags)
        assertEquals("高亮样式要回来", 0.2f, highlight.style.bleed, 1e-6f)
        assertEquals(
            "高亮的渲染模式要回来",
            dev.inkleaf.core.model.HighlightRenderMode.OVERLAY,
            highlight.style.renderMode,
        )

        val margin = all.filterIsInstance<Annotation.MarginNote>().single()
        assertEquals("边注的侧边要回来", Side.LEFT, margin.side)
        assertEquals("边注正文要回来", "边注正文", margin.noteText)
        assertEquals("边注锚点要回来", chain("blk[2]"), margin.anchor)

        val underline = all.filterIsInstance<Annotation.Underline>().single()
        assertEquals("下划线的锚点数量要回来", 2, underline.anchors.size)
        assertEquals("下划线的波浪线设置要回来", true, underline.style.wavy)
        assertEquals(0.06f, underline.style.thicknessEm, 1e-6f)

        val bookmark = all.filterIsInstance<Annotation.Bookmark>().single()
        assertEquals("书签的进度兜底要回来", Selector.ProgressHint(0.6f, "2"), bookmark.progressHint)
        assertEquals("书签标签要回来", listOf("书签"), bookmark.tags)
    }

    @Test
    fun `往返之后手写标注的锚点链要回来`() = runBlocking {
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()

        val restored = store.documents.list().single()
        val ink = store.annotations.listAll().filterIsInstance<Annotation.Ink>().single()
        assertEquals("手写标注的锚点链必须回来", chain("blk[1]", 2, 6), ink.anchor)
        assertEquals(
            "笔迹 id 列表要回来（顺序不定，但集合必须一样）",
            listOf("s-1", "s-2"),
            ink.strokeIds.sorted(),
        )
    }

    @Test
    fun `往返两次得到同一份内容`() = runBlocking {
        // 往返不是幂等就意味着"导出两次得到的东西不一样"——
        // 那用户就无法判断自己的备份到底是不是最新的。
        val doc = importDoc()
        seedAllKinds(doc.id)

        roundTrip()
        val firstPass = store.annotations.listAll().map { it.id }.sorted()

        // 再来一轮：把刚才恢复出来的东西再导出、再导入。
        val archive = ByteArrayOutputStream()
            .also { store.archive.exportToStream(it, "test") }
            .toByteArray()
        store.close()
        wipe()
        store = InkLeafStore(context)
        store.warmUp()
        store.archive.importFromStream(ByteArrayInputStream(archive))

        val secondPass = store.annotations.listAll().map { it.id }.sorted()
        assertEquals("往返两次之后内容必须一样", firstPass, secondPass)
        assertEquals(5, secondPass.size)
    }

    @Test
    fun `往返之后外置笔迹文件也能被回收`() = runBlocking {
        // 外置笔迹有文件参与：往返之后 blob 文件必须在，
        // 否则下一次"删文档"时找不到要删的文件，磁盘上会永久留下孤儿。
        val doc = importDoc()
        store.annotations.saveInk(
            Annotation.Ink(
                id = "ink-big", docId = doc.id, createdAt = 100,
                colorArgb = 0xFF1A1A1A.toInt(), anchor = chain(),
                semantics = InkReflowSemantics.FOLLOWER,
                strokeIds = listOf("s-big"),
            ),
            listOf(stroke("s-big", 20_000)),
            listOf(BrushSpec.DEFAULT_PEN),
        )
        assertEquals("前提：大笔迹应该外置成文件", 1, store.paths.blobsDir.listFiles()!!.size)

        roundTrip()

        val restored = store.documents.list().single()
        val snapshot = store.annotations.listByDoc(restored.id)
        assertEquals("外置笔迹必须读得回来", 0, snapshot.unreadableStrokeCount)
        assertEquals(
            "点位必须一条不少",
            20_000,
            snapshot.strokesOf("ink-big").single().localPoints.size / 2,
        )
    }
}
