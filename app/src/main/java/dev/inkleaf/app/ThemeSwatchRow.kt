package dev.inkleaf.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 阅读主题色卡：六档纸墨 + 一个「跟随系统」。
 *
 * 每张卡上直接印一个"文"字，用**该主题的墨色**。这比只给一个色块有用得多：
 * 用户要判断的不是"这个背景色好不好看"，而是"这张纸上这个字读起来怎么样"。
 * 所以这里展示的是一对纸墨，而不是一种颜色。
 *
 * 「跟随系统」单独做成一个 chip 而不是第六张卡：它不是一种纸，
 * 而是一条规则（白天用纸张、夜里用夜间），放进色卡排里会让人以为它是第六种配色。
 *
 * ## 为什么这个组件被两处共用
 *
 * 它是**阅读页**的主题选择器：设置页里那一档管的是阅读以外的界面（三档明暗），
 * 两者不是同一个东西。它出现在两个地方 —— 阅读界面的排版面板（主入口，
 * 在那里改能立刻看到正文重排），以及设置页的试读卡（让用户先看一眼纸墨的样子）。
 * 两处必须是同一个组件，否则会出现"设置页说羊皮纸长这样、书里却长那样"。
 *
 * @param compact 收紧间距。排版面板里下面还有三个滑杆，56 dp 的卡 + 三行说明
 *   会把整个面板顶满；紧凑档把卡缩到 48 dp、行距也收一档。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSwatchRow(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit,
    compact: Boolean = false,
) {
    val following = selected == ReaderTheme.AUTO
    val swatchSize = if (compact) 44.dp else 56.dp
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            ReaderTheme.entries.filter { it != ReaderTheme.AUTO }.forEach { candidate ->
                val isSelected = !following && candidate == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(swatchSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(candidate.paper)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelect(candidate) }
                            // 读屏要能分辨"哪一张是当前用的"。只读"羊皮纸"不够 ——
                            // 六张卡念下来，用户不知道自己停在哪一张上。
                            .semantics {
                                contentDescription =
                                    "阅读主题：${candidate.label}${if (isSelected) "，已选中" else ""}"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "文",
                            color = candidate.ink,
                            style = if (compact) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                        )
                    }
                    Text(
                        candidate.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        FilterChip(
            selected = following,
            onClick = { onSelect(ReaderTheme.AUTO) },
            label = { Text("跟随系统") },
        )
        Text(
            selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}
