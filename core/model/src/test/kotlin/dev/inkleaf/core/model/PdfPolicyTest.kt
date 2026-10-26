package dev.inkleaf.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 能力档与扫描件判定的测试。
 *
 * 这里断言的多数是**边界**而不是"某个输入输出对"：能力档的边界（S Ext 12/13/17/18）
 * 与扫描件判定的边界（几页有文字才算文本型）都是那种"错一档用户就以为功能坏了"
 * 的地方 —— 而这两种错误在本机都无法用真机发现（没有设备、S Extensions 也不可控），
 * 所以只能在这里钉死。
 */
class PdfPolicyTest {

    // ── 能力档 ────────────────────────────────────────────────────────────

    @Test
    fun `S Extensions 门槛的三个边界`() {
        // 12 与 13 之间是"能不能取到文本层"，17 与 18 之间是"能不能写回文件"。
        assertEquals(PdfBackend.RENDER_ONLY, PdfBackendPolicy.of(31, 12))
        assertEquals(PdfBackend.VIEW_ONLY, PdfBackendPolicy.of(31, 13))
        assertEquals(PdfBackend.VIEW_ONLY, PdfBackendPolicy.of(31, 17))
        assertEquals(PdfBackend.EDITABLE, PdfBackendPolicy.of(31, 18))
    }

    @Test
    fun `扩展版本再高也不会越过 Android 版本这一层`() {
        // S Extensions 从 API 31 起存在。低于它时读到的 0 必须解释为"没有这个能力"，
        // 而不是"扩展版本是 0 所以比 13 低"—— 两者结论相同，但来源不同：
        // 前者是"这个概念不存在"，后者是"这个概念存在但版本低"。
        assertEquals(PdfBackend.RENDER_ONLY, PdfBackendPolicy.of(30, 0))
        assertEquals(PdfBackend.RENDER_ONLY, PdfBackendPolicy.of(30, 99))
    }

    @Test
    fun `未来的扩展版本按最高档处理而不是回落`() {
        assertEquals(PdfBackend.EDITABLE, PdfBackendPolicy.of(37, 99))
    }

    @Test
    fun `只有最高档能写回文件`() {
        assertTrue(PdfBackendPolicy.capabilitiesOf(PdfBackend.EDITABLE).canWriteBackAnnotations)
        assertFalse(PdfBackendPolicy.capabilitiesOf(PdfBackend.VIEW_ONLY).canWriteBackAnnotations)
        assertFalse(PdfBackendPolicy.capabilitiesOf(PdfBackend.RENDER_ONLY).canWriteBackAnnotations)
    }

    @Test
    fun `仅渲染档明确没有文本层且说明里写清了这一点`() {
        // 这一档最容易被误解成"功能坏了"：用户会去点文字、去搜索。
        // 所以说明必须点出原因（系统扩展版本过低），而不是"暂不支持"。
        val capabilities = PdfBackendPolicy.capabilitiesOf(PdfBackend.RENDER_ONLY)

        assertFalse(capabilities.hasTextLayer)
        assertTrue(capabilities.notice.contains("文本"))
        assertTrue(capabilities.notice.contains("扩展"))
    }

    @Test
    fun `有文本层的两档都说明了自己能做什么`() {
        val viewOnly = PdfBackendPolicy.capabilitiesOf(PdfBackend.VIEW_ONLY)
        assertTrue(viewOnly.hasTextLayer)
        assertTrue(viewOnly.notice.contains("不能写回"))

        val editable = PdfBackendPolicy.capabilitiesOf(PdfBackend.EDITABLE)
        assertTrue(editable.hasTextLayer)
        assertTrue(editable.notice.contains("写回"))
    }

    // ── 扫描件判定 ────────────────────────────────────────────────────────

    private fun page(chars: Int) = "字".repeat(chars)

    @Test
    fun `每一页都有文本层时判为文本型`() {
        val assessment = ScannedPdfPolicy.assess(listOf(page(100), page(200), page(50)))

        assertEquals(PdfTextLayer.FULL, assessment.textLayer)
        assertEquals(3, assessment.pagesWithText)
        assertTrue(assessment.canAnchorToLines)
        assertFalse(assessment.needsNotice)
    }

    @Test
    fun `过半页有文本层时判为部分有文本层并说清哪几页不能批注`() {
        val assessment = ScannedPdfPolicy.assess(listOf(page(100), page(100), "", ""))

        assertEquals(PdfTextLayer.PARTIAL, assessment.textLayer)
        assertTrue("部分有文本层仍然可以按行定位", assessment.canAnchorToLines)
        assertTrue("必须提示用户", assessment.needsNotice)
        assertTrue("说明里要写出页数", assessment.reason.contains("2 页没有文本层"))
    }

    @Test
    fun `恰好一半有文本层算部分有文本层而不是扫描件`() {
        // 边界取"包含"：一半页能选中文字，判成扫描件会让那半本书也没法批注 ——
        // 代价比"提示一下有几页是图片"大。
        val assessment = ScannedPdfPolicy.assess(listOf(page(100), ""))

        assertEquals(PdfTextLayer.PARTIAL, assessment.textLayer)
    }

    @Test
    fun `几乎没有文本层时判为扫描件`() {
        val assessment = ScannedPdfPolicy.assess(listOf(page(100), page(0), page(0)))

        assertEquals(PdfTextLayer.NONE, assessment.textLayer)
        assertFalse(assessment.canAnchorToLines)
        assertTrue(assessment.reason.contains("扫描件"))
    }

    @Test
    fun `零星字符不算文本层`() {
        // 扫描页上常有页码/水印被 OCR 成几个字符。若把它们算成文本层，
        // 一本扫描书会被判成文本型，症状是"每页都有文本层却对不上行"。
        val assessment = ScannedPdfPolicy.assess(listOf("12", "第 3 页", "   ", "水印"))

        assertEquals(PdfTextLayer.NONE, assessment.textLayer)
        assertEquals(0, assessment.pagesWithText)
    }

    @Test
    fun `刚好达到下限的一页算有文本层`() {
        assertEquals(
            PdfTextLayer.FULL,
            ScannedPdfPolicy.assess(listOf(page(ScannedPdfPolicy.MIN_CHARS_PER_PAGE))).textLayer,
        )
        assertEquals(
            PdfTextLayer.NONE,
            ScannedPdfPolicy.assess(listOf(page(ScannedPdfPolicy.MIN_CHARS_PER_PAGE - 1))).textLayer,
        )
    }

    @Test
    fun `空白字符不计入文本层`() {
        val spaces = " ".repeat(100) + "\n".repeat(100)
        assertEquals(PdfTextLayer.NONE, ScannedPdfPolicy.assess(listOf(spaces)).textLayer)
    }

    @Test
    fun `没有页面时不假装判成了扫描件`() {
        val assessment = ScannedPdfPolicy.assess(emptyList())

        assertEquals(PdfTextLayer.NONE, assessment.textLayer)
        assertEquals(0, assessment.pages)
        // "没有页面"与"页面都是图片"是两回事：前者是文件坏了，后者是扫描件。
        assertFalse(assessment.reason.contains("扫描件"))
    }
}
