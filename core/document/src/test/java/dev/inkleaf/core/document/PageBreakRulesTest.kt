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
 * 版面规则的分页测试（M33）：寡行、孤行、标题不与后文分离。
 *
 * ## 为什么这些断言写成"性质"而不是"第几页第几行"
 *
 * 页码与行号是**排版参数的函数**：换一个字号的版本、换一台机器，
 * 数字全变。写死数字的测试会在每次调参时红一遍，然后被人随手改掉 ——
 * 那时它就不再防任何东西了。
 *
 * 这里的断言都是"无论怎么排都该成立的形状"：
 * 页尾不许是某段的第一行、页首不许是某段的最后一行、页尾不许是标题。
 * 它们直接对应读者能看见的毛病，也不依赖具体数字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageBreakRulesTest {

    private val theme = DocumentTheme.light(textSizePx = 44f, lineSpacingMultiplier = 1.35f)

    /** 一页大约放 6 行：够小，边界足够多，规则不会因为"恰好没有边界"而空转。 */
    private fun metrics() = PageLayoutMetrics(
        contentWidthPx = 900,
        contentHeightPx = 700,
        marginLeftPx = 0f,
        marginRightPx = 0f,
        marginTopPx = 0f,
        marginBottomPx = 0f,
    )

    private fun session(text: String) =
        DocumentCodec.buildSession("break-rules", DocumentKind.TXT, text)

    /**
     * 造一份"每段行数不一"的正文。
     *
     * 段落长度必须参差：全都一样长时每页恰好卡在段落边界上，
     * 三条规则一次都不会触发，测试就变成了空断言。
     */
    private fun book(paragraphs: Int, longEvery: Int = 7): String =
        (1..paragraphs).joinToString("\n") { i ->
            val body = "第 $i 段：" + "这是用来把段落撑成多行的正文，".repeat(if (i % longEvery == 0) 4 else 2)
            body.trimEnd('，') + "。"
        }

    /** 全局行 → 段号。规则全靠它判断"这一行是段首还是段末"。 */
    private fun lineBlocks(layout: ReflowableTextLayout, session: DocumentSession): List<IntArray> =
        layout.sections.map { section ->
            IntArray(section.lineCount) { line ->
                session.blockIndexOfChar(section.charStart + section.layout.getLineStart(line))
            }
        }

    /**
     * (段号, 行号) → 全文行号。
     *
     * 规则要比较"这一行在不在那一段的开头"，而在多段布局里 (段号, 行号) 是两个数，
     * 直接比不成大小。换成一条连续的全局行号之后，所有比较都回到整数比较 ——
     * 段内行号只在段内有意义，全局行号才是有序的。
     */
    private fun globalLine(layout: ReflowableTextLayout, section: Int, line: Int): Int {
        var base = 0
        for (i in 0 until section) base += layout.sections[i].lineCount
        return base + line
    }

    @Test
    fun `没有一页以某段的第一行结尾`() {
        val doc = session(book(40))
        val layout = doc.layout(metrics(), theme)
        val blocks = lineBlocks(layout, doc)
        val firstLineOfBlock = HashMap<Int, Int>()
        val lastLineOfBlock = HashMap<Int, Int>()
        for (s in blocks.indices) {
            for (line in blocks[s].indices) {
                val b = blocks[s][line]
                val g = globalLine(layout, s, line)
                firstLineOfBlock.putIfAbsent(b, g)
                lastLineOfBlock[b] = g
            }
        }

        for (page in layout.pages) {
            val lastSpan = page.spans.last()
            val lastLine = lastSpan.lineEndExclusive - 1
            val block = blocks[lastSpan.sectionIndex][lastLine]
            val g = globalLine(layout, lastSpan.sectionIndex, lastLine)
            val isFirstLine = firstLineOfBlock.getValue(block) == g
            // 这一段在本页之后还有行吗？
            val continues = lastLineOfBlock.getValue(block) > g
            val singleLineParagraph = firstLineOfBlock.getValue(block) == lastLineOfBlock.getValue(block)
            assertTrue(
                "第 ${page.index} 页以段落 $block 的第一行结尾" +
                    "（该段还有下文，读者读到段首一句就要翻页）",
                !isFirstLine || !continues || singleLineParagraph || page.lineCount == 1,
            )
        }
    }

    @Test
    fun `没有一页以某段的最后一行开头`() {
        val doc = session(book(40))
        val layout = doc.layout(metrics(), theme)
        val blocks = lineBlocks(layout, doc)
        val firstLineOfBlock = HashMap<Int, Int>()
        val lastLineOfBlock = HashMap<Int, Int>()
        for (s in blocks.indices) {
            for (line in blocks[s].indices) {
                val b = blocks[s][line]
                val g = globalLine(layout, s, line)
                firstLineOfBlock.putIfAbsent(b, g)
                lastLineOfBlock[b] = g
            }
        }

        for (page in layout.pages) {
            val firstSpan = page.spans.first()
            val block = blocks[firstSpan.sectionIndex][firstSpan.lineStart]
            val here = globalLine(layout, firstSpan.sectionIndex, firstSpan.lineStart)
            val isLastLine = lastLineOfBlock.getValue(block) == here
            val startedEarlier = firstLineOfBlock.getValue(block) < here
            val singleLineParagraph = firstLineOfBlock.getValue(block) == lastLineOfBlock.getValue(block)
            assertTrue(
                "第 ${page.index} 页以段落 $block 的**最后一行**开头" +
                    "（本段的大部分在上一页，这里是孤零零的一行）",
                !isLastLine || !startedEarlier || singleLineParagraph,
            )
        }
    }

    @Test
    fun `标题不会落在页尾`() {
        // 每 5 段插一个标题，标题后紧跟正文。
        val text = (1..40).joinToString("\n") { i ->
            if (i % 5 == 0) {
                "第 ${i / 5} 章 标题"
            } else {
                "第 $i 段正文：" + "这一段用来把行撑满并产生折行，".repeat(2) + "。"
            }
        }
        val doc = session(text)
        val layout = doc.layout(metrics(), theme)
        val blocks = lineBlocks(layout, doc)
        for (page in layout.pages) {
            val lastSpan = page.spans.last()
            val block = blocks[lastSpan.sectionIndex][lastSpan.lineEndExclusive - 1]
            val kind = doc.blocks[block].kind
            assertTrue(
                "第 ${page.index} 页的页尾是标题（块 $block，kind=$kind）—— " +
                    "标题应当跟着它的正文一起走",
                kind != BlockKind.HEADING || page.lineCount == 1,
            )
        }
    }

    @Test
    fun `分页仍然覆盖全部行且不重叠`() {
        val doc = session(book(40))
        val layout = doc.layout(metrics(), theme)
        var covered = 0
        var previousBottom = -1
        for (page in layout.pages) {
            assertTrue("页不能为空", page.lineCount > 0)
            covered += page.lineCount
            assertTrue(
                "第 ${page.index} 页与上一页重叠",
                page.topPx >= previousBottom,
            )
            previousBottom = page.topPx + page.heightPx
        }
        assertEquals("所有行都必须恰好被一页覆盖", layout.lineCount, covered)
    }

    @Test
    fun `跨段的页照样能反查到它覆盖的每一行`() {
        // 段间距让排版段变短，一页常横跨好几个段。页与行的反查必须照样成立，
        // 否则笔迹定位会在段边界附近指到别的页。
        val doc = session(book(30))
        val layout = doc.layout(metrics(), theme.copy(paragraphSpacingEm = 0.4f))
        assertTrue(
            "样例里应当存在横跨多个排版段的页",
            layout.pages.any { it.spans.size > 1 },
        )
        for (page in layout.pages) {
            for (span in page.spans) {
                for (line in span.lineStart until span.lineEndExclusive) {
                    assertEquals(
                        "第 ${page.index} 页的第 $line 行反查不到这一页",
                        page.index,
                        layout.pageContainingLine(span.sectionIndex, line),
                    )
                }
            }
        }
    }
}
