package dev.inkleaf.core.model

/** 文档格式。v1 只支持这四种；PDF 仅限有文本层的（扫描件导入时被拒，见 §5.5）。 */
enum class DocumentKind {
    TXT,
    MARKDOWN,
    EPUB,
    PDF;

    companion object {
        /** 该格式是否支持"重排"（决定锚点是否会在字号/窗宽变化后移动）。 */
        fun DocumentKind.isReflowable(): Boolean = this == TXT || this == MARKDOWN || this == EPUB
    }
}

/** 文档来源。用于"源文件已被外部修改/删除"时的降级提示（§9 风险表）。 */
enum class DocumentOrigin {
    /** 通过 SAF 选中后复制进 App 私有目录（推荐路径，最稳）。 */
    SAF_COPY,

    /** 由 App 自己在私有目录里生成或通过分享进入。 */
    PRIVATE_IMPORT,
}

/**
 * 文档元数据。
 *
 * [sourceFingerprint] 与 [normalizationProfileId] / [normalizationVersion]
 * 必须一起存：指纹是在某个归一化口径下算出来的，只存指纹而不存口径，
 * 换口径后全库锚点会集体静默失配（§6.3.1）。
 */
data class DocumentMeta(
    val id: String,
    val title: String,
    val author: String? = null,
    val kind: DocumentKind,
    val origin: DocumentOrigin = DocumentOrigin.SAF_COPY,
    /** 归一化后文本 + 口径 + 解析器版本 的指纹。见 SourceFingerprint。 */
    val sourceFingerprint: String,
    val normalizationProfileId: String,
    val normalizationVersion: Int,
    /** 私有目录内的相对路径（绝不放绝对路径：绝对路径换机器/换用户即失效）。 */
    val relativePath: String,
    /** 导入时探测到的编码名，仅用于展示与"编码猜错"的排查。 */
    val encoding: String,
    val byteSize: Long,
    /** 归一化后字符数。分页进度、字数统计用它。 */
    val charCount: Int,
    val blockCount: Int,
    val importedAt: Long,
    val lastOpenedAt: Long? = null,
    val coverRelativePath: String? = null,
    /** 原始 SAF URI，仅用于"重新授权"提示，不用于读取（读取一律走私有副本）。 */
    val sourceUri: String? = null,
    /** PDF：是否存在文本层。false = 扫描件（只读打开，禁批注）。 */
    val hasTextLayer: Boolean = true,
)

/** 目录项（章节）。 */
data class TocItem(
    val id: String,
    val title: String,
    val level: Int,
    val blockIndex: Int,
    /** 该章节起始处在归一化全文中的字符偏移。 */
    val charOffset: Int,
    /** 固定版式格式（PDF/EPUB 的固定分页）用页键；重排格式为 null。 */
    val pageKey: String? = null,
)

/** 阅读进度。刻意不用百分比存：百分比在重排后误差可到整章（§6.3.1 规则 3）。 */
data class ReadingProgress(
    val docId: String,
    val charOffset: Int,
    val pageIndex: Int,
    /** 仅供 UI 显示与孤儿兜底，不作为定位依据。 */
    val docRatio: Float,
    val updatedAt: Long,
)

/**
 * 视口。分页计算的全部输入必须只来自它，不允许在分页器里偷偷读全局资源 ——
 * 否则同一份文档在不同窗口尺寸下会得到不可复现的行盒。
 */
data class Viewport(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    /** 系统字号缩放（Android 14+ 非线性缩放的最终倍率由调用方算好传入）。 */
    val fontScale: Float = 1f,
)

/** 页面排版度量（全部为像素）。 */
data class PageLayoutMetrics(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val marginLeftPx: Float,
    val marginRightPx: Float,
    val marginTopPx: Float,
    val marginBottomPx: Float,
)

/** 标注大类。用于笔记列表分组与图标，不引入新的状态语义。 */
enum class AnnotationKind { HIGHLIGHT, INK, MARGIN_NOTE, UNDERLINE, BOOKMARK }

/**
 * 标注的运行时类型投影。
 *
 * 刻意做成扩展属性而不是往 sealed interface 上加抽象成员：
 * [Annotation] 的每个子类都已经是 data class，加抽象成员会强迫
 * 所有构造点都写一遍 `override val kind`，却没有带来任何额外约束。
 */
val Annotation.kind: AnnotationKind
    get() = when (this) {
        is Annotation.Highlight -> AnnotationKind.HIGHLIGHT
        is Annotation.Ink -> AnnotationKind.INK
        is Annotation.MarginNote -> AnnotationKind.MARGIN_NOTE
        is Annotation.Underline -> AnnotationKind.UNDERLINE
        is Annotation.Bookmark -> AnnotationKind.BOOKMARK
    }

/** 一两句话描述标注，用于笔记列表与搜索。 */
fun Annotation.summaryText(maxChars: Int = 60): String {
    noteText?.takeIf { it.isNotBlank() }?.let { return it.take(maxChars) }
    return when (this) {
        is Annotation.Highlight -> "高亮"
        is Annotation.Ink -> "手写"
        is Annotation.MarginNote -> "边注"
        is Annotation.Underline -> "下划线"
        is Annotation.Bookmark -> "书签"
    }
}
