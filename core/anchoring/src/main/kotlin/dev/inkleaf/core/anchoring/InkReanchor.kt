package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 手动重挂锚点 —— 方案 §6.3.1 里"用户手动确认"那一步的可执行版本。
 *
 * 它要解决的是三态设计里唯一没有出路的一类：`Ambiguous`（多处都可能）与
 * `Orphaned`（找不到原来那一行）。在解析器的硬规则（"解不开就不画"，
 * 见 `:core:ink` 的 `InkResolver`）下这两类笔迹**一个像素都不画**，
 * 于是"看不见"本身就成了用户最先要解决的问题。
 * 重挂是唯一能在**不猜**的前提下把它们救回来的办法：位置由用户给出，
 * 而不是由算法给一个像样的答案。
 *
 * ## 重挂做了什么（以及为什么只有这些）
 *
 * 存下来的每段笔迹有两部分：[StrokeAnchor.localPoints]（行局部 em 坐标）与
 * 一条 [TextAnchorChain]（"这一行 + 行内偏移 + 引文"）。重挂只改后者：
 *
 * - **点一个都不动**。em 坐标的含义是"在这一行的哪个相对位置"，
 *   它属于**行**而不属于页面。用户说"这条笔记应该在那一行"，
 *   我们要做的就是把它接到那一行上，形状原样保留；
 *   反而按新旧行的绝对位置换算会把"用户选中的那一行"这个**唯一确定的信息**丢掉。
 * - [StrokeAnchor.frozenMatrix] 同样原样保留。这是"重挂改的是**指向**，不是**位置**"：
 *   PINNED / FREE 的墨迹本来就固定在这一页的这个位置上（方案 §6.3.3
 *   "墨迹不平移不缩放"），重挂只是给它补一条"它指向哪一行"的锚点。
 *   在这里清掉冻结几何等于**把用户的墨迹挪走** —— 而用户点这一下的目的
 *   恰恰是"让这条看不见的笔记回来"。
 *   （这条与 M3 §18.2 当初写的"一律清空"相反：那时 [StrokeAnchor.frozenMatrix]
 *   还叫 `fallbackDocPoints`，语义是"换算失败时的旧坐标垃圾"，清掉是对的；
 *   M3.1 之后它变成了 PINNED / FREE 的真实几何，清掉就错了。见 M3 §20。）
 * - 锚点链用**当前**文档的口径重建（当前指纹、当前归一化口径）。
 *   这正是孤儿能被救回来的原因：旧链记的是"文件是原来那一份"这个已经为假的前提。
 *
 * ## 不做的事
 *
 * - **不判断"挂得对不对"**：没有相似度门槛、不做自动匹配。用户点哪一行就是哪一行。
 * - **不改语义**（FOLLOWER / PINNED / FREE）：那是"重排时怎么跟随"的分类，
 *   与"接到哪一行"是两件事。
 * - **不碰没有文字的行**：拿不到引文的锚点在重排后必然判不回 `Resolved`
 *   （见 [InkAnchorFactory] 的已知边界 1），所以宁可当场拒绝并说清原因，
 *   也不要写一条"看起来挂上了、下次打开又消失"的记录。
 */
object InkReanchor {

    /** 重挂的结果：要么给出可写库的新记录，要么说清为什么不能挂。 */
    sealed interface Outcome {

        /**
         * 可以写库了。
         *
         * [chain] 是给标注（`Annotation.Ink.anchor`）用的那条链；[strokes] 是新笔迹行，
         * 它们的 `anchor` 与 [chain] 的结构型选择器一致 —— 两处必须同源，
         * 否则"按行检索笔迹"（用 stroke.anchor）与"解析笔记"（用 annotation.anchor）
         * 会给出不同的行。
         */
        data class Done(
            val strokes: List<StrokeAnchor>,
            val chain: TextAnchorChain,
            val line: LineBox,
        ) : Outcome

        /** 拒绝重挂。[reason] 是给用户看的一句话，说清卡在哪一步。 */
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * 把 [strokes] 全部重挂到 [target] 这一行上。
     *
     * @param strokes 这条笔记实际存着的笔迹行（不是它"声明"的那几段 ——
     *        重挂时以实际存在的为准，声明缺失的段在这里无法被凭空补出来）。
     * @param target 用户选中的那一行，来自**当前排版**的行盒。
     * @param source / [document] 当前排版的文本与文档事实，与落笔时用的是同一套
     *        （`LayoutFacts.lineSource()` / `LayoutFacts.document()`），
     *        这样"重挂时引用什么"与"解析时校验什么"永远一致。
     */
    fun attach(
        strokes: List<StrokeAnchor>,
        target: LineBox,
        source: InkAnchorFactory.LineSource,
        document: InkAnchorFactory.Document,
    ): Outcome {
        if (strokes.isEmpty()) {
            return Outcome.Rejected("这条笔记没有笔迹点，挂不上去了。")
        }

        // 能不能挂只判一次：链的构造器（`AnchorChainFactory.forLine`）在没有引文时
        // 返回 null，这里就地转成给用户看的一句拒绝。两处各判一遍正是本工程
        // 吃过两次亏的错误模式（两侧自洽、拼起来错）。
        val chain = AnchorChainFactory.forLine(
            target = target,
            source = source,
            document = document,
        ) ?: return Outcome.Rejected(AnchorChainFactory.NO_QUOTE_REASON)

        val structural = chain.structural
            ?: return Outcome.Rejected("锚点链构造失败：这一行给不出结构型锚点。")

        return Outcome.Done(
            strokes = strokes.map { stroke ->
                stroke.copy(anchor = structural)
            },
            chain = chain,
            line = target,
        )
    }
}
