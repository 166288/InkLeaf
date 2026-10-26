package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Selector

/**
 * 置信度打分。全部为纯函数，可穷举测试。
 *
 * 打分口径的设计原则：**分数必须可解释**。用户看到"这条笔记需要确认"时，
 * 我们能说清是哪一路证据弱了（结构路径没命中 / 文本变了 / 命中的文本太短），
 * 而不是甩出一个来路不明的 0.73。
 */
object ConfidenceScorer {

    /**
     * TextQuote 单独命中时的分数上限。
     *
     * 这个常量是**安全边界**，不是调参旋钮：它必须严格小于
     * [AnchorResolution.CONFIDENT_THRESHOLD]，从而在类型层面保证
     * "只靠文本查找命中的锚点永远不可能被自动接受"。
     */
    const val TEXT_QUOTE_ONLY_CEILING = 0.85f

    /**
     * 命中的 exact 短于该长度时降权：短串在全文里必然遍地都是。
     *
     * ★ 这个降权**只在上下文没法校验时**才用（见 [structuralConsistency]）。
     * 它的理由是"证据不足"，而不是"文本本身可疑"—— 这两者必须分清，
     * 否则会退化成"凡短行上的笔记都永远需要人工确认"（而按本工程的硬规则
     * `Ambiguous` 的笔迹**不画**，于是用户看到的是"我写的字不见了"）。
     */
    private const val SHORT_NEEDLE_LENGTH = 8

    private const val PREFIX_MISMATCH_PENALTY = 0.15f
    private const val SUFFIX_MISMATCH_PENALTY = 0.15f
    private const val SHORT_NEEDLE_PENALTY = 0.2f

    /**
     * 结构路径命中之后，用 TextQuote 校验"这一行还是不是原来那一行"。
     *
     * 返回 0 表示校验不通过（该行文本里找不到 exact）。返回 0 时调用方
     * 必须降级到下一路选择器，而不是把这次结构命中当成成功。
     *
     * @param actualText 该行的文本；若 [checkContext] 为 true，则必须是
     *        **带前后上下文的文本窗口**（见 [AnchorResolver.resolve] 的 `contextOf`）。
     * @param checkContext 是否有资格校验 prefix/suffix。**这不是"宽容一点"**：
     *        引文的 prefix/suffix 是从**全文**上下文里取的（见
     *        [AnchorChainFactory.build]），如果拿"只有这一行文字"去校验它们，
     *        结果必然是"不匹配"，于是一条好好的锚点会因为**校验方式**而
     *        被固定扣掉 0.15 分、永远达不到自动接受的阈值 —— 这会退化成
     *        "所有笔记都要用户确认一遍"。传 false 表示"没有证据可用来扣分"，
     *        而不是"视为匹配"。
     *
     * ## 引文长度为什么不是无条件降权（这是一条修订）
     *
     * 判断"这个短串在全文里到处都是"的前提是**我们正在全文里找它**。可结构
     * 命中这条路不是找，是验证：候选行已经由 `blockPath` + 字符区间唯一确定
     * （而规则 0/1 又已经钉住"这是同一份文档、同一套归一化口径"），
     * 引文只是用来**否决**（找不到就降级到下一路选择器）。
     *
     * 所以降权只保留给"证据不足"的情形：
     *   * `checkContext = true`：前后缀是可校验的独立证据。前缀/后缀都吻合时，
     *     引文短不构成弱点 —— 此时再扣 0.2 会把分数压到 0.8 < 0.9，
     *     于是**每一行短于 8 字的笔记（标题、诗句、短对白）写下去就永远
     *     需要人工确认**；而按硬规则 `Ambiguous` 不画，用户看到的就是
     *     "刚写的字不见了"。这是本工程最不能接受的一类症状。
     *   * `checkContext = false`：调用方给不出上下文窗口，结构命中之外只剩
     *     引文长度这一条，短串此时确实是证据不足 —— 扣分保留。
     *
     * 换言之：这个惩罚表征的是**证据不足**，不是"文本可疑"。
     */
    fun structuralConsistency(
        expected: Selector.TextQuote?,
        actualText: String,
        checkContext: Boolean = true,
    ): Float {
        if (expected == null) return 0f
        val locator = locate(expected, actualText) ?: return 0f

        var score = 1.0f
        if (checkContext) {
            if (!locator.prefixMatches) score -= PREFIX_MISMATCH_PENALTY
            if (!locator.suffixMatches) score -= SUFFIX_MISMATCH_PENALTY
        } else if (expected.exact.length < SHORT_NEEDLE_LENGTH) {
            score -= SHORT_NEEDLE_PENALTY
        }
        return score.coerceIn(0f, 1f)
    }

    /**
     * 纯文本查找的相似度。用于"结构路径已失配，只能靠文本找"的第二路。
     *
     * 上限被钉在 [TEXT_QUOTE_ONLY_CEILING]：无论 prefix/suffix 多么吻合、
     * 命中的文本多长，这一路都拿不到自动接受的资格。
     */
    fun textQuoteSimilarity(expected: Selector.TextQuote, actualText: String): Float {
        if (expected.exact.isEmpty()) return 0f
        val locator = locate(expected, actualText) ?: return 0f

        // 0.75 起步：低于 CONFIDENT_THRESHOLD，但高于 USABLE_THRESHOLD，
        // 保证它一定会作为"候选"呈现给用户而不是被当成噪声丢掉。
        var score = 0.75f
        if (locator.prefixMatches) score += 0.05f
        if (locator.suffixMatches) score += 0.05f
        return score.coerceIn(0f, TEXT_QUOTE_ONLY_CEILING)
    }

    private class Locator(val prefixMatches: Boolean, val suffixMatches: Boolean) {
        /** 两路证据各自是否成立。用于在多次出现里挑"证据最好"的那一次。 */
        val evidence: Int get() = (if (prefixMatches) 1 else 0) + (if (suffixMatches) 1 else 0)
    }

    /**
     * 在 [actualText] 里找 [expected] 的 exact，并给出上下文是否吻合。
     *
     * ## 为什么要扫**全部**出现位置，而不是第一次出现（这是一条修订）
     *
     * 旧实现只看 `indexOf` 的第一次出现，理由是"找一个位置就够了"。它对
     * **相邻的重复行**直接失效：中文小说里的重复短句（"他点了点头。"、
     * 连续的对白"——"）太常见了，而结构命中时传进来的 `actualText` 是
     * **带前后上下文的窗口**（±64 字符），重复行必然同时在窗口里。于是
     * 第二行上的笔记会被按"第一行的上下文"来校验 —— 前缀必然对不上，
     * 扣掉 0.15 分后一致性 0.85 < 0.9，判定从"已定位"掉到"需要确认"；
     * 而按本工程的硬规则，`Ambiguous` 的笔迹**一个像素都不画**。
     * 用户看到的就是：**刚写下的字不见了**（本工程最不能接受的一类症状）。
     *
     * 所以这里改成扫全部出现位置，只要有**一次**出现的前后缀都吻合
     * （或对应字段为空）就当场返回，否则取证据最多的那一次。
     *
     * ## 这样改之后，这一路校验还剩下什么
     *
     * 它校验的是"窗口里存在与锚点上下文完全一致的文字序列"，而不是
     * "这段文字就在这个行盒上"。这个放松是可以接受的，因为**规则 1（源指纹）
     * 已经钉死"这是同一份文件、同一套归一化口径"**：文件一模一样时，
     * 结构路径已经唯一确定了行，引文在此处的作用是"否决"（对不上就降级到
     * 下一路选择器），而不是"二次定位"。把"否决"实现成"找不到**任何**一致的证据"
     * 比"只看第一次出现"更接近它的本意。
     */
    private fun locate(expected: Selector.TextQuote, actualText: String): Locator? {
        var best: Locator? = null
        var index = actualText.indexOf(expected.exact)
        while (index >= 0) {
            val before = actualText.substring(0, index)
            val after = actualText.substring(index + expected.exact.length)

            // 前缀/后缀为空时视为"无信息"，不参与判定，也不因此加分。
            val locator = Locator(
                prefixMatches = expected.prefix.isEmpty() || before.endsWith(expected.prefix),
                suffixMatches = expected.suffix.isEmpty() || after.startsWith(expected.suffix),
            )
            if (locator.evidence == 2) return locator
            if (best == null || locator.evidence > best.evidence) best = locator

            index = actualText.indexOf(expected.exact, index + 1)
        }
        return best
    }
}
