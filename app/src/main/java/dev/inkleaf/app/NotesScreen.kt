package dev.inkleaf.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.data.AnnotationSnapshot
import dev.inkleaf.core.ink.HandwritingOcr
import dev.inkleaf.core.ink.InkThumbnail
import dev.inkleaf.core.ink.NoteMarkdown
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.AnnotationKind
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.model.kind
import dev.inkleaf.core.model.summaryText
import dev.inkleaf.core.ui.InkLeafTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotesUiState(
    val title: String = "",
    val author: String? = null,
    val snapshot: AnnotationSnapshot = AnnotationSnapshot.EMPTY,
    val loading: Boolean = true,
    val error: String? = null,
)

class NotesViewModel(private val container: AppContainer, private val docId: String) : ViewModel() {

    private val _state = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val meta: DocumentMeta? = container.store.documents.byId(docId)
                if (meta == null) {
                    _state.value = NotesUiState(loading = false, error = "这本书已经不在书架上了")
                    return@launch
                }
                _state.value = NotesUiState(
                    title = meta.title,
                    author = meta.author,
                    snapshot = container.store.annotations.listByDoc(docId),
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "读取笔记失败")
            }
        }
    }

    fun delete(annotationId: String) {
        viewModelScope.launch {
            container.store.annotations.delete(annotationId)
            refresh()
        }
    }

    /** 识别结果：标注 id → 给用户的那句话（认出的文字 / 没认出来 / 失败原因）。 */
    private val _recognized = MutableStateFlow<Map<String, String>>(emptyMap())
    val recognized: StateFlow<Map<String, String>> = _recognized.asStateFlow()

    /**
     * 认一条手写（S3 的 L3）。
     *
     * 三种结果必须分开说：认出来了、没认出来、**识别失败**。
     * 后两者混成一句"没认出来"，用户会一直重试，而失败那一类重试永远不成。
     *
     * @param paperArgb 缩略图的底色。传进来的必须是书里那张纸：
     *        荧光笔的混色方向由它决定，底色错了会认不出深色纸上的字。
     */
    fun recognize(annotationId: String, paperArgb: Int) {
        val engine = container.handwritingOcr ?: return
        viewModelScope.launch {
            _recognized.value += (annotationId to "正在识别…")
            val snapshot = _state.value.snapshot
            val strokes = snapshot.strokesOf(annotationId)
            val brushes = snapshot.brushesOf(annotationId)
            val message = withContext(Dispatchers.Default) {
                val bitmap = InkThumbnail.render(strokes, brushes, OCR_SIZE_PX, paperArgb)
                    ?: return@withContext "这一条没有可以识别的墨。"
                runCatching { engine.recognize(bitmap) }.fold(
                    onSuccess = { result ->
                        val text = result?.text?.trim().orEmpty()
                        if (text.isEmpty()) {
                            "没认出来。"
                        } else {
                            result?.confidence?.let { "「$text」（把握 %.0f%%）".format(it * 100f) }
                                ?: "「$text」"
                        }
                    },
                    onFailure = { "识别失败：${it.message ?: "引擎出错"}。重试不会更好。" },
                )
            }
            _recognized.value += (annotationId to message)
        }
    }
}

/**
 * 交给识别引擎的图，比清单里的缩略图大得多。
 *
 * 160px 的图够"让人认出这一笔"，不够让模型看清笔画结构。
 * `InkThumbnail` 是等比缩放，所以放大只是多给细节，不会改变形状。
 */
private const val OCR_SIZE_PX = 512

/**
 * 笔记面板。
 *
 * 目前它列出的是**存储层的原始事实**（锚点指向哪个块、哪一段字符），
 * 而不是"已定位 / 需要确认 / 已失效"三态 —— 因为三态要由锚点解析器
 * 在**当前排版结果**上跑一遍才能得出，那属于 M3。
 * 这里刻意不假装有三态：一个不基于真实排版结果的"已定位"标记
 * 比没有标记更有害。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    container: AppContainer,
    docId: String,
    onClose: () -> Unit,
    /**
     * "跳回原位"：点一条笔记 → 打开阅读器并定位到它。
     *
     * 传的是**笔记 id**而不是偏移 —— 偏移要等排版结果出来才算得出，
     * 而笔记 id 在库里就有，阅读器自己算更准（它有当前排版）。
     */
    onOpenNote: (String) -> Unit = {},
) {
    val vm: NotesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "notes-$docId",
        factory = VmFactory { NotesViewModel(container, docId) },
    )
    val state by vm.state.collectAsState()
    val settings by container.settings.collectAsState()
    val recognized by vm.recognized.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    /**
     * 缩略图的底色必须是**书里那张纸**。
     *
     * 这不是配色讲究：荧光笔的混色方向由纸色决定（浅纸正片叠底 / 深纸滤色），
     * 底色给错了，夜间纸上的荧光笔在缩略图里会整个消失。
     * 判定的唯一一份实现在 `InkStrokeRenderer.isDarkPaper`，这里只负责把纸色取对。
     */
    val paperArgb = settings.readerTheme.resolve(isSystemInDarkTheme()).paperArgb

    /**
     * "只看有手写的"。
     *
     * 手写检索 L1 的另一半：几十条笔记里找"我画过图的那一页"，
     * 靠读文字是找不到的（手写的摘要文本是空的）。
     */
    var inkOnly by remember { mutableStateOf(false) }
    val visible = remember(state.snapshot, inkOnly) {
        if (inkOnly) state.snapshot.annotations.filter { it is Annotation.Ink }
        else state.snapshot.annotations
    }

    /**
     * 待写入的 Markdown。
     *
     * 系统文件选择器是异步的：用户可能在对话框里挑目录、改名、甚至取消。
     * 内容必须在**按下导出那一刻**就定下来，而不是等回调再读一次库 ——
     * 那会让"我导出的是刚才看到的那些笔记吗"变成一个没有答案的问题。
     */
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("系统没有给出可写的文件")
                }
            }
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "已导出 Markdown 笔记" },
                    onFailure = { "导出失败：${it.message ?: "无法写入所选文件"}" },
                ),
            )
        }
    }

    LaunchedEffect(docId) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记${if (state.title.isNotBlank()) " · ${state.title}" else ""}", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回阅读")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.loading && state.error == null,
                        onClick = {
                            pendingExport = NoteMarkdown.render(
                                title = state.title,
                                author = state.author,
                                notes = NoteMarkdown.fromAnnotations(state.snapshot.annotations) { id ->
                                    state.snapshot.strokesOf(id).size
                                },
                                exportedAt = System.currentTimeMillis(),
                            )
                            exportLauncher.launch(exportFileName(state.title))
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "导出 Markdown 笔记")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Hint(state.error!!)
                state.loading -> Hint("正在读取笔记…")
                state.snapshot.isEmpty && state.snapshot.unreadableAnnotationCount == 0 ->
                    Hint("还没有笔记。\n（手写与圈选需要 :core:ink，见里程碑 M3。）")

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !inkOnly,
                                onClick = { inkOnly = false },
                                label = { Text("全部 ${state.snapshot.annotations.size}") },
                            )
                            FilterChip(
                                selected = inkOnly,
                                onClick = { inkOnly = true },
                                label = {
                                    Text(
                                        "只看手写 " +
                                            state.snapshot.annotations.count { it is Annotation.Ink },
                                    )
                                },
                            )
                        }
                    }
                    item {
                        // 有没有识别能力必须**说出来**。默认状态是"没接引擎"，
                        // 而一个从来不出现的按钮不会告诉用户"本来可以有"。
                        Text(
                            when (val engine = container.handwritingOcr) {
                                null -> "手写识别：未接入引擎（本应用不内置模型）"
                                else -> "手写识别：${engine.displayName}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.snapshot.unreadableAnnotationCount > 0 || state.snapshot.unreadableStrokeCount > 0) {
                        item {
                            // 数据损坏必须**可见**。让它静默少一条是本工程明确要避免的行为。
                            Card {
                                Column(Modifier.padding(12.dp)) {
                                    Text("有数据无法读取", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "标注 ${state.snapshot.unreadableAnnotationCount} 条、" +
                                            "笔迹 ${state.snapshot.unreadableStrokeCount} 条读取失败。" +
                                            "它们没有被猜着放回页面 —— 猜出来的位置比没有更危险。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    items(visible, key = { it.id }) { annotation ->
                        NoteCard(
                            annotation = annotation,
                            strokes = state.snapshot.strokesOf(annotation.id),
                            brushes = state.snapshot.brushesOf(annotation.id),
                            paperArgb = paperArgb,
                            ocr = container.handwritingOcr,
                            recognizedText = recognized[annotation.id],
                            onRecognize = { vm.recognize(annotation.id, paperArgb) },
                            onDelete = { vm.delete(annotation.id) },
                            onOpen = { onOpenNote(annotation.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一张手写笔记的缩略图（S3 的 L1）。
 *
 * 生成放在 `Dispatchers.Default` 而不是主线程：一张 120px 的位图不重，
 * 但清单里可能有几十条手写，一次全在主线程建位图 + 画折线，滑动会掉帧。
 *
 * 生成期间显示的是**空框**而不是占位动画：它只是一格 64dp 的方块，
 * 播动画反而比画出它更贵。
 */
@Composable
private fun InkThumbnailBox(
    strokes: List<StrokeAnchor>,
    brushes: List<BrushSpec>,
    paperArgb: Int,
    modifier: Modifier = Modifier,
) {
    var image by remember(annotationKeyOf(strokes, brushes, paperArgb)) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(annotationKeyOf(strokes, brushes, paperArgb)) {
        val bitmap = withContext(Dispatchers.Default) {
            InkThumbnail.render(strokes, brushes, THUMBNAIL_PX, paperArgb)
        }
        image = bitmap?.asImageBitmap()
    }
    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "这笔手写的缩略图",
            modifier = modifier
                .size(THUMBNAIL_DP)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Box(
            modifier
                .size(THUMBNAIL_DP)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(paperArgb)),
        )
    }
}

/**
 * `remember` / `LaunchedEffect` 的键。
 *
 * 用"段数 + 笔刷数 + 纸色"而不是 List 本身：列表每次重组都是新对象
 * （`strokesOf` 从 Map 里取出来），拿它当键会让缩略图**每次重组都重画一遍**。
 */
private fun annotationKeyOf(
    strokes: List<StrokeAnchor>,
    brushes: List<BrushSpec>,
    paperArgb: Int,
): String = "${strokes.size}/${strokes.firstOrNull()?.strokeId}/${brushes.size}/$paperArgb"

/** 缩略图边长：清单里 64dp 够认出一个圈，再大就是让清单变卡。 */
private val THUMBNAIL_DP = 64.dp
private const val THUMBNAIL_PX = 160

@Composable
private fun NoteCard(
    annotation: Annotation,
    strokes: List<StrokeAnchor>,
    brushes: List<BrushSpec>,
    paperArgb: Int,
    /** 识别引擎。null = 没接，此时界面上不出现任何识别入口。 */
    ocr: HandwritingOcr?,
    /** 这一条的识别结果（null = 还没认过）。 */
    recognizedText: String?,
    onRecognize: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // 左侧色条：用的就是标注自己的颜色，因此它同时是"这条笔记长什么样"的预览。
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color(annotation.colorArgb), RoundedCornerShape(2.dp)),
            )
            // 手写给一张缩略图：几十条笔记里找"我画过图的那一页"，
            // 靠读文字是找不到的（手写的摘要文本是空的）。
            if (annotation is Annotation.Ink && strokes.isNotEmpty()) {
                InkThumbnailBox(
                    strokes = strokes,
                    brushes = brushes,
                    paperArgb = paperArgb,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            // 整块内容可点：点一下就回到它在书里的位置。
            // 为什么放在 Column 而不是整个 Card：Card 里还有删除按钮，
            // 整个 Card 可点会让"删除"这个动作变得含糊。
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kindLabel(annotation.kind), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "  " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(Date(annotation.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    annotation.summaryText(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    describeAnchor(annotation),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (annotation is Annotation.Ink) {
                    Text(
                        "笔迹 ${strokes.size} 段 · 笔刷 ${brushes.size} 种 · 语义 ${annotation.semantics.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (recognizedText != null) {
                    Text(
                        recognizedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkLeafTokens.AnchorResolved,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (annotation.tags.isNotEmpty()) {
                    Text(
                        annotation.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkLeafTokens.AnchorResolved,
                    )
                }
            }
            // 识别入口只在**真的有引擎**时出现。没接引擎却摆一个按钮，
            // 点了没反应（或者弹"暂不支持"）比没有这个按钮更让人困惑。
            if (ocr != null && annotation is Annotation.Ink) {
                IconButton(onClick = onRecognize) {
                    Icon(Icons.Default.Search, contentDescription = "识别这一段手写")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除笔记")
            }
            IconButton(onClick = onOpen) {
                Icon(InkIcons.OpenInNew, contentDescription = "跳到原文")
            }
        }
    }
}

private fun describeAnchor(annotation: Annotation): String = when (annotation) {
    is Annotation.Highlight -> describeChain(annotation.anchor)
    is Annotation.MarginNote -> describeChain(annotation.anchor) + "（边注：" + annotation.side.name + "）"
    is Annotation.Ink -> annotation.anchor?.let { describeChain(it) } ?: "整页自由笔迹（不随重排移动）"
    is Annotation.Underline -> "跨 ${annotation.anchors.size} 行：" + describeChain(annotation.anchors.first())
    is Annotation.Bookmark -> "阅读位置，" +
        "全文 ${"%.0f%%".format(annotation.progressHint.docRatio * 100)}" +
        (pageNumberOf(annotation.progressHint.pageKey)?.let { "，第 $it 页" } ?: "")
}

/**
 * `p3` 这种页键 → `"3"`；不是页键就返回 null。
 *
 * PDF 的锚点链把页身份存在进度兜底选择器里（`ProgressHint.pageKey`），
 * 笔记面板把它翻译成"第 3 页"。重排格式的链没有页键，这里就不会多出一句话。
 */
private fun pageNumberOf(pageKey: String?): String? =
    pageKey?.removePrefix("p")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

private fun describeChain(chain: TextAnchorChain): String {
    val structural = chain.structural
    val quote = chain.textQuote
    return buildString {
        if (structural != null) {
            append("锚点 ${structural.blockPath} 第 ${structural.startOffset}–${structural.endOffset} 字")
        } else {
            append("无结构锚点")
        }
        if (quote != null) {
            append("，引文「${quote.exact.take(12)}」")
        }
        pageNumberOf(chain.progressHint.pageKey)?.let { append("，第 $it 页") }
        append("，兜底进度 ${"%.0f%%".format(chain.progressHint.docRatio * 100)}")
    }
}

private fun kindLabel(kind: AnnotationKind): String = when (kind) {
    AnnotationKind.HIGHLIGHT -> "高亮"
    AnnotationKind.INK -> "手写"
    AnnotationKind.MARGIN_NOTE -> "边注"
    AnnotationKind.UNDERLINE -> "下划线"
    AnnotationKind.BOOKMARK -> "书签"
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * SAF 里的默认文件名。
 *
 * 只做两件事：去掉文件系统不能接受的字符、限长。**不**去掉中文与空格 ——
 * 用户看到的应该是书名，而不是一个被 ASCII 化的内部 id。
 *
 * 清洗规则在 [sanitizeFileName] 里（与 PNG / JSON 导出共用一份）：两处各写一份正则，
 * 早晚会在"要不要去点号"这种细节上分家，而分家之后的症状是"有的导出能存、
 * 有的导出被系统拒绝"。
 */
internal fun exportFileName(title: String): String = "${sanitizeFileName(title, "笔记")}-笔记.md"
