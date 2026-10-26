package dev.inkleaf.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.common.Units
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentTheme
import dev.inkleaf.core.document.PdfAnnotationPlanner
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfPageFit
import dev.inkleaf.core.document.PlatformPdfPageRenderer
import dev.inkleaf.core.document.ReflowableDocumentSession
import dev.inkleaf.core.document.ReflowableTextLayout
import dev.inkleaf.core.document.TextSearch
import dev.inkleaf.core.ink.DocumentInkView
import dev.inkleaf.core.ink.DrawableHighlight
import dev.inkleaf.core.ink.DrawableStroke
import dev.inkleaf.core.ink.DrawableUnderline
import dev.inkleaf.core.ink.NoteJson
import dev.inkleaf.core.ink.NoteMarkdown
import dev.inkleaf.core.ink.underlineBands
import dev.inkleaf.core.ink.underlineRangeOf
import dev.inkleaf.core.ink.HighlightRect
import dev.inkleaf.core.ink.InkCoverage
import dev.inkleaf.core.ink.StrokeHitTest
import dev.inkleaf.core.ink.InkLayerController
import dev.inkleaf.core.ink.InkNoteRow
import dev.inkleaf.core.ink.InkResolver
import dev.inkleaf.core.ink.InkState
import dev.inkleaf.core.ink.TypedNoteKind
import dev.inkleaf.core.ink.TypedNoteRow
import dev.inkleaf.core.ink.highlightRanges
import dev.inkleaf.core.ink.lineHighlightRange
import dev.inkleaf.core.ink.rowsForDisplay
import dev.inkleaf.core.ink.typedNoteRows
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.PdfAnnotationWriter
import dev.inkleaf.core.ink.PdfBand
import dev.inkleaf.core.ink.PdfExportSources
import dev.inkleaf.core.ink.PdfInkPageView
import dev.inkleaf.core.ink.PdfLayoutFacts
import dev.inkleaf.core.ink.PdfTextBands
import dev.inkleaf.core.ink.ReaderSearch
import dev.inkleaf.core.ink.SessionLayoutFacts
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.LayoutMode
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.PageGeometry
import dev.inkleaf.core.model.PageLayoutMetrics
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.ui.InkLeafTheme
import dev.inkleaf.core.ui.InkLeafTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * 阅读界面状态。
 *
 * 刻意只持有"文档会话"，不持有排版结果 —— 排版结果依赖视口尺寸与主题，
 * 而那两样东西只在界面层存在。把排版放进 ViewModel 就需要 ViewModel 知道
 * 屏幕尺寸，于是旋转屏幕 / 分屏 / 改字号都要走一遍"通知 ViewModel"的流程。
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val docId: String,
) : ViewModel() {

    sealed interface State {
        data object Loading : State

        data class Failed(val message: String) : State

        data class Ready(
            val meta: DocumentMeta,
            /**
             * 会话。接口类型：重排格式与 PDF 走同一个契约、不同的实现
             * （PDF 的阅读视图见 `PdfReaderContent`）。
             */
            val session: dev.inkleaf.core.document.DocumentSession,
            val savedProgress: ReadingProgress?,
            /** 本机能不能读 PDF 文本层；非 PDF 恒为 true。见 `PdfPageSource.canReadTextLayer`。 */
            val pdfTextLayerAvailable: Boolean = true,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var progressSaveJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val meta = container.store.documents.byId(docId)
                if (meta == null) {
                    _state.value = State.Failed("这本书已经不在书架上了")
                    return@launch
                }
                // 会话缓存（M29）：重开同一本书（进出书架、从笔记跳回）直接命中，
                // 「正在打开…」与「正在排版…」两个加载页全部跳过 —— 解析产物挂在
                // 会话上，排版缓存也挂在会话里（键含尺寸/字号/字体）。
                val cached = container.cachedSessionFor(meta.id, meta.sourceFingerprint)
                if (cached != null) {
                    val (session, pdfTextOk) = cached
                    if (session is ReflowableDocumentSession || session is PdfDocumentSession) {
                        container.store.documents.markOpened(meta.id)
                        _state.value = State.Ready(
                            meta = meta,
                            session = session,
                            savedProgress = container.store.documents.progressOf(meta.id),
                            pdfTextLayerAvailable = pdfTextOk,
                        )
                        return@launch
                    }
                }
                // 读文件 + 解析 + 切块全在**后台**：这以前跑在 viewModelScope 的
                // 主线程上，一本大 TXT 会让点击到首帧之间整条 UI 冻住。
                val build = withContext(Dispatchers.Default) {
                    val bytes = container.store.documents.readBytes(meta)
                    // 走**与导入时同一个**入口（DocumentCodec.sessionFromBytes）：
                    // 重新探测编码或换一种解包顺序都会让锚点的坐标系与导入时不一致。
                    DocumentCodec.sessionFromBytes(
                        docId = meta.id,
                        kind = meta.kind,
                        bytes = bytes,
                        forcedCharset = meta.encoding,
                        pdf = container.pdfPageSource,
                    )
                }
                val session = build.session
                if (session !is ReflowableDocumentSession && session !is PdfDocumentSession) {
                    _state.value = State.Failed("不支持的文档类型：${session.kind}")
                    return@launch
                }
                container.cacheSession(meta.id, meta.sourceFingerprint, session, build.pdfTextLayerAvailable)
                container.store.documents.markOpened(meta.id)
                _state.value = State.Ready(
                    meta = meta,
                    session = session,
                    savedProgress = container.store.documents.progressOf(meta.id),
                    pdfTextLayerAvailable = build.pdfTextLayerAvailable,
                )
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "打开失败")
            }
        }
    }

    /**
     * 保存进度，去抖 800ms。
     *
     * 快速连翻十页会产生十次写事务，而只有最后一次是用户真正想留下的位置。
     */
    fun saveProgress(charOffset: Int, pageIndex: Int, docRatio: Float) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(800)
            write(charOffset, pageIndex, docRatio)
        }
    }

    /**
     * 退出前立即落盘，不走去抖。
     *
     * 走 [AppContainer.saveProgressNow]（应用级作用域）而不是 viewModelScope（M36）：
     * 退出阅读界面时 ViewModel 也随之销毁、viewModelScope 立刻被取消 ——
     * 那次写入会死在 `delay` 里，用户看到的是"滚了几屏再返回，重进却回到老位置"。
     */
    fun flushProgress(charOffset: Int, pageIndex: Int, docRatio: Float) {
        progressSaveJob?.cancel()
        container.saveProgressNow(
            ReadingProgress(
                docId = docId,
                charOffset = charOffset,
                pageIndex = pageIndex,
                docRatio = docRatio.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun write(charOffset: Int, pageIndex: Int, docRatio: Float) {
        runCatching {
            container.store.documents.saveProgress(
                ReadingProgress(
                    docId = docId,
                    charOffset = charOffset,
                    pageIndex = pageIndex,
                    docRatio = docRatio.coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    docId: String,
    onClose: () -> Unit,
    onOpenNotes: () -> Unit,
    /**
     * 从笔记清单"跳回原位"时带进来的笔记 id（A3）。
     *
     * 用 id 而不是偏移：偏移要等当前排版结果出来才算得出，
     * 而 id 在库里就有 —— 阅读器自己算更准（它有 facts）。
     */
    initialNoteId: String? = null,
) {
    val vm: ReaderViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "reader-$docId",
        factory = VmFactory { ReaderViewModel(container, docId) },
    )
    val state by vm.state.collectAsState()
    val settings by container.settings.collectAsState()
    // 主题先把 AUTO 解析掉：两个阅读界面拿到的必须是一个**有纸有墨**的具体主题，
    // 不能让它们各自去猜"跟随系统"是什么意思（那会有两份判断，迟早不一致）。
    val readerTheme = settings.readerTheme.resolve(isSystemInDarkTheme())

    // 阅读界面里再套一层自己的明暗：书里的纸是深色，这个界面里的托盘、面板、
    // 目录就都该是深色，与外层（书架/设置）那一档无关 —— 两者是独立设置，
    // 见 `ReaderSettings.uiDarkMode` 与 `ReaderTheme` 的注释。
    InkLeafTheme(darkTheme = readerTheme.isDark) {
        when (val s = state) {
        is ReaderViewModel.State.Loading -> CenteredMessage("正在打开…", showSpinner = true)
        is ReaderViewModel.State.Failed -> CenteredMessage(s.message)
        is ReaderViewModel.State.Ready -> {
            // 阅读时长统计（M12）：只在阅读界面可见、且 Activity 处于前台时计数。
            // 放在这里而不是两个阅读视图里各放一份：PDF 与重排格式的"在读"定义相同。
            ReadingTimeEffect(container, s.meta.id)
            when (val session = s.session) {
            is ReflowableDocumentSession -> ReaderContent(
                container = container,
                meta = s.meta,
                session = session,
                settings = settings,
                theme = readerTheme,
                savedCharOffset = s.savedProgress?.charOffset ?: 0,
                savedPageIndex = s.savedProgress?.pageIndex ?: 0,
                initialNoteId = initialNoteId,
                onClose = onClose,
                onOpenNotes = onOpenNotes,
                onProgress = vm::saveProgress,
                onFlush = vm::flushProgress,
            )

            // 固定版式（PDF）与重排格式没有共同的阅读视图：页是纸，纸不会因为
            // 用户调大字号而变大。坐标系的定义权在 `PdfDocumentSession`，
            // 这里只负责把页位图摆到屏幕上（见 `PdfReaderContent`）。
            is PdfDocumentSession -> PdfReaderContent(
                container = container,
                meta = s.meta,
                session = session,
                pdfTextLayerAvailable = s.pdfTextLayerAvailable,
                savedPageIndex = s.savedProgress?.pageIndex ?: 0,
                theme = readerTheme,
                keepScreenOn = settings.keepScreenOn,
                stylusOnly = settings.stylusOnly,
                pageTurnZones = settings.pageTurnZones,
                inkUnbuffered = settings.inkUnbuffered,
                inkStaticLayer = settings.inkStaticLayer,
                initialNoteId = initialNoteId,
                onClose = onClose,
                onOpenNotes = onOpenNotes,
                onProgress = vm::saveProgress,
                onFlush = vm::flushProgress,
            )

            else -> CenteredMessage("不支持的文档类型")
            }
        }
    }
    }
}

/**
 * 阅读时长的生命周期接线。
 *
 * 用 `LifecycleEventObserver` 而不是只看 Composable 的出现/消失：切到后台时
 * Composable 并不会销毁（返回再进来还是同一份状态），只看 `DisposableEffect`
 * 会把"在后台待了两小时"算成阅读。`ON_STOP` 是系统对"用户看不见这个界面了"
 * 的统一定义，锁屏、切应用、被全屏通知盖住都会走到它。
 */
@Composable
private fun ReadingTimeEffect(container: AppContainer, docId: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, docId) {
        val tracker = ReadingTimeTracker(
            onSegment = { startedAt, endedAt, durationMs ->
                container.recordReading(docId, startedAt, endedAt, durationMs)
            },
            elapsedMs = SystemClock::elapsedRealtime,
            wallMs = System::currentTimeMillis,
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tracker.enterForeground()
                Lifecycle.Event.ON_STOP -> tracker.leaveForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // addObserver 会补发当前状态的事件，但不同版本的行为不完全一致；
        // 显式检查一次更可靠（[ReadingTimeTracker.enterForeground] 对重复调用是幂等的）。
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            tracker.enterForeground()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 退出阅读界面时补一段：ON_STOP 可能还没到（Compose 先销毁视图）。
            tracker.leaveForeground()
        }
    }
}

/**
 * 固定版式（PDF）的阅读界面。
 *
 * ## 它为什么不是 [ReaderContent] 的一个分支
 *
 * 重排阅读界面里的每一个量 —— 字号、行距、页边距、分页、滚动位置 —— 在 PDF 上
 * 都不存在。页面尺寸是**文件的事实**，改应用字号不会让纸变大。硬塞进同一个
 * 界面就要在十几处判断"是不是 PDF"，而其中任何一处漏判都会表现为
 * "笔迹与文字错开"（M5 记录反复强调的那类 bug）。
 *
 * ## 这一版能做什么、不能做什么
 *
 * 能做：打开、按页渲染、上一页/下一页、记住页码、**在文本层上按行落笔**（M10）、
 * **按行标高亮**、**全文搜索并跳到命中页**、从「更多」进入整篇笔记（M11）。
 * 不能做：逐字选择文字、复制文字；PDF 的高亮是**按行**的（锚定精度 L2）。
 *
 * ## 落笔依赖文本层
 *
 * 笔迹按**行**锚定（L2），而 PDF 的行盒只来自文本层。于是：
 *
 *   - Android 15+ 且是文本型 PDF：能写；
 *   - 扫描页（没有文本层的那一页）：不能写 —— 这一页没有行，落笔会被明确丢掉，
 *     界面提前把笔收起来并说明原因；
 *   - Android 15 以下：整份 PDF 都读不到文本层，也就整份都不能写。
 *
 * 这不是实现偷懒，而是"笔迹必须能解回原处"的直接后果：没有行盒就没有锚点，
 * 存下来的只会是一条永远找不到位置的笔记。
 *
 * ## 为什么先渲染成位图再显示，而不是直接画到 View 上
 *
 * `PdfRenderer` 只能把页画进 `Bitmap`，没有"直接画到 Canvas"的入口。
 * 位图尺寸按视口算（[PdfPageFit]），所以屏幕上是 1:1 像素，不会糊。
 * 页位图只保留当前这一页：A4 在 1440px 宽的屏上约 12MB，缓存三页就是 36MB，
 * 而翻页一次渲染只要几十毫秒。
 *
 * 位图与笔迹由 [PdfInkPageView] **同一个 View、同一次 `onDraw`** 画出来：
 * 两者共用同一个 `pageRect` 与同一个缩放，翻页时不存在"图换了、墨迹还在旧页"的窗口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfReaderContent(
    container: AppContainer,
    meta: DocumentMeta,
    session: PdfDocumentSession,
    pdfTextLayerAvailable: Boolean,
    savedPageIndex: Int,
    /** 已经解析过的阅读主题（不含 AUTO）。 */
    theme: ReaderTheme,
    keepScreenOn: Boolean,
    /** 只接受手写笔：手指不落笔，继续去翻页。 */
    stylusOnly: Boolean,
    /** 书写时手指落在左右两侧是否翻页（M25 的分区让渡，可从设置里关）。 */
    pageTurnZones: Boolean,
    /** 书写与套索走无缓冲派发（低延迟）。PDF 笔迹层是同一个 View，共用这一开关。 */
    inkUnbuffered: Boolean,
    /** 静态层是否录进 RenderNode 缓存。 */
    inkStaticLayer: Boolean,
    /** 从笔记清单跳回原位时带进来的笔记 id（A3）。 */
    initialNoteId: String? = null,
    onClose: () -> Unit,
    onOpenNotes: () -> Unit,
    onProgress: (Int, Int, Float) -> Unit,
    onFlush: (Int, Int, Float) -> Unit,
) {
    val pageCount = session.pages.size
    val lastIndex = (pageCount - 1).coerceAtLeast(0)

    // 与重排阅读界面同一条纪律：屏幕常亮作用在 Activity 窗口上，而不是 Composition。
    val context = LocalContext.current
    DisposableEffect(keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var pageIndex by remember(meta.id) {
        mutableIntStateOf(savedPageIndex.coerceIn(0, lastIndex))
    }
    // 进界面时**从库里现读**一次页码（M36）。
    //
    // 与重排界面（`ReaderContent` 的定位效果）同一个理由：ViewModel 挂在 Activity 上，
    // 退出阅读界面它不会被销毁，复用的还是同一个实例 —— 里面的 `savedProgress`
    // 是**上一次进书时**读到的那份。拿它翻页的症状是"关掉再打开，页码回到第一次打开时"。
    var restoredPage by remember(meta.id) { mutableStateOf(false) }
    LaunchedEffect(meta.id) {
        if (restoredPage) return@LaunchedEffect
        restoredPage = true
        val fresh = runCatching { container.store.documents.progressOf(meta.id)?.pageIndex }
            .getOrNull() ?: return@LaunchedEffect
        pageIndex = fresh.coerceIn(0, lastIndex)
    }
    var area by remember(meta.id) { mutableStateOf(IntSize.Zero) }
    var pageBitmap by remember(meta.id) { mutableStateOf<Bitmap?>(null) }
    /**
     * 当前页的视图引用（导出 PNG 用）。
     *
     * 只有它能给出"屏幕上这一页真正的样子"——位图变量里没有墨迹、没有色带、
     * 没有下划线。导出必须走视图的 `renderToBitmap()`（与屏幕同一段绘制代码）。
     */
    var pageView by remember(meta.id) { mutableStateOf<PdfInkPageView?>(null) }
    /** [pageBitmap] 画的是第几页（0 起）。页号与位图必须同源，否则翻页瞬间会"新页码配旧图"。 */
    var bitmapPage by remember(meta.id) { mutableIntStateOf(-1) }
    var failure by remember(meta.id) { mutableStateOf<String?>(null) }

    // ── 笔迹（M10）───────────────────────────────────────────────────────────
    // 与重排阅读界面同一条接线：控制器只认文档坐标，界面只负责提供"最新的排版事实"。
    // 差别只在事实的来源 —— PDF 的行盒来自文本层，页偏移来自 `PdfDocumentSession`。
    val scope = rememberCoroutineScope()
    var penEnabled by remember(meta.id) { mutableStateOf(false) }
    var inkStrokes by remember(meta.id) { mutableStateOf<List<DrawableStroke>>(emptyList()) }
    /** 与 [inkStrokes] 同一份解析结果的覆盖集合，给抬笔暂存的交接待用。 */
    var inkCoverage by remember(meta.id) { mutableStateOf(InkCoverage.EMPTY) }
    var inkNotice by remember(meta.id) { mutableStateOf<String?>(null) }
    /**
     * 撤销/重做能不能按 —— 从控制器**抄进 Compose 状态**。
     *
     * `InkLayerController.canUndo/canRedo` 不是 Compose 状态：撤销栈在控制器里，
     * 变了也不会通知 Compose。而工具栏的 `actions` lambda 只在它读到的状态变化时
     * 才重组；直接读 `canUndo` 会让按钮一直停在第一次组合时的灰色。
     * 每次解析后抄一遍，两个按钮就与撤销栈同步（解析本身由 `inkTicks` 驱动）。
     */
    var inkCanUndo by remember(meta.id) { mutableStateOf(false) }
    var inkCanRedo by remember(meta.id) { mutableStateOf(false) }

    /** 当前笔刷（三支笔 × 墨色 × 粗细）。切笔刷是纯界面状态，不需要重建控制器。 */
    var brush by remember(meta.id) { mutableStateOf(BrushSpec.DEFAULT_PEN) }
    /** 橡皮模式。与书写互斥：同一处按下不能既被理解成"写"又被理解成"擦"。 */
    var eraserEnabled by remember(meta.id) { mutableStateOf(false) }
    /**
     * 笔刷面板（笔形 / 墨色 / 粗细）是否展开。
     *
     * 默认**收起**：书写时屏幕该留给字。想调笔的属性再点一下"笔画设置"。
     * 这是 M17 的产品判断 —— 之前一进书写就把整块面板顶上来，
     * 屏幕下半部被占掉 120 dp，而选中哪支笔一天也改不了几次。
     */
    var brushPanelOpen by remember(meta.id) { mutableStateOf(false) }
    /** 已解析的下划线 / 波浪线（文档坐标）。与色带同一次解析，画在同一个变换里。 */
    var pdfUnderlines by remember(meta.id) { mutableStateOf<List<DrawableUnderline>>(emptyList()) }

    // ── 高亮与搜索（M11）────────────────────────────────────────────────────
    //
    // 与 M10 的笔迹走**同一个**解析出口：`highlightRanges` / `typedNoteRows` /
    // `InkResolver` 都用同一份 `inkFacts`，所以"屏幕上画出来的色带"与"面板里说有"、
    // "搜索跳到的行"不可能指向三个不同的位置。
    var highlightMode by remember(meta.id) { mutableStateOf(false) }
    /** 「画线」模式（下划线 / 波浪线）。与高亮、书写三者互斥。 */
    var underlineMode by remember(meta.id) { mutableStateOf(false) }
    /** 画直线还是波浪线。它决定**写进标注里的样式**，不是当前的显示开关。 */
    var underlineWavy by remember(meta.id) { mutableStateOf(false) }
    /** 已解析的高亮（区间 + 颜色）。色带由它派生，界面不自己算区间。 */
    var pdfHighlights by remember(meta.id) { mutableStateOf<List<DrawableHighlight>>(emptyList()) }
    /** 打字批注按当前排版解析后的清单，搜索面板要拿它找"我写过的话"。 */
    var pdfTypedRows by remember(meta.id) { mutableStateOf<List<TypedNoteRow>>(emptyList()) }
    /** PDF 里的手写笔迹落在第几页（批注 id → 页号）。见下面 refresh 里的填充。 */
    var pdfInkPages by remember(meta.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    /** 写入类操作的结果；几秒后自动消失。 */
    var noteMessage by remember(meta.id) { mutableStateOf<String?>(null) }
    /** 刚删掉、还能撤销的那一条（标注 id）。与 [noteMessage] 同时出现、同时消失。 */
    var pendingUndo by remember(meta.id) { mutableStateOf<ReaderUndo?>(null) }
    /** 「本页笔记」面板。 */
    var pageNotesOpen by remember(meta.id) { mutableStateOf(false) }
    var overflowOpen by remember(meta.id) { mutableStateOf(false) }
    val noteWriter = remember(meta.id) { NoteWriter(container, meta.id) }
    /** PNG / JSON 导出出口。结果写进 [noteMessage]，与其它写入操作共用一行状态。 */
    val pageExporter = rememberPageExporter { noteMessage = it }

    /**
     * 导出用的全书笔记 JSON。
     *
     * 与 Markdown 导出共用 [NoteMarkdown.fromAnnotations] 的映射 —— 两种格式
     * 必须对"笔记有哪些字段"给出同一个答案，否则用户会拿到两份互相矛盾的文件。
     * 差别只在编码：Markdown 给人读，JSON 给程序读（见 [NoteJson] 的注释）。
     */
    suspend fun notesJson(): String {
        val snapshot = container.store.annotations.listByDoc(meta.id)
        return NoteJson.render(
            title = meta.title,
            author = meta.author,
            docId = meta.id,
            kind = meta.kind.name,
            notes = NoteMarkdown.fromAnnotations(snapshot.annotations) { snapshot.strokesOf(it).size },
            exportedAt = System.currentTimeMillis(),
        )
    }

    var searchOpen by remember(meta.id) { mutableStateOf(false) }
    var searchQuery by remember(meta.id) { mutableStateOf("") }
    var searchResult by remember(meta.id) { mutableStateOf(ReaderSearch.Result.EMPTY) }
    var searchIndex by remember(meta.id) { mutableIntStateOf(0) }
    val searchHits = searchResult.hits
    val searchHit = searchHits.getOrNull(searchIndex.coerceIn(0, maxOf(0, searchHits.lastIndex)))
    val factsHolder = remember(meta.id) { AtomicReference<LayoutFacts?>(null) }
    val inkTicks = remember(meta.id) { MutableStateFlow(0) }
    val inkSink = remember(meta.id) { RepositoryInkSink(container, meta.id) }
    val inkController = remember(meta.id) {
        InkLayerController(
            scope = scope,
            sink = inkSink,
            facts = { factsHolder.get() },
            // 控制器在 worker 线程回调，这里只碰线程安全的 StateFlow，
            // 真正的界面状态更新在下面的 collect 里（主线程）。
            onChanged = { inkTicks.update { it + 1 } },
        )
    }

    // 笔刷是控制器的普通字段（不是 Compose 状态）：换一次就写一次。
    // 不写进 remember 的初始化里，是因为用户会在书写过程中换笔 ——
    // 那一刻必须立刻生效，而不是等下一次重建控制器。
    LaunchedEffect(brush, inkController) { inkController.brush = brush }

    // 文档坐标 = 页内坐标：左边界与上下边距都取 0。
    // PDF 没有"排版边距"这个概念，页就是纸；凭空加一圈边距只会让行盒与位图错开。
    val inkMetrics = remember(session) {
        PageLayoutMetrics(
            contentWidthPx = session.maxPageWidthPx.toInt(),
            contentHeightPx = session.maxPageHeightPx.toInt(),
            marginLeftPx = 0f,
            marginRightPx = 0f,
            marginTopPx = 0f,
            marginBottomPx = 0f,
        )
    }
    // 行盒要遍历全部页与行，只建一次（翻页时复用）。页号只影响兜底提示的比例，
    // 见下面那个按页包一层的 facts。
    val inkFacts = remember(session) { PdfLayoutFacts(session, inkMetrics, docRatio = 0f) }

    /**
     * 当前页的行盒（文档坐标）。
     *
     * 只用来做"点哪一行"的命中测试与高亮时的行号换算；翻页只换这一小段，
     * 不重建全部行盒（500 页的 PDF 有几万行）。
     */
    val currentPageLines = remember(inkFacts, pageIndex) {
        val top = session.pageTopPx(pageIndex)
        val bottom = top + (session.pages.getOrNull(pageIndex)?.heightPx ?: 0f)
        inkFacts.lines.filter { it.rectDoc.top >= top && it.rectDoc.bottom <= bottom }
    }

    /**
     * 高亮 → 色带。
     *
     * PDF 的文字在位图里，没有字距表，所以色带按**整行**铺满 —— 与本工程的
     * 锚定精度 L2 一致（见 [PdfTextBands]）。这里只做映射，不判断"标没标上"：
     * 能进 [pdfHighlights] 的，都是 `highlightRanges` 判定解析成功的。
     */
    val pdfHighlightBands = remember(pdfHighlights, inkFacts) {
        PdfTextBands.ofHighlights(pdfHighlights, inkFacts)
    }

    /** 当前搜索命中的色带。只对正文命中画：批注命中没有"正文区间"。 */
    val pdfSearchBands = remember(searchHit, inkFacts) {
        PdfTextBands.ofSearch(searchHit as? ReaderSearch.TextHit, inkFacts)
    }

    // 高亮在下、搜索在上：重叠时"此刻正在找的就是这里"要比"我标过这里"更显眼。
    val pdfBands: List<PdfBand> = remember(pdfHighlightBands, pdfSearchBands) {
        pdfHighlightBands + pdfSearchBands
    }

    /** 某个字符偏移所在页的页键（`p3`），写进锚点链的兜底提示。 */
    fun pageKeyAt(offset: Int): String? =
        session.pageIndexOfChar(offset).takeIf { it >= 0 }?.let { "p${it + 1}" }

    /**
     * 当前页的打字批注（高亮与边注）。
     *
     * 位置判据是 `charOffset → 页号`，与笔迹、色带共用同一个 `PdfDocumentSession`。
     * 高亮的 `charOffset` 是它所在**行**的起点（L2），一行不会跨页 ——
     * 所以"这条高亮属于哪一页"没有第二个答案。
     */
    val pageNotes = remember(pdfTypedRows, pageIndex, session) {
        pdfTypedRows.filter { row ->
            val offset = row.charOffset ?: return@filter false
            session.pageIndexOfChar(offset) == pageIndex
        }
    }

    /** 解不开位置的批注。它们不属于任何一页，但必须有一个能被看见、能删掉的地方。 */
    val unplacedNotes = remember(pdfTypedRows) {
        pdfTypedRows.filter { it.charOffset == null }
    }

    /** 高亮的精确区间 → 显示原文时用。打不完的引文比整行文字更接近"我标了哪一句"。 */
    val highlightById = remember(pdfHighlights) {
        pdfHighlights.associateBy { it.annotationId }
    }

    fun quoteOf(row: TypedNoteRow): String? {
        if (row.kind == TypedNoteKind.HIGHLIGHT) {
            val range = highlightById[row.annotationId]?.range
            if (range != null && range.first >= 0 && range.last < session.normalized.text.length) {
                return session.normalized.text.substring(range.first, range.last + 1)
            }
        }
        return row.excerpt ?: row.originalText
    }

    fun deletePageNote(annotationId: String) {
        scope.launch {
            val outcome = noteWriter.deleteTypedNote(annotationId)
            noteMessage = outcome.message
            pendingUndo = if (outcome.changed) ReaderUndo.Note(annotationId) else null
            if (outcome.changed) inkTicks.update { it + 1 }
        }
    }

    /**
     * 橡皮擦到一笔之后的落库动作。
     *
     * 命中测试在 `PdfInkPageView` 里（那里有屏幕坐标），这里只负责"删掉 + 留撤销现场"。
     * 删除会硬删笔迹行，所以撤销现场必须在删除**之前**从快照里取出 ——
     * `NoteWriter.eraseInk` 的返回值就是那个现场。
     */
    fun eraseStroke(stroke: DrawableStroke) {
        scope.launch {
            val outcome = noteWriter.eraseInk(stroke.strokeId)
            noteMessage = outcome.message
            pendingUndo = outcome.erased?.let(ReaderUndo::Ink)
            if (outcome.changed) inkTicks.update { it + 1 }
        }
    }

    /** 撤销当前那一次删除 / 擦除。两种撤销的语义不同，分岔点只在 `NoteWriter` 里。 */
    fun undoPending() {
        val token = pendingUndo ?: return
        pendingUndo = null
        when (token) {
            is ReaderUndo.Note -> scope.launch {
                val outcome = noteWriter.restoreTypedNote(token.annotationId)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.Ink -> scope.launch {
                val outcome = noteWriter.restoreErasedInk(token.erased)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.InkMoved -> scope.launch {
                val outcome = noteWriter.undoMove(token.moved)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.InkBatch -> scope.launch {
                val outcome = noteWriter.restoreErasedInkBatch(token.erased)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }
        }
    }

    // 打开私有副本。PdfRenderer 需要一个可 seek 的文件描述符，
    // 而库里的正文副本本来就是一个真实文件 —— 不需要再复制一份。
    val opened = remember(meta.id) {
        runCatching { PlatformPdfPageRenderer(container.store.paths.resolveDocument(meta.relativePath)) }
    }
    val renderer = opened.getOrNull()
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }

    LaunchedEffect(pageIndex, area, renderer) {
        val r = renderer ?: return@LaunchedEffect
        val page = session.pages.getOrNull(pageIndex) ?: return@LaunchedEffect
        if (area.width <= 0 || area.height <= 0) return@LaunchedEffect
        val scale = PdfPageFit.scale(
            availableWidthPx = area.width.toFloat(),
            availableHeightPx = area.height.toFloat(),
            pageWidthPx = page.widthPx,
            pageHeightPx = page.heightPx,
        )
        val width = PdfPageFit.bitmapWidth(page.widthPx, scale)
        val height = PdfPageFit.bitmapHeight(page.heightPx, scale)
        failure = null
        val result = withContext(Dispatchers.IO) {
            runCatching { r.render(pageIndex, width, height) }
        }
        val rendered = result.getOrNull()
        if (rendered != null) {
            pageBitmap = rendered
            bitmapPage = pageIndex
        } else {
            failure = "这一页渲染失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
        }
    }

    // 进度：PDF 没有"字符偏移"这个稳定量（扫描件连文本都没有），
    // 所以存页号 + 页比例。重排格式的 charOffset 在这里写 0 —— 它不是定位依据，
    // 书架显示的百分比来自 docRatio（`LibraryScreen` 只读这一个字段）。
    val ratio = if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)
    LaunchedEffect(pageIndex) { onProgress(0, pageIndex, ratio) }

    // 当前页在整篇里的比例，只进"锚点链完全解不开时"的兜底提示（ProgressHint）。
    // 用接口委托而不是重建 `PdfLayoutFacts`：重建会重算全部页的行盒，
    // 500 页的 PDF 每翻一页就要遍历几万行 —— 而这里要改的只是一个小数。
    // （只换 docRatio 是安全的，见 `LayoutFacts.lineAtOffset` 关于委托的说明。）
    LaunchedEffect(pageIndex, inkFacts) {
        val currentRatio = if (pageCount <= 1) 0f else pageIndex.toFloat() / (pageCount - 1)
        factsHolder.set(object : LayoutFacts by inkFacts { override val docRatio: Float = currentRatio })
    }

    // 库有变化（落笔 / 撤销 / 重做）就重新解析一次。这是**唯一**的笔迹来源，
    // 与重排阅读界面共用同一个 `InkResolver`，所以 PDF 笔记与 TXT 笔记在
    // "解得开 / 解不开"上是同一套判据。
    LaunchedEffect(inkFacts, inkController) {
        val refresh: suspend () -> Unit = {
            val snapshot = container.store.annotations.listByDoc(meta.id)
            val resolution = withContext(Dispatchers.Default) {
                InkResolver.resolve(
                    inks = snapshot.annotations.filterIsInstance<Annotation.Ink>(),
                    strokesOf = snapshot::strokesOf,
                    brushesOf = snapshot::brushesOf,
                    facts = inkFacts,
                )
            }
            inkStrokes = resolution.drawable
            inkCoverage = resolution.coverage
            inkNotice = inkController.lastNotice
            inkCanUndo = inkController.canUndo
            inkCanRedo = inkController.canRedo
            // 高亮与打字批注走**同一份** facts：图表里画出的色带、搜索清单里的条目、
            // 笔记面板里的说法因此只可能指向同一个位置。
            pdfHighlights = highlightRanges(
                snapshot.annotations.filterIsInstance<Annotation.Highlight>(),
                inkFacts,
            )
            // 下划线与高亮走同一个 AnchorResolver、同一份 facts：所以"面板说有、
            // 页面上没画"不可能发生。它们不能合并成一条通道 —— 画法不同（色带 vs 线）。
            pdfUnderlines = underlineBands(
                snapshot.annotations.filterIsInstance<Annotation.Underline>(),
                inkFacts,
            )
            pdfTypedRows = typedNoteRows(snapshot.annotations, inkFacts)
            // 手写笔迹在 PDF 里绑的是**页**（不是字符偏移），
            // 所以"跳回原位"要另有一条通道：批注 id → 它落在第几页。
            pdfInkPages = snapshot.annotations
                .filterIsInstance<Annotation.Ink>()
                .mapNotNull { ink ->
                    val page = ink.pages.firstNotNullOfOrNull { key ->
                        key.removePrefix("p").toIntOrNull()
                    } ?: ink.anchor?.progressHint?.pageKey?.removePrefix("p")?.toIntOrNull()
                    if (page == null) null else ink.id to page
                }
                .toMap()
        }
        refresh()
        inkTicks.collect {
            refresh()
            inkController.clearNotice()
        }
    }

    // "这一笔没存下来"只留一会儿：它是一句话，不是需要一直盯着看的状态。
    LaunchedEffect(inkNotice) {
        if (inkNotice != null) {
            delay(6_000)
            inkNotice = null
        }
    }

    // 这一页有没有可锚定的行。扫描页没有行盒：落笔会被控制器明确丢掉，
    // 所以界面先把笔收起来并说清原因，而不是让用户写半天再看到"没存下来"。
    val pageHasLines = session.pages.getOrNull(pageIndex)?.lines?.isNotEmpty() == true
    LaunchedEffect(pageIndex) {
        if (!pageHasLines) {
            penEnabled = false
            highlightMode = false
        }
    }

    // 查询变化 → 重新搜索。口径与重排阅读界面完全一致（`ReaderSearch`：正文 + 打字批注，
    // 一份清单）；PDF 只多一步"命中偏移 → 页号"，因为它的跳转是换页而不是滚动。
    //
    // 依赖里刻意没有 `pdfTypedRows`：它每次重新解析都会换一个对象，而这条效果末尾
    // 会跳到默认命中 —— 用户每标一条高亮就被拽回第一处，是很难解释的行为。
    LaunchedEffect(searchQuery, session) {
        val result = withContext(Dispatchers.Default) {
            ReaderSearch.search(session.normalized.text, pdfTypedRows, searchQuery)
        }
        searchResult = result
        searchIndex = result.defaultIndex.coerceAtLeast(0)
        result.hits.getOrNull(result.defaultIndex)?.charOffset?.let { offset ->
            session.pageIndexOfChar(offset).takeIf { it >= 0 }?.let { pageIndex = it }
        }
    }

    /** 上一处 / 下一处：到头折回另一端（与重排阅读界面同一条规则）。 */
    fun goToPdfSearchHit(index: Int) {
        if (searchHits.isEmpty()) return
        val count = searchHits.size
        val target = ((index % count) + count) % count
        searchIndex = target
        searchHits[target].charOffset?.let { offset ->
            session.pageIndexOfChar(offset).takeIf { it >= 0 }?.let { pageIndex = it }
        }
    }

    /**
     * 从笔记清单跳回原位（A3）。
     *
     * PDF 的位置是**页**，两条通道各管一类：
     *   - 打字批注（高亮 / 边注）走"字符偏移 → 页号"，与搜索命中同一条换算，
     *     所以从笔记跳过去与从搜索跳过去的落点一致；
     *   - 手写笔迹直接记着它落在第几页（[pdfInkPages]）—— 它本来就不绑字符偏移。
     */
    var jumpedNoteId by remember(meta.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(initialNoteId, pdfTypedRows, pdfInkPages) {
        val id = initialNoteId ?: return@LaunchedEffect
        if (jumpedNoteId == id) return@LaunchedEffect
        val typedOffset = pdfTypedRows.firstOrNull { it.annotationId == id }?.charOffset
        val page = typedOffset?.let { session.pageIndexOfChar(it) }?.takeIf { it >= 0 }
            ?: pdfInkPages[id]
        if (page != null) {
            pageIndex = page.coerceIn(0, lastIndex)
        } else {
            noteMessage = "这条笔记在当前排版下定位不到位置（锚点可能已失效）。"
        }
        jumpedNoteId = id
    }

    /**
     * 用户在「高亮」模式里点了一行。
     *
     * 点中的行**当场**标上，没有二次确认：进入高亮模式本身就是那次确认。
     * 失败（没有文本层 / 这一行是空行）显示在状态行上，不弹窗。
     * 写入走 [NoteWriter.addHighlightsByLine]，一次只写这一行（L2）。
     */
    val lineTap: (LineBox?) -> Unit = { line ->
        val facts = factsHolder.get() ?: inkFacts
        when {
            !pageHasLines -> noteMessage = "这一页没有文本层，标不了高亮。"
            line == null -> noteMessage = "这一处没有正文行，换一行点。"
            // 「画线」与「高亮」共用同一次点行：两者的几何来源完全一样（这一行），
            // 差别只在写进库的标注类型。分成两套手势只会让用户记两种点法。
            underlineMode -> {
                val range = facts.lineHighlightRange(line)
                if (range == null) {
                    noteMessage = "这一行没有可画线的文字。"
                } else {
                    scope.launch {
                        val outcome = noteWriter.addUnderline(
                            start = range.first,
                            endExclusive = range.last + 1,
                            facts = facts,
                            wavy = underlineWavy,
                            pageKeyOf = ::pageKeyAt,
                        )
                        noteMessage = outcome.message
                        if (outcome.added) inkTicks.update { it + 1 }
                    }
                }
            }
            else -> {
                val range = facts.lineHighlightRange(line)
                if (range == null) {
                    noteMessage = "这一行没有可标的文字。"
                } else {
                    scope.launch {
                        val outcome = noteWriter.addHighlightsByLine(
                            start = range.first,
                            endExclusive = range.last + 1,
                            facts = facts,
                            pageKeyOf = ::pageKeyAt,
                        )
                        noteMessage = outcome.message
                        if (outcome.added) inkTicks.update { it + 1 }
                    }
                }
            }
        }
    }

    // 写入结果只留一会儿：它是一句话，不是需要一直盯着看的状态。
    // 撤销窗口与这句话同寿命：话没了却还能撤销，用户就找不到入口了。
    //
    // 10 秒（M15 之前是 6 秒）：撤销是"删错了"的唯一补救，6 秒对
    // "看到提示 → 意识到删错了 → 伸手去点"来说太紧。这不是为测试调的 ——
    // 设备测试只是暴露了它本来就偏短。
    LaunchedEffect(noteMessage, pendingUndo) {
        if (noteMessage != null) {
            delay(10_000)
            noteMessage = null
            pendingUndo = null
        }
    }

    // 离开界面时立即落盘（不走去抖）。用 `rememberUpdatedState` 而不是把 pageIndex
    // 当 key：按页 key 会让"翻页"先触发一次旧页的 flush，而它与新页的去抖保存
    // 会互相 cancel —— 最终存下哪一页取决于协程调度顺序，这种不确定不该存在。
    val latest = rememberUpdatedState(pageIndex to ratio)
    DisposableEffect(Unit) {
        onDispose { onFlush(0, latest.value.first, latest.value.second) }
    }

    val notice = when {
        !pdfTextLayerAvailable ->
            "本机 Android 版本读不到 PDF 文本层（需要 Android 15 及以上）"

        !session.hasTextLayer -> "这份 PDF 没有文本层（扫描件）"
        else -> null
    }

    // ── 界面外框（M15）──────────────────────────────────────────────────────
    // 与重排阅读界面同一套骨架（`ReaderChrome.kt`），但底色不同：
    // PDF 页是"纸"，纸周围的**工作台**要暗下来 —— 夜里看白纸像看灯。
    val chrome = rememberReaderChromeState()
    val holdChrome = penEnabled || highlightMode || underlineMode || searchOpen || pageNotesOpen

    // 进书即开始一次退场计时（M33），与重排界面同一条规则。
    LaunchedEffect(meta.id) { chrome.enterReader() }

    // 音量键翻页（M27）：与重排界面同一条规则（见 ReaderContent 的注释）。
    // PDF 只有"页"一种单位，上键=上一页、下键=下一页。
    val turnByKey: (Boolean) -> Unit = { forward ->
        chrome.onRead()
        val next = pageIndex + if (forward) 1 else -1
        if (next in 0..lastIndex) pageIndex = next
    }
    DisposableEffect(chrome) {
        container.readerVolumeKeyHandler = turnByKey
        onDispose {
            if (container.readerVolumeKeyHandler === turnByKey) {
                container.readerVolumeKeyHandler = null
            }
        }
    }

    // ── 拖动进度线翻页（M28）──────────────────────────────────────────────
    //
    // 拖动中 [seekRatio] 非空，用它盖住进度显示与页码（预览）；
    // 松手才真正跳页。lambda 捕获的是 State 委托，remember 一次即可。
    var seekRatio by remember(meta.id) { mutableStateOf<Float?>(null) }
    /** 拖动预览时的页码；不在拖动时就等于当前页。 */
    val previewPageIndex = seekRatio
        ?.let { (it * pageCount).toInt().coerceIn(0, lastIndex) }
        ?: pageIndex
    val onSeek: ((Float, Boolean) -> Unit)? = remember(meta.id) {
        { ratio, finished ->
            if (finished) {
                seekRatio = null
                chrome.onRead()
                // 显示的进度是 (页码+1)/页数，它的逆运算就是这里的页号映射。
                pageIndex = (ratio * pageCount).toInt().coerceIn(0, lastIndex)
            } else {
                seekRatio = ratio
            }
        }
    }

    ReadingScaffold(
        chrome = chrome,
        // PDF 页本身是白的（颜色由文件决定），这里只是页外的"工作台"。
        paper = theme.paper,
        hold = holdChrome,
        // 笔刷面板只在书写时展开；高亮 / 下划线 / 搜索 / 笔记这些弹层只压住自动隐藏。
        expand = penEnabled && brushPanelOpen,
        topBar = {
            ReaderFloatingTopBar(title = meta.title, onBack = onClose)
        },
        // 写入结果、落笔失败、模式提示共用这一条 —— 界面上只有一个地方说
        // "刚刚发生了什么"。它和托盘同处一层，所以笔刷面板展开时不会被盖住。
        notice = {
            val statusText = when {
                // 删除后的几秒里，这一条只有一个含义：撤销。用库返回的说明
                // （"已删除这条高亮（点这里撤销）"），不再另写一句话。
                pendingUndo != null -> noteMessage ?: "已删除（点这里撤销）。"
                else -> noteMessage
                    ?: inkNotice
                    ?: if (underlineMode) {
                        "点一行就能${if (underlineWavy) "画上波浪线" else "画上下划线"}；再点一次菜单项退出。"
                    } else if (highlightMode) {
                        "点一行就能把它标成高亮；再点一次「高亮」退出。"
                    } else if (eraserEnabled) {
                        "橡皮：点或划过笔迹就能擦掉；擦错了点提示条撤销。"
                    } else {
                        null
                    }
            }
            if (statusText != null) {
                // 语义挂在**外层容器**上，不是内层 Text 的 modifier：
                // 后者在真机上不生效（M13 那次设备测试把整棵语义树打出来才看见）。
                val undoable = pendingUndo != null
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clickable(enabled = undoable || noteMessage != null) {
                            if (undoable) undoPending() else noteMessage = null
                        }
                        .semantics {
                            if (undoable) {
                                role = Role.Button
                                contentDescription = "$statusText 点按撤销"
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (noteMessage == null && inkNotice != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        },
        tray = {
            ReaderTray(
                level = chrome.level,
                progress = seekRatio
                    ?: if (pageCount > 0) (pageIndex + 1f) / pageCount else null,
                onSeek = onSeek,
                ticks = remember(pdfTypedRows, pdfInkPages, pageCount) {
                    buildPdfTicks(pdfTypedRows, pdfInkPages, session, pageCount)
                },
                onJumpToTick = { tick ->
                    // 刻度跳转到"那条笔记"：用的是它自己的位置，
                    // 不是百分比 —— 百分比在重排后会指错（§6.3.1 规则 3）。
                    val page = pdfInkPages[tick.annotationId]
                        ?: pdfTypedRows.firstOrNull { it.annotationId == tick.annotationId }
                            ?.charOffset
                            ?.let { session.pageIndexOfChar(it) }
                    if (page != null && page >= 0) {
                        chrome.onRead()
                        pageIndex = page.coerceIn(0, lastIndex)
                    }
                },
                panel = {
                    InkBrushPanel(
                        brush = brush,
                        eraserEnabled = eraserEnabled,
                        showLasso = false,
                        lassoEnabled = false,
                        onBrushChange = { brush = it },
                        onEraserChange = { eraserEnabled = it },
                        onLassoChange = {},
                        selectedCount = 0,
                    )
                },
            ) {
                TrayTurnButton(
                    forward = false,
                    // 书写时**可以**翻页了（M25）。以前这里在书写时置灰，
                    // 理由是"这一页上正写着东西，翻过去等于把它们从眼前拿走"——
                    // 但那条理由只说明"翻页不该被误触"，不说明"翻页不该存在"。
                    // 真正要防的是误触，而误触由两件事挡住：
                    // ① 笔的触摸被 View 消费（落笔），不会变成翻页；
                    // ② 手指的左右两侧要落在热区才会翻，中间照常写字。
                    // 想翻页的人有明确的动作（点按钮 / 点两侧），不会误触。
                    enabled = pageIndex > 0,
                    onClick = {
                        chrome.onRead()
                        if (pageIndex > 0) pageIndex--
                    },
                )
                Text(
                    text = if (pageCount == 0) "没有页面" else "第 ${previewPageIndex + 1} / $pageCount 页",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                TrayTurnButton(
                    forward = true,
                    enabled = pageIndex < lastIndex,
                    onClick = {
                        chrome.onRead()
                        if (pageIndex < lastIndex) pageIndex++
                    },
                )
                Spacer(Modifier.weight(1f))

                if (penEnabled) {
                    IconButton(onClick = { inkController.undo() }, enabled = inkCanUndo) {
                        Icon(InkIcons.Undo, contentDescription = "撤销上一笔")
                    }
                    IconButton(onClick = { inkController.redo() }, enabled = inkCanRedo) {
                        Icon(InkIcons.Redo, contentDescription = "重做")
                    }
                }
                IconButton(
                    onClick = {
                        penEnabled = !penEnabled
                        // 模式互斥：同一块屏幕不能同时解释成"写下来"和"标这一行"。
                        if (penEnabled) {
                            highlightMode = false
                            underlineMode = false
                        }
                        // 退出书写时顺手关掉橡皮：橡皮是书写模式里的一个工具，
                        // 留着它会让下一次进入书写**直接处于擦除状态**（很危险）。
                        eraserEnabled = false
                        // 进书写时把面板收起，只留按钮高亮：屏幕要让给字。
                        brushPanelOpen = false
                        if (penEnabled) {
                            // 必须说出来：书写时**怎么**翻页，是新规则（笔写字、
                            // 手指点两侧翻页），用户不问就不知道。
                            noteMessage = "已在书写：笔写字，手指点左右两侧翻页。"
                        }
                    },
                    // 没有文本层就没有行盒，也就没有可以锚定的位置。
                    // 禁用比"允许写、写完丢掉"诚实得多。
                    enabled = pageHasLines,
                ) {
                    Icon(
                        InkIcons.Brush,
                        contentDescription = if (penEnabled) "退出书写" else "书写",
                        tint = if (penEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.material3.LocalContentColor.current
                        },
                    )
                }
                if (penEnabled) {
                    // 只在书写时出现：读的时候没有"笔的属性"这回事。
                    // 图标画的是**点了会发生什么**（展开→收起），不是当前状态。
                    IconButton(onClick = { brushPanelOpen = !brushPanelOpen }) {
                        Icon(
                            imageVector = if (brushPanelOpen) {
                                Icons.Default.KeyboardArrowDown
                            } else {
                                InkIcons.Tune
                            },
                            contentDescription = if (brushPanelOpen) {
                                "收起笔画设置"
                            } else {
                                "笔画设置（笔形、墨色、粗细）"
                            },
                            tint = if (brushPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.material3.LocalContentColor.current
                            },
                        )
                    }
                }
                IconButton(
                    onClick = {
                        highlightMode = !highlightMode
                        if (highlightMode) {
                            penEnabled = false
                            underlineMode = false
                        }
                    },
                    enabled = pageHasLines,
                ) {
                    Icon(
                        InkIcons.Highlight,
                        contentDescription = if (highlightMode) "退出高亮" else "高亮",
                        tint = if (highlightMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.material3.LocalContentColor.current
                        },
                    )
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全文搜索") },
                            enabled = session.hasTextLayer,
                            onClick = {
                                overflowOpen = false
                                searchOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("本页笔记（${pageNotes.size}）") },
                            onClick = {
                                overflowOpen = false
                                pageNotesOpen = true
                            },
                        )
                        // ── 下划线 / 波浪线（M13）────────────────────────────
                        // 放进菜单而不是托盘：托盘要留给"读的时候手边要有"的动作，
                        // 而这一项是偶尔用一次的。
                        DropdownMenuItem(
                            text = { Text(if (underlineMode) "退出画线" else "画线模式（点一行）") },
                            enabled = pageHasLines,
                            onClick = {
                                overflowOpen = false
                                underlineMode = !underlineMode
                                if (underlineMode) {
                                    penEnabled = false
                                    highlightMode = false
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (underlineWavy) "波浪线：开" else "波浪线：关") },
                            enabled = underlineMode,
                            onClick = {
                                overflowOpen = false
                                underlineWavy = !underlineWavy
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("在这里放书签") },
                            onClick = {
                                overflowOpen = false
                                val facts = factsHolder.get() ?: inkFacts
                                // 书签绑的是"读到哪儿"，不是"哪几个字"：取本页第一个
                                // 有文字的行作为字符偏移，取不到就用 0（页号兜底仍在）。
                                val offset = currentPageLines.firstOrNull()
                                    ?.let { facts.absoluteRangeOf(it)?.first } ?: 0
                                scope.launch {
                                    val outcome = noteWriter.addBookmark(
                                        charOffset = offset,
                                        facts = facts,
                                        pageKey = "p${pageIndex + 1}",
                                    )
                                    noteMessage = outcome.message
                                    if (outcome.added) inkTicks.update { it + 1 }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出本页 PNG") },
                            onClick = {
                                overflowOpen = false
                                pageExporter.png(
                                    bitmap = pageView?.renderToBitmap(),
                                    title = meta.title,
                                    pageLabel = "第${pageIndex + 1}页",
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("批注写回 PDF（另存）") },
                            onClick = {
                                overflowOpen = false
                                scope.launch {
                                    // 计划在**按下这一刻**算好：用户在选择器里挑目录、
                                    // 改名、犹豫的时候，写出去的东西不能跟着变。
                                    // 几何全部来自"屏幕上正在画的那一批"对象
                                    // （pdfHighlights / pdfUnderlines / inkStrokes），
                                    // 不重新解析锚点 —— 见 `PdfExportSources` 的注释。
                                    val snapshot = container.store.annotations.listByDoc(meta.id)
                                    // 笔迹 → 标注 的归属：库里有（一笔属于哪条标注），
                                    // 而 `DrawableStroke` 只带 strokeId。这个映射没有第二个来源。
                                    val ownerOf = HashMap<String, String>()
                                    for (ink in snapshot.annotations.filterIsInstance<Annotation.Ink>()) {
                                        for (stroke in snapshot.strokesOf(ink.id)) {
                                            ownerOf[stroke.strokeId] = ink.id
                                        }
                                    }
                                    val plan = PdfAnnotationPlanner.plan(
                                        pages = PdfExportSources.pageBoxes(session),
                                        bands = PdfExportSources.highlightBands(pdfHighlights, inkFacts) +
                                            PdfExportSources.underlineBands(pdfUnderlines),
                                        inks = PdfExportSources.inkSources(inkStrokes) { ownerOf[it] },
                                        notes = PdfExportSources.noteSources(
                                            snapshot.annotations
                                                .filterIsInstance<Annotation.MarginNote>(),
                                            inkFacts,
                                        ),
                                        bookmarks = snapshot.annotations
                                            .filterIsInstance<Annotation.Bookmark>()
                                            .map { it.id },
                                    )
                                    // 先报"没什么可写"，再报"这台机器不行"：两条都不写文件，
                                    // 但用户此刻最想知道的是前者（他的标注是不是白做了）。
                                    if (plan.isEmpty) {
                                        noteMessage = plan.summary()
                                        return@launch
                                    }
                                    if (!PdfAnnotationWriter.isSupported()) {
                                        noteMessage = PdfAnnotationWriter.unsupportedReason()
                                        return@launch
                                    }
                                    pageExporter.annotatedPdf(
                                        source = container.store.paths.resolveDocument(meta.relativePath),
                                        plan = plan,
                                        title = meta.title,
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出笔记 JSON") },
                            onClick = {
                                overflowOpen = false
                                scope.launch {
                                    pageExporter.json(notesJson(), meta.title)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("笔记（整篇）") },
                            onClick = {
                                overflowOpen = false
                                onOpenNotes()
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // PDF 页是"纸"：深色主题下也不该把纸染黑（正文颜色由文件决定），
                // 但纸周围的工作台要暗下来，否则夜里看白纸像看灯。
                .background(theme.paper)
                .onSizeChanged { area = it }
                // 与重排阅读界面同一套翻页热区：左 1/3 上一页、右 1/3 下一页。
                // 按钮仍然保留 —— 热区是"读起来顺手"，按钮是"看得见、找得到"。
                // 书写模式下热区**开着**（M25）：笔的触摸被 PdfInkPageView 消费（落笔），
                // 到不了这里；手指的左右两侧由 View 的翻页分区让出来。
                // 高亮模式下仍然关掉：点行标高亮与翻页必须二选一。
                .pointerInput(pageIndex, lastIndex, penEnabled, highlightMode) {
                    if (highlightMode) return@pointerInput
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        when {
                            width <= 0f -> Unit
                            offset.x < width / 3f && pageIndex > 0 -> {
                                chrome.onRead()
                                pageIndex--
                            }

                            offset.x > width * 2f / 3f && pageIndex < lastIndex -> {
                                chrome.onRead()
                                pageIndex++
                            }

                            // 点正中切换顶栏与托盘（M31）：在收、收着放。
                            // 这条路必须走 `detectTapGestures`：
                            // 页位图是一个原生 View，父 Box 上的 pointerInput 收不到触摸。
                            // 书写时不切换（M32）：那一下是笔，不是"叫菜单"。
                            else -> if (!penEnabled) chrome.onTap()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = pageBitmap
            val currentPage = session.pages.getOrNull(pageIndex)
            val openError = opened.exceptionOrNull()
            when {
                renderer == null -> CenteredMessage(
                    "打不开这份 PDF：${openError?.message ?: "文件可能已损坏"}",
                )

                failure != null -> CenteredMessage(failure!!)

                pageCount == 0 -> CenteredMessage("这份 PDF 没有页面")

                currentPage != null && shown != null && bitmapPage == pageIndex -> AndroidView(
                    // 语义挂在 AndroidView 这一层：原生 View 自己不在语义树里，
                    // 而设备测试与无障碍都要能"看见"这一页。
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "第 ${pageIndex + 1} 页" },
                    factory = { ctx -> PdfInkPageView(ctx).also { pageView = it } },
                    update = { view ->
                        // 页宽高是**文档单位**（PDF point），不是位图像素：
                        // 视图自己按视口算缩放，位图与笔迹因此共用同一个变换。
                        view.submitPage(
                            bitmap = shown,
                            pageWidthPx = currentPage.widthPx,
                            pageHeightPx = currentPage.heightPx,
                            pageTopPx = session.pageTopPx(pageIndex),
                            fontSizePx = currentPage.lines.firstOrNull()?.fontSizePx
                                ?: PdfDocumentSession.FALLBACK_FONT_SIZE_PX,
                            lines = currentPageLines,
                        )
                        view.submitInk(inkStrokes, inkCoverage)
                        view.submitBands(pdfBands)
                        view.submitUnderlines(pdfUnderlines)
                        // 高亮的色带、命中行与文字共用同一个变换：翻页时不会出现
                        // "字在第二页、色带还停在第一页"的中间态。
                        view.onLineTap = lineTap
                        // 书写模式才接管触摸；否则事件上浮给 Compose 的翻页热区。
                        // 橡皮也是"接管触摸"的一种，所以它同样要求 penEnabled。
                        // 控制器一直挂着（它要负责把"抬笔了、干墨还没到"的那一笔画完），
                        // 开关只决定这一下触摸算不算写字 —— 理由见 `DocumentInkView.attachInk`。
                        view.attachInk(
                            if (pageHasLines) inkController else null,
                            enabled = penEnabled && !eraserEnabled && pageHasLines,
                        )
                        view.attachEraser(eraserEnabled && pageHasLines)
                        view.attachStylusOnly(stylusOnly)
                        // 渲染/输入调优（M26）：同重排阅读界面。
                        view.configureInk(staticLayer = inkStaticLayer, unbuffered = inkUnbuffered)
                        // 翻页分区（M25）：书写时手指落在左右各 1/3 让给翻页热区。
                        // 设置里可以整体关掉（见 ReaderSettings.pageTurnZones）。
                        view.attachPageTurnZones(penEnabled && pageTurnZones)
                        view.onErase = ::eraseStroke
                        view.onEraseMiss = { noteMessage = "这里没有笔迹，没有擦掉任何东西。" }
                        view.attachLineTaps((highlightMode || underlineMode) && pageHasLines)
                    },
                )

                else -> CenteredMessage("正在渲染第 ${pageIndex + 1} 页…", showSpinner = true)
            }

            // 整份文档级别的说明（没有文本层 / 本机读不到文本层）。
            // 旧版把它占在正文**上方**，一出现就把页位图挤小、按新视口重渲染；
            // 现在它浮在页面上沿，页的尺寸不变。
            if (notice != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        text = "$notice：只能按页查看，不能选中文字或按行锚定笔记。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // PDF 的全文搜索面板（M11）。
    //
    // 与重排阅读界面的面板是**两份**：那一边的"跳到"是滚动到字符偏移、还能把
    // 解不开的笔记改挂到某一行；PDF 这边"跳到"是换一页，也没有重排后的重锚。
    // 共用的是更重要的东西：`ReaderSearch` 的命中口径、`SearchHitRow` 的显示、
    // 以及"关面板就清查询"的清理路径。
    if (searchOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                searchOpen = false
                searchQuery = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    label = { Text("在正文和我的批注中搜索") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = searchStatusText(searchResult, searchIndex),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { goToPdfSearchHit(searchIndex - 1) },
                        enabled = searchHits.size > 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一处")
                    }
                    IconButton(
                        onClick = { goToPdfSearchHit(searchIndex + 1) },
                        enabled = searchHits.size > 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一处")
                    }
                }

                val listState = rememberLazyListState()
                LaunchedEffect(searchIndex, searchResult) {
                    if (searchResult.isEmpty) return@LaunchedEffect
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == searchIndex }) {
                        listState.animateScrollToItem(searchIndex)
                    }
                }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .heightIn(min = 48.dp),
                    state = listState,
                ) {
                    itemsIndexed(
                        items = searchResult.hits,
                        key = { _, hit -> searchHitKey(hit) },
                    ) { index, hit ->
                        val textHit = hit as? ReaderSearch.TextHit
                        SearchHitRow(
                            hit = hit,
                            text = session.normalized.text,
                            current = index == searchIndex,
                            onJump = {
                                searchIndex = index
                                hit.charOffset?.let { offset ->
                                    session.pageIndexOfChar(offset).takeIf { it >= 0 }?.let { pageIndex = it }
                                }
                            },
                            // 只有正文命中能标高亮（批注命中要标的是"我写过的那句话"，
                            // 那句话本身已经在库里了）。
                            onHighlight = if (textHit == null) {
                                null
                            } else {
                                {
                                    val facts = factsHolder.get() ?: inkFacts
                                    scope.launch {
                                        // PDF 一行 = 一块，一条高亮链不能跨块：
                                        // 跨行的命中在 `addHighlightsByLine` 里拆成每行一条。
                                        val outcome = noteWriter.addHighlightsByLine(
                                            start = textHit.start,
                                            endExclusive = textHit.endExclusive,
                                            facts = facts,
                                            pageKeyOf = ::pageKeyAt,
                                        )
                                        noteMessage = outcome.message
                                        if (outcome.added) inkTicks.update { it + 1 }
                                    }
                                }
                            },
                        )
                    }
                }
                if (searchResult.isEmpty && searchResult.query.isNotEmpty()) {
                    Text(
                        text = "正文和我写下的批注里都没有「${searchResult.query}」。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // PDF 的「本页笔记」面板（M12）。
    //
    // 与重排阅读界面的「本页笔记」是两份：那边的"本页"由分页结果定义，
    // 这边的"本页"由 `PdfDocumentSession.pageIndexOfChar` 定义；两边都不自己算位置。
    // 共用的是删除/撤销同一条路（`NoteWriter` + 软删除），以及"笔迹不在清单里删"
    // 这条纪律 —— 一笔跨行时是几段标注，按段删只会擦掉一笔的一部分。
    if (pageNotesOpen) {
        ModalBottomSheet(
            onDismissRequest = { pageNotesOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = "第 ${pageIndex + 1} 页 · 本页笔记",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp),
            )
            Text(
                text = "这里列出本页的高亮与边注。删除是软删除，删完状态行会留一句「点这里撤销」。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                // 撤销放在面板**里面**：ModalBottomSheet 的遮罩会挡住状态行，
                // 让用户"关掉面板再去找撤销"是把一个可逆操作变得像不可逆。
                if (pendingUndo != null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = noteMessage ?: "已删除（可撤销）。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { undoPending() }) {
                                Text("撤销")
                            }
                        }
                    }
                }
                if (pageNotes.isNotEmpty()) {
                    item { NoteSectionHeader("本页（${pageNotes.size}）") }
                    items(pageNotes, key = { it.annotationId }) { row ->
                        PdfPageNoteRow(
                            row = row,
                            quote = quoteOf(row),
                            colorArgb = highlightById[row.annotationId]?.colorArgb,
                            onDelete = { deletePageNote(row.annotationId) },
                        )
                    }
                }
                if (unplacedNotes.isNotEmpty()) {
                    item { NoteSectionHeader("位置不明（${unplacedNotes.size}）") }
                    items(unplacedNotes, key = { it.annotationId }) { row ->
                        PdfPageNoteRow(
                            row = row,
                            quote = quoteOf(row),
                            colorArgb = highlightById[row.annotationId]?.colorArgb,
                            onDelete = { deletePageNote(row.annotationId) },
                        )
                    }
                }
                if (pageNotes.isEmpty() && unplacedNotes.isEmpty()) {
                    item {
                        Text(
                            text = if (pageHasLines) {
                                "这一页还没有高亮或边注。在阅读页点「高亮」再点一行，就能标上。"
                            } else {
                                "这一页没有文本层（扫描页）：没有行可以锚定高亮或边注。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                item {
                    Text(
                        text = "手写笔迹不在这条清单里删：一笔跨行时是几段标注，按段删只会擦掉" +
                            "一笔的一部分。笔迹请用顶部的撤销 / 重做。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    container: AppContainer,
    meta: DocumentMeta,
    session: ReflowableDocumentSession,
    settings: ReaderSettings,
    /** 已经解析过的阅读主题（不含 AUTO）。 */
    theme: ReaderTheme,
    savedCharOffset: Int,
    savedPageIndex: Int,
    /** 从笔记清单跳回原位时带进来的笔记 id（A3）；为 null 表示不是这么进来的。 */
    initialNoteId: String? = null,
    onClose: () -> Unit,
    onOpenNotes: () -> Unit,
    onProgress: (Int, Int, Float) -> Unit,
    onFlush: (Int, Int, Float) -> Unit,
) {
    val context = LocalContext.current

    // 屏幕常亮走原生的窗口标志，而不是 Compose 的副作用：
    // 它作用在 Activity 的窗口上，与 Compose 是否在重组无关。
    DisposableEffect(settings.keepScreenOn) {
        val window = context.findActivity()?.window
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // 离开界面或用户关掉开关时一律清除。条件化清除会留下"关了设置但屏幕还亮着"的残留。
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val density = LocalDensity.current
    val densityDpi = context.resources.displayMetrics.densityDpi
    val paged = settings.layoutMode != LayoutMode.SCROLL

    // 字号（px）。LOCKED 模式下不乘系统字号缩放：锁定版式的意思是
    // "冻结排版输入"，而系统字号正是最容易在用户不知情时改变排版的一个输入。
    val baseTextPx = with(density) { settings.fontSizeSp.sp.toPx() }
    val textSizePx = if (settings.layoutMode == LayoutMode.LOCKED) {
        settings.fontSizeSp * density.density
    } else {
        baseTextPx
    }

    // 纸色不进排版，墨色进：正文/标题/代码/引用的颜色都由当前主题派生，
    // 换主题时"纸变了字没变"这种事在结构上就不会发生。
    // 字体也进排版（M29）：换字体会改变度量与断行，属于排版输入。
    val documentTheme = remember(
        textSizePx, settings.lineSpacing, theme, settings.fontFamily,
        settings.paragraphSpacingEm, settings.justifyText, settings.cjkTypesetting,
    ) {
        DocumentTheme.withInk(
            textSizePx = textSizePx,
            lineSpacingMultiplier = settings.lineSpacing,
            inkArgb = theme.inkArgb,
            isDark = theme.isDark,
            typeface = settings.fontFamily.resolveTypeface(),
            // 排版开关（M33）：段间距、两端对齐、中文禁则都是排版的**输入**，
            // 与字号行距同级 —— 改了它们必须重排，所以它们进 theme、也进缓存键。
            paragraphSpacingEm = settings.paragraphSpacingEm,
            justifyText = settings.justifyText,
            cjkPunctuation = settings.cjkTypesetting,
        )
    }

    // 绘制区的实际像素尺寸。用 onSizeChanged 而不是 BoxWithConstraints：
    // 前者给的是**已经扣掉顶栏与翻页条之后**的真实可用区域，
    // 而分页必须按真实可用高度来切 —— 用估计值会让最后一行的位置随工具栏高度漂移。
    var area by remember(meta.id) { mutableStateOf(IntSize.Zero) }

    /** 当前渲染视图（导出 PNG 用）：与屏幕同一段绘制代码，导出结果才等于"看到的样子"。 */
    var pageView by remember(meta.id) { mutableStateOf<DocumentInkView?>(null) }

    val marginPx = Units.mmToPx(settings.pageMarginMm, densityDpi)
    // 正文框不再直接等于"屏宽 − 2 × 边距"：16 mm 的纸书边距在 411 dp 的手机上会吃掉
    // 一半屏宽（M8 记录 §1）。[PageGeometry] 让边距给正文让位、给行宽封顶，
    // 规则与数字都在那个纯函数里，这里只负责把屏幕事实喂进去。
    //
    // 纵向从 M33 起不再跟着毫米边距走（毫米是纸书的左右边距概念），
    // 从 M34 起改成**只留一口气**：顶栏与托盘是浮层，盖在正文上，
    // 所以正文只需要避开安全区与手势条，不必给控件让出一块永久留白。
    // 让位仍必须是常量 —— 它跟着控件显隐变的话，每次点屏幕都要重排整篇。
    val topReservePx = with(density) { READER_TOP_BREATH.toPx() }
    val bottomReservePx = with(density) { READER_BOTTOM_GAP.toPx() }
    val metrics = remember(
        area, marginPx, textSizePx, density.density, topReservePx, bottomReservePx,
    ) {
        if (area.width <= 0 || area.height <= 0) {
            null
        } else {
            PageGeometry.solve(
                widthPx = area.width,
                heightPx = area.height,
                userMarginPx = marginPx,
                textSizePx = textSizePx,
                density = density.density,
                topReservePx = topReservePx,
                bottomReservePx = bottomReservePx,
            )
        }
    }

    // 排版放到 Default 调度器上：一份十万字的小说要构建若干 StaticLayout，
    // 放在主线程上会有肉眼可见的卡顿。
    val layoutState by produceState<ReflowableTextLayout?>(null, session, metrics, documentTheme) {
        val m = metrics
        value = if (m == null) {
            null
        } else {
            withContext(Dispatchers.Default) { session.layout(m, documentTheme) }
        }
    }
    val layout = layoutState

    var pageIndex by remember(meta.id) { mutableIntStateOf(savedPageIndex.coerceAtLeast(0)) }
    var restoreDone by remember(meta.id) { mutableStateOf(false) }
    var currentOffset by remember(meta.id) { mutableIntStateOf(savedCharOffset) }
    var currentRatio by remember(meta.id) { mutableFloatStateOf(0f) }

    /**
     * 一次性定位请求（字符偏移），只对滚动模式有意义：滚动模式没有"页"这个单位，
     * 唯一的定位量是像素。目录跳转与"恢复上次阅读位置"都从这里进。
     *
     * 带序号（而不是只带偏移）是为了"再跳回同一章"能重新触发一次：
     * 只比较偏移的话，第二次点同一章与上一次的值相等，LaunchedEffect 不会重跑，
     * 用户会觉得"点了没反应"。
     */
    var scrollJump by remember(meta.id) { mutableStateOf<ScrollJump?>(null) }
    var jumpSeq by remember(meta.id) { mutableIntStateOf(0) }
    fun requestScrollJump(charOffset: Int) {
        jumpSeq += 1
        scrollJump = ScrollJump(charOffset, jumpSeq)
    }

    // 定位：打开文档时恢复上次的位置；**换版式时把当前位置带过去**。
    //
    // 必须等排版就绪才做 —— 两种模式都要先有排版：分页要查"这个字符落在哪一页"，
    // 滚动要查"这个字符所在行的顶边在哪"。用字符偏移而不是页码，理由同 §6.3.1 规则 3。
    LaunchedEffect(layout, paged) {
        val l = layout ?: return@LaunchedEffect
        // 第一次**从库里现读**上次的位置（M36），而不是用传进来的 `savedCharOffset`。
        //
        // 因为 ViewModel 是挂在 Activity 上的（`viewModel(key = "reader-$docId")`）：
        // 退出阅读界面它并不会被销毁，下次再点开同一本书复用的还是这个实例，
        // 里面的 `savedProgress` 停在上一次进书时读到的那份。用它恢复的症状是个死循环：
        // 读了五十页 → 回书架 → 再点进来 → 直接退回五十页之前（而且这次"恢复"
        // 写回库的值又会把刚读的位置覆盖掉，再进还是那儿）。
        //
        // 现读一次是一次本地查询（一行），而恢复本来就要等排版，不差这一下。
        val first = !restoreDone
        val saved = if (first) {
            runCatching { container.store.documents.progressOf(meta.id)?.charOffset }.getOrNull()
                ?: savedCharOffset
        } else {
            currentOffset
        }
        val target = saved
        restoreDone = true
        // 先把"当前位置"对齐到刚读出来的这一份：用户若在滚动落位之前就退出，
        // 落盘的至少是**库里那一份**，而不是进书时那份更旧的快照。
        if (first) currentOffset = saved
        if (paged) {
            if (l.pages.isEmpty()) return@LaunchedEffect
            val index = l.pageIndexOfCharOffset(target).coerceIn(0, l.pages.lastIndex)
            pageIndex = index
            currentOffset = l.pages[index].charStart
            currentRatio = ratioOf(session, currentOffset)
        } else if (target > 0) {
            requestScrollJump(target)
        }
    }

    val pages = layout?.pages.orEmpty()
    val pageCount = pages.size
    val safePageIndex = pageIndex.coerceIn(0, maxOf(0, pageCount - 1))

    // 离开界面时立即落盘一次（M36）——**不管从哪个口出去**。
    //
    // 此前只有顶栏那个返回按钮会 flush：按系统返回键或用手势返回时，界面直接离场，
    // 去抖的 800ms 还没到就被取消了，于是"最后读到哪儿"丢掉一截。PDF 界面
    // （`PdfReaderContent`）一直是两个口都落盘的，重排界面漏了这一半 ——
    // 同一个应用里两条退出路径行为不同，用户只会觉得"有时候记得住有时候记不住"。
    //
    // 用 `rememberUpdatedState` 而不是把偏移当 key：按偏移 key 会让每一次滚动都
    // 重新装一次 DisposableEffect，而 onDispose 里写的是上一次的值。
    val flushSnapshot = rememberUpdatedState(
        Triple(currentOffset, safePageIndex, currentRatio),
    )
    DisposableEffect(Unit) {
        onDispose {
            val (offset, index, ratio) = flushSnapshot.value
            onFlush(offset, index, ratio)
        }
    }

    // ── 翻页动效的"旧页"快照（M33）────────────────────────────────────────
    //
    // 仿真翻页要画"翻走的那一页"，而页码一变，屏幕上就只剩新页了。所以位图必须
    // 在**改页码之前**截：这一段就是那个"之前"。截的是 `DocumentInkView` 自己的
    // 绘制结果（文字 + 笔迹 + 高亮同源），不是 Compose 层的截图。
    var turnBitmap by remember(meta.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    /**
     * 翻到第 [index] 页。**所有"往前/往后走一页"的入口都走这里**
     * （点左右两侧、托盘箭头、音量键、拖动进度条）：动效的准备工作只写一次，
     * 漏掉一条入口的表现是"有时候有动画、有时候没有"，最难查。
     *
     * 目录跳转不经过它 —— 一次跨几十页的跳转不该播翻页动画，
     * 那会把"我跳到了那一章"演成"我翻了三十页"。
     */
    fun showPage(index: Int) {
        if (index !in pages.indices || index == safePageIndex) return
        if (settings.turnEffect == TurnEffect.CURL) {
            turnBitmap = runCatching { pageView?.renderToBitmap(theme.paperArgb) }.getOrNull()
        }
        pageIndex = index
    }

    // ── 目录（章节跳转）──────────────────────────────────────────────────────
    //
    // 目录项来自会话（EPUB 按 spine 生成；TXT/Markdown 由标题启发式生成）。
    // 跳转刻意走**与恢复阅读进度同一条路**（字符偏移 → 位置），而不是"第几条目录 → 第几页"：
    // 页码只在同一套排版参数下有意义，换字号后目录就会指错（§6.3.1 规则 3）。
    val toc = session.toc
    var tocOpen by remember(meta.id) { mutableStateOf(false) }

    fun jumpToCharOffset(charOffset: Int) {
        // 手动跳转之后不许再被"恢复上次进度"覆盖
        restoreDone = true
        if (paged) {
            val l = layout ?: return
            if (l.pages.isEmpty()) return
            val target = l.pageIndexOfCharOffset(charOffset).coerceIn(0, l.pages.lastIndex)
            pageIndex = target
            val page = l.pages[target]
            currentOffset = page.charStart
            currentRatio = ratioOf(session, page.charStart)
            onProgress(page.charStart, target, currentRatio)
        } else {
            requestScrollJump(charOffset)
        }
    }

    // ── 全文搜索（M6 / M6b）──────────────────────────────────────────────────
    //
    // 搜索跑在**归一化文本**上：那是全工程唯一的"文字坐标"，锚点链、行盒、
    // 跳转偏移全用它。拿原文去搜会让命中偏移变成第二套坐标（见 `TextSearch` 的注释）。
    //
    // M6b 起搜索范围是**正文 + 我写的批注**（方案 §1.3），结果合成一份清单。
    // 拼接与排序全在 `ReaderSearch` 里（那是纯逻辑，能在本机测），界面只负责显示。
    var searchOpen by remember(meta.id) { mutableStateOf(false) }
    var searchQuery by remember(meta.id) { mutableStateOf("") }
    var searchResult by remember(meta.id) { mutableStateOf(ReaderSearch.Result.EMPTY) }
    /** 当前是清单里的第几条（0 起）。 */
    var searchIndex by remember(meta.id) { mutableIntStateOf(0) }

    val searchHits = searchResult.hits
    val searchHit = searchHits.getOrNull(searchIndex.coerceIn(0, maxOf(0, searchHits.lastIndex)))
    // 重新搜索（LaunchedEffect）与"当前这一条标在页面哪个区间"都在下面：
    // 两者都要用**当前排版**下的本页批注清单与排版事实，而那些东西到那里才存在。

    /**
     * 上一处 / 下一处。到头折回另一端：连着点同一头不该"点了没反应"。
     *
     * 跳不过去的那几条（暂时对不上正文的边注）照样会被走到：它们是清单里真实的一行，
     * 跳过它们会让计数器和标记对不上（"第 3 条"标在清单的第 5 行上）。
     * 走到它们时只是不跳页面，原因就写在那一行上。
     */
    fun goToSearchHit(index: Int) {
        if (searchHits.isEmpty()) return
        val count = searchHits.size
        val target = ((index % count) + count) % count
        searchIndex = target
        searchHits[target].charOffset?.let { jumpToCharOffset(it) }
    }

    // ── 笔迹（M3b）────────────────────────────────────────────────────────────
    //
    // 单一真相源是数据库：落笔写库，写完**再读回来**解析成"可画的笔迹"。
    // 因此内存里不存在第二份笔迹列表需要与库对账 —— 代价是每落一笔多一次读事务，
    // 换来的是"屏幕上看到的永远是库里真实存在的东西"。
    var penEnabled by remember(meta.id) { mutableStateOf(false) }
    /** 当前笔刷（三支笔 × 墨色 × 粗细）。 */
    var brush by remember(meta.id) { mutableStateOf(BrushSpec.DEFAULT_PEN) }
    /** 橡皮模式：与书写互斥，命中判据在 `DocumentInkView`，删除与撤销在这里。 */
    var eraserEnabled by remember(meta.id) { mutableStateOf(false) }
    /** 套索模式（S1）。 */
    var lassoEnabled by remember(meta.id) { mutableStateOf(false) }
    /** 套索选中的笔迹 id。空的表示"没选" —— 套索关掉时也要跟着清。 */
    var selectedInkIds by remember(meta.id) { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * 笔刷面板（笔形 / 墨色 / 粗细）是否展开。
     *
     * 默认**收起**：书写时屏幕该留给字。想调笔的属性再点一下"笔画设置"。
     * 这是 M17 的产品判断 —— 之前一进书写就把整块面板顶上来，
     * 屏幕下半部被占掉 120 dp，而选中哪支笔一天也改不了几次。
     */
    var brushPanelOpen by remember(meta.id) { mutableStateOf(false) }
    var inkUnderlines by remember(meta.id) { mutableStateOf<List<DrawableUnderline>>(emptyList()) }
    var inkStrokes by remember(meta.id) { mutableStateOf<List<DrawableStroke>>(emptyList()) }
    /**
     * 上一版解析"覆盖了哪些笔迹"。
     *
     * 与 [inkStrokes] 必须**同一份解析结果**：它是抬笔暂存的交接凭据，
     * 分开算就会有"墨迹里有它、凭据里没有"（湿墨被多留一帧）
     * 或反过来（湿墨被提前撤掉，屏幕上闪一下）的窗口。
     */
    var inkCoverage by remember(meta.id) { mutableStateOf(InkCoverage.EMPTY) }

    var inkNotice by remember(meta.id) { mutableStateOf<String?>(null) }
    /** 每一条笔记相对**当前排版**的状态（含画不出来的）。见 `:core:ink` 的 `rowsForDisplay`。 */
    var inkRows by remember(meta.id) { mutableStateOf<List<InkNoteRow>>(emptyList()) }
    /**
     * 打字批注（边注）相对当前排版的状态。
     *
     * 与 [inkRows] 分成两份列表，而不是塞进同一个界面模型：笔迹多出两条轴
     * （墨迹在不在屏幕上 [InkNoteRow.drawn]、有没有笔迹可挂 [InkNoteRow.hasStrokes]），
     * 打字批注这两条都不存在 —— 合并就得给边注编一个假值，
     * 而界面上那句"这笔暂时画不出来"立刻变成谎言。
     */
    var typedRows by remember(meta.id) { mutableStateOf<List<TypedNoteRow>>(emptyList()) }
    /**
     * 已经标好的高亮相对**当前排版**的区间（含颜色）。
     *
     * 与 [typedRows] 出自**同一次**解析：面板里说"已定位"的那一条，正文里就必须真有底色。
     * 界面自己不判断"这一段标上没标上" —— 那正是本工程吃过两次亏的错误模式。
     */
    var highlightBands by remember(meta.id) { mutableStateOf<List<DrawableHighlight>>(emptyList()) }
    var problemsOpen by remember(meta.id) { mutableStateOf(false) }
    /**
     * 「一键清除画不出来的笔迹」的二次确认。
     *
     * 为什么要确认：这个动作**真的删数据**。笔迹现在画不出来，可能只是换了字号
     * 之后锚点暂时对不上 —— 用户点这一下，那些墨迹就永久没了（撤销窗口之外找不回来）。
     */
    var confirmClearProblems by remember(meta.id) { mutableStateOf(false) }
    /**
     * 工具栏右侧的「更多」菜单。
     *
     * 第一次上模拟器就撞上了这个：阅读界面最多有 9 个图标动作，411dp 宽的屏幕
     * （Pixel 6 Pro）在打开书写模式后**放不下** —— 标题被压成 0 宽，
     * 左边两个图标直接叠在了一起。这里把"偶尔用一次"的四个动作
     * （全文搜索 / 目录 / 加边注 / 笔记清单）收进菜单，工具栏在任何模式下
     * 都只留 5 个动作：360dp 也放得下（5×48 + 48 = 288dp，还剩 72dp 给标题）。
     */
    var overflowOpen by remember(meta.id) { mutableStateOf(false) }
    /**
     * 排版面板（M8）。
     *
     * 「铺满屏幕」不是一个偷偷改掉的常量：字号 / 行距 / 边距都在这里当场可调，
     * 面板上直接写出"一行约几个字、正文占屏宽百分之多少" —— 也就是这次缺陷的度量。
     */
    var typePanelOpen by remember(meta.id) { mutableStateOf(false) }
    /**
     * 面板里的草稿。拖动时只改它，**松手才写库**：写库会触发整篇重排
     * （重建若干 StaticLayout），边拖边排会掉帧。
     */
    var typeDraft by remember(meta.id) { mutableStateOf<ReaderSettings?>(null) }
    /**
     * 画不出来的笔记才是需要解释的对象：**屏幕上真有一个像素都没有**的那些。
     *
     * 判据是 `drawn` 而不是 `state`：PINNED / FREE 的墨迹固定在页面上，
     * 锚点解不开时它照样画在原处（见 `InkResolver` 的那条例外），
     * 把它列进"画不出来"会让用户去面板里找一条其实就在眼前的笔记。
     */
    val problemRows = inkRows.filter { !it.drawn }
    /**
     * 已经挂在页面上的笔记：面板的第二组。
     *
     * 它们不"需要处理"，但可以**改挂** —— 用户完全可能就是要让一条记好的笔记换一行。
     * 之所以现在才把它们列出来：`canReanchor` 此前只对画不出来的笔记开放，
     * 理由是"面板只列问题，给已经画好的笔记塞个按钮，它看起来像装饰"。
     * 面板扩成"本页笔记"之后，那个按钮对已经挂好的笔记才第一次有了确切含义（改挂）。
     */
    val settledRows = inkRows.filter { it.drawn }
    /**
     * 打字批注里**暂时挂不回任何一行**的那些。
     *
     * 它们的处境与"画不出来"不同：内容一直都在（会显示出来），丢的只是它与正文的联系。
     * 所以它们不进 [problemRows]（那条清单讲的是墨迹），但必须被算进"有问题"里 ——
     * 否则工具栏上不会有任何提示，用户根本不知道点开这个面板。
     */
    val typedProblems = typedRows.filter { !it.located }
    /** 工具栏图标、面板标题共用的"有多少条暂时对不上正文"。两类笔记在这里第一次合流。 */
    val problemCount = problemRows.size + typedProblems.size
    /** 每处理一次库变化就自增。工具栏的"能否撤销"读的是控制器里的普通字段，
     *  靠它保证状态变化时一定重组合一次。 */
    var inkEpoch by remember(meta.id) { mutableIntStateOf(0) }
    /**
     * 当前排版事实的**界面可见**副本。
     *
     * 与 [factsHolder] 是同一个对象，多这一份是因为界面也要用它：预览
     * "这条笔记将被挂到哪一行"必须能触发重组（`AtomicReference` 不会）。
     * 两份指向同一个值，不存在"两边算出不同答案"的余地。
     */
    var inkFacts by remember(meta.id) { mutableStateOf<LayoutFacts?>(null) }

    /**
     * 搜索清单里当前那一条要在页面上标出来的文字区间。
     *
     * 放在这里（而不是搜索那一段代码里）只为一个原因：它要用上面那份排版事实。
     * 边注命中在正文里**没有**对应的字 —— 能指的只有"它挂在哪一行"，
     * 而"哪个偏移落在哪一行"只有排版事实知道（`lineAtOffset`，与"确认位置"同一个入口）。
     *
     * 高亮的几何与行盒同源（同一个 marginLeft、同一组 lineTop/lineBottom），
     * 所以"高亮框"与"笔迹锚定的那一行"不可能对不上。
     */
    val searchHighlightRange: IntRange? = remember(searchHit, inkFacts) {
        when (val hit = searchHit) {
            null -> null
            is ReaderSearch.TextHit -> hit.start until hit.endExclusive
            is ReaderSearch.NoteHit -> {
                val facts = inkFacts
                val offset = hit.charOffset
                val line = if (facts == null || offset == null) null else facts.lineAtOffset(offset)
                if (line == null || facts == null) null else facts.absoluteRangeOf(line)
            }
        }
    }
    val searchRects = remember(layout, searchHighlightRange) {
        val l = layout
        val range = searchHighlightRange
        if (l == null || range == null) emptyList() else l.rectsOfCharRange(range.first, range.last + 1)
    }

    /**
     * 我自己标的高亮要画的矩形（文档坐标 + 每条的颜色）。
     *
     * 区间 → 矩形的换算只能在排版层做（只有它知道每个字画在哪），
     * 而"是哪几个字"来自解析结果（[highlightBands]）。界面在这里只是把两者接起来：
     * 既不推算坐标，也不判断"这一段算不算标上了"。
     *
     * 一条高亮可能给出**多个**矩形（横跨折行处的段落就是两个），
     * 所以这里用 flatMap 而不是一对一映射。
     */
    val noteHighlightRects = remember(layout, highlightBands) {
        val l = layout
        if (l == null) {
            emptyList()
        } else {
            highlightBands.flatMap { band ->
                l.rectsOfCharRange(band.range.first, band.range.last + 1)
                    .map { HighlightRect(it, band.colorArgb) }
            }
        }
    }

    // 查询变化 → 重新搜索。扫一遍 800 万字的书是几十毫秒，放在主线程上会掉帧
    // （而此刻用户正在打字），所以放到默认调度器。连续输入时 LaunchedEffect
    // 会取消上一次，只有最后一次的结果落地。
    //
    // 它写在这里（而不是搜索状态那一段）是因为要用上面刚刚解析好的 `typedRows`：
    // 搜索范围是"正文 + 我写的批注"，而批注要**按当前排版解析过**才能给出可跳的位置。
    //
    // 依赖里刻意**没有**批注清单：它每次重解析都会换一个对象（写一条边注、换一次字号都会），
    // 而这条效果末尾会跳到默认那一条 —— 混在一起的话，用户每写一条边注都会被拽走一次。
    // 代价只是"刚写下的那条边注要等下一次改查询才会出现在清单里"，而写边注必须先关掉
    // 这个面板（它是模态的），所以那条路本来就走不通。
    LaunchedEffect(searchQuery, session) {
        val result = withContext(Dispatchers.Default) {
            ReaderSearch.search(session.normalized.text, typedRows, searchQuery)
        }
        searchResult = result
        searchIndex = result.defaultIndex.coerceAtLeast(0)
        result.hits.getOrNull(result.defaultIndex)?.charOffset?.let { jumpToCharOffset(it) }
    }
    /** 正在"确认位置"的笔记 id；非空时搜索面板进入重挂模式（见 [NoteWriter]）。 */
    var reanchorNote by remember(meta.id) { mutableStateOf<String?>(null) }
    /** 那条笔记当时标在哪句话上，用来预填搜索框。当时写在空白处时为 null。 */
    var reanchorText by remember(meta.id) { mutableStateOf<String?>(null) }
    /** 写入类操作（改挂 / 新建边注）的结果，显示在状态行上，几秒后自动消失。 */
    var noteMessage by remember(meta.id) { mutableStateOf<String?>(null) }
    /**
     * 刚被删掉、还能撤销的那一条（标注 id）。
     *
     * 撤销窗口**就是**状态行那句话的寿命：两者一起出现、一起消失。这样界面上
     * 只有一个地方在说"还能撤销"，不会出现"话没了、撤销还开着"这种没人看得见的后门。
     * 同一个窗口里删第二条，前一条的撤销就没了 —— 只保留最近一次，说得清也够用。
     */
    var pendingUndo by remember(meta.id) { mutableStateOf<ReaderUndo?>(null) }
    /** PNG / JSON 导出出口。结果与其它写入操作共用一行状态。 */
    val pageExporter = rememberPageExporter { noteMessage = it }

    /**
     * 导出用的全书笔记 JSON。与 Markdown 共用同一个映射（见 [NoteMarkdown]），
     * 两种格式因此在"笔记有哪些字段"上不可能给出两个答案。
     */
    suspend fun notesJson(): String {
        val snapshot = container.store.annotations.listByDoc(meta.id)
        return NoteJson.render(
            title = meta.title,
            author = meta.author,
            docId = meta.id,
            kind = meta.kind.name,
            notes = NoteMarkdown.fromAnnotations(snapshot.annotations) { snapshot.strokesOf(it).size },
            exportedAt = System.currentTimeMillis(),
        )
    }
    /**
     * 笔记的写入出口。笔迹与打字批注共用它：界面手上只有一个标注 id，
     * 它本来就不该知道这条笔记是墨迹还是文字（见 [NoteWriter]）。
     */
    val noteWriter = remember(meta.id) { NoteWriter(container, meta.id) }
    /** "加边注"对话框是否打开。 */
    var noteDialogOpen by remember(meta.id) { mutableStateOf(false) }
    /** 正在写的那条边注正文。 */
    var noteDraft by remember(meta.id) { mutableStateOf("") }
    /** 最近一次写入失败的原因。显示在对话框**里**：模态框盖住了状态行，那里写了也看不见。 */
    var noteDialogError by remember(meta.id) { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    // 排版事实的持有者：控制器在**抬笔那一刻**取它，所以必须是"最新的那一份"，
    // 而不是创建控制器时捕获的那一份（改字号后捕获旧排版会让笔迹挂到旧行上）。
    val factsHolder = remember(meta.id) { AtomicReference<LayoutFacts?>(null) }
    val inkTicks = remember(meta.id) { MutableStateFlow(0) }
    val inkSink = remember(meta.id) { RepositoryInkSink(container, meta.id) }
    val inkController = remember(meta.id) {
        InkLayerController(
            scope = scope,
            sink = inkSink,
            facts = { factsHolder.get() },
            // 控制器在 worker 线程回调，所以这里只碰线程安全的 StateFlow，
            // 真正的界面状态更新放在下面 collect 里（在主线程）。
            onChanged = { inkTicks.update { it + 1 } },
        )
    }

    LaunchedEffect(brush, inkController) { inkController.brush = brush }

    /**
     * 删掉一条打字批注，并留一个撤销窗口。
     *
     * 界面上**没有确认对话框**：删除是软删除，紧接着就能撤销（"可逆的动作不用问"），
     * 而图书馆里删一本书要问一句，因为那是硬删 + 删文件，不可逆 ——
     * 两种删除问不问，取决于能不能回来，不取决于它有多重要。
     */
    fun deleteTypedNote(annotationId: String) {
        scope.launch {
            val outcome = noteWriter.deleteTypedNote(annotationId)
            noteMessage = outcome.message
            pendingUndo = if (outcome.changed) ReaderUndo.Note(annotationId) else null
            // 库是唯一真相源：删完读回来重新解析（与落笔、写边注同一条路）。
            if (outcome.changed) inkTicks.update { it + 1 }
        }
    }

    /**
     * 橡皮擦到一笔之后：删掉并留住撤销现场。
     *
     * 与 PDF 界面同一个出口（`NoteWriter.eraseInk`），因为"擦一笔"的语义与版式无关：
     * 它删的是一条笔迹标注，而标注在库里长什么样，两种阅读界面看到的是同一张表。
     */
    fun eraseStroke(stroke: DrawableStroke) {
        scope.launch {
            val outcome = noteWriter.eraseInk(stroke.strokeId)
            noteMessage = outcome.message
            pendingUndo = outcome.erased?.let(ReaderUndo::Ink)
            if (outcome.changed) inkTicks.update { it + 1 }
        }
    }

    /** 把刚删掉 / 擦掉的那一份放回去。撤销窗口在按下它的这一刻就关掉。 */    fun undoPending() {
        val token = pendingUndo ?: return
        pendingUndo = null
        when (token) {
            is ReaderUndo.Note -> scope.launch {
                val outcome = noteWriter.restoreTypedNote(token.annotationId)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.Ink -> scope.launch {
                val outcome = noteWriter.restoreErasedInk(token.erased)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.InkMoved -> scope.launch {
                val outcome = noteWriter.undoMove(token.moved)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }

            is ReaderUndo.InkBatch -> scope.launch {
                val outcome = noteWriter.restoreErasedInkBatch(token.erased)
                noteMessage = outcome.message
                if (outcome.changed) inkTicks.update { it + 1 }
            }
        }
    }

    /**
     * 给**当前读到的那一行**画下划线 / 波浪线。
     *
     * 为什么用 `currentOffset` 而不是"上一次点击的位置"：重排格式里点屏是翻页，
     * 没有"点一行"这个手势（那是 PDF 的交互）。用户能指定的位置只有一个——
     * 他正在读的地方，也就是滚动/翻页算出来的那个字符偏移。
     * 这个偏移与"确认位置"、搜索跳转用的是同一个量，因此三处指向同一行。
     */
    fun addUnderlineToCurrentLine(wavy: Boolean) {
        val facts = inkFacts
        if (facts == null) {
            noteMessage = "排版还没准备好，稍后再试。"
            return
        }
        val line = facts.lineAtOffset(currentOffset)
        val range = line?.let { facts.lineHighlightRange(it) }
        if (range == null) {
            noteMessage = "当前这一行没有文字，没有可画线的内容。"
            return
        }
        scope.launch {
            val outcome = noteWriter.addUnderline(
                start = range.first,
                endExclusive = range.last + 1,
                facts = facts,
                wavy = wavy,
            )
            noteMessage = outcome.message
            if (outcome.added) inkTicks.update { it + 1 }
        }
    }

    // 排版一变就重建事实并重新解析一次；库有变化（落笔 / 撤销 / 重做）时再解析一次。
    LaunchedEffect(layout, inkController) {
        val current = layout ?: return@LaunchedEffect
        val refresh: suspend () -> Unit = {
            val facts = SessionLayoutFacts(session, current, docRatio = currentRatio)
            factsHolder.set(facts)
            inkFacts = facts
            val snapshot = container.store.annotations.listByDoc(meta.id)
            val resolution = withContext(Dispatchers.Default) {
                InkResolver.resolve(
                    inks = snapshot.annotations.filterIsInstance<Annotation.Ink>(),
                    strokesOf = snapshot::strokesOf,
                    brushesOf = snapshot::brushesOf,
                    facts = facts,
                )
            }
            inkStrokes = resolution.drawable
            inkCoverage = resolution.coverage
            // 列表与渲染共用**同一次**解析结果：分成两次算，两边就会给出不同的答案
            // （症状："这里说在第三段，跳过去第三段上什么都没有"）。
            inkRows = resolution.rowsForDisplay(facts)
            // 打字批注走另一条解析入口（没有笔迹的那一类），但用的是**同一个**
            // `AnchorResolver`：所以同一个状态在两类笔记里得到的是同一句话。
            typedRows = typedNoteRows(snapshot.annotations, facts)
            // 高亮的区间走另一条出口（它标的是一段字符，不是某一行），但用的是同一个
            // `AnchorResolver`、同一份 `facts`：所以"面板说有、正文里没有"不可能发生。
            highlightBands = highlightRanges(snapshot.annotations.filterIsInstance<Annotation.Highlight>(), facts)
            // 下划线与高亮同源（同一个 AnchorResolver、同一份 facts），但画法不同，
            // 所以分开解析、分层绘制（色带在字下、线在基线上）。
            inkUnderlines = underlineBands(snapshot.annotations.filterIsInstance<Annotation.Underline>(), facts)
            // 状态行与面板必须用**同一个**判据（`notDrawn`），否则会出现
            // "状态行说有 2 条画不出来、点开面板一条都没有"。
            val stuck = resolution.notDrawn
            inkNotice = when {
                inkController.lastNotice != null -> inkController.lastNotice
                stuck.any { it.state == InkState.ORPHANED } ->
                    "有 ${stuck.count { it.state == InkState.ORPHANED }} 条笔迹暂时画不出来（找不到原来的位置），可点开查看。"
                stuck.any { it.state == InkState.AMBIGUOUS } ->
                    "有 ${stuck.count { it.state == InkState.AMBIGUOUS }} 条笔迹暂时画不出来（有多个可能位置），可点开查看。"
                else -> null
            }
            inkEpoch++
        }
        refresh()
        inkTicks.collect {
            refresh()
            inkController.clearNotice()
        }
    }

    // 从笔记清单跳回原位（A3）。
    //
    // 依赖 [inkEpoch]（解析完成一轮的计数）而不是只依赖 typedRows：
    // typedRows 一开始是空的，那时跳过去等于没跳，而"没跳"与"跳不了"
    // 在界面上长得一模一样 —— 所以要等解析真的完成一轮再判断。
    //
    // 跳不了的也要说一句话：静默不动，用户会以为应用卡了。
    var jumpedNoteId by remember(meta.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(initialNoteId, inkEpoch, typedRows, inkRows) {
        val id = initialNoteId ?: return@LaunchedEffect
        if (jumpedNoteId == id) return@LaunchedEffect
        if (inkEpoch == 0) return@LaunchedEffect
        val offset = typedRows.firstOrNull { it.annotationId == id }?.charOffset
            ?: inkRows.firstOrNull { it.annotationId == id }?.charOffset
        if (offset != null) {
            jumpToCharOffset(offset)
        } else {
            noteMessage = "这条笔记在当前排版下定位不到位置（锚点可能已失效）。"
        }
        jumpedNoteId = id
    }

    // 写入结果只留一会儿：它是"刚刚发生了什么"的一句话，不是需要一直盯着看的状态。
    LaunchedEffect(noteMessage, pendingUndo) {
        if (noteMessage != null) {
            // 10 秒（M15 之前是 6 秒）：撤销是"删错了"的唯一补救，6 秒对
            // "看到提示 → 意识到删错了 → 伸手去点"来说太紧。
            delay(10_000)
            noteMessage = null
            // 撤销窗口跟着这句话一起关掉：话没了却还能撤销，用户就没有任何地方
            // 能读到"我还能撤销"（那条状态行正是他唯一的入口）。
            pendingUndo = null
        }
    }

    // ── 界面外框（M15）──────────────────────────────────────────────────────
    //
    // 三层：纸 / 悬浮顶栏 / 底部托盘。控件全部是覆盖层，正文的可用高度与它们
    // 是否可见无关 —— 理由写在 `ReaderChrome.kt` 文件头。
    val chrome = rememberReaderChromeState()
    // 滚动模式自己的滚动状态。提到这一层（而不是留在 ScrollBody 里）：
    // 音量键在滚动模式下的职责是"滚一屏"，它需要从这里摸到 scrollState。
    // 它自己持有偏移、不要求内容比视口高 —— 见 [ReaderScrollState]。
    //
    // 按书 id 分槽（M36）：换书时这个 Composable 通常会被换掉，但"通常"不是保证 ——
    // 会话缓存命中时有可能直接从一本书的 Ready 变成另一本书的 Ready，
    // 那时留下来的就是上一本的滚动偏移。读到新书却停在第 300 屏，没人解释得清。
    val scrollState = rememberReaderScrollState(meta.id)

    // 进书即开始一次退场计时（M33）：不这么做，顶栏会一直挂着（理由见 `enterReader`）。
    LaunchedEffect(meta.id) { chrome.enterReader() }

    // ── 音量键翻页（M27）───────────────────────────────────────────────────
    //
    // MainActivity 把音量键转交到 [AppContainer.readerVolumeKeyHandler]；
    /**
     * 滚一整屏。**音量键、托盘左右箭头都走这里**（M36）。
     *
     * 两处各算各的那一版里，箭头改的是 `pageIndex`（滚动界面根本不看），
     * "滚一屏"只挂在音量键上 —— 同一个动作在两个入口里一个生效一个不生效，
     * 而失效的那个看起来完全正常。
     *
     * 视口高度用正文区的实测高度（area），与 ScrollBody 的视口一致。
     * 瞬时滚动，与目录跳转同一条规则：滚一屏是"接着往下读"，不是"放一段动画"。
     */
    fun scrollByScreen(forward: Boolean) {
        val delta = if (forward) area.height else -area.height
        // scrollTo 是挂起函数，丢进作用域即刻执行（事件本来就在主线程）。
        scope.launch {
            scrollState.scrollTo((scrollState.value + delta).coerceIn(0, scrollState.maxValue))
        }
    }

    // 分页模式翻一页，滚动模式滚一整屏。lambda 里读的全是 State 委托
    // （pageIndex / layoutState / area / scrollState），注册一次即可，
    // 不随翻页重挂 —— 这正是"捕获委托而不是捕获值"要买的东西。
    val turnByKey: (Boolean) -> Unit = { forward ->
        // 与点翻页按钮同一条口径：翻页是"在读"，沉浸计时从这里重算。
        chrome.onRead()
        if (paged) {
            val pages = layoutState?.pages
            val count = pages?.size ?: 0
            if (count > 0) {
                val cur = pageIndex.coerceIn(0, count - 1)
                val next = (cur + if (forward) 1 else -1).coerceIn(0, count - 1)
                if (next != cur) {
                    showPage(next)
                    val page = pages!![next]
                    // 位置与进度跟 `PagedBody.onTurn` 完全同源，两处不能各算各的。
                    currentOffset = page.charStart
                    currentRatio = ratioOf(session, page.charStart)
                    onProgress(page.charStart, next, currentRatio)
                }
            }
        } else {
            scrollByScreen(forward)
        }
    }
    DisposableEffect(paged, chrome) {
        container.readerVolumeKeyHandler = turnByKey
        onDispose {
            // 只摘自己的回调：万一将来出现两个阅读界面同屏的布局，
            // 也不能把别人刚注册的摘掉。
            if (container.readerVolumeKeyHandler === turnByKey) {
                container.readerVolumeKeyHandler = null
            }
        }
    }

    // ── 拖动进度线翻页（M28）──────────────────────────────────────────────
    //
    // 只接**分页**模式：进度线显示的是页进度，页号映射是它的精确逆运算。
    // 滚动模式不给拖 —— 那里显示的是页进度、滚的是像素，两套坐标系硬接
    // 会出现"拖到头却跳不到底"的错位；滚动模式的翻页本来就是滚动。
    // 拖动中 [seekRatio] 非空，盖住进度显示与页码（预览）；松手才真正跳页。
    var seekRatio by remember(meta.id) { mutableStateOf<Float?>(null) }
    val seekPreviewPage = seekRatio
        ?.let { (it * pageCount).toInt().coerceIn(0, pageCount - 1) }
        ?: safePageIndex
    val onSeek: ((Float, Boolean) -> Unit)? = if (paged) remember(meta.id) {
        { ratio, finished ->
            if (finished) {
                seekRatio = null
                chrome.onRead()
                val count = layoutState?.pages?.size ?: 0
                if (count > 0) {
                    val next = (ratio * count).toInt().coerceIn(0, count - 1)
                    showPage(next)
                    // 位置与进度跟 `PagedBody.onTurn` / 音量键完全同源，三处不能各算各的。
                    val page = layoutState!!.pages[next]
                    currentOffset = page.charStart
                    currentRatio = ratioOf(session, page.charStart)
                    onProgress(page.charStart, next, currentRatio)
                }
            } else {
                seekRatio = ratio
            }
        }
    } else null
    // 这些弹层打开时压住自动隐藏：用户正在里面做选择，界面不该在这时候消失。
    val holdChrome = penEnabled || tocOpen || searchOpen || problemsOpen || typePanelOpen ||
        noteDialogOpen

    /**
     * 点正文中间：切换顶栏与托盘（M31）。**书写时不切换**（M32）。
     *
     * 记笔迹的时候，手指落在正文上只有一个意思：这是笔（或误触）。此前那一下
     * 会顺手把顶栏和托盘收起来/放出来 —— 滚动模式下尤其明显，因为滚动本身不
     * 消费触摸，点击会一路冒到界面层。写两个字工具栏跳一下，谁也没法专心。
     *
     * 书写时控件本来就由 `holdChrome` 钉在屏幕上，不需要"点出来"；
     * 想收起控件就先退出书写模式 —— 这是同一个旋钮的两个方向，不该有两个入口。
     */
    val toggleChromeByTap: () -> Unit = { if (!penEnabled) chrome.onTap() }

    // 导出 / 分享时"这一版"是什么：分页模式是一**页**，滚动模式是一**屏**（M36）。
    //
    // 两个入口共用这一份判断。各写一次的话，总有一天会出现"文件名写第 3 屏、
    // 提示说已导出这一页"—— 而用户对着两个说法只会以为其中一个错了。
    val exportUnit = if (paged) PageUnit.PAGE else PageUnit.SCREEN
    val exportLabel: String? = if (paged) {
        if (pageCount == 0) null else "第${safePageIndex + 1}页"
    } else {
        // 滚动模式没有页号，用"第几屏"（偏移 ÷ 视口高）。连续导出两张时
        // 文件名不会撞在一起，也不会凭空编一个页码出来。
        "第${scrollScreenIndex(scrollState.value, area.height)}屏"
    }

    ReadingScaffold(
        chrome = chrome,
        paper = theme.paper,
        hold = holdChrome,
        // 只有书写时才展开笔刷面板。弹层（排版 / 目录 / 搜索）只压住自动隐藏，
        // 不展开托盘 —— 否则笔刷面板会躲在系统弹层后面出现，谁也不需要它。
        expand = penEnabled && brushPanelOpen,
        topBar = {
            ReaderFloatingTopBar(
                title = meta.title,
                onBack = {
                    onFlush(currentOffset, safePageIndex, currentRatio)
                    onClose()
                },
            )
        },
        // 提示条：界面上只有一个地方说"刚刚发生了什么"。
        //
        // 旧版让它常驻 20 dp —— 什么都不说的时候也占着。现在它只在有话说的时候出现，
        // 而且和托盘同处一层：笔刷面板展开时它被顶到面板**上面**，
        // 否则"擦掉一笔之后点提示条撤销"会被面板挡掉（设备测试抓到的）。
        notice = {
            val statusText = when {
                pendingUndo != null -> noteMessage ?: "已删除（点这里撤销）。"
                else -> noteMessage ?: inkNotice
            }
            AnimatedVisibility(
                visible = statusText != null,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val undoable = pendingUndo != null
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = undoable || noteMessage != null) {
                            if (undoable) undoPending() else noteMessage = null
                        }
                        // TalkBack 要能听出"现在可以撤销"，否则它看起来只是一句状态文字。
                        // 语义挂在 Surface 上（不是一个内层 Text），与 M13 那次设备测试
                        // 抓到的形状一致：挂在内层 Text 上在真机上不生效。
                        .semantics {
                            if (undoable) {
                                role = Role.Button
                                contentDescription = "${statusText.orEmpty()} 点按撤销"
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        text = statusText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        },
        tray = {
            ReaderTray(
                level = chrome.level,
                // 进度线：分页模式用页号比例（页码是精确的），滚动模式用**实测**的
                // 滚动比例。滚动模式没有页号，`safePageIndex` 停在进书那一页 ——
                // 拿它算进度，滚完整章进度线纹丝不动（M36 之前就是这样）。
                progress = seekRatio ?: if (paged) {
                    if (pageCount > 0) (safePageIndex + 1f) / pageCount else null
                } else {
                    currentRatio
                },
                onSeek = onSeek,
                ticks = remember(typedRows, inkRows, session) {
                    buildReflowableTicks(typedRows, inkRows, session)
                },
                onJumpToTick = { tick ->
                    // 刻度跳转到"那条笔记"：用的是它自己的字符偏移，
                    // 不是百分比 —— 百分比在重排后会指错（§6.3.1 规则 3）。
                    val offset = typedRows.firstOrNull { it.annotationId == tick.annotationId }
                        ?.charOffset
                        ?: inkRows.firstOrNull { it.annotationId == tick.annotationId }?.charOffset
                    if (offset != null) {
                        chrome.onRead()
                        jumpToCharOffset(offset)
                    }
                },
                panel = {
                    InkBrushPanel(
                        brush = brush,
                        eraserEnabled = eraserEnabled,
                        lassoEnabled = lassoEnabled,
                        onBrushChange = { brush = it },
                        onEraserChange = { eraserEnabled = it },
                        onLassoChange = { on ->
                            lassoEnabled = on
                            if (!on) selectedInkIds = emptySet()
                        },
                        selectedCount = selectedInkIds.size,
                    )
                },
            ) {
                TrayTurnButton(
                    forward = false,
                    // 书写时**可以**翻页了（M25）。理由同 PDF 界面：
                    // 要防的是误触，不是翻页本身。笔的触摸被 View 消费，
                    // 手指要落在左右两侧才翻页，中间照常写字。
                    //
                    // 滚动模式下这两个箭头改成"滚一屏"（M36）：它们此前改的是
                    // `pageIndex`，而滚动界面根本不看页号 —— 按下去什么都不会发生，
                    // 一处看得见却按不动的控件比没有更让人迷惑。
                    enabled = if (paged) safePageIndex > 0 else scrollState.canScrollBackward,
                    onClick = {
                        chrome.onRead()
                        if (paged) {
                            if (safePageIndex > 0) showPage(safePageIndex - 1)
                        } else {
                            scrollByScreen(forward = false)
                        }
                    },
                )
                Text(
                    text = trayPositionLabel(
                        paged = paged,
                        pageIndex = seekPreviewPage,
                        pageCount = pageCount,
                        ratio = currentRatio,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                TrayTurnButton(
                    forward = true,
                    enabled = if (paged) {
                        safePageIndex < pageCount - 1
                    } else {
                        scrollState.canScrollForward
                    },
                    onClick = {
                        chrome.onRead()
                        if (paged) {
                            if (safePageIndex < pageCount - 1) showPage(safePageIndex + 1)
                        } else {
                            scrollByScreen(forward = true)
                        }
                    },
                )
                Spacer(Modifier.weight(1f))

                // 托盘右侧只放"读的时候手边要有"的动作。原来它们在顶栏，
                // 单手拿手机时拇指够不到 —— 这是这次改版最直接的理由。
                if (!penEnabled) {
                    IconButton(onClick = {
                        // 「高亮这一行」标的不是"读到的位置"而是**当前这一行的字**：
                        // 没有对话框、没有第二下确认 —— 它足够便宜，也就该足够快。
                        // 拒绝的原因（这一行没字）显示在提示条上，不弹窗。
                        val facts = inkFacts
                        val line = if (facts == null) null else facts.lineAtOffset(currentOffset)
                        val range = if (facts == null || line == null) null else facts.lineHighlightRange(line)
                        if (facts == null || range == null) {
                            noteMessage = "当前这一行没有文字，没有可标的内容。"
                            return@IconButton
                        }
                        scope.launch {
                            val outcome = noteWriter.addHighlight(range.first, range.last + 1, facts)
                            noteMessage = outcome.message
                            // 库是唯一真相源：写完读回来重新解析（与落笔、写边注同一条路）。
                            if (outcome.added) inkTicks.update { it + 1 }
                        }
                    }) {
                        Icon(InkIcons.Highlight, contentDescription = "高亮当前这一行")
                    }
                }
                if (inkRows.isNotEmpty() || typedRows.isNotEmpty()) {
                    // 图标仍按"有没有问题"分（有问题=警告，没问题=信息），
                    // 因为这两件事对用户的意义完全不同：一个是"去看一眼"，
                    // 另一个是"有事要处理"。
                    IconButton(onClick = { problemsOpen = true }) {
                        if (problemCount > 0) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "本页笔记：有 $problemCount 条暂时对不上正文",
                            )
                        } else {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "本页笔记（${inkRows.size + typedRows.size} 条）",
                            )
                        }
                    }
                }
                if (penEnabled) {
                    IconButton(onClick = { inkController.undo() }, enabled = inkController.canUndo) {
                        Icon(InkIcons.Undo, contentDescription = "撤销上一笔")
                    }
                    IconButton(onClick = { inkController.redo() }, enabled = inkController.canRedo) {
                        Icon(InkIcons.Redo, contentDescription = "重做")
                    }
                }
                IconButton(onClick = {
                    penEnabled = !penEnabled
                    // 退出书写时一律退出橡皮：橡皮是书写里的一个工具，
                    // 留着它等于"下次一进来就是擦除状态"。
                    eraserEnabled = false
                    // 面板收起：进书写只高亮按钮，屏幕留给字。
                    brushPanelOpen = false
                    if (penEnabled) {
                        noteMessage = "已在书写：笔写字，手指点左右两侧翻页。"
                    }
                }) {
                    Icon(
                        InkIcons.Brush,
                        contentDescription = if (penEnabled) "退出书写" else "书写",
                        tint = if (penEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            androidx.compose.material3.LocalContentColor.current
                        },
                    )
                }
                if (penEnabled) {
                    // 只在书写时出现：读的时候没有"笔的属性"这回事。
                    // 图标画的是**点了会发生什么**（展开→收起），不是当前状态。
                    IconButton(onClick = { brushPanelOpen = !brushPanelOpen }) {
                        Icon(
                            imageVector = if (brushPanelOpen) {
                                Icons.Default.KeyboardArrowDown
                            } else {
                                InkIcons.Tune
                            },
                            contentDescription = if (brushPanelOpen) {
                                "收起笔画设置"
                            } else {
                                "笔画设置（笔形、墨色、粗细）"
                            },
                            tint = if (brushPanelOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.material3.LocalContentColor.current
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全文搜索") },
                            onClick = {
                                overflowOpen = false
                                searchOpen = true
                            },
                        )
                        if (toc.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("目录") },
                                onClick = {
                                    overflowOpen = false
                                    tocOpen = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("加边注（挂在当前读到的那一行）") },
                            onClick = {
                                overflowOpen = false
                                // 打开时先清空上一次的草稿与报错：留着它们，用户会以为
                                // 那条边注没写成功、或者这次的问题还是上一次那个。
                                noteDraft = ""
                                noteDialogError = null
                                noteDialogOpen = true
                            },
                        )
                        // ── 下划线 / 波浪线 / 书签（M13）──────────────────────
                        // 重排格式没有"点一行"这个手势（点屏是翻页），所以画线的对象
                        // 只能是**当前读到的那一行**。这也是它该放在菜单里的原因：
                        // 它作用于"我正在读的地方"，而不是"我刚才点了哪里"。
                        DropdownMenuItem(
                            text = { Text("给这一行画下划线") },
                            onClick = {
                                overflowOpen = false
                                addUnderlineToCurrentLine(wavy = false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("给这一行画波浪线") },
                            onClick = {
                                overflowOpen = false
                                addUnderlineToCurrentLine(wavy = true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("在这里放书签") },
                            onClick = {
                                overflowOpen = false
                                val facts = inkFacts
                                if (facts == null) {
                                    noteMessage = "排版还没准备好，稍后再试。"
                                } else {
                                    scope.launch {
                                        val outcome = noteWriter.addBookmark(
                                            charOffset = currentOffset,
                                            facts = facts,
                                        )
                                        noteMessage = outcome.message
                                        if (outcome.added) inkTicks.update { it + 1 }
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (paged) "导出本页 PNG" else "导出这一屏 PNG") },
                            onClick = {
                                overflowOpen = false
                                pageExporter.png(
                                    bitmap = pageView?.renderToBitmap(
                                        // 导出用的底色必须与屏幕上那张纸一致，
                                        // 否则"导出的图和我看到的不一样"。
                                        backgroundArgb = theme.paperArgb,
                                    ),
                                    title = meta.title,
                                    pageLabel = exportLabel,
                                    unit = exportUnit,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (paged) "分享这一页" else "分享这一屏") },
                            onClick = {
                                overflowOpen = false
                                // 与"导出本页 PNG"是同一张图、同一段绘制代码，
                                // 区别只在去处：导出是存到某个目录，分享是交给某个 App
                                // （相册 / 第三方 OCR）。手写检索的 L2 要的是后者 ——
                                // 那些 App 里很多不会注册成 SAF 的文档提供者，走导出选不到它们。
                                pageExporter.share(
                                    bitmap = pageView?.renderToBitmap(backgroundArgb = theme.paperArgb),
                                    title = meta.title,
                                    pageLabel = exportLabel,
                                    unit = exportUnit,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出笔记 JSON") },
                            onClick = {
                                overflowOpen = false
                                scope.launch { pageExporter.json(notesJson(), meta.title) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("笔记（整篇）") },
                            onClick = {
                                overflowOpen = false
                                onOpenNotes()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("排版（主题 / 字号 / 行距 / 边距）") },
                            onClick = {
                                overflowOpen = false
                                // 打开时把草稿对齐当前设置：上一次调完没保存的中间值不该留到这里。
                                typeDraft = settings
                                typePanelOpen = true
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { area = it },
        ) {
    /**
     * 套索的宿主对象（S1）。
     *
     * 定义在**用到它的地方之前、所有依赖之后**：命中判定要当前解析出来的
     * `inkStrokes`，落库要 `noteWriter` 与 `inkFacts`，而这些都在下面才声明。
     */
    fun lassoHost() = LassoHost(
        enabled = lassoEnabled,
        selection = selectedInkIds,
        onLasso = { polygon ->
            val hit = StrokeHitTest.selectByLasso(inkStrokes, polygon)
            selectedInkIds = hit.map { it.strokeId }.toSet()
            // 圈空了也要说一句：静默的"什么都没选"会被当成套索坏了。
            noteMessage = if (hit.isEmpty()) {
                "这个圈没有圈住任何笔迹。"
            } else {
                "已选中 ${hit.size} 笔，拖动它就能挪位置。"
            }
        },
        onMove = { dx, dy ->
            val facts = inkFacts
            val picked = inkStrokes.filter { it.strokeId in selectedInkIds }
            if (facts == null || picked.isEmpty()) {
                noteMessage = "还没有选中笔迹。"
            } else {
                scope.launch {
                    val outcome = noteWriter.moveInk(picked, dx, dy, facts)
                    noteMessage = outcome.message
                    pendingUndo = outcome.moved?.let(ReaderUndo::InkMoved)
                    // 无论成没成都收掉选择：挪完之后落点已经变了，旧的选择不再有意义；
                    // 没挪成的话更要收 —— 否则预览会一直停在"挪过去之后"的位置，
                    // 与库里没动的墨同时显示在屏幕上。
                    selectedInkIds = emptySet()
                    if (outcome.changed) inkTicks.update { it + 1 }
                }
            }
        },
    )

            when {
                layout == null || pages.isEmpty() -> CenteredMessage(
                    if (layout == null) "正在排版…" else "这篇文档没有可显示的正文。",
                    showSpinner = layout == null,
                )

                paged -> PagedBody(
                    layout = layout,
                    pageIndex = safePageIndex,
                    lasso = lassoHost(),
                    paperArgb = theme.paperArgb,
                    penEnabled = penEnabled,
                    eraserEnabled = eraserEnabled,
                    stylusOnly = settings.stylusOnly,
                    pageTurnZones = settings.pageTurnZones,
                    inkUnbuffered = settings.inkUnbuffered,
                    inkStaticLayer = settings.inkStaticLayer,
                    inkStrokes = inkStrokes,
                    inkCoverage = inkCoverage,
                    inkUnderlines = inkUnderlines,
                    inkController = inkController,
                    searchRects = searchRects,
                    noteHighlightRects = noteHighlightRects,
                    onErase = ::eraseStroke,
                    onEraseMiss = { noteMessage = "这里没有笔迹，没有擦掉任何东西。" },
                    onViewReady = { pageView = it },
                    onCenterTap = toggleChromeByTap,
                    turnEffect = settings.turnEffect,
                    pendingTurnBitmap = turnBitmap,
                    onTurnBitmapConsumed = { turnBitmap = null },
                    onTurn = { delta ->
                        // 翻页是"在读"的唯一明确信号，隐藏计时从这里开始。
                        chrome.onRead()
                        val next = (safePageIndex + delta).coerceIn(0, pageCount - 1)
                        showPage(next)
                        val page = pages[next]
                        currentOffset = page.charStart
                        currentRatio = ratioOf(session, page.charStart)
                        onProgress(page.charStart, next, currentRatio)
                    },
                )

                else -> ScrollBody(
                    layout = layout,
                    scrollState = scrollState,
                    lasso = lassoHost(),
                    paperArgb = theme.paperArgb,
                    penEnabled = penEnabled,
                    eraserEnabled = eraserEnabled,
                    stylusOnly = settings.stylusOnly,
                    pageTurnZones = settings.pageTurnZones,
                    inkUnbuffered = settings.inkUnbuffered,
                    inkStaticLayer = settings.inkStaticLayer,
                    inkStrokes = inkStrokes,
                    inkCoverage = inkCoverage,
                    inkUnderlines = inkUnderlines,
                    inkController = inkController,
                    searchRects = searchRects,
                    noteHighlightRects = noteHighlightRects,
                    onErase = ::eraseStroke,
                    onEraseMiss = { noteMessage = "这里没有笔迹，没有擦掉任何东西。" },
                    onViewReady = { pageView = it },
                    jump = scrollJump,
                    onJumpConsumed = { scrollJump = null },
                    onCenterTap = toggleChromeByTap,
                    onProgress = { offset, ratio ->
                        currentOffset = offset
                        currentRatio = ratio
                        onProgress(offset, 0, ratio)
                    },
                )
            }
        }
    }

    if (tocOpen) {
        ModalBottomSheet(
            onDismissRequest = { tocOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = "目录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(toc, key = { it.id }) { item ->
                    // 按层级缩进：nav / ncx 的目录是嵌套的，不缩进的话
                    // "第一章 / 第一节 / 第二节"会挤成一样密的一列，读不出结构。
                    val indent = 16.dp * ((item.level - 1).coerceIn(0, 3))
                    Text(
                        text = item.title,
                        style = if (item.level == 1) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                jumpToCharOffset(item.charOffset)
                                tocOpen = false
                            }
                            .padding(start = 24.dp + indent, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }

    // 排版面板（M8）。
    //
    // 为什么放在阅读界面而不是只留在设置页：字号 / 行距 / 边距是"读的时候才想调"的东西，
    // 调完必须立刻看到这一页变成什么样。设置页离得太远，用户只能靠想象。
    if (typePanelOpen) {
        val draft = typeDraft ?: settings
        val scope = rememberCoroutineScope()
        val draftTextPx = with(density) { draft.fontSizeSp.sp.toPx() }.coerceAtLeast(1f)
        val preview = if (area.width > 0 && area.height > 0) {
            PageGeometry.solve(
                widthPx = area.width,
                heightPx = area.height,
                userMarginPx = Units.mmToPx(draft.pageMarginMm, densityDpi),
                textSizePx = draftTextPx,
                density = density.density,
            )
        } else {
            null
        }
        val charsPerLine = preview?.let { (it.contentWidthPx / draftTextPx).roundToInt() }
        val widthRatio = preview?.let { it.contentWidthPx.toFloat() / area.width }
        fun commitDraft() {
            val next = typeDraft ?: return
            scope.launch { container.updateSettings { next } }
        }

        ModalBottomSheet(
            onDismissRequest = {
                typePanelOpen = false
                typeDraft = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 纸墨色卡进来之后这块内容变高了：小屏手机上可能超出面板高度，
                    // 而 ModalBottomSheet 不会替你滚 —— 超出的部分会被直接裁掉，
                    // 表现为"最下面那个滑杆/按钮不见了"。
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("排版", style = MaterialTheme.typography.titleMedium)
                Text(
                    "正文自动铺满可用宽度：一行至少 ${PageGeometry.MIN_CONTENT_EM.roundToInt()} 个字" +
                        "（窄屏上边距会让位给正文），最多 ${PageGeometry.MAX_LINE_EM.roundToInt()} 个字" +
                        "（大屏上多出来的宽度变成左右留白）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (charsPerLine != null && widthRatio != null) {
                    Text(
                        "当前：一行约 $charsPerLine 个字，正文占屏宽 ${(widthRatio * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LabeledSlider(
                    label = "字号 ${draft.fontSizeSp.roundToInt()} sp",
                    value = draft.fontSizeSp,
                    range = 12f..32f,
                    onValueChange = { typeDraft = draft.copy(fontSizeSp = it) },
                    onValueChangeFinished = { commitDraft() },
                    name = "字号",
                )
                LabeledSlider(
                    label = "行距 ${"%.2f".format(draft.lineSpacing)} 倍",
                    value = draft.lineSpacing,
                    range = 1.0f..2.5f,
                    onValueChange = { typeDraft = draft.copy(lineSpacing = it) },
                    onValueChangeFinished = { commitDraft() },
                    name = "行距",
                )
                LabeledSlider(
                    label = "页边距 ${"%.0f".format(draft.pageMarginMm)} mm（留白上限）",
                    value = draft.pageMarginMm,
                    range = 0f..40f,
                    onValueChange = { typeDraft = draft.copy(pageMarginMm = it) },
                    onValueChangeFinished = { commitDraft() },
                    name = "页边距",
                )
                // 段间距（M33）：0 = 段与段紧贴，1 = 空出一整行。
                // 它只影响**段落之间**，不加在段内的硬换行上 —— 见 DocumentTheme 的注释。
                LabeledSlider(
                    label = "段间距 ${"%.2f".format(draft.paragraphSpacingEm)} 字",
                    value = draft.paragraphSpacingEm,
                    range = 0f..1f,
                    onValueChange = { typeDraft = draft.copy(paragraphSpacingEm = it) },
                    onValueChangeFinished = { commitDraft() },
                    name = "段间距",
                )
                Spacer(Modifier.height(6.dp))
                Text("主题（纸色 + 墨色）", style = MaterialTheme.typography.titleSmall)
                ThemeSwatchRow(
                    selected = draft.readerTheme,
                    // 主题与三个滑杆不同：它不需要"松手才写库"那么重的理由
                    // （重排一次就够，不会边拖边排），而用户点一下就想立刻看到
                    // 这一页换纸。所以这里直接写库，同时把草稿对齐，避免
                    // "面板里显示旧主题、书里已经是新主题"。
                    onSelect = { picked ->
                        typeDraft = draft.copy(readerTheme = picked)
                        scope.launch {
                            container.updateSettings { it.copy(readerTheme = picked) }
                        }
                    },
                    compact = true,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val defaults = ReaderSettings()
                            typeDraft = draft.copy(
                                fontSizeSp = defaults.fontSizeSp,
                                lineSpacing = defaults.lineSpacing,
                                pageMarginMm = defaults.pageMarginMm,
                                paragraphSpacingEm = defaults.paragraphSpacingEm,
                                // 主题属于这一组，恢复默认就该一起回「跟随系统」。
                                readerTheme = defaults.readerTheme,
                            )
                            commitDraft()
                        },
                    ) { Text("恢复默认") }
                    Button(onClick = {
                        typePanelOpen = false
                        typeDraft = null
                    }) { Text("完成") }
                }
            }
        }
    }

    // 全文搜索面板。
    //
    // 关掉面板时把查询清空：查询一变，搜索效果就会把结果置空，
    // 高亮随之消失 —— 用一条路径清理"面板状态 + 高亮 + 结果"，比三处各清一次可靠。
    if (searchOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                searchOpen = false
                searchQuery = ""
                // 关掉面板就是把"确认位置"这件事放下：留着模式，下次打开搜索
                // 会莫名其妙多出一排挂接按钮。
                reanchorNote = null
                reanchorText = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    label = { Text("在正文和我的批注中搜索") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = searchStatusText(searchResult, searchIndex),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { goToSearchHit(searchIndex - 1) },
                        enabled = searchHits.size > 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一处")
                    }
                    IconButton(
                        onClick = { goToSearchHit(searchIndex + 1) },
                        enabled = searchHits.size > 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一处")
                    }
                }

                // 命中清单（M6b）。
                //
                // 原来这里只显示"当前那一处"的上下文。它的问题不是难看，而是**没法用**：
                // 用户翻到第 12 处时已经不知道自己在哪一条上了，也没法回头找刚才跳过的那条。
                // 现在把整份清单列出来，点哪一条跳哪一条。
                //
                // 清单是**一份**：正文命中与我自己写的批注命中混在同一列里按位置排。
                // 分成两个列表等于让用户搜两遍，而"我写的那句话里也出现过这个词"
                // 常常正是他要找的那一处。
                val listState = rememberLazyListState()
                LaunchedEffect(searchIndex, searchResult) {
                    if (searchResult.isEmpty) return@LaunchedEffect
                    // 只在当前那一条不在屏幕上时滚 —— 否则用户自己滑到的位置
                    // 会被每一次"下一处"拽回顶部。
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == searchIndex }) {
                        listState.animateScrollToItem(searchIndex)
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
                        items = searchResult.hits,
                        key = { _, hit -> searchHitKey(hit) },
                    ) { index, hit ->
                        // 只有正文命中能标高亮：标注命中要标的是"我写过的那句话"，
                        // 而那句话本身已经在库里了（再标一条只会多出一条看不见的高亮）。
                        val textHit = hit as? ReaderSearch.TextHit
                        SearchHitRow(
                            hit = hit,
                            text = session.normalized.text,
                            current = index == searchIndex,
                            onJump = {
                                searchIndex = index
                                hit.charOffset?.let { jumpToCharOffset(it) }
                            },
                            onHighlight = if (textHit == null) {
                                null
                            } else {
                                {
                                    // 标中的正是**这一条命中的那几个字**（不是它所在的一整行）：
                                    // 搜索刚告诉用户"找的就是这几个字"，把整行涂上等于抹掉这个信息。
                                    val facts = inkFacts
                                    if (facts == null) {
                                        noteMessage = "版面还没准备好，稍等一下再标。"
                                    } else {
                                        scope.launch {
                                            val outcome = noteWriter.addHighlight(
                                                textHit.start,
                                                textHit.endExclusive,
                                                facts,
                                            )
                                            noteMessage = outcome.message
                                            if (outcome.added) inkTicks.update { it + 1 }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                if (searchResult.isEmpty && searchResult.query.isNotEmpty()) {
                    Text(
                        text = "正文和我写下的批注里都没有「${searchResult.query}」。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                // ── "确认位置"模式（M3 遗留的最后一块）────────────────────────
                //
                // 解不开的笔记（Ambiguous / Orphaned）一个像素都不画，用户看到的就是
                // "我的笔记不见了"。这里给出的补救办法只有一条：**由用户指出它在哪一行**。
                // 位置不是算法猜的，所以不违反"绝不静默重锚"；而入口复用全文搜索 ——
                // 用户本来就要先找到那句话，搜索正是为此存在的。
                val pendingId = reanchorNote
                if (pendingId != null) {
                    // 目标行取的是**当前那一条命中**的位置。正文命中就是它命中的那个字；
                    // 边注命中的是"它挂在哪一行"（边注在正文里没有自己的字，
                    // 它的位置就是它挂的那一行）—— 两种情况都落到一个真实的字符偏移上，
                    // 所以"挂到这一处"的含义始终是"挂到这个偏移所在的行"。
                    val hitOffset = searchHit?.charOffset
                    val targetLine = remember(hitOffset, inkFacts) {
                        val facts = inkFacts
                        if (hitOffset == null || facts == null) null else facts.lineAtOffset(hitOffset)
                    }
                    val targetLabel = remember(targetLine, inkFacts) {
                        val line = targetLine
                        val facts = inkFacts
                        if (line == null || facts == null) {
                            null
                        } else {
                            val text = facts.textOf(line).trim()
                            if (text.isEmpty()) "（这一行没有文字，挂不上去）" else text.take(32)
                        }
                    }

                    Text(
                        text = "正在为一条笔记指定它该挂在哪一行：跳到它原来那句文字所在的地方，" +
                            "再点下面的按钮把它挂到那一行。" +
                            "改挂只改变它记在哪一行：手写笔迹不会按新行重新换算形状，" +
                            "而是整块挪到那一行旁边（固定在页面上写下的一动不动，除非它离那一行太远）；" +
                            "打字批注没有墨迹，换的是它指向的那一行。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (reanchorText == null) {
                        Text(
                            text = "这条笔记当时写在空白处，库里没有可搜索的原文：请自己搜索定位到那一行。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = when {
                            searchHit == null -> "还没有找到可以挂的位置。"
                            hitOffset == null -> "这一条是暂时对不上正文的边注，它没有可挂的偏移：换一处。"
                            targetLine == null -> "这一处取不到正文行。"
                            else -> "将挂到这一行：$targetLabel"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            reanchorNote = null
                            reanchorText = null
                        }) {
                            Text("取消")
                        }
                        Button(
                            enabled = hitOffset != null && targetLine != null && inkFacts != null,
                            onClick = {
                                val offset = hitOffset ?: return@Button
                                val facts = inkFacts ?: return@Button
                                scope.launch {
                                    val outcome = noteWriter.attachToCharOffset(pendingId, offset, facts)
                                    noteMessage = outcome.message
                                    if (!outcome.attached) {
                                        // 失败时**不关面板**：用户多半是要换个位置再挂一次，
                                        // 关掉等于让他从头点一遍（原因显示在状态行上，面板不会盖住它）。
                                        return@launch
                                    }
                                    reanchorNote = null
                                    reanchorText = null
                                    searchOpen = false
                                    searchQuery = ""
                                    // 库是唯一真相源：写完再读回来重新解析（同一次落笔的路径）。
                                    inkTicks.update { it + 1 }
                                }
                            },
                        ) {
                            Text("挂到这一处")
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 加边注：v1 里唯一需要"输入文字"的入口。
    //
    // 它必须当场回答两个问题，否则用户会在写完之后才发现挂不上去：
    //   1. **会挂到哪一行** —— 把那一行的文字直接显示出来（`charOffset` 来自当前阅读位置）；
    //   2. **能不能挂** —— 空白行当场说明白（判据与写入用的是同一段代码，
    //      见 `NoteWriter.addMarginNote`；界面这里只是一种更早的提示，
    //      而不是第二份判定）。
    if (noteDialogOpen) {
        val dialogFacts = inkFacts
        val targetLine = remember(dialogFacts, currentOffset) {
            if (dialogFacts == null) null else dialogFacts.lineAtOffset(currentOffset)
        }
        val targetText = remember(targetLine, dialogFacts) {
            val line = targetLine
            if (line == null || dialogFacts == null) null else dialogFacts.textOf(line).trim()
        }
        AlertDialog(
            onDismissRequest = { noteDialogOpen = false },
            title = { Text("加边注") },
            text = {
                Column {
                    Text(
                        text = when {
                            dialogFacts == null -> "版面还没准备好，稍等一下再试。"
                            targetLine == null -> "这里没有可挂的正文行（当前排版里一行都取不到位置）。"
                            targetText.isNullOrEmpty() -> "当前这一行没有文字，挂不上去：换一行再写。"
                            else -> "会挂在当前读到的那一行：$targetText"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("边注") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        minLines = 2,
                    )
                    // 失败的话必须显示在**对话框里**：它是模态的，状态行被它盖住了，
                    // 用户看不到（而失败时对话框刻意不关，他要换个位置再试）。
                    noteDialogError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = noteDraft.isNotBlank() &&
                        dialogFacts != null &&
                        targetLine != null &&
                        !targetText.isNullOrEmpty(),
                    onClick = {
                        val facts = dialogFacts ?: return@TextButton
                        scope.launch {
                            val outcome = noteWriter.addMarginNote(noteDraft, currentOffset, facts)
                            noteMessage = outcome.message
                            if (!outcome.added) {
                                noteDialogError = outcome.message
                                return@launch
                            }
                            noteDialogOpen = false
                            noteDraft = ""
                            // 库是唯一真相源：写完再读回来重新解析（与落笔同一条路）。
                            inkTicks.update { it + 1 }
                        }
                    },
                ) {
                    Text("写下来")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogOpen = false }) {
                    Text("取消")
                }
            },
        )
    }
    // 笔记状态面板：本页的笔记清单 —— 画不出来的（讲清为什么）、已经挂在页面上的
    // （可以改挂），以及打字批注（边注）。
    // 状态来自当前排版、与渲染共用同一次解析结果，所以列表里说的位置
    // 就是"跳到原文"能跳到的位置。
    if (problemsOpen) {
        ModalBottomSheet(
            onDismissRequest = { problemsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = if (problemCount == 0) {
                    "本页笔记"
                } else {
                    "本页笔记：有 $problemCount 条暂时对不上正文"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp),
            )
            Text(
                text = "\"解不开就不画\"：宁可暂时看不见，也不把笔记画到（或挂到）错误的位置上。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                if (problemRows.isNotEmpty()) {
                    item { NoteSectionHeader("画不出来（${problemRows.size}）") }
                    item {
                        // 一行说明 + 一个按钮。为什么需要这个按钮：这些笔迹
                        // 在"暂时对不上"和"用户早就不要了"两种情况下长得一模一样，
                        // 而在此之前唯一能做的是一条条"确认位置"。
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                            Text(
                                "它们在当前排版下定位不到正文，所以一个像素都没画出来。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            TextButton(
                                onClick = { confirmClearProblems = true },
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text("全部清除（${problemRows.size} 条）")
                            }
                        }
                    }
                    items(problemRows, key = { it.annotationId }) { row ->
                        NoteRowItem(
                            row = row,
                            onJump = { offset ->
                                jumpToCharOffset(offset)
                                problemsOpen = false
                            },
                            onReanchor = {
                                // 重挂只用搜索面板：不引入新的手势，
                                // 也不让"点正文"这件事同时有两个含义。
                                penEnabled = false
                                reanchorNote = row.annotationId
                                reanchorText = row.originalText
                                searchQuery = row.originalText.orEmpty()
                                problemsOpen = false
                                searchOpen = true
                            },
                        )
                    }
                }
                if (settledRows.isNotEmpty()) {
                    item { NoteSectionHeader("已经挂在页面上（${settledRows.size}）") }
                    items(settledRows, key = { it.annotationId }) { row ->
                        NoteRowItem(
                            row = row,
                            onJump = { offset ->
                                jumpToCharOffset(offset)
                                problemsOpen = false
                            },
                            onReanchor = {
                                penEnabled = false
                                reanchorNote = row.annotationId
                                reanchorText = row.originalText
                                searchQuery = row.originalText.orEmpty()
                                problemsOpen = false
                                searchOpen = true
                            },
                        )
                    }
                }
                if (typedRows.isNotEmpty()) {
                    // 边注**没有**"画不出来"这一组：它不需要被画出来，它需要的是
                    // 一个能被看见的地方（就是这里）。所以它只按类型占一组，
                    // 位置状态在行内如实说明（说不清位置的会带上「确认位置」按钮）。
                    // 组名用"打字批注"而不是"边注"：这一组将来还会装高亮，
                    // 而每行自己会说清它是哪一种（见 `TypedNoteRowItem`）。
                    item { NoteSectionHeader("打字批注（${typedRows.size}）") }
                    items(typedRows, key = { it.annotationId }) { row ->
                        TypedNoteRowItem(
                            row = row,
                            onJump = { offset ->
                                jumpToCharOffset(offset)
                                problemsOpen = false
                            },
                            onReanchor = {
                                // 与笔迹同一条路：搜索 → 挂到这一处。
                                penEnabled = false
                                reanchorNote = row.annotationId
                                reanchorText = row.originalText
                                searchQuery = row.originalText.orEmpty()
                                problemsOpen = false
                                searchOpen = true
                            },
                            // 面板不关：连删两条是常事，每删一条都要重新点开一次面板
                            // 才是真的麻烦。
                            onDelete = { deleteTypedNote(row.annotationId) },
                        )
                    }
                }
                item {
                    // 说清"现在能做什么、还不能做什么"。少一个按钮，比让用户找半天强。
                    Text(
                        text = "确认位置 / 改挂：点下去后用搜索找到那句话，再把它挂到那一行上 —— " +
                            "位置由你指出，系统不猜。" +
                            "画不出来的笔记一个像素都不会被画出来（宁可暂时看不见，也不画到错误的位置上）；" +
                            "手写笔迹用「改挂」换行：跟随文字的会跟着新行落位，" +
                            "固定在页面上写下的墨迹会整块挪到新那一行旁边（形状与笔宽不变）；" +
                            "边注只换它指向的那一行（它没有墨迹，也没有「画不出来」这一说）。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                item {
                    Text(
                        text = "删除：打字批注可以逐条删掉，删完顶上的状态行会留一句「点这里撤销」" +
                            "（几秒内有效）—— 删除是软删除，撤销会把它原样放回来。" +
                            "画不出来的手写笔迹可以一次性全部清除（上面那个按钮）：" +
                            "它会**真的删掉**墨迹数据，撤销窗口之外找不回来，" +
                            "所以只对「已经定位不到正文」的笔迹开放 —— " +
                            "还能画出来的笔迹仍然用橡皮或撤销 / 重做处理。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }

    if (confirmClearProblems) {
        val count = problemRows.size
        AlertDialog(
            onDismissRequest = { confirmClearProblems = false },
            title = { Text("清除这 $count 条笔迹？") },
            text = {
                Text(
                    "它们在当前排版下定位不到正文，所以一直没被画出来。\n\n" +
                        "清除会真的删掉这些墨迹：之后即使换回原来的字号、版式，" +
                        "它们也不会回来。几秒内可以点提示条撤销。\n\n" +
                        "如果只是暂时对不上，用「确认位置」把它们挂回去更安全。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearProblems = false
                    val ids = problemRows.map { it.annotationId }
                    scope.launch {
                        val outcome = noteWriter.eraseInkAnnotations(ids)
                        noteMessage = outcome.message
                        // 只有真的留下现场的才给撤销入口：空壳（点集丢了）
                        // 写回去也还是画不出来，给了按钮等于骗人。
                        if (outcome.erased.isNotEmpty()) {
                            pendingUndo = ReaderUndo.InkBatch(outcome.erased)
                        }
                        if (outcome.changedCount > 0) inkTicks.update { it + 1 }
                    }
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearProblems = false }) { Text("取消") }
            },
        )
    }
}

/** PDF「本页笔记」里的一行：高亮或边注。 */
@Composable
private fun PdfPageNoteRow(
    row: TypedNoteRow,
    quote: String?,
    colorArgb: Int?,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (colorArgb != null) {
                Text("■ ", color = Color(colorArgb), style = MaterialTheme.typography.titleSmall)
            }
            Text(typedKindLabel(row.kind), style = MaterialTheme.typography.titleSmall)
            Text(
                "  ${inkStateLabel(row.state)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // 用户写的字在前：来这个面板常常是为了找回自己写下的那句话。
        row.noteText?.takeIf { it.isNotBlank() }?.let { body ->
            Text(body, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
        }
        quote?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                "原文：${text.trim()}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("删除")
        }
    }
}

/** 笔记面板里的一组（"画不出来" / "已经挂在页面上" / "边注"）。 */
@Composable
private fun NoteSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 笔记列表里的一行（手写笔迹）。
 *
 * 同一个动作在不同处境下叫**不同的名字**，因为用户的意图不同：
 *
 * - 画不出来的笔记是"**确认位置**"—— 不做这笔笔记就一直看不见；
 * - 已经画在页面上的笔记是"**改挂**"—— 用户主动说"这笔该记在那一行"。
 *
 * 用词由 [InkNoteRow.drawn] 决定，而"能不能点"由 [InkNoteRow.canReanchor] 决定
 * （判据是库里有没有笔迹可挂）。界面不自己算"它算不算问题" ——
 * 两侧各评一遍正是本工程吃过两次亏的错误模式。
 */
@Composable
private fun NoteRowItem(
    row: InkNoteRow,
    onJump: (Int) -> Unit,
    onReanchor: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(inkStateLabel(row.state), style = MaterialTheme.typography.titleSmall)
        Text(row.reason, style = MaterialTheme.typography.bodyMedium)
        // 解析到的那一行原文优先；解不开的笔记退到"当时标在哪句话上"——
        // 后者是库里存的，正是用户确认位置时要找的那句话。
        (row.excerpt ?: row.originalText)?.let { excerpt ->
            Text("原文：$excerpt", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Text(
            text = "这类笔迹：${semanticsLabel(row.semantics)}",
            style = MaterialTheme.typography.bodySmall,
        )
        NoteActions(
            charOffset = row.charOffset,
            reanchorLabel = if (row.drawn) "改挂" else "确认位置",
            canReanchor = row.canReanchor,
            onJump = onJump,
            onReanchor = onReanchor,
        )
    }
}

/**
 * 笔记列表里的一行（打字批注 / 边注）。
 *
 * 与 [NoteRowItem] 分开写而不是塞进同一个 composable：这一行要显示的是**用户写的那句话**
 * （手写笔迹没有正文可显示），而它没有"这类笔迹：跟随文字"这样的语义可讲
 * （打字批注是离散的，没有重排语义）。
 *
 * 选词的口径与笔迹一致，只是判据换成了"说不说得清它在哪一行"
 * （[TypedNoteRow.located]，它是 [InkNoteRow.drawn] 在打字批注上的对应物）：
 * 说不清 → 「确认位置」，说得清 → 「改挂」。而"能不能点"由 [TypedNoteRow.canReanchor]
 * 决定（对打字批注恒真：链就是它的全部几何）。
 */
@Composable
private fun TypedNoteRowItem(
    row: TypedNoteRow,
    onJump: (Int) -> Unit,
    onReanchor: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(inkStateLabel(row.state), style = MaterialTheme.typography.titleSmall)
        // 正文在前：用户来这个面板常常是为了找自己写过的那句话，而不是为了看状态。
        row.noteText?.let { body ->
            Text(body, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
        }
        Text(row.reason, style = MaterialTheme.typography.bodyMedium)
        (row.excerpt ?: row.originalText)?.let { excerpt ->
            Text("原文：$excerpt", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Text(
            text = "这类批注：${typedKindLabel(row.kind)}",
            style = MaterialTheme.typography.bodySmall,
        )
        NoteActions(
            charOffset = row.charOffset,
            reanchorLabel = if (row.located) "改挂" else "确认位置",
            canReanchor = row.canReanchor,
            onJump = onJump,
            onReanchor = onReanchor,
            onDelete = onDelete,
        )
    }
}

/**
 * 两种笔记共用的动作行。
 *
 * 抽出来是为了让"两个按钮叫什么、能不能点"只有一处定义：笔迹与边注的选词口径
 * 必须一模一样（用户在同一个面板里看到它们），抄一遍就会漂一次。
 *
 * [onDelete] 为 null 时**不画删除按钮**：手写笔迹不在这条清单里删
 * （一笔跨行时是几段标注，按段删只会擦掉一笔的一部分 —— 整笔擦除还没做，
 * 笔迹用撤销 / 重做）。少一个按钮比给一个会做错事的按钮强。
 */
@Composable
private fun NoteActions(
    charOffset: Int?,
    reanchorLabel: String,
    canReanchor: Boolean,
    onJump: (Int) -> Unit,
    onReanchor: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (charOffset != null) {
            TextButton(onClick = { onJump(charOffset) }) {
                Text("跳到原文")
            }
        }
        if (canReanchor) {
            TextButton(onClick = onReanchor) {
                Text(reanchorLabel)
            }
        }
        if (onDelete != null) {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("删除")
            }
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
private fun searchStatusText(result: ReaderSearch.Result, index: Int): String = when {
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
private fun searchHitKey(hit: ReaderSearch.Hit): String = when (hit) {
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
private fun hitPreview(hit: ReaderSearch.Hit, text: String): AnnotatedString = when (hit) {
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

@Composable
private fun PagedBody(
    layout: ReflowableTextLayout,
    pageIndex: Int,
    /** 套索的状态与回调（S1）。两个阅读视图共用同一套行为，见 [LassoHost]。 */
    lasso: LassoHost,
    /** 当前纸色。只用来决定荧光笔的混色方向（见 `DocumentInkView.setPaper`）。 */
    paperArgb: Int,
    penEnabled: Boolean,
    eraserEnabled: Boolean,
    /** 只接受手写笔：手指事件不落笔，继续去翻页。 */
    stylusOnly: Boolean,
    /** 书写时手指落在左右两侧是否翻页（M25 的分区让渡，可从设置里关）。 */
    pageTurnZones: Boolean,
    /** 书写与套索走无缓冲派发（低延迟）。 */
    inkUnbuffered: Boolean,
    /** 静态层是否录进 RenderNode 缓存。 */
    inkStaticLayer: Boolean,
    inkStrokes: List<DrawableStroke>,
    /** 与 [inkStrokes] 同一份解析的覆盖集合（见 [InkCoverage]）。 */
    inkCoverage: InkCoverage,
    inkUnderlines: List<DrawableUnderline>,
    inkController: InkLayerController,
    searchRects: List<Box>,
    noteHighlightRects: List<HighlightRect>,
    onErase: (DrawableStroke) -> Unit,
    onEraseMiss: () -> Unit,
    onViewReady: (DocumentInkView) -> Unit,
    onTurn: (Int) -> Unit,
    /** 翻页动效（M33）。 */
    turnEffect: TurnEffect,
    /**
     * 翻页**之前**由调用方截好的旧页位图（仿真翻页用）。
     *
     * 为什么由调用方截：翻页动作有五条入口（点左右两侧、托盘箭头、音量键、拖动进度条、
     * 目录跳转），它们都在界面层改 `pageIndex`。让每个入口各自"先截图再翻页"
     * 就是把同一段代码抄五遍，而漏掉的那一条会表现为"有时有动画、有时没有"。
     * 所以收成一条规矩：**改页码之前先说一声，这里只负责把它画出来。**
     */
    pendingTurnBitmap: android.graphics.Bitmap? = null,
    /** 动画播完，位图可以释放了。 */
    onTurnBitmapConsumed: () -> Unit = {},
    /**
     * 点了正文中间（既不是左三分之一也不是右三分之一）。
     *
     * 这里是"唤出顶栏"的入口，而它只能走 `detectTapGestures` 这一条路：
     * 正文是一个 `AndroidView`，原生 View 会消费触摸事件，
     * 挂在父 `Box` 上的 `pointerInput` 在设备上收不到（M15 实测：
     * 三个沉浸用例全部卡在"顶栏没退场"上，因为唤醒事件根本没进来）。
     */
    onCenterTap: () -> Unit,
) {
    val pages = layout.pages
    val page = pages.getOrNull(pageIndex) ?: pages.first()
    val centerTap by rememberUpdatedState(onCenterTap)
    val latestTurn by rememberUpdatedState(onTurn)

    /** 正在播放的仿真翻页位图。翻页结束即释放 —— 一张全屏位图是 8 MB 起步。 */
    var curlBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var curlActive by remember { mutableStateOf(false) }

    /** 翻页进度：0 = 旧页完整，1 = 旧页已经卷走。 */
    val curlProgress = remember { Animatable(1f) }

    /** 新页淡入（120 ms）。 */
    val fadeAlpha = remember { Animatable(1f) }

    // 首帧不动效：刚打开一本书时"淡入一下"会让人以为是加载动画。
    var firstFrame by remember { mutableStateOf(true) }

    LaunchedEffect(pageIndex) {
        if (firstFrame) {
            firstFrame = false
            return@LaunchedEffect
        }
        val pending = pendingTurnBitmap
        when (turnEffect) {
            TurnEffect.NONE -> Unit

            TurnEffect.FADE -> {
                // 只淡入新页，不做交叉溶解：交叉溶解要同时持有两页的位图，
                // 在低端机上那是每翻一页分配一次全屏位图。120 ms 的淡入已经足够
                // 让眼睛跟得上"换了一张纸"。
                fadeAlpha.snapTo(0.25f)
                fadeAlpha.animateTo(1f, tween(120))
            }

            TurnEffect.CURL -> if (pending == null) {
                // 没截到位图（视图还没量好、软件画布失败）：退化成淡入，
                // 不能什么都不动 —— 那会让翻页看起来"卡了一下"。
                fadeAlpha.snapTo(0.25f)
                fadeAlpha.animateTo(1f, tween(120))
            } else {
                curlBitmap = pending
                curlActive = true
                curlProgress.snapTo(0f)
                curlProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(320, easing = LinearOutSlowInEasing),
                )
                curlActive = false
                curlBitmap = null
            }
        }
        if (pending != null) onTurnBitmapConsumed()
    }

    Box(
        Modifier
            .fillMaxSize()
            // 点左侧三分之一上一页、右侧三分之一下一页、中间唤出控件。
            // 中间刻意不做翻页热区：那里是"圈选/批注"的入口，
            // 把它也变成翻页热区会让用户永远点不到想批注的位置。
            .pointerInput(pageIndex, pages.size, penEnabled, turnEffect) {
                // 书写模式下**不屏蔽**翻页热区（M25）。
                //
                // 以前这里是 `if (penEnabled) return`，理由是"翻页与书写落在同一区域，
                // 必须二选一"—— 但真正的二选一已经下沉到 View 那一层了：
                // 笔的触摸被 DocumentInkView 消费（落笔），**根本到不了这里**，
                // 所以这里收到的只可能是手指，不会出现"正在写字时页面突然翻页"。
                //
                // 手指的左右两侧让不让给翻页，由 DocumentInkView 的翻页分区决定
                // （见 `attachPageTurnZones`）：中间照常写字，左右让给翻页。
                detectTapGestures { offset ->
                    when {
                        offset.x < size.width / 3f -> latestTurn(-1)
                        offset.x > size.width * 2f / 3f -> latestTurn(1)
                        else -> centerTap()
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // 淡入读的是动画值，写在 layer 块里而不是组合里：
                // 组合里读会每帧重组一次（翻页 120 ms 内重建 7 次整棵子树），
                // layer 块只改 alpha，一个子节点都不会重画。
                .graphicsLayer { alpha = fadeAlpha.value },
            factory = { ctx -> DocumentInkView(ctx).also(onViewReady) },
            update = { view ->
                view.submit(layout, DocumentInkView.Mode.PAGED, page.topPx)
                // 纸色只影响荧光笔的混色方向（浅纸正片叠底 / 深纸滤色）。
                view.setPaper(paperArgb)
                view.submitInk(inkStrokes, inkCoverage)
                view.submitNoteHighlights(noteHighlightRects)
                view.submitUnderlines(inkUnderlines)
                view.submitSearchHighlight(searchRects)
                // 关开关而不是摘控制器：触摸事件继续上浮给上面的翻页手势（两条路只有一条通），
                // 但暂存层还要画 —— "抬笔了、干墨还没到"的那一笔是已经写下的墨，
                // 点一下开关就让它消失，正是用户报的"圈有时候会消失"。
                // 橡皮同样要"接管触摸"，所以它与取笔互斥、但都要求 penEnabled。
                // 套索也在这里显式避开：View 里它的分支排在最前面，本来也轮不到书写，
                // 但"开关"与"顺序"两处都写上，改其中一处时另一处还在。
                view.attachInk(inkController, enabled = penEnabled && !eraserEnabled && !lasso.enabled)
                view.attachEraser(eraserEnabled)
                // 套索：圈选与拖动。与橡皮同级的模式，同样互斥（见 attachLasso）。
                view.attachLasso(lasso.enabled)
                view.setSelection(lasso.selection)
                view.onLasso = lasso.onLasso
                view.onMove = lasso.onMove
                // "谁能写"与"写什么"是两件事，分开挂：上面两个决定行为，这个决定输入来源。
                view.attachStylusOnly(stylusOnly)
                // 渲染/输入调优（M26）：两项都从设置来，值没变时 configureInk 内部不动作。
                view.configureInk(staticLayer = inkStaticLayer, unbuffered = inkUnbuffered)
                // 翻页分区：分页模式下书写时，手指落在左右各 1/3 让给翻页手势（笔不受影响）。
                // 中间照常写字 —— 分区与界面层的热区是同一个 1/3。
                // 设置里可以整体关掉：只用笔、手经常搭在屏上的人会把它当误触源。
                view.attachPageTurnZones(penEnabled && pageTurnZones)
                view.onErase = onErase
                view.onEraseMiss = onEraseMiss
            },
        )

        // 仿真翻页的覆盖层。画在正文**之上**、控件之下。
        if (turnEffect == TurnEffect.CURL && curlActive) {
            val bitmap = curlBitmap
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    drawPageCurl(image, curlProgress.value)
                }
            }
        }
    }
}

/**
 * 仿真翻页：旧页绕"书脊"（左边缘）卷过去（M33）。
 *
 * ## 为什么是"绕左边卷"而不是"从右边掀过来"
 *
 * 后者（掀起来的纸盖在左半边）在动画中间**看不到新的一页** —— 读者盯着的
 * 是纸的背面，直到动画结束新页才出现。中文书从右往左翻的概念也支持这一版：
 * 手指从右边一推，纸面整体向左边卷走，新的一页从右边开始露出来。
 *
 * ## 怎么做出"卷"
 *
 * 屏幕被切成 [CURL_BANDS] 条横带，每条带按各自的横向缩放画同一张旧页位图：
 * 中间的带缩得少、上下两端缩得多，于是页面右缘画出来是一条**弧**，
 * 而不是一条直线 —— 这就是"卷"和"平铺"的全部区别。带数取 24：
 * 再少能看见阶梯，再多对观感没有帮助（每帧 24 次 `drawImage` 是免费的）。
 *
 * ## 阴影与压暗
 *
 * - 纸面上压一层随进度加深的黑：纸被翻离光源，这层暗是"立体"的主要来源；
 * - 露出来的新页左侧投一条横向渐变阴影，让旧页看起来**高过**纸面。
 *
 * 都是近似，但近似得对：读者要的是"这一页被翻走了"，不是一个物理正确的曲面。
 */
internal fun DrawScope.drawPageCurl(image: ImageBitmap, progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return
    val w = size.width
    val h = size.height

    for (band in 0 until CURL_BANDS) {
        val y0 = h * band / CURL_BANDS
        val y1 = h * (band + 1) / CURL_BANDS
        val t = (band + 0.5f) / CURL_BANDS
        // 中间少缩、两端多缩 → 右缘是一条弧。系数乘 p：动画一开始是平的，
        // 越往后越卷 —— 起手那一下"整页一起动"比"先卷一个角"更像翻书。
        val shrink = (1f - p) * (1f - CURL_BEND * p * (t - 0.5f) * (t - 0.5f) * 4f)
        val safeShrink = shrink.coerceIn(0.01f, 1f)
        val edge = w * safeShrink

        clipRect(0f, y0, w, y1 + if (band < CURL_BANDS - 1) 1f else 0f) {
            scale(scaleX = safeShrink, scaleY = 1f, pivot = Offset.Zero) {
                drawImage(image, dstSize = IntSize(w.toInt(), h.toInt()))
            }
            // 纸被翻离光源：整体压暗，且越靠右（越接近离开）压得越重。
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.05f * p),
                    1f to Color.Black.copy(alpha = 0.42f * p),
                    startX = 0f,
                    endX = edge.coerceAtLeast(1f),
                ),
                topLeft = Offset(0f, y0),
                size = Size(edge.coerceAtLeast(1f), y1 - y0),
            )
        }
    }

    // 新页上的投影：贴着旧页的右缘，往右渐隐。
    val edgeMin = w * (1f - p) * (1f - CURL_BEND * p * 0.25f)
    if (edgeMin < w) {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.22f * p),
                1f to Color.Transparent,
                startX = edgeMin,
                endX = (edgeMin + CURL_SHADOW_PX).coerceAtMost(w),
            ),
            topLeft = Offset(edgeMin, 0f),
            size = Size(((edgeMin + CURL_SHADOW_PX).coerceAtMost(w) - edgeMin).coerceAtLeast(1f), h),
        )
    }
}

/** 仿真翻页的横带数量。见 [drawPageCurl]。 */
private const val CURL_BANDS = 24

/** 卷边弧的最大相对深度（占屏宽的比例）。 */
private const val CURL_BEND = 0.14f

/** 投影的宽度（像素）。1024 宽的手机上约 30 dp。 */
private const val CURL_SHADOW_PX = 96f

@Composable
private fun ScrollBody(
    layout: ReflowableTextLayout,
    /** 滚动状态由界面层持有：音量键"滚一屏"要从这里摸到它（M27）。 */
    scrollState: ReaderScrollState,
    /** 套索的状态与回调（S1）。见 [LassoHost]。 */
    lasso: LassoHost,
    /** 当前纸色。只用来决定荧光笔的混色方向（见 `DocumentInkView.setPaper`）。 */
    paperArgb: Int,
    penEnabled: Boolean,
    eraserEnabled: Boolean,
    /** 只接受手写笔：手指事件不落笔，继续去滚动。 */
    stylusOnly: Boolean,
    /** 书写时手指落在左右两侧是否翻页。滚动模式不用它（分区恒关），只为签名对齐。 */
    pageTurnZones: Boolean,
    /** 书写与套索走无缓冲派发（低延迟）。 */
    inkUnbuffered: Boolean,
    /** 静态层是否录进 RenderNode 缓存。 */
    inkStaticLayer: Boolean,
    inkStrokes: List<DrawableStroke>,
    /** 与 [inkStrokes] 同一份解析的覆盖集合（见 [InkCoverage]）。 */
    inkCoverage: InkCoverage,
    inkUnderlines: List<DrawableUnderline>,
    inkController: InkLayerController,
    searchRects: List<Box>,
    noteHighlightRects: List<HighlightRect>,
    onErase: (DrawableStroke) -> Unit,
    onEraseMiss: () -> Unit,
    onViewReady: (DocumentInkView) -> Unit,
    jump: ScrollJump?,
    onJumpConsumed: () -> Unit,
    /** 点正文：切换顶栏与托盘（M31）。与分页模式同一个手势、同一个语义。 */
    onCenterTap: () -> Unit,
    onProgress: (Int, Float) -> Unit,
) {
    // 目录跳转 / 恢复阅读位置：一次性滚到目标行。
    // 用瞬时 scrollTo 而不是动画：跳一整本书的距离时动画要么很慢要么很快，
    // 而"目录点下去应该直接到那一章"是用户可预期的行为。
    LaunchedEffect(jump, scrollState.maxValue) {
        val target = jump ?: return@LaunchedEffect
        // 上界还没算出来时 maxValue 是 0，此时 scrollTo 会被夹到 0（跳了等于没跳）。
        // 所以这里**不消费请求**，等 maxValue 到位（它的变化会重跑本效果）再跳。
        if (scrollState.maxValue <= 0) return@LaunchedEffect
        // `scrollYOfCharOffset` 给的已经是**文档坐标**，而 scrollState 的值也是文档
        // 坐标 —— 中间没有"加上边距"这一步。两处各算各的一直是这类偏移 bug 的来源，
        // 所以从 M35 起统一成文档坐标：View 内部那一次 `translate(marginTop - windowTop)`
        // 就是唯一的换算点。
        scrollState.scrollTo(layout.scrollYOfCharOffset(target.offset))
        onJumpConsumed()
    }
    val centerTap by rememberUpdatedState(onCenterTap)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // 拖动、惯性、边缘回弹都由 Compose 提供；这里只回答"还能滚多少"。
            // 与 `verticalScroll` 的差别是它**不需要内容比视口高** —— 正是这一点
            // 让 View 可以只报一屏高（见 ReaderScrollState 的类注释）。
            .scrollable(
                state = scrollState,
                orientation = Orientation.Vertical,
                overscrollEffect = rememberOverscrollEffect(),
            )
            // 点一下正文切换顶栏与托盘（M31）：与分页模式同一个手势、同一个语义。
            // 排在 scrollable **之后**：滚动优先，只有"按下没移动就抬起"才算点按，
            // 否则每次手指划一下松手都会顺手把控件收起来。
            .pointerInput(Unit) { detectTapGestures { centerTap() } },
        contentAlignment = Alignment.TopStart,
    ) {
        val viewportPx = constraints.maxHeight
        val marginPx = (layout.metrics.marginTopPx + layout.metrics.marginBottomPx).toInt()
        // 末尾那块与迷你托盘等高的空白（M34）现在算进可滚动范围而不是排一个 Spacer：
        // 滚到底时末行停在托盘上沿，托盘不压字；而 View 只有一屏高，
        // 这块空白不需要真的占一个布局元素。
        val trayPx = with(LocalDensity.current) { TRAY_MINI.toPx() }.toInt()
        val maxScroll = scrollMaxFor(layout.totalHeightPx + marginPx, trayPx, viewportPx)
        // 用 SideEffect 而不是 LaunchedEffect：上界必须**这一帧**就位。
        // 晚一帧的话第一帧的 maxValue 还是 0，一切都滚不动。
        SideEffect { scrollState.setMax(maxScroll) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DocumentInkView(ctx).also { v ->
                    // 滚动手势的回调栈里**同步**推给 View：走重组会晚一帧，
                    // 屏幕上就是"画面追不上手指"。
                    scrollState.onValueChange = { px -> v.setWindowTop(px) }
                    onViewReady(v)
                }
            },
            update = { view ->
                // 窗口上沿就是滚动偏移（文档坐标），View 只画这一段。
                view.submit(layout, DocumentInkView.Mode.SCROLL, scrollState.value)
                // 与 PagedBody 同一条：纸色只决定荧光笔往哪个方向混。
                view.setPaper(paperArgb)
                view.submitInk(inkStrokes, inkCoverage)
                view.submitNoteHighlights(noteHighlightRects)
                view.submitUnderlines(inkUnderlines)
                view.submitSearchHighlight(searchRects)
                // 与 PagedBody 同一条规则：控制器不摘，开关只关输入（见那里的注释）。
                view.attachInk(inkController, enabled = penEnabled && !eraserEnabled && !lasso.enabled)
                view.attachEraser(eraserEnabled)
                // 套索：圈选与拖动。与橡皮同级的模式，同样互斥（见 attachLasso）。
                view.attachLasso(lasso.enabled)
                view.setSelection(lasso.selection)
                view.onLasso = lasso.onLasso
                view.onMove = lasso.onMove
                // "谁能写"与"写什么"是两件事，分开挂：上面两个决定行为，这个决定输入来源。
                view.attachStylusOnly(stylusOnly)
                // 渲染/输入调优（M26）：同 PagedBody。
                view.configureInk(staticLayer = inkStaticLayer, unbuffered = inkUnbuffered)
                // 滚动模式不开分区：那里"翻页"本来就是滚动，
                // 把左右两侧让出去只会让滚动卡在中间。
                view.attachPageTurnZones(false)
                view.onErase = onErase
                view.onEraseMiss = onEraseMiss
            },
        )
    }
    // 滚动进度：像素比例只用于书架显示，不是定位依据（方案 §6.3.1 规则 3 明确禁止
    // 用百分比重建锚点）。**位置**则用"视口顶端那一行的第一个字符"，而不是
    // "总字数 × 像素比例" —— 后者在长短段混排时误差能有几千字，而且反复换版式
    // 会越漂越远；前者与 scrollYOfCharOffset 互为逆运算，来回切换位置不漂。
    // 比例用"可滚动范围"而不是"正文总高"来算：视图高度还含上下页边距，
    // 用总高当分母会让滚动到底时百分比还没到 100%。
    //
    // `jump != null` 时**不上报**（M36）：那一刻偏移还是初始的 0，而 0 会被当成
    // "读到了开头"写进库（去抖 800ms 后落盘）。恢复跳转哪怕只晚一帧，
    // 也已经有了一次"读到开头"在排队 —— 用户退出后重进，位置就真的回开头了。
    // 等跳转落地（`onJumpConsumed` 把 jump 置空，本效果因 key 变化重跑）再开始报。
    LaunchedEffect(scrollState.value, scrollState.maxValue, jump) {
        if (jump != null) return@LaunchedEffect
        val max = scrollState.maxValue.coerceAtLeast(1)
        val ratio = (scrollState.value.toFloat() / max).coerceIn(0f, 1f)
        onProgress(layout.charOffsetOfScrollY(scrollState.value), ratio)
    }
}

/**
 * 套索的状态与回调（S1）。
 *
 * 收成一个对象而不是给 `PagedBody` / `ScrollBody` 各加四个参数：
 * 这四个值永远一起变（开关一关，选择、回调的含义也一起变），
 * 拆成四个参数就等于允许"传了开关却传了旧的选择"这种组合。
 *
 * 回调留在**界面层**而不下沉到 View：命中判定需要当前解析出来的 `inkStrokes`，
 * 落库需要 `noteWriter` 与 `inkFacts` —— 这些 View 一样都不该认识
 * （它连 `Annotation` 都不认识，见 `DocumentInkView` 的类注释）。
 */
private class LassoHost(
    val enabled: Boolean,
    val selection: Set<String>,
    /** 圈画完了：文档坐标的闭合折线 → 选中了哪些。 */
    val onLasso: (FloatArray) -> Unit,
    /** 拖动松手了：文档坐标里的位移。 */
    val onMove: (Float, Float) -> Unit,
)

/**
 * 滚动模式的一次性定位请求。
 *
 * [seq] 只用于"让相等的偏移也是不同的请求"（见 `ReaderContent.requestScrollJump`）。
 */
private data class ScrollJump(val offset: Int, val seq: Int)

/** 状态名。与状态行、`InkResolver` 的 `reason` 用同一套口径。 */
private fun inkStateLabel(state: InkState): String = when (state) {
    InkState.RESOLVED -> "已定位"
    InkState.AMBIGUOUS -> "需确认位置"
    InkState.ORPHANED -> "找不到原来的位置"
}

private fun semanticsLabel(semantics: InkReflowSemantics): String = when (semantics) {
    InkReflowSemantics.FOLLOWER -> "跟随文字"
    // 名字必须跟着行为改：M3.4 之后它**不再是**"留在页面上不动"，
    // 而是"钉在它记的那一行旁边"（形状与笔宽仍冻在落笔那一刻）。
    InkReflowSemantics.PINNED -> "钉在这一行上（形状不变）"
    InkReflowSemantics.FREE -> "自由笔迹（不跟着文字动）"
}

/** 打字批注的种类名。与 `TypedNoteKind` 一一对应，改一处必须改另一处（编译器会拦住）。 */
private fun typedKindLabel(kind: TypedNoteKind): String = when (kind) {
    TypedNoteKind.MARGIN_NOTE -> "边注"
    TypedNoteKind.HIGHLIGHT -> "高亮"
}

@Composable
private fun CenteredMessage(text: String, showSpinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSpinner) CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun ratioOf(session: ReflowableDocumentSession, charOffset: Int): Float =
    if (session.normalized.length == 0) 0f
    else (charOffset.toFloat() / session.normalized.length).coerceIn(0f, 1f)

/**
 * 批注刻度（重排格式）：打字批注 + 手写笔迹。
 *
 * 位置用 [ratioOf]（字符偏移占全文的比例）—— 它只用来**画**刻度，
 * 不用来定位：定位走那条笔记自己的字符偏移。
 *
 * 同一处的多条批注会画出多枚刻度，挤在一起的刻度看着像一根粗线 ——
 * 那没关系，它表达的是"这里写得多"，正是用户想知道的。
 */
private fun buildReflowableTicks(
    typedRows: List<TypedNoteRow>,
    inkRows: List<InkNoteRow>,
    session: ReflowableDocumentSession,
): List<ReaderTick> {
    val out = ArrayList<ReaderTick>(typedRows.size + inkRows.size)
    typedRows.forEach { row ->
        val offset = row.charOffset ?: return@forEach
        out.add(ReaderTick(ratioOf(session, offset), row.annotationId, ink = false))
    }
    inkRows.forEach { row ->
        val offset = row.charOffset ?: return@forEach
        out.add(ReaderTick(ratioOf(session, offset), row.annotationId, ink = true))
    }
    return out
}

/**
 * 批注刻度（PDF）：位置是页号占总页数的比例。
 *
 * 手写笔迹在 PDF 里记的是页（不是字符偏移），打字批注要由字符偏移换算成页 ——
 * 两条通道都换算成"页"，刻度才有统一的位置。
 */
private fun buildPdfTicks(
    typedRows: List<TypedNoteRow>,
    inkPages: Map<String, Int>,
    session: PdfDocumentSession,
    pageCount: Int,
): List<ReaderTick> {
    if (pageCount <= 0) return emptyList()
    val out = ArrayList<ReaderTick>(typedRows.size + inkPages.size)
    typedRows.forEach { row ->
        val offset = row.charOffset ?: return@forEach
        val page = session.pageIndexOfChar(offset)
        if (page < 0) return@forEach
        out.add(ReaderTick((page.toFloat() / pageCount).coerceIn(0f, 1f), row.annotationId, ink = false))
    }
    inkPages.forEach { (id, page) ->
        out.add(ReaderTick((page.toFloat() / pageCount).coerceIn(0f, 1f), id, ink = true))
    }
    return out
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
