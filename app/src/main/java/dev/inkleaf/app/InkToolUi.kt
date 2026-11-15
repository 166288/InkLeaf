package dev.inkleaf.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.inkleaf.core.model.BrushSpec
import kotlin.math.roundToInt

/**
 * 书写工具面板：三支笔、八个墨色、一档粗细、一个橡皮。
 *
 * ## 为什么是一个面板而不是三个按钮
 *
 * "笔 / 颜色 / 粗细"在用户心里是**一支笔的属性**，不是三个独立开关。
 * 拆成工具栏上的三个图标，用户就得记住"我刚才选的是哪支 + 哪个颜色"，
 * 而它们之间还有约束（马克笔没有压感、荧光笔半透明）。做成一张面板，
 * 当前那一支笔的全部状态一眼看完，也才有地方写"这一支是什么"。
 *
 * ## 尺寸与位置
 *
 * 只占底部一条：书写时用户的手就在屏幕下半部，面板浮在笔尖附近比浮在顶部
 * 更不容易被手掌挡住。
 *
 * **它是覆盖层，不是 `Column` 的孩子**（调用处以 `Modifier.align(BottomCenter)`
 * 放进 `Box`）。这一点不是风格问题：面板一旦参与 Column 的高度分配，绘制区就变矮
 * → 整篇重排 → 用户正在写的那一笔按新排版重新锚定，表现为"我刚写的字跑了一行"。
 * 代价是它可能盖住正文最后一行（见 M13 §8 第 9 条），这个代价是明知而接受的：
 * 重排那一下的破坏比"看不见最后一行"严重得多。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InkBrushPanel(
    brush: BrushSpec,
    eraserEnabled: Boolean,
    /** 套索模式（S1）：这一串触摸是"画个圈选中笔迹"，不是"写一笔"。 */
    lassoEnabled: Boolean,
    onBrushChange: (BrushSpec) -> Unit,
    onEraserChange: (Boolean) -> Unit,
    onLassoChange: (Boolean) -> Unit,
    /** 已经选中了几笔。为 0 时套索那一行只说"画个圈"。 */
    selectedCount: Int = 0,
    /**
     * 是否显示套索入口。PDF 那一侧目前为 false —— 它的视图（`PdfInkPageView`）
     * 是另一套触摸仲裁（还有"点行选中文字"那条），套索接进去要另做一次真机验证，
     * 本轮不做。宁可不显示，也不放一个点了没反应的按钮。
     */
    showLasso: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrushSpec.TOOL_BRUSHES.forEach { candidate ->
                    BrushChip(
                        brush = candidate,
                        selected = !eraserEnabled && brush.name == candidate.name,
                        onClick = {
                            // 换笔等于退出橡皮与套索：三个模式互斥，
                            // 否则"我选了钢笔却还在擦 / 还在圈"。
                            onEraserChange(false)
                            onLassoChange(false)
                            // 笔宽跟着这一支笔的预设走（不沿用上一支）：四支笔的笔宽口径
                            // 本来就不在同一个量级（荧光笔是行高口径，见 [carryColor] 旁边
                            // 那段注释），沿用会立刻越出这一支的滑杆范围。
                            // 颜色则要**搬**而不是沿用 —— 见 [carryColor]。
                            onBrushChange(carryColor(candidate, brush))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                EraserChip(
                    selected = eraserEnabled,
                    onClick = {
                        onLassoChange(false)
                        onEraserChange(!eraserEnabled)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (showLasso) {
                    LassoChip(
                        selected = lassoEnabled,
                        onClick = {
                            onEraserChange(false)
                            onLassoChange(!lassoEnabled)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 套索模式下"墨色 / 粗细"这两行没有任何意义：圈选不写字。
            // 换成一句说明而不是留着两行灰掉的控件 —— 灰掉的控件会被当成
            // "暂时不能用"，用户会去找怎么把它点亮。
            if (lassoEnabled) {
                Text(
                    if (selectedCount == 0) {
                        "在要选的笔迹周围画个圈，圈完拖动它就能挪位置。"
                    } else {
                        "已选中 $selectedCount 笔：拖动它挪位置，在别处画圈重新选。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    // 名字必须跟着色板换：荧光笔那一栏里放的是"荧光黄/荧光绿"，
                    // 顶上却写着"墨色"，用户会以为自己选错了工具。
                    if (brush.overprint) "荧光色" else "墨色",
                    style = MaterialTheme.typography.labelMedium,
                )
                // 八色在窄屏上放不进一行，交给 FlowRow 自己折：宽屏是一行，
                // 360dp 以下的机器折成两行。**不做横向滚动** —— 色板是"一眼挑一个"
                // 的东西，把一半藏在屏幕外等于先要求用户学会"这里能滑"。
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 荧光笔用另一份色板：那八色墨色全不透明，当荧光笔用会立刻
                    // 变成"涂掉这一段"（见 BrushSpec.HIGHLIGHTER_COLORS 的注释）。
                    val palette = if (brush.overprint) {
                        BrushSpec.HIGHLIGHTER_COLORS
                    } else {
                        BrushSpec.PEN_COLORS
                    }
                    palette.forEach { color ->
                        ColorDot(
                            color = color,
                            selected = color == brush.colorArgb,
                            onClick = {
                                onEraserChange(false)
                                onBrushChange(brush.copy(colorArgb = color))
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (brush.overprint) {
                    // 荧光笔是**行高口径**（M25 §3 S2：笔宽取正文行高的 0.8～1.0 倍），
                    // 与普通笔的 0.015～0.18em 差了将近十倍 —— 共用一条滑杆的话，
                    // 要么普通笔那一段挤成看不见的一小截，要么荧光笔永远推不到最粗。
                    Text("行宽", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = brush.widthEm,
                        onValueChange = { value ->
                            onEraserChange(false)
                            // 步进 0.01em（≈ 0.007 × 行高）：这一档滑杆的全程只有 0.29em，
                            // 再细就推不出差别了。
                            onBrushChange(brush.copy(widthEm = (value * 100f).roundToInt() / 100f))
                        },
                        valueRange = BrushSpec.HIGHLIGHTER_WIDTH_EM_MIN..BrushSpec.HIGHLIGHTER_WIDTH_EM_MAX,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "荧光笔笔宽（相对行高）" },
                    )
                    // 读成"0.90 × 行高"而不是"1.305 em"：这一档滑杆说的是
                    // "盖住一行还是盖住大半行"，em 在这里只是内部单位。
                    Text(
                        text = "%.2f × 行高".format(brush.widthEm / BrushSpec.LINE_HEIGHT_EM),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Text("粗细", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = brush.widthEm,
                        onValueChange = { value ->
                            onEraserChange(false)
                            // 步进 0.005em：连续滑块会给出 0.0412371 这种值，
                            // 而笔宽存进库里是要被反复读出来比较的，圆整之后调试起来才看得懂。
                            onBrushChange(brush.copy(widthEm = (value * 200f).roundToInt() / 200f))
                        },
                        valueRange = MIN_WIDTH_EM..MAX_WIDTH_EM,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "笔宽（em）" },
                    )
                    Text(
                        text = "%.3f em".format(brush.widthEm),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            }
        }
    }
}

/**
 * 换笔时把颜色搬到新笔的色域里。
 *
 * 两份色板的 alpha 天生不同（[BrushSpec.PEN_COLORS] 全不透明，
 * [BrushSpec.HIGHLIGHTER_COLORS] 一律带 [BrushSpec.HIGHLIGHTER_ALPHA]），
 * 直接沿用的话："选了荧光黄再去选钢笔"会得到一支 alpha=0.35 的钢笔，
 * 写出来的字淡到像铅笔，用户只会认为笔坏了。
 *
 * 反方向（墨色 → 荧光笔）**保留色相、只换 alpha**：用户刚在色板上挑过一次颜色，
 * 把它丢掉换成默认黄，等于无视那一次选择。
 *
 * 笔宽不在这里搬 —— 四支笔的笔宽口径本来就不在一个量级（荧光笔是行高口径），
 * 一律用新笔预设的那一档。
 */
private fun carryColor(to: BrushSpec, from: BrushSpec): BrushSpec {
    val opaque = (from.colorArgb ushr 24) == 0xFF
    val color = when {
        to.overprint && opaque -> {
            // 墨色 → 荧光：留色相，套上叠印笔的半透明度。
            ((from.colorArgb and 0x00FFFFFF) or (BrushSpec.HIGHLIGHTER_ALPHA shl 24))
        }
        !to.overprint && !opaque -> to.colorArgb
        else -> from.colorArgb
    }
    return to.copy(colorArgb = color)
}

@Composable
private fun BrushChip(
    brush: BrushSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (brush.name) {
        "ballpoint" -> "圆珠笔"
        "marker" -> "马克笔"
        // 不写这一支就会落进 else 显示成"钢笔" —— 界面上说它是钢笔，
        // 画出来却是一条半透明的宽带，这是最伤人的一种"按钮坏了"。
        "highlighter" -> "荧光笔"
        else -> "钢笔"
    }
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            // 无可见文字之外的提示：TalkBack 必须能读出"圆珠笔，已选中"。
            // 只读"圆珠笔"会让用户不知道当前用的是哪一支。
            .semantics {
                contentDescription = "$label，${if (selected) "已选中" else "未选中"}"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            // 用一小段线示意笔宽，而不是只写名字：四支笔的差别首先是粗细，
            // 而"圆珠笔/钢笔/马克笔"这三个词对不同人意味着不同的粗细。
            //
            // 颜色用**这支笔自己的墨色**而不是主题色：荧光笔那一段于是显示为
            // 半透明的黄 —— "它是半透明的"这件事不用写字说明，看一眼就知道，
            // 这也是它与不透明的马克笔唯一能在一格里拉开的区别（两者都顶到最粗）。
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(width = 28.dp, height = (brush.widthEm * 120f).coerceIn(2f, 12f).dp)
                    .background(
                        color = Color(brush.colorArgb),
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun EraserChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (selected) "橡皮（使用中）" else "橡皮"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("橡皮", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LassoChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (selected) "套索（使用中）" else "套索"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("套索", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                // 颜色名而不是十六进制：TalkBack 读 `#FF1A1A1A` 对谁都没有意义。
                contentDescription = "${colorName(color)}，${if (selected) "已选中" else "未选中"}"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 墨色名。TalkBack 读 `#FF1A1A1A` 对谁都没有意义，所以每一格都要有个名字。
 *
 * 兜底故意是"自定义墨色"而不是"墨黑"：真出现色板之外的值（旧版本存的、
 * 或 [dev.inkleaf.core.model.BrushSpec.DEFAULT_MARKER] 的默认色）时，
 * 读成"墨黑"会让视障用户以为选错了。名字与 `BrushSpec.PEN_COLORS` 一一对应，
 * 加色时必须同时加名字 —— 否则那一格就只剩"未选中"三个字。
 */
private fun colorName(argb: Int): String = when (argb) {
    0xFF1A1A1A.toInt() -> "墨黑"
    0xFFB3261E.toInt() -> "朱红"
    0xFFE0573F.toInt() -> "朱橙"
    0xFF7A4B2A.toInt() -> "赭棕"
    0xFF2E7D32.toInt() -> "松绿"
    0xFF00695C.toInt() -> "深青"
    0xFF1F5FA8.toInt() -> "靛蓝"
    0xFF6A3E9B.toInt() -> "紫"
    // 荧光色板（`BrushSpec.HIGHLIGHTER_COLORS`）。同样一一对应，加色必须同时加名字。
    0x59FFD54F -> "荧光黄"
    0x5969F0AE -> "荧光绿"
    0x5982B1FF -> "荧光蓝"
    0x59FF80AB -> "荧光粉"
    0x59FFB74D -> "荧光橙"
    0x59B388FF -> "荧光紫"
    else -> if ((argb ushr 24) == 0xFF) "自定义墨色 $argb" else "自定义荧光色 $argb"
}

/** 笔宽范围。上限 0.18em：再宽就不是笔，是刷墙。 */
private const val MIN_WIDTH_EM = 0.015f
private const val MAX_WIDTH_EM = 0.18f
