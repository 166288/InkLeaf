package dev.inkleaf.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.model.ReadingStats
import dev.inkleaf.core.model.ReadingStatsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DocumentReadingStat(
    val docId: String,
    val title: String,
    val durationMs: Long,
)

data class StatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: ReadingStats? = null,
    val perDocument: List<DocumentReadingStat> = emptyList(),
)

/**
 * 阅读统计（M12）。
 *
 * 数据只有一个来源：`reading_session` 表里一段一段的前台阅读时长。
 * 聚合口径在 [ReadingStatsCalculator]（纯 Kotlin、有单测），这里只负责把
 * 已经算好的事实摆到屏幕上 —— 界面不重新定义"今天"或"连续几天"。
 */
class StatsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val sessions = container.store.readingSessions.recent()
                val documents = container.store.documents.list().associateBy { it.id }
                val stats = ReadingStatsCalculator.compute(sessions, System.currentTimeMillis())
                val perDocument = ReadingStatsCalculator.perDocument(sessions)
                    .entries
                    .sortedByDescending { it.value }
                    .mapNotNull { (docId, ms) ->
                        documents[docId]?.let { meta ->
                            DocumentReadingStat(docId = docId, title = meta.title, durationMs = ms)
                        }
                    }
                _state.value = StatsUiState(
                    loading = false,
                    stats = stats,
                    perDocument = perDocument,
                )
            } catch (e: Exception) {
                _state.value = StatsUiState(loading = false, error = e.message ?: "读取统计失败")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onClose: () -> Unit) {
    val vm: StatsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VmFactory { StatsViewModel(container) },
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读统计") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Hint(state.error!!)
                state.loading -> Hint("正在统计…")
                state.stats == null -> Hint("还没有可显示的统计。")
                else -> StatsBody(state)
            }
        }
    }
}

@Composable
private fun StatsBody(state: StatsUiState) {
    val stats = state.stats ?: return
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("总阅读时长", formatDuration(stats.totalMs), Modifier.weight(1f))
                StatCard("今天", formatDuration(stats.todayMs), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("连续阅读", "${stats.streakDays} 天", Modifier.weight(1f))
                StatCard("有阅读的日子", "${stats.daysRead} 天", Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近 7 天", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    WeekBars(stats)
                }
            }
        }
        item {
            Text(
                "只统计阅读界面在前台的时间；切到后台、锁屏都不算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.perDocument.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("还没有阅读记录", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "打开一本书读一会儿（至少几秒），回到书架再进这里，就会看到时长。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    "每本书",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(state.perDocument, key = { it.docId }) { doc ->
                DocumentStatRow(doc, stats.totalMs)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WeekBars(stats: ReadingStats) {
    val max = stats.last7Days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
    val today = stats.last7Days.lastOrNull()?.epochDay
    Row(
        Modifier.fillMaxWidth().height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (day in stats.last7Days) {
            val ratio = if (max == 0L) 0f else day.durationMs.toFloat() / max
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = if (day.durationMs > 0) shortDuration(day.durationMs) else "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height((72f * ratio).dp.coerceAtLeast(if (day.durationMs > 0) 2.dp else 0.dp))
                        .background(
                            color = if (day.epochDay == today) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            },
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (day.epochDay == today) "今天" else dayLabel(day.epochDay),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DocumentStatRow(doc: DocumentReadingStat, totalMs: Long) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    formatDuration(doc.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (totalMs > 0) {
                Text(
                    "%.0f%%".format(doc.durationMs * 100.0 / totalMs),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 统计页的时长口径：分钟以下说"不到 1 分钟"，避免把 20 秒显示成 0。 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 分钟"
    val totalMinutes = ms / 60_000
    return when {
        totalMinutes < 1 -> "不到 1 分钟"
        totalMinutes < 60 -> "$totalMinutes 分钟"
        else -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
        }
    }
}

private fun shortDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}

private fun dayLabel(epochDay: Long): String =
    java.time.LocalDate.ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofPattern("M/d", Locale.getDefault()))
