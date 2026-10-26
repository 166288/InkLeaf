package dev.inkleaf.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.inkleaf.core.common.AppLog
import dev.inkleaf.core.data.DocumentImporter
import dev.inkleaf.core.data.InkLeafStore
import dev.inkleaf.core.data.SettingKeys
import dev.inkleaf.core.model.HighlightRenderMode
import dev.inkleaf.core.model.LayoutMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 界面明暗策略：**阅读页以外**的那些界面（书架、设置、导入、笔记、统计）用不用深色。
 *
 * 与 [LayoutMode] 那种模型层枚举分开：它是纯粹的用户偏好（跟不跟随系统），
 * 而模型层的枚举会被持久化进锚点链、参与指纹计算，两者的变更代价完全不同。
 * 把"用户偏好"混进模型层是这类项目最常见的分层污染。
 *
 * ## 为什么它与 [ReaderTheme] 是两件事
 *
 * 曾经是一件事：选「夜间」既改书里的纸、也改书架的底色，理由是"整套界面同一套明暗
 * 才不会割裂"。实际用起来恰好相反 —— 有人白天把书架留在浅色，只在夜里读书时
 * 才要深色纸；也有人反过来，系统深色、但书一定要白纸。这两种诉求都不涉及"割裂"，
 * 割裂的是**强行把它们绑在一起**。
 *
 * 所以现在：这里管"外面"，[ReaderTheme] 管"书里"。两者互不推导，各自持久化。
 */
enum class DarkModePreference {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** 解析成"到底是不是深色"。界面层只需要这个布尔答案。 */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * 书里正文的字体族（M29）。
 *
 * 刻意只给系统字体族，不引入字体文件：内置字体会让 APK 涨几 MB～十几 MB，
 * 而且"你的书离开这台设备"时导入导出还要考虑字体的授权问题。
 * Android 系统的 serif 族在中文上落在 Noto Serif CJK（宋体风格），
 * 对长文阅读是真正有用的第二选项；monospace 主要给代码控。
 */
enum class ReaderFontFamily {
    /** 系统默认（sans-serif，中文上是黑体风格）。 */
    DEFAULT,

    /** 宋体风格（系统 serif 族，中文上是 Noto Serif CJK）。 */
    SERIF,

    /** 等宽。 */
    MONOSPACE,
    ;

    /** 换算成排版层的 Typeface。null = 不指定，用系统默认。 */
    fun resolveTypeface(): android.graphics.Typeface? = when (this) {
        DEFAULT -> null
        SERIF -> android.graphics.Typeface.SERIF
        MONOSPACE -> android.graphics.Typeface.MONOSPACE
    }

    companion object {
        fun from(name: String?): ReaderFontFamily =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 翻页动效（M33）。
 *
 * 为什么是一个三档枚举而不是两个开关：这三种效果**互斥** ——
 * 同时开"淡入"和"仿真"时，到底是先淡再卷，还是卷的时候顺带淡，
 * 没有一个用户能预期得到的答案。选项越少，选择越不需要解释。
 */
enum class TurnEffect {
    /** 不等待：页面瞬间换掉。最省电，也最"直"。 */
    NONE,

    /** 新页 120 ms 淡入。翻页动作仍然是一下就完成，只是眼睛有个过渡。 */
    FADE,

    /** 仿真翻页：上一页的位图沿页脊卷过去（M33 的 C 档）。 */
    CURL,
    ;

    companion object {
        fun from(name: String?): TurnEffect =
            entries.firstOrNull { it.name == name } ?: FADE
    }
}

/**
 * 阅读器设置。
 *
 * 全部有默认值，且默认值必须与 [dev.inkleaf.core.data.StorageSchema.DEFAULT_SETTINGS]
 * 一致 —— 两处不一致的症状是"设置界面显示的与实际排版用的不是一回事"，
 * 而它只在第一次启动时出现，最难被发现。
 */
data class ReaderSettings(
    val fontSizeSp: Float = 17f,
    val lineSpacing: Float = 1.45f,
    /**
     * 页边距（毫米）。
     *
     * M8 之前默认 16 mm（按纸书定的），在 411 dp 的手机上等于每边吃掉 100 dp，
     * 正文只剩 12 个汉字一行。现在它是"我想要的留白"而不是"正文必须让出的宽度"：
     * 窄屏上 [dev.inkleaf.core.model.PageGeometry] 会让它给正文让位，宽屏上则用它
     * 之外的多余宽度来限制行宽。默认值随之降到 4 mm。
     */
    val pageMarginMm: Float = 4f,
    val layoutMode: LayoutMode = LayoutMode.PAGED,
    /**
     * 阅读主题：**书里的纸色 + 墨色**。
     *
     * 它与 [fontSizeSp]、[lineSpacing] 同属"排版"那一组，不是"外观"：
     * 纸色一变，同一个字号看起来的大小、同一段落的可读性都会变，
     * 三者必须放在一起调才是对的 —— 所以它的入口在**阅读界面的排版面板**里，
     * 而不是在设置页（设置页那个是 [uiDarkMode]，管阅读以外）。
     */
    val readerTheme: ReaderTheme = ReaderTheme.AUTO,
    /**
     * 阅读以外那些界面的明暗。
     *
     * 与 [readerTheme] 曾经是同一个字段，见 [DarkModePreference] 的注释。
     * 拆开之后 `ui.dark_mode` 这个老键重新成为它的真相源（它本来就是这个意思），
     * 而 `reader.theme` 只归书里用。
     */
    val uiDarkMode: DarkModePreference = DarkModePreference.SYSTEM,
    val highlightMode: HighlightRenderMode = HighlightRenderMode.MULTIPLY,
    val keepScreenOn: Boolean = false,
    /**
     * 只允许手写笔书写。
     *
     * 开着时手指不再被当成笔：落在正文上的触摸继续上浮给翻页手势。
     * 这不是"防误触"的补丁，而是一类真实使用方式 —— 用笔批注、用手指翻页，
     * 两种输入本来就是分开的（见 `DocumentInkView.onTouchEvent`）。
     */
    val stylusOnly: Boolean = false,
    /** 书架用网格还是列表。它不影响阅读，但同样该被记住。 */
    val libraryGrid: Boolean = false,
    /**
     * 书写时手指落在左右两侧是否翻页（M25 的分区让渡）。
     *
     * 大多数人要的就是"笔写字、手指翻页"；但也有人只用笔、手经常搭在屏上，
     * 分区对他们就是误触源 —— 给个开关，默认与 M25 的行为一致。
     */
    val pageTurnZones: Boolean = true,
    /**
     * 阅读界面里音量键翻页（M27）。
     *
     * 上键=上一页、下键=下一页。单手握持时拇指就搭在音量键上，
     * 这是物理按键能提供的最省力的翻页方式。默认开：它只在阅读界面接管音量键，
     * 离开阅读界面音量键立刻恢复本来的职责，误伤面很小。
     */
    val volumeKeyTurn: Boolean = true,
    /** 书里正文的字体族（M29）。见 [ReaderFontFamily]。 */
    val fontFamily: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    /**
     * 书写与套索走无缓冲派发（`requestUnbufferedDispatch`）。
     *
     * 它把"事件到达应用"与"这一帧被合成"之间的系统缓冲省掉，是跟手性
     * 最大的一档收益。个别设备的笔固件与它配合不好（事件抖动），
     * 关掉即回到普通派发 —— 所以做成设置而不是写死。
     */
    val inkUnbuffered: Boolean = true,
    /**
     * 静态层（正文 + 已落盘的墨）是否录进 RenderNode 缓存。
     *
     * 开着时书写每一帧只画湿墨与覆盖层，静态层整帧复用；关掉则每帧全量重画。
     * 正常都该开着；排查显示问题（残影、错层）时关掉它能把问题缩小到
     * "缓存"与"当场画"哪一侧。
     */
    val inkStaticLayer: Boolean = true,
    /**
     * 锁住屏幕方向（M31）。
     *
     * 开着时 Activity 用 `SCREEN_ORIENTATION_LOCKED`：以**开启那一刻**的方向为准，
     * 之后怎么转都不动。关掉即恢复系统默认（跟随重力感应）。
     * 躺着看书、单手举着手机时，屏幕自己转身会把整页重排一次，读着很难受。
     */
    val rotationLock: Boolean = false,
    /**
     * 段间距（em，M33）。0 = 段与段紧贴，1 = 空出一整行。
     *
     * 默认 0.3：纸书的段间距大约是半个到一个"字高"之间，0.3 配上 1.45 倍行距，
     * 段与段之间的空隙约为行距的 1.2 倍 —— 眼睛能分出"新的一段开始了"，
     * 又不至于一页只剩七八行。
     */
    val paragraphSpacingEm: Float = 0.3f,
    /** 两端对齐（M33）。中文的右边缘要不要像纸书一样齐。 */
    val justifyText: Boolean = true,
    /** 中文禁则（M33）：标点不出现在行首，连续标点挤压。 */
    val cjkTypesetting: Boolean = true,
    /** 翻页动效（M33）。只在分页模式下有意义。 */
    val turnEffect: TurnEffect = TurnEffect.FADE,
) {

    fun toSettingsMap(): Map<String, String> = mapOf(
        SettingKeys.FONT_SIZE_SP to fontSizeSp.toString(),
        SettingKeys.LINE_SPACING to lineSpacing.toString(),
        SettingKeys.PAGE_MARGIN_MM to pageMarginMm.toString(),
        SettingKeys.LAYOUT_MODE to layoutMode.name,
        SettingKeys.READER_THEME to readerTheme.name,
        SettingKeys.DARK_MODE to uiDarkMode.name,
        SettingKeys.HIGHLIGHT_MODE to highlightMode.name,
        SettingKeys.KEEP_SCREEN_ON to keepScreenOn.toString(),
        SettingKeys.STYLUS_ONLY to stylusOnly.toString(),
        SettingKeys.LIBRARY_GRID to libraryGrid.toString(),
        SettingKeys.PAGE_TURN_ZONES to pageTurnZones.toString(),
        SettingKeys.VOLUME_KEY_TURN to volumeKeyTurn.toString(),
        SettingKeys.READER_FONT_FAMILY to fontFamily.name,
        SettingKeys.INK_UNBUFFERED to inkUnbuffered.toString(),
        SettingKeys.INK_STATIC_LAYER to inkStaticLayer.toString(),
        SettingKeys.ROTATION_LOCK to rotationLock.toString(),
        SettingKeys.PARAGRAPH_SPACING_EM to paragraphSpacingEm.toString(),
        SettingKeys.JUSTIFY_TEXT to justifyText.toString(),
        SettingKeys.CJK_TYPESETTING to cjkTypesetting.toString(),
        SettingKeys.TURN_EFFECT to turnEffect.name,
    )

    companion object {
        /** 从设置表还原。任何一项读不出来就退回该字段的默认值（设置不是锚点，宽容是合理的）。 */
        fun from(map: Map<String, String>): ReaderSettings {
            val defaults = ReaderSettings()
            return ReaderSettings(
                fontSizeSp = map[SettingKeys.FONT_SIZE_SP]?.toFloatOrNull()?.coerceIn(12f, 32f)
                    ?: defaults.fontSizeSp,
                lineSpacing = map[SettingKeys.LINE_SPACING]?.toFloatOrNull()?.coerceIn(1.0f, 2.5f)
                    ?: defaults.lineSpacing,
                pageMarginMm = map[SettingKeys.PAGE_MARGIN_MM]?.toFloatOrNull()?.coerceIn(0f, 40f)
                    ?: defaults.pageMarginMm,
                layoutMode = map[SettingKeys.LAYOUT_MODE]?.let { name ->
                    LayoutMode.entries.firstOrNull { it.name == name }
                } ?: defaults.layoutMode,
                readerTheme = resolveReaderTheme(map) ?: defaults.readerTheme,
                uiDarkMode = resolveUiDarkMode(map) ?: defaults.uiDarkMode,
                highlightMode = map[SettingKeys.HIGHLIGHT_MODE]?.let { name ->
                    HighlightRenderMode.entries.firstOrNull { it.name == name }
                } ?: defaults.highlightMode,
                keepScreenOn = map[SettingKeys.KEEP_SCREEN_ON]?.toBooleanStrictOrNull()
                    ?: defaults.keepScreenOn,
                stylusOnly = map[SettingKeys.STYLUS_ONLY]?.toBooleanStrictOrNull()
                    ?: defaults.stylusOnly,
                libraryGrid = map[SettingKeys.LIBRARY_GRID]?.toBooleanStrictOrNull()
                    ?: defaults.libraryGrid,
                pageTurnZones = map[SettingKeys.PAGE_TURN_ZONES]?.toBooleanStrictOrNull()
                    ?: defaults.pageTurnZones,
                volumeKeyTurn = map[SettingKeys.VOLUME_KEY_TURN]?.toBooleanStrictOrNull()
                    ?: defaults.volumeKeyTurn,
                fontFamily = ReaderFontFamily.from(map[SettingKeys.READER_FONT_FAMILY]),
                inkUnbuffered = map[SettingKeys.INK_UNBUFFERED]?.toBooleanStrictOrNull()
                    ?: defaults.inkUnbuffered,
                inkStaticLayer = map[SettingKeys.INK_STATIC_LAYER]?.toBooleanStrictOrNull()
                    ?: defaults.inkStaticLayer,
                rotationLock = map[SettingKeys.ROTATION_LOCK]?.toBooleanStrictOrNull()
                    ?: defaults.rotationLock,
                paragraphSpacingEm = map[SettingKeys.PARAGRAPH_SPACING_EM]?.toFloatOrNull()
                    ?.coerceIn(0f, 1f) ?: defaults.paragraphSpacingEm,
                justifyText = map[SettingKeys.JUSTIFY_TEXT]?.toBooleanStrictOrNull()
                    ?: defaults.justifyText,
                cjkTypesetting = map[SettingKeys.CJK_TYPESETTING]?.toBooleanStrictOrNull()
                    ?: defaults.cjkTypesetting,
                turnEffect = TurnEffect.from(map[SettingKeys.TURN_EFFECT]),
            )
        }

        /**
         * 阅读主题的读取顺序：**新键优先，旧键兜底**。
         *
         * 旧版本只存 `ui.dark_mode`（三档）。直接忽略它会让升级后的用户
         * "从深色突然变回白纸"—— 那是最刺眼的一种升级体验。
         * 这里把它映射成对应的主题，之后用户改主题时新键就会接管。
         *
         * 注意 0.4.0 起 `ui.dark_mode` 又变回它自己的意思（界面明暗），
         * 但它同时仍是这个键的**历史来源**：只有 reader.theme 缺失时才会走到下面，
         * 而 0.4.0 之后每次写设置都会把 reader.theme 写进去。所以两者不会打架。
         */
        private fun resolveReaderTheme(map: Map<String, String>): ReaderTheme? {
            map[SettingKeys.READER_THEME]?.let { return ReaderTheme.fromName(it) }
            return when (map[SettingKeys.DARK_MODE]?.uppercase()) {
                "DARK" -> ReaderTheme.NIGHT
                "LIGHT" -> ReaderTheme.PAPER
                else -> null
            }
        }

        /** 界面明暗。老库里的值可能是小写（DEFAULT_SETTINGS 曾经写的是 `system`）。 */
        private fun resolveUiDarkMode(map: Map<String, String>): DarkModePreference? =
            map[SettingKeys.DARK_MODE]?.uppercase()?.let { name ->
                DarkModePreference.entries.firstOrNull { it.name == name }
            }
    }
}

/**
 * 应用级依赖容器。
 *
 * 同时承担"设置的单例状态"这个职责：[settings] 是所有界面共用的唯一真相源，
 * 因此改字号之后回到阅读界面立刻生效，不需要哪个界面去主动刷新。
 */
class AppContainer(
    context: Context,
    /**
     * 手写识别引擎（S3 的 L3）。**默认 null —— 本应用不内置模型。**
     *
     * 中文手写模型体积大（十几到几十 MB）、准确率不稳，而且会改变本应用
     * "你的字从未离开这台设备"这句隐私叙事。所以这里只留插拔口：
     * 想接的人实现 `HandwritingOcr` 并在构造时传进来即可，
     * 界面会据此多出一个识别入口；不传就一个入口都没有 ——
     * 不会有"点了没反应"的按钮，也不会有假装能识别的地方。
     */
    val handwritingOcr: dev.inkleaf.core.ink.HandwritingOcr? = null,
) {

    val context: Context = context.applicationContext

    /**
     * 音量键翻页回调（M27）。
     *
     * 阅读界面打开时注册、离开时注销；null 表示当前不在阅读界面。
     * MainActivity 的 `onKeyDown/onKeyUp` 查它：有回调且设置开着，
     * 音量键就被转交为翻页，否则照常调音量。
     *
     * 为什么走 Activity 拦截而不是 Compose 的 `onPreviewKeyEvent`：
     * 音量键由系统直接派发给窗口，Compose 只在内部持有焦点时才收得到按键，
     * 正文里没有可聚焦的节点 —— 走 Compose 的口径等于永远收不到。
     * 参数 `forward`：true=下一页（音量下键），false=上一页（音量上键）。
     */
    var readerVolumeKeyHandler: ((forward: Boolean) -> Unit)? = null

    // ── 最近打开的文档会话缓存（M29）─────────────────────────────────────
    //
    // 每次打开一本书都要"读文件 → 解析 → 切块"，重排格式还要再"建 StaticLayout
    // 排版"。而这些结果里的绝大部分**在重开同一本书时原封不动**：解析产物挂在
    // 会话上，排版产物挂在会话的缓存里（键是尺寸+字号+字体）。
    //
    // 这里留**一个槽位**（最近一本）：从书架进出阅读界面、从笔记清单跳回原位
    // 这类"重开刚看过的书"的场景直接命中 —— 「正在打开…」和「正在排版…」
    // 两个加载页全部跳过，界面立即可用。
    //
    // 内存账：会话持有全书的规范化文本，一本长篇在几 MB 量级，一个槽位可以接受；
    // 槽位按 docId+指纹 失配（重新导入）自动让位，不需要显式失效。
    private var cachedSessionKey: String? = null

    @Volatile
    private var cachedSessionValue: dev.inkleaf.core.document.DocumentSession? = null

    /** PDF 文本层能力（设备级事实），与会话一起缓存：Ready 状态需要它。 */
    @Volatile
    private var cachedPdfTextLayerAvailable: Boolean = true

    /** 命中返回会话（排版缓存在会话里，命中即免排版）；未命中返回 null。 */
    fun cachedSessionFor(
        docId: String,
        fingerprint: String,
    ): Pair<dev.inkleaf.core.document.DocumentSession, Boolean>? {
        if (cachedSessionKey == "$docId|$fingerprint") {
            val session = cachedSessionValue ?: return null
            return session to cachedPdfTextLayerAvailable
        }
        return null
    }

    /** 打开成功后放入缓存。换书自动挤掉上一本（单槽位）。 */
    fun cacheSession(
        docId: String,
        fingerprint: String,
        session: dev.inkleaf.core.document.DocumentSession,
        pdfTextLayerAvailable: Boolean,
    ) {
        cachedSessionKey = "$docId|$fingerprint"
        cachedSessionValue = session
        cachedPdfTextLayerAvailable = pdfTextLayerAvailable
    }

    /**
     * PDF 页源工厂（导入与打开**共用**）。
     *
     * 放在容器里而不是各处 new：导入与打开必须走同一个实现，
     * 否则会出现"导入时判定是扫描件、打开时又读得出文本层"这种自相矛盾的状态。
     * 它注入进 `InkLeafStore`，再传给 `DocumentImporter`；阅读界面直接用它重开会话。
     */
    val pdfPageSource: dev.inkleaf.core.document.PdfPageSource.Factory =
        dev.inkleaf.core.document.PlatformPdfPageSource.factory(this.context.cacheDir)

    val store: InkLeafStore = InkLeafStore(this.context, pdfPageSourceFactory = pdfPageSource)

    val importer: DocumentImporter get() = store.importer

    private val _settings = MutableStateFlow(ReaderSettings())

    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    /** 首次加载是否完成。完成前界面不应渲染文档（否则会用默认字号排一次版，用户会看到跳变）。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 应用级写作用域。
     *
     * 阅读时长在 `ON_STOP` 时落盘，而那一刻 Compose 的 `rememberCoroutineScope`
     * 可能随界面销毁一起被取消 —— 用界面作用域写会在退出阅读界面的同时丢掉最后一段。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 记一段阅读时长。失败不打扰用户：统计是附加信息，不能因为它写不进去就弹错。 */
    fun recordReading(docId: String, startedAt: Long, endedAt: Long, durationMs: Long) {
        appScope.launch {
            runCatching {
                store.readingSessions.record(docId, startedAt, endedAt, durationMs)
            }.onFailure { e ->
                AppLog.w("Stats", { "阅读时长写入失败 doc=$docId" }, e)
            }
        }
    }

    /**
     * 立刻写一次阅读位置（M36）。
     *
     * 与 [recordReading] 同一个理由，但栽的坑更疼：**退出阅读界面时 ViewModel 被销毁**，
     * `viewModelScope` 随之取消。用 viewModelScope 写"最后一次位置"，那次写入就死在
     * `delay` 里了 —— 表现是"滚了几屏再按返回，重进却回到上一次保存的位置"。
     *
     * 用应用级作用域，写到一半也不会被界面销毁打断。失败只记日志：位置丢一次
     * 是可惜，弹一个错误框更糟。
     */
    fun saveProgressNow(progress: dev.inkleaf.core.model.ReadingProgress) {
        appScope.launch {
            runCatching { store.documents.saveProgress(progress) }
                .onFailure { e -> AppLog.w("Reader", { "阅读位置写入失败 doc=${progress.docId}" }, e) }
        }
    }

    /** 启动时调用：打开数据库、补齐默认设置、读入设置。 */
    suspend fun load() {
        // 打开数据库会触发建表/迁移，绝不能放在主线程 —— 首次启动的表创建
        // 在慢设备上可以到几十毫秒，足够丢一帧。
        store.warmUp()
        val raw = store.settings.all()
        _settings.value = ReaderSettings.from(raw)
        _ready.value = true
    }

    /** 修改设置：先持久化再改内存状态（顺序反过来会在写失败时给出"看起来生效了"的假象）。 */
    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        store.settings.putAll(next.toSettingsMap())
        _settings.value = next
    }
}

/**
 * ViewModel 工厂。
 *
 * 用 lambda 而不是 `when (modelClass)` 分派：分派表会随 ViewModel 增多而增长，
 * 而且每次新增都要改工厂 —— 这个 lambda 版本对"有没有漏注册"是编译期免疫的。
 */
class VmFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V {
        @Suppress("UNCHECKED_CAST")
        return create() as V
    }
}

class InkLeafApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** 应用级作用域。只用于"启动时加载设置"这类必须活过任何界面的工作。 */
    private val appScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLog.enabled = BuildConfig.DEBUG
        AppLog.perfEnabled = BuildConfig.DEBUG
        container = AppContainer(this)
        appScope.launch {
            try {
                container.load()
            } catch (e: Exception) {
                // 启动加载失败不能崩：界面会用默认设置渲染，并在设置页显示存储异常。
                AppLog.e("App", { "启动加载失败" }, e)
            }
        }
    }
}
