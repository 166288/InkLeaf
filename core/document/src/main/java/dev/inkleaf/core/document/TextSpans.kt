package dev.inkleaf.core.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import dev.inkleaf.core.model.DocumentKind
import kotlin.math.roundToInt

/**
 * 渲染主题。所有尺寸单位是像素（由调用方用 Units 从 sp/dp 换算好）。
 *
 * 为什么颜色也放在这里：荧光笔的"正片叠底"模式（§6.4）需要一个底色参考，
 * 而文档渲染与墨迹渲染在同一个 View 里，它们必须用同一套颜色事实。
 */
data class DocumentTheme(
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val textColor: Int,
    val headingColor: Int,
    val codeColor: Int,
    val codeBackgroundColor: Int,
    val quoteColor: Int,
    val markerHiddenColor: Int = Color.TRANSPARENT,
    /**
     * 正文字体（M29）。null = 系统默认（sans-serif）。
     *
     * 它必须参与排版缓存键（见 `ReflowableDocumentSession.layout`）：
     * 换字体不改键的话，会复用旧字体的行盒 —— 字变了、断行没变，排版全错。
     */
    val typeface: Typeface? = null,
    /**
     * 段间距（em，0..1，M33）。
     *
     * 只加在**段落之间**，不加在段内的硬换行上：一本书的版面里，
     * "段与段"的呼吸靠留白，"行与行"的呼吸靠行距。两者用同一个参数表达，
     * 调到舒服的段间距时行距必然过大。
     *
     * ## 为什么它是几何留白，而不是 `LineHeightSpan`
     *
     * 最初的做法是在段首字符上挂一个 `LineHeightSpan`，把那一行的 `ascent` 撑大。
     * 实测不成立：`StaticLayout` 在量行高时复用同一个 `FontMetricsInt` 实例，
     * 一旦被 span 改动，**后面每一行都会继承**这个改动（30 段的样例里，
     * 从第 2 段起行距逐行变大，段边界反而看不出来了）。
     *
     * 所以段间距改由排版段之间的纵向留白实现：段落边界处切段，
     * 下一段的 `topPx` 多让出一个 [paragraphSpacingEm] 字高。
     * 位置的来源仍然只有一份（`TextLayoutSection.topPx`），行盒与绘制同源。
     */
    val paragraphSpacingEm: Float = 0f,
    /**
     * 两端对齐（M33）。
     *
     * 中文正文的右边缘是否要像纸书一样齐。收益主要在中西混排：
     * 一排英文单词参差不齐时，右边缘的锯齿会让人误以为"这段没排完"。
     * 纯中文的观感差别很小 —— 中文字宽本来就是均匀的。
     */
    val justifyText: Boolean = false,
    /**
     * 中文禁则（M33）：不让 `。，、；：？！` 这类标点落在行首。
     *
     * 两件事一起做：
     * 1. **断行层**：API 33+ 打开 `LINE_BREAK_STYLE_STRICT`，把禁则交给系统的
     *    ICU 断行器（它认识完整的行首/行末禁则表）；
     * 2. **行盒层**：连续出现的收尾标点（`。」`、`，、`）按 [PUNCTUATION_SQUEEZE]
     *    压缩后一个的步进宽度 —— 这就是"标点挤压"，也是禁则后半段的"悬挂"：
     *    标点不再被挤到下一行，而是缩进前一个字的下方。
     */
    val cjkPunctuation: Boolean = false,
) {
    companion object {
        fun light(textSizePx: Float, lineSpacingMultiplier: Float = 1.45f) = DocumentTheme(
            textSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            textColor = 0xFF1B1B1B.toInt(),
            headingColor = 0xFF10233B.toInt(),
            codeColor = 0xFF2E3440.toInt(),
            codeBackgroundColor = 0x14000000,
            quoteColor = 0xFF5B6673.toInt(),
        )

        fun dark(textSizePx: Float, lineSpacingMultiplier: Float = 1.45f) = DocumentTheme(
            textSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            textColor = 0xFFE6E3DC.toInt(),
            headingColor = 0xFFF2EFE7.toInt(),
            codeColor = 0xFFD8DEE9.toInt(),
            codeBackgroundColor = 0x22FFFFFF,
            quoteColor = 0xFFA9B3BF.toInt(),
        )

        /**
         * 按**给定墨色**生成一套主题（M17：阅读主题）。
         *
         * 纸色不进来：纸是背景，画在排版结果之外，排版层根本不需要知道它是什么色。
         * 但墨色必须进来 —— 它决定正文、标题、代码、引用的颜色，也决定
         * "荧光笔正片叠底"要乘哪一个底色。只改纸不改墨的"主题"会得到
         * 白纸上深灰字、黑纸上浅灰字这种读不下去的组合。
         *
         * 标题、代码、引用这三档**从正文墨色派生**，而不是再抄三个常量：
         * 抄常量意味着换一个主题就要记得改四处，漏一处就是"正文变了标题没变"。
         *
         * @param isDark 决定派生方向：深色主题里标题要更亮（往白走），
         *   浅色主题里要更深（往黑走）。同向派生会让标题在深色底上糊成一片。
         */
        fun withInk(
            textSizePx: Float,
            lineSpacingMultiplier: Float,
            inkArgb: Int,
            isDark: Boolean,
            typeface: Typeface? = null,
            /** 段间距（em）。见 [DocumentTheme.paragraphSpacingEm]。 */
            paragraphSpacingEm: Float = 0f,
            justifyText: Boolean = false,
            cjkPunctuation: Boolean = false,
        ): DocumentTheme {
            val toward = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            return DocumentTheme(
                textSizePx = textSizePx,
                lineSpacingMultiplier = lineSpacingMultiplier,
                textColor = inkArgb,
                headingColor = mix(inkArgb, toward, 0.14f),
                codeColor = mix(inkArgb, toward, 0.08f),
                codeBackgroundColor = if (isDark) 0x22FFFFFF else 0x14000000,
                // 引用靠"淡"区分而不是靠换色：换色在六个主题里很难都协调，
                // 而降不透明度在任何纸色上都成立。
                quoteColor = (inkArgb and 0x00FFFFFF) or (0xB0 shl 24),
                typeface = typeface,
                paragraphSpacingEm = paragraphSpacingEm,
                justifyText = justifyText,
                cjkPunctuation = cjkPunctuation,
            )
        }

        /** 把 [argb] 往 [target] 混 [ratio]（0..1）。只用于上面那几档派生色。 */
        private fun mix(argb: Int, target: Int, ratio: Float): Int {
            fun channel(shift: Int): Int {
                val a = (argb ushr shift) and 0xFF
                val b = (target ushr shift) and 0xFF
                return (a * (1f - ratio) + b * ratio).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

/**
 * 文本样式构造。
 *
 * 一条不可违反的规则：**任何 span 都不能改变文本长度**。
 * 行盒、块内偏移、锚点全部以字符下标为坐标，一旦某个 span 让文本变长变短，
 * 整套坐标系就地失效。所以：
 *   - Markdown 的语法标记（`##`、`>`、`**`）不删除，只把前景色设成透明；
 *   - 缩进用 `LeadingMarginSpan`（只影响排版宽度，不动文本）；
 *   - 列表标记保持可见（它本身是语义）。
 */
object TextSpanBuilder {

    /** 标题相对正文字号的缩放。h6 与正文同号，避免小标题反而比正文小。 */
    private val HEADING_SCALES = floatArrayOf(1.6f, 1.4f, 1.25f, 1.15f, 1.08f, 1.0f)

    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)")
    private val INLINE_CODE = Regex("`([^`\\n]+?)`")

    fun headingScale(level: Int): Float =
        HEADING_SCALES[(level - 1).coerceIn(0, HEADING_SCALES.lastIndex)]

    /** 中文段落首行缩进的宽度（em）。两格是中文书籍的通例。 */
    const val CJK_FIRST_LINE_INDENT_EM = 2f

    /**
     * 段内 CJK 字符占比到这个数才算"中文段落"。
     *
     * 0.3 而不是 0.5：中英混排的技术书里，一段话常常是"大量英文术语 + 中文连接词"，
     * 按字符数算下来英文反而更多，但读者在心理上读的是中文段落。
     */
    private const val CJK_RATIO_THRESHOLD = 0.3f

    /**
     * 这一段要不要首行缩进。
     *
     * 三条规则，任何一条不满足就不缩进：
     *
     *  1. **必须是普通段落**。标题、列表、代码、引用、图片各有自己的版式，
     *     给它们加缩进只会让层级关系变模糊。
     *  2. **段内 CJK 字符占比要够**。英文段落靠段间距分段，首行缩进是错的 ——
     *     一本英文书的每一个自然段都缩进两格，看起来像排版事故。
     *  3. **段首本来没有空白**。作者自己敲了两个空格的地方不要再叠一层。
     *
     * 判据只看块自己的文本，不看文档类型：一本 EPUB 里可能中英章节交替，
     * 用"这份文件是不是中文"来决定缩进，必然有一半是错的。
     */
    fun shouldIndentFirstLine(block: TextBlock): Boolean {
        if (block.kind != BlockKind.PARAGRAPH) return false
        val text = block.text
        if (text.isEmpty()) return false
        val first = text.firstOrNull { !it.isWhitespace() } ?: return false
        if (text.first() != first) return false
        var cjk = 0
        var letters = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            letters++
            if (isCjk(c)) cjk++
        }
        if (letters == 0) return false
        return cjk.toFloat() / letters >= CJK_RATIO_THRESHOLD
    }

    /** 是否属于中日韩文字与全角标点。 */
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || // 基本汉字
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0xF900..0xFAFF || // 兼容汉字
            code in 0x3000..0x303F || // 中日韩标点
            code in 0xFF00..0xFFEF // 全角形式
    }

    /** 某个块在渲染时使用的字号。行盒要用它做 em → px 换算，因此必须与 span 完全一致。 */
    fun fontSizePxOf(block: TextBlock, theme: DocumentTheme): Float = when {
        block.kind == BlockKind.HEADING && block.headingLevel > 0 ->
            theme.textSizePx * headingScale(block.headingLevel)

        else -> theme.textSizePx
    }

    fun build(
        normalizedText: String,
        blocks: List<TextBlock>,
        kind: DocumentKind,
        theme: DocumentTheme,
        /** 取图器（EPUB 才有）。null 时图片位置留白。 */
        imageProvider: DocumentImageProvider? = null,
        /** 图片可用的最大宽度（正文栏宽）；0 表示不放图。 */
        imageMaxWidthPx: Int = 0,
        imageMaxHeightPx: Int = 0,
    ): Spannable {
        val sb = SpannableStringBuilder(normalizedText)

        for (block in blocks) {
            val start = block.absoluteStart
            val end = block.absoluteEnd
            if (start >= end) continue

            // ① 语法标记：置为透明，不删字符
            for (marker in block.markerSpans) {
                val ms = marker.start.coerceIn(start, end)
                val me = marker.end.coerceIn(ms, end)
                if (ms < me) {
                    sb.setSpan(
                        ForegroundColorSpan(theme.markerHiddenColor),
                        ms, me, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

            when (block.kind) {
                BlockKind.HEADING -> {
                    val scale = headingScale(block.headingLevel.coerceAtLeast(1))
                    sb.setSpan(
                        RelativeSizeSpan(scale), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        ForegroundColorSpan(theme.headingColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                BlockKind.CODE, BlockKind.CODE_FENCE -> {
                    sb.setSpan(
                        TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        ForegroundColorSpan(theme.codeColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        BackgroundColorSpan(theme.codeBackgroundColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                BlockKind.QUOTE -> {
                    sb.setSpan(
                        ForegroundColorSpan(theme.quoteColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    // 引用用左侧留白表示层级：只改排版，不改文本
                    sb.setSpan(
                        LeadingMarginSpan.Standard((theme.textSizePx * 1.5f).toInt()),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                BlockKind.RULE -> {
                    sb.setSpan(
                        ForegroundColorSpan(theme.markerHiddenColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                BlockKind.LIST_ITEM -> Unit

                BlockKind.IMAGE -> {
                    // 只有"占位字符 + 有 href"的块才是真正要画的图（EPUB 正文图）。
                    // Markdown 的 `![](x)` 块不是占位字符，保持原样可见 ——
                    // 那行本来就是给人看的 Markdown 源码。
                    if (isImagePlaceholderLine(block.text)) {
                        val href = block.imageHref
                        val drawable = if (href != null && imageProvider != null &&
                            imageMaxWidthPx > 0 && imageMaxHeightPx > 0
                        ) {
                            runCatching {
                                imageProvider.drawable(href, imageMaxWidthPx, imageMaxHeightPx)
                            }.getOrNull()
                        } else {
                            null
                        }
                        if (drawable != null) {
                            // 一张图替换掉一个占位字符。之所以用 ImageSpan 而不是
                            // "在绘制层按行盒贴图"：行高必须真的把图算进去，
                            // 否则图会盖住下一行，而"图压字"比"图不显示"更难解释。
                            sb.setSpan(
                                ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BASELINE),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        } else {
                            // 取不到图（文件缺失、格式不支持、超内存预算）：占位字符透明。
                            // 留一个"豆腐块"或方框会让人以为书坏了；留白至少读得下去。
                            sb.setSpan(
                                ForegroundColorSpan(Color.TRANSPARENT),
                                start,
                                end,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }

                BlockKind.PARAGRAPH -> {
                    // 中文段落首行缩进两格。规则与理由在 [shouldIndentFirstLine]。
                    // 用 `LeadingMarginSpan.Standard(first, rest)` 而不是插两个全角空格：
                    // 空格会改变文本长度，而字符下标是全工程唯一的文字坐标（见本文件开头）。
                    if (shouldIndentFirstLine(block)) {
                        sb.setSpan(
                            LeadingMarginSpan.Standard(
                                (theme.textSizePx * CJK_FIRST_LINE_INDENT_EM).toInt(),
                                0,
                            ),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }

        // ② 行内样式：只在 Markdown 上做（TXT 里的 * 就是星号，不该被吃成斜体）
        if (kind == DocumentKind.MARKDOWN) {
            applyInlineStyles(sb, blocks, theme)
        }

        // ③ 中文禁则里的"挤压"（M33）。断行层的禁则由 StaticLayout 的
        //    `LINE_BREAK_STYLE_STRICT` 负责，见 `ReflowableDocumentSession.buildLayout`；
        //    段间距不进 span，它是排版段之间的几何留白，见那边的 `buildLayout`。
        if (theme.cjkPunctuation) applyPunctuationSqueeze(sb, blocks)

        return sb
    }

    /**
     * 行内 `**粗体**` / `*斜体*` / `` `代码` ``。
     *
     * 标记同样只隐藏不删除，因此文本长度恒定。
     * 只在块内匹配（不跨块）：跨块的正则会在换行处产生难以预测的匹配范围，
     * 而块是锚点的边界，任何跨块的样式范围都会让"这一行属于哪个块"变得模糊。
     */
    private fun applyInlineStyles(sb: SpannableStringBuilder, blocks: List<TextBlock>, theme: DocumentTheme) {
        for (block in blocks) {
            if (block.kind == BlockKind.CODE || block.kind == BlockKind.CODE_FENCE) continue
            val base = block.absoluteStart
            val text = block.text

            for (match in INLINE_CODE.findAll(text)) {
                val inner = match.range.first + 1
                val innerEnd = match.range.last
                if (inner >= innerEnd) continue
                styleInline(sb, base, match.range.first, match.range.last + 1, inner, innerEnd) {
                    sb.setSpan(TypefaceSpan("monospace"), base + inner, base + innerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(theme.codeBackgroundColor), base + inner, base + innerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            for (match in BOLD.findAll(text)) {
                val inner = match.range.first + 2
                val innerEnd = match.range.last - 1
                if (inner >= innerEnd) continue
                styleInline(sb, base, match.range.first, match.range.last + 1, inner, innerEnd) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), base + inner, base + innerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            for (match in ITALIC_STAR.findAll(text)) {
                val inner = match.range.first + 1
                val innerEnd = match.range.last
                if (inner >= innerEnd) continue
                styleInline(sb, base, match.range.first, match.range.last + 1, inner, innerEnd) {
                    sb.setSpan(StyleSpan(Typeface.ITALIC), base + inner, base + innerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private inline fun styleInline(
        sb: SpannableStringBuilder,
        base: Int,
        markerStart: Int,
        markerEndExclusive: Int,
        innerStart: Int,
        innerEndExclusive: Int,
        apply: () -> Unit,
    ) {
        // 标记（** 或 ` ）透明化：不删字符，长度恒定
        sb.setSpan(
            ForegroundColorSpan(Color.TRANSPARENT),
            base + markerStart, base + innerStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            ForegroundColorSpan(Color.TRANSPARENT),
            base + innerEndExclusive, base + markerEndExclusive, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        apply()
    }

    /**
     * 这两块之间算不算"段落边界"。
     *
     * 三条判据按可靠性排列，命中任一条即成立：
     *
     * 1. **源文里隔着一个空行**。最可靠 —— Markdown、按段落分行的 TXT 都是这样。
     * 2. **任一侧是标题**。标题自己就带上下留白，不需要再看别处。
     * 3. **前一段以句末标点收尾**。EPUB 的提取器把每个 `<p>` 用**单个**换行接起来，
     *    没有空行可依 —— 如果只看 1，EPUB（本应用最主要的重排格式）会一个段间距都没有。
     *    所以退一步看句末：一个以 `。！？…` 收尾的块，几乎一定是一段的结束。
     *
     * 代码块两侧一律不加：代码的每一行都是一个块，加了就是把行距撑成两倍。
     */
    internal fun needsParagraphGap(prev: TextBlock, next: TextBlock): Boolean {
        if (prev.kind == BlockKind.CODE || prev.kind == BlockKind.CODE_FENCE) return false
        if (next.kind == BlockKind.CODE || next.kind == BlockKind.CODE_FENCE) return false
        if (next.kind == BlockKind.RULE) return false
        if (next.absoluteStart - prev.absoluteEnd >= 2) return true
        if (prev.kind == BlockKind.HEADING || next.kind == BlockKind.HEADING) return true
        return endsParagraph(prev.text)
    }

    /** 这一段是不是以句末标点收尾（跳过收尾的引号/括号）。 */
    internal fun endsParagraph(text: String): Boolean {
        val t = text.trimEnd()
        var i = t.lastIndex
        while (i >= 0 && t[i] in CLOSING_QUOTES) i--
        if (i < 0) return false
        return t[i] in SENTENCE_ENDERS || t[i] == '.'
    }

    // ── 中文禁则里的"挤压"（M33）──────────────────────────────────────────────

    /**
     * 连续出现的收尾标点（`。」`、`，、`、`？！`）把**后一个**压窄。
     *
     * 为什么必须压：中文的全角标点每个占满一格，`。」` 这样的组合会白白吃掉两格，
     * 行尾还因此更容易排不下 —— 两个标点一起被挤到下一行，正是读者最常抱怨的
     * "行首一个句号"。
     *
     * 为什么只能"压"不能"删"：删字符会让字符下标失效（见本文件开头）。
     * [SqueezedPunctuationSpan] 是 `ReplacementSpan`，它只改步进宽度、不动文本 ——
     * 正好是"挤压"的语义。代价是这一段里的字符取不到"半个字"的位置
     * （`getPrimaryHorizontal` 在 span 内部只能给起点），而这段恰好就是单个标点，
     * 所以唯一的影响是：搜索命中一个标点时高亮宽度为 0，会被跳过。
     */
    private fun applyPunctuationSqueeze(sb: SpannableStringBuilder, blocks: List<TextBlock>) {
        for (block in blocks) {
            if (block.kind == BlockKind.CODE || block.kind == BlockKind.CODE_FENCE) continue
            val text = block.text
            for (i in 1..text.lastIndex) {
                if (text[i] !in CJK_CLOSING_MARKS) continue
                if (text[i - 1] !in CJK_CLOSING_MARKS) continue
                val start = block.absoluteStart + i
                if (start < 0 || start >= sb.length) continue
                sb.setSpan(
                    SqueezedPunctuationSpan(PUNCTUATION_SQUEEZE),
                    start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    /** 收尾类标点（会落在行首的那一批）。 */
    private const val CJK_CLOSING_MARKS = "、。，．；：？！）》」』】〕”’…"

    /** 可以跟在前一个标点后面的收尾引号/括号。 */
    private const val CLOSING_QUOTES = "”’\"'」』）】〕》>"

    /** 句末标点。逗号顿号不算 —— 硬折行的 TXT 里每一行都可能以逗号结尾。 */
    private const val SENTENCE_ENDERS = "。！？…—～"

    /**
     * 挤压后第二个标点保留的宽度比例。
     *
     * 0.5 是"压掉半个字"，与纸书排版里"两个标点共占一格半"的经验值一致。
     * 再小就会让字形互相压住（笔画真的叠上），再大就看不出在压。
     */
    const val PUNCTUATION_SQUEEZE = 0.5f
}

/**
 * 把一个全角标点的步进宽度压到 [ratio] 倍（M33：标点挤压）。
 *
 * `ReplacementSpan` 是 Android 提供的"只改宽度、不改文本"的唯一正规手段：
 * `getSize` 决定它占多宽，`draw` 决定它画在哪。文本长度恒定，
 * 所以字符下标、行盒、锚点全都不受影响。
 *
 * 画的时候向**左**偏移（`(width - natural) / 2` 为负），让字形贴着前一个字：
 * 中文标点的墨迹本来就偏在字格的一侧，收窄后不偏移会看起来"缩在中间"，
 * 相邻两个字之间凭空多出一条缝。
 */
internal class SqueezedPunctuationSpan(private val ratio: Float) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        val natural = paint.measureText(text, start, end)
        return (natural * ratio).roundToInt().coerceAtLeast(1)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val natural = paint.measureText(text, start, end)
        val width = natural * ratio
        canvas.drawText(text, start, end, x + (width - natural) / 2f, y.toFloat(), paint)
    }
}
