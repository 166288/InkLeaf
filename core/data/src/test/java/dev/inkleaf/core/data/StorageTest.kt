package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.model.Affine2D
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.InkRepresentation
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.document.DocumentCodec
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 存储层的**真实实现**回归测试。
 *
 * ## 为什么是 Robolectric 而不是 connectedAndroidTest
 *
 * 这些断言针对的是持久层的 Android 一侧，而那一侧几乎全是"真实性"问题：
 * `SQLiteOpenHelper` 的外键是否真的开启（SQLite 默认**不执行**外键，
 * 忘了 `setForeignKeyConstraintsEnabled(true)` 会让"删文档顺带删笔记"
 * 静默失效）、BLOB 列的绑定与读取、`ON CONFLICT DO UPDATE` 的写法、
 * 事务回滚的边界。Robolectric 用的是**真实 SQLite**，这些都在覆盖范围内。
 *
 * 本机没有虚拟化（`HypervisorPresent=False`，AEHD 未安装，开 WHPX 需要
 * 管理员权限加重启），也就是说 `connectedAndroidTest` 在这台机器上
 * 永远跑不起来，而跑不起来的测试会烂掉 —— 一个从没执行过的测试不是资产。
 *
 * 要搬回设备端：把 `@RunWith(RobolectricTestRunner::class)` / `@Config`
 * 换成 `@RunWith(AndroidJUnit4::class)` 即可，其余断言一行都不用改
 * （取 Context 用的是 `ApplicationProvider`，两边通用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageTest {

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

    // ── 导入 ────────────────────────────────────────────────────────────────

    private val sampleText = "第一章 起风\n他说：走吧。\n第二章 落雪"

    /**
     * 直接构造候选文档，而不是走 `readFromUri`。
     *
     * 理由：`readFromUri` 是 5 行 `resolver.openInputStream(uri).readBytes()`，
     * 而 Robolectric 对 `file://` 之外的 `ContentResolver` 行为有自己的实现细节。
     * 把"编码 → 归一化 → 切块 → 落库"这条真正有逻辑的链路测实，
     * 比为了让一次 I/O 调用在测试环境里通而绕远路更有价值。
     */
    private suspend fun importSample(
        title: String = "测试小说.txt",
        text: String = sampleText,
    ) = store.importer.commit(
        store.importer.inspect(
            DocumentImporter.ImportCandidate(
                displayName = title,
                title = title.substringBeforeLast('.'),
                kind = DocumentCodec.kindFromFileName(title),
                bytes = text.toByteArray(Charsets.UTF_8),
                sourceUri = null,
            ),
        ),
    )

    @Test
    fun `导入后文档出现在书架且正文副本可读回`() = runBlocking {
        val meta = importSample()
        assertEquals(DocumentKind.TXT, meta.kind)
        assertEquals("测试小说", meta.title)
        assertEquals(3, meta.blockCount)
        assertEquals(16, meta.sourceFingerprint.length)
        assertEquals("第一章 起风".length + 1 + "他说：走吧。".length + 1 + "第二章 落雪".length, meta.charCount)

        val listed = store.documents.list()
        assertEquals(1, listed.size)
        assertEquals(meta.sourceFingerprint, listed[0].sourceFingerprint)

        // 原样字节存副本：将来换编码重新解码不需要用户重新导入。
        assertEquals(sampleText, String(store.documents.readBytes(listed[0]), Charsets.UTF_8))
    }

    @Test
    fun `同样的正文重复导入得到同一个指纹但仍是两篇文档`() = runBlocking {
        val a = importSample("甲.txt")
        val b = importSample("乙.txt")
        assertEquals("指纹只取决于内容与口径", a.sourceFingerprint, b.sourceFingerprint)
        assertTrue("但它们是两篇不同的文档", a.id != b.id)
        assertEquals(2, store.documents.count())
    }

    @Test
    fun `GB18030 的正文能被探测并按原样字节保存`() = runBlocking {
        // 国区 Windows 上大量 TXT 是 GBK/GB18030；探测不出来就等于不支持 TXT。
        val gbk = "第一章 起风".toByteArray(charset("GB18030"))
        val meta = store.importer.commit(
            store.importer.inspect(
                DocumentImporter.ImportCandidate(
                    displayName = "gbk.txt",
                    title = "gbk",
                    kind = DocumentKind.TXT,
                    bytes = gbk,
                    sourceUri = null,
                ),
            ),
        )
        assertEquals("GB18030", meta.encoding)
        assertEquals("第一章 起风", DocumentCodec.decode(store.documents.readBytes(meta), "GB18030").text)
        // 换一个编码就能重新解码，不需要重新导入。
        assertFalse(DocumentCodec.decode(store.documents.readBytes(meta), "UTF-8").text == "第一章 起风")
    }

    @Test
    fun `只有空白的文档会被明确拒绝而不是导入一篇空白`() {
        var message: String? = null
        try {
            store.importer.inspect(
                DocumentImporter.ImportCandidate(
                    displayName = "空.txt",
                    title = "空",
                    kind = DocumentKind.TXT,
                    bytes = "  \n\n\t\n".toByteArray(),
                    sourceUri = null,
                ),
            )
        } catch (e: DocumentImporter.ImportException) {
            message = e.message
        }
        assertNotNull("空文档必须被拒绝", message)
        assertEquals(0, runBlocking { store.documents.count() })
    }

    // ── 笔迹 ────────────────────────────────────────────────────────────────

    private fun chain() = TextAnchorChain(
        selectors = listOf(
            Selector.Structural("blk[0]", 0, 4, 1.0f),
            Selector.ProgressHint(0.0f, null, "第一章 起风"),
        ),
        normalization = NormalizationProfile(),
        sourceFingerprint = "0123456789abcdef",
    )

    private fun bigStroke(id: String, pointCount: Int) = StrokeAnchor(
        strokeId = id,
        anchor = Selector.Structural("blk[0]", 0, 4, 1.0f),
        semantics = InkReflowSemantics.FOLLOWER,
        brushId = BrushSpec.DEFAULT_PEN.name,
        localPoints = FloatArray(pointCount * 2) { it * 0.001f },
        localPressures = FloatArray(pointCount) { 0.5f },
    )

    private fun smallStroke(id: String) = StrokeAnchor(
        strokeId = id,
        anchor = Selector.Structural("blk[1]", 1, 3, 1.0f),
        semantics = InkReflowSemantics.FOLLOWER,
        brushId = BrushSpec.DEFAULT_HIGHLIGHTER.name,
        localPoints = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
        localPressures = floatArrayOf(0.9f, 0.3f),
    )

    private fun inkAnnotation(docId: String, strokeIds: List<String>) = Annotation.Ink(
        id = "ink-$docId",
        docId = docId,
        createdAt = 1000L,
        colorArgb = 0xFF1A1A1A.toInt(),
        anchor = chain(),
        semantics = InkReflowSemantics.FOLLOWER,
        representation = InkRepresentation.VECTOR,
        strokeIds = strokeIds,
    )

    @Test
    fun `小笔迹内联存储且不产生文件`() = runBlocking {
        val meta = importSample()
        val annotation = inkAnnotation(meta.id, listOf("s-small"))
        store.annotations.saveInk(
            annotation, listOf(smallStroke("s-small")), listOf(BrushSpec.DEFAULT_HIGHLIGHTER),
        )

        val snapshot = store.annotations.listByDoc(meta.id)
        assertEquals(0, snapshot.unreadableAnnotationCount)
        assertEquals(0, snapshot.unreadableStrokeCount)
        val strokes = snapshot.strokesOf(annotation.id)
        assertEquals(1, strokes.size)
        assertEquals(listOf(0.9f, 0.3f), strokes[0].localPressures.toList())
        assertEquals(2, strokes[0].localPoints.size / 2)
        // 笔刷必须自己存（ink-storage 不序列化 Brush），否则历史笔记会以默认黑色细笔重绘。
        assertEquals(listOf(BrushSpec.DEFAULT_HIGHLIGHTER), snapshot.brushesOf(annotation.id))
        assertEquals(0, store.paths.blobsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `大笔迹外置成文件且能逐位原样读回`() = runBlocking {
        val meta = importSample()
        // 20_000 个点 → (8 + 4) × 20_000 = 240 KB，超过 64 KB 的内联上限。
        val pointCount = 20_000
        val source = bigStroke("s-big", pointCount)
        val annotation = inkAnnotation(meta.id, listOf("s-big"))
        store.annotations.saveInk(annotation, listOf(source), listOf(BrushSpec.DEFAULT_PEN))

        val blobFiles = store.paths.blobsDir.listFiles()
        assertNotNull(blobFiles)
        assertEquals("超过阈值的笔迹必须外置", 1, blobFiles!!.size)

        val restored = store.annotations.listByDoc(meta.id).strokesOf(annotation.id)
        assertEquals(1, restored.size)
        assertEquals(pointCount, restored[0].localPoints.size / 2)
        for (i in 0 until pointCount * 2 step 397) {
            assertEquals(
                "第 $i 个坐标必须逐位一致",
                source.localPoints[i].toRawBits(),
                restored[0].localPoints[i].toRawBits(),
            )
        }
    }

    @Test
    fun `冻结几何能存能取_外置与内联都算`() = runBlocking {
        // 冻结几何决定"边注钉在页面的哪个位置"。它在编码器里是一条可选字段，
        // 一旦漏了一个分支（内联 / 外置），症状是"重开文档边注全跑到左上角"，
        // 而单元测试里内存对象照样是对的 —— 所以两条路径都要走一遍。
        val meta = importSample()
        val frozen = Affine2D.emToDoc(fontSizePx = 24f, originLeft = 60f, originTop = 400f)
        val small = smallStroke("s-small").copy(frozenMatrix = frozen)
        val big = bigStroke("s-big", 20_000).copy(frozenMatrix = frozen)
        val annotation = inkAnnotation(meta.id, listOf("s-small", "s-big"))

        store.annotations.saveInk(annotation, listOf(small, big), listOf(BrushSpec.DEFAULT_PEN))

        val restored = store.annotations.listByDoc(meta.id).strokesOf(annotation.id)
        assertEquals(2, restored.size)
        assertEquals("内联那一条", frozen, restored.single { it.strokeId == "s-small" }.frozenMatrix)
        assertEquals("外置那一条", frozen, restored.single { it.strokeId == "s-big" }.frozenMatrix)
    }

    @Test
    fun `重写笔迹时旧的外置文件被回收`() = runBlocking {
        val meta = importSample()
        val annotation = inkAnnotation(meta.id, listOf("s-big"))
        store.annotations.saveInk(annotation, listOf(bigStroke("s-big", 20_000)), listOf(BrushSpec.DEFAULT_PEN))
        assertEquals(1, store.paths.blobsDir.listFiles()!!.size)

        // 用同 id 的小笔迹覆盖：外置文件应当被删掉，而不是永远悬空。
        store.annotations.saveInk(annotation, listOf(smallStroke("s-small")), listOf(BrushSpec.DEFAULT_PEN))
        assertEquals(0, store.paths.blobsDir.listFiles()?.size ?: 0)
        assertEquals(1, store.annotations.listByDoc(meta.id).strokesOf(annotation.id).size)
    }

    @Test
    fun `外置文件被外部删除时这条笔迹判为不可读而不是编造坐标`() = runBlocking {
        val meta = importSample()
        val annotation = inkAnnotation(meta.id, listOf("s-big"))
        store.annotations.saveInk(annotation, listOf(bigStroke("s-big", 20_000)), listOf(BrushSpec.DEFAULT_PEN))
        store.paths.blobsDir.listFiles()!!.forEach { it.delete() }

        val snapshot = store.annotations.listByDoc(meta.id)
        assertEquals("笔迹读不出来必须被计数", 1, snapshot.unreadableStrokeCount)
        assertTrue(snapshot.strokesOf(annotation.id).isEmpty())
        // 但标注本身仍然存在：用户要能看到"这里有一条笔记，只是读不出来"。
        assertEquals(1, snapshot.annotations.size)
    }

    // ── 级联删除 ────────────────────────────────────────────────────────────

    @Test
    fun `删除文档会连带删除笔记笔迹与外置文件`() = runBlocking {
        val meta = importSample()
        store.annotations.saveInk(
            inkAnnotation(meta.id, listOf("s-big")),
            listOf(bigStroke("s-big", 20_000)),
            listOf(BrushSpec.DEFAULT_PEN),
        )

        store.documents.delete(meta.id)

        assertEquals(0, store.documents.count())
        assertTrue("笔记必须随文档级联删除", store.annotations.listByDoc(meta.id).isEmpty)
        assertTrue("正文副本必须被删除", store.paths.documentsDir.listFiles().isNullOrEmpty())
        assertTrue("外置笔迹文件必须被删除", store.paths.blobsDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `删除笔记会删除它的笔迹但保留文档`() = runBlocking {
        val meta = importSample()
        val annotation = inkAnnotation(meta.id, listOf("s-big"))
        store.annotations.saveInk(annotation, listOf(bigStroke("s-big", 20_000)), listOf(BrushSpec.DEFAULT_PEN))

        store.annotations.delete(annotation.id)

        assertTrue(store.annotations.listByDoc(meta.id).isEmpty)
        assertTrue("外置文件必须回收", store.paths.blobsDir.listFiles().isNullOrEmpty())
        assertEquals("但文档还在", 1, store.documents.count())
    }

    @Test
    fun `删除文档会连它的封面缓存一起清掉`() = runBlocking {
        val meta = importSample()
        store.derived.coverImage(meta.id, meta.sourceFingerprint).writeBytes(ByteArray(128))
        store.derived.coverMissingMarker(meta.id, meta.sourceFingerprint).writeText("")

        store.documents.delete(meta.id)

        assertFalse(
            "文档没了，它的封面缓存必须一起走 —— 否则 derived/ 只增不减",
            store.derived.coverImage(meta.id, meta.sourceFingerprint).exists(),
        )
        assertFalse(store.derived.coverMissingMarker(meta.id, meta.sourceFingerprint).exists())
    }

    @Test
    fun `孤儿封面缓存会被扫掉活着的留下`() = runBlocking {
        val alive = importSample("甲.txt")
        val gone = importSample("乙.txt")
        store.derived.coverImage(alive.id, alive.sourceFingerprint).writeBytes(ByteArray(64))
        store.derived.coverImage(gone.id, gone.sourceFingerprint).writeBytes(ByteArray(64))

        store.documents.delete(gone.id)
        // 手动造一个"文档还在但缓存没了"之外的情况：删掉文档却留下缓存文件。
        store.derived.coverImage(gone.id, gone.sourceFingerprint).writeBytes(ByteArray(64))

        val swept = store.documents.sweepOrphanDerived()

        assertTrue("孤儿必须被扫掉", swept >= 1)
        assertTrue(
            "还活着的文档的封面不能被扫掉",
            store.derived.coverImage(alive.id, alive.sourceFingerprint).exists(),
        )
        assertFalse(store.derived.coverImage(gone.id, gone.sourceFingerprint).exists())
    }

    @Test
    fun `撤销删除会把标注原样放回来`() = runBlocking {
        val meta = importSample()
        val note = Annotation.MarginNote(
            id = "note-undo",
            docId = meta.id,
            createdAt = 9L,
            colorArgb = 0xFFFFC107.toInt(),
            noteText = "记一句",
            anchor = chain(),
        )
        store.annotations.upsert(note)
        store.annotations.delete(note.id)
        assertTrue("前提：删掉之后读不到", store.annotations.listByDoc(meta.id).isEmpty)

        assertTrue("撤销必须真的把那一行放回去", store.annotations.restore(note.id))

        val back = store.annotations.listByDoc(meta.id).annotations.single() as Annotation.MarginNote
        assertEquals("回来的必须是同一条（id 不变）", "note-undo", back.id)
        assertEquals("内容不能被撤销弄丢", "记一句", back.noteText)
        assertEquals("软删除没动锚点，撤销也就原样回来", note.anchor, back.anchor)
        assertEquals("别的列也一样没被碰过", 9L, back.createdAt)
    }

    @Test
    fun `撤销删除是幂等的：重复撤销与撤销没删过的都不谎报成功`() = runBlocking {
        val meta = importSample()
        val note = Annotation.MarginNote(
            id = "note-live",
            docId = meta.id,
            createdAt = 1L,
            colorArgb = 0,
            noteText = "还在",
            anchor = chain(),
        )
        store.annotations.upsert(note)

        assertFalse("没删过就没有可撤销的", store.annotations.restore(note.id))

        store.annotations.delete(note.id)
        assertTrue(store.annotations.restore(note.id))
        assertFalse("同一次删除只能撤销一次", store.annotations.restore(note.id))
    }

    // ── 进度与设置 ──────────────────────────────────────────────────────────

    @Test
    fun `阅读进度与设置可持久化且默认值已就位`() = runBlocking {
        val meta = importSample()
        store.documents.saveProgress(ReadingProgress(meta.id, charOffset = 12, pageIndex = 3, docRatio = 0.42f, updatedAt = 7L))

        val loaded = store.documents.progressOf(meta.id)
        assertNotNull(loaded)
        assertEquals(12, loaded!!.charOffset)
        assertEquals(3, loaded.pageIndex)
        assertEquals(0.42f, loaded.docRatio, 1e-6f)

        store.settings.putAll(mapOf(SettingKeys.FONT_SIZE_SP to "21.5", SettingKeys.LAYOUT_MODE to "SCROLL"))
        assertEquals(21.5f, store.settings.getFloat(SettingKeys.FONT_SIZE_SP, 1f), 1e-6f)
        assertEquals("SCROLL", store.settings.get(SettingKeys.LAYOUT_MODE))
        // 默认值在首次建表时就写好了：否则"设置界面显示的"与"排版实际用的"会是两回事。
        assertEquals(1.45f, store.settings.getFloat(SettingKeys.LINE_SPACING, 0f), 1e-6f)
    }

    // ── 全库存档 ────────────────────────────────────────────────────────────

    @Test
    fun `全库存档导出后导入能恢复文档笔记笔迹与进度`() = runBlocking {
        val meta = importSample("甲.txt")
        val other = importSample("乙.txt", "另一篇正文\n第二行")
        store.annotations.saveInk(
            inkAnnotation(meta.id, listOf("s-big")),
            listOf(bigStroke("s-big", 20_000)),
            listOf(BrushSpec.DEFAULT_PEN),
        )
        store.annotations.upsert(
            Annotation.Highlight(
                id = "hl-1", docId = meta.id, createdAt = 5L, colorArgb = 0x66FFD54F, anchor = chain(),
            ),
        )
        store.annotations.upsert(
            Annotation.Bookmark(
                id = "bm-1", docId = other.id, createdAt = 6L, colorArgb = 0,
                progressHint = Selector.ProgressHint(0.5f, "2"),
            ),
        )
        store.documents.saveProgress(ReadingProgress(meta.id, 4, 1, 0.1f, 9L))

        val expectedDocs = store.documents.count()
        val expectedNotes = store.annotations.listAll().size
        assertEquals(2, expectedDocs)
        assertEquals(3, expectedNotes)

        val archive = ByteArrayOutputStream().also { store.archive.exportToStream(it, "test") }.toByteArray()
        assertTrue("档案里必须有内容", archive.size > 1000)

        // 真正清空全库（删库 + 删文件），再从档案恢复。
        store.close()
        wipe()
        store = InkLeafStore(context)
        store.warmUp()
        assertEquals(0, store.documents.count())

        val summary = store.archive.importFromStream(ByteArrayInputStream(archive))
        assertEquals(expectedDocs, summary.importedDocuments)
        assertEquals(expectedNotes, summary.importedAnnotations)
        assertEquals(0, summary.skippedDocuments)
        assertEquals(0, summary.skippedAnnotations)

        // 恢复后的可读性：正文、笔迹点位、笔刷调色板、进度都要在。
        val restoredDoc = store.documents.list().first { it.title == "甲" }
        assertEquals(sampleText, String(store.documents.readBytes(restoredDoc), Charsets.UTF_8))
        val snapshot = store.annotations.listByDoc(restoredDoc.id)
        assertEquals(0, snapshot.unreadableAnnotationCount)
        assertEquals(0, snapshot.unreadableStrokeCount)
        assertEquals(2, snapshot.annotations.size)
        val restoredStrokes = snapshot.strokesOf("ink-${restoredDoc.id}")
        assertEquals(1, restoredStrokes.size)
        assertEquals(20_000, restoredStrokes[0].localPoints.size / 2)
        assertEquals(listOf(BrushSpec.DEFAULT_PEN), snapshot.brushesOf("ink-${restoredDoc.id}"))
        assertNotNull(store.documents.progressOf(restoredDoc.id))
        assertEquals("阅读进度也要一起恢复", 4, store.documents.progressOf(restoredDoc.id)!!.charOffset)
    }

    @Test
    fun `重复导入同一份档案只做合并且不覆盖`() = runBlocking {
        val meta = importSample("甲.txt")
        store.annotations.upsert(
            Annotation.Bookmark(
                id = "bm-1", docId = meta.id, createdAt = 6L, colorArgb = 0,
                progressHint = Selector.ProgressHint(0.5f, null),
            ),
        )
        val archive = ByteArrayOutputStream().also { store.archive.exportToStream(it, "test") }.toByteArray()

        val second = store.archive.importFromStream(ByteArrayInputStream(archive))
        assertEquals(0, second.importedDocuments)
        assertEquals(1, second.skippedDocuments)
        assertEquals(0, second.importedAnnotations)
        assertEquals(1, second.skippedAnnotations)
        assertEquals("现有文档没有被换掉", 1, store.documents.count())
        assertEquals("现有笔记没有被换掉", 1, store.annotations.listAll().size)
    }

    @Test
    fun `档案条目路径越界时被拒绝`() = runBlocking {
        // zip 里的路径是外部输入。一个 ../../databases/inkleaf.db 就能覆盖掉数据库本身。
        val evil = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(evil).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../../../databases/inkleaf.db"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        var rejected = false
        try {
            store.archive.importFromStream(ByteArrayInputStream(evil.toByteArray()))
        } catch (e: ArchiveRepository.ArchiveException) {
            rejected = true
        }
        assertTrue("越界的档案条目必须被拒绝", rejected)
    }

    @Test
    fun `不是本应用的 zip 会被明确拒绝`() = runBlocking {
        val junk = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(junk).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        var message: String? = null
        try {
            store.archive.importFromStream(ByteArrayInputStream(junk.toByteArray()))
        } catch (e: ArchiveRepository.ArchiveException) {
            message = e.message
        }
        assertNotNull("缺少 manifest 必须报错而不是静默成功", message)
    }

    // ── 维护 ────────────────────────────────────────────────────────────────

    @Test
    fun `维护任务不会回收仍被引用的外置文件`() = runBlocking {
        // sweepOrphanBlobs 唯一危险的失败方向是"误删"，
        // 所以这里断言的是"有引用时一条都不动"，而不是"孤儿能被删掉"。
        val meta = importSample()
        val annotation = inkAnnotation(meta.id, listOf("s-big"))
        store.annotations.saveInk(annotation, listOf(bigStroke("s-big", 20_000)), listOf(BrushSpec.DEFAULT_PEN))
        val before = store.paths.blobsDir.listFiles()!!.size
        assertEquals(1, before)

        assertEquals(0, store.documents.sweepOrphanBlobs())

        assertEquals("仍在被引用的外置文件必须原样保留", before, store.paths.blobsDir.listFiles()!!.size)
        assertEquals(20_000, store.annotations.listByDoc(meta.id).strokesOf(annotation.id)[0].localPoints.size / 2)
    }

    @Test
    fun `op_log 严格单调递增用于增量导出`() = runBlocking {
        val first = store.readSnapshot { it.lastOpSeq() }
        importSample()
        val afterImport = store.readSnapshot { it.lastOpSeq() }
        assertTrue("导入必须留下一条操作记录", afterImport > first)
        store.settings.put(SettingKeys.KEEP_SCREEN_ON, "true")
        assertTrue(store.readSnapshot { it.lastOpSeq() } > afterImport)
    }
}
