package dev.inkleaf.app

import androidx.compose.ui.graphics.Color

/**
 * 阅读主题：**纸是什么颜色、墨是什么颜色**。
 *
 * ## 为什么它属于"排版"而不是"外观"
 *
 * 因为它改的不只是背景。同一个字号在羊皮纸上显得偏小、在纯黑上显得偏细，
 * 而首行缩进、行距、下划线的可读性都随纸色变化 —— 这正是一整套纸墨系统该一起
 * 决定的事。所以这里给出的不是一个背景色，而是 [paperArgb] 与 [inkArgb] 的**一对**：
 * 排版层（`DocumentTheme`）拿墨色去排字，界面层拿纸色去铺底，两边不可能对不上。
 *
 * ## 为什么没有"自定义颜色"
 *
 * 取色器听起来自由，实际结果通常是一屏读不下去的配色（浅灰纸配中灰字、
 * 纯黑底配纯黑字）。这里给的是阅读器里被反复验证过的几套 —— 与 Kindle、
 * 微信读书、Apple Books 的选项基本重合，因为它们解决的是同一个问题：
 * 在白天、夜里、强光下都能读。
 *
 * ## AUTO 为什么带着一张纸
 *
 * [AUTO] 要靠系统明暗才能解析成具体主题，所以它的纸墨字段是**兜底值**
 * （等于 [PAPER]）。用之前一律先过 [resolve] —— 它保证结果不会是 AUTO。
 * 不这么做就得把字段写成可空类型，然后每个使用点都写一次 `!!`，
 * 那种写法迟早会在某一条冷路径上崩掉。
 */
enum class ReaderTheme(
    val label: String,
    /** 纸色（ARGB）。 */
    val paperArgb: Int,
    /** 墨色（ARGB）。排版用的正文色就取它。 */
    val inkArgb: Int,
    /** 是不是深色主题。决定状态栏图标、代码块底色这类"反色"细节。 */
    val isDark: Boolean,
    /** 一行说明。设置页里**只解释当前选中的那一个**，避免五个词排在那里没人看得懂。 */
    val hint: String,
) {

    /** 跟随系统：系统深色时用 [NIGHT]，否则 [PAPER]。 */
    AUTO("跟随系统", 0xFFFBF9F4.toInt(), 0xFF1B1B1B.toInt(), false, "白天用纸张，夜里自动换成夜间。"),

    /**
     * 纸张：暖白，不是纯白。
     *
     * 纯白（#FFFFFF）在 OLED 上长时间阅读刺眼，而且会把对比度推到极限，
     * 中文这种笔画密集的文字在纯白上反而更累。暖白是纸书的颜色。
     */
    PAPER("纸张", 0xFFFBF9F4.toInt(), 0xFF1B1B1B.toInt(), false, "暖白纸 + 深墨字。最接近纸书，白天默认用这个。"),

    /**
     * 羊皮纸：偏黄的暖褐。
     *
     * 黄纸的价值不只是"复古"：暖色底会降低屏幕蓝光比例，
     * 在夜里不想开深色模式时比白纸舒服，又不像深色那样丢失纸张感。
     */
    SEPIA("羊皮纸", 0xFFF3E8D5.toInt(), 0xFF3B2F24.toInt(), false, "偏黄的暖纸，墨色也偏褐。夜里不想用深色时用它。"),

    /**
     * 护眼绿：低饱和的灰绿。
     *
     * 刻意压低饱和度：鲜艳的绿（#C7EDCC 一类）会让文字边缘产生色晕，
     * 长读反而更容易疲劳。这一版是"能看两小时"的绿，不是"截图好看"的绿。
     */
    GREEN("护眼绿", 0xFFE4EDE2.toInt(), 0xFF22301F.toInt(), false, "低饱和的灰绿纸，墨色偏深绿。强光下比白纸柔和。"),

    /** 夜间：暖墨底 + 米白字。不是纯黑 —— 纯黑上白字会"发光"。 */
    NIGHT("夜间", 0xFF14161A.toInt(), 0xFFE6E3DC.toInt(), true, "暖墨底 + 米白字。夜里读不刺眼，也不会觉得字在发光。"),

    /**
     * 纯黑：给 OLED 屏用的。
     *
     * 与 [NIGHT] 的区别只有两点：黑是真的黑（省电、无漏光），
     * 墨色压得更暗一点（#D2D2D2 而非 #E6E3DC），否则在纯黑上对比度过高。
     */
    BLACK("纯黑", 0xFF000000.toInt(), 0xFFD2D2D2.toInt(), true, "真正的黑底，OLED 屏最省电。字比「夜间」稍暗一档，避免刺眼。"),
    ;

    /** 纸色（Compose 用）。 */
    val paper: Color get() = Color(paperArgb)

    /** 墨色（Compose 用）。 */
    val ink: Color get() = Color(inkArgb)

    /**
     * 把 [AUTO] 解析成具体主题。其它值原样返回。
     *
     * 传的是"系统是不是深色"，而不是 `DarkModePreference`：那个偏好已经被
     * 这张表取代了（见 `ReaderSettings.darkMode` 的注释），再让它参与解析
     * 就会出现"主题说羊皮纸、明暗说深色"这种两个真相源打架的状态。
     */
    fun resolve(systemDark: Boolean): ReaderTheme = when (this) {
        AUTO -> if (systemDark) NIGHT else PAPER
        else -> this
    }

    companion object {
        /** 设置表里存的是枚举名。读不出来就退回 [AUTO]（设置不是锚点，宽容是合理的）。 */
        fun fromName(name: String?): ReaderTheme =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
