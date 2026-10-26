package dev.inkleaf.core.document

import java.io.Closeable

/**
 * 文本层里的一行（坐标为**页内像素**，原点 = 页面左上角）。
 *
 * 为什么坐标是"页内"而不是"文档"：固定版式没有重排，页面不会变；页内坐标因此是
 * 一个永远稳定的量。文档坐标由页序累加得到（见 [PdfDocumentSession.pageTopPx]），
 * 两者只差一个页偏移 —— 把页内坐标存进文本层、把页偏移留给会话，是因为
 * "这一页在整篇里排第几"会随页序变化，而"这行在纸上哪"不会。
 *
 * [baselineYPx] 与 [fontSizePx] 一起决定笔迹的纵向基准与 em 换算（L2 锚定需要它们）：
 * 平台取不到真实基线时，实现方按 `bottom - fontSize * DESCENT_RATIO` 估算，
 * 并且**必须**在 `evidence` 里说出来 —— 估算的基线会让笔迹有半个字的偏移。
 */
data class PdfTextLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val baselineYPx: Float,
    val fontSizePx: Float,
) {
    /** 行盒的宽高（平台给出的往往就是它，不需要另算）。 */
    val widthPx: Float get() = right - left
    val heightPx: Float get() = bottom - top
}

/** 一页：页内尺寸 + 文本层。没有文本层的页（扫描页）[lines] 为空。 */
data class PdfPageFacts(
    /** 页序号，从 0 开始，且必须与它在列表里的位置一致（会话会校验）。 */
    val index: Int,
    val widthPx: Float,
    val heightPx: Float,
    val lines: List<PdfTextLine> = emptyList(),
    /**
     * 平台给得出文字、却给不出包围盒时的整页文本（见 `PlatformPdfPageSource` 的类注释）。
     *
     * 正常情况下它是 null，[text] 由 [lines] 拼出 —— 两条路必须给出**同一份文字**，
     * 否则「扫描件判定」与「行盒」会各说一套。行盒为空而文字非空时，这一页仍然算
     * **有文本层**（能搜索、能判断不是扫描件），只是不能按行锚定。
     */
    val textOverride: String? = null,
) {
    /** 这一页的纯文本。扫描件判定、归一化、指纹都用它，见 `ScannedPdfPolicy`。 */
    val text: String get() = textOverride ?: lines.joinToString("\n") { it.text }
}

/**
 * PDF 页面与文本层的来源 —— **平台 PDF 阅读器的唯一接缝**。
 *
 * 为什么要有这条接缝：解析 PDF 只能靠平台的 `PdfRenderer`（内部是 pdfium），
 * 而它在本机**完全不可验证**（没有设备，Robolectric 也没有它的影子实现，
 * 见 design/M5 记录 §8）。把"取页"抽成一个接口之后：
 *
 *   - 会话、行盒、坐标、锚点这一整层（也就是最容易出错、最需要测试的那一层）
 *     可以用假页源在 JVM 上完整钉死；
 *   - 平台实现只剩"把 API 的数字搬出来"这一件事，而它错了也只错在数字上，
 *     不会错在坐标系上。
 *
 * 这一点与 M3 的 `LayoutFacts` 是同一条经验：**把不可验证的东西挤到最薄的一层**。
 *
 * 实现方**必须**保证 [page] 的返回是稳定的（同一 [pageCount] 下同序同内容）：
 * 指纹与锚点偏移都建立在它之上。
 */
interface PdfPageSource : Closeable {

    /** 页数。为 0 表示文件里没有页面（文件坏了，不是扫描件 —— 见 `ScannedPdfPolicy`）。 */
    val pageCount: Int

    /**
     * 取第 [index] 页。
     *
     * 实现方应在此处把该页的文本层一并取出来（渲染与取文本可以是同一次打开）；
     * 取不到文本层时返回 `lines = emptyList()` 的页，而**不是**抛异常 ——
     * 扫描件是正常文件，不是错误。
     */
    fun page(index: Int): PdfPageFacts

    /**
     * 供界面显示的文本层来路说明（例如"平台 PdfRendererPreV 的 selectContent"
     * 或"平台没给出整页行盒，已退到按页定位"）。
     *
     * 之所以要带出来：方案 §3.4 第 3 条明确记着"平台能不能给出整页行盒"是
     * **未验证**的，所以这件事必须由实现方如实上报，而不是由上层猜。
     */
    val textLayerEvidence: String

    /**
     * **本机/本实现**能不能读文本层。
     *
     * 它与"这份文件里有没有文本层"是两件事，必须分开：
     * 平台直到 Android 15（API 35）才有 `PdfRenderer.Page.getTextContents()`，
     * 在 Android 12 上我们读不到文本层，但文件完全可能是文本型 PDF。
     * 导入闸门只有在 `canReadTextLayer = true` 时才有资格判"扫描件"；
     * 否则只能提示"本机读不到文本层"（见 `PlatformPdfPageSource` 的类注释）。
     *
     * 默认 true 是为了让测试用的假页源不必关心这件事；真实实现必须如实上报。
     */
    val canReadTextLayer: Boolean get() = true

    /**
     * 打开一份 PDF 字节。
     *
     * 刻意吃 `ByteArray` 而不是 `File`/`Uri`：与 `DocumentCodec.sessionFromBytes`
     * 保持同一形状，于是"导入时"与"打开时"走的是同一个入参，
     * 不可能出现"导入能解析、打开解析不了"（M4.2 的接缝）。
     */
    fun interface Factory {
        fun open(bytes: ByteArray): PdfPageSource
    }
}

/**
 * PDF 解析层的失败。每一种都要能在界面上给出一句人话，理由同 `EpubFormatException`：
 * 把"这条没法支持"与"这个文件坏了"分开，用户才知道该换文件还是换设备。
 */
class PdfFormatException(
    val failure: Failure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Failure {
        /** 没有提供页源。导入与打开**都必须**提供它，缺了就是接线漏了。 */
        NO_PAGE_SOURCE,

        /** 文件读不出来（损坏、加密、根本不是 PDF）。 */
        BROKEN,

        /**
         * 解析结果内部不一致（页序号与位置对不上、行与块映射对不上）。
         *
         * 这一类的处置是**拒绝**而不是"尽力而为"：PDF 的锚点坐标全部由这套映射推出，
         * 映射错了的症状是"笔迹画在别的行上"，而用户会以为是自己记错了位置。
         */
        INCONSISTENT,
    }
}
