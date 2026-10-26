package dev.inkleaf.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * 设计令牌。
 *
 * [AnchorState] 的三色是这一层**必须**存在的东西，不是可选的主题美化：
 * 方案 §6.3.4 要求"每一处锚点的状态对用户可见"，而三种状态
 * （已定位 / 需要确认 / 已失效）如果不共用同一套颜色语义，
 * 用户就得在阅读界面学一遍、在笔记列表里再学一遍。
 *
 * 颜色刻意选得彼此可分且不依赖色相细节（对比度也拉开），
 * 因为"需要确认"和"已失效"在灰度下也必须能区分开 —— 这是精神病学上
 * 红绿色盲用户占比约 8% 的直接后果，而笔记状态是必须传达的信息。
 */
object InkLeafTokens {

    // ── 阅读纸面 ─────────────────────────────────────────────────────────────
    /** 浅色纸面：刻意不是纯白（#FFFFFF），纯白在 OLED 上长时间阅读刺眼。 */
    val PaperLight = Color(0xFFFBF9F4)
    val PaperDark = Color(0xFF14161A)

    /** 正文墨色。与 `DocumentTheme.light/dark` 的 textColor 保持一致。 */
    val InkLight = Color(0xFF1B1B1B)
    val InkDark = Color(0xFFE6E3DC)

    // ── 锚点状态 ─────────────────────────────────────────────────────────────
    /** 已定位：不需要用户做任何事。用中性色，避免抢注意力。 */
    val AnchorResolved = Color(0xFF2E7D32)

    /** 需要确认：锚点被重新匹配到了候选位置，必须由用户看一眼。用琥珀色（警示但不等于错误）。 */
    val AnchorAmbiguous = Color(0xFFB26A00)

    /** 已失效：找不到位置，只能按进度兜底。用偏冷的灰红，与"错误红"区分（这不是崩溃）。 */
    val AnchorOrphaned = Color(0xFF8C4A4A)

    /** 高亮默认色。与 `BrushSpec.DEFAULT_HIGHLIGHTER` 一致。 */
    val HighlightDefault = Color(0x66FFD54F)
}

/**
 * 主题。
 *
 * 排版上做了一处与 Material 默认不同的选择：**正文用衬线字体族**。
 * 中文正文在衬线字体下长段落可读性明显更好，而这是阅读器的主场景；
 * 界面控件仍走默认无衬线，两者不会互相干扰。
 * 这里只改"正文文本"这一档，不动标题与控件，避免变成一个"到处都很文艺"的 App。
 */
private val InkLeafTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            lineHeight = 23.sp,
        ),
    )
}

/**
 * 浅色方案。
 *
 * ## 为什么要把 Material 的"没指定就自己算"改成一套完整的暖灰
 *
 * 之前只给了 primary / surface / background 三项，其余角色由 `lightColorScheme`
 * 的兜底值填充 —— 而兜底值是**淡紫**（Material 3 的基线色板）。于是界面上出现了
 * 一块本工程从未选择过的颜色：滑杆的未激活轨道、选中的筛选芯片、书架的卡片底，
 * 全是 #E8DEF8 一带的紫。在暖白纸上它们看起来像另一个应用的控件贴了进来。
 *
 * 这里把与"纸与墨"直接冲突的那几档一次补齐：容器色一律取纸的暖灰（在纸色里
 * 掺一点墨），outline 取暖灰而不是中性灰。primary 保持原来的墨蓝 —— 它不是装饰，
 * 是"可点的东西"的唯一标识（M15 §5.1：朱红只用于已经发生的标记，蓝色用于动作）。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3A5A78),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F0),
    onPrimaryContainer = Color(0xFF14263A),
    secondary = Color(0xFF6B6157),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6DA),
    onSecondaryContainer = Color(0xFF2A241C),
    surface = InkLeafTokens.PaperLight,
    onSurface = InkLeafTokens.InkLight,
    surfaceVariant = Color(0xFFEFEAE0),
    onSurfaceVariant = Color(0xFF5B564E),
    surfaceTint = Color(0xFF3A5A78),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF7F3EB),
    surfaceContainer = Color(0xFFF3EEE4),
    surfaceContainerHigh = Color(0xFFEDE8DE),
    surfaceContainerHighest = Color(0xFFE7E2D7),
    background = InkLeafTokens.PaperLight,
    onBackground = InkLeafTokens.InkLight,
    outline = Color(0xFFA6A093),
    outlineVariant = Color(0xFFD9D3C7),
    inverseSurface = Color(0xFF322F2A),
    inverseOnSurface = Color(0xFFF4EFE6),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FBAD4),
    onPrimary = Color(0xFF10233B),
    primaryContainer = Color(0xFF2A3F55),
    onPrimaryContainer = Color(0xFFD7E5F3),
    secondary = Color(0xFFC9BFB2),
    onSecondary = Color(0xFF332D25),
    secondaryContainer = Color(0xFF3A342B),
    onSecondaryContainer = Color(0xFFEDE6DA),
    surface = InkLeafTokens.PaperDark,
    onSurface = InkLeafTokens.InkDark,
    surfaceVariant = Color(0xFF2A2C31),
    onSurfaceVariant = Color(0xFFB9B4AA),
    surfaceTint = Color(0xFF9FBAD4),
    surfaceContainerLowest = Color(0xFF0F1114),
    surfaceContainerLow = Color(0xFF191B1F),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF24272C),
    surfaceContainerHighest = Color(0xFF2B2E34),
    background = InkLeafTokens.PaperDark,
    onBackground = InkLeafTokens.InkDark,
    outline = Color(0xFF6E6A62),
    outlineVariant = Color(0xFF3A3C41),
    inverseSurface = Color(0xFFE6E3DC),
    inverseOnSurface = Color(0xFF2A2721),
)

@Composable
fun InkLeafTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = InkLeafTypography,
        content = content,
    )
}
