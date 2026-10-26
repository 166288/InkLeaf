package dev.inkleaf.core.ink

import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 粘合层的测试：从**真实排版**构造 [LayoutFacts]，检查它给出的每一项
 * 都是排版事实的直接投影，而不是某种推算。
 *
 * 必须跑真实 `StaticLayout`（`NATIVE` 模式）：这一层存在的全部理由就是
 * "行盒来自真实排版"，用假行盒测它等于什么都没测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionLayoutFactsTest {

    private val theme = DocumentTheme.light(textSizePx = 40f, lineSpacingMultiplier = 1.4f)

    private val metrics = PageLayoutMetrics(
        contentWidthPx = 900,
        contentHeightPx = 1400,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    private val text = "第一行文字\n第二行文字\n第三行文字"

    private fun facts(): LayoutFacts {
        val session = DocumentCodec.buildSession("doc-facts", DocumentKind.TXT, text)
        val layout = session.layout(metrics, theme)
        return SessionLayoutFacts(session, layout, docRatio = 0.5f)
    }

    @Test
    fun `行盒来自真实排版而且是同一份缓存对象`() {
        val facts = facts()

        assertTrue("真实排版应当给出行盒", facts.lines.isNotEmpty())
        assertSame(
            "行盒必须缓存：一次落笔会问好几遍，每次重新遍历全部段是纯浪费",
            facts.lines,
            facts.lines,
        )
    }

    @Test
    fun `行的文本与绝对区间都指回同一个块`() {
        val facts = facts()
        val first = facts.lines.first()

        assertEquals("第一行文字", facts.textOf(first))
        val range = facts.absoluteRangeOf(first)
        assertNotNull("单行块必须能给出绝对区间", range)
        assertEquals("第一行的绝对区间应当从全文开头起", 0, range!!.first)
        assertEquals(
            "绝对区间的长度必须等于该行文本长度（否则引文会多带或少带字符）",
            facts.textOf(first).length,
            range.last - range.first + 1,
        )
    }

    @Test
    fun `文本窗口包含相邻行的内容`() {
        val facts = facts()
        val first = facts.lines.first()

        // 这条断言就是"引文前后缀可校验"的前提：
        // 后缀取自全文（因而包含下一行的开头），只有窗口够宽才校验得出来。
        assertTrue(
            "窗口必须包含下一行的内容，否则前后缀会恒定不匹配（见 AnchorResolver.contextOf）",
            facts.textWindow(first).contains("第二行文字"),
        )
    }

    @Test
    fun `正文栏的横向范围来自排版度量而不是行盒墨迹盒`() {
        val facts = facts()
        val box = facts.geometry.contentBox

        assertEquals(24f, box.left, 0.5f)
        assertEquals(24f + 900f, box.right, 0.5f)
        // 短行的墨迹盒是窄的；用它的右边界会让"压在页边上"的判定随行内容抖动。
        val inkRight = facts.lines.first().rectDoc.right
        assertTrue("确实存在墨迹盒窄于正文栏的情形，否则这条断言没有意义", inkRight <= box.right)
    }

    @Test
    fun `没有候选行时不崩并且每行都给得出事实`() {
        val facts = facts()

        // 逐行问一遍：任何一个空指针或越界都会让"打开笔记本"直接崩。
        for (line in facts.lines) {
            assertNotNull(facts.absoluteRangeOf(line))
            assertTrue(facts.textOf(line).isNotEmpty())
            assertTrue(facts.textWindow(line).isNotEmpty())
        }
    }

    /**
     * 块这一层的两个方向必须互为逆运算。
     *
     * 这是**区间**标注（高亮）唯一的几何来源：写进去时把"全文里的这一段"换成块内偏移，
     * 画出来时再换回来。两处只要有一处差一个字符，症状就是"色带整体偏一格"，
     * 而两边的单元测试各自都是绿的。
     */
    @Test
    fun `行区间与结构型选择器的换算互为逆运算`() {
        val facts = facts()

        for (line in facts.lines) {
            val range = facts.absoluteRangeOf(line)!!
            val structural = facts.structuralOfRange(range)!!

            assertEquals("块路径必须指回同一块", line.anchor.blockPath, structural.blockPath)
            assertEquals("块内起点必须一致", line.anchor.startOffset, structural.startOffset)
            assertEquals("块内终点必须一致", line.anchor.endOffset, structural.endOffset)
            assertEquals("基线的相对位置也要取同一行的", line.anchor.baselineOffsetEm, structural.baselineOffsetEm, 0f)
            assertEquals("换回来必须是同一个绝对区间", range, facts.absoluteRangeOf(structural))
        }
    }

    @Test
    fun `跨块的区间被夹进第一个块`() {
        val facts = facts()
        // 3..8 横跨第 0 块的尾巴（3..4）、块之间的换行符（5）与第 1 块的开头（6..8）。
        // 结构型选择器只有一个块路径，装不下两段：宁可少标，也不要写一条
        // "指向两处"的链（它在重排后必然有一半是错的）。
        val structural = facts.structuralOfRange(3..8)!!

        assertEquals("blk[0]", structural.blockPath)
        assertEquals(3, structural.startOffset)
        assertEquals("右端被夹在第 0 块的末尾", 5, structural.endOffset)
        assertEquals(3..4, facts.absoluteRangeOf(structural))
    }
}
