package dev.inkleaf.app

import dev.inkleaf.core.data.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读主题（纸色 + 墨色）。
 *
 * 这里测三件事，每件都有明确的失败症状：
 *
 * 1. **解析**（AUTO → 具体主题）。错了的症状是"跟随系统在深色模式下没变暗"。
 * 2. **对比度**。错了的症状不是崩溃，是"读十分钟眼睛疼" —— 只有算出来才说得清，
 *    所以这里真的按 WCAG 的相对亮度公式算，而不是目测。
 * 3. **老库迁移**。错了的症状是升级后"我的深色模式变回白纸了"，
 *    而当时正好是夜里。
 */
class ReaderThemeTest {

    @Test
    fun `跟随系统在深色时用夜间、浅色时用纸张`() {
        assertEquals(ReaderTheme.NIGHT, ReaderTheme.AUTO.resolve(systemDark = true))
        assertEquals(ReaderTheme.PAPER, ReaderTheme.AUTO.resolve(systemDark = false))
    }

    @Test
    fun `选定了主题就不再跟随系统`() {
        // 这是"主题"与旧的"明暗三档"最关键的区别：用户明确选了羊皮纸，
        // 手机切到深色也不该把它变成夜间。
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.SEPIA.resolve(systemDark = true))
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.SEPIA.resolve(systemDark = false))
        assertEquals(ReaderTheme.BLACK, ReaderTheme.BLACK.resolve(systemDark = false))
    }

    @Test
    fun `每个主题的纸墨对比度都够读正文`() {
        // 7:1 是 WCAG AAA 对正文的要求。低于它的配色在强光下会"字糊进纸里"。
        ReaderTheme.entries.filter { it != ReaderTheme.AUTO }.forEach { theme ->
            val ratio = contrastRatio(theme.paperArgb, theme.inkArgb)
            assertTrue(
                "${theme.label} 的纸墨对比度只有 %.2f:1，低于正文可读的 7:1".format(ratio),
                ratio >= 7.0,
            )
        }
    }

    @Test
    fun `深色标记与纸的明暗一致`() {
        // isDark 决定状态栏图标、代码块底色。它与实际纸色不一致时，
        // 症状是"深色纸配深色图标"—— 状态栏那一条直接看不见。
        ReaderTheme.entries.filter { it != ReaderTheme.AUTO }.forEach { theme ->
            val paperLuminance = relativeLuminance(theme.paperArgb)
            if (theme.isDark) {
                assertTrue("${theme.label} 标成深色，纸却不够暗（$paperLuminance）", paperLuminance < 0.2)
            } else {
                assertTrue("${theme.label} 标成浅色，纸却不够亮（$paperLuminance）", paperLuminance > 0.6)
            }
        }
    }

    @Test
    fun `六种主题的纸色互不相同`() {
        // 色卡点下去必须有可见变化。两个主题撞色时用户会以为按钮坏了。
        val papers = ReaderTheme.entries.filter { it != ReaderTheme.AUTO }.map { it.paperArgb }
        assertEquals("有重复的纸色：$papers", papers.size, papers.toSet().size)
    }

    @Test
    fun `枚举名能往返`() {
        ReaderTheme.entries.forEach { theme ->
            assertEquals(theme, ReaderTheme.fromName(theme.name))
        }
        assertEquals("读不出来就退回跟随系统", ReaderTheme.AUTO, ReaderTheme.fromName("NOPE"))
    }

    // ── 设置迁移 ────────────────────────────────────────────────────────────

    @Test
    fun `老库只有明暗三档时迁移成对应主题`() {
        // 旧版本存的键是 ui.dark_mode。升级后直接忽略它，用户会在夜里
        // 突然看见一张白纸 —— 这是最刺眼的一种"升级体验"。
        assertEquals(
            ReaderTheme.NIGHT,
            ReaderSettings.from(mapOf(SettingKeys.DARK_MODE to "DARK")).readerTheme,
        )
        assertEquals(
            ReaderTheme.PAPER,
            ReaderSettings.from(mapOf(SettingKeys.DARK_MODE to "LIGHT")).readerTheme,
        )
        assertEquals(
            ReaderTheme.AUTO,
            ReaderSettings.from(mapOf(SettingKeys.DARK_MODE to "SYSTEM")).readerTheme,
        )
    }

    @Test
    fun `新键优先于旧键`() {
        val map = mapOf(
            SettingKeys.READER_THEME to "SEPIA",
            SettingKeys.DARK_MODE to "DARK",
        )
        assertEquals(ReaderTheme.SEPIA, ReaderSettings.from(map).readerTheme)
    }

    @Test
    fun `阅读主题与界面明暗互不推导`() {
        // 曾经是同一个字段：选「夜间」界面也必须跟着深色，因为"整套界面同一套明暗
        // 才不割裂"。实际用起来恰好相反 —— 书架留浅色、只在夜里把书换成深色纸，
        // 是很常见的用法。下列两种组合都必须原样存在。
        val darkUiPaperBook = ReaderSettings(
            uiDarkMode = DarkModePreference.DARK,
            readerTheme = ReaderTheme.PAPER,
        )
        assertEquals(ReaderTheme.PAPER, darkUiPaperBook.readerTheme)
        assertEquals(DarkModePreference.DARK, darkUiPaperBook.uiDarkMode)

        val lightUiNightBook = ReaderSettings(
            uiDarkMode = DarkModePreference.LIGHT,
            readerTheme = ReaderTheme.NIGHT,
        )
        assertEquals(ReaderTheme.NIGHT, lightUiNightBook.readerTheme)
        assertEquals(DarkModePreference.LIGHT, lightUiNightBook.uiDarkMode)
    }

    @Test
    fun `写设置时两个键各写自己的值`() {
        val map = ReaderSettings(
            readerTheme = ReaderTheme.BLACK,
            uiDarkMode = DarkModePreference.LIGHT,
        ).toSettingsMap()
        assertEquals("BLACK", map[SettingKeys.READER_THEME])
        // 界面明暗不再派生自阅读主题：书里纯黑，书架仍可以是浅色。
        assertEquals("LIGHT", map[SettingKeys.DARK_MODE])
    }

    @Test
    fun `界面明暗三档解析成布尔`() {
        assertTrue("深色档在浅色系统下仍是深色", DarkModePreference.DARK.resolveDark(systemDark = false))
        assertFalse("浅色档在深色系统下仍是浅色", DarkModePreference.LIGHT.resolveDark(systemDark = true))
        assertTrue(DarkModePreference.SYSTEM.resolveDark(systemDark = true))
        assertFalse(DarkModePreference.SYSTEM.resolveDark(systemDark = false))
    }

    @Test
    fun `界面明暗从老库读出，大小写都能认`() {
        // DEFAULT_SETTINGS 曾经写的是小写 `system`。直接 valueOf 会静默退化成
        // 字段默认值 —— 恰好也是 SYSTEM，所以这个错误在测试里不会有症状，
        // 但换成 DARK 的小写形态就会真的丢设置。
        assertEquals(
            DarkModePreference.SYSTEM,
            ReaderSettings.from(mapOf(SettingKeys.DARK_MODE to "system")).uiDarkMode,
        )
        assertEquals(
            DarkModePreference.DARK,
            ReaderSettings.from(mapOf(SettingKeys.DARK_MODE to "dark")).uiDarkMode,
        )
    }

    @Test
    fun `默认设置与默认值表一致`() {
        // 两处不一致的症状是"设置页显示 17sp、实际排版用别的"，
        // 而且只在第一次启动出现（AppContainer 注释里写过这条）。
        val defaults = ReaderSettings()
        assertEquals("AUTO", defaults.toSettingsMap()[SettingKeys.READER_THEME])
        assertEquals(
            "界面默认跟随系统",
            "SYSTEM",
            defaults.toSettingsMap()[SettingKeys.DARK_MODE],
        )
        assertFalse("默认不限制成只用笔", defaults.stylusOnly)
        assertFalse("默认是列表", defaults.libraryGrid)
    }

    // ── 颜色计算 ────────────────────────────────────────────────────────────

    /** WCAG 相对亮度。 */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
