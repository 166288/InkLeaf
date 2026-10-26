package dev.inkleaf.core.data

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.HighlightRenderMode
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.InkRepresentation
import dev.inkleaf.core.model.LineEnding
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.Side
import dev.inkleaf.core.model.SpacePolicy
import dev.inkleaf.core.model.TextAnchorChain
import dev.inkleaf.core.model.UnicodeForm
import dev.inkleaf.core.model.UnderlineStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 持久化用的 DTO 层。
 *
 * ## 为什么不直接给 `:core:model` 加 `@Serializable`
 *
 * 因为**磁盘格式不应该随模型重构而改变**。把注解直接贴在领域模型上，
 * 意味着改一个字段名、拆一个类，就可能让历史上所有的笔记读不出来。
 * DTO 在这里是一层"刻意的重复"：它把 wire format 钉死，
 * 让模型可以自由演进，转换错误也集中在一处、可以被单测覆盖。
 *
 * 这也让 `:core:model` 继续**零依赖**（连序列化库都不需要）。
 *
 * ## 为什么不用多态序列化
 *
 * 多态 JSON 需要 `SerializersModule` 注册，且反序列化时的类名解析
 * 会成为"未来某个类改名 → 旧数据读不出来"的隐蔽风险。
 * 这里改用**扁平 DTO + 显式 type 字段**：每种选择器一个可空字段集，
 * 解析失败就明确返回 null。啰嗦，但可读、可测、不会在两年后突然失效。
 */
object AnchorCodec {

    /**
     * 解析器本身刻意不宽容：`ignoreUnknownKeys = true` 只用于"未来加了字段"这一种情况
     * （丢字段不会导致定位错误）；而**类型/取值不认识时一律返回 null**，
     * 因为那是"坐标系变了"，必须走孤儿流程而不是猜一个默认值。
     */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = false
    }

    fun encode(chain: TextAnchorChain): String =
        json.encodeToString(AnchorChainDto.serializer(), AnchorChainDto.of(chain))

    fun decode(text: String?): TextAnchorChain? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(AnchorChainDto.serializer(), text).toDomain()
        }.getOrNull()
    }
}

// ── 选择器 ───────────────────────────────────────────────────────────────────

@Serializable
data class SelectorDto(
    val type: String,
    val blockPath: String? = null,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val baselineOffsetEm: Float = 1f,
    val exact: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val docRatio: Float = 0f,
    val pageKey: String? = null,
    val nearbyText: String = "",
) {
    fun toDomain(): Selector? = when (type) {
        TYPE_STRUCTURAL -> {
            val path = blockPath ?: return null
            if (startOffset < 0 || endOffset < startOffset) return null
            Selector.Structural(path, startOffset, endOffset, baselineOffsetEm)
        }

        TYPE_TEXT_QUOTE -> {
            val e = exact ?: return null
            Selector.TextQuote(e, prefix ?: "", suffix ?: "")
        }

        TYPE_PROGRESS -> Selector.ProgressHint(
            docRatio = docRatio.coerceIn(0f, 1f),
            pageKey = pageKey,
            nearbyText = nearbyText,
        )

        else -> null
    }

    companion object {
        const val TYPE_STRUCTURAL = "structural"
        const val TYPE_TEXT_QUOTE = "textQuote"
        const val TYPE_PROGRESS = "progressHint"

        fun of(selector: Selector): SelectorDto = when (selector) {
            is Selector.Structural -> SelectorDto(
                type = TYPE_STRUCTURAL,
                blockPath = selector.blockPath,
                startOffset = selector.startOffset,
                endOffset = selector.endOffset,
                baselineOffsetEm = selector.baselineOffsetEm,
            )

            is Selector.TextQuote -> SelectorDto(
                type = TYPE_TEXT_QUOTE,
                exact = selector.exact,
                prefix = selector.prefix,
                suffix = selector.suffix,
            )

            is Selector.ProgressHint -> SelectorDto(
                type = TYPE_PROGRESS,
                docRatio = selector.docRatio,
                pageKey = selector.pageKey,
                nearbyText = selector.nearbyText,
            )
        }
    }
}

// ── 归一化口径 ───────────────────────────────────────────────────────────────

@Serializable
data class NormalizationProfileDto(
    val version: Int,
    val profileId: String,
    val unicodeForm: String,
    val spacePolicy: String,
    val lineEnding: String,
    val stripBom: Boolean,
    val trimTrailing: Boolean,
    val caseFold: Boolean,
    val stripPunctuation: Boolean,
) {
    /**
     * 任何一项读不出来就返回 null。
     *
     * 这里**绝对不能**回退到默认口径：口径就是坐标系，换一个口径去解释旧锚点
     * 会让笔记整体静默偏移。宁可让这条标注显示为"无法读取"。
     */
    fun toDomain(): NormalizationProfile? {
        val unicode = enumOrNull<UnicodeForm>(unicodeForm) ?: return null
        val space = enumOrNull<SpacePolicy>(spacePolicy) ?: return null
        val lineEnd = enumOrNull<LineEnding>(lineEnding) ?: return null
        if (profileId.isBlank()) return null
        return NormalizationProfile(
            version = version,
            unicodeForm = unicode,
            spacePolicy = space,
            lineEnding = lineEnd,
            stripBom = stripBom,
            trimTrailing = trimTrailing,
            caseFold = caseFold,
            stripPunctuation = stripPunctuation,
            profileId = profileId,
        )
    }

    companion object {
        fun of(profile: NormalizationProfile) = NormalizationProfileDto(
            version = profile.version,
            profileId = profile.profileId,
            unicodeForm = profile.unicodeForm.name,
            spacePolicy = profile.spacePolicy.name,
            lineEnding = profile.lineEnding.name,
            stripBom = profile.stripBom,
            trimTrailing = profile.trimTrailing,
            caseFold = profile.caseFold,
            stripPunctuation = profile.stripPunctuation,
        )
    }
}

// ── 锚点链 ───────────────────────────────────────────────────────────────────

@Serializable
data class AnchorChainDto(
    val format: Int = FORMAT,
    val selectors: List<SelectorDto>,
    val normalization: NormalizationProfileDto,
    val sourceFingerprint: String,
) {
    fun toDomain(): TextAnchorChain? {
        if (format != FORMAT) return null
        if (sourceFingerprint.isBlank()) return null
        val profile = normalization.toDomain() ?: return null

        val decoded = ArrayList<Selector>(selectors.size)
        for (dto in selectors) {
            // 有一个选择器读不出来就整条链作废：降级链的价值在于"每一级都是真的"，
            // 悄悄跳过读不出来的那一级，等于伪造了降级路径。
            val selector = dto.toDomain() ?: return null
            decoded.add(selector)
        }
        if (decoded.isEmpty()) return null
        if (decoded.last() !is Selector.ProgressHint) return null

        return runCatching {
            TextAnchorChain(decoded, profile, sourceFingerprint)
        }.getOrNull()
    }

    companion object {
        /** wire format 版本。不认识的版本一律判为不可读（显式失败点）。 */
        const val FORMAT = 1

        fun of(chain: TextAnchorChain) = AnchorChainDto(
            format = FORMAT,
            selectors = chain.selectors.map { SelectorDto.of(it) },
            normalization = NormalizationProfileDto.of(chain.normalization),
            sourceFingerprint = chain.sourceFingerprint,
        )
    }
}

// ── 样式 ─────────────────────────────────────────────────────────────────────

@Serializable
data class HighlightStyleDto(
    val renderMode: String,
    val snapCoverageThreshold: Float,
    val bleed: Float,
) {
    fun toDomain(): HighlightStyle? {
        val mode = enumOrNull<HighlightRenderMode>(renderMode) ?: return null
        return HighlightStyle(mode, snapCoverageThreshold, bleed)
    }

    companion object {
        fun of(style: HighlightStyle) = HighlightStyleDto(
            renderMode = style.renderMode.name,
            snapCoverageThreshold = style.snapCoverageThreshold,
            bleed = style.bleed,
        )
    }
}

@Serializable
data class UnderlineStyleDto(
    val thicknessEm: Float,
    val baselineOffsetEm: Float,
    val wavy: Boolean,
) {
    fun toDomain(): UnderlineStyle = UnderlineStyle(thicknessEm, baselineOffsetEm, wavy)

    companion object {
        fun of(style: UnderlineStyle) = UnderlineStyleDto(style.thicknessEm, style.baselineOffsetEm, style.wavy)
    }
}

/**
 * 笔刷的持久化表示。
 *
 * 必须自己存：`ink-storage` 只序列化 `StrokeInputBatch`，**不序列化 Brush**（§7.3）。
 * 只存笔迹不存笔刷，历史笔记就会以"默认黑色细笔"重绘，用户会以为颜色丢了。
 */
@Serializable
data class BrushSpecDto(
    val name: String,
    val colorArgb: Int,
    val widthEm: Float,
    val pressureEnabled: Boolean,
    val pressureScaleMax: Float,
    val lowLatency: Boolean,
    /**
     * 叠印笔（荧光笔）标志。**只有它有默认值**：这一档是后加的（M25 S2），
     * 而磁盘上已经躺着没有这个字段的旧档案。kotlinx.serialization 读到缺字段时
     * 要么用默认值、要么抛异常 —— 给默认值，旧笔记的笔刷照样读得出来，
     * 而它们本来也不可能是荧光笔（那时还没有荧光笔）。
     *
     * 反过来**不能**给其它字段加默认值：那会让"档案里少写了笔宽"变成
     * "静默画成某种宽度"，而这种错最难查 —— 画得出来，只是画错了。
     */
    val overprint: Boolean = false,
) {
    fun toDomain(): BrushSpec = BrushSpec(
        name = name,
        colorArgb = colorArgb,
        widthEm = widthEm,
        pressureEnabled = pressureEnabled,
        pressureScaleMax = pressureScaleMax,
        lowLatency = lowLatency,
        overprint = overprint,
    )

    companion object {
        fun of(brush: BrushSpec) = BrushSpecDto(
            name = brush.name,
            colorArgb = brush.colorArgb,
            widthEm = brush.widthEm,
            pressureEnabled = brush.pressureEnabled,
            pressureScaleMax = brush.pressureScaleMax,
            lowLatency = brush.lowLatency,
            overprint = brush.overprint,
        )
    }
}

// ── 标注的"子类特有字段" ─────────────────────────────────────────────────────

/**
 * 标注各行共用的列之外的字段都放这里（一列 JSON）。
 *
 * 好处是新增标注子类不需要改表结构（`ALTER TABLE` 在移动端是做迁移风险的来源）；
 * 代价是失去列级约束 —— 用 DTO 的可空字段 + 显式校验来补。
 */
@Serializable
data class AnnotationExtraDto(
    val semantics: String? = null,
    val representation: String? = null,
    val strokeIds: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
    val side: String? = null,
    val highlightStyle: HighlightStyleDto? = null,
    val underlineStyle: UnderlineStyleDto? = null,
    /**
     * 该标注用到的笔刷调色板。
     *
     * 是列表而不是单个：同一段笔迹可以由两种颜色组成（先黑笔勾画、再荧光笔标记），
     * 而 `ink-storage` 不序列化 Brush，所以必须在这里自带。
     * 只存一个 brush 会让多笔刷标注在重新打开后整体变成同一种颜色。
     */
    val brushes: List<BrushSpecDto> = emptyList(),
    /** 书签没有锚点链，它的定位信息就是这条进度兜底。 */
    val progressHint: SelectorDto? = null,
    /** 下划线是离散的：一段下划线覆盖多行，每行一条锚点链。 */
    val multiAnchors: List<AnchorChainDto> = emptyList(),
) {
    fun semanticsOrNull(): InkReflowSemantics? = semantics?.let { enumOrNull(it) }
    fun representationOrNull(): InkRepresentation? = representation?.let { enumOrNull(it) }
    fun sideOrNull(): Side? = side?.let { enumOrNull(it) }
}

/** 标注的持久化类型判别。与 `:core:model` 的 sealed 子类一一对应。 */
enum class AnnotationType {
    HIGHLIGHT, INK, MARGIN_NOTE, UNDERLINE, BOOKMARK;

    companion object {
        fun of(annotation: Annotation): AnnotationType = when (annotation) {
            is Annotation.Highlight -> HIGHLIGHT
            is Annotation.Ink -> INK
            is Annotation.MarginNote -> MARGIN_NOTE
            is Annotation.Underline -> UNDERLINE
            is Annotation.Bookmark -> BOOKMARK
        }
    }
}

/**
 * 泛型枚举反查：不认识的名字返回 null，绝不回退到默认值。
 *
 * `internal` 而不是 `private`：[Daos.kt] 的行映射也要用同一条规则。
 * 这类"默认值回退"的诱惑在存储层特别大（"读不出来就用默认的"），
 * 而它恰恰是把"数据损坏"伪装成"数据正常"的主力。
 */
internal inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
