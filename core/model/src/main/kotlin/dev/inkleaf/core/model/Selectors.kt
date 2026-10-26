package dev.inkleaf.core.model

/** 文本方向。v1 支持横排中/英；竖排（CJK vertical）不进 v1。 */
enum class Direction { LTR, RTL }

/**
 * 版式模式（§6.5）。
 *
 * [PAGED] 与 [SCROLL] 都是"允许自动回流"，差别只在呈现：
 * 分页模式下行盒随页宽变化，滚动模式下随视口宽度变化但连续排布。
 * 两者的锚点语义完全相同 —— 因此它们不是两种锚定模式，只是两种视图。
 * [LOCKED] 才是真正的第三种：锁定版式后一切自动回流与自动重锚都被禁止，
 * 用户得到的是"所见即所存"（§6.5 的版式锁定模式）。
 */
enum class LayoutMode { PAGED, SCROLL, LOCKED }

/**
 * 文本归一化配置。
 *
 * 这条配置**必须随锚点链一起持久化**。理由：锚点的 sourceFingerprint 是在
 * 某个归一化口径下算出来的；如果只存归一化后的文本而不存口径，
 * 换一个版本/换一种归一化规则就会导致全库锚点集体失配（§6.3.1）。
 */
data class NormalizationProfile(
    /**
     * 规则版本号。
     *
     * 它与 [profileId] 是两个不同的东西，都要存：
     *   - [profileId] 标识"用哪套口径"（例如将来出现"忽略标点的宽松口径"）；
     *   - [version] 标识"这套口径的第几版"。
     * 任何一项变化都必须触发全库锚点重算 + 显式提示用户（§6.3.1），
     * 而不是让旧锚点在新口径下静默偏移。
     */
    val version: Int = CURRENT_VERSION,
    val unicodeForm: UnicodeForm = UnicodeForm.NFC,
    /** 空白策略。默认值刻意不是"全删"：全角空格在中文排版里是语义字符，不是空白。 */
    val spacePolicy: SpacePolicy = SpacePolicy.COLLAPSE_HALF_WIDTH,
    val lineEnding: LineEnding = LineEnding.NORMALIZE_LF,
    /** 去掉文件头 BOM。BOM 在某些编辑器里会被当成正文首字符，导致首块偏移整体 +1。 */
    val stripBom: Boolean = true,
    /** 去掉行尾空白。拖尾空格在"复制粘贴 → 另存"流程里最常见，且完全无语义。 */
    val trimTrailing: Boolean = true,
    val caseFold: Boolean = false,
    val stripPunctuation: Boolean = false,
    val profileId: String = DEFAULT_ID,
) {
    companion object {
        /** v1 唯一在用的口径。新增口径必须换新的 id，不得原地修改语义。 */
        const val DEFAULT_ID = "nf:std-1"

        /** 当前规则版本。改这里的任何一项都必须同时 +1（规则见 [version] 注释）。 */
        const val CURRENT_VERSION = 1
    }
}

enum class UnicodeForm { NFC, NFKC, NONE }

/**
 * 空白策略。
 *
 * [COLLAPSE_HALF_WIDTH] 是默认：把连续的半角空格/制表符折叠成一个半角空格，
 * 但**保留全角空格 U+3000**。中文文档里 U+3000 常用于段首缩进与对齐，
 * 把它当空白折叠掉会改变段落的语义边界。
 */
enum class SpacePolicy { COLLAPSE_HALF_WIDTH, PRESERVE, REMOVE_ALL }

enum class LineEnding { NORMALIZE_LF, PRESERVE }

/**
 * 选择器。三个选择器按可靠性降序排列，构成多重校验的降级链（§6.3.1）。
 *
 * 这里刻意对齐 W3C Web Annotation Data Model 的 selector 语义，
 * 目的不是形式上的规范一致，而是让已经研究过这套模型的人能直接看懂
 * （并允许未来做标注导出/导入互通）。
 */
sealed interface Selector {

    /**
     * 结构型选择器 —— 最可靠，锚定到解析器给出的块路径 + 字符偏移。
     *
     * 对 TXT / EPUB / PDF 而言都是全局块路径（PDF 一行一块，见 `PdfDocumentSession`）。
     *
     * 页号**刻意不进路径**：块路径的解析只有一处（`DocumentSession.blockIndexFromPath`），
     * 多一种路径格式就多一处会走样的判断。页号该出现的地方是 [ProgressHint.pageKey]。
     */
    data class Structural(
        /** 块路径，例如 "body/blk[3]/p[0]"；TXT 为 "blk[12]"。 */
        val blockPath: String,
        /** 块内起始字符偏移（按归一化后文本计数）。 */
        val startOffset: Int,
        /** 块内结束字符偏移（前闭后开）。 */
        val endOffset: Int,
        /**
         * 基线在行盒内的相对位置，单位 em（1.0 = 一个字号高度）。
         * 这是笔迹与文本行绑定的锚点信息，见 §6.3.2。
         */
        val baselineOffsetEm: Float,
    ) : Selector

    /**
     * 文本型选择器 —— 当结构路径因为重排/换版本而失配时的第二道网。
     *
     * [exact] / [prefix] / [suffix] 三元组直接来自 W3C TextQuoteSelector。
     * 其中 prefix / suffix 的作用是消歧：exact 常常是"他说"这种到处都有的短句。
     */
    data class TextQuote(
        val exact: String,
        val prefix: String = "",
        val suffix: String = "",
    ) : Selector

    /**
     * 进度兜底选择器 —— 永远排在链尾。
     *
     * 它**不允许**被用来直接重建锚点，只允许用来把用户的视线送到"大概位置"
     * （§6.3.1 规则 3）：因为按百分比定位在重排后误差可以到整章。
     */
    data class ProgressHint(
        /** 全文进度 0..1。 */
        val docRatio: Float,
        /** 所在页键（EPUB 用 spine index + 页序；PDF 用页号）。 */
        val pageKey: String? = null,
        /** 附近的一段文本，仅用于 UI 提示"可能是这一段"。 */
        val nearbyText: String = "",
    ) : Selector
}

/**
 * 锚点链。
 *
 * 不变量（由 init 强制，而不是靠调用方自觉）：
 *   1. 至少有一个选择器；
 *   2. 最后一项必须是 [Selector.ProgressHint]。
 *
 * 第 2 条让 [progressHint] 成为全函数，孤儿状态因此永远有地方可去 ——
 * 否则"锚点失效后去哪"这个问题会在每个调用点各写一份 if。
 */
data class TextAnchorChain(
    val selectors: List<Selector>,
    val normalization: NormalizationProfile,
    val sourceFingerprint: String,
) {
    init {
        require(selectors.isNotEmpty()) { "锚点链不能为空" }
        require(selectors.last() is Selector.ProgressHint) {
            "锚点链最后一项必须是 ProgressHint（孤儿兜底），见方案 §6.3.1"
        }
    }

    val progressHint: Selector.ProgressHint get() = selectors.last() as Selector.ProgressHint

    val structural: Selector.Structural? get() = selectors.filterIsInstance<Selector.Structural>().firstOrNull()

    val textQuote: Selector.TextQuote? get() = selectors.filterIsInstance<Selector.TextQuote>().firstOrNull()
}

/**
 * 行盒（L2 锚定的基本单位）。
 *
 * 对应方案 §6.3.2：L2 之所以可行，是因为"行盒由真实排版结果给出"——
 * 见 §5.7，行盒只能来自 `TextLayoutResult` / 浏览器 JS 的真实结果，
 * 不允许自己用字号 × 行高算出来（Android 14+ 的非线性字号缩放会让这种算法全错）。
 */
data class LineBox(
    /** 该行的结构型锚点信息。 */
    val anchor: Selector.Structural,
    /** 行盒在文档坐标系下的矩形（不含行间距的"墨迹盒"）。 */
    val rectDoc: Box,
    /** 基线在文档坐标系下的 y。笔迹的纵向基准就是它。 */
    val baselineY: Float,
    /** 该行的字号（像素），em → px 换算用。 */
    val fontSizePx: Float,
    val direction: Direction = Direction.LTR,
) {
    /**
     * 行局部空间（单位 em，原点 = 行盒左上角）→ 文档坐标 的变换。
     *
     * 所有笔迹都以这个空间存储，所以字号/字体变化时笔迹能自动等比跟随；
     * 换行位置变化时才需要重新判定语义（§6.3.3）。
     */
    fun matrixInDoc(): Affine2D = Affine2D.emToDoc(
        fontSizePx = fontSizePx,
        originLeft = rectDoc.left,
        originTop = rectDoc.top,
    )

    /** 文档坐标 → 行局部空间。写入笔迹时用。 */
    fun docToEmSpace(): Affine2D? = matrixInDoc().invert()
}
