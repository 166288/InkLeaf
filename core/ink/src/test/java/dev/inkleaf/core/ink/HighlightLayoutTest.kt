package dev.inkleaf.core.ink

import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 高亮在**真实排版**下的两条几何结论：标哪几个字、画成几个矩形。
 *
 * 必须跑真实 `StaticLayout`（`NATIVE` 模式）：这两条讲的都是"字到底画在哪" ——
 * 缩进占多宽、段落从哪里折行，假行盒里根本没有这些事实。
 * 纯逻辑（建链、解析、解不开就不画）在 [HighlightsTest] 里，那里更快也更准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightLayoutTest {

    private val metrics = PageLayoutMetrics(
        contentWidthPx = 900,
        contentHeightPx = 1400,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    private val theme = DocumentTheme.light(textSizePx = 40f, lineSpacingMultiplier = 1.4f)

    private fun factsOf(text: String): Pair<LayoutFacts, dev.inkleaf.core.document.ReflowableTextLayout> {
        val session = DocumentCodec.buildSession("hl-layout", DocumentKind.TXT, text)
        val layout = session.layout(metrics, theme)
        return SessionLayoutFacts(session, layout, docRatio = 0f) to layout
    }

    private fun highlight(chain: dev.inkleaf.core.model.TextAnchorChain, id: String = "hl-1") =
        Annotation.Highlight(
            id = id,
            docId = "hl-layout",
            createdAt = 1L,
            colorArgb = 0x66FFD54F.toInt(),
            anchor = chain,
        )

    @Test
    fun `行首的空白不算在高亮里`() {
        // 段首缩进是排版给的一部分，但"这一行的字"不该从缩进处开始：
        // 色带从缩进处起画，用户看到的是"这一行被涂了"，而不是"这一行的字被标了"。
        val (facts, _) = factsOf("   缩进的一行\n普通的一行")
        val line = facts.lines.first()

        val range = facts.lineHighlightRange(line)
        assertNotNull("这一行有文字，必须给得出可标的区间", range)
        assertEquals(
            "缩进必须被裁掉",
            "缩进的一行",
            facts.normalizedText.substring(range!!.first, range.last + 1),
        )
    }

    @Test
    fun `行的区间里不含块之间的换行符`() {
        val (facts, _) = factsOf("第一行\n第二行")

        for (line in facts.lines) {
            val range = facts.lineHighlightRange(line)!!
            val text = facts.normalizedText.substring(range.first, range.last + 1)
            assertTrue("底色不该盖到下一段的开头：[$text]", !text.contains('\n'))
        }
    }

    @Test
    fun `软换行的段落里横跨两行的区间画到两个视觉行上`() {
        // 一个字块（一个自然段）被排版折成多个视觉行 —— 高亮横跨折行处是完全正常的，
        // 这也是它**不能**按"解到的那一行"裁剪的原因（裁了第二行的色带就没了）。
        val paragraph = "这一段很长".repeat(40)
        val (facts, layout) = factsOf("$paragraph\n短行")
        assertTrue("前提：这一段必须被折成多个视觉行", facts.lines.size >= 3)
        assertEquals("前提：折出来的前两行必须属于同一块", facts.lines[0].anchor.blockPath, facts.lines[1].anchor.blockPath)

        val first = facts.absoluteRangeOf(facts.lines[0])!!
        val second = facts.absoluteRangeOf(facts.lines[1])!!
        val range = (first.first + 2)..(second.first + 2)

        val chain = facts.chainForRange(range.first, range.last + 1)
        assertNotNull("横跨折行处的区间必须建得出链", chain)
        val band = highlightRanges(listOf(highlight(chain!!)), facts).single()

        assertEquals("画出来的必须正好是标中的那一段", range, band.range)
        val rects = layout.rectsOfCharRange(band.range.first, band.range.last + 1)
        assertEquals("横跨两个视觉行就该有两个矩形", 2, rects.size)
        assertTrue("第二个矩形必须在下一行上", rects[1].top > rects[0].top)
    }
}
