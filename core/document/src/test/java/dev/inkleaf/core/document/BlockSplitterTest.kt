package dev.inkleaf.core.document

import dev.inkleaf.core.anchoring.NormalizationProfiles
import dev.inkleaf.core.anchoring.TextNormalizer
import dev.inkleaf.core.model.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSplitterTest {

    private fun txt(source: String): SplitResult =
        BlockSplitter.splitPlainText(TextNormalizer.normalize(source, NormalizationProfiles.PLAIN_TEXT))

    private fun md(source: String): SplitResult =
        BlockSplitter.splitMarkdown(TextNormalizer.normalize(source, NormalizationProfiles.MARKDOWN))

    // ── 纯文本 ───────────────────────────────────────────────────────────────

    @Test
    fun `空文件没有块`() {
        assertEquals(0, txt("").blockCount)
        assertEquals(0, txt("\n\n\n").blockCount)
    }

    @Test
    fun `一行一段每行一个块`() {
        val r = txt("第一行\n第二行\n第三行")
        assertEquals(3, r.blockCount)
        assertEquals(0, r.blocks[0].absoluteStart)
        assertEquals("第一行", r.blocks[0].text)
        assertEquals("第二行", r.blocks[1].text)
        assertEquals("第三行", r.blocks[2].text)
    }

    @Test
    fun `空行被跳过但不影响块的绝对偏移`() {
        val source = "第一行\n\n\n第二行"
        val r = txt(source)
        assertEquals(2, r.blockCount)
        // 第二行在原文里的偏移是 6，块区间必须仍然指向 6，而不是被压缩成 4
        assertEquals(6, r.blocks[1].absoluteStart)
    }

    @Test
    fun `块区间落在归一化全文内且按顺序不重叠`() {
        val source = "第一章 起风\n\n他站在门口说，今天风很大。\n\n第二段。"
        val r = txt(source)
        var previousEnd = 0
        for (block in r.blocks) {
            assertTrue("块起点必须非负：${block.span}", block.absoluteStart >= 0)
            assertTrue("块必须有序且不重叠：${block.span} vs 上一个结束 $previousEnd", block.absoluteStart >= previousEnd)
            assertTrue("块终点必须不大于后继起点", block.absoluteEnd >= block.absoluteStart)
            previousEnd = block.absoluteEnd
        }
    }

    @Test
    fun `块文本等于归一化全文的切片`() {
        val source = "\u3000首行缩进的一段。\n\n其次一段。"
        val normalized = TextNormalizer.normalize(source, NormalizationProfiles.PLAIN_TEXT)
        val r = BlockSplitter.splitPlainText(normalized)
        // 这是整个锚定体系的地基：块文本必须是全文切片，否则坐标系就对不上了
        for (block in r.blocks) {
            assertEquals(normalized.text.substring(block.absoluteStart, block.absoluteEnd), block.text)
        }
    }

    @Test
    fun `行首全角缩进被保留在块文本里`() {
        // 中文 TXT 的段首缩进是内容的一部分，不能当空白裁掉
        val r = txt("\u3000\u3000这是缩进段落。")
        assertTrue(r.blocks[0].text.startsWith("\u3000"))
    }

    @Test
    fun `章节标题被识别并进入目录`() {
        val r = txt("第一章 起风\n\n他站在门口。\n\n第二章 落雨\n\n雨很大。")
        assertEquals(4, r.blockCount)
        assertEquals(BlockKind.HEADING, r.blocks[0].kind)
        assertEquals(1, r.blocks[0].headingLevel)
        assertEquals(2, r.toc.size)
        assertEquals("第一章 起风", r.toc[0].title)
        assertEquals("第二章 落雨", r.toc[1].title)
        assertEquals(0, r.toc[0].charOffset)
    }

    @Test
    fun `带句号的正文不会被误判为章节`() {
        // 句读是最有效的判据：标题不会有句号
        val r = txt("第一章里提到的那个人最后走了。\n\n正文。")
        assertEquals(BlockKind.PARAGRAPH, r.blocks[0].kind)
        assertEquals(0, r.toc.size)
    }

    @Test
    fun `超长行不会被误判为章节`() {
        val long = "第一章 " + "补".repeat(BlockSplitter.MAX_TXT_HEADING_CHARS)
        val r = txt(long)
        assertEquals(BlockKind.PARAGRAPH, r.blocks[0].kind)
    }

    @Test
    fun `章节标记后尾巴过长时不判为章节`() {
        val tail = "补".repeat(BlockSplitter.MAX_TXT_HEADING_TAIL_CHARS + 5)
        val r = txt("第一章 $tail")
        assertEquals(BlockKind.PARAGRAPH, r.blocks[0].kind)
    }

    @Test
    fun `英文与特名章节也能识别`() {
        assertEquals(BlockKind.HEADING, txt("Chapter 12").blocks[0].kind)
        assertEquals(BlockKind.HEADING, txt("序章").blocks[0].kind)
        assertEquals(BlockKind.HEADING, txt("楔子").blocks[0].kind)
        assertEquals(BlockKind.HEADING, txt("番外 一").blocks[0].kind)
    }

    @Test
    fun `CRLF 文档与 LF 文档切分结果一致`() {
        val lf = txt("第一章\n正文\n第二章\n正文")
        val crlf = txt("第一章\r\n正文\r\n第二章\r\n正文")
        assertEquals(lf.blockCount, crlf.blockCount)
        assertEquals(lf.blocks.map { it.text }, crlf.blocks.map { it.text })
        assertEquals(lf.toc.size, crlf.toc.size)
    }

    // ── Markdown ────────────────────────────────────────────────────────────

    @Test
    fun `标题层级与目录`() {
        val r = md("# 一级\n\n## 二级\n\n正文")
        assertEquals(BlockKind.HEADING, r.blocks[0].kind)
        assertEquals(1, r.blocks[0].headingLevel)
        assertEquals(2, r.blocks[1].headingLevel)
        assertEquals("一级", r.toc[0].title)
        assertEquals("二级", r.toc[1].title)
        assertEquals(2, r.toc.size)
    }

    @Test
    fun `标题标记被标为待隐藏且长度不变`() {
        val source = "## 二级标题"
        val normalized = TextNormalizer.normalize(source, NormalizationProfiles.MARKDOWN)
        val r = BlockSplitter.splitMarkdown(normalized)
        val block = r.blocks[0]
        // 关键：隐藏标记不能靠删字符，一旦删了，块内偏移整体平移，锚点全错
        assertEquals(source, block.text)
        assertEquals(1, block.markerSpans.size)
        assertEquals(CharSpan(0, 3), block.markerSpans[0])
    }

    @Test
    fun `缩进的标题标记位置正确`() {
        val r = md("   ### 缩进标题")
        assertEquals(BlockKind.HEADING, r.blocks[0].kind)
        assertEquals(3, r.blocks[0].headingLevel)
        // 标记从第 3 个字符开始（前两个是缩进）
        assertEquals(CharSpan(3, 7), r.blocks[0].markerSpans[0])
    }

    @Test
    fun `围栏代码块把内部内容判为代码`() {
        val r = md("```kotlin\n# 这不是标题\nval x = 1\n```\n\n正文")
        assertEquals(BlockKind.CODE_FENCE, r.blocks[0].kind)
        assertEquals(BlockKind.CODE, r.blocks[1].kind)
        assertEquals(BlockKind.CODE, r.blocks[2].kind)
        assertEquals(BlockKind.CODE_FENCE, r.blocks[3].kind)
        assertEquals(BlockKind.PARAGRAPH, r.blocks[4].kind)
        // 代码块里的 "# 这不是标题" 绝不能进目录
        assertEquals(0, r.toc.size)
    }

    @Test
    fun `波浪线围栏也能识别`() {
        val r = md("~~~\ncode\n~~~")
        assertEquals(BlockKind.CODE_FENCE, r.blocks[0].kind)
        assertEquals(BlockKind.CODE, r.blocks[1].kind)
        assertEquals(BlockKind.CODE_FENCE, r.blocks[2].kind)
    }

    @Test
    fun `引用行识别且标记待隐藏`() {
        val r = md("> 引用内容")
        assertEquals(BlockKind.QUOTE, r.blocks[0].kind)
        assertEquals(CharSpan(0, 2), r.blocks[0].markerSpans[0])
    }

    @Test
    fun `列表项识别且标记保持可见`() {
        val r = md("- 第一条\n- 第二条\n1. 有序项")
        assertEquals(BlockKind.LIST_ITEM, r.blocks[0].kind)
        assertEquals(BlockKind.LIST_ITEM, r.blocks[1].kind)
        assertEquals(BlockKind.LIST_ITEM, r.blocks[2].kind)
        // 列表标记是语义的一部分，不隐藏
        assertTrue(r.blocks[0].markerSpans.isEmpty())
    }

    @Test
    fun `分隔线识别`() {
        assertEquals(BlockKind.RULE, md("---").blocks[0].kind)
        assertEquals(BlockKind.RULE, md("* * *").blocks[0].kind)
        assertEquals(BlockKind.RULE, md("___").blocks[0].kind)
    }

    @Test
    fun `单独的图片行识别为图片块`() {
        assertEquals(BlockKind.IMAGE, md("![封面](cover.png)").blocks[0].kind)
    }

    @Test
    fun `Markdown 口径保留缩进`() {
        // Markdown 的语法依赖空白（4 空格缩进代码块），归一化不能折叠它
        val normalized = TextNormalizer.normalize("    indented code", NormalizationProfiles.MARKDOWN)
        assertEquals("    indented code", normalized.text)
    }

    @Test
    fun `按格式分派`() {
        val normalized = TextNormalizer.normalize("# 标题", NormalizationProfiles.MARKDOWN)
        assertEquals(BlockKind.HEADING, BlockSplitter.split(DocumentKind.MARKDOWN, normalized).blocks[0].kind)
        // 同样的文本按 TXT 处理时 "#" 只是普通字符
        val plain = TextNormalizer.normalize("# 标题", NormalizationProfiles.PLAIN_TEXT)
        assertEquals(BlockKind.PARAGRAPH, BlockSplitter.split(DocumentKind.TXT, plain).blocks[0].kind)
    }

    // ── 行切分 ──────────────────────────────────────────────────────────────

    @Test
    fun `lineSpans 覆盖全文且以换行为界`() {
        val spans = BlockSplitter.lineSpans("ab\ncd\ne")
        assertEquals(3, spans.size)
        assertEquals(CharSpan(0, 2), spans[0])
        assertEquals(CharSpan(3, 5), spans[1])
        assertEquals(CharSpan(6, 7), spans[2])
    }

    @Test
    fun `lineSpans 对以换行结尾的文本产生一个空的末区间`() {
        val spans = BlockSplitter.lineSpans("ab\n")
        assertEquals(2, spans.size)
        assertTrue(spans[1].isEmpty)
    }

    @Test
    fun `块路径可直接用作结构型选择器`() {
        val r = txt("a\nb")
        assertEquals("blk[0]", r.blocks[0].blockPath)
        assertEquals("blk[1]", r.blocks[1].blockPath)
        assertNotNull(r.blocks[0].span)
    }
}
