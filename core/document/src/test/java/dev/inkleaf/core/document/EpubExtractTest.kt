package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB 解包与章节映射。
 *
 * 重点覆盖两类东西：
 *   1. **错误路径**（不是 ZIP、缺 container、固定版式、加密、没有文字）：
 *      每一条都要给用户一句人话，而不是崩溃或空白书；
 *   2. **章节 ↔ 块的映射**：这是 EPUB 特有的、最容易静默错位的地方
 *      （偏移算错一个字符，目录就指到上一行）。
 */
class EpubExtractTest {

    private fun extract(spec: TestEpub.Spec) = EpubExtractor.extract(TestEpub.build(spec))

    // ── 正常解包 ────────────────────────────────────────────────────────────

    @Test
    fun `解出正文元数据与章节顺序`() {
        val epub = extract(TestEpub.Spec())
        assertEquals("测试书", epub.title)
        assertEquals("某作者", epub.author)
        assertEquals("zh", epub.language)
        assertEquals(2, epub.chapters.size)
        assertEquals("第一章\n第一段正文。\n第二章\n第二段正文。", epub.text)
        assertEquals(2, epub.spineItemCount)
        assertEquals(0, epub.skippedSpineItems)
    }

    @Test
    fun `章节顺序按 spine 而不是 ZIP 内顺序`() {
        val epub = extract(
            TestEpub.Spec(spineOrder = listOf("ch2.xhtml", "ch1.xhtml")),
        )
        assertEquals(listOf("第二章", "第一章"), epub.chapters.map { it.title })
        assertEquals("第二章\n第二段正文。\n第一章\n第一段正文。", epub.text)
    }

    @Test
    fun `章节偏移指向第一章的首行`() {
        val epub = extract(TestEpub.Spec())
        assertEquals(listOf("第一章", "第二章"), epub.chapters.map { it.title })
        for (chapter in epub.chapters) {
            assertEquals(
                "章节偏移应落在标题行首，实际=${chapter.charOffset}",
                chapter.title,
                epub.text.substring(chapter.charOffset, chapter.charOffset + chapter.title.length),
            )
        }
    }

    @Test
    fun `非正文媒体类型不进 spine`() {
        val epub = extract(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>字。</p>"),
                    TestEpub.Chapter("style.css", "p{color:red}", mediaType = "text/css"),
                ),
            ),
        )
        assertEquals(1, epub.spineItemCount)
        assertTrue("CSS 内容不该出现在正文里：${epub.text}", !epub.text.contains("color"))
    }

    @Test
    fun `没有文字的章节被跳过并计数`() {
        val epub = extract(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("empty.xhtml", "<div> </div>"),
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>字。</p>"),
                ),
            ),
        )
        assertEquals(2, epub.spineItemCount)
        assertEquals(1, epub.skippedSpineItems)
        assertEquals(1, epub.chapters.size)
        assertEquals(0, epub.imageCount)
        assertEquals("正文\n字。", epub.text)
    }

    @Test
    fun `图片章节不再算作没有文字`() {
        // M31 之前纯图片章节会被整章跳过；现在它留下一行占位字符，本身就是内容。
        val epub = extract(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("cover.xhtml", "<div><img src=\"cover.jpg\"/></div>"),
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>字。</p>"),
                ),
                extra = mapOf("OEBPS/cover.jpg" to PIC_BYTES),
            ),
        )
        assertEquals(0, epub.skippedSpineItems)
        assertEquals(1, epub.imageCount)
        assertEquals(1, epub.images.size)
    }

    @Test
    fun `图片地址按本章目录解析`() {
        // 现实里的写法：章在 text/ 下，图在 ../Images/ 下（甚至写成 ./../）
        val epub = extract(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("text/ch1.xhtml", "<h1>正文</h1><p>字。</p><img src=\"../Images/pic.png\"/>"),
                ),
                // 章在 OEBPS/text/ 下，`../Images/` 回退一级 → OEBPS/Images/
                extra = mapOf("OEBPS/Images/pic.png" to PIC_BYTES),
            ),
        )
        val image = epub.images.single()
        // 章的实际包内路径是 OEBPS/text/ch1.xhtml，上一级回退后落在 OEBPS/Images/
        assertEquals("OEBPS/Images/pic.png", image.href)
        // 偏移必须正好落在正文里的那个占位字符上
        assertEquals(IMAGE_PLACEHOLDER, epub.text[image.charOffset])
    }

    @Test
    fun `图片文件不在包里时不进正文`() {
        val epub = extract(
            TestEpub.Spec(chapters = listOf(TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><img src=\"没有这张.png\"/>"))),
        )
        assertEquals(1, epub.imageCount)
        assertTrue("找不到的图不该留下空占位", epub.images.isEmpty())
        assertTrue(epub.imageBytes.isEmpty())
    }

    @Test
    fun `文件名带空格时按 URI 转义解析`() {
        val epub = extract(
            TestEpub.Spec(chapters = listOf(TestEpub.Chapter("my chapter.xhtml", "<h1>带空格</h1>"))),
        )
        assertEquals("带空格", epub.chapters.single().title)
    }

    @Test
    fun `章节没有标题时给出序号标题`() {
        val epub = extract(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("a.xhtml", "<p>甲</p>"),
                    TestEpub.Chapter("b.xhtml", "<h2>有标题</h2>"),
                ),
            ),
        )
        assertEquals(listOf("第 1 节", "有标题"), epub.chapters.map { it.title })
    }

    // ── 错误路径 ────────────────────────────────────────────────────────────

    @Test
    fun `不是压缩包时给出缺 container 的明确原因`() {
        val failure = failureOf { EpubExtractor.extract("这不是电子书".toByteArray(Charsets.UTF_8)) }
        assertEquals(EpubFormatException.Failure.CONTAINER_MISSING, failure)
    }

    @Test
    fun `ZIP 头损坏时给出分类失败而不是崩溃`() {
        val broken = ByteArray(64).also {
            // 本地文件头签名正确、后面全是 0
            it[0] = 0x50; it[1] = 0x4B; it[2] = 0x03; it[3] = 0x04
        }
        // 具体是 BROKEN_ZIP 还是 CONTAINER_MISSING 取决于 JDK 的 readLOC 在
        // 空文件名时抛异常还是返回 null。这里钉住的是**契约**：
        // 任何损坏输入都必须以 EpubFormatException 结束，绝不逸出原始 IOException。
        val failure = failureOf { EpubExtractor.extract(broken) }
        assertTrue(
            "损坏的 ZIP 必须落到 BROKEN_ZIP 或 CONTAINER_MISSING，实际=$failure",
            failure == EpubFormatException.Failure.BROKEN_ZIP ||
                failure == EpubFormatException.Failure.CONTAINER_MISSING,
        )
    }

    @Test
    fun `被截断的压缩包不会静默给出半本残缺的正文`() {
        val bytes = TestEpub.build(
            TestEpub.Spec(
                chapters = List(8) { i ->
                    TestEpub.Chapter("ch$i.xhtml", "<h1>第 $i 章</h1><p>${"正文".repeat(50)}</p>")
                },
            ),
        )
        val truncated = bytes.copyOf(bytes.size / 2)
        val result = runCatching { EpubExtractor.extract(truncated) }
        val epub = result.getOrNull()
        if (epub == null) {
            // 允许"读不出来"：必须是分类失败，而不是原始 IO 异常
            assertTrue("实际=${result.exceptionOrNull()}", result.exceptionOrNull() is EpubFormatException)
        } else {
            // 允许"读出来一部分"，但必须把缺失的文档数报出来
            assertTrue(
                "截断后仍能解包时必须报告缺失的正文文档，实际 missing=${epub.missingSpineItems} / spine=${epub.spineItemCount}",
                epub.missingSpineItems > 0,
            )
            assertTrue("缺失信息要出现在给用户的依据里", epub.evidence().contains("找不到"))
        }
    }

    @Test
    fun `缺 rootfile 时判为 OPF 缺失`() {
        val bytes = TestEpub.build(
            TestEpub.Spec(containerXml = "<?xml version=\"1.0\"?><container><rootfiles/></container>"),
        )
        val failure = failureOf { EpubExtractor.extract(bytes) }
        assertEquals(EpubFormatException.Failure.OPF_MISSING, failure)
    }

    @Test
    fun `固定版式 EPUB 被明确拒绝`() {
        val bytes = TestEpub.build(TestEpub.Spec(layout = "pre-paginated"))
        val e = runCatching { EpubExtractor.extract(bytes) }.exceptionOrNull()
        assertTrue("应为 EpubFormatException，实际=$e", e is EpubFormatException)
        assertEquals(EpubFormatException.Failure.FIXED_LAYOUT, (e as EpubFormatException).failure)
        assertTrue("提示里应说明原因：${e.message}", e.message!!.contains("固定版式"))
    }

    @Test
    fun `正文被加密时拒绝而字体被加密时照常解包`() {
        // 字体混淆（idpf embedding）在正规电子书里很常见，不能因此拒绝整本书
        val fonts = TestEpub.build(
            TestEpub.Spec(encryptedRefs = listOf("OEBPS/fonts/serif.otf", "OEBPS/fonts/serif.ttf")),
        )
        assertEquals(2, EpubExtractor.extract(fonts).chapters.size)

        val text = TestEpub.build(TestEpub.Spec(encryptedRefs = listOf("OEBPS/ch1.xhtml")))
        val failure = failureOf { EpubExtractor.extract(text) }
        assertEquals(EpubFormatException.Failure.ENCRYPTED, failure)
    }

    @Test
    fun `没有可提取文字时判为空`() {
        val bytes = TestEpub.build(
            TestEpub.Spec(chapters = listOf(TestEpub.Chapter("a.xhtml", "<div></div>"))),
        )
        val failure = failureOf { EpubExtractor.extract(bytes) }
        assertEquals(EpubFormatException.Failure.EMPTY, failure)
    }

    // ── XHTML 编码 ──────────────────────────────────────────────────────────

    @Test
    fun `按文件内声明解码并处理 BOM`() {
        val body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>中文</p>"
        assertEquals(body, EpubExtractor.decodeXhtml(body.toByteArray(Charsets.UTF_8)))

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(Charsets.UTF_8)
        assertEquals(body, EpubExtractor.decodeXhtml(withBom))
    }

    @Test
    fun `声明与真实编码不符时回退到通用探测`() {
        // 现实里"写着 UTF-8、实际是 GBK"的电子书并不罕见
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><p>第一章 中文正文</p>"
        val gbk = body.toByteArray(charset("GB18030"))
        val decoded = EpubExtractor.decodeXhtml(gbk)
        assertTrue("GBK 正文应能正确解出，实际=$decoded", decoded.contains("第一章 中文正文"))
    }

    // ── 与下游管线的接缝 ────────────────────────────────────────────────────

    @Test
    fun `章节映射到块与目录项`() {
        val build = DocumentCodec.sessionFromBytes("d1", DocumentKind.EPUB, TestEpub.build())
        val session = build.session

        assertEquals(EpubExtractor.CHARSET_LABEL, build.decoded.charsetName)
        assertEquals("测试书", build.title)
        assertEquals("某作者", build.author)
        assertEquals(4, session.blocks.size)
        assertEquals("第一章", session.blocks[0].text)

        val toc = session.toc
        assertEquals(listOf("第一章", "第二章"), toc.map { it.title })
        for (item in toc) {
            assertEquals(
                "目录字符偏移必须等于对应块的起点",
                item.charOffset,
                session.blocks[item.blockIndex].absoluteStart,
            )
            assertTrue(session.blocks[item.blockIndex].kind == BlockKind.HEADING)
        }
        // 启发式目录（"第X章"）不该混进来
        assertEquals(2, toc.size)
    }

    @Test
    fun `EPUB 的内嵌编码不因强制编码而改变`() {
        val bytes = TestEpub.build()
        val a = DocumentCodec.sessionFromBytes("d", DocumentKind.EPUB, bytes)
        val b = DocumentCodec.sessionFromBytes("d", DocumentKind.EPUB, bytes, forcedCharset = "GB18030")
        assertEquals(a.session.fingerprint, b.session.fingerprint)
        assertEquals(a.session.normalized.text, b.session.normalized.text)
        assertNotEquals("", a.session.fingerprint)
    }

    @Test
    fun `图片与跳过章节会出现在警告里`() {
        val bytes = TestEpub.build(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("empty.xhtml", "<div></div>"),
                    // 引用了一张包里没有的图：正文照读，但要告诉用户图没能显示
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>字。<img src=\"missing.png\"/></p>"),
                ),
            ),
        )
        val build = DocumentCodec.sessionFromBytes("d", DocumentKind.EPUB, bytes)
        assertTrue(build.warnings.any { it.contains("图片") })
        assertTrue(build.warnings.any { it.contains("没有文字") })
    }

    private fun failureOf(block: () -> Unit): EpubFormatException.Failure? {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("应抛 EpubFormatException，实际=$e", e is EpubFormatException)
        return (e as? EpubFormatException)?.failure
    }
}

/**
 * 一张真实的 1×1 PNG。
 *
 * 用真图而不是随便几个字节：`EpubImageProvider` 要真的解码一次才知道
 * "取不到字节"与"字节不是图"是两件不同的事。
 */
internal val PIC_BYTES: ByteArray = java.util.Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
)
