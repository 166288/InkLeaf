package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.LineEnding
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.SpacePolicy
import dev.inkleaf.core.model.UnicodeForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化引擎的行为锁定。
 *
 * 这些用例不是在测"字符串处理对不对"，而是在锁住一件事：
 * **同一个输入永远得到同一个输出，且偏移映射永远能回到原文**。
 * 一旦这条被破坏，全库锚点会静默错位 —— 用户不会收到任何提示，
 * 只会发现笔记跑到了别的地方。这是本工程最不能出的 bug。
 */
class TextNormalizerTest {

    private val default = NormalizationProfile()

    @Test
    fun `CRLF 折叠成一个换行而不是两个`() {
        val n = TextNormalizer.normalize("第一行\r\n第二行", default)
        assertEquals("第一行\n第二行", n.text)
        // 关键：换行只能算一次。若 CRLF 被当成两个换行，块切分会多出一个空段落，
        // 后续所有块的 blockPath 全部 +1 —— 锚点集体错位。
        assertEquals(1, n.text.count { it == '\n' })
    }

    @Test
    fun `CR 单独出现也折叠成 LF`() {
        val n = TextNormalizer.normalize("a\rb", default)
        assertEquals("a\nb", n.text)
    }

    @Test
    fun `归一化后可以映射回原文下标`() {
        val n = TextNormalizer.normalize("a\r\nb", default)
        // "a" 来自 0，"b" 来自 3
        assertEquals(0, n.rawIndexOf(0))
        assertEquals(3, n.rawIndexOf(2))
        assertEquals(4, n.rawIndexOf(n.length))
    }

    @Test
    fun `BOM 被去掉且偏移整体对齐`() {
        val n = TextNormalizer.normalize("\uFEFF你好", default)
        assertEquals("你好", n.text)
        // 若 BOM 没被去掉，首块偏移会整体 +1，导致该文档所有锚点错位一格
        assertEquals(1, n.rawIndexOf(0))
    }

    @Test
    fun `行尾空白被去掉而行内空白保留`() {
        val n = TextNormalizer.normalize("a  \nb  c", default)
        assertEquals("a\nb c", n.text)
    }

    @Test
    fun `连续半角空白折叠成一个`() {
        val n = TextNormalizer.normalize("a   \t  b", default)
        assertEquals("a b", n.text)
    }

    @Test
    fun `全角空格必须保留`() {
        // 全角空格在中文排版里是语义字符（段首缩进、对齐），不是"空白"
        val n = TextNormalizer.normalize("你\u3000好", default)
        assertEquals("你\u3000好", n.text)
    }

    @Test
    fun `REMOVE_ALL 策略也不删换行`() {
        val profile = default.copy(spacePolicy = SpacePolicy.REMOVE_ALL)
        val n = TextNormalizer.normalize("a b\nc d", profile)
        // 换行必须留下：删掉换行会把段落粘成一段，块结构整体失效
        assertEquals("ab\ncd", n.text)
    }

    @Test
    fun `PRESERVE 策略原样保留空白`() {
        val profile = default.copy(spacePolicy = SpacePolicy.PRESERVE, trimTrailing = false)
        val n = TextNormalizer.normalize("a   b", profile)
        assertEquals("a   b", n.text)
    }

    @Test
    fun `NFKC 把全角拉丁字母归一为半角`() {
        val profile = default.copy(unicodeForm = UnicodeForm.NFKC)
        val n = TextNormalizer.normalize("ＡＢＣ", profile)
        assertEquals("ABC", n.text)
    }

    @Test
    fun `大小写折叠`() {
        val profile = default.copy(caseFold = true)
        val n = TextNormalizer.normalize("AbC", profile)
        assertEquals("abc", n.text)
    }

    @Test
    fun `去标点用 Unicode 分类而不是手写字符表`() {
        val profile = default.copy(stripPunctuation = true)
        // 中文标点、英文标点、引号都要被判为标点
        val n = TextNormalizer.normalize("你好，世界。\"对\"", profile)
        assertEquals("你好世界对", n.text)
    }

    @Test
    fun `去标点时中英混排的全角与半角标点都被处理`() {
        val profile = default.copy(stripPunctuation = true)
        val n = TextNormalizer.normalize("a,b。c！d?", profile)
        assertEquals("abcd", n.text)
    }

    @Test
    fun `偏移映射在混合变换下仍然单调且可回原文`() {
        val raw = "\uFEFF标题\r\n\r\n第一段  有   空白。\r\n" + "ＡＢ，\t尾随空白   "
        val profile = default.copy(unicodeForm = UnicodeForm.NFKC, stripPunctuation = true)
        val n = TextNormalizer.normalize(raw, profile)

        var previous = -1
        for (i in 0..n.length) {
            val rawIndex = n.rawIndexOf(i)
            assertTrue("rawIndexOf 必须非递减：i=$i raw=$rawIndex prev=$previous", rawIndex >= previous)
            assertTrue("rawIndexOf 必须落在原文范围内：$rawIndex", rawIndex in 0..raw.length)
            previous = rawIndex
        }
        assertEquals("末项必须等于原文长度", raw.length, n.rawIndexOf(n.length))
    }

    @Test
    fun `归一化是确定性的`() {
        val raw = "混合 Mixed 内容\r\n 带空白 \u3000 与全角 Ｘ "
        val a = TextNormalizer.normalize(raw, default)
        val b = TextNormalizer.normalize(raw, default)
        assertEquals(a.text, b.text)
        for (i in 0..a.length) {
            assertEquals(a.rawIndexOf(i), b.rawIndexOf(i))
        }
    }

    @Test
    fun `原文下标映射到归一化下标`() {
        val raw = "a  b"
        val n = TextNormalizer.normalize(raw, default)
        assertEquals("a b", n.text)
        // 原文下标 3 的 'b' 在归一化文本里是下标 2
        assertEquals(2, n.normalizedIndexOf(3))
    }

    @Test
    fun `空输入不崩且映射有效`() {
        val n = TextNormalizer.normalize("", default)
        assertEquals("", n.text)
        assertEquals(0, n.rawIndexOf(0))
        assertEquals(0, n.rawIndexOf(99))
        assertTrue(n.isEmpty)
    }

    @Test
    fun `只有 BOM 的输入归一化为空`() {
        val n = TextNormalizer.normalize("\uFEFF", default)
        assertEquals("", n.text)
        assertEquals(1, n.rawIndexOf(0))
    }

    @Test
    fun `行尾空白在无换行的最后一行也被去掉`() {
        val n = TextNormalizer.normalize("abc   ", default)
        assertEquals("abc", n.text)
    }

    @Test
    fun `LineEnding 为 PRESERVE 时不改换行`() {
        val profile = default.copy(lineEnding = LineEnding.PRESERVE)
        val n = TextNormalizer.normalize("a\r\nb", profile)
        assertEquals("a\r\nb", n.text)
    }
}
