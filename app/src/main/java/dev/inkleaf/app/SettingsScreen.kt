package dev.inkleaf.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.inkleaf.core.common.Units
import dev.inkleaf.core.data.ArchiveRepository
import dev.inkleaf.core.data.DerivedCache
import dev.inkleaf.core.document.TypographyPreview
import dev.inkleaf.core.model.HighlightRenderMode
import dev.inkleaf.core.model.LayoutMode
import dev.inkleaf.core.model.PageGeometry
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
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * ## 这一版改的是"设置页能不能自己说明自己"
 *
 * 改之前它是一列 Material Card + 滑杆，标题与说明里全是内部术语
 * （"行盒"、"锚点"、"自动回流"、"方案 §6.5"）。用户看不到一个 sp 意味着什么，
 * 也看不到改完之后版面变成什么样 —— 他得退出去、打开一本书、翻两页，
 * 才知道刚才那一下是变好读了还是变难读了。**设置页在描述结果，却不显示结果。**
 *
 * 这一版补三件事：
 *
 * 1. **顶部一块真试读**：不是画个近似的样子，而是走 `DocumentCodec` 的同一条排版路
 *    （切块 → span → StaticLayout），所以边距让位、首行缩进、按词组断行全部在场。
 *    滑杆一拖，上面立刻重排；"一行约 N 个字"是数第一行真实排下的字数，不是拿宽度除的估数。
 * 2. **分组按用户在做的事**：文字 / 怎么翻 / 外观 / 荧光笔 / 阅读时 / 备份 / 关于。
 *    旧的"排版"和"版式模式"是同一件事被拆成两处（一个改字号、一个改分页），
 *    用户想"把字调大一点"时要在两个卡片之间找。
 * 3. **能退回去**：字号有"小 / 标准 / 大 / 特大"四个落点，滑杆旁边写了默认值，
 *    文字组右下角一个"恢复默认"。在此之前，用户把三个滑杆拖乱之后没有任何回退路径。
 *
 * ## 拖动为什么要草稿
 *
 * 拖动时只改本地草稿（预览立刻重排），**松手才写库**。每次拖动都写库不只是浪费，
 * 还会让"刚松手那一帧"读到旧值、滑杆弹回去一下。草稿在写库完成、与真相源相等之后
 * 自动交还，所以不存在两份状态长期并存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    val vm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VmFactory { SettingsViewModel(container) },
    )
    val settings by vm.settings.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()

    // 试读区与阅读界面用同一套纸墨：正在调羊皮纸，就该在羊皮纸上看到它。
    val readerTheme = settings.readerTheme.resolve(isSystemInDarkTheme())

    // 三个排版数字的本地草稿（见文件头"拖动为什么要草稿"）。
    var draft by remember { mutableStateOf<TypoDraft?>(null) }
    val typed = draft ?: TypoDraft.of(settings)
    val scope = rememberCoroutineScope()

    // 写库回来之后把草稿交还给真相源：两份状态并存得越久，越容易在
    // "导入设置"这类外部改动上打架。
    LaunchedEffect(settings) {
        if (draft != null && draft == TypoDraft.of(settings)) draft = null
    }

    fun commitTypography() {
        val next = draft ?: return
        scope.launch { container.updateSettings { it.withTypography(next) } }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.exportArchive(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importArchive(uri) }

    Scaffold(
        // 与阅读界面同一种纸色，不再是一层 Material 的灰面。
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 顶栏不叠阴影：它是这张纸的一部分，不是浮在上面的一层。
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 第一句就说清"改了会怎样"，省掉用户对"要不要点保存"的猜测。
            // 第二句划清边界：这里的「界面外观」不管书里的纸，纸在阅读界面里调。
            Text(
                "改动立刻生效，也会自动保存。下面这块试读用的是书里的字号与纸色；" +
                    "纸色在阅读界面的「排版」面板里换，这里的「界面外观」只管书架、设置这些界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            TryReadCard(settings = settings.withTypography(typed), theme = readerTheme)

            SettingsSection(
                title = "文字",
                hint = "字号与行距决定一行放多少字、眼睛往下一行时会不会串行。",
            ) {
                ChoiceRow(
                    options = FONT_FAMILY_CHOICES,
                    selected = FONT_FAMILY_CHOICES.firstOrNull { it.value == settings.fontFamily },
                    onSelect = { family ->
                        // 字体是"点一下就定了"的选择，不走排版数字的草稿：
                        // 它换的是排版输入，立即重排并落库。
                        scope.launch {
                            container.updateSettings { it.copy(fontFamily = family) }
                        }
                    },
                )
                ChoiceRow(
                    options = FONT_PRESETS,
                    selected = FONT_PRESETS.firstOrNull {
                        kotlin.math.abs(it.value - typed.fontSizeSp) < 0.51f
                    },
                    onSelect = { preset ->
                        draft = typed.copy(fontSizeSp = preset)
                        commitTypography()
                    },
                )
                LabeledSlider(
                    label = "字号 ${typed.fontSizeSp.roundToInt()} sp",
                    value = typed.fontSizeSp,
                    range = FONT_RANGE,
                    onValueChange = { draft = typed.copy(fontSizeSp = it) },
                    onValueChangeFinished = { commitTypography() },
                    name = "字号",
                )
                LabeledSlider(
                    label = "行距 ${"%.2f".format(typed.lineSpacing)} 倍",
                    value = typed.lineSpacing,
                    range = LINE_RANGE,
                    onValueChange = { draft = typed.copy(lineSpacing = it) },
                    onValueChangeFinished = { commitTypography() },
                    name = "行距",
                )
                LabeledSlider(
                    label = "页边距 ${"%.0f".format(typed.pageMarginMm)} mm",
                    value = typed.pageMarginMm,
                    range = MARGIN_RANGE,
                    onValueChange = { draft = typed.copy(pageMarginMm = it) },
                    onValueChangeFinished = { commitTypography() },
                    name = "页边距",
                )
                Text(
                    "页边距是「我想要的留白」：屏幕窄的时候它会自动让位，" +
                        "保证一行至少 ${PageGeometry.MIN_CONTENT_EM.roundToInt()} 个字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                LabeledSlider(
                    label = "段间距 ${"%.2f".format(typed.paragraphSpacingEm)} 字",
                    value = typed.paragraphSpacingEm,
                    range = SPACING_RANGE,
                    onValueChange = { draft = typed.copy(paragraphSpacingEm = it) },
                    onValueChangeFinished = { commitTypography() },
                    name = "段间距",
                )
                SettingSwitch(
                    title = "两端对齐",
                    hint = "正文右边缘对齐成一条齐线，读起来像纸书。中英混排时收益最大，" +
                        "纯中文的观感差别很小。",
                    checked = settings.justifyText,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(justifyText = on) }
                        }
                    },
                )
                SettingSwitch(
                    title = "中文禁则",
                    hint = "句号、逗号、引号不出现在行首；连续的标点（如 `。」`）压窄一格。" +
                        "这是中文排版的规矩，关掉之后标点会随便落在行首。",
                    checked = settings.cjkTypesetting,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(cjkTypesetting = on) }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            draft = TypoDraft.DEFAULT
                            commitTypography()
                        },
                    ) { Text("恢复默认") }
                }
            }

            SettingsSection(
                title = "怎么翻",
                hint = "改的是翻页手感，不是内容。切换之后会回到你上次读到的地方。",
            ) {
                ChoiceRow(
                    options = TURN_MODES,
                    selected = TURN_MODES.firstOrNull { it.value == settings.layoutMode },
                    onSelect = { mode ->
                        scope.launch {
                            container.updateSettings { it.copy(layoutMode = mode) }
                        }
                    },
                )
                ChoiceRow(
                    options = TURN_EFFECTS,
                    selected = TURN_EFFECTS.firstOrNull { it.value == settings.turnEffect },
                    onSelect = { effect ->
                        scope.launch {
                            container.updateSettings { it.copy(turnEffect = effect) }
                        }
                    },
                )
                Text(
                    "翻页动效只在分页模式下有效；滚动模式本来就是连续滑动，没有\"翻\"这个动作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            SettingsSection(
                title = "界面外观",
                hint = "明暗只管阅读以外的界面（书架、设置、笔记这些）；" +
                    "书里的纸色是另一件事，在阅读界面的「排版」面板里调 —— " +
                    "书架用浅色、书里用夜间纸是正常的。",
            ) {
                ChoiceRow(
                    options = UI_DARK_MODES,
                    selected = UI_DARK_MODES.firstOrNull { it.value == settings.uiDarkMode },
                    onSelect = { mode ->
                        scope.launch {
                            container.updateSettings { it.copy(uiDarkMode = mode) }
                        }
                    },
                )
                SettingSwitch(
                    title = "屏幕常亮",
                    hint = "读长文时不必反复点屏幕。只在阅读界面生效，会稍微费电。",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(keepScreenOn = on) }
                        }
                    },
                )
                SettingSwitch(
                    title = "锁定屏幕方向",
                    hint = "以开启这一刻的方向为准，之后屏幕不再跟着手机转。" +
                        "躺着看书时，少一次转动就少一次整页重排。",
                    checked = settings.rotationLock,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(rotationLock = on) }
                        }
                    },
                )
            }

            SettingsSection(
                title = "荧光笔",
                hint = "只影响标记在纸上的样子，不改变笔记的内容与位置。",
            ) {
                ChoiceRow(
                    options = HIGHLIGHT_MODES,
                    selected = HIGHLIGHT_MODES.firstOrNull { it.value == settings.highlightMode },
                    onSelect = { mode ->
                        scope.launch {
                            container.updateSettings { it.copy(highlightMode = mode) }
                        }
                    },
                )
            }

            SettingsSection(title = "阅读时") {
                SettingSwitch(
                    title = "音量键翻页",
                    hint = "阅读时音量下键翻到下一页、上键翻回上一页。" +
                        "只在这个界面生效，其他地方音量键照常调音量。",
                    checked = settings.volumeKeyTurn,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(volumeKeyTurn = on) }
                        }
                    },
                )
                SettingSwitch(
                    title = "只用手写笔书写",
                    hint = "开着时手指不再落笔：手指点正文仍然是翻页，" +
                        "只有手写笔（和笔的橡皮头）能留下笔迹。",
                    checked = settings.stylusOnly,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(stylusOnly = on) }
                        }
                    },
                )
            }

            SettingsSection(
                title = "书写与性能",
                hint = "默认值对绝大多数设备就是最快的。除非遇到问题，否则不必动。",
            ) {
                SettingSwitch(
                    title = "书写时手指点两侧翻页",
                    hint = "书写模式下，手指落在页面左 / 右三分之一翻页，中间照常写字。" +
                        "只用笔、手经常搭在屏幕上的话，关掉可以避免误触。",
                    checked = settings.pageTurnZones,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(pageTurnZones = on) }
                        }
                    },
                )
                SettingSwitch(
                    title = "低延迟书写（无缓冲派发）",
                    hint = "让笔迹更快跟手。个别设备的笔与它配合不好（笔迹抖动）时再关。",
                    checked = settings.inkUnbuffered,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(inkUnbuffered = on) }
                        }
                    },
                )
                SettingSwitch(
                    title = "静态层缓存",
                    hint = "正文与已落盘的笔迹缓存成一帧，书写时只画新墨。" +
                        "出现残影或错层时关掉它，能帮我们定位问题。",
                    checked = settings.inkStaticLayer,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.updateSettings { it.copy(inkStaticLayer = on) }
                        }
                    },
                )
            }

            SettingsSection(
                title = "备份与恢复",
                hint = "一个 zip 里是全部文档副本、笔记、笔迹、阅读进度与设置。",
            ) {
                Text(
                    "导入只做合并：已经存在的文档与笔记会被跳过，不会被覆盖，" +
                        "所以拿旧备份恢复也不会冲掉新写的笔记。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                // 卸载会连私有目录一起删掉，而 Android 拦不住卸载 ——
                // 这句话是唯一能给出的警告，写在导出按钮的上面而不是藏在关于里。
                Text(
                    "卸载应用会删掉全部笔记，请先导出全库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(vm.suggestedArchiveName()) },
                        enabled = !busy,
                    ) { Text("导出全库") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "*/*")) },
                        enabled = !busy,
                    ) { Text("导入档案") }
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("导入失败") || it.startsWith("导出失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        },
                    )
                }
            }

            SettingsSection(
                title = "存储",
                hint = "书架封面是从书里现抽的缩略图，删了会在下次打开书架时重新生成；" +
                    "笔记、正文与笔迹不在这里，永远不会被这一步碰到。",
            ) {
                Text(
                    cacheUsageLine(vm.cacheUsage.value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.clearCache() },
                        enabled = !busy && (vm.cacheUsage.value?.fileCount ?: 0) > 0,
                    ) { Text("清理封面缓存") }
                }
                vm.cacheStatus.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("清理失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        },
                    )
                }
            }

            SettingsSection(title = "关于") {
                Text("InkLeaf ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "批注只存放在应用私有目录：不写回源文件（除非你主动导出），也不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    "不申请任何权限：没有网络，没有账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    "代码以 MIT 许可发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── 试读 ────────────────────────────────────────────────────────────────────

/**
 * 真排版引擎渲染的试读区。
 *
 * 高度是**常量**（168 dp）。它一变，整页的滚动位置就会跳，用户正在对比的两版
 * 就没有同一个参照了；也正因为高度固定，超出部分是被裁掉的 —— 那不是"内容少了"，
 * 是一页纸到这里为止。
 */
@Composable
private fun TryReadCard(settings: ReaderSettings, theme: ReaderTheme) {
    val density = LocalDensity.current
    val densityDpi = LocalContext.current.resources.displayMetrics.densityDpi
    val paper = theme.paper
    val border = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // 纸面上的那圈边线必须跟着**纸**走，不能用界面的 onSurface：
    // M18 之后"界面深色 + 纸是浅色"是合法组合，那时 onSurface 是白色，
    // 一道白色半透明线画在浅纸上等于没有 —— 试读卡会看起来像没边框。
    val paperEdge = theme.ink.copy(alpha = 0.14f)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "试读",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(8.dp))

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widthPx = constraints.maxWidth
                val heightPx = with(density) { PREVIEW_HEIGHT.toPx() }.roundToInt()
                val textSizePx = with(density) { settings.fontSizeSp.sp.toPx() }
                val marginPx = Units.mmToPx(settings.pageMarginMm, densityDpi)

                // 只有真正影响版面输入的量才重建。dark 在键里：颜色变了也要重排，
                // 否则切主题后预览还是旧墨色。
                val preview = remember(
                    widthPx, heightPx, textSizePx, settings.lineSpacing, marginPx,
                    density.density, theme, settings.fontFamily,
                ) {
                    TypographyPreview.build(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        textSizePx = textSizePx,
                        lineSpacingMultiplier = settings.lineSpacing,
                        userMarginPx = marginPx,
                        density = density.density,
                        inkArgb = theme.inkArgb,
                        dark = theme.isDark,
                        typeface = settings.fontFamily.resolveTypeface(),
                    )
                }

                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PREVIEW_HEIGHT)
                            .clipToBounds()
                            .background(paper, RoundedCornerShape(8.dp))
                            .drawBehind {
                                drawRect(
                                    color = paperEdge,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                                )
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            // 裁到最后一个**整行**的底边，而不是画布底边：
                            // 后者会正好切在半行字上，看起来像界面坏了。
                            val clipBottom =
                                preview.metrics.marginTopPx + preview.visibleTextHeightPx
                            clipRect(top = 0f, bottom = clipBottom) {
                                drawIntoCanvas { canvas ->
                                    val native = canvas.nativeCanvas
                                    native.save()
                                    native.translate(
                                        preview.metrics.marginLeftPx,
                                        preview.metrics.marginTopPx,
                                    )
                                    preview.layout.sections.forEach { section ->
                                        native.save()
                                        native.translate(0f, section.topPx.toFloat())
                                        section.layout.draw(native)
                                        native.restore()
                                    }
                                    native.restore()
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 这两个数字是**量出来的**：字数来自第一行真实排布，
                    // 占比来自 PageGeometry 解出的三个框。不是按字号做的估算。
                    Text(
                        "一行约 ${preview.charsPerLine} 个字 · " +
                            "正文占屏宽 ${(preview.widthRatio * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

// ── 组件 ────────────────────────────────────────────────────────────────────

/**
 * 一个分组。
 *
 * 用"标题 + 一行解释 + 内容"三段，而不是只有一个标题：设置项之所以显得冷冰冰，
 * 大多是因为它只告诉用户"这里有个开关"，没告诉他"打开之后会发生什么"。
 */
@Composable
private fun SettingsSection(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val border = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            content()
        }
    }
}

/**
 * 一行开关：名字 + 一句解释 + 右侧开关。
 *
 * 整行可点（不只是那个开关）：50 dp 的行高比 32 dp 的开关好点得多，
 * 读屏也会把"名字 + 状态 + 可切换"当成一件事念出来。
 */
@Composable
private fun SettingSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 一个可选项：值 + 按钮上的短标签 + 选中后那一行解释。 */
private class Choice<T>(val value: T, val label: String, val hint: String)

/**
 * 一排互斥选项。
 *
 * 用 [FlowRow] 而不是 [Row]：选项文字长短不一（"跟随系统"比"浅色"宽一倍），
 * 窄屏上 Row 会把最后一个挤出屏幕外，而挤出去的那个恰好是用户要点的。
 * 同一行里还给出**当前选中项的解释** —— 三个陌生词排在那里，不解释等于让用户猜。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    options: List<Choice<T>>,
    selected: Choice<T>?,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { choice ->
                FilterChip(
                    selected = choice === selected,
                    onClick = { onSelect(choice.value) },
                    label = { Text(choice.label) },
                )
            }
        }
        selected?.let {
            Text(
                it.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

/**
 * 一行标题 + 一根滑杆。
 *
 * [onValueChangeFinished] 是可选的：设置页里改动很小、写入也便宜，松不松手都行；
 * 但阅读界面的排版面板**必须**等到松手才写库 —— 那里的每个值都会触发整篇重排
 * （重建 StaticLayout），边拖边排会掉帧。
 *
 * [name] 是给读屏用的**固定**名字（"字号""行距"）。不传就退回 [label]。
 * 为什么要单独一个参数：[label] 里带着当前值（"字号 17 sp"），而 Slider 自己
 * 已经会读 `stateDescription`（17.0）—— 直接拿 label 当描述会让 TalkBack 把
 * 数字念两遍。更要紧的是，没有名字的滑杆在语义树里只是一根"未命名滑杆"，
 * 设备测试也只能靠**下标**去猜哪根是字号（M15：托盘里多了笔宽滑杆之后，
 * 原来的 `[0]` 就指错了对象，用例却还在报"页数没变"）。
 */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    name: String? = null,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.semantics { contentDescription = name ?: label },
        )
    }
}

// ── 常量与预设 ───────────────────────────────────────────────────────────────

/**
 * 试读区高度。
 *
 * 176 dp 在默认设置（17 sp、1.45 倍行距、4 mm 边距）下正好放下样本的四行 ——
 * 中文两行 + 英文两行。再矮就只剩中文，看不出中英混排时的基线；
 * 再高就把下面的滑杆推出第一屏，而滑杆才是用户真正要动的东西。
 */
private val PREVIEW_HEIGHT = 176.dp

private const val FONT_MIN = 12f
private const val FONT_MAX = 32f
private const val LINE_MIN = 1.0f
private const val LINE_MAX = 2.5f
private const val MARGIN_MAX = 40f
private val FONT_RANGE = FONT_MIN..FONT_MAX
private val LINE_RANGE = LINE_MIN..LINE_MAX
private val MARGIN_RANGE = 0f..MARGIN_MAX

/** 段间距的两端：0 = 段与段紧贴，1 = 空出一整行。 */
private val SPACING_RANGE = 0f..1f

/**
 * 字号的四个落点。
 *
 * 滑杆给的是连续值，但读者心里想的是"大一点""再大一点"，不是"20.5 sp"。
 * 给了落点之后，手指不必精确停在某个像素上；滑杆仍然在，用来微调。
 * 数值不是均分的：12→32 均分会让"标准"落在 20，而 20 sp 已经偏大了。
 */
private val FONT_PRESETS = listOf(
    Choice(15f, "小", "15 sp：一屏装得下更多字，适合通勤时快速读。"),
    Choice(17f, "标准", "17 sp：默认值，接近纸书的正文大小。"),
    Choice(20f, "大", "20 sp：一行字少了，眼睛不用来回扫。"),
    Choice(24f, "特大", "24 sp：给视力不佳或大屏远看时用，一页只有十来行。"),
)

/**
 * 界面明暗三档。
 *
 * 与阅读主题的六档色卡分开：那是"纸"，这是"界面"。这里只解释当前这一档，
 * 因为"跟随系统 / 浅色 / 深色"三个词本身不需要解释，需要解释的是**它管哪里**。
 */
private val UI_DARK_MODES = listOf(
    Choice(
        DarkModePreference.SYSTEM,
        "跟随系统",
        "系统切到深色时跟着变。白天浅色、夜里深色，不用管它。",
    ),
    Choice(
        DarkModePreference.LIGHT,
        "浅色",
        "书架与设置一直用浅色，不管系统怎么设。适合白天固定用浅色的人。",
    ),
    Choice(
        DarkModePreference.DARK,
        "深色",
        "书架与设置一直用深色。注意：这只改外面的界面，书里那张纸由阅读主题决定。",
    ),
)

/** 书里正文的字体（M29）。描述写清"中文上长什么样"，用户不试也能选对。 */
private val FONT_FAMILY_CHOICES = listOf(
    Choice(
        ReaderFontFamily.DEFAULT,
        "默认",
        "系统默认字体（黑体风格）。笔画均匀，屏幕上最清晰省眼。",
    ),
    Choice(
        ReaderFontFamily.SERIF,
        "宋体",
        "衬线字体，横细竖粗，最接近纸质书的长文阅读体验。",
    ),
    Choice(
        ReaderFontFamily.MONOSPACE,
        "等宽",
        "每个字符同宽。中英混排时英文对得整齐，中文阅读收益不大。",
    ),
)

private val TURN_MODES = listOf(
    Choice(
        LayoutMode.PAGED,
        "翻页",
        "一页一页地翻：点屏幕左右两侧就会翻页。适合小说、散文这类顺着读的内容。",
    ),
    Choice(
        LayoutMode.SCROLL,
        "滚动",
        "像网页一样连续往下滑：中途停下不会丢位置。适合资料、文档这类边读边找的内容。",
    ),
    Choice(
        LayoutMode.LOCKED,
        "固定字号",
        "仍然一页一页翻，但忽略系统的字体大小设置 —— 换了手机、改了系统字号，" +
            "这里的版面也不变。适合需要对照原文的场合。",
    ),
)

/**
 * 翻页动效三档（M33）。
 *
 * 描述里写的是**代价**而不只是效果：这三档对电量的影响差别真实存在
 * （仿真翻页每翻一次要截一张全屏位图），用户有权知道"好看"要花什么。
 */
private val TURN_EFFECTS = listOf(
    Choice(
        TurnEffect.NONE,
        "直接换页",
        "翻页瞬间完成，不做动画。最省电，也最不容易让人分心。",
    ),
    Choice(
        TurnEffect.FADE,
        "淡入",
        "新的一页用 120 毫秒淡进来。眼睛有个过渡，开销可以忽略。",
    ),
    Choice(
        TurnEffect.CURL,
        "仿真翻页",
        "像纸书一样把这一页沿书脊卷走，约 0.3 秒。每翻一页要多截一张位图，最费电。",
    ),
)

private val HIGHLIGHT_MODES = listOf(
    Choice(
        HighlightRenderMode.MULTIPLY,
        "正片叠底",
        "颜色与字色相乘：标记看起来最像真的荧光笔，字仍然是深色。默认用它。",
    ),
    Choice(
        HighlightRenderMode.UNDER_TEXT,
        "垫在字下",
        "颜色铺在字的下方，字一点不被染色，最清晰，但荧光感弱一些。",
    ),
    Choice(
        HighlightRenderMode.OVERLAY,
        "盖在字上",
        "半透明色盖在字上，最像马克笔，字会被染色、对比度略降。",
    ),
)

/**
 * 三个排版数字的草稿。
 *
 * 只装这三个：分组里的其他选项（翻页方式、主题、荧光笔）不需要草稿 ——
 * 它们是"点一下就定了"，放进来反而会盖住别处写入的新值。
 */
private data class TypoDraft(
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val pageMarginMm: Float,
    val paragraphSpacingEm: Float,
) {
    fun toSettings(base: ReaderSettings): ReaderSettings = base.copy(
        fontSizeSp = fontSizeSp,
        lineSpacing = lineSpacing,
        pageMarginMm = pageMarginMm,
        paragraphSpacingEm = paragraphSpacingEm,
    )

    companion object {
        fun of(s: ReaderSettings) =
            TypoDraft(s.fontSizeSp, s.lineSpacing, s.pageMarginMm, s.paragraphSpacingEm)

        /** 默认值从 [ReaderSettings] 自己取，不在这里抄一遍数字。 */
        val DEFAULT = of(ReaderSettings())
    }
}

private fun ReaderSettings.withTypography(draft: TypoDraft): ReaderSettings = draft.toSettings(this)

/**
 * 缓存占用的一行说明。
 *
 * 没量过（null）就说"正在统计"而不是"0 KB"——
 * 撒谎的"0"会让用户以为已经清过了。
 */
private fun cacheUsageLine(usage: DerivedCache.Usage?): String = when {
    usage == null -> "正在统计…"
    usage.fileCount == 0 -> "目前没有缓存的封面。"
    else -> "封面缓存 ${usage.fileCount} 个文件，占 ${formatBytes(usage.bytes)}。"
}

// ── ViewModel ───────────────────────────────────────────────────────────────

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = container.settings

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 派生缓存当前占用。
     *
     * null = 还没量过（刚进设置页）。界面据此把按钮置灰 ——
     * 一进来就显示"0 个文件、0 KB"是撒谎：那只是还没量。
     */
    private val _cacheUsage = MutableStateFlow<DerivedCache.Usage?>(null)
    val cacheUsage: StateFlow<DerivedCache.Usage?> = _cacheUsage.asStateFlow()

    private val _cacheStatus = MutableStateFlow<String?>(null)
    val cacheStatus: StateFlow<String?> = _cacheStatus.asStateFlow()

    init {
        measureCache()
    }

    /** 量一下缓存有多大。 */
    fun measureCache() {
        viewModelScope.launch {
            val usage = runCatching { container.store.documents.derivedUsage() }.getOrNull()
            _cacheUsage.value = usage
        }
    }

    /**
     * 清空封面缓存。
     *
     * 只清派生资源，笔记、正文、笔迹一律不碰 ——
     * 这一点必须在界面上说清楚，否则用户会以为"清缓存"是"清空应用"。
     *
     * 清完之后书架会重新抽封面（`DerivedCache.epoch` 变了），
     * 所以"清完再回书架看到封面还在"不是没清掉，是重新生成的。
     */
    fun clearCache() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val cleared = container.store.documents.clearDerived()
                _cacheStatus.value = if (cleared.fileCount == 0) {
                    "没有可清的封面缓存。"
                } else {
                    "已清理 ${cleared.fileCount} 个封面缓存，释放 ${formatBytes(cleared.bytes)}。" +
                        "回到书架时会重新生成。"
                }
            } catch (e: Exception) {
                _cacheStatus.value = "清理失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
                measureCache()
            }
        }
    }

    fun suggestedArchiveName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "inkleaf-archive-$stamp.zip"
    }

    fun exportArchive(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在导出…"
            try {
                val resolver = container.context.contentResolver
                val summary = withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri)
                        ?: throw ArchiveRepository.ArchiveException("无法写入所选位置")
                    out.use { container.store.archive.exportToStream(it, BuildConfig.VERSION_NAME) }
                }
                _status.value = "已导出 ${summary.documentCount} 篇文档、${summary.annotationCount} 条笔记、" +
                    "${summary.strokeCount} 条笔迹。"
            } catch (e: Exception) {
                _status.value = "导出失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun importArchive(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在导入…"
            try {
                val resolver = container.context.contentResolver
                val summary = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri)
                        ?: throw ArchiveRepository.ArchiveException("无法读取所选文件")
                    input.use { container.store.archive.importFromStream(it) }
                }
                _status.value = buildString {
                    append("已导入 ${summary.importedDocuments} 篇文档、${summary.importedAnnotations} 条笔记。")
                    if (summary.skippedDocuments > 0) append("跳过已存在文档 ${summary.skippedDocuments} 篇。")
                    if (summary.skippedAnnotations > 0) append("跳过已存在笔记 ${summary.skippedAnnotations} 条。")
                    if (summary.skippedStrokes > 0) append("有 ${summary.skippedStrokes} 条笔迹数据损坏，未导入。")
                }
            } catch (e: Exception) {
                _status.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }
}
