package dev.inkleaf.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.data.DocumentImporter
import dev.inkleaf.core.model.DocumentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 导入界面。
 *
 * 存在的理由是**编码**：中文 TXT 里 GBK/GB18030 占比很高，自动探测在
 * GBK 与 Big5 之间没有可靠判据（见 CharsetDetector 的能力边界）。
 * 所以这里必须让用户"看一眼摘录、换一个编码再看一眼"。
 * 一个只有"导入"按钮的界面在这个用户群上是不可用的。
 */
class ImportViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        /** 还没选文件。 */
        data object Idle : State

        data class Reading(val fileName: String) : State

        data class Ready(val inspection: DocumentImporter.ImportInspection) : State

        /** 读取/解码失败。[retryable] 为真时说明"只是编码不对"，界面应保留切换编码的入口。 */
        data class Failed(
            val message: String,
            val retryable: Boolean,
            /** 失败原因是"扫描件闸门"：界面要给一条「仍要打开」的路（方案 §5.5）。 */
            val scannedPdf: Boolean = false,
        ) : State

        data class Done(val title: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var candidate: DocumentImporter.ImportCandidate? = null

    fun pick(uri: Uri) {
        _state.value = State.Reading(uri.lastPathSegment ?: "文件")
        viewModelScope.launch {
            try {
                val read = container.importer.readFromUri(container.context.contentResolver, uri)
                candidate = read
                inspect(read, forcedCharset = null)
            } catch (e: DocumentImporter.ImportException) {
                _state.value = State.Failed(e.message ?: "读取失败", retryable = false)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "读取失败", retryable = false)
            }
        }
    }

    /** 切换编码：只重新解码，不重新读文件（SAF 的 URI 可能已经失效）。 */
    fun chooseCharset(charsetName: String) {
        val current = candidate ?: return
        inspect(current, forcedCharset = charsetName)
    }

    /**
     * 扫描件闸门的逃生口：用户看过理由之后仍然要打开。
     *
     * 之所以要用户**主动点**：扫描件没有文本层，按行锚定与文字选择都不可用。
     * 自动放行会让用户在"画了一笔却挂不到任何一行"时以为是应用坏了；
     * 先说明、再放行，他就知道这是文件的限制。
     */
    fun forceOpenPdf() {
        val current = candidate ?: return
        inspect(current, forcedCharset = null, allowScannedPdf = true)
    }

    fun commit() {
        val ready = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            try {
                val meta = container.importer.commit(ready.inspection)
                _state.value = State.Done(meta.title)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "保存失败", retryable = false)
            }
        }
    }

    private fun inspect(
        candidate: DocumentImporter.ImportCandidate,
        forcedCharset: String?,
        allowScannedPdf: Boolean = false,
    ) {
        try {
            _state.value = State.Ready(
                container.importer.inspect(candidate, forcedCharset, allowScannedPdf),
            )
        } catch (e: DocumentImporter.ImportException) {
            // 自动编码探测的结果不满意时，用户仍然可以手动指定 —— 因此这里 retryable。
            _state.value = State.Failed(
                message = e.message ?: "无法解析该文件",
                retryable = e.reason == DocumentImporter.RejectReason.EMPTY_DOCUMENT,
                scannedPdf = e.reason == DocumentImporter.RejectReason.SCANNED_PDF,
            )
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "无法解析该文件", retryable = true)
        }
    }

    /** 重新选一个文件。 */
    fun reset() {
        candidate = null
        _state.value = State.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(container: AppContainer, onClose: () -> Unit, onImported: () -> Unit) {
    val vm: ImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VmFactory { ImportViewModel(container) },
    )
    val state by vm.state.collectAsState()

    // 用 "*/*" 而不是 text/plain：国区很多 TXT 在 SAF 里被报成
    // application/octet-stream，按 MIME 过滤会把用户真正想选的文件藏起来。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(uri)
    }

    // 进入界面直接开选择器：这是一个"点进来就是要选文件"的界面，
    // 多一次点击只是仪式感。
    LaunchedEffect(Unit) {
        if (state is ImportViewModel.State.Idle) {
            picker.launch(arrayOf("*/*"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入文档") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is ImportViewModel.State.Idle ->
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("选择文件") }

                is ImportViewModel.State.Reading -> {
                    Text("正在读取并解析…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(s.fileName, style = MaterialTheme.typography.bodySmall)
                }

                is ImportViewModel.State.Failed -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("导入失败", style = MaterialTheme.typography.titleMedium)
                            Text(s.message, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    if (s.retryable) {
                        Text(
                            "如果显示的是乱码，试试换一个编码。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        EncodingPicker(choices = dev.inkleaf.core.document.CharsetDetector.MANUAL_CHOICES) {
                            vm.chooseCharset(it)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.scannedPdf) {
                            Button(onClick = vm::forceOpenPdf) { Text("仍要打开") }
                        }
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("换一个文件") }
                        TextButton(onClick = onClose) { Text("返回书架") }
                    }
                }

                is ImportViewModel.State.Ready -> {
                    val inspection = s.inspection
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(inspection.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (inspection.isFixedLayout) {
                                    "${kindLabel(inspection.kind)} · ${inspection.byteSize / 1024} KB · " +
                                        "${inspection.pageCount} 页" +
                                        if (inspection.hasTextLayer) "" else " · 没有文本层"
                                } else {
                                    "${kindLabel(inspection.kind)} · ${inspection.byteSize / 1024} KB · " +
                                        "${inspection.charCount} 字 · ${inspection.blockCount} 段"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (inspection.isLowConfidence) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            if (inspection.charsetIsUserChangeable) {
                                Text(
                                    "编码：${inspection.charsetName}" +
                                        "（置信度 ${"%.2f".format(inspection.confidence)}）",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            } else {
                                Text(
                                    "${kindLabel(inspection.kind)} 自带编码信息",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                inspection.evidence,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (inspection.isLowConfidence) {
                                Text(
                                    "探测结果不够可靠。请对照下面的摘录确认不是乱码，必要时换一个编码。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            for (warning in inspection.warnings) {
                                Text(
                                    "· $warning",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (inspection.charsetIsUserChangeable) {
                                Row(Modifier.padding(top = 8.dp)) {
                                    EncodingPicker(inspection.manualChoices) { vm.chooseCharset(it) }
                                }
                            }
                        }
                    }

                    Text("预览", style = MaterialTheme.typography.titleSmall)
                    Card {
                        Text(
                            text = inspection.excerpt.ifBlank { "（无可显示的正文）" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                            modifier = Modifier.padding(14.dp).horizontalScroll(rememberScrollState()),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::commit) { Text("导入到书架") }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("换一个文件") }
                    }
                }

                is ImportViewModel.State.Done -> {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("已导入《${s.title}》", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "正文副本已保存到应用私有目录；笔记也只会存在这里。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImported) { Text("回到书架") }
                        TextButton(onClick = { vm.reset(); picker.launch(arrayOf("*/*")) }) { Text("再导一本") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EncodingPicker(choices: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) { Text("手动选择编码") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onChoose(name)
                    },
                )
            }
        }
    }
}

private fun kindLabel(kind: DocumentKind): String = when (kind) {
    DocumentKind.TXT -> "TXT"
    DocumentKind.MARKDOWN -> "Markdown"
    DocumentKind.EPUB -> "EPUB"
    DocumentKind.PDF -> "PDF"
}
