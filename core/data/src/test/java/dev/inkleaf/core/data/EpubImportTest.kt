package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.Selector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 穿过**导入层**的接缝测试。
 *
 * ## 为什么必须单独测这条缝
 *
 * `:core:document` 的测试证明"字节 → 会话"是对的；`StorageTest` 证明"会话 → 落库 → 读回"
 * 是对的。但两者之间还有一条缝：**导入时算出的指纹与打开时算出的指纹必须是同一个**。
 * 只在各自模块里测，这条缝正好落在两侧测试的盲区里（M3c 的两个缺陷都是这么来的）。
 *
 * 这里的做法是把书真的落库、再从盘上读回来重算一遍，然后比较指纹与每个块的文本 ——
 * 只要解包顺序、编码口径、归一化口径有任何一处不一致，断言立刻红。
 *
 * ## 为什么夹具在这里重写一遍
 *
 * 测试源集不跨模块共享（`:core:document` 的测试代码在 `:core:data` 里不可见）。
 * 为一个 50 行的 ZIP 写入器引入 testFixtures 模块，代价大于收益 ——
 * 这里只需要一本最小的书，不需要 `TestEpub` 的全部开关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpubImportTest {

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

    // ── 夹具：一本两章的 EPUB ───────────────────────────────────────────────

    private fun epubBytes(
        title: String = "风的名字",
        author: String = "某人",
        layout: String? = null,
        chapters: List<Pair<String, String>> = listOf(
            "ch1.xhtml" to "<h1>第一章 起风</h1><p>他说：走吧。</p>",
            "ch2.xhtml" to "<h1>第二章 落雪</h1><p>雪落在肩上。</p>",
        ),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            zip.text(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0"><rootfiles>
                   <rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/>
                   </rootfiles></container>""",
            )

            val manifest = chapters.mapIndexed { i, c ->
                """<item id="i$i" href="${c.first}" media-type="application/xhtml+xml"/>"""
            }.joinToString("\n")
            val spine = chapters.indices.joinToString("\n") { """<itemref idref="i$it"/>""" }
            zip.text(
                "OEBPS/book.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>$title</dc:title>
                       <dc:creator>$author</dc:creator>
                       <dc:language>zh</dc:language>
                       ${layout?.let { "<meta property=\"rendition:layout\">$it</meta>" } ?: ""}
                     </metadata>
                     <manifest>$manifest</manifest>
                     <spine>$spine</spine>
                   </package>""",
            )

            for ((file, body) in chapters) {
                zip.text(
                    "OEBPS/$file",
                    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml">
                       <head><title>t</title></head><body>$body</body></html>""",
                )
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun candidate(bytes: ByteArray, name: String = "风的名字.epub") =
        DocumentImporter.ImportCandidate(
            displayName = name,
            title = name.substringBeforeLast('.'),
            kind = DocumentCodec.kindFromFileName(name),
            bytes = bytes,
            sourceUri = null,
        )

    // ── 导入 ────────────────────────────────────────────────────────────────

    @Test
    fun `导入 EPUB 时用书内元数据作为标题与作者`() {
        val inspection = store.importer.inspect(candidate(epubBytes()))
        assertEquals(DocumentKind.EPUB, inspection.kind)
        assertEquals("风的名字", inspection.title)
        assertEquals("某人", inspection.author)
        assertEquals(4, inspection.blockCount)
        assertTrue("摘录应包含正文：${inspection.excerpt}", inspection.excerpt.contains("第一章 起风"))
        // EPUB 的编码来自书内声明，界面上不该出现"手动换编码"
        assertTrue(!inspection.charsetIsUserChangeable)
        assertTrue(inspection.evidence.contains("EPUB"))
    }

    @Test
    fun `固定版式 EPUB 在导入阶段被拒绝`() {
        val e = runCatching {
            store.importer.inspect(candidate(epubBytes(layout = "pre-paginated")))
        }.exceptionOrNull()
        assertTrue("实际=$e", e is DocumentImporter.ImportException)
        assertEquals(
            DocumentImporter.RejectReason.UNSUPPORTED_FORMAT,
            (e as DocumentImporter.ImportException).reason,
        )
        assertTrue(e.message!!.contains("固定版式"))
    }

    @Test
    fun `损坏的 EPUB 不会让导入崩溃`() {
        val e = runCatching { store.importer.inspect(candidate("随便一些字节".toByteArray())) }
            .exceptionOrNull()
        assertTrue("实际=$e", e is DocumentImporter.ImportException)
    }

    @Test
    fun `PDF 现在能读进内存，不再在选文件时被拒`() = runBlocking {
        val file = File(context.cacheDir, "论文.pdf")
        file.writeBytes("%PDF-1.7\n".toByteArray())

        val candidate = store.importer.readFromUri(context.contentResolver, android.net.Uri.fromFile(file))

        assertEquals(DocumentKind.PDF, candidate.kind)
        assertTrue("标题应来自文件名：${candidate.title}", candidate.title.endsWith("论文"))
        assertTrue("字节必须原样读进来，解析留给闸门", candidate.bytes.isNotEmpty())
    }

    // ── 落库与重开：坐标系必须逐字节一致 ─────────────────────────────────────

    @Test
    fun `重开 EPUB 时指纹与每个块都与导入时完全一致`() = runBlocking {
        val bytes = epubBytes()
        val inspection = store.importer.inspect(candidate(bytes))
        val imported = inspection.session

        val meta = store.importer.commit(inspection)
        assertEquals(DocumentKind.EPUB, meta.kind)
        assertEquals("风的名字", meta.title)
        assertEquals("某人", meta.author)
        assertEquals(imported.fingerprint, meta.sourceFingerprint)
        assertEquals(imported.blocks.size, meta.blockCount)

        // 原始 EPUB 字节被原样保存（将来改进解包器不必让用户重新导入）
        val stored = store.documents.readBytes(meta)
        assertTrue("私有副本应是原始 EPUB 字节", stored.contentEquals(bytes))

        // 打开：走与导入同一个入口
        val reopened = DocumentCodec.sessionFromBytes(meta.id, meta.kind, stored, meta.encoding).session
        assertEquals(
            "导入与打开的指纹必须是同一个：否则笔记在重开后立刻全部失配",
            meta.sourceFingerprint,
            reopened.fingerprint,
        )
        assertEquals(imported.normalized.text, reopened.normalized.text)
        assertEquals(imported.blocks.size, reopened.blocks.size)
        for (i in imported.blocks.indices) {
            assertEquals(imported.blocks[i].blockPath, reopened.blocks[i].blockPath)
            assertEquals(imported.blocks[i].text, reopened.blocks[i].text)
        }
    }

    @Test
    fun `重开后按块路径仍能找到同一行`() = runBlocking {
        val inspection = store.importer.inspect(candidate(epubBytes()))
        val meta = store.importer.commit(inspection)

        // 导入时在"第二章 落雪"上留下的结构型选择器
        val target = inspection.session.blocks.first { it.text.contains("第二章") }
        val selector = Selector.Structural(target.blockPath, 0, target.text.length, baselineOffsetEm = 0.8f)

        val reopened = DocumentCodec.sessionFromBytes(
            meta.id,
            meta.kind,
            store.documents.readBytes(meta),
            meta.encoding,
        ).session
        val found = reopened.blocks.firstOrNull { it.blockPath == selector.blockPath } ?: error("块路径失效")
        assertEquals(target.text, found.text)
        assertEquals(target.absoluteStart, found.absoluteStart)
        assertTrue(found.text.contains("第二章"))
    }

    @Test
    fun `目录项指向真实存在的标题块`() = runBlocking {
        val inspection = store.importer.inspect(candidate(epubBytes()))
        val toc = inspection.session.toc
        assertEquals(listOf("第一章 起风", "第二章 落雪"), toc.map { it.title })
        for (item in toc) {
            val block = inspection.session.blocks[item.blockIndex]
            assertEquals(item.charOffset, block.absoluteStart)
            assertTrue(block.text.contains(item.title))
        }
    }

    @Test
    fun `读不出来的正文文档会出现在导入提示里`() {
        // spine 声明了 3 个正文文档，压缩包里只有 2 个：必须报告缺失
        val bytes = epubBytes(
            chapters = listOf(
                "ch1.xhtml" to "<h1>第一章</h1>",
                "ch2.xhtml" to "<h1>第二章</h1>",
            ),
        )
        // 直接构造"OPF 多一个 itemref"的样本：把 ch3 写进 OPF 但不写文件
        val broken = bytes.copyOf()
        val patched = ByteArrayOutputStream()
        ZipOutputStream(patched).use { zip ->
            java.util.zip.ZipInputStream(broken.inputStream()).use { src ->
                while (true) {
                    val entry = src.nextEntry ?: break
                    val data = src.readBytes()
                    val text = if (entry.name.endsWith(".opf")) {
                        String(data, Charsets.UTF_8)
                            .replace("</manifest>", """<item id="i9" href="ch3.xhtml" media-type="application/xhtml+xml"/></manifest>""")
                            .replace("</spine>", """<itemref idref="i9"/></spine>""")
                    } else {
                        null
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(text?.toByteArray(Charsets.UTF_8) ?: data)
                    zip.closeEntry()
                }
            }
        }

        val inspection = store.importer.inspect(candidate(patched.toByteArray()))
        assertTrue(
            "缺失的正文文档必须出现在提示里：${inspection.warnings}",
            inspection.warnings.any { it.contains("读不到") },
        )
        assertTrue(inspection.evidence.contains("找不到"))
    }
}
