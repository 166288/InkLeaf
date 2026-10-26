package dev.inkleaf.core.model

/**
 * PDF 后端能力档。
 *
 * 为什么这台机器上要有一个"档"的概念：PDF 的能力**不由 Android 版本决定，而由
 * S Extensions 决定**，而 S Extensions 是经 Google Play 系统更新投递的 ——
 * 同一台 Android 13 的平板，可能拿到 S Ext 18，也可能只有 S Ext 8。
 * 因此"能不能选中文字、能不能把批注写回文件"必须在运行时判定，
 * 并且要在**导入与打开之前**判定：判断错了，用户看到的是"功能坏了"，
 * 而不是"你的设备没有这个能力"。
 *
 * 参考方案 §5.4 的三档：
 *  · [EDITABLE]     S Ext ≥ 18：文本层 + 搜索 + 选择 + **平台注解写回**
 *  · [VIEW_ONLY]    S Ext 13–17：文本层 + 搜索 + 选择（**不能写回文件**）
 *  · [RENDER_ONLY]  S Ext < 13：**没有文本层**，只能渲染页面
 *
 * 注意一处容易误解的地方：**三档都能批注**（笔迹层是我们自己的），
 * 差别在于"能不能取到文本层"（能不能吸附到某一行）与"能不能写回文件"。
 * 详见 [PdfCapabilities]。
 */
enum class PdfBackend { EDITABLE, VIEW_ONLY, RENDER_ONLY }

/**
 * 某一档具体能做什么。
 *
 * 这张表存在的意义是**让"能力"只有一个来源**：界面、导入流程、批注层都来问它，
 * 而不是各自写一串 `SdkExtensions.getExtensionVersion(...) >= 18`。
 * 散落的版本判断一旦不一致，症状是"某个入口说能选中、另一个说不能"。
 */
data class PdfCapabilities(
    /** 能取到文本层。文本选择、搜索、吸附高亮、行级定位全都建立在它之上。 */
    val hasTextLayer: Boolean,
    /** 能把批注作为 PDF 注解写回文件本身（否则只能导出烧录了笔迹的副本）。 */
    val canWriteBackAnnotations: Boolean,
    /** 给用户看的一句话能力说明（导入与打开时提示）。 */
    val notice: String,
)

/** 三档的判定。纯函数：输入是读到的数值，输出是档位 —— 因此可以在 JVM 上钉死边界。 */
object PdfBackendPolicy {

    /** 能取到文本层的最低 S Extensions 版本（`PdfRendererPreV` 的文本选择/搜索下放档）。 */
    const val TEXT_LAYER_MIN_EXTENSION = 13

    /** 能写回注解的最低 S Extensions 版本（平台 `addPageAnnotation` / `write()` 下放档）。 */
    const val WRITE_BACK_MIN_EXTENSION = 18

    /**
     * S Extensions 从 Android 12（API 31）起存在。
     *
     * 本工程 minSdk 就是 31，所以这一条在真机上永远不会命中；
     * 仍然显式写出来，是因为它表达的是**能力来源**而不是兼容性兜底：
     * 低于 API 31 时"扩展版本"这个概念本身不存在，读到的 0 必须解释为"无能力"，
     * 而不是"扩展版本 0"。
     */
    const val MIN_SDK_WITH_EXTENSIONS = 31

    fun of(sdkInt: Int, sExtensionVersion: Int): PdfBackend = when {
        sdkInt < MIN_SDK_WITH_EXTENSIONS -> PdfBackend.RENDER_ONLY
        sExtensionVersion >= WRITE_BACK_MIN_EXTENSION -> PdfBackend.EDITABLE
        sExtensionVersion >= TEXT_LAYER_MIN_EXTENSION -> PdfBackend.VIEW_ONLY
        else -> PdfBackend.RENDER_ONLY
    }

    fun capabilitiesOf(backend: PdfBackend): PdfCapabilities = when (backend) {
        PdfBackend.EDITABLE -> PdfCapabilities(
            hasTextLayer = true,
            canWriteBackAnnotations = true,
            notice = "PDF：可选中、可搜索，批注可以写回文件。",
        )

        PdfBackend.VIEW_ONLY -> PdfCapabilities(
            hasTextLayer = true,
            canWriteBackAnnotations = false,
            notice = "PDF：可选中、可搜索；批注留在应用内，不能写回文件。",
        )

        PdfBackend.RENDER_ONLY -> PdfCapabilities(
            hasTextLayer = false,
            canWriteBackAnnotations = false,
            notice = "PDF：本机没有 PDF 文本能力（系统扩展版本过低），" +
                "只能看页面与按页批注，不能选中或搜索文字。",
        )
    }
}

/**
 * 文本层的判定结果。
 *
 * 与 [PdfBackend] 正交：档位说的是"这台设备能不能取文本层"，
 * 这里说的是"这份文件里到底有没有文本层"。
 * 两者都为"能取到"时，PDF 才能按行定位；否则只能按页定位。
 */
enum class PdfTextLayer { FULL, PARTIAL, NONE }

/**
 * 扫描件判定。
 *
 * 扫描件的字是**图片**：没有文本层就没有行级定位、没有选择、没有搜索。
 * 方案 §5.5 要求"导入时提示 + 允许只读打开或拒绝"，所以这里给出的不只是结论，
 * 还有**说给用户听的理由**（[reason]）—— 用户要能判断这个判断对不对，
 * 因为它是启发式的（[ScannedPdfPolicy.MIN_CHARS_PER_PAGE] 这类阈值）。
 */
data class ScannedPdfAssessment(
    val textLayer: PdfTextLayer,
    val pages: Int,
    val pagesWithText: Int,
    /** 给用户看的解释。 */
    val reason: String,
) {
    /** 有文本层的页才能按行定位；没有的页只能按页坐标定位（固定版式下这没问题）。 */
    val canAnchorToLines: Boolean get() = textLayer != PdfTextLayer.NONE

    /** 是否需要提示用户。全部页都有文本层时不该打扰用户。 */
    val needsNotice: Boolean get() = textLayer != PdfTextLayer.FULL
}

/**
 * "这份 PDF 是扫描件吗"的判定规则。
 *
 * 这是个**启发式**，所以规则要能被用户看懂并被用户推翻（"强制只读打开"）。
 * 判成扫描件的代价是"不给你行级定位"，判成文本型的代价是"批注锚在不存在的行上" ——
 * 前者可恢复，后者会画错位置，所以阈值取**偏向判成扫描件**的一侧。
 */
object ScannedPdfPolicy {

    /**
     * 一页算"有文本层"的非空白字符下限。
     *
     * 取 16 而不是 1 或 2：扫描件的页面上常常有页码、水印、书店logo 被 OCR 成了
     * 零星几个字符。门槛太低会让一本扫描书被判成文本型，然后每一页都"有文本层
     * 却对不上行"，用户看到的是一堆错位的批注。
     * 门槛太高则会误判那些**每页只有标题**的图文画册 —— 但那一类本来就没有
     * 可锚定的正文，判成扫描件（按页批注）反而更接近事实。
     */
    const val MIN_CHARS_PER_PAGE = 16

    /** 有文本层的页占比达到它才算"文本型"。 */
    const val TEXT_PAGE_RATIO = 0.5f

    fun assess(pageTexts: List<String>): ScannedPdfAssessment {
        val pages = pageTexts.size
        if (pages == 0) {
            return ScannedPdfAssessment(
                textLayer = PdfTextLayer.NONE,
                pages = 0,
                pagesWithText = 0,
                reason = "这份 PDF 里没有页面。",
            )
        }

        val withText = pageTexts.count { nonBlankChars(it) >= MIN_CHARS_PER_PAGE }
        val ratio = withText.toFloat() / pages

        return when {
            ratio >= 1f -> ScannedPdfAssessment(
                textLayer = PdfTextLayer.FULL,
                pages = pages,
                pagesWithText = withText,
                reason = "每一页都有文本层。",
            )

            ratio >= TEXT_PAGE_RATIO -> ScannedPdfAssessment(
                textLayer = PdfTextLayer.PARTIAL,
                pages = pages,
                pagesWithText = withText,
                reason = "共 $pages 页，其中 ${pages - withText} 页没有文本层" +
                    "（通常是插图页或扫描页）：这些页上不能选中文字，笔迹只能按页定位。",
            )

            else -> ScannedPdfAssessment(
                textLayer = PdfTextLayer.NONE,
                pages = pages,
                pagesWithText = withText,
                reason = "共 $pages 页，只有 $withText 页有文本层：这份 PDF 应该是扫描件，" +
                    "文字是图片，不能选中、搜索或按行批注。",
            )
        }
    }

    private fun nonBlankChars(pageText: String): Int = pageText.count { !it.isWhitespace() }
}
