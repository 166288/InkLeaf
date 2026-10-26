package dev.inkleaf.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题按墨色派生（M17：阅读主题）。
 *
 * 为什么这件事值得单独测：纸色换了而墨色没换，症状不是崩溃，是
 * "羊皮纸上还是那撮灰字" —— 看起来像"主题没生效"，实际上只生效了一半。
 */
class DocumentThemeInkTest {

    private val sepiaInk = 0xFF3B2F24.toInt()

    @Test
    fun `正文用的就是给的那支墨`() {
        val theme = DocumentTheme.withInk(
            textSizePx = 40f,
            lineSpacingMultiplier = 1.4f,
            inkArgb = sepiaInk,
            isDark = false,
        )
        assertEquals(sepiaInk, theme.textColor)
        assertEquals(40f, theme.textSizePx, 0f)
        assertEquals(1.4f, theme.lineSpacingMultiplier, 0f)
    }

    @Test
    fun `浅色主题里标题比正文更深`() {
        val theme = DocumentTheme.withInk(40f, 1.4f, sepiaInk, isDark = false)
        assertTrue(
            "标题应当往黑走（否则它和正文一样，层级靠什么区分）：" +
                "${hex(theme.headingColor)} vs ${hex(theme.textColor)}",
            luminance(theme.headingColor) < luminance(theme.textColor),
        )
    }

    @Test
    fun `深色主题里标题比正文更亮`() {
        val theme = DocumentTheme.withInk(40f, 1.4f, 0xFFE6E3DC.toInt(), isDark = true)
        assertTrue(
            "深色主题里标题要往白走：${hex(theme.headingColor)} vs ${hex(theme.textColor)}",
            luminance(theme.headingColor) > luminance(theme.textColor),
        )
    }

    @Test
    fun `引用色保留墨色的色相，只是变淡`() {
        val theme = DocumentTheme.withInk(40f, 1.4f, sepiaInk, isDark = false)
        // 只动 alpha，不动 RGB：换色在六个主题里很难都协调，降不透明度在任何纸上都成立。
        assertEquals(sepiaInk and 0x00FFFFFF, theme.quoteColor and 0x00FFFFFF)
        assertTrue("引用要比正文淡", (theme.quoteColor ushr 24) < 0xFF)
    }

    @Test
    fun `代码块底色在深色与浅色下方向相反`() {
        val light = DocumentTheme.withInk(40f, 1.4f, sepiaInk, isDark = false)
        val dark = DocumentTheme.withInk(40f, 1.4f, 0xFFE6E3DC.toInt(), isDark = true)
        // 浅色主题用黑色低透明（压暗），深色主题用白色低透明（提亮）。
        assertTrue("浅色主题的代码底色应当是暗的", (light.codeBackgroundColor and 0x00FFFFFF) == 0)
        assertTrue("深色主题的代码底色应当是亮的", (dark.codeBackgroundColor and 0x00FFFFFF) == 0x00FFFFFF)
    }

    private fun hex(argb: Int): String = "#%08X".format(argb)

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
