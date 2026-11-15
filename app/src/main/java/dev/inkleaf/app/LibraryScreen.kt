package dev.inkleaf.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.document.EpubCoverExtractor
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryItem(
    val meta: DocumentMeta,
    val progress: ReadingProgress?,
    /**
     * 已落盘的封面图（目前只有 EPUB 内嵌封面）。null = 没有可用封面，
     * 网格用自绘封面兜底 —— 不是错误状态。
     */
    val coverFile: File? = null,
)

data class LibraryState(
    val items: List<LibraryItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /**
     * 待确认删除的书。单选删除是它长度为 1 的特例，多选是长度 > 1 ——
     * 两种删除共用同一个确认框与同一条执行路径：以前只有单本删除，
     * 加多选时若另写一套，就必然出现"多选删除忘了提醒笔记会一起没"这类分叉。
     */
    private val _deleteTargets = MutableStateFlow<List<DocumentMeta>>(emptyList())
    val deleteTargets: StateFlow<List<DocumentMeta>> = _deleteTargets.asStateFlow()

    /**
     * 多选模式下的选中集合。**空集合就等于不在多选模式** ——
     * 不另设一个 `selecting` 布尔，否则两个状态迟早会互相矛盾
     * （退出多选忘了清选中，下次长按进来还攥着上次的几本）。
     */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                // 数据库读取挪到 IO（M29）：这两条以前跑在 viewModelScope 的
                // 主线程上，书多或冷启动磁盘慢时会卡住首帧。
                val (documents, progressByDoc) = withContext(Dispatchers.IO) {
                    val documents = container.store.documents.list()
                    val progress = container.store.documents.allProgress().associateBy { it.docId }
                    documents to progress
                }
                // 列表**先上屏**（封面留空，书瓦片刻不容缓地画出自绘封面）；
                // 封面随后在后台逐本补齐，补到哪本哪本换成真图。
                // 以前封面解析挡在 loading 里：一本没抽过封面的 EPUB 就能把
                // 整个首页卡在「正在读取…」几秒 —— 感观上的"打开慢"大半是它。
                _state.value = LibraryState(
                    items = documents.map { LibraryItem(it, progressByDoc[it.id]) },
                    loading = false,
                )
                // 刷新后把选中集合同现有书目对齐：别的界面删了书、或者上一批
                // 删除成功，选中集合里不该再留着已经不存在的 id
                // —— 否则标题栏显示"已选 3 项"，实际只有 2 本可选。
                _selectedIds.update { it intersect documents.map { doc -> doc.id }.toSet() }
                launch {
                    val covers = withContext(Dispatchers.IO) {
                        documents.associate { it.id to coverFileFor(it) }
                    }
                    _state.update { s ->
                        s.copy(items = s.items.map { item ->
                            item.copy(coverFile = covers[item.meta.id] ?: item.coverFile)
                        })
                    }
                }
                // 抽完封面顺手扫一遍孤儿：上一轮删文档时崩在中间、
                // 或者老版本没清过缓存，都会留下"属于已删文档"的封面文件。
                // 它们既占地方，又在书架上点开时让人以为"还有一本没显示的书"。
                // 它是纯维护工作，不挡列表也不挡封面 —— 放最后，IO 上慢慢跑。
                launch {
                    runCatching {
                        withContext(Dispatchers.IO) { container.store.documents.sweepOrphanDerived() }
                    }
                }
            } catch (e: Exception) {
                _state.value = LibraryState(loading = false, error = e.message ?: "读取书架失败")
            }
        }
    }

    /**
     * 取这本书的封面缓存；没有就尝试从 EPUB 里抽一次。
     *
     * 缓存策略刻意放在这里而不是解析器里：文件名带 `sourceFingerprint`，
     * 同一个 docId 换了文件内容（重新导入）自然会指向新文件，旧缓存不被复用。
     * 抽不到时写一个 `.none` 标记，避免每次回到书架都把整本 EPUB 解压一遍。
     *
     * **只读**：不写数据库、不改 `DocumentMeta`。封面是派生资源，丢了可以重建；
     * 把它写进库反而会让"备份/恢复"多背一份可再生的字节。
     *
     * 非 EPUB 暂时没有封面来源（PDF 首页渲染成本高，且网格里一屏十几本代价太大）。
     *
     * 文件名规则在 [dev.inkleaf.core.data.DerivedCache] 里定 —— 这里只管"命中没有、
     * 抽不抽"，不自己拼名字。
     */
    private fun coverFileFor(meta: DocumentMeta): File? {
        val cache = container.store.derived
        val image = cache.coverImage(meta.id, meta.sourceFingerprint)
        if (image.length() > 0) return image
        val missing = cache.coverMissingMarker(meta.id, meta.sourceFingerprint)
        if (missing.exists()) return null

        if (meta.kind != DocumentKind.EPUB) return null
        val docFile = runCatching { container.store.paths.resolveDocument(meta.relativePath) }.getOrNull()
        if (docFile == null || !docFile.exists()) return null

        val bytes = runCatching { EpubCoverExtractor.extract(docFile.readBytes()) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            runCatching { missing.writeText("") }
            return null
        }
        return runCatching { image.writeBytes(bytes); image }.getOrNull()
    }

    /**
     * 多选。长按进入（长按的那一本直接选中），此后单击是"勾/取消"而不是"打开"。
     *
     * 进入方式刻意只留长按：书架最常用的动作是"打开上次那本"，
     * 放一个常驻的"选择"按钮等于把最常用动作的注意力分走一半。
     */
    fun startSelection(id: String) {
        _selectedIds.update { it + id }
    }

    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** 全选 ↔ 取消全选：已全选时再点一次退回空集合（也等于退出多选）。 */
    fun toggleSelectAll() {
        val all = _state.value.items.map { it.meta.id }.toSet()
        if (all.isEmpty()) return
        _selectedIds.value = if (_selectedIds.value.containsAll(all)) emptySet() else all
    }

    fun askDelete(meta: DocumentMeta) {
        _deleteTargets.value = listOf(meta)
    }

    /** 删除所选：按书架顺序取 meta，确认框里列标题的顺序才和屏幕上看到的一致。 */
    fun askDeleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        val metas = _state.value.items.map { it.meta }.filter { it.id in ids }
        if (metas.isNotEmpty()) _deleteTargets.value = metas
    }

    fun dismissDelete() {
        _deleteTargets.value = emptyList()
    }

    /** 删除文档会连带删掉它的全部笔记（外键级联）。确认文案必须说清这一点。 */
    fun confirmDelete() {
        val targets = _deleteTargets.value
        if (targets.isEmpty()) return
        _deleteTargets.value = emptyList()
        viewModelScope.launch {
            for (target in targets) {
                // 单本失败不拖住整批：比如某本书的文件被别的应用占用，
                // 不该让已经删掉的那几本白删、剩下的几本永远删不掉。
                // 失败的那本会因为它还在列表里而被用户看见，不必再弹提示。
                runCatching { container.store.documents.delete(target.id) }
            }
            clearSelection()
            refresh()
        }
    }
}

/**
 * 书架。
 *
 * ## 两种排布，一套数据
 *
 * 列表与网格不是两套界面：同一个 `LibraryState`、同一个点击入口、同一个删除确认，
 * 只有"怎么摆"不同。分开写会立刻分叉 —— 列表里修好的删除确认，网格里忘了加，
 * 这类事在本工程已经发生过（M13 两个阅读界面各写一份工具栏）。
 *
 * 选择被持久化进设置（`library.grid`）：它是"我怎么用这个应用"的一部分，
 * 不该每次冷启动都回到默认。默认是**列表** —— 列表一屏能看见更多标题，
 * 而书架最常见的动作是"找到上次那本"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDocument: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val vm: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VmFactory { LibraryViewModel(container) },
    )
    val state by vm.state.collectAsState()
    val deleteTargets by vm.deleteTargets.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val grid = settings.libraryGrid

    // 空集合即"不在多选模式"：见 LibraryViewModel._selectedIds 的注释。
    val selecting = selectedIds.isNotEmpty()

    // 多选时系统返回键应当是"退出多选"，而不是"退出应用"
    // —— 用户按返回的心理预期是退回上一步，不是掉出书架。
    BackHandler(enabled = selecting) { vm.clearSelection() }

    // 派生缓存的版本号。设置页清掉封面缓存后它会变，
    // 书架据此重抽一次 —— 否则界面手上还攥着已被删掉的路径，
    // 画出来的是一张"读不出来"的封面。
    val cacheEpoch by container.store.derived.epoch.collectAsState()

    // 每次回到书架都重新读一次：导入、删除、阅读进度都可能在其他界面里改变。
    LaunchedEffect(Unit, cacheEpoch) { vm.refresh() }

    Scaffold(
        topBar = {
            if (selecting) {
                // 多选时标题栏整体换掉：这时候"摆放方式/统计/设置"都不是用户要做的事，
                // 留在原地只会给误触机会。
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        val allSelected = state.items.isNotEmpty() &&
                            selectedIds.size == state.items.size
                        IconButton(onClick = vm::toggleSelectAll) {
                            Icon(
                                imageVector = if (allSelected) {
                                    InkIcons.Deselect
                                } else {
                                    InkIcons.SelectAll
                                },
                                contentDescription = if (allSelected) "取消全选" else "全选",
                            )
                        }
                        IconButton(onClick = vm::askDeleteSelected) {
                            Icon(Icons.Default.Delete, contentDescription = "删除所选")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("书架") },
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    container.updateSettings { it.copy(libraryGrid = !grid) }
                                }
                            },
                        ) {
                            // 图标画的是"点了会变成什么"，不是"现在是什么"：
                            // 显示网格图标却已经处于网格，用户会以为按钮坏了。
                            Icon(
                                imageVector = if (grid) InkIcons.ViewList
                                else InkIcons.GridView,
                                contentDescription = if (grid) "切换为列表" else "切换为网格",
                            )
                        }
                        IconButton(onClick = onOpenStats) {
                            Icon(InkIcons.BarChart, contentDescription = "阅读统计")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // 多选时不显示导入按钮：它在多选语境里没有意义，还会压住列表末尾的书。
            if (!selecting) {
                FloatingActionButton(onClick = onOpenImport) {
                    Icon(Icons.Default.Add, contentDescription = "导入")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> EmptyHint("读取书架失败：${state.error}")
                state.loading -> EmptyHint("正在读取…")
                state.items.isEmpty() -> EmptyHint(
                    "书架还是空的。\n点右下角的 + 导入 TXT、Markdown、EPUB 或 PDF 文件。\n" +
                        "PDF 只接受带文字的那一种：整页都是扫描图的 PDF 会在导入时被拒（可以手动强制只读打开）。",
                )

                grid -> LazyVerticalGrid(
                    // 书封是 2:3 的竖长方形，同样的最小宽度下比方形卡片高约一半；
                    // 132dp 在 411dp 宽的机器上是 3 列，一屏能看见两排多。
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.items, key = { it.meta.id }) { item ->
                        BookTile(
                            item = item,
                            selecting = selecting,
                            selected = item.meta.id in selectedIds,
                            onClick = {
                                if (selecting) vm.toggleSelection(item.meta.id)
                                else onOpenDocument(item.meta.id)
                            },
                            onLongClick = { vm.startSelection(item.meta.id) },
                            onDelete = { vm.askDelete(item.meta) },
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.meta.id }) { item ->
                        DocumentCard(
                            item = item,
                            selecting = selecting,
                            selected = item.meta.id in selectedIds,
                            onClick = {
                                if (selecting) vm.toggleSelection(item.meta.id)
                                else onOpenDocument(item.meta.id)
                            },
                            onLongClick = { vm.startSelection(item.meta.id) },
                            onDelete = { vm.askDelete(item.meta) },
                        )
                    }
                }
            }
        }
    }

    if (deleteTargets.isNotEmpty()) {
        // 一本书和多本书共用同一个确认框：文案的分歧只在标题与第一句，
        // 后面"笔记会一起删、无法撤销、建议先导出"这几句是同一件事。
        val single = deleteTargets.singleOrNull()
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = {
                Text(
                    if (single != null) {
                        "删除《${single.title}》？"
                    } else {
                        "删除选中的 ${deleteTargets.size} 本书？"
                    },
                )
            },
            text = {
                Text(
                    buildString {
                        if (single == null) {
                            // 列前三本够了：多选几十本时把标题全列出来只会把对话框撑成一页。
                            append(deleteTargets.take(3).joinToString("、") { "《${it.title}》" })
                            if (deleteTargets.size > 3) {
                                append(" 等 ${deleteTargets.size} 本")
                            }
                            append("\n\n")
                        }
                        append(
                            "正文副本与这些文档上的全部笔记（高亮、手写、边注、书签）都会一起删除，" +
                                "无法撤销。\n\n建议先用「设置 → 备份与恢复」导出一份全库档案。",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) {
                    Text(if (single != null) "删除" else "删除这 ${deleteTargets.size} 本")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text("取消") }
            },
        )
    }
}

/**
 * 列表里的一本书。
 *
 * 多选态下尾部按钮从"删除"换成勾选框：同一个位置表达"这本书要不要被选中"，
 * 用户的视线不用在两个位置之间来回找。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    item: LibraryItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.meta.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
            Text(
                text = describe(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.docRatio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 网格里的一本书。
 *
 * ## 为什么是 2:3 的竖长方形
 *
 * 书封本来就是这个比例（出版行业约 1.5:1 的高宽比）。方形卡片会把书压成"图标"，
 * 书架就变成一堆方块；竖长方形加上一条书脊、右侧一道书口，才有"一本书立在架上"
 * 的体积感 —— 也就是用户要的"长方体"。
 *
 * ## 封面从哪来
 *
 * 1. EPUB 内嵌封面（[LibraryItem.coverFile]，导入后按需抽取并缓存）→ 直接用图；
 * 2. 没有封面 → [GeneratedCover] 按标题哈希挑一套纸色自绘一张：书脊、叶脉细线、
 *    标题（衬线）、作者、格式。**不再显示标题首字** —— 那张色块卡是上一版的做法。
 *
 * ## 标题放在封面里，不放在封面下
 *
 * 真实书脊上就有书名，再在下面重复一行会让一屏信息量翻倍。封面下方只留
 * "格式 · 进度"与一条细进度条。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    item: LibraryItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val meta = item.meta
    val cover = rememberCoverBitmap(item.coverFile)
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp, bottomStart = 3.dp)

    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(elevation = 4.dp, shape = shape)
                    .clip(shape),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = meta.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    GeneratedCover(meta, Modifier.fillMaxSize())
                }
                // 选中：压一层主色薄雾 + 描一圈主色边。只用其中一种的话，
                // 深色封面（书香多为深色）上单靠薄雾几乎看不出来。
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    )
                }
            }
            // 右上角：非多选态是"删除"，多选态是"选中/未选中"的圆点。
            // 同一个位置承担同一个语义 —— 这块地方永远在说"这本书本身怎么了"。
            // 尺寸刻意小（22dp）：它不该抢走"打开书"的点击面积。
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.20f),
                        )
                        .then(
                            if (selected) Modifier
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.18f))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除《${meta.title}》",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kindLabel(meta),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = percentLabel(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (item.progress?.docRatio ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

/** 一套"纸与墨"的配色。全部低饱和，放在浅色书架上不会各自抢戏。 */
private data class CoverPalette(
    val paperTop: Color,
    val paperBottom: Color,
    val spineDark: Color,
    val spine: Color,
    val ink: Color,
    val accent: Color,
)

private val COVER_PALETTES = listOf(
    // 暖纸 / 淡青 / 雾蓝 / 陶土 / 灰紫 / 沙
    CoverPalette(Color(0xFFF7F0E3), Color(0xFFE9DDC7), Color(0xFFC9B79A), Color(0xFFDCCBAE), Color(0xFF2B2B2B), Color(0xFF8A6A3D)),
    CoverPalette(Color(0xFFE6EDE5), Color(0xFFD2DFD3), Color(0xFFA9C0AC), Color(0xFFBCD0BE), Color(0xFF22302A), Color(0xFF3F6B4A)),
    CoverPalette(Color(0xFFE4EAF1), Color(0xFFCFDAE6), Color(0xFFA9BCCE), Color(0xFFBCCCDB), Color(0xFF1F2A36), Color(0xFF3A5A78)),
    CoverPalette(Color(0xFFF1E4DB), Color(0xFFE3D1C4), Color(0xFFC9AC98), Color(0xFFD9C0AE), Color(0xFF33241C), Color(0xFF8A4B2A)),
    CoverPalette(Color(0xFFE9E3EC), Color(0xFFD8CFDC), Color(0xFFB9A9BF), Color(0xFFCBBCCC), Color(0xFF2B2430), Color(0xFF6A3E9B)),
    CoverPalette(Color(0xFFEFE8D4), Color(0xFFE0D5B9), Color(0xFFC7B68F), Color(0xFFD6C5A0),         Color(0xFF2F2A1C), Color(0xFF7A5A1F)),
)

/**
 * 同一个标题永远挑同一套色（书架上每次刷新颜色不会跳），不同书之间又尽量分开。
 * 用 `floorMod` 而不是 `%`：标题哈希可能为负，负数取模会得到负下标。
 */
private fun coverPalette(title: String): CoverPalette {
    var hash = 0
    for (ch in title) hash = hash * 31 + ch.code
    return COVER_PALETTES[Math.floorMod(hash, COVER_PALETTES.size)]
}

/**
 * 自绘书封：纸色渐变 + 书脊 + 书口 + **居中的**书名/作者/格式。
 *
 * 文字整块居中而不是顶对齐：书脊上的书名本来就是居中排的，顶对齐会让封面
 * 下半截空一大片，看起来像"只写了一半"。`start=18 / end=8` 是配平过的：
 * 左侧让出 10dp 书脊后，剩下的页心左右各留 8dp，文字中心正好落在书脊右侧的
 * 页心正中（不是整张卡片的几何中心）。
 *
 * 刻意**不画树叶**：书架上十几本同款叶标会变成噪音；品牌感靠配色与字体。
 */
@Composable
private fun GeneratedCover(meta: DocumentMeta, modifier: Modifier = Modifier) {
    val p = coverPalette(meta.title)
    Box(modifier.background(Brush.verticalGradient(listOf(p.paperTop, p.paperBottom)))) {
        // 书脊：左侧一条，用横向渐变伪装圆柱受光，比纯色更像"装订过"。
        Box(
            Modifier
                .fillMaxHeight()
                .width(10.dp)
                .background(Brush.horizontalGradient(listOf(p.spineDark, p.spine))),
        )
        // 书口：右侧薄薄一条亮边，暗示书页的厚度。
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.White.copy(alpha = 0.55f)),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = meta.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = p.ink,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(9.dp))
            Box(Modifier.width(24.dp).height(1.5.dp).background(p.accent.copy(alpha = 0.85f)))
            Spacer(Modifier.height(9.dp))
            meta.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = author,
                    fontSize = 11.sp,
                    color = p.ink.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = kindLabel(meta).uppercase(),
                fontSize = 8.5.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                color = p.ink.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 封面缩略图缓存。
 *
 * 封面原图常有 1600px 长边，一屏十几本全量解码会吃掉几十 MB。
 * 这里做两件事：`inSampleSize` 降到约 400px（书架上的显示宽度不到 200dp），
 * 以及按文件路径做 LRU —— 来回滚动不重复解码。
 */
private val coverCache = LruCache<String, android.graphics.Bitmap>(32)

private fun decodeCover(file: File): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 400) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
private fun rememberCoverBitmap(file: File?): androidx.compose.ui.graphics.ImageBitmap? {
    if (file == null) return null
    val cached = coverCache.get(file.path)
    return produceState(
        initialValue = cached?.asImageBitmap(),
        key1 = file.path,
    ) {
        if (value != null) return@produceState
        val bmp = withContext(Dispatchers.IO) {
            coverCache.get(file.path) ?: decodeCover(file)?.also { coverCache.put(file.path, it) }
        }
        value = bmp?.asImageBitmap()
    }.value
}

private fun percentLabel(item: LibraryItem): String =
    item.progress?.let { "%.0f%%".format(it.docRatio * 100) } ?: "未开始"

private fun describe(item: LibraryItem): String {
    val meta = item.meta
    val percent = item.progress?.let { "已读 %.0f%%".format(it.docRatio * 100) } ?: "未开始"
    return "${kindLabel(meta)} · ${sizeLabel(meta)} · ${meta.encoding} · $percent"
}


private fun kindLabel(meta: DocumentMeta): String = when (meta.kind) {
    DocumentKind.TXT -> "TXT"
    DocumentKind.MARKDOWN -> "Markdown"
    DocumentKind.EPUB -> "EPUB"
    DocumentKind.PDF -> "PDF"
}

private fun sizeLabel(meta: DocumentMeta): String = when {
    meta.byteSize >= 1024 * 1024 -> "%.1f MB".format(meta.byteSize / 1024.0 / 1024.0)
    else -> "${meta.byteSize / 1024} KB"
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
