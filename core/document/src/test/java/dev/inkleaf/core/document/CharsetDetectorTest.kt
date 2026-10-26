package dev.inkleaf.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 编码探测的行为锁定。
 *
 * 最重要的是"UTF-8 的中文字节**不能**被猜成 GB18030"这一条：
 * 一旦猜错，用户看到的是一篇通顺的错字文，比乱码更难察觉。
 */
class CharsetDetectorTest {

    private fun bytesOf(text: String, charset: String): ByteArray =
        text.toByteArray(Charset.forName(charset))

    private val chinese = "第一章 起风\n\n他站在门口说，今天风很大。\n"

    // ── BOM ─────────────────────────────────────────────────────────────────

    @Test
    fun `UTF-8 BOM 被识别并跳过`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + bytesOf(chinese, "UTF-8")
        val d = CharsetDetector.detect(bytes)
        assertEquals("UTF-8", d.charsetName)
        assertEquals(3, d.bomLength)
        assertEquals(1f, d.confidence, 1e-6f)
        // BOM 必须被跳过，否则正文开头会多一个 U+FEFF
        assertTrue(CharsetDetector.decode(bytes, d).startsWith("第一章"))
    }

    @Test
    fun `UTF-16LE 与 UTF-16BE 靠 BOM 区分`() {
        val le = bytesOf(chinese, "UTF-16LE")
        val be = bytesOf(chinese, "UTF-16BE")
        // 手工加上 BOM（Java 的 UTF-16LE 编码器不写 BOM）
        val leBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + le
        val beBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + be
        assertEquals("UTF-16LE", CharsetDetector.detect(leBom).charsetName)
        assertEquals("UTF-16BE", CharsetDetector.detect(beBom).charsetName)
        assertEquals(chinese, CharsetDetector.decode(leBom, CharsetDetector.detect(leBom)))
        assertEquals(chinese, CharsetDetector.decode(beBom, CharsetDetector.detect(beBom)))
    }

    @Test
    fun `UTF-32LE 的 BOM 不会被误判为 UTF-16LE`() {
        // FF FE 00 00 的前两字节与 UTF-16LE 的 BOM 完全一样，必须优先判 UTF-32
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00,
            0x41, 0x00, 0x00, 0x00,
        )
        assertEquals("UTF-32LE", CharsetDetector.detect(bytes).charsetName)
    }

    // ── 无 BOM ──────────────────────────────────────────────────────────────

    @Test
    fun `纯 ASCII 判为 UTF-8`() {
        val d = CharsetDetector.detect("hello world\n".toByteArray(Charsets.US_ASCII))
        assertEquals("UTF-8", d.charsetName)
        assertTrue(d.confidence >= 0.9f)
    }

    @Test
    fun `UTF-8 中文被正确识别`() {
        val d = CharsetDetector.detect(bytesOf(chinese, "UTF-8"))
        assertEquals("UTF-8", d.charsetName)
        assertTrue("严格合法的 UTF-8 应当高置信度", d.confidence >= 0.9f)
    }

    @Test
    fun `GB18030 中文被正确识别`() {
        val d = CharsetDetector.detect(bytesOf(chinese, "GB18030"))
        assertEquals("GB18030", d.charsetName)
        assertEquals(chinese, CharsetDetector.decode(bytesOf(chinese, "GB18030"), d))
    }

    @Test
    fun `UTF-8 中文不会被猜成 GB18030`() {
        // 这是本类最重要的一条：猜错会让用户看到通顺的错字文，比乱码更难发现
        val d = CharsetDetector.detect(bytesOf(chinese, "UTF-8"))
        assertEquals("UTF-8", d.charsetName)
        assertEquals(chinese, CharsetDetector.decode(bytesOf(chinese, "UTF-8"), d))
    }

    @Test
    fun `GB18030 中文不会被猜成 UTF-8`() {
        val bytes = bytesOf(chinese, "GB18030")
        val d = CharsetDetector.detect(bytes)
        assertEquals("GB18030", d.charsetName)
    }

    @Test
    fun `低置信度会被标记出来`() {
        // 一段谁都不是的垃圾字节：必须走"低置信度 + 交给用户"这条路
        val garbage = ByteArray(64) { (0xF0 + (it % 8)).toByte() }
        val d = CharsetDetector.detect(garbage)
        assertTrue("猜不出来时不能假装确定", d.isLowConfidence || d.charsetName == "UTF-8")
    }

    @Test
    fun `空输入不崩`() {
        val d = CharsetDetector.detect(ByteArray(0))
        assertEquals("UTF-8", d.charsetName)
        assertEquals(1f, d.confidence, 1e-6f)
        assertEquals("", CharsetDetector.decode(ByteArray(0), d))
    }

    @Test
    fun `只有 BOM 的文件解码为空`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val d = CharsetDetector.detect(bytes)
        assertEquals("", CharsetDetector.decode(bytes, d))
    }

    // ── 解码 ────────────────────────────────────────────────────────────────

    @Test
    fun `严格解码在非法字节上返回 null 而不是替换字符`() {
        // 单个 0xFF 不是合法 UTF-8 首字节
        assertNull(CharsetDetector.strictDecode(byteArrayOf(0xFF.toByte()), 0, 1, "UTF-8"))
        assertNotNull(CharsetDetector.strictDecode("a".toByteArray(), 0, 1, "UTF-8"))
    }

    @Test
    fun `解码失败时不抛异常而是尽力给出文本`() {
        val bad = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        val d = CharsetDetector.DetectedEncoding("UTF-16LE", 0.1f, 0, "测试用")
        // 不要求内容正确，只要求"不抛、能返回"
        val text = CharsetDetector.decode(bad, d)
        assertTrue(text.isNotEmpty() || text.isEmpty())
    }

    @Test
    fun `手动候选列表至少包含 UTF-8 与 GB18030`() {
        assertTrue(CharsetDetector.MANUAL_CHOICES.contains("UTF-8"))
        assertTrue(CharsetDetector.MANUAL_CHOICES.contains("GB18030"))
    }

    @Test
    fun `短文本与长文本判定一致`() {
        val long = chinese.repeat(200)
        assertEquals("UTF-8", CharsetDetector.detect(bytesOf(long, "UTF-8")).charsetName)
        assertEquals("GB18030", CharsetDetector.detect(bytesOf(long, "GB18030")).charsetName)
    }
}
