package dev.inkleaf.app

import dev.inkleaf.core.anchoring.AnchorChainFactory
import dev.inkleaf.core.anchoring.InkReanchor
import dev.inkleaf.core.common.AppLog
import dev.inkleaf.core.ink.DrawableStroke
import dev.inkleaf.core.ink.InkMove
import dev.inkleaf.core.ink.LayoutFacts
import dev.inkleaf.core.ink.PdfTextBands
import dev.inkleaf.core.ink.chainForLine
import dev.inkleaf.core.ink.chainForRange
import dev.inkleaf.core.ink.document
import dev.inkleaf.core.ink.highlightRanges
import dev.inkleaf.core.ink.lineHighlightRange
import dev.inkleaf.core.ink.lineSource
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.Side
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.UnderlineStyle
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * "确认位置 / 改挂"的结果。
 *
 * 刻意把"成没成功"与"给用户看的那句话"分开：[message] 无论成败都必须能读懂，
 * 而界面还需要知道成败来决定**要不要把面板关掉** —— 失败时不该关：
 * 用户多半是要换个位置再挂一次，把面板关了等于让他从头点一遍。
 */
internal data class ReanchorOutcome(
    val attached: Boolean,
    val message: String,
)

/** 新建一条边注的结果。与 [ReanchorOutcome] 同样的分工：成败 + 给用户看的一句话。 */
internal data class AddNoteOutcome(
    val added: Boolean,
    val message: String,
    /**
     * 没写成的原因是"这一处本来就标过"。
     *
     * 单独一个字段（而不是让调用方去认 [message] 的措辞）是因为"按行标"要把
     * 多行的结果合成一句话：重复与写入失败在那句话里的说法不同，靠字符串前缀
     * 判断原因，改一次文案就会错一次。
     */
    val alreadyMarked: Boolean = false,
)

/**
 * 删除 / 撤销删除一条笔记的结果。
 *
 * 与 [AddNoteOutcome] 分开写，只因为字段名必须说实话：把 `added` 拿来形容"删除成功"
 * 会让后面读代码的人愣一下。形状一样是有意的 —— 界面在这几个出口上要的都只有两件事：
 * 库变了没有（据此决定要不要亮出撤销）、给用户看哪句话。
 */
internal data class NoteChangeOutcome(
    val changed: Boolean,
    val message: String,
)

/**
 * 一次"整笔擦除"留存的现场：被擦掉的那条标注、它的笔迹点集与笔刷。
 *
 * 为什么要留着：仓储的 [delete] 会**硬删**笔迹行（文件不能悬空），
 * 所以撤销不能靠 soft-delete 那条路。擦除的撤销是"把这三样东西原样写回去"——
 * 它们必须在删除**之前**就从快照里取出来，删除之后再想拿回来是不可能的。
 */
internal data class ErasedInk(
    val annotation: Annotation.Ink,
    val strokes: List<StrokeAnchor>,
    val brushes: List<BrushSpec>,
)

/** 擦除的结果：成没成、给用户看哪句话、以及撤销要用到的现场。 */
internal data class EraseOutcome(
    val changed: Boolean,
    val message: String,
    val erased: ErasedInk? = null,
)

/**
 * 一次移动的现场。撤销要两头都有：新建的那些要删掉，撤掉的那些要写回去。
 *
 * 与 [ErasedInk] 分开：[ErasedInk] 只有"被删掉的那一份"，而移动多出一份
 * "新写出来的" —— 撤销时先放旧的后删新的，两件事缺一不可。
 */
internal data class MovedInk(
    val createdIds: List<String>,
    val removed: List<ErasedInk>,
)

/** 移动的结果：成没成、给用户看哪句话、以及撤销要用到的现场。 */
internal data class MoveOutcome(
    val changed: Boolean,
    val message: String,
    val moved: MovedInk? = null,
)

/**
 * 一次清掉**多条**笔迹的结果。
 *
 * [erased] 只装真正带点集的那几条：撤销要的是"原样写回去"，
 * 而点集已经丢了的空壳写回去也还是画不出来 —— 那种情况撤不回来要如实说，
 * 不能假装撤得回来。
 */
internal data class BulkEraseOutcome(
    val changedCount: Int,
    val message: String,
    val erased: List<ErasedInk> = emptyList(),
)

/**
 * 笔记的写入出口：**新建打字批注**与**把任意一条笔记挂到用户指定的那一行**。
 *
 * 两类笔记必须从同一个出口走，因为"挂到哪一行"这件事对它们是同一件事，
 * 差别只在写什么：笔迹要把笔迹行的锚点一起重写（[InkReanchor]），
 * 打字批注只要换掉它自己那条链。若分成两个类，界面就得分清手上的笔记是哪一类 ——
 * 而界面手上只有一个 id，它本来就不该知道、也分不清。
 *
 * 与 [RepositoryInkSink] 同一个分工：`:app` 只做"取数据 → 调纯逻辑 → 写回库"，
 * 几何与判定全在 `:core:anchoring`（[InkReanchor] / [AnchorChainFactory]）
 * 与 `:core:ink`（[LayoutFacts]）里。因此这里没有任何坐标运算，
 * 也就可以被整链路测试直接调用 —— 界面里那些触屏操作在本机测不了（没有设备），
 * 但"点下去之后会发生什么"必须测。
 *
 * 类名里的 `Ink` 是在加入打字批注时摘掉的：它当时只服务手写笔迹，
 * 而"改挂"这个动作从一开始就不是墨迹独有的。
 */
internal class NoteWriter(
    private val container: AppContainer,
    private val docId: String,
    private val idFactory: () -> String = { "note-" + UUID.randomUUID() },
    private val highlightIdFactory: () -> String = { "hl-" + UUID.randomUUID() },
    private val underlineIdFactory: () -> String = { "ul-" + UUID.randomUUID() },
    private val bookmarkIdFactory: () -> String = { "bm-" + UUID.randomUUID() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 新建一条边注，挂在 [charOffset] 所在的那一行上。
     *
     * 这是 [Annotation.MarginNote] 的第一个创建入口：此前模型里有它、库里能存它、
     * 笔记界面能列它，就是没有一个地方能**造**出它。
     *
     * @param text 用户写下的那句话。空内容一律拒绝 —— 一条没有内容的边注写进库，
     *        只会在笔记列表里变成一行看不懂的占位，而它永远也不会自己长出内容来。
     * @param charOffset 归一化全文里的偏移，来自**当前阅读位置**
     *        （阅读界面已经知道它了：滚动/翻页时算出的那个字符偏移）。
     * @param facts 当前排版的全部事实，必须是界面正在用的那一份：
     *        行盒来自它，写进链的指纹与归一化口径也来自它。
     */
    suspend fun addMarginNote(
        text: String,
        charOffset: Int,
        facts: LayoutFacts,
    ): AddNoteOutcome {
        val body = text.trim()
        if (body.isEmpty()) {
            return AddNoteOutcome(false, "边注是空的，没有写。")
        }

        val line = facts.lineAtOffset(charOffset)
            ?: return AddNoteOutcome(false, "这里没有可挂的正文行（当前排版里一行都取不到位置）。")

        // 判据与落笔、手动重挂同一个构造器：这一行没有文字（空行）时当场拒绝，
        // 绝不写一条"看着挂上了、下次打开又消失"的记录。
        val chain = facts.chainForLine(line)
            ?: return AddNoteOutcome(false, AnchorChainFactory.NO_QUOTE_REASON)

        val note = Annotation.MarginNote(
            id = idFactory(),
            docId = docId,
            createdAt = now(),
            colorArgb = NOTE_COLOR_ARGB,
            noteText = body,
            anchor = chain,
            side = Side.RIGHT,
        )

        return try {
            container.store.annotations.upsert(note)
            AddNoteOutcome(true, "已把边注挂在：${excerptOf(facts.textOf(line))}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "边注写库失败：${note.id}" }, e)
            AddNoteOutcome(false, "写入失败，这条边注没有存下来。")
        }
    }

    /**
     * 新建一条高亮，标在 [start], [endExclusive) 这一段文字上。
     *
     * 与 [addMarginNote] 的分工：边注挂的是**一行**（用户写的那句话是内容，行是位置），
     * 高亮标的是**一段字符**（它没有内容，标中的那一段就是全部）。因此这里的入参是
     * 一个区间，而不是一个字符偏移 —— 界面手上也正是一个区间：
     * 「高亮这一行」给的是当前那一行的区间，「把这一处标成高亮」给的是搜索命中的区间。
     *
     * 空内容、取不到块、重复标同一处都会被拒绝，而且各有各的说法：
     * 用户要知道自己遇到的是"这里没字"、"这一处存不下来"还是"已经标过了"。
     *
     * @param facts 当前排版的全部事实，必须是界面正在用的那一份：
     *        写进链的块路径、指纹、归一化口径都来自它。
     * @param pageKey 固定版式（PDF）的页键。**必须**由界面带上（`p3` 这种形式）：
     *        文本型 PDF 的锚点链是精确的，但"精确位置取不到"时兜底提示只剩页号；
     *        PDF 没有可重排的字符偏移，丢了页号，兜底就无处可去。
     *        重排格式传 null —— 那里没有页。
     */
    suspend fun addHighlight(
        start: Int,
        endExclusive: Int,
        facts: LayoutFacts,
        pageKey: String? = null,
    ): AddNoteOutcome {
        val text = facts.normalizedText
        if (start < 0 || endExclusive > text.length || endExclusive <= start) {
            return AddNoteOutcome(false, "这一段取不到正文，没有标。")
        }
        val marked = text.substring(start, endExclusive)
        if (marked.isBlank()) {
            // 引文是几个空格的链在重排后会匹配到任意一处空白：这种记录不如不写。
            return AddNoteOutcome(false, "这一段没有文字，没有可标的内容。")
        }

        // 判据与边注、落笔同一个构造器（链的形状只有那一处定义）。
        val chain = facts.chainForRange(start, endExclusive, pageKey)
            ?: return AddNoteOutcome(false, AnchorChainFactory.NO_QUOTE_REASON)

        // 同一段标两遍不会更显眼，只会在笔记面板里多出一条看不出差别的高亮。
        // 删除入口在阅读界面的「本页笔记」里，所以这里只会挡住**手滑**，
        // 不会把用户困住（拒绝的那句话会告诉他去哪儿改）。
        val existing = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取已有高亮失败，无法判断是否重复：$docId" }, e)
            return AddNoteOutcome(false, "读取已有笔记失败了，这一处没有标。")
        }
        val alreadyMarked = highlightRanges(
            existing.annotations.filterIsInstance<Annotation.Highlight>(),
            facts,
        ).any { it.range.first == start && it.range.last == endExclusive - 1 }
        if (alreadyMarked) {
            return AddNoteOutcome(
                added = false,
                message = "这一段已经是高亮了；要改的话，在「本页笔记」里删掉它再标。",
                alreadyMarked = true,
            )
        }

        val highlight = Annotation.Highlight(
            id = highlightIdFactory(),
            docId = docId,
            createdAt = now(),
            // 高亮的颜色**就是**荧光笔的颜色：同一个视觉语言（黄底、半透明、字压在上面），
            // 而且省掉第三个各自写一遍十六进制数的地方。
            colorArgb = BrushSpec.DEFAULT_HIGHLIGHTER.colorArgb,
            anchor = chain,
        )

        return try {
            container.store.annotations.upsert(highlight)
            AddNoteOutcome(true, "已把这一处标成高亮：${excerptOf(marked)}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "高亮写库失败：${highlight.id}" }, e)
            AddNoteOutcome(false, "写入失败，这一处没有标上。")
        }
    }

    /**
     * 把 `[start, endExclusive)` **按行**标成高亮（固定版式 / PDF 的唯一入口）。
     *
     * 为什么不复用 [addHighlight] 一次标完：PDF 一行 = 一块，而一条高亮只有**一条**
     * 锚点链（`Annotation.Highlight.anchor`），链的区间不能跨块。跨行的命中因此必须
     * 拆成"每行一条" —— 与笔迹"一笔跨行 = 好几条标注"是同一条模型约束。
     *
     * 重排格式在软换行处跨行但仍在同一块里，一条链就够，所以它走 [addHighlight]；
     * 两个入口的差别只有"拆不拆"，判据（`lineHighlightRange` / 重复检查 / 写库）
     * 全部复用同一个 [addHighlight]。
     *
     * @param pageKeyOf 由**界面**给出"这个字符偏移在哪一页"（PDF 的 `p3`）。
     *        不放进 `:core:ink` 是因为页是文档层的事实，而这里只有排版事实；
     *        重排格式不传，null 即可。
     */
    suspend fun addHighlightsByLine(
        start: Int,
        endExclusive: Int,
        facts: LayoutFacts,
        pageKeyOf: (Int) -> String? = { null },
    ): AddNoteOutcome {
        if (endExclusive <= start) return AddNoteOutcome(false, "这一段取不到正文，没有标。")
        val lines = PdfTextBands.linesOverlapping(start until endExclusive, facts)
        if (lines.isEmpty()) return AddNoteOutcome(false, "这一段没有可标的正文行。")

        var added = 0
        var already = 0
        var failed = 0
        var failure: String? = null
        for (line in lines) {
            val range = facts.lineHighlightRange(line) ?: continue
            val outcome = addHighlight(range.first, range.last + 1, facts, pageKeyOf(range.first))
            when {
                outcome.added -> added++
                outcome.alreadyMarked -> already++
                else -> {
                    failed++
                    failure = outcome.message
                }
            }
        }

        return when {
            added == 0 -> AddNoteOutcome(false, failure ?: "这几行没有可标的文字。")
            // 部分成功：说清标了几行、另外几行为什么没标 —— 把"写失败"说成"已经是高亮"
            // 会让用户去笔记面板找一条根本不存在的记录。
            failed > 0 -> AddNoteOutcome(true, "已标 $added 行；另有 $failed 行没标上：$failure")
            already > 0 -> AddNoteOutcome(true, "已标 $added 行；另有 $already 行已经是高亮。")
            added == 1 -> AddNoteOutcome(true, "已把这一行标成高亮。")
            else -> AddNoteOutcome(true, "已把 $added 行标成高亮。")
        }
    }

    /**
     * 给 `[start, endExclusive)` 加一条下划线 / 波浪线。
     *
     * 与高亮的三点不同，每一点都是"它是装饰而不是内容"的直接后果：
     *
     *  1. **一条标注装多条链**：`Annotation.Underline.anchors` 是列表，每行一条
     *     （一行一条线才对得上排版）。高亮只有一条链，因为它画的是一段区间。
     *  2. **不查重**：同一行加两条下划线在视觉上只会更深一点，不会像高亮那样
     *     变成两块叠在一起的色块；而重复加一条书签级别的"拒绝"反而让用户困惑。
     *  3. **样式是标注的属性**：`wavy` 存进 [UnderlineStyle] 而不是界面的开关 ——
     *     开关只决定"这一次加哪种"，加完之后它属于这条标注，关掉开关也不会变。
     */
    suspend fun addUnderline(
        start: Int,
        endExclusive: Int,
        facts: LayoutFacts,
        wavy: Boolean = false,
        pageKeyOf: (Int) -> String? = { null },
    ): AddNoteOutcome {
        val text = facts.normalizedText
        if (start < 0 || endExclusive > text.length || endExclusive <= start) {
            return AddNoteOutcome(false, "这一段取不到正文，没有画线。")
        }

        // 与高亮同一条取行口径：重叠即算。不为"半行"单独造一条链，
        // 因为装饰只能整行画（`underlineBands` 的横向范围就是行盒）。
        val lines = facts.lines.filter { line ->
            val range = facts.absoluteRangeOf(line) ?: return@filter false
            range.first < endExclusive && range.last >= start
        }
        if (lines.isEmpty()) return AddNoteOutcome(false, "这一段没有可画线的正文行。")

        val chains = lines.mapNotNull { line ->
            val range = facts.lineHighlightRange(line) ?: return@mapNotNull null
            facts.chainForRange(range.first, range.last + 1, pageKeyOf(range.first))
        }
        if (chains.isEmpty()) return AddNoteOutcome(false, AnchorChainFactory.NO_QUOTE_REASON)

        val underline = Annotation.Underline(
            id = underlineIdFactory(),
            docId = docId,
            createdAt = now(),
            colorArgb = UNDERLINE_COLOR_ARGB,
            anchors = chains,
            style = UnderlineStyle(wavy = wavy),
        )

        return try {
            container.store.annotations.upsert(underline)
            val what = if (wavy) "波浪线" else "下划线"
            AddNoteOutcome(true, "已给${if (chains.size > 1) "这 ${chains.size} 行" else "这一行"}加上$what。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "下划线写库失败：${underline.id}" }, e)
            AddNoteOutcome(false, "写入失败，这条线没有画上。")
        }
    }

    /**
     * 在当前阅读位置放一个书签。
     *
     * 书签是**唯一一种不要求正文的笔记**：它绑的是"读到哪儿"，不是"哪几个字"。
     * 所以锚点链可以为 null（空行、扫描页都能放），兜底提示（页号 + 进度）
     * 则是必须的 —— 没有它的书签只能回到"大概那一页"，而用户点书签的预期是
     * "回到我刚才那一眼"。
     */
    suspend fun addBookmark(
        charOffset: Int,
        facts: LayoutFacts,
        pageKey: String? = null,
    ): AddNoteOutcome {
        val line = facts.lineAtOffset(charOffset)
        val chain = line?.let { facts.chainForLine(it) }
        val hint = Selector.ProgressHint(
            docRatio = facts.docRatio.coerceIn(0f, 1f),
            pageKey = pageKey,
            nearbyText = line?.let { excerptOf(facts.textOf(it)) }.orEmpty(),
        )

        val bookmark = Annotation.Bookmark(
            id = bookmarkIdFactory(),
            docId = docId,
            createdAt = now(),
            colorArgb = BOOKMARK_COLOR_ARGB,
            progressHint = hint,
            anchor = chain,
        )

        return try {
            container.store.annotations.upsert(bookmark)
            AddNoteOutcome(true, "已在这里放上书签。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "书签写库失败：${bookmark.id}" }, e)
            AddNoteOutcome(false, "写入失败，书签没有放上。")
        }
    }

    /**
     * 整笔擦除：删掉**一条**笔迹标注（一笔跨行时它只是其中一段）。
     *
     * ## 入参是**笔迹 id**，不是标注 id
     *
     * 这一点曾经写错过：渲染层（`DrawableStroke.strokeId`）手里只有笔迹 id，
     * 而这里按标注 id 去查，于是每一次擦除都走"这条标注不存在"那条分支，
     * 状态行显示「这一笔已经不在了。」，而界面上那一笔当然还在。
     *
     * 为什么单测没抓到：命中测试只测"点到哪一笔"（纯几何），本方法只测
     * "传标注 id 能不能删"（直连仓储）——**两边的单测都对，接缝是错的**。
     * 设备测试（`InkToolsOnDeviceTest`）第一次跑就把它照出来了（M13 §7.4）。
     *
     * 所以这里由本方法自己做 stroke → annotation 的解析：橡皮的单位是笔，
     * 库里的删除单位是标注，这个翻译只该有一处。一条标注含多段笔迹时一起删
     * （"整笔擦除"的定义就是整条标注）。
     *
     * 与 [deleteTypedNote] 分开写，因为两者对笔迹的语义相反：
     *  - 删除一条打字批注 → 软删除，撤销是 `undelete` 一行；
     *  - 擦掉一笔墨迹 → 仓储会**硬删**笔迹行与 blob（文件不能悬空），
     *    撤销只能靠这里在删除前取出的 [ErasedInk] 原样写回去。
     *
     * 把这两条路合成一个 `delete()` 会得到"撤销之后墨迹没了"的静默数据损失。
     */
    suspend fun eraseInk(strokeId: String): EraseOutcome {
        val snapshot = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取笔记失败，无法擦除：$strokeId" }, e)
            return EraseOutcome(false, "读取笔记失败了，这一笔没有擦掉。")
        }

        // 先按笔迹行找（有行才有几何），再退回标注自带的 strokeIds
        // （点集丢了的情况：那种形状下没有任何笔迹行可查，但标注还在库里）。
        val annotationId = snapshot.strokesByAnnotation.entries
            .firstOrNull { (_, strokes) -> strokes.any { it.strokeId == strokeId } }
            ?.key
            ?: snapshot.annotations.filterIsInstance<Annotation.Ink>()
                .firstOrNull { strokeId in it.strokeIds }
                ?.id
            ?: return EraseOutcome(false, "这一笔已经不在了。")

        val ink = snapshot.annotations.firstOrNull { it.id == annotationId } as? Annotation.Ink
            ?: return EraseOutcome(false, "这一笔已经不在了。")

        val strokes = snapshot.strokesOf(annotationId)
        val brushes = snapshot.brushesOf(annotationId)
        if (strokes.isEmpty()) {
            // 点集已经丢了（旧数据 / 上一次失败的写入）：仍然允许删掉这条空壳，
            // 否则它会永远留在库里，而界面上什么都看不见 —— 一条看得见删不掉的幽灵。
            return try {
                container.store.annotations.delete(annotationId)
                EraseOutcome(true, "这一笔的点已经丢失，已清掉这条空记录。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.e("Ink", { "清除空笔迹失败：$annotationId" }, e)
                EraseOutcome(false, "清理失败，这一笔还在。")
            }
        }

        return try {
            container.store.annotations.delete(annotationId)
            EraseOutcome(
                changed = true,
                message = "已擦掉这一笔（点这里撤销）。",
                erased = ErasedInk(ink, strokes, brushes),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "擦除笔迹失败：$annotationId" }, e)
            EraseOutcome(false, "擦除失败了，这一笔还在。")
        }
    }

    /**
     * 把选中的笔迹挪个位置（S1 套索的后半段）：**写新的 + 撤旧的**，一次落库。
     *
     * 顺序不能反过来。先撤旧的再写新的，中间任何一步失败（写库抛异常、
     * 进程被杀）就是"墨没了" —— 而先写新的再撤旧的，同样的失败点是
     * "多了一份"，用户看得见也撤得掉。宁可多一份，不能少一份。
     *
     * @param strokes 被选中的段，必须是**当前解析出来的** [DrawableStroke]。
     *        它们的点就是此刻画在屏幕上的那些；拿库里的原始记录来平移会
     *        挪错（那是上一次排版下的坐标）。
     * @param facts 当前排版。重新锚定要用它，与落笔时用的是同一套。
     */
    suspend fun moveInk(
        strokes: List<DrawableStroke>,
        dx: Float,
        dy: Float,
        facts: LayoutFacts,
    ): MoveOutcome {
        val snapshot = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取笔记失败，无法移动" }, e)
            return MoveOutcome(false, "读取笔记失败了，没有移动。")
        }

        val plan = InkMove.plan(strokes, dx, dy, facts) { idFactory() }
        if (plan is InkMove.Outcome.Rejected) {
            return MoveOutcome(false, plan.reason)
        }
        val relocations = (plan as InkMove.Outcome.Moved).relocations

        // 旧记录先取全（标注 + 笔迹 + 笔刷），撤销要靠它们原样写回。
        // 取不到的那些直接**不参与这次移动**：写一份新的却不撤掉旧的，
        // 那不是移动，是复制 —— 屏幕上会平白多出一条一模一样的墨。
        val sources = LinkedHashMap<String, ErasedInk>()
        for (relocation in relocations) {
            val annotationId = snapshot.strokesByAnnotation.entries
                .firstOrNull { (_, list) -> list.any { it.strokeId == relocation.fromStrokeId } }
                ?.key ?: continue
            val ink = snapshot.annotations.firstOrNull { it.id == annotationId } as? Annotation.Ink
                ?: continue
            sources[relocation.fromStrokeId] =
                ErasedInk(ink, snapshot.strokesOf(annotationId), snapshot.brushesOf(annotationId))
        }
        if (sources.isEmpty()) {
            return MoveOutcome(false, "这些笔迹在笔记里找不到了，没有移动。")
        }
        val removed = sources.values.distinctBy { it.annotation.id }
        val createdIds = ArrayList<String>()

        return try {
            for (relocation in relocations) {
                val erased = sources[relocation.fromStrokeId] ?: continue
                // 一条旧记录可能切成多条新记录（跨行移动）。它们各自成条，
                // 与落笔时"一段一条"是同一个不变量 —— 否则"按行检索笔迹"
                // 与"解析笔记"会给出不同的行。
                for (segment in relocation.segments) {
                    val source = erased.annotation
                    val id = idFactory()
                    container.store.annotations.saveInk(
                        annotation = Annotation.Ink(
                            id = id,
                            docId = docId,
                            // 沿用原来的创建时间：移动不是"重新写一遍"，
                            // 改了它，笔记清单里的顺序会跟着变，用户会以为笔记丢了。
                            createdAt = source.createdAt,
                            colorArgb = source.colorArgb,
                            tags = source.tags,
                            noteText = source.noteText,
                            anchor = segment.chain,
                            semantics = segment.stroke.semantics,
                            strokeIds = listOf(segment.stroke.strokeId),
                        ),
                        strokes = listOf(segment.stroke),
                        // 整份调色板都带过去：笔刷是按名字配对的（见 InkResolver），
                        // 只挑当前这一支会让"换过笔的老笔迹"在移动后变样。
                        brushes = erased.brushes,
                    )
                    createdIds += id
                }
            }
            // 新的都写成了，才撤旧的。
            for (erased in removed) {
                container.store.annotations.delete(erased.annotation.id)
            }
            MoveOutcome(
                changed = true,
                message = "已移动 ${relocations.size} 笔（点这里撤销）。",
                moved = MovedInk(createdIds = createdIds, removed = removed),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "移动笔迹失败" }, e)
            MoveOutcome(false, "移动失败了，笔迹还在原来的位置。")
        }
    }

    /**
     * 撤销一次移动：把新写的撤掉、把旧的写回去。
     *
     * 与"移动"严格互逆，顺序同样是先写后撤 —— 中间失败留下的是两份墨
     * （看得见、可以再擦），而不是零份。
     */
    suspend fun undoMove(moved: MovedInk): NoteChangeOutcome = try {
        for (erased in moved.removed) {
            container.store.annotations.saveInk(erased.annotation, erased.strokes, erased.brushes)
        }
        for (id in moved.createdIds) {
            container.store.annotations.delete(id)
        }
        NoteChangeOutcome(true, "已撤销，笔迹回到原来的位置。")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        AppLog.e("Ink", { "撤销移动失败" }, e)
        NoteChangeOutcome(false, "撤销失败了，笔迹没有回到原位。")
    }

    /**
     * 撤销一次整笔擦除：把删除前取出的标注、笔迹与笔刷原样写回去。
     *
     * 走 `saveInk` 而不是 `restore`：标注行已经被硬删了（`delete` 对笔迹是
     * 软删标注 + 硬删笔迹行），`undelete` 只能找回一行没有墨迹的空壳。
     * `saveInk` 是 upsert，会把标注行与笔迹行一起重建 —— 这正是"原样写回"。
     */
    suspend fun restoreErasedInk(erased: ErasedInk): NoteChangeOutcome = try {
        container.store.annotations.saveInk(
            annotation = erased.annotation,
            strokes = erased.strokes,
            brushes = erased.brushes,
        )
        NoteChangeOutcome(true, "已撤销，这一笔回来了。")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        AppLog.e("Ink", { "撤销擦除失败：${erased.annotation.id}" }, e)
        NoteChangeOutcome(false, "撤销失败了，这一笔没有回来。")
    }

    /**
     * 一键清掉一批**画不出来**的笔迹。
     *
     * ## 为什么需要它
     *
     * 笔迹画不出来（锚点对不上）时，界面上会留一条"有 N 条暂时对不上正文"的提示。
     * 在此之前用户能做的只有"确认位置"（把每一条挂回去）—— 一条一条点，
     * 而有些笔迹其实他早就不想要了，只是删不掉：面板里说过"手写笔迹不在这条
     * 清单里删"。于是这些数据永远留在库里，每次打开都提醒他一次。
     *
     * ## 与 [eraseInk] 的关系
     *
     * 同一个删除动作（硬删笔迹行），差别只在入口：那个从**笔迹 id** 出发（橡皮），
     * 这个从**标注 id** 出发（面板里的一行就是一条标注）。这里不复用 [eraseInk]，
     * 因为它要按 strokeId 反查标注、每一条都重读一次快照 —— 清 30 条就是 30 次全表读。
     *
     * ## 为什么要返回现场
     *
     * 删除是不可逆的（笔迹文件不能悬空，仓储会硬删）。这些笔迹**现在**画不出来，
     * 但用户可能只是暂时换了字号/版式 —— 撤销窗口里必须能把它们原样放回去。
     */
    suspend fun eraseInkAnnotations(annotationIds: List<String>): BulkEraseOutcome {
        val ids = annotationIds.distinct()
        if (ids.isEmpty()) return BulkEraseOutcome(0, "没有可以清除的笔迹。")

        val snapshot = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取笔记失败，无法批量清除：${ids.size} 条" }, e)
            return BulkEraseOutcome(0, "读取笔记失败了，没有清除任何东西。")
        }

        val erased = ArrayList<ErasedInk>(ids.size)
        var deleted = 0
        var failed = 0
        for (id in ids) {
            val ink = snapshot.annotations.firstOrNull { it.id == id } as? Annotation.Ink
            if (ink == null) {
                // 快照里没有：可能已经被别处删掉了。不算失败，也不算清掉。
                continue
            }
            val strokes = snapshot.strokesOf(id)
            val brushes = snapshot.brushesOf(id)
            try {
                container.store.annotations.delete(id)
                deleted += 1
                if (strokes.isNotEmpty()) erased += ErasedInk(ink, strokes, brushes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.e("Ink", { "批量清除失败：$id" }, e)
                failed += 1
            }
        }

        val message = buildString {
            append("已清除 $deleted 条画不出来的笔迹。")
            if (failed > 0) append("另有 $failed 条没能删掉。")
        }
        return BulkEraseOutcome(
            changedCount = deleted,
            message = message,
            erased = erased,
        )
    }

    /**
     * 撤销一次批量清除：把每一条现场原样写回去，逐条进行。
     *
     * 一条失败不影响其它的 —— 撤销是"尽量把东西还给用户"，不是一次原子事务。
     * 但失败要数出来并说出来，否则用户以为全回来了。
     */
    suspend fun restoreErasedInkBatch(erased: List<ErasedInk>): NoteChangeOutcome {
        var restored = 0
        var failed = 0
        for (item in erased) {
            try {
                container.store.annotations.saveInk(
                    annotation = item.annotation,
                    strokes = item.strokes,
                    brushes = item.brushes,
                )
                restored += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.e("Ink", { "撤销批量清除失败：${item.annotation.id}" }, e)
                failed += 1
            }
        }
        return NoteChangeOutcome(
            changed = restored > 0,
            message = if (failed == 0) {
                "已撤销，$restored 条笔迹回来了。"
            } else {
                "撤销了 $restored 条，另有 $failed 条没有回来。"
            },
        )
    }

    /**
     * 删掉一条**打字批注**（边注 / 高亮 / 下划线 / 书签）。
     *
     * 只接受打字批注，不是"暂时只做了这一种"：手写笔迹在库里是按行分段的
     * （一笔跨行 = 好几条标注），按一条删等于只擦掉一笔的一部分 ——
     * 而"部分擦除"与"整笔擦除"是两件不同的功能（见方案 §6.5 的橡皮）。
     * 整笔擦除走 [eraseInk]，所以这个出口把笔迹挡在外面：
     * 界面也就不必自己判断手上那一条是墨迹还是文字，类型判据只在这里有一份。
     *
     * 删除是软删除（[dev.inkleaf.core.data.AnnotationRepository.delete]），
     * 因此界面可以接一个撤销 —— [NoteChangeOutcome.changed] 正是"要不要亮出撤销"的判据。
     */
    suspend fun deleteTypedNote(annotationId: String): NoteChangeOutcome {
        val snapshot = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取笔记失败，无法删除：$annotationId" }, e)
            return NoteChangeOutcome(false, "读取笔记失败了，这一条没有删。")
        }

        val note = snapshot.annotations.firstOrNull { it.id == annotationId }
            ?: return NoteChangeOutcome(false, "这条笔记已经不在了。")

        val label = when (note) {
            is Annotation.MarginNote -> "边注"
            is Annotation.Highlight -> "高亮"
            is Annotation.Underline -> "下划线"
            is Annotation.Bookmark -> "书签"
            is Annotation.Ink -> return NoteChangeOutcome(
                changed = false,
                message = "手写笔迹不从这里删：一笔跨行时是几段标注，按段删只会擦掉一笔的一部分。" +
                    "要擦笔迹请切到橡皮。",
            )
        }

        return try {
            container.store.annotations.delete(annotationId)
            NoteChangeOutcome(true, "已删除这条${label}（点这里撤销）。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "删除笔记失败：$annotationId" }, e)
            NoteChangeOutcome(false, "删除失败了，这条${label}还在。")
        }
    }

    /**
     * 撤销一次删除 —— [deleteTypedNote] 的逆操作。
     *
     * 入参只可能是 [deleteTypedNote] 成功过的 id：那个出口已经把笔迹挡在外面，
     * 而笔迹的撤销救不回墨迹（删除会硬删笔迹行，见仓储的 `restore`），
     * 所以这里不再判一次类型 —— 不是图省事，是"类型判据只有一处"。
     */
    suspend fun restoreTypedNote(annotationId: String): NoteChangeOutcome = try {
        if (container.store.annotations.restore(annotationId)) {
            NoteChangeOutcome(true, "已撤销删除，那条笔记回来了。")
        } else {
            NoteChangeOutcome(false, "这条笔记已经不在删除状态里，没有可撤销的。")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        AppLog.e("Ink", { "撤销删除失败：$annotationId" }, e)
        NoteChangeOutcome(false, "撤销失败了，这条笔记没有回来。")
    }

    /**
     * 把 [annotationId] 这条笔记挂到 [charOffset] 所在的那一行上。
     *
     * 笔迹与打字批注在这里分岔（笔迹要多重写一步笔迹行），但**拒绝的理由与成功的说法
     * 是同一套**：用户在同一个面板里点同一个按钮，不该因为笔记类型不同
     * 而看到两种措辞（那会让他以为遇到了两种毛病）。
     *
     * @param charOffset 归一化全文里的偏移（搜索命中的位置就是它）。
     * @param facts 当前排版的全部事实。**必须是界面正在用的那一份**：
     *        行盒来自它，重挂写进的指纹/口径也来自它。
     */
    suspend fun attachToCharOffset(
        annotationId: String,
        charOffset: Int,
        facts: LayoutFacts,
    ): ReanchorOutcome {
        val snapshot = try {
            container.store.annotations.listByDoc(docId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLog.e("Ink", { "读取笔记失败，无法重挂：$annotationId" }, e)
            return ReanchorOutcome(false, "读取这条笔记失败了，没能挂上去。")
        }

        // 按 id 在**全部**标注里找，而不是只找笔迹：这条路径现在也服务边注与高亮。
        // 只找笔迹会让"给边注改挂"得到"这条笔记已经不在了" —— 一句不实的提示。
        val note = snapshot.annotations.firstOrNull { it.id == annotationId }
            ?: return ReanchorOutcome(false, "这条笔记已经不在了（可能刚被撤销）。")

        val line = facts.lineAtOffset(charOffset)
            ?: return ReanchorOutcome(false, "这里没有可挂的正文行（当前排版里一行都取不到位置）。")

        if (note is Annotation.Ink) {
            return attachInk(note, snapshot.strokesOf(annotationId), snapshot.brushesOf(annotationId), line, facts)
        }

        // 打字批注：锚点链**就是**它的全部几何，所以改挂只换这一条链
        // （没有笔迹行、没有冻结坐标要跟着写）。与新建边注同一个构造器。
        val chain = facts.chainForLine(line)
            ?: return ReanchorOutcome(false, AnchorChainFactory.NO_QUOTE_REASON)

        return when (note) {
            is Annotation.MarginNote -> attachTyped(note.copy(anchor = chain), line, facts)
            is Annotation.Highlight -> attachTyped(note.copy(anchor = chain), line, facts)
            // 下划线是"每行一条链"的集合：改挂到一行之后只剩一条链 ——
            // 这不是丢数据，而是"下划线现在只盖这一行"的字面意思。
            is Annotation.Underline -> attachTyped(note.copy(anchors = listOf(chain)), line, facts)
            // 书签的位置由**进度提示**决定，链只是"这一行大概在哪"的补充。
            // 改挂时两者都要更新，否则会出现"链指向新行、进度仍指旧处"的分裂。
            is Annotation.Bookmark -> attachTyped(
                note.copy(
                    anchor = chain,
                    progressHint = note.progressHint.copy(
                        docRatio = facts.docRatio.coerceIn(0f, 1f),
                        nearbyText = excerptOf(facts.textOf(line)),
                    ),
                ),
                line,
                facts,
            )
        }
    }

    /**
     * 重挂一条手写笔迹：笔迹行的锚点必须与标注的锚点**同源**，
     * 否则"按行检索笔迹"（用 `stroke.anchor`）与"解析笔记"（用 `annotation.anchor`）
     * 会给出不同的行（见 [InkReanchor.Outcome.Done]）。
     */
    private suspend fun attachInk(
        ink: Annotation.Ink,
        strokes: List<StrokeAnchor>,
        brushes: List<BrushSpec>,
        line: LineBox,
        facts: LayoutFacts,
    ): ReanchorOutcome {
        val outcome = InkReanchor.attach(
            strokes = strokes,
            target = line,
            source = facts.lineSource(),
            document = facts.document(),
        )

        return when (outcome) {
            is InkReanchor.Outcome.Rejected -> ReanchorOutcome(false, outcome.reason)

            is InkReanchor.Outcome.Done -> try {
                container.store.annotations.saveInk(
                    // strokeIds 一律按**实际存在的笔迹**重写：重挂是"把声明与实际对上"
                    // 的天然时机，留着旧声明会让"有 N 段笔迹点缺失"这类提示一直挂着。
                    annotation = ink.copy(
                        anchor = outcome.chain,
                        strokeIds = outcome.strokes.map { it.strokeId },
                    ),
                    strokes = outcome.strokes,
                    brushes = brushes,
                )
                ReanchorOutcome(true, attachedMessage(facts.textOf(outcome.line)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.e("Ink", { "重挂笔记写库失败：${ink.id}" }, e)
                ReanchorOutcome(false, "写入失败，这条笔记还在原处。")
            }
        }
    }

    /**
     * 重挂一条打字批注：**只换链**，一步写完。
     *
     * [AnnotationRepository.upsert][dev.inkleaf.core.data.AnnotationRepository.upsert]
     * 按类型分派（边注与高亮都在它认识的行里），所以这里不需要新增任何仓储方法。
     */
    private suspend fun attachTyped(
        note: Annotation,
        line: LineBox,
        facts: LayoutFacts,
    ): ReanchorOutcome = try {
        container.store.annotations.upsert(note)
        ReanchorOutcome(true, attachedMessage(facts.textOf(line)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        AppLog.e("Ink", { "重挂边注写库失败：${note.id}" }, e)
        ReanchorOutcome(false, "写入失败，这条笔记还在原处。")
    }

    private fun attachedMessage(text: String): String {
        val excerpt = excerptOf(text)
        return if (excerpt.isEmpty()) {
            "已把这条笔记挂到这一行。"
        } else {
            "已把这条笔记挂到：$excerpt"
        }
    }

    private fun excerptOf(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= EXCERPT_CHARS) trimmed else trimmed.take(EXCERPT_CHARS) + "…"
    }

    private companion object {
        /** 结果提示里的摘录长度：这句话是给用户确认"挂对了没有"用的，太长反而看不清。 */
        const val EXCERPT_CHARS = 24

        /**
         * 边注的颜色。
         *
         * v1 里边注只以**文字**出现在笔记面板里（版式内的边注渲染还没做），
         * 所以这个值目前不影响任何像素；先钉一个中性色，等有渲染时统一交给样式管理。
         */
        const val NOTE_COLOR_ARGB = 0xFFFFC107.toInt()

        /**
         * 下划线的颜色：靛蓝。
         *
         * 不用墨黑：下划线要能在一整页黑字里**一眼认出"这是我划的"**，
         * 同色会与正文的笔画混在一起；也不用高亮的琥珀 —— 那是"涂了一片"的语言，
         * 与"划了一条线"必须能区分开。
         */
        const val UNDERLINE_COLOR_ARGB = 0xFF1F5FA8.toInt()

        /** 书签的颜色：暖橙。只出现在笔记面板的标签上，不画在版式里。 */
        const val BOOKMARK_COLOR_ARGB = 0xFFFF8F3C.toInt()
    }
}
