package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 排版开关的回归测试（M33）：段间距、中文禁则、两端对齐。
 *
 * 这三项有个共同点：**它们都不许改变文本长度**。
 * 行盒、块内偏移、笔迹锚点全部以字符下标为坐标，任何"删掉一个标点"
 * "把两个标点合成一个"的做法都会让整套坐标系失效。
 * 所以这里的断言分两层：版面确实变了（高度、字宽），而字符数一个没少。
 *
 * 走真实 `StaticLayout`（Robolectric NATIVE），理由与 [PaginationTest] 相同：
 * 段间距是 `LineHeightSpan`、挤压是 `ReplacementSpan`，
 * 这两样东西的效果只有真的排一次版才能量出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesettingTest {

    private val theme = DocumentTheme.light(textSizePx = 48f, lineSpacingMultiplier = 1.4f)

    private fun metrics(width: Int = 1200, height: Int = 4000) = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height,
        marginLeftPx = 0f,
        marginRightPx = 0f,
        marginTopPx = 0f,
        marginBottomPx = 0f,
    )

    /** 每段都以句号收尾：这是 EPUB 提取器给出的形状（块之间只有一个换行）。 */
    private fun paragraphs(count: Int): String = (1..count).joinToString("\n") { i ->
        "第 $i 段正文，写得足够长以产生折行，但每一段自己是一个段落。"
    }

    private fun layoutOf(text: String, theme: DocumentTheme = this.theme) =
        DocumentCodec.buildSession("test-doc", DocumentKind.TXT, text).let { session ->
            session.layout(metrics(), theme) to session
        }

    // ── 段间距 ─────────────────────────────────────────────────────────────

    @Test
    fun `段间距按段加高：三段之间只有两处空隙`() {
        val text = paragraphs(3)
        val (base, _) = layoutOf(text)
        val (spaced, _) = layoutOf(text, theme.copy(paragraphSpacingEm = 0.5f))

        val extra = (48f * 0.5f).toInt()
        assertEquals(
            "三段之间只有两处段间距（第一段之前不加）",
            2 * extra,
            spaced.totalHeightPx - base.totalHeightPx,
        )
    }

    @Test
    fun `段间距不改变行数`() {
        val text = paragraphs(6)
        val (base, _) = layoutOf(text)
        val (spaced, _) = layoutOf(text, theme.copy(paragraphSpacingEm = 0.8f))
        assertEquals(
            "段间距只动行盒的上下边界，不许改变断行结果",
            base.lineCount,
            spaced.lineCount,
        )
    }

    @Test
    fun `段间距为零时版面与不带这个设置完全一致`() {
        val text = paragraphs(4)
        val (base, _) = layoutOf(text)
        val (zero, _) = layoutOf(text, theme.copy(paragraphSpacingEm = 0f))
        assertEquals(base.totalHeightPx, zero.totalHeightPx)
    }

    @Test
    fun `段间距让在段与段之间：第二段整体下移一个字高的量`() {
        val text = paragraphs(3)
        val (base, baseSession) = layoutOf(text)
        val (spaced, _) = layoutOf(text, theme.copy(paragraphSpacingEm = 0.5f))

        // 不开段间距时，三个短段会被并进同一个排版段；开了之后每段各成一段，
        // 段间距正好落在段与段之间（这是"几何留白"与"行内 span"的关键差别）。
        assertEquals("不开段间距时应当并成一段", 1, base.sections.size)
        assertEquals("开了段间距后每个自然段各成一段", 3, spaced.sections.size)

        val secondBlockStart = baseSession.blocks[1].absoluteStart
        val gap = (48f * 0.5f).toInt()
        assertEquals(
            "第二段的首行应当正好下移一个段间距",
            base.scrollYOfCharOffset(secondBlockStart) + gap,
            spaced.scrollYOfCharOffset(secondBlockStart),
        )
        assertEquals(
            "第三段的首行应当下移两个段间距（每两段之间一个）",
            base.scrollYOfCharOffset(baseSession.blocks[2].absoluteStart) + 2 * gap,
            spaced.scrollYOfCharOffset(baseSession.blocks[2].absoluteStart),
        )
    }

    @Test
    fun `段间距不改变段内行距`() {
        val text = paragraphs(2)
        val (base, _) = layoutOf(text)
        val (spaced, _) = layoutOf(text, theme.copy(paragraphSpacingEm = 0.5f))
        // 段间距只加在段与段之间：段内相邻两行的行盒高度必须与不带段间距时一致。
        val baseHeights = base.lineBoxes().map { it.rectDoc.height }
        val spacedHeights = spaced.lineBoxes().map { it.rectDoc.height }
        assertEquals("行盒数量不变", baseHeights.size, spacedHeights.size)
        for (i in baseHeights.indices) {
            assertEquals("第 $i 行的行高不该被段间距改变", baseHeights[i], spacedHeights[i], 0.01f)
        }
    }

    // ── 中文禁则 / 标点挤压 ─────────────────────────────────────────────────

    @Test
    fun `连续标点被压窄到半个字`() {
        // 第 7 个字符是 。、第 8 个是 」 —— 两个都是收尾标点，后一个该被压窄。
        val text = "他说：「这样。」然后走了。"
        val second = text.indexOf('」')
        val (plain, _) = layoutOf(text)
        val (squeezed, _) = layoutOf(text, theme.copy(cjkPunctuation = true))

        fun advanceOf(layout: android.text.Layout, index: Int): Float =
            layout.getPrimaryHorizontal(index + 1) - layout.getPrimaryHorizontal(index)

        val plainWidth = advanceOf(plain.sections.first().layout, second)
        val squeezedWidth = advanceOf(squeezed.sections.first().layout, second)

        assertEquals("未挤压时一个全角标点占一个字宽", 48f, plainWidth, 1f)
        assertEquals(
            "挤压后应当只剩半个字宽",
            48f * TextSpanBuilder.PUNCTUATION_SQUEEZE,
            squeezedWidth,
            1.5f,
        )
    }

    @Test
    fun `挤压不改变文本长度`() {
        val text = "他说：「这样。」然后走了。\n她答：「好。」"
        val (plain, _) = layoutOf(text)
        val (squeezed, _) = layoutOf(text, theme.copy(cjkPunctuation = true))
        assertEquals(
            "字符数是全工程唯一的文字坐标，一个都不能少",
            plain.sections.first().layout.text.length,
            squeezed.sections.first().layout.text.length,
        )
    }

    @Test
    fun `单个标点不挤压`() {
        val text = "他走了。然后她也走了。"
        val index = text.indexOf('。')
        val (plain, _) = layoutOf(text)
        val (squeezed, _) = layoutOf(text, theme.copy(cjkPunctuation = true))
        fun advanceOf(layout: android.text.Layout, i: Int): Float =
            layout.getPrimaryHorizontal(i + 1) - layout.getPrimaryHorizontal(i)
        assertEquals(
            "单个句号后面不是标点，没有可压的对象",
            advanceOf(plain.sections.first().layout, index),
            advanceOf(squeezed.sections.first().layout, index),
            0.5f,
        )
    }

    // ── 两端对齐 ───────────────────────────────────────────────────────────

    @Test
    fun `两端对齐后非末行右缘齐平`() {
        // 样例必须是**中西混排**。
        //
        // 纯中文的每一行都由等宽方块字填满，不对齐时右缘本来就在同一条线上 ——
        // 那样的样例里"极差"永远是 0，这条测试就成了恒真式，谁都发现不了它已经失效。
        // 混排才有"一个英文单词宽度不定，行尾剩下几十像素"的参差，而端齐的收益
        // 恰恰在这里（中文侧拉字距，英文侧拉词距）。
        val text = "这一段刻意写得长一些，用来产生多次折行，比如 Apple 和 Google 这样的词，" +
            "再混 several English words 进去，让每一行的右边缘天然参差不齐。" +
            "再多写几句，让这一段的行数足够多，断言才有区分力。" +
            "最后加一点 Android、Kotlin、Compose 之类的中英夹杂，长度各异。"
        val (ragged, _) = layoutOf(text)
        val (justified, _) = layoutOf(text, theme.copy(justifyText = true))

        val raggedLayout = ragged.sections.first().layout
        val justifiedLayout = justified.sections.first().layout
        assertTrue("样例应当不止两行", raggedLayout.lineCount > 2)
        assertEquals("对齐不许改变断行结果", raggedLayout.lineCount, justifiedLayout.lineCount)

        // 除最后一行（段末，天然不齐）之外的每一行，右边缘应当落在同一条线上。
        val rights = (0 until justifiedLayout.lineCount - 1)
            .map { justifiedLayout.getLineRight(it) }
        val spread = (rights.max() - rights.min())
        assertTrue("非末行右缘的极差应当小于 2px，实际 $spread（$rights）", spread < 2f)

        val raggedSpread = (0 until raggedLayout.lineCount - 1)
            .map { raggedLayout.getLineRight(it) }
            .let { it.max() - it.min() }
        assertTrue(
            "不对齐时右缘必然参差（否则这条测试没有区分力），实际极差 $raggedSpread",
            raggedSpread > 2f,
        )
    }

    @Test
    fun `两端对齐不改变字符数`() {
        val text = "对齐只动字与字之间的空隙，不动文字本身。"
        val (plain, _) = layoutOf(text)
        val (justified, _) = layoutOf(text, theme.copy(justifyText = true))
        assertEquals(
            plain.sections.first().layout.text.length,
            justified.sections.first().layout.text.length,
        )
    }
}
