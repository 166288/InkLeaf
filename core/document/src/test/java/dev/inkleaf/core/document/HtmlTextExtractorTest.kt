package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizationProfiles
import dev.inkleaf.core.anchoring.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XHTML → 纯文本的行为契约。
 *
 * 这些断言不是"顺手写的边界"，而是**锚点坐标系的定义**：提取结果变了，
 * 所有 EPUB 笔记的偏移都会跟着变。因此每条规则都必须有一个测试钉住。
 */
class HtmlTextExtractorTest {

    private fun text(html: String) = HtmlTextExtractor.extract(html).text

    // ── 块结构 ──────────────────────────────────────────────────────────────

    @Test
    fun `每个块级元素占一行且不产生空行`() {
        val result = HtmlTextExtractor.extract(
            """
            <html><body>
              <h1>标题</h1>
              <p>第一段</p>
              <div></div>
              <p>   </p>
              <p>第二段</p>
            </body></html>
            """.trimIndent(),
        )
        assertEquals("标题\n第一段\n第二段", result.text)
        assertEquals("标题", result.firstHeading)
        assertEquals(1, result.firstHeadingLevel)
    }

    @Test
    fun `行内标签不换行且连续空白折叠为一个半角空格`() {
        assertEquals("a b c", text("<p>a  <b>b</b>\n\t<i>c</i></p>"))
        assertEquals("甲 乙", text("<p>甲\n乙</p>"))
    }

    @Test
    fun `br 与 hr 等价于换行`() {
        assertEquals("a\nb", text("<p>a<br>b</p>"))
        assertEquals("a\nb", text("<p>a<br/>b</p>"))
        assertEquals("a\nb", text("<p>a<hr>b</p>"))
    }

    @Test
    fun `列表项与表格单元格各自成行`() {
        assertEquals("甲\n乙", text("<ul><li>甲</li><li>乙</li></ul>"))
        assertEquals("a\nb", text("<table><tr><td>a</td><td>b</td></tr></table>"))
    }

    @Test
    fun `未闭合的块级标签不会吞掉后面的内容`() {
        // 现实里最常见的"不是合法 XML"：HTML5 序列化
        assertEquals("a\nb\nc", text("<p>a<p>b<p>c"))
    }

    @Test
    fun `pre 保留真实换行`() {
        assertEquals("line1\nline2", text("<pre>line1\nline2</pre>"))
    }

    // ── 内容取舍 ────────────────────────────────────────────────────────────

    @Test
    fun `脚本样式注音矢量图不进正文`() {
        val result = HtmlTextExtractor.extract(
            "<p>甲<script>var x=1</script><style>p{}</style>" +
                "<ruby>漢<rt>かん</rt></ruby>乙</p>" +
                "<svg><text>图</text></svg><math><mi>x</mi></math>",
        )
        assertEquals("甲漢乙", result.text)
        assertTrue("脚本/样式/矢量图/公式应计入跳过数，实际=${result.skippedElements}", result.skippedElements >= 4)
    }

    @Test
    fun `图片单独占一行并记住地址`() {
        val result = HtmlTextExtractor.extract("<p>前<img src=\"a.png\" alt=\"图\"/>后</p>")
        // 图片前后各断开一次：图是块级的，混在段落里会把那一行的行高扯开。
        assertEquals("前\n$IMAGE_PLACEHOLDER\n后", result.text)
        assertEquals(1, result.imageCount)
        assertEquals(listOf("a.png"), result.images)
    }

    @Test
    fun `没有地址的图片只计数不留占位`() {
        // 留一个永远填不上的占位，等于在书里留一个空格子 —— 那比不显示更像故障。
        val result = HtmlTextExtractor.extract("<p>前<img alt=\"无名\"/>后</p>")
        assertEquals("前后", result.text)
        assertEquals(1, result.imageCount)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `单引号与懒加载地址都认得`() {
        val quoted = HtmlTextExtractor.extract("<img src='单引号.png'/>")
        assertEquals(listOf("单引号.png"), quoted.images)
        val lazy = HtmlTextExtractor.extract("<img data-src=\"懒加载.png\"/>")
        assertEquals(listOf("懒加载.png"), lazy.images)
    }

    @Test
    fun `标题文本与正文里的那一行完全一致`() {
        // 目录项要能被用来对回正文，所以标题里的空白不能被吃掉
        val result = HtmlTextExtractor.extract("<h1>第一章  起风</h1><p>正文</p>")
        assertEquals("第一章 起风", result.firstHeading)
        assertTrue("标题必须与它所在的那一行逐字一致", result.text.startsWith(result.firstHeading!!))
    }

    @Test
    fun `没有标题时首标题为空且层级为 0`() {
        val result = HtmlTextExtractor.extract("<p>只有正文</p>")
        assertEquals("只有正文", result.text)
        assertNull(result.firstHeading)
        assertEquals(0, result.firstHeadingLevel)
    }

    @Test
    fun `首标题取文档里第一个标题而不是层级最高的那个`() {
        val result = HtmlTextExtractor.extract("<h2>二</h2><h1>一</h1>")
        assertEquals("二", result.firstHeading)
        assertEquals(2, result.firstHeadingLevel)
    }

    // ── 实体与转义 ──────────────────────────────────────────────────────────

    @Test
    fun `命名与数字实体被解码`() {
        assertEquals("a & b", text("<p>a &amp; b</p>"))
        assertEquals("<x>", text("<p>&lt;x&gt;</p>"))
        assertEquals("A中", text("<p>&#65;&#x4e2d;</p>"))
        assertEquals("“引”", text("<p>&ldquo;引&rdquo;</p>"))
    }

    @Test
    fun `nbsp 参与空白折叠但全角空格保留`() {
        assertEquals("a b", text("<p>a&nbsp;b</p>"))
        assertEquals("a　b", text("<p>a\u3000b</p>"))
        // 段首全角空格是中文缩进，必须留下
        assertEquals("　　缩进段", text("<p>&#x3000;&#x3000;缩进段</p>"))
    }

    @Test
    fun `不认识的命名实体原样保留而不是被丢掉`() {
        assertEquals("caf&eacute;", text("<p>caf&eacute;</p>"))
    }

    @Test
    fun `属性值里的尖括号不会让标签解析错位`() {
        assertEquals("x", text("""<a href="a" title="a > b">x</a>"""))
    }

    @Test
    fun `落单的尖括号当正文`() {
        assertEquals("a < b", text("<p>a < b</p>"))
    }

    @Test
    fun `注释与 CDATA`() {
        assertEquals("a", text("<!-- 注释 --><p>a</p>"))
        assertEquals("a<b", text("<p><![CDATA[a<b]]></p>"))
    }

    // ── 与归一化口径的关系 ──────────────────────────────────────────────────

    @Test
    fun `提取结果在 PLAIN_TEXT 口径下近似恒等`() {
        // 这条是 EPUB 走 TXT 下游管线的前提：提取时的清理规则与归一化口径一致，
        // 因此章节偏移不会在归一化这一步漂移。
        val html = "<h1>第一章</h1><p>正文一 <b>加粗</b>&nbsp;结束。</p><p>正文二。</p>"
        val extracted = text(html)
        val normalized = TextNormalizer.normalize(extracted, NormalizationProfiles.PLAIN_TEXT)
        assertEquals(extracted, normalized.text)
    }

    @Test
    fun `空输入`() {
        val result = HtmlTextExtractor.extract("")
        assertEquals("", result.text)
        assertEquals(0, result.imageCount)
    }
}
