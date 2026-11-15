package dev.inkleaf.core.document

import android.text.StaticLayout
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageGeometry
import dev.inkleaf.core.model.PageLayoutMetrics

/**
 * 设置页的"试读"排版。
 *
 * ## 为什么要有一个真的预览，而不是"调完回书里看"
 *
 * 设置页改字号 / 行距 / 边距，屏幕上原本只有滑杆标签里的数字在动 —— 读者要退出去、
 * 打开一本书、翻两页，才知道刚才那一下是变好读了还是变难读了。这是设置页最大的缺陷：
 * **它在描述结果，却不显示结果。**
 *
 * ## 为什么复用排版引擎，而不是用 Compose 的 Text 近似一下
 *
 * 预览的价值来自"它和真书里一模一样"。用 `Text` + `fontSize` + `lineHeight` 画一段，
 * 看上去差不多，但它绕过了三件真正决定版面的事：
 *
 * 1. `PageGeometry.solve` 的"边距让位给正文 / 行宽封顶"两条规则 ——
 *    于是预览里的边距会比真书里更有效，用户照着调，回到书里发现没变化；
 * 2. 中文首行缩进（`LeadingMarginSpan`）与按词组断行（`LineBreakConfig`）；
 * 3. 标题 / 引用的字号阶梯。
 *
 * 所以这里走的是打开文档的同一条路：`DocumentCodec.buildSession` → 切块 → 建 span →
 * `StaticLayout`。预览与正文只有"喂进去的文本不同"这一处差别。
 *
 * 这样一来，"一行约 N 个字"也不是拿宽度除出来的估数，而是**数第一行真实排下了几个字**
 * —— 估算与真实在窄屏、混排、标点避头尾上都会差一到两个字，而这一两个字的差别
 * 恰好是用户判断"这个字号是不是太大"的依据。
 */
object TypographyPreview {

    /**
     * 试读文本。
     *
     * 两句，各占两行 —— 这个长度是被预览区的高度反过来定的，不是随手抄的：
     *
     * - 第一句是中文自然段，能看出首行缩进、字距，以及换行后第二行是否还舒服
     *   （行距的效果主要在行与行之间）；
     * - 第二句是英文，能看出中英混排时基线有没有跳、行宽封顶之后英文是否还成行。
     *
     * 为什么不再多放一句：预览区只有四行的可见高度，第三句会被裁掉。用户看不见的
     * 内容不该出现在预览里 —— 它只让每次拖动滑杆多排一遍。
     */
    const val SAMPLE_TEXT =
        "山间的雨后小路会变得泥泞，踩上去会留下清晰的脚印。\n" +
            "The quick brown fox jumps over the lazy dog.\n"

    /** 试读文档的 id。刻意带前缀，避免与库里的真实文档撞 id。 */
    private const val SAMPLE_DOC_ID = "preview:typography"

    /**
     * 排一段试读。
     *
     * @param widthPx 预览区的宽度（与真书的正文区同宽时才具有可比性）
     * @param heightPx 预览区的高度。只用于垂直边距与裁剪，不影响行宽
     * @param userMarginPx 用户设置的页边距（毫米换算成像素）
     */
    fun build(
        widthPx: Int,
        heightPx: Int,
        textSizePx: Float,
        lineSpacingMultiplier: Float,
        userMarginPx: Float,
        density: Float,
        inkArgb: Int,
        dark: Boolean,
        typeface: android.graphics.Typeface? = null,
    ): Preview {
        // 墨色由调用方给（当前阅读主题的那一支）：预览必须与书里同一套纸墨，
        // 否则用户调完回到书里会发现"字色不一样"。字体同理（M29）。
        val theme = DocumentTheme.withInk(
            textSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            inkArgb = inkArgb,
            isDark = dark,
            typeface = typeface,
        )
        val metrics = PageGeometry.solve(
            widthPx = widthPx,
            heightPx = heightPx,
            userMarginPx = userMarginPx,
            textSizePx = textSizePx,
            density = density,
        )
        val layout = DocumentCodec
            .buildSession(SAMPLE_DOC_ID, DocumentKind.TXT, SAMPLE_TEXT)
            .layout(metrics, theme)
        return Preview(layout, metrics)
    }

    /** 一次排版的结果，连同由它测出来的事实。 */
    class Preview internal constructor(
        val layout: ReflowableTextLayout,
        val metrics: PageLayoutMetrics,
    ) {

        /** 第一行真实排下的字数（去掉行尾空白）。没排出来时是 0。 */
        val charsPerLine: Int = layout.sections.firstOrNull()
            ?.let { charsOnLine(it.layout, 0) }
            ?: 0

        /** 正文宽度占预览区宽度的比例（0..1）。行宽被封顶时它会明显小于 1。 */
        val widthRatio: Float = run {
            val total = metrics.contentWidthPx + metrics.marginLeftPx + metrics.marginRightPx
            if (total <= 0f) 0f else metrics.contentWidthPx / total
        }

        /**
         * 最后一个**完整**行在正文坐标系里的底边。
         *
         * 预览区高度是固定的，超出的部分要裁掉。按"画布高度"裁会正好切在半行字上，
         * 看起来像界面坏了；按"最后一个整行的底边"裁，收口落在行与行之间，
         * 看起来才像"这一页到这里"。
         *
         * 返回 0 表示一行都放不下（预览区被压到极小），调用方应当整块不画。
         */
        val visibleTextHeightPx: Int = run {
            var bottom = 0
            outer@ for (section in layout.sections) {
                for (line in 0 until section.layout.lineCount) {
                    val y = section.topPx + section.layout.getLineBottom(line)
                    if (y > metrics.contentHeightPx) break@outer
                    bottom = y
                }
            }
            bottom
        }
    }

    /**
     * 第 [line] 行有几个字。
     *
     * `getLineEnd` 包含行尾的换行符，所以要先从尾部剥掉空白 —— 否则"一行 21 个字"里
     * 会混进那个看不见的 `\n`，而用户数出来是 20 个。
     */
    fun charsOnLine(layout: StaticLayout, line: Int): Int {
        if (line < 0 || line >= layout.lineCount) return 0
        val text = layout.text ?: return 0
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line).coerceAtMost(text.length)
        while (end > start && text[end - 1].isWhitespace()) end--
        return (end - start).coerceAtLeast(0)
    }
}
