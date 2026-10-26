package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文搜索的测试。
 *
 * 这里最要紧的一组断言是**偏移**而不是"找到了几处"：命中的区间要直接拿去
 * 跳转与高亮，偏移差一位，用户看到的症状就是"跳过去没高亮到那个词"，
 * 而这类错误在本机（没有设备）永远不会以"看起来不对"的形式暴露出来。
 */
class TextSearchTest {

    private fun hits(text: String, query: String, ignoreCase: Boolean = true, maxHits: Int = TextSearch.MAX_HITS) =
        TextSearch.findIn(text, query, ignoreCase, maxHits)

    @Test
    fun `找到全部命中且区间是半开的`() {
        val result = hits("abcabc", "abc")

        assertEquals(2, result.count)
        assertEquals(0, result.hits[0].start)
        assertEquals(3, result.hits[0].endExclusive)
        assertEquals(3, result.hits[1].start)
        assertEquals(6, result.hits[1].endExclusive)
        assertFalse(result.truncated)
    }

    @Test
    fun `默认忽略大小写且命中长度按查询串算`() {
        // 大小写不同也要命中，但区间必须按**文本里的**那一段算长度：
        // 若按查询串的长度算，在"长小的字符长度不同"的语言里会切错。
        val result = hits("AbCabc", "abc")

        assertEquals(2, result.count)
        assertEquals(3, result.hits[0].length)
        assertEquals(3, result.hits[1].length)
    }

    @Test
    fun `区分大小写时只命中完全一致的`() {
        val result = hits("AbCabc", "abc", ignoreCase = false)

        assertEquals(1, result.count)
        assertEquals(3, result.hits[0].start)
    }

    @Test
    fun `重叠的命中算多处`() {
        // 不算重叠的话，用户会遇到"明明有两处却只找到一处"。
        val result = hits("aaa", "aa")

        assertEquals(2, result.count)
        assertEquals(0, result.hits[0].start)
        assertEquals(1, result.hits[1].start)
    }

    @Test
    fun `空查询与纯空白查询不返回全书`() {
        // "空查询匹配所有位置"是最容易顺手写出来的错误，而它的症状是界面直接卡死。
        assertEquals(0, hits("abcabc", "").count)
        assertEquals(0, hits("abcabc", "   ").count)
        assertEquals(0, hits("abcabc", "\n\t").count)
        assertEquals(0, hits("", "abc").count)
    }

    @Test
    fun `查询首尾空白被裁掉而内部空白折叠成一个半角空格`() {
        // 与文档的归一化口径对齐：从别处复制来的文字常带换行与多余空格，
        // 而文档里那些位置是**一个**半角空格。
        val text = "前面 后面"
        assertEquals(1, hits(text, "  前面 后面  ").count)
        assertEquals(1, hits(text, "前面\t后面").count)
        assertEquals("两个空格也要能搜到一个空格的正文", 1, hits(text, "前面  后面").count)
        assertEquals("反过来不成立：正文里没有空格就不该命中", 0, hits("前面后面", "前面 后面").count)
    }

    @Test
    fun `全角空格按字符匹配而不是被折叠成半角`() {
        // 文档的归一化刻意保留 U+3000（方案 §5.9 的空白口径），
        // 查询若把它折成半角空格，中日文里用全角空格排版的行就永远搜不到。
        val text = "第一章\u3000内容"
        assertEquals(1, hits(text, "第一章\u3000内容").count)
        assertEquals(0, hits(text, "第一章 内容").count)
    }

    @Test
    fun `超过上限时截断并说明还有更多`() {
        val result = hits("a a a a a", "a", maxHits = 2)

        assertEquals(2, result.count)
        assertTrue("应当如实说明还有更多命中", result.truncated)
    }

    @Test
    fun `命中数恰好等于上限时不算截断`() {
        val result = hits("a a", "a", maxHits = 2)

        assertEquals(2, result.count)
        assertFalse("恰好搜完就不该说'还有更多'", result.truncated)
    }

    @Test
    fun `找不到时返回空且不截断`() {
        val result = hits("abcabc", "xyz")

        assertTrue(result.isEmpty)
        assertFalse(result.truncated)
    }

    @Test
    fun `命中落在文本末尾时不越界`() {
        val result = hits("aaa", "aa")

        assertEquals(3, result.hits[1].endExclusive)
        assertEquals(3, "aaa".length)
    }

    @Test
    fun `上下文摘要给出前后文与命中串本身`() {
        val context = TextSearch.contextOf("前面的话命中后面的话", TextSearch.Hit(4, 6), contextChars = 3)

        assertEquals("面的话", context.before)
        assertEquals("命中", context.match)
        assertEquals("后面的", context.after)
    }

    @Test
    fun `上下文摘要把换行折成空格`() {
        // 界面上一行要放得下；文档的归一化文本里换行与块边界同义，直接显示会断开成两行。
        val text = "上一段\n命中\n下一段"
        val context = TextSearch.contextOf(text, TextSearch.Hit(4, 6), contextChars = 4)

        assertTrue("摘要里不该出现换行：'${context.before}'", !context.before.contains('\n'))
        assertEquals("上一段 命中 下一段", context.before + context.match + context.after)
    }

    @Test
    fun `上下文摘要不切出半个代理对`() {
        // 截出半个 emoji 会显示成一个方块字符，看起来像"文档里有乱码"。
        val text = "a😀命中😀b"
        val hit = TextSearch.Hit(start = 3, endExclusive = 5)

        // 前后各两个字：起点正好落在第二个 emoji 的第一个码元上，终点正好把后一个 emoji 取全。
        val wide = TextSearch.contextOf(text, hit, contextChars = 2)
        assertEquals("😀", wide.before)
        assertEquals("😀", wide.after)

        // 前后各一个字：起点落在半个 emoji 上，于是**让出一位**把整个 emoji 收进来；
        // 终点落在半个 emoji 上，于是**不给**而不是给半个。
        val narrow = TextSearch.contextOf(text, hit, contextChars = 1)
        assertEquals("宁可少给一个字，也不显示半个码点", "😀", narrow.before)
        assertEquals("", narrow.after)
    }

    @Test
    fun `命中偏移能指回它所在的块`() {
        // 搜索跑在**归一化文本**上，命中偏移必须与"块 / 行盒 / 锚点"共用同一套坐标。
        // 若哪一天有人改成在原文上搜索，这条会先失败。
        val session = DocumentCodec.buildSession(
            "search-doc",
            DocumentKind.TXT,
            "第一段写了一些话\n第二段里有一个特别的目标词\n第三段也是话",
        )
        val result = TextSearch.findIn(session.normalized.text, "特别的目标词")

        assertEquals(1, result.count)
        assertEquals(1, session.blockIndexOfChar(result.hits[0].start))
        assertEquals(
            "特别的目标词",
            session.normalized.text.substring(result.hits[0].start, result.hits[0].endExclusive),
        )
    }
}
