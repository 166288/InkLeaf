package dev.inkleaf.core.document

import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 中文首行缩进的规则与落地。
 *
 * ## 为什么要分两层测
 *
 * "这一段该不该缩进"是一条可以脱离 Android 讨论的规则（[TextSpanBuilder.shouldIndentFirstLine]），
 * 所以它被写成纯函数，逐条钉死；而"缩进到底有没有落到排版上"依赖真实
 * `StaticLayout`，只能在这里跑（`NATIVE` 图形模式，真 Skia）。
 *
 * 只测前者的话，`LeadingMarginSpan` 传错了构造参数（`Standard(first, rest)`
 * 与 `Standard(rest)` 是两个字面量相同、含义不同的重载）在测试里看不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextIndentTest {

    private fun block(text: String, kind: BlockKind = BlockKind.PARAGRAPH) = TextBlock(
        index = 0,
        kind = kind,
        headingLevel = 0,
        span = CharSpan(0, text.length),
        text = text,
    )

    private fun metrics(width: Int = 1200) = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = 2000,
        marginLeftPx = 24f,
        marginRightPx = 24f,
        marginTopPx = 24f,
        marginBottomPx = 24f,
    )

    /**
     * 排版一段文本，返回**段落**左边界（含 `LeadingMarginSpan`）。
     *
     * 不能用 `getLineLeft`：那个方法只看对齐方式，对 `ALIGN_NORMAL` 恒为 0，
     * 缩进写在 `LeadingMarginSpan` 里它看不见 —— 写成 `getLineLeft` 的断言
     * 无论缩进有没有生效都是 0，等于什么都没测。
     */
    private fun firstLineLeft(text: String, textSizePx: Float = 40f): Float {
        val theme = DocumentTheme.light(textSizePx = textSizePx, lineSpacingMultiplier = 1.4f)
        val layout = DocumentCodec.buildSession("indent-doc", DocumentKind.TXT, text)
            .layout(metrics(), theme)
        return layout.sections.first().layout.getParagraphLeft(0).toFloat()
    }

    @Test
    fun `中文自然段缩进两个字号`() {
        val left = firstLineLeft("这是一段很普通的中文，用来验证首行缩进真的落到了排版上。", textSizePx = 40f)
        assertEquals(80f, left, 4f)
    }

    @Test
    fun `英文自然段不缩进`() {
        val left = firstLineLeft(
            "This is an ordinary English paragraph used to check that no indent is applied.",
            textSizePx = 40f,
        )
        assertEquals(0f, left, 1f)
    }

    @Test
    fun `规则 中文段落该缩进`() {
        assertTrue(TextSpanBuilder.shouldIndentFirstLine(block("这是一段很普通的中文。")))
    }

    @Test
    fun `规则 英文段落不该缩进`() {
        // 英文靠段间距分段。一本英文书每段缩两格，读起来像排版事故。
        assertFalse(
            TextSpanBuilder.shouldIndentFirstLine(block("This is an ordinary English paragraph.")),
        )
    }

    @Test
    fun `规则 中英混排按中文算`() {
        // 技术书里最常见的形状：术语是英文，但读者读的是中文段落。
        assertTrue(
            TextSpanBuilder.shouldIndentFirstLine(
                block("Kotlin 的 coroutine 让 suspend 函数的写法更接近同步代码。"),
            ),
        )
    }

    @Test
    fun `规则 标题不缩进`() {
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("第一章 出发", BlockKind.HEADING)))
    }

    @Test
    fun `规则 列表项不缩进`() {
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("- 第一件事", BlockKind.LIST_ITEM)))
    }

    @Test
    fun `规则 代码块不缩进`() {
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("val x = 1", BlockKind.CODE)))
    }

    @Test
    fun `规则 引用不缩进`() {
        // 引用已经有左侧留白（M8 定的层级表达），再缩首行会让它看起来像个错版。
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("> 他说过一句话。", BlockKind.QUOTE)))
    }

    @Test
    fun `规则 作者自己缩过的不再叠一层`() {
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("　　这里作者敲了两个全角空格。")))
    }

    @Test
    fun `规则 空段落不缩进`() {
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("   ")))
        assertFalse(TextSpanBuilder.shouldIndentFirstLine(block("")))
    }

    @Test
    fun `缩进量是两个字宽`() {
        assertEquals(2f, TextSpanBuilder.CJK_FIRST_LINE_INDENT_EM, 0f)
    }
}
