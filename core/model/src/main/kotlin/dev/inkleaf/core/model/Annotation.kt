package dev.inkleaf.core.model

/**
 * 荧光笔着色方式（§6.4）。
 *
 * 这是竞品差异最大的一处细节：多数阅读器的荧光笔是"半透明矩形压在文字上"，
 * 一旦深色模式或图片背景就会变得灰暗糊。v1 采用 [MULTIPLY] 与
 * [UNDER_TEXT] 两种可选模式，默认 [MULTIPLY]。
 */
enum class HighlightRenderMode {
    /** 正片叠底：保留文字对比度，深色背景下允许自动提亮。 */
    MULTIPLY,

    /** 压在文字之下（真正的"荧光笔"效果，但对跨行/行高不足的情况有边缘风险）。 */
    UNDER_TEXT,

    /** 半透明覆盖：兼容性最好，视觉效果最差。仅作回退。 */
    OVERLAY,
}

/** 荧光笔的吸附与外观参数。 */
data class HighlightStyle(
    val renderMode: HighlightRenderMode = HighlightRenderMode.MULTIPLY,
    /**
     * 吸附阈值：笔迹墨迹盒与行盒重合度超过该值才认为"要划这一行"。
     * 0.85 明显高于 [InkReflowSemantics.FOLLOWER] 的 0.6 ——
     * 因为荧光笔一旦误吸附，视觉错误非常刺眼；宁可让它变成自由笔迹。
     */
    val snapCoverageThreshold: Float = 0.85f,
    /** 上下各外扩的比例（相对行高），让色带看起来"包住"文字。 */
    val bleed: Float = 0.12f,
)

/** 下划线样式。 */
data class UnderlineStyle(
    /** 线的粗细，单位 em。 */
    val thicknessEm: Float = 0.045f,
    /** 相对基线的偏移，单位 em。 */
    val baselineOffsetEm: Float = 0.12f,
    val wavy: Boolean = false,
)

/**
 * 笔迹的存储表示（§7.3）。
 *
 * 这里是方案里一个容易踩的坑：Jetpack Ink 的 `ink-storage` 只序列化
 * `StrokeInputBatch`，**不序列化 Brush**。所以笔刷必须自己存。
 * [VECTOR] 是默认与唯一必需的形式；[RASTER] 只是导出 PDF 时的中间产物，
 * 绝不作为持久化来源（否则缩放后模糊，且体积爆炸）。
 */
enum class InkRepresentation { VECTOR, RASTER }

/**
 * 标注模型。
 *
 * [Ink] 与 [Underline] 的区别不是"手写 vs 手势"，而是语义：
 * 下划线是**离散**的（吸附到若干文本行），笔迹是**连续**的。
 * 把下划线做成一支"直线笔刷"是竞品常见的偷懒，后果是它无法正确跨行、
 * 也无法在重排后逐行跟随。
 */
sealed interface Annotation {

    val id: String
    val docId: String
    val createdAt: Long
    val colorArgb: Int
    val tags: List<String>
    val noteText: String?

    data class Highlight(
        override val id: String,
        override val docId: String,
        override val createdAt: Long,
        override val colorArgb: Int,
        override val tags: List<String> = emptyList(),
        override val noteText: String? = null,
        val anchor: TextAnchorChain,
        val style: HighlightStyle = HighlightStyle(),
    ) : Annotation

    /**
     * 手写笔迹。[semantics] 决定重排行为（§6.3.3）。
     * [anchor] 为 null 表示整页自由笔迹（PINNED/FREE），此时 [pages] 必非空。
     */
    data class Ink(
        override val id: String,
        override val docId: String,
        override val createdAt: Long,
        override val colorArgb: Int,
        override val tags: List<String> = emptyList(),
        override val noteText: String? = null,
        val anchor: TextAnchorChain?,
        val semantics: InkReflowSemantics,
        val representation: InkRepresentation = InkRepresentation.VECTOR,
        val strokeIds: List<String> = emptyList(),
        /** 页面键集合，用于 PINNED/FREE 笔迹。 */
        val pages: List<String> = emptyList(),
    ) : Annotation

    /**
     * 边注。产品决策：**用纯文本而不是手写**（§6.4）。
     *
     * 理由是手写边注最难做对的三件事 —— 重排后跟随、导出后可读、
     * 全文可搜索 —— 在文本下全部免费获得；而手写边注必须解决全部三件。
     * 画布式手写仍然可用，只是不作为"边注"这个产品的默认实现。
     */
    data class MarginNote(
        override val id: String,
        override val docId: String,
        override val createdAt: Long,
        override val colorArgb: Int,
        override val tags: List<String> = emptyList(),
        override val noteText: String? = null,
        val anchor: TextAnchorChain,
        val side: Side = Side.RIGHT,
    ) : Annotation

    data class Underline(
        override val id: String,
        override val docId: String,
        override val createdAt: Long,
        override val colorArgb: Int,
        override val tags: List<String> = emptyList(),
        override val noteText: String? = null,
        /** 下划线是离散的：一段下划线覆盖若干行，每行一个锚点。 */
        val anchors: List<TextAnchorChain>,
        val style: UnderlineStyle = UnderlineStyle(),
    ) : Annotation {
        init {
            require(anchors.isNotEmpty()) { "下划线至少需要一行锚点" }
        }
    }

    /**
     * 书签。刻意与 [Highlight] 区分开：书签**不绑定文本**，
     * 只绑定阅读位置。把它做成"零长度高亮"会导致重排后书签漂移。
     */
    data class Bookmark(
        override val id: String,
        override val docId: String,
        override val createdAt: Long,
        override val colorArgb: Int,
        override val tags: List<String> = emptyList(),
        override val noteText: String? = null,
        val progressHint: Selector.ProgressHint,
        val anchor: TextAnchorChain? = null,
    ) : Annotation
}
