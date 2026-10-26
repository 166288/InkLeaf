package dev.inkleaf.app

import java.io.ByteArrayOutputStream

/**
 * 设备测试用的 PDF 构造器。
 *
 * 为什么要手写 PDF 字节，而不是放一个二进制 fixture：
 *
 *   - 仓库里没有现成的文本型 PDF，而"从网上下一个"既不可复现也不该进仓库；
 *   - 这一版要测的是**平台** `PdfRenderer`：只有真的 PDF 才能让它工作，
 *     假页源（JVM 测试用的那种）在这里没有意义；
 *   - 手写之后，"第 1 页 10 行、第 2 页 2 行"这种差异是**测试自己**决定的，
 *     因此可以用"深色像素数变化"断言真的翻页了，而不是只信页码文字。
 *
 * 结构是最小可用集：Catalog → Pages → 每页一个 Page + 一个内容流 → 一个 Helvetica 字体。
 * 交叉引用表按实际字节偏移生成，`PdfRenderer` 对格式是挑剔的。
 */
object TestPdf {

    /** 文本型 PDF：每页若干行英文（Helvetica 只有拉丁字形，中文会变成空白）。 */
    fun textPdf(pageLineCounts: List<Int>): ByteArray {
        require(pageLineCounts.isNotEmpty())
        val builder = PdfBuilder()
        val pageIds = pageLineCounts.indices.map { 3 + 2 * it }
        val fontId = 3 + 2 * pageLineCounts.size

        builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add(
            "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] " +
                "/Count ${pageLineCounts.size} >>",
        )
        pageLineCounts.forEachIndexed { page, lineCount ->
            val contentId = pageIds[page] + 1
            builder.add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Resources << /Font << /F1 $fontId 0 R >> >> " +
                    "/Contents $contentId 0 R >>",
            )
            val lines = (1..lineCount).map { "Page ${page + 1} line $it: InkLeaf renders this PDF." }
            builder.addStream(contentStream(lines))
        }
        builder.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        return builder.build()
    }

    /**
     * 扫描件形状的 PDF：有页面、有内容（一块灰矩形），**一个文字都没有**。
     *
     * 平台在 API 35+ 上会如实报告"没有文本层"，导入闸门应当拦下来；
     * 在更低版本上我们读不到文本层（能力为 false），闸门不能据此判它是扫描件。
     */
    fun imageOnlyPdf(pageCount: Int = 2): ByteArray {
        require(pageCount > 0)
        val builder = PdfBuilder()
        val pageIds = (0 until pageCount).map { 3 + 2 * it }
        builder.add("<< /Type /Catalog /Pages 2 0 R >>")
        builder.add(
            "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] " +
                "/Count $pageCount >>",
        )
        pageIds.forEachIndexed { page, id ->
            val contentId = id + 1
            builder.add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Contents $contentId 0 R >>",
            )
            builder.addStream("0.6 0.6 0.6 rg 60 60 475 722 re f")
        }
        return builder.build()
    }

    private fun contentStream(lines: List<String>): String = buildString {
        append("BT\n/F1 18 Tf\n72 760 Td\n")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("0 -28 Td\n")
            append("(").append(escape(line)).append(") Tj\n")
        }
        append("ET")
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '(', ')', '\\' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }

    private class PdfBuilder {
        private val objects = ArrayList<ByteArray>()

        fun add(body: String) {
            objects.add(body.toByteArray(Charsets.ISO_8859_1))
        }

        fun addStream(stream: String) {
            val bytes = stream.toByteArray(Charsets.ISO_8859_1)
            add("<< /Length ${bytes.size} >>\nstream\n$stream\nendstream")
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val offsets = IntArray(objects.size + 1)
            out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
            objects.forEachIndexed { index, body ->
                offsets[index + 1] = out.size()
                out.write("${index + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
            val xrefOffset = out.size()
            out.write("xref\n0 ${objects.size + 1}\n".toByteArray(Charsets.ISO_8859_1))
            out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
            for (i in 1..objects.size) {
                out.write("%010d 00000 n \n".format(offsets[i]).toByteArray(Charsets.ISO_8859_1))
            }
            out.write(
                ("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n").toByteArray(Charsets.ISO_8859_1),
            )
            return out.toByteArray()
        }
    }
}
