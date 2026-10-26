package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 锚点链构造器。
 *
 * 职责单一：把"用户在某一行的某个字符区间上做了一笔标注"这件事，
 * 变成一条可持久化的、带降级链的 [TextAnchorChain]。
 *
 * 它**不**负责解析（那是 [AnchorResolver]）也不负责判定语义（那是 InkReflowSemantics）。
 * 分开的理由是：构造只在写入时发生、必须极快且永不失败；
 * 解析在每次打开文档时发生、可以慢、可以判孤儿。两者的失败模型完全不同。
 */
object AnchorChainFactory {

    /** textQuote 的上下文长度。32 个字符足以消歧"他说"这类高频短句。 */
    const val DEFAULT_CONTEXT_CHARS = 32

    /**
     * [forLine] 取不到引文时给用户看的那句话。
     *
     * 放在这里而不是各写入路径自己写：手写笔迹、打字批注、手动重挂三条路径
     * 拒绝的是**同一件事**（这一行没有文字可引）。三处各写一句，用户就要从
     * 三种说法里猜自己遇到的是不是同一个问题。
     */
    const val NO_QUOTE_REASON =
        "这一行没有文字，没有引文可写：挂上去的锚点重排后必然又变成孤儿，所以没有挂。"

    /**
     * exact 的上限。
     *
     * 不是"越长越好"：锚点链会随每条标注写进数据库，用户可能划 3000 字的段落。
     * 256 字符已经远超消歧需要，同时把单条锚点的存储开销钉在常数级。
     */
    const val MAX_EXACT_CHARS = 256

    /**
     * 锚定目标。由渲染层在真实排版结果上给出 —— 这是 L2 精度的来源：
     * 行盒来自布局对象，而不是由字号推算（§5.7）。
     *
     * 两组偏移都要给，因为它们服务不同目的且不能互相推导：
     *   - [blockStartOffset]/[blockEndOffset] 是**块内**偏移，写进结构型选择器，
     *     块内偏移不受"前面插入了多少字符"影响，是重排后最稳的锚；
     *   - [absoluteStart]/[absoluteEnd] 是**全文**偏移，只用来抽取 textQuote 的上下文。
     *     它随文档变化而失效，所以绝不能被当作锚点本身存起来。
     */
    data class Target(
        val blockPath: String,
        val blockStartOffset: Int,
        val blockEndOffset: Int,
        val absoluteStart: Int,
        val absoluteEnd: Int,
        /** 基线在行盒内的相对位置，单位 em。默认 1.0 = 贴着行盒底部。 */
        val baselineOffsetEm: Float = 1.0f,
    )

    /**
     * 构造锚点链。
     *
     * @param normalizedText 归一化后的全文。
     * @param fingerprint 当前文档指纹（必须与 [normalizedText] 同一口径算出来）。
     * @param docRatio 全文进度 0..1，用于兜底选择器。
     * @param pageKey 固定版式格式的页键（PDF 页号 / EPUB spine 序），重排格式传 null。
     * @param nearbyText 兜底提示里给用户看的"大概是这一段"。不传则用 exact 截断。
     */
    fun build(
        normalizedText: String,
        profile: NormalizationProfile,
        fingerprint: String,
        target: Target,
        docRatio: Float,
        pageKey: String? = null,
        contextChars: Int = DEFAULT_CONTEXT_CHARS,
        nearbyText: String? = null,
    ): TextAnchorChain {
        require(fingerprint.isNotBlank()) { "指纹不能为空，否则规则 1（指纹校验）会形同虚设" }
        require(contextChars >= 0) { "contextChars 不能为负：$contextChars" }

        val len = normalizedText.length
        require(target.absoluteStart in 0..len) {
            "absoluteStart=${target.absoluteStart} 越界（0..$len）"
        }
        require(target.absoluteEnd in target.absoluteStart..len) {
            "absoluteEnd=${target.absoluteEnd} 必须落在 absoluteStart..$len"
        }
        require(target.blockStartOffset <= target.blockEndOffset) {
            "块内偏移必须 start <= end，实际 ${target.blockStartOffset}..${target.blockEndOffset}"
        }

        val absStart = target.absoluteStart
        val absEnd = target.absoluteEnd

        val exactFull = normalizedText.substring(absStart, absEnd)
        val exact = if (exactFull.length > MAX_EXACT_CHARS) {
            exactFull.substring(0, MAX_EXACT_CHARS)
        } else {
            exactFull
        }

        val prefix = normalizedText.substring(maxOf(0, absStart - contextChars), absStart)
        val suffix = normalizedText.substring(absEnd, minOf(len, absEnd + contextChars))

        val structural = Selector.Structural(
            blockPath = target.blockPath,
            startOffset = target.blockStartOffset,
            endOffset = target.blockEndOffset,
            baselineOffsetEm = target.baselineOffsetEm,
        )

        // 空选区（例如只点了一下）不生成 textQuote：空的 exact 在匹配时
        // 会命中任意位置（"空串是所有串的子串"），这是最危险的假阳性来源。
        val quote = if (exact.isNotEmpty()) {
            Selector.TextQuote(exact = exact, prefix = prefix, suffix = suffix)
        } else {
            null
        }

        val hint = Selector.ProgressHint(
            docRatio = docRatio.coerceIn(0f, 1f),
            pageKey = pageKey,
            nearbyText = nearbyText ?: exact,
        )

        val selectors = buildList {
            add(structural)
            if (quote != null) add(quote)
            add(hint)
        }

        return TextAnchorChain(
            selectors = selectors,
            normalization = profile,
            sourceFingerprint = fingerprint,
        )
    }

    /**
     * 把"用户选中了**某一行**"这件事变成锚点链 —— 这是"选行 → 链"的**唯一实现**。
     *
     * 三条写入路径都走这里：落笔（[InkAnchorFactory] 的产链调用方）、打字批注
     * （`:core:ink` 的 `LayoutFacts.chainForLine`）、手动重挂（[InkReanchor]）。
     * 抽出来不是为了少写几行，而是因为链的**形状**（结构型 → 引文 → 进度兜底）
     * 一旦出现第二份实现，两份就会慢慢分家：后写的那份总会有人"顺手"改一点，
     * 而症状是"手动挂上去的笔记重开文档就变回孤儿" —— 两侧各自的单元测试都是绿的。
     *
     * 返回 null 表示这一行**没有可引用的文字**（取不到绝对区间，例如空行、零宽行）。
     * 调用方必须当场拒绝并说清原因，不要写一条"看起来挂上了、下次打开又消失"的记录：
     * 没有引文的锚点在重排后必然判不回 `Resolved`（[InkAnchorFactory] 的已知边界 1）。
     *
     * @param pageKey 固定版式格式的页键（PDF 页号）：进度兜底的落点靠它记住"大概是哪一页"。
     *        重排格式传 null —— 那里没有页，只有字符偏移。
     */
    fun forLine(
        target: LineBox,
        source: InkAnchorFactory.LineSource,
        document: InkAnchorFactory.Document,
        pageKey: String? = null,
    ): TextAnchorChain? {
        val absolute = source.absoluteRangeOf(target) ?: return null
        return build(
            normalizedText = document.normalizedText,
            profile = document.profile,
            fingerprint = document.fingerprint,
            target = Target(
                blockPath = target.anchor.blockPath,
                blockStartOffset = target.anchor.startOffset,
                blockEndOffset = target.anchor.endOffset,
                absoluteStart = absolute.first,
                absoluteEnd = absolute.last + 1,
                baselineOffsetEm = target.anchor.baselineOffsetEm,
            ),
            docRatio = document.docRatio,
            pageKey = pageKey,
            // 兜底提示里给用户看的那句话取这一行的文字（截断到 exact 的上限，
            // 与引文本身同一口径）：它只在"连引文都对不上"时才被看到。
            nearbyText = source.textOf(target).take(MAX_EXACT_CHARS),
        )
    }
}
