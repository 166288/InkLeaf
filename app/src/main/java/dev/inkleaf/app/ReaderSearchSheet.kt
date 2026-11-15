package dev.inkleaf.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.inkleaf.core.ink.ReaderSearch
import dev.inkleaf.core.document.TextSearch
import dev.inkleaf.core.ink.TypedNoteKind

/**
 * 书内搜索面板（M43/J1 第一刀）。
 *
 * ## 为什么要单独一个文件
 *
 * 这个面板此前在 `ReaderScreen.kt` 里存在**两份**：重排阅读界面一份（约 160 行）、
 * PDF 一份（约 110 行）。两份的输入框、状态行、上下翻、清单、空态提示几乎逐字相同，
 * 差别只在四个回调（跳到哪、能不能标黄、关掉时顺带清什么）。
 *
 * 抄两遍的代价不是那 100 行，而是**改一次要改两处，而漏掉一处不会有任何报错** ——
 * 一边加了"正文只列出前 500 处"的说明，另一边就悄悄少一句。
 *
 * ## 无状态
 *
 * 这里不持有任何 `remember { mutableStateOf }`：查询串、当前第几条、结果都由调用方给。
 * 于是它可以在两个入口之间自由复用，也可以被单独的预览/测试直接渲染 ——
 * 有状态的面板只能通过"操作一遍界面"来验证。
 *
 * [footer] 是给重排侧"确认位置"（重新把一条解不开的笔记挂到某一行）留的槽：
 * 那块只在重排界面成立（PDF 的笔迹锚在页上，不挂在行上），但**共用同一个面板**是对的 ——
 * 用户在那里做的事（找到那句话、跳过去）就是搜索本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    result: ReaderSearch.Result,
    /** 当前停在第几条（[ReaderSearch.Result.hits] 的下标）；-1 表示还没跳。 */
    currentIndex: Int,
    /** 归一化全文，用于给正文命中摘上下文。 */
    text: String,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /**
     * 点清单里第 [index] 条。[hit] 一并给出，省得调用方再去 `result.hits[index]` 取一次
     * —— 两处各取一次，迟早有一处取错（例如用了另一个 result）。
     */
    onJump: (index: Int, hit: ReaderSearch.Hit) -> Unit,
    /** 把这一处命中的字标成高亮。只有正文命中会调到这里。 */
    onHighlight: (ReaderSearch.TextHit) -> Unit,
    /** 面板底部额外的内容（重排侧的"确认位置"）。PDF 侧不传。 */
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("在正文和我的批注中搜索") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = searchStatusText(result, currentIndex),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onPrevious, enabled = result.count > 1) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一处")
                }
                IconButton(onClick = onNext, enabled = result.count > 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一处")
                }
            }

            val listState = rememberLazyListState()
            // 只在当前那一条不在屏幕上时滚 —— 否则用户自己滑到的位置会被每一次
            // "下一处"拽回顶部。
            LaunchedEffect(currentIndex, result) {
                if (result.isEmpty || currentIndex < 0) return@LaunchedEffect
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }) {
                    listState.animateScrollToItem(currentIndex)
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    // 空清单也要占位：它一出现/消失，下面的"确认位置"按钮就会上下跳，
                    // 而用户此时正要按它。
                    .heightIn(min = 48.dp),
                state = listState,
            ) {
                itemsIndexed(
                    items = result.hits,
                    key = { _, hit -> searchHitKey(hit) },
                ) { index, hit ->
                    // 只有正文命中能标高亮：批注命中的那一句本身已经在库里了，
                    // 再标一条只会多出一条看不见的高亮。
                    val textHit = hit as? ReaderSearch.TextHit
                    SearchHitRow(
                        hit = hit,
                        text = text,
                        current = index == currentIndex,
                        onJump = { onJump(index, hit) },
                        onHighlight = textHit?.let { { onHighlight(it) } },
                    )
                }
            }
            if (result.isEmpty && result.query.isNotEmpty()) {
                Text(
                    text = "正文和我写下的批注里都没有「${result.query}」。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            footer()

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 搜索清单上面那行"现在在第几条"。
 *
 * 计数与清单是**同一份**（[ReaderSearch.Result]），所以不会出现"清单里有 7 条、
 * 上面写着 5 条"。截断也必须说出来：少给了结果却不说，用户会以为"书里就这么多"。
 *
 * 单位用"条"而不是"处"：清单里现在既有正文里的一处命中，也有我自己写的一条批注，
 * 而一条批注不是正文里的"一处"。数与物对不上，是这类计数器最容易被用户发现的问题。
 */
internal fun searchStatusText(result: ReaderSearch.Result, index: Int): String = when {
    // 空清单的说明由清单那一段给 —— 那里能把查询串念出来（"都没有「XX」"）。
    // 两处各说一句"没有找到"，是同一个事实的两个说法，只会让人多看一眼。
    result.isEmpty -> ""
    else -> buildString {
        append("第 ${index + 1} / ${result.count} 条")
        if (result.noteCount > 0) append("（其中 ${result.noteCount} 条来自我写的批注）")
        if (result.textTruncated) append("；正文只列出前 ${TextSearch.MAX_HITS} 处")
        if (result.noteTruncated) append("；批注只列出前 ${ReaderSearch.MAX_NOTE_HITS} 条")
    }
}

/** 清单行的稳定 key：正文按起点，批注按"哪一条 + 它自己正文里的第几个字"。 */
internal fun searchHitKey(hit: ReaderSearch.Hit): String = when (hit) {
    is ReaderSearch.TextHit -> "text-${hit.start}"
    is ReaderSearch.NoteHit -> "note-${hit.annotationId}-${hit.matchStart}"
}

/**
 * 清单里这一行要显示的字，命中处加粗。
 *
 * 正文命中用 [TextSearch.contextOf]（它负责把换行折成空格、不切出半个代理对）；
 * 批注命中直接用用户自己写的那句话 —— 在他自己的字里标出命中，比给他摘一段正文更有用：
 * 他要确认的是"这是我写的那条吗"。
 */
internal fun hitPreview(hit: ReaderSearch.Hit, text: String): AnnotatedString = when (hit) {
    is ReaderSearch.TextHit -> {
        val context = TextSearch.contextOf(text, TextSearch.Hit(hit.start, hit.endExclusive))
        buildAnnotatedString {
            append(context.before)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(context.match) }
            append(context.after)
        }
    }

    is ReaderSearch.NoteHit -> buildAnnotatedString {
        append(hit.noteText.substring(0, hit.matchStart))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(hit.noteText.substring(hit.matchStart, hit.matchEndExclusive))
        }
        append(hit.noteText.substring(hit.matchEndExclusive))
    }
}

/**
 * 搜索清单里的一行。
 *
 * 两种命中共用一行，是因为它们在清单里是**同一件事**：一个能点过去的位置，
 * 外加一句话让用户确认"是不是这一处"。差别只有来源标签与可点性。
 *
 * 跳不过去的那一条（暂时对不上正文的边注）照样显示：内容一直都在，丢的只是
 * 它与正文的联系。把它藏起来，用户会以为"我写过的那句话丢了"。
 */
@Composable
private fun SearchHitRow(
    hit: ReaderSearch.Hit,
    text: String,
    current: Boolean,
    onJump: () -> Unit,
    onHighlight: (() -> Unit)?,
) {
    val jumpable = hit.charOffset != null
    Row(
        Modifier
            .fillMaxWidth()
            // 当前那一条用主色淡底标出来：清单会随着"上一处 / 下一处"移动，
            // 而计数器只给一个数字 —— 没有底色就没人知道自己在第几条上。
            .background(
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
            )
            .clickable(enabled = jumpable) { onJump() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = when (hit) {
                    is ReaderSearch.TextHit -> "正文"
                    is ReaderSearch.NoteHit -> typedKindLabel(hit.kind)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = hitPreview(hit, text),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            if (hit is ReaderSearch.NoteHit) {
                val second = if (jumpable) {
                    hit.row.excerpt?.let { "原文：$it" }
                        ?: hit.row.originalText?.let { "当时标在：$it" }
                } else {
                    // 点不动的那一条必须当场说明为什么，否则它就是一行死链。
                    hit.row.reason
                }
                second?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
        // 只有正文命中带这个按钮（见调用点）：它标的是**这一处命中的字**，
        // 所以按钮必须挨着那一行结果，而不是在面板顶上放一个管全局的。
        if (onHighlight != null) {
            IconButton(onClick = onHighlight) {
                Icon(InkIcons.Highlight, contentDescription = "把这一处标成高亮")
            }
        }
    }
}

/** 打字批注的种类名。搜索清单与笔记清单共用一套说法。 */
internal fun typedKindLabel(kind: TypedNoteKind): String = when (kind) {
    TypedNoteKind.MARGIN_NOTE -> "边注"
    TypedNoteKind.HIGHLIGHT -> "高亮"
}
