package dev.inkleaf.core.ink

import dev.inkleaf.core.anchoring.AnchorChainFactory
import dev.inkleaf.core.anchoring.AnchorResolver
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 打字型标注（边注、高亮）—— **恰好一条锚点链、没有笔迹**的那一类。
 *
 * 与 [Annotation.Ink] 的区别不是"手打的 vs 手写的"，而是这个：笔迹是**连续**的
 * （一条笔迹行有自己的 em 局部坐标），打字批注是**离散**的（它只有一个锚点，
 * 几何完全由"解析到哪一行"决定）。因此它比笔迹简单一档：不需要冻结几何、
 * 也不可能出现"锚点解不开但墨迹还在"的两条轴（见 [TypedNoteRow.located]）。
 *
 * 只有界面上的称呼不同；解析走的是**同一个** `AnchorResolver`。
 */
enum class TypedNoteKind {
    MARGIN_NOTE,
    HIGHLIGHT,
}

/**
 * 把一条打字批注挂到 [line] 上 —— 这是打字批注**写入方向**的唯一入口。
 *
 * 返回 null 表示这一行没有可引用的文字（空行）：调用方必须当场拒绝，不要写一条
 * "挂上了但下次打开就消失"的记录。判据与落笔、手动重挂一样来自
 * [AnchorChainFactory.forLine]（链的形状只有那一处定义）。
 *
 * @param pageKey 固定版式（PDF）的页键。重排格式传 null：那里没有页，只有字符偏移。
 *        PDF 的页身份存在进度兜底选择器里（`ProgressHint.pageKey`，见 M5 §8），
 *        所以写固定版式的批注时**必须**把它带上，否则"取不回精确位置"的兜底落点会丢。
 */
fun LayoutFacts.chainForLine(line: LineBox, pageKey: String? = null): TextAnchorChain? =
    AnchorChainFactory.forLine(line, lineSource(), document(), pageKey)

/**
 * "笔记"列表面板里的一行打字批注。
 *
 * 与 [InkNoteRow] 是**并列**的两种行，刻意不合并成一个类型：笔迹多出两条轴
 * （[InkNoteRow.drawn] 墨迹在不在屏幕上、[InkNoteRow.hasStrokes] 有没有笔迹可挂），
 * 而打字批注这两条都不存在。硬塞进同一个数据类，就得给[打字批注]编一个
 * "drawn = false" 之类的假值 —— 而界面上那句"这笔暂时画不出来"立刻变成谎言。
 *
 * 判定同样**只能**由 `:core:ink` 产出：它依赖当前排版（[LayoutFacts]），
 * 界面自己算就是两侧各判一遍（M3c §15 记录过的错误模式）。
 */
data class TypedNoteRow(
    val annotationId: String,
    val kind: TypedNoteKind,
    val state: InkState,
    val confidence: Float,
    /** 给用户看的解释，口径与 [InkNoteRow.reason] 完全一致（同一套说法）。 */
    val reason: String,
    /** 用户写下的那句话本身。空内容不会被写进库，因此非空才是常态。 */
    val noteText: String?,
    /** 解析到的那一行原文（过长会截断）；解不开时为 null。 */
    val excerpt: String?,
    /** "跳到原文"用的**全文**偏移；解不开时为 null。 */
    val charOffset: Int?,
    /**
     * 这条批注**当时标在哪句话上**（存在锚点链里的文字）；老数据可能没有。
     *
     * 与 [excerpt] 的区别是"哪来的"：前者来自解析结果，后者来自库里的链。
     * 解不开时它是唯一线索 —— 拿它去搜索，就能找到候选位置（也就是"确认位置"用的那一句）。
     */
    val originalText: String? = null,
) {
    /** 只有解到某一行的批注才有可跳的位置。 */
    val canJump: Boolean get() = charOffset != null

    /**
     * 这条批注现在**说得清它在哪一行**吗。
     *
     * 这是 [InkNoteRow.drawn] 在打字批注上的对应物：打字批注没有墨迹，
     * "出现在页面上"这件事对它而言**就是**"解析到了某一行"（界面按行显示它、
     * 跳转也靠这一行）。所以这里不叫 drawn —— 叫 drawn 会让读代码的人
     * 以为有一个墨迹层要管。
     *
     * 解不开的批注不是"画不出来"，而是"暂时对不上正文的哪一行"：
     * 内容没丢（[noteText] 还在），丢的是它与文字的联系。界面据此选词
     * （说不清位置 → "确认位置"，说得清 → "改挂"）。
     */
    val located: Boolean get() = charOffset != null

    /**
     * 打字批注**永远**可以被改挂 —— 判据是恒真的，但必须写出来。
     *
     * [InkNoteRow.canReanchor] 要问"库里有没有笔迹行可挂"，因为笔迹的几何在笔迹行里，
     * 没有笔迹行就无从挂起。打字批注没有这个前提：锚点链**就是它本身**，
     * 改挂不过是把这一条链换掉（不会动任何几何，因为没有几何）。
     * 所以这里不是"条件恰好成立"，而是"这个动作对它永远可行"。
     */
    val canReanchor: Boolean get() = true
}

/**
 * 把打字批注解析成列表面板要显示的行，顺序与入参一致（界面按状态分组显示，
 * 过滤是显示策略，不是判定结论）。
 *
 * 只处理 [Annotation.MarginNote] 与 [Annotation.Highlight]（恰好一条锚点链的类型）；
 * [Annotation.Ink] 走 [InkResolver]，而 [Annotation.Underline]（一段下划线覆盖多行，
 * 有多个锚点）与 [Annotation.Bookmark]（不绑文本）刻意不走这条路 ——
 * 前者要先回答"一整段下划线在列表里算哪一行"这个产品问题，后者根本不绑文本。
 * 两者目前都没有创建入口，所以不是"漏做"，而是**还没有需要回答的问题**。
 */
fun typedNoteRows(
    notes: List<Annotation>,
    facts: LayoutFacts,
    maxExcerptChars: Int = 60,
): List<TypedNoteRow> {
    val limit = maxExcerptChars.coerceAtLeast(1)
    val rows = ArrayList<TypedNoteRow>()

    for (note in notes) {
        val kind: TypedNoteKind
        val chain: TextAnchorChain
        when (note) {
            is Annotation.MarginNote -> {
                kind = TypedNoteKind.MARGIN_NOTE
                chain = note.anchor
            }

            is Annotation.Highlight -> {
                kind = TypedNoteKind.HIGHLIGHT
                chain = note.anchor
            }

            else -> continue
        }

        val resolution = AnchorResolver.resolve(
            chain = chain,
            currentSourceFingerprint = facts.fingerprint,
            candidates = facts.lines,
            textOf = facts::textOf,
            currentProfile = facts.profile,
            contextOf = facts::textWindow,
        )

        rows += when (resolution) {
            is AnchorResolution.Resolved -> {
                val line = resolution.line
                TypedNoteRow(
                    annotationId = note.id,
                    kind = kind,
                    state = InkState.RESOLVED,
                    confidence = resolution.confidence,
                    reason = AnchorReason.resolved(line.anchor.blockPath, resolution.confidence),
                    noteText = note.noteText,
                    excerpt = excerptOfNote(facts.textOf(line), limit),
                    charOffset = facts.absoluteRangeOf(line)?.first,
                    originalText = storedQuoteOf(chain),
                )
            }

            is AnchorResolution.Ambiguous -> TypedNoteRow(
                annotationId = note.id,
                kind = kind,
                state = InkState.AMBIGUOUS,
                confidence = 0f,
                reason = AnchorReason.ambiguous(resolution.candidates.size),
                noteText = note.noteText,
                excerpt = null,
                charOffset = null,
                originalText = storedQuoteOf(chain),
            )

            is AnchorResolution.Orphaned -> TypedNoteRow(
                annotationId = note.id,
                kind = kind,
                state = InkState.ORPHANED,
                confidence = 0f,
                // 后半句与笔迹**不同**，而且这里不能说成"没有画出来"：
                // 打字批注的内容一直都在（下面就会显示出来），丢的只是它与正文的联系。
                reason = AnchorReason.ORPHANED_CAUSE + "边注本身还在，只是暂时挂不回任何一行。",
                noteText = note.noteText,
                excerpt = null,
                charOffset = null,
                originalText = storedQuoteOf(chain),
            )
        }
    }
    return rows
}

/**
 * 这条批注当时标在哪句话上 —— 取链里的引文，按 [AnchorChainFactory.MAX_EXACT_CHARS] 截断。
 *
 * 与 [InkResolver.MAX_ORIGINAL_TEXT_CHARS]（48）看着像重复，其实是两回事：
 * 那是**面板要显示**的长度，而这里取的是**库里那条引文本身**。
 * 两者一旦合并，会让"引文长度"变成显示参数 —— 而它是锚点的一部分，
 * 显示想改就把库里的锚点改了。
 */
private fun storedQuoteOf(chain: TextAnchorChain): String? =
    chain.textQuote?.exact?.takeIf { it.isNotBlank() }

private fun excerptOfNote(text: String, limit: Int): String? {
    val trimmed = text.trim()
    return when {
        trimmed.isEmpty() -> null
        trimmed.length <= limit -> trimmed
        else -> trimmed.take(limit) + "…"
    }
}
