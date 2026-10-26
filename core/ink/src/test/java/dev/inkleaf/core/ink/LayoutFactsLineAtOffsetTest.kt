package dev.inkleaf.core.ink

import dev.inkleaf.core.model.LineBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * "全文偏移 → 那一行"的测试（手动重挂锚点的入口）。
 *
 * 这个换算之所以必须由排版事实提供：搜索给的是**字符偏移**，重挂要的是**行盒**。
 * 界面自己推算"偏移大概在第几行"就会与渲染层给出不同答案，
 * 而症状恰好是最刺眼的那种："提示说挂到这一行，笔迹出现在别处"。
 *
 * 边界（空行、越界）直接测 [LineLookup.at]：它们没法通过包一层 `LayoutFacts`
 * 造出来 —— 接口委托会把默认实现转发给内层对象，见 [LayoutFacts.lineAtOffset]。
 */
class LayoutFactsLineAtOffsetTest {

    private val doc = TestDoc(3)

    private fun rangeOf(line: LineBox): IntRange? = doc.absoluteRangeOf(line)

    @Test
    fun `偏移落在某一行里就返回那一行`() {
        val lines = doc.lines()
        val facts = doc.facts(lines)

        assertSame(lines[0], facts.lineAtOffset(0))
        val second = doc.full.indexOf(doc.texts[1])
        assertSame("第二块首字符的偏移必须命中第二块", lines[1], facts.lineAtOffset(second))
        assertSame(
            "行中间任意一个字符也要命中同一行",
            lines[1],
            facts.lineAtOffset(second + 2),
        )
    }

    @Test
    fun `偏移落在换行符上时命中等距的前一行`() {
        val lines = doc.lines()
        val facts = doc.facts(lines)
        // 第 0 块区间是 [0, len-1]，换行符的下标是 len，第 1 块从 len+1 起。
        // 到两边距离相等 ⇒ 取先出现的那一行（规则见 `LineLookup.at`）。
        val newline = doc.texts[0].length

        assertSame(lines[0], facts.lineAtOffset(newline))
    }

    @Test
    fun `偏移超出全文时退到最近的一行而不是崩`() {
        val lines = doc.lines()
        val facts = doc.facts(lines)

        assertSame(
            "末行之后没有任何文字，最近的只能是末行",
            lines[2],
            facts.lineAtOffset(doc.full.length + 1_000),
        )
        assertSame(
            "负数偏移同样退到行首那一行（防御，不是常态）",
            lines[0],
            facts.lineAtOffset(-5),
        )
    }

    @Test
    fun `空行不参与命中但它的邻居照旧可用`() {
        val lines = doc.lines()
        val blank = lines[1]
        val blankAware = { line: LineBox -> if (line === blank) null else rangeOf(line) }

        val third = doc.full.indexOf(doc.texts[2])
        assertSame(
            "空行不能把命中吃掉：第三块仍要命中自己",
            lines[2],
            LineLookup.at(third, lines, blankAware),
        )
        assertSame(
            "落在空行区间里的偏移退到最近的有文字的那一行，而不是 null",
            lines[2],
            LineLookup.at(third - 1, lines, blankAware),
        )
    }

    @Test
    fun `一行都取不到绝对区间时返回 null 而不是猜一行`() {
        val lines = doc.lines()

        assertNull(
            "没有可用的行就必须明确说没有，而不是挑一行出来",
            LineLookup.at(0, lines) { null },
        )
    }

    @Test
    fun `包一层 LayoutFacts 不会改变命中结果`() {
        // `:app` 里"模拟正文换了一版"正是这么做的（`object : LayoutFacts by facts`）。
        // 接口委托会把 lineAtOffset 转发给内层对象，只要包装不改行盒，
        // 命中结果就必须与内层完全一致 —— 这条断言把这个语义钉住。
        val lines = doc.lines()
        val inner = doc.facts(lines)
        val wrapped = object : LayoutFacts by inner {
            override val fingerprint: String = "fp-换了一版"
        }

        val offset = doc.full.indexOf(doc.texts[2])
        assertSame(inner.lineAtOffset(offset), wrapped.lineAtOffset(offset))
        assertEquals("包一层换的只是指纹", "fp-换了一版", wrapped.fingerprint)
    }
}
