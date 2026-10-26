package dev.inkleaf.core.document

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 另存文件名的规则。
 *
 * 一个纯字符串函数为什么要单测：它就是"我导出的文件在哪"这件事的全部答案，
 * 而错的方式都很安静 —— 名字变成 `书名-annotated.pdf.pdf`、或者把书名里的点
 * 当成扩展名截断（`第 3 章.上` 会变成 `第 3 章-annotated.pdf`），用户只会觉得
 * "这个软件导出的文件名很怪"，不会报 bug。
 *
 * 同时钉住一条产品决策：**默认另存，不就地写回**。源文件永不被写，这是
 * "一次自动备份"在另存语义下的最强形式（见 [PdfExportTarget] 的注释）。
 */
class PdfExportTargetTest {

    @Test
    fun `普通书名追加后缀`() {
        assertEquals("书-annotated.pdf", PdfExportTarget.suggestedName("书.pdf"))
    }

    @Test
    fun `扩展名大小写不影响判断`() {
        assertEquals("Paper-annotated.pdf", PdfExportTarget.suggestedName("Paper.PDF"))
    }

    @Test
    fun `没有扩展名时把整个名字当主名`() {
        assertEquals("无扩展名-annotated.pdf", PdfExportTarget.suggestedName("无扩展名"))
    }

    @Test
    fun `书名里有点不能被当成扩展名截断`() {
        // "第 3 章.上.pdf"：最后一个点之后是 pdf，所以主名是"第 3 章.上"。
        // 按第一个点切会得到"第 3 章-annotated.pdf"，用户会以为导错了书。
        assertEquals("第 3 章.上-annotated.pdf", PdfExportTarget.suggestedName("第 3 章.上.pdf"))
    }

    @Test
    fun `以点开头的隐藏文件名不被截成空串`() {
        assertEquals(".hidden-annotated.pdf", PdfExportTarget.suggestedName(".hidden"))
    }

    @Test
    fun `空书名给一个能用的兜底名字`() {
        assertEquals("文档-annotated.pdf", PdfExportTarget.suggestedName("   "))
    }

    @Test
    fun `前后空白会被去掉`() {
        assertEquals("书-annotated.pdf", PdfExportTarget.suggestedName("  书.pdf  "))
    }

    @Test
    fun `带空格与括号的书名原样保留`() {
        // 刻意不做"清洗非法字符"：清洗规则总会在某个名字上错，而错的表现是
        // "导出的文件名和我给的不一样"，没人能复现。SAF 允许这些字符。
        assertEquals(
            "深入理解 (第2版)-annotated.pdf",
            PdfExportTarget.suggestedName("深入理解 (第2版).pdf"),
        )
    }
}
