package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizationProfiles
import dev.inkleaf.core.model.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字节 → 文档会话的回归测试。
 *
 * 这是**坐标系的产生地**：块切分的块内偏移、归一化文本的字符下标、
 * 指纹，全部在这里被确定。它跑在 JVM 上（不需要设备），
 * 因为归一化/切分/指纹都不依赖 Android —— 只有"把它们画出来"才依赖。
 */
class DocumentCodecTest {

    private fun bytes(text: String, charset: String = "UTF-8") = text.toByteArray(charset(charset))

    @Test
    fun `UTF-8 中文按行切块`() {
        val session = DocumentCodec.buildSession("d1", DocumentKind.TXT, "第一行\n第二行\n第三行")
        assertEquals(3, session.blocks.size)
        assertEquals("第二行", session.blocks[1].text)
        assertEquals("blk[1]", session.blocks[1].blockPath)
    }

    @Test
    fun `CRLF 与 LF 得到同一个坐标系`() {
        // 这是"换行归一化"存在的全部理由：同一份文档在 Windows 与 Linux 上
        // 必须得到相同的块结构，否则锚点会在用户换编辑器后整篇错位。
        val lf = DocumentCodec.buildSession("d1", DocumentKind.TXT, "甲\n乙\n丙")
        val crlf = DocumentCodec.buildSession("d1", DocumentKind.TXT, "甲\r\n乙\r\n丙")
        assertEquals(lf.blocks.map { it.text }, crlf.blocks.map { it.text })
        assertEquals(lf.fingerprint, crlf.fingerprint)
    }

    @Test
    fun `BOM 不进入正文`() {
        val withBom = DocumentCodec.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("你好"))
        assertTrue(withBom.text.startsWith("你好"))
        assertEquals(3, withBom.bomLength)
        assertEquals("UTF-8", withBom.charsetName)
    }

    @Test
    fun `GB18030 中文能被探测出来`() {
        // 国区 Windows 上大量 TXT 是 GBK/GB18030。探测不出来就等于"不支持 TXT"。
        val raw = "这是一段中文，用来测试编码探测。".toByteArray(charset("GB18030"))
        val decoded = DocumentCodec.decode(raw)
        assertEquals("GB18030", decoded.charsetName)
        assertEquals("这是一段中文，用来测试编码探测。", decoded.text)
    }

    @Test
    fun `手动指定编码优先于自动探测`() {
        val raw = "第一章 起风".toByteArray(charset("GB18030"))
        val decoded = DocumentCodec.decode(raw, forcedCharset = "GB18030")
        assertEquals("第一章 起风", decoded.text)
        assertEquals(1f, decoded.confidence, 0f)
    }

    @Test
    fun `手动指定了错误的编码不会抛异常而是给出可预览的错误文本`() {
        // 必须把这条能力边界固化下来：强制一个"字节层面合法"的错误编码，
        // 结果不是"打不开"，而是"打开后是一篇通顺的错字"。
        // 这正是导入界面必须显示摘录、并把编码做成可切换下拉框的原因
        // （与 CharsetDetector 里不自动判定 Big5 的理由同源）。
        val raw = "这是一段中文".toByteArray(charset("GB18030"))
        val decoded = DocumentCodec.decode(raw, forcedCharset = "UTF-16LE")
        assertEquals("UTF-16LE", decoded.charsetName)
        assertNotEquals("这是一段中文", decoded.text)
    }

    @Test
    fun `手动指定的编码无法严格解码时标记低置信度`() {
        // 字节层面就非法（UTF-8 里的 0xFF）→ 严格解码失败 → 走宽松解码 + 低置信度。
        // "能打开但可能是乱码"远好于"打不开"：用户还能再换一个编码看一眼。
        val raw = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        val decoded = DocumentCodec.decode(raw, forcedCharset = "UTF-8")
        assertTrue(decoded.lowConfidence)
        assertTrue(decoded.evidence.contains("UTF-8"))
    }

    @Test
    fun `Markdown 标题带隐藏标记且不改变文本长度`() {
        val session = DocumentCodec.buildSession("d1", DocumentKind.MARKDOWN, "## 小标题\n正文")
        val heading = session.blocks[0]
        assertEquals(BlockKind.HEADING, heading.kind)
        assertEquals(2, heading.headingLevel)
        // 文本长度必须与原文一致：一旦 span 删掉 "## "，块内所有偏移都会平移。
        assertEquals("## 小标题".length, heading.text.length)
        assertEquals(1, heading.markerSpans.size)
        assertEquals(0 + 0, heading.markerSpans[0].start - heading.absoluteStart)
        assertEquals(3, heading.markerSpans[0].length)
    }

    @Test
    fun `Markdown 的正文缩进与行尾空白被保留`() {
        val text = "    四个空格缩进\n行尾有两个空格  \n"
        val session = DocumentCodec.buildSession("d1", DocumentKind.MARKDOWN, text)
        assertEquals("    四个空格缩进", session.blocks[0].text)
        // 行尾空白在 Markdown 里是"强制换行"，不能被归一化掉。
        assertTrue(session.blocks[1].text.endsWith("  "))
        assertEquals(NormalizationProfiles.MARKDOWN.profileId, session.profile.profileId)
    }

    @Test
    fun `指纹随口径变化而变化`() {
        // 指纹里若不混入口径，"同一份正文用不同口径解析"会被判为"没变"，
        // 于是把锚点按错误的偏移映射出去 —— 最危险的一类静默错位。
        val text = "甲  乙\n丙"
        val plain = DocumentCodec.buildSession("d", DocumentKind.TXT, text)
        val preserved = DocumentCodec.buildSession("d", DocumentKind.TXT, text, NormalizationProfiles.MARKDOWN)
        assertNotEquals(plain.fingerprint, preserved.fingerprint)
    }

    @Test
    fun `指纹稳定可复现`() {
        val text = "第一章 起风\n\n他说：\"走吧。\"\n"
        val a = DocumentCodec.buildSession("d", DocumentKind.TXT, text)
        val b = DocumentCodec.buildSession("d", DocumentKind.TXT, text)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(16, a.fingerprint.length)
    }

    @Test
    fun `空文档得到空会话而不是崩溃`() {
        val session = DocumentCodec.buildSession("d", DocumentKind.TXT, "")
        assertEquals(0, session.blocks.size)
        assertEquals(0, session.normalized.length)
    }

    @Test
    fun `只有空白的文档没有块`() {
        val session = DocumentCodec.buildSession("d", DocumentKind.TXT, "\n\n   \n\t\n")
        assertEquals(0, session.blocks.size)
    }

    @Test
    fun `扩展名推断格式`() {
        assertEquals(DocumentKind.MARKDOWN, DocumentCodec.kindFromFileName("a.MD"))
        assertEquals(DocumentKind.MARKDOWN, DocumentCodec.kindFromFileName("readme.markdown"))
        assertEquals(DocumentKind.EPUB, DocumentCodec.kindFromFileName("book.epub"))
        assertEquals(DocumentKind.PDF, DocumentCodec.kindFromFileName("paper.pdf"))
        assertEquals(DocumentKind.TXT, DocumentCodec.kindFromFileName("novel.txt"))
        assertEquals(DocumentKind.TXT, DocumentCodec.kindFromFileName("没有扩展名"))
    }

    @Test
    fun `章节标题被识别进目录`() {
        val text = "第一章 起风\n他说：第一章里提到的那个人最后走了。\n第二章 落雪"
        val session = DocumentCodec.buildSession("d", DocumentKind.TXT, text)
        assertEquals(2, session.toc.size)
        assertEquals("第一章 起风", session.toc[0].title)
        assertEquals(0, session.toc[0].blockIndex)
        assertEquals(2, session.toc[1].blockIndex)
        // 第二行含句读 → 不是标题
        assertEquals(BlockKind.PARAGRAPH, session.blocks[1].kind)
    }

    @Test
    fun `摘录跳过空行`() {
        val session = DocumentCodec.buildSession("d", DocumentKind.TXT, "\n\n第一段\n\n第二段")
        val excerpt = DocumentCodec.excerpt(session, 64)
        assertEquals("第一段\n第二段", excerpt)
    }
}
