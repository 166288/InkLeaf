package dev.inkleaf.app

import dev.inkleaf.core.document.PdfAnnotationPlanner
import dev.inkleaf.core.document.PdfPageBox
import dev.inkleaf.core.ink.PdfWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNameTest {

    @Test
    fun `书名里的非法字符被替换_中文保留`() {
        assertEquals("三体-笔记.md", exportFileName("三体"))
        assertEquals("A_B_C_-笔记.md", exportFileName("A/B:C*"))
    }

    @Test
    fun `空标题有兜底名字`() {
        assertEquals("笔记-笔记.md", exportFileName("   "))
    }

    @Test
    fun `超长标题被截断但仍然可读`() {
        val name = exportFileName("长".repeat(100))
        assertEquals(60 + "-笔记.md".length, name.length)
        assertFalse(name.contains("/"))
    }

    @Test
    fun `PNG 文件名带页码_没有页码时只写书名`() {
        assertEquals("三体-第3页.png", pagePngFileName("三体", "第3页"))
        assertEquals("三体.png", pagePngFileName("三体", null))
        assertEquals("三体.png", pagePngFileName("三体", "  "))
        // 滚动模式没有页身份，名字里也不该硬塞一个页码。
        assertFalse(pagePngFileName("三体", null).contains("页"))
    }

    /**
     * 滚动模式的"第几屏"。
     *
     * 它的用途只有一个：**让连着的两次导出不撞名**。滚动模式没有页号，
     * 之前两次导出都叫 `书名.png`，第二张直接覆盖第一张 —— 用户看到的是
     * "我明明导了两次，相册里只有一张"。
     */
    @Test
    fun `滚动模式按偏移算第几屏`() {
        assertEquals("第一屏（顶部）", 1, scrollScreenIndex(0, 3_008))
        assertEquals("还没滚过一整屏", 1, scrollScreenIndex(3_007, 3_008))
        assertEquals("正好滚过一屏", 2, scrollScreenIndex(3_008, 3_008))
        assertEquals("滚了三屏半", 4, scrollScreenIndex(3_500 * 3, 3_008))
        assertEquals("负偏移（回弹过头）也算第一屏", 1, scrollScreenIndex(-40, 3_008))
    }

    @Test
    fun `量不出视口时按第一屏算_不除零`() {
        assertEquals(1, scrollScreenIndex(5_000, 0))
        assertEquals(1, scrollScreenIndex(5_000, -1))
    }

    @Test
    fun `导出成功那句话与导出的是页还是屏一致`() {
        // 名字和作用对不上的按钮，用户要按两次才敢相信它。
        assertEquals("已导出这一页的 PNG", PageUnit.PAGE.exportedMessage)
        assertEquals("已导出这一屏的 PNG", PageUnit.SCREEN.exportedMessage)
        assertFalse(PageUnit.SCREEN.exportedMessage.contains("页"))
        assertFalse(PageUnit.PAGE.exportedMessage.contains("屏"))
    }

    @Test
    fun `滚动模式导出的文件名与分页的不会互相覆盖`() {
        val paged = pagePngFileName("三体", "第3页")
        val scrolled = pagePngFileName("三体", "第3屏")
        assertFalse("两种模式的文件名必须不同", paged == scrolled)
        assertFalse("也不能退回到只有书名", scrolled == pagePngFileName("三体", null))
    }

    @Test
    fun `JSON 文件名与 Markdown 同一套清洗规则`() {
        assertEquals("A_B_-笔记.json", noteJsonFileName("A/B:"))
        assertEquals("笔记-笔记.json", noteJsonFileName(""))
    }

    @Test
    fun `PDF 另存文件名先清洗再加后缀`() {
        assertEquals("三体-annotated.pdf", pdfAnnotatedFileName("三体"))
        // 非法字符先被换成下划线，后缀仍然加在主名后面而不是扩展名后面。
        assertEquals("A_B_-annotated.pdf", pdfAnnotatedFileName("A/B:"))
        assertEquals("文档-annotated.pdf", pdfAnnotatedFileName("   "))
        // 书名里带点不能被当成扩展名截断。
        assertEquals("深入理解 (第2版)-annotated.pdf", pdfAnnotatedFileName("深入理解 (第2版)"))
    }

    @Test
    fun `写回结果的三句话互不相同且都说得清`() {
        val plan = PdfAnnotationPlanner.plan(
            pages = listOf(PdfPageBox(topDoc = 0f, widthPt = 600f, heightPt = 800f)),
            bands = emptyList(),
            inks = emptyList(),
            notes = emptyList(),
            bookmarks = listOf("b1"),
        )
        val unsupported = describePdfOutcome(PdfWriteOutcome.Unsupported("这台设备的系统 PDF 扩展版本太低。"))
        val failed = describePdfOutcome(PdfWriteOutcome.Failed("写文件时出错：权限不足。"))
        assertTrue(unsupported, unsupported.contains("扩展版本"))
        assertTrue(failed, failed.contains("权限"))
        // 不支持与"重试"无关，文案里不能出现让人以为重试有用的字眼。
        assertFalse(unsupported, unsupported.contains("重试"))

        val written = describePdfOutcome(PdfWriteOutcome.Written(bytes = 2048, plan = plan))
        assertFalse("即使一条都没写成，也不能是空字符串", written.isBlank())
    }

    @Test
    fun `字节数按量级换单位`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes(1024L * 1024L * 3 / 2))
    }
}
