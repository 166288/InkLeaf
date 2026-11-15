package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.StrokeAnchor

/**
 * 按笔记增量解析（M43/I1）。
 *
 * ## 它解决的是哪一半的卡
 *
 * 落笔一次 → 库里多一条笔记 → 界面要重新算"每一条笔记现在画在哪"。
 * 而 [InkResolver.resolve] 是**全书重算**：几百条笔记逐条送回
 * [dev.inkleaf.core.anchoring.AnchorResolver]（每条要在候选行里做文本比对），
 * 再把每条笔迹的点按当前行盒变换到文档坐标。
 *
 * 于是"写得越多越卡"是线性的 —— 不是在画的时候卡（那是暂存层，很便宜），
 * 是在**每次抬笔之后那一帧**卡。重度用户写到第 500 条时，每次抬笔要重算 500 条。
 *
 * 这个类只做一件事：把 [InkResolver.resolveOne] 的结果按笔记 id 记住，
 * 只重算**变了的那几条**。落笔一次 = 解析 1 条，而不是 500 条。
 *
 * ## 什么时候必须整个作废
 *
 * [LayoutFacts] 换了（排版变了：字号、行距、边距、换页宽、换字体）。
 * 那时每一条的解析结果都依赖新的行盒，缓存一条都不能留。
 * 用**同一性**（`!==`）而不是相等性判断：`LayoutFacts` 没有 equals，
 * 而"同一份排版"在界面里就是同一个对象 —— 调用方必须把 facts 记住
 * （`remember(layout)`），每次重组新建一份就等于每帧全量重算，
 * 这个缓存会安静地白干。
 *
 * ## 与"就地修改"的冲突
 *
 * [InkResolver.assemble] 里的 PINNED 落位会**就地**平移 `docPoints`。缓存对象
 * 如果直接交过去，第二轮就会在已经平移过的点上再平移一次 —— 笔迹每轮往同一个
 * 方向挪一点，看起来像"自己会漂"。所以交给 assemble 时传
 * `copyPlaceablePoints = true`：只复制参与落位的那几组，其余共享引用。
 */
class InkResolveCache {

    private var facts: LayoutFacts? = null

    private val entries = HashMap<String, Entry>()

    private class Entry(
        val ink: Annotation.Ink,
        val signature: Int,
        val per: InkResolver.PerInk,
    )

    /**
     * 与 [InkResolver.resolve] 同签名、同结果，只是尽量少算。
     *
     * 调用方必须保证：[facts] 在"排版没变"时是**同一个对象**。
     */
    fun resolve(
        inks: List<Annotation.Ink>,
        strokesOf: (String) -> List<StrokeAnchor>,
        brushesOf: (String) -> List<BrushSpec>,
        facts: LayoutFacts,
    ): InkResolution {
        if (facts !== this.facts) {
            this.facts = facts
            entries.clear()
        }
        val spans = facts.blockSpans
        val live = HashSet<String>(inks.size * 2)
        val perInks = ArrayList<InkResolver.PerInk>(inks.size)
        var computed = 0

        for (ink in inks) {
            val stored = strokesOf(ink.id)
            val brushes = brushesOf(ink.id)
            val signature = signatureOf(stored, brushes)
            live += ink.id
            val hit = entries[ink.id]
            val per = if (hit != null && hit.signature == signature && hit.ink == ink) {
                hit.per
            } else {
                computed++
                val fresh = InkResolver.resolveOne(ink, stored, brushes, facts, spans)
                entries[ink.id] = Entry(ink, signature, fresh)
                fresh
            }
            perInks += per
        }

        // 删掉的笔记（删批注、撤销一次落笔）不能一直占着槽位：
        // 它们既不会被命中，也会跟着 facts 一起活到下一次排版。
        entries.keys.retainAll(live)
        lastComputedCount = computed

        return InkResolver.assemble(perInks, facts, copyPlaceablePoints = true)
    }

    /**
     * 上一轮真正重算了几条。给测试与日志用 —— 这个类的正确性只能靠"少算了多少"
     * 来验证，光看结果对不对看不出缓存有没有生效（结果永远应该一样）。
     */
    var lastComputedCount: Int = 0
        private set

    /** 强制作废（例如换了文档）。 */
    fun clear() {
        entries.clear()
        facts = null
        lastComputedCount = 0
    }

    /**
     * 一条笔记"内容指纹"，只覆盖会影响**解析结果**的量。
     *
     * 为什么不用 `ink == cached.ink` 一把梭：笔迹点藏在 `StrokeAnchor` 里，
     * 而它包含 `FloatArray`（equals 按引用比）—— 每次从库里读出来都是新对象，
     * 那样比就永远不等，缓存一辈子不命中。所以点序列单独按**内容**算。
     */
    private fun signatureOf(strokes: List<StrokeAnchor>, brushes: List<BrushSpec>): Int {
        var h = 1
        for (stroke in strokes) {
            h = 31 * h + stroke.strokeId.hashCode()
            h = 31 * h + stroke.localPoints.contentHashCode()
            h = 31 * h + stroke.localPressures.contentHashCode()
            h = 31 * h + (stroke.frozenMatrix?.hashCode() ?: 0)
            h = 31 * h + stroke.brushId.hashCode()
            h = 31 * h + stroke.semantics.hashCode()
        }
        h = 31 * h + brushes.size
        for (brush in brushes) h = 31 * h + brush.hashCode()
        return h
    }
}
