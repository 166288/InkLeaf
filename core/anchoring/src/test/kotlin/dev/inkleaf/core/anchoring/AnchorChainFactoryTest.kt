package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorChainFactoryTest {

    private val profile = NormalizationProfile()
    private val text = "第一章 起风\n\n他站在门口说，今天风很大。\n\n第二段的内容在这里。"
    private val fp = SourceFingerprint.compute(text, profile, blockCount = 3)

    private fun target(
        absoluteStart: Int,
        absoluteEnd: Int,
        blockStart: Int = 0,
        blockEnd: Int = absoluteEnd - absoluteStart,
    ) = AnchorChainFactory.Target(
        blockPath = "blk[1]",
        blockStartOffset = blockStart,
        blockEndOffset = blockEnd,
        absoluteStart = absoluteStart,
        absoluteEnd = absoluteEnd,
    )

    @Test
    fun `构造出的链以 ProgressHint 结尾`() {
        val chain = AnchorChainFactory.build(text, profile, fp, target(9, 17), docRatio = 0.3f)
        assertTrue(chain.selectors.last() is Selector.ProgressHint)
        assertEquals(3, chain.selectors.size)
        assertEquals(fp, chain.sourceFingerprint)
        assertEquals(profile, chain.normalization)
    }

    @Test
    fun `结构型选择器保留块路径与块内偏移`() {
        val chain = AnchorChainFactory.build(
            text, profile, fp,
            AnchorChainFactory.Target("blk[7]", 3, 11, 9, 17),
            docRatio = 0.5f,
        )
        val s = chain.structural!!
        assertEquals("blk[7]", s.blockPath)
        assertEquals(3, s.startOffset)
        assertEquals(11, s.endOffset)
        assertEquals(1.0f, s.baselineOffsetEm, 1e-6f)
    }

    @Test
    fun `textQuote 取到 exact 与前后文`() {
        val start = 9
        val end = 17
        val chain = AnchorChainFactory.build(text, profile, fp, target(start, end), docRatio = 0.2f)
        val q = chain.textQuote!!
        assertEquals(text.substring(start, end), q.exact)
        assertEquals(text.substring(0, start), q.prefix)
        assertEquals(text.substring(end, text.length), q.suffix)
    }

    @Test
    fun `上下文长度受参数限制`() {
        val start = 9
        val end = 17
        val chain = AnchorChainFactory.build(
            text, profile, fp, target(start, end), docRatio = 0.2f, contextChars = 2,
        )
        val q = chain.textQuote!!
        assertEquals(text.substring(start - 2, start), q.prefix)
        assertEquals(text.substring(end, end + 2), q.suffix)
    }

    @Test
    fun `空选区不生成 textQuote`() {
        // 空 exact 会命中任意位置（空串是所有串的子串）—— 这是最危险的假阳性
        val chain = AnchorChainFactory.build(text, profile, fp, target(9, 9), docRatio = 0.2f)
        assertNull(chain.textQuote)
        assertEquals(2, chain.selectors.size)
        // 但仍必须保留结构型与兜底，否则链不合法
        assertTrue(chain.selectors.last() is Selector.ProgressHint)
    }

    @Test
    fun `超长选区被截断到上限`() {
        val long = "字".repeat(AnchorChainFactory.MAX_EXACT_CHARS + 500)
        val longFp = SourceFingerprint.compute(long, profile, 1)
        val chain = AnchorChainFactory.build(
            long, profile, longFp,
            AnchorChainFactory.Target("blk[0]", 0, long.length, 0, long.length),
            docRatio = 0f,
        )
        assertEquals(AnchorChainFactory.MAX_EXACT_CHARS, chain.textQuote!!.exact.length)
    }

    @Test
    fun `进度被夹在零到一`() {
        val a = AnchorChainFactory.build(text, profile, fp, target(9, 17), docRatio = -5f)
        val b = AnchorChainFactory.build(text, profile, fp, target(9, 17), docRatio = 42f)
        assertEquals(0f, a.progressHint.docRatio, 1e-6f)
        assertEquals(1f, b.progressHint.docRatio, 1e-6f)
    }

    @Test
    fun `兜底提示默认用 exact`() {
        val chain = AnchorChainFactory.build(text, profile, fp, target(9, 17), docRatio = 0.2f)
        assertEquals(chain.textQuote!!.exact, chain.progressHint.nearbyText)
    }

    @Test
    fun `可以显式给出页键与兜底文本`() {
        val chain = AnchorChainFactory.build(
            text, profile, fp, target(9, 17), docRatio = 0.2f,
            pageKey = "page:3", nearbyText = "提示文本",
        )
        assertEquals("page:3", chain.progressHint.pageKey)
        assertEquals("提示文本", chain.progressHint.nearbyText)
    }

    @Test
    fun `空指纹被拒绝`() {
        // 指纹为空会让规则 1 永远判"变了"或永远判"没变"，两种都不可接受
        assertThrows(IllegalArgumentException::class.java) {
            AnchorChainFactory.build(text, profile, "   ", target(9, 17), docRatio = 0f)
        }
    }

    @Test
    fun `越界的绝对偏移被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnchorChainFactory.build(text, profile, fp, target(0, text.length + 1), docRatio = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnchorChainFactory.build(text, profile, fp, target(-3, 5), docRatio = 0f)
        }
    }

    @Test
    fun `块内偏移顺序颠倒被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnchorChainFactory.build(
                text, profile, fp,
                AnchorChainFactory.Target("blk[0]", 10, 3, 9, 17),
                docRatio = 0f,
            )
        }
    }

    @Test
    fun `负的上下文长度被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnchorChainFactory.build(text, profile, fp, target(9, 17), docRatio = 0f, contextChars = -1)
        }
    }

    @Test
    fun `选区在文末时后缀为空而不是越界`() {
        val chain = AnchorChainFactory.build(text, profile, fp, target(text.length - 2, text.length), docRatio = 1f)
        assertEquals("", chain.textQuote!!.suffix)
    }
}
