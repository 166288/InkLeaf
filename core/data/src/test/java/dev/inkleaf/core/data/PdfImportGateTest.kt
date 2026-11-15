package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.PdfFormatException
import dev.inkleaf.core.document.PdfPageFacts
import dev.inkleaf.core.document.PdfPageSource
import dev.inkleaf.core.document.PdfTextLine
import dev.inkleaf.core.model.DocumentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PDF 导入闸门（方案 §5.5）。
 *
 * 用假页源，因为这一层要回答的是**策略**问题：
 *
 *   - 扫描件该不该拦？拦了之后用户还能不能进来？
 *   - 一台读不到文本层的旧系统，会不会把所有文本型 PDF 都判死？
 *   - 打不开的文件给出的理由，用户能不能看懂？
 *
 * 真页源（`PdfRenderer`）在 Robolectric 里没有影子实现，也不该在这一层被测 ——
 * 它只负责把平台数字搬出来（见 `PlatformPdfPageSource` 的类注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfImportGateTest {

    private lateinit var store: InkLeafStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        wipe()
    }

    @After
    fun tearDown() {
        if (::store.isInitialized) store.close()
        wipe()
    }

    private fun wipe() {
        context.deleteDatabase(StorageSchema.DATABASE_NAME)
        File(context.filesDir, "documents").deleteRecursively()
        File(context.filesDir, "blobs").deleteRecursively()
        File(context.filesDir, "derived").deleteRecursively()
    }

    /** 两页文本：每页两行，每行都远超扫描件判定的 16 字下限。 */
    private fun textPages(): List<PdfPageFacts> = (0 until 2).map { index ->
        PdfPageFacts(
            index = index,
            widthPx = 595f,
            heightPx = 842f,
            lines = listOf(
                PdfTextLine(
                    text = "第 ${index + 1} 页的第一行正文，长度足够让扫描件判定认为这里有文本。",
                    left = 72f, top = 100f, right = 523f, bottom = 124f,
                    baselineYPx = 118f, fontSizePx = 20f,
                ),
                PdfTextLine(
                    text = "这是第二行正文，同样足够长，确保每一页都被判定为有文本层。",
                    left = 72f, top = 124f, right = 523f, bottom = 148f,
                    baselineYPx = 142f, fontSizePx = 20f,
                ),
            ),
        )
    }

    /** 扫描件：有页面、没有文本层。 */
    private fun scannedPages(): List<PdfPageFacts> = (0 until 2).map { index ->
        PdfPageFacts(index = index, widthPx = 595f, heightPx = 842f, lines = emptyList())
    }

    private fun storeWith(
        pages: List<PdfPageFacts>,
        canReadTextLayer: Boolean = true,
    ): InkLeafStore {
        val factory = PdfPageSource.Factory { FakePdfSource(pages, canReadTextLayer) }
        store = InkLeafStore(context, pdfPageSourceFactory = factory)
        store.warmUp()
        return store
    }

    private fun candidate() = DocumentImporter.ImportCandidate(
        displayName = "测试.pdf",
        title = "测试",
        kind = DocumentKind.PDF,
        // 页源是假的，字节内容不会被解析；给一个非空数组让"读到了文件"这件事成立。
        bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46),
        sourceUri = null,
    )

    @Test
    fun `文本型 PDF 通过闸门，页数与文本层如实落库`() = runBlocking {
        val store = storeWith(textPages())

        val inspection = store.importer.inspect(candidate())

        assertTrue("PDF 应当走固定版式", inspection.isFixedLayout)
        assertEquals(2, inspection.pageCount)
        assertTrue("文本型 PDF 必须有文本层", inspection.hasTextLayer)
        assertTrue("有文本层的 PDF 不该被当成空文档", inspection.charCount > 0)

        val meta = store.importer.commit(inspection)
        assertEquals(DocumentKind.PDF, meta.kind)
        assertTrue(meta.hasTextLayer)
    }

    @Test
    fun `扫描件被闸门挡住，理由要说出这是扫描件`() {
        val store = storeWith(scannedPages())

        val error = assertThrows(DocumentImporter.ImportException::class.java) {
            store.importer.inspect(candidate())
        }

        assertEquals(DocumentImporter.RejectReason.SCANNED_PDF, error.reason)
        assertTrue("理由必须点出扫描件：${error.message}", error.message!!.contains("扫描件"))
        assertTrue(
            "理由必须说明仍然可以按页查看：${error.message}",
            error.message!!.contains("按页"),
        )
    }

    @Test
    fun `用户点仍要打开之后，扫描件能导入并标记为没有文本层`() = runBlocking {
        val store = storeWith(scannedPages())

        val inspection = store.importer.inspect(candidate(), allowScannedPdf = true)

        assertEquals(2, inspection.pageCount)
        assertFalse("扫描件没有文本层", inspection.hasTextLayer)
        assertEquals("扫描件没有可阅读的正文", 0, inspection.charCount)
        assertEquals("", inspection.excerpt)

        val meta = store.importer.commit(inspection)
        assertFalse("落库必须记住这是扫描件", meta.hasTextLayer)
    }

    @Test
    fun `读不到文本层的旧系统不把文本型 PDF 判成扫描件`() = runBlocking {
        // 能力为 false：页源给不出文本层，但这只说明"我们看不见"，不说明文件是扫描件。
        val store = storeWith(scannedPages(), canReadTextLayer = false)

        val inspection = store.importer.inspect(candidate())

        assertEquals(2, inspection.pageCount)
        assertTrue(
            "必须提示是本机读不到，而不是文件没有：${inspection.warnings}",
            inspection.warnings.any { it.contains("本机") && it.contains("文本层") },
        )
        assertFalse(inspection.hasTextLayer)
    }

    @Test
    fun `PDF 打不开时给出 PDF_BROKEN，而不是一个空白文档`() {
        val factory = PdfPageSource.Factory {
            throw PdfFormatException(PdfFormatException.Failure.BROKEN, "这份 PDF 有密码保护，本版本打不开")
        }
        store = InkLeafStore(context, pdfPageSourceFactory = factory)
        store.warmUp()

        val error = assertThrows(DocumentImporter.ImportException::class.java) {
            store.importer.inspect(candidate())
        }

        assertEquals(DocumentImporter.RejectReason.PDF_BROKEN, error.reason)
        assertTrue("理由要能直接给用户看：${error.message}", error.message!!.contains("密码"))
    }
}

private class FakePdfSource(
    private val pages: List<PdfPageFacts>,
    override val canReadTextLayer: Boolean,
) : PdfPageSource {

    override val pageCount: Int get() = pages.size

    override fun page(index: Int): PdfPageFacts = pages[index]

    override val textLayerEvidence: String
        get() = if (canReadTextLayer) "测试用假页源（有文本层）" else "测试用假页源（本机无文本层）"

    override fun close() = Unit
}
