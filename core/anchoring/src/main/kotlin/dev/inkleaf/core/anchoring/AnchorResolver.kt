package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.TextAnchorChain

/**
 * 锚点解析器 —— 方案 §6.3.1 的可执行版本。
 *
 * 四条规则，按优先级执行：
 *
 *   规则 0  归一化口径不符 → 直接 [AnchorResolution.Orphaned]（仅在调用方传入
 *           [currentProfile] 时生效）。锚点的字符偏移是"归一化之后"的偏移，
 *           口径一变（例如从折叠空格改成保留空格），同一段文字的偏移会整体平移，
 *           结构型选择器会指向别的字符 —— 这与源指纹不符是同一类问题。
 *
 *   规则 1  源指纹不符 → 直接 [AnchorResolution.Orphaned]。
 *           **不做任何重锚尝试**。源文件已经不是原来那一份了，此时任何"聪明"
 *           的匹配都是在猜；猜错的代价（笔记挂到别处还显示成功）远大于
 *           老老实实说"我找不到它了"。
 *
 *   规则 2a 结构型命中 → 用 TextQuote 校验文本；一致度 ≥ 0.9 → [AnchorResolution.Resolved]。
 *
 *   规则 2b TextQuote 查找命中 → 一律 [AnchorResolution.Ambiguous]。
 *           即使只有唯一命中也不自动接受，理由见 [AnchorResolution.Ambiguous]。
 *
 *   规则 2c 都不中 → [AnchorResolution.Orphaned] + 进度兜底。
 *
 * ## 关于 50ms 预算
 *
 * §6.3.1 给每条锚点解析定了 50ms 硬预算。这里不用计时器实现它 ——
 * 计时器只会在超时后被动放弃，且让结果依赖机器负载、不可复现。
 * 取而代之的是对**工作量**设上限（[maxCandidateScan]），
 * 使单次解析的复杂度与输入规模脱钩：只要上限内不命中，就当场降级为孤儿，
 * 而不是"再扫一会儿看看"。代价是极少数笔记会被判为孤儿（可重新挂接），
 * 换到的是可复现、可单测、不会在低端机上突然变慢的行为。
 */
object AnchorResolver {

    /**
     * 单次解析最多扫描多少个候选行。
     *
     * 200 行的量级足以覆盖"当前章节 ± 1 章"这个检索范围（§6.3.1 规定的范围），
     * 同时保证最坏情况下的工作在移动端是常数级。
     */
    const val DEFAULT_MAX_CANDIDATE_SCAN = 200

    /**
     * @param chain 持久化的锚点链。
     * @param currentSourceFingerprint 当前文档的指纹（文件哈希 + 解析器版本 + 归一化口径）。
     * @param candidates 候选行。调用方负责限定范围（当前章节 ± 1 章）。
     * @param textOf 取某一行当前文本。行盒本身不携带文本（它是排版结果的产物），
     *               文本必须从文档会话里取。
     * @param maxCandidateScan 工作量上限，见类注释。
     */
    fun resolve(
        chain: TextAnchorChain,
        currentSourceFingerprint: String,
        candidates: List<LineBox>,
        textOf: (LineBox) -> String,
        maxCandidateScan: Int = DEFAULT_MAX_CANDIDATE_SCAN,
        /**
         * 当前文档的归一化口径。传 null 表示调用方不检查（旧调用点保持兼容）；
         * 传进来就必须一致，否则整条链判孤儿。
         */
        currentProfile: NormalizationProfile? = null,
        /**
         * 取某一行**连同前后上下文**的文本窗口。只有它才能校验引文的前后缀。
         *
         * 为什么不直接用 [textOf]：引文的 prefix/suffix 是从全文上下文里取的
         * （见 [AnchorChainFactory.build]），而 [textOf] 只给这一行的文字。
         * 拿"一行文字"去校验"跨行的上下文"必然不匹配 —— 见
         * [ConfidenceScorer.structuralConsistency] 的 `checkContext`。
         *
         * 不传则前后缀不参与打分（宁可少一路证据，也不要一路**恒定误判**的证据）。
         * 窗口至少要比 `AnchorChainFactory.DEFAULT_CONTEXT_CHARS` 宽。
         */
        contextOf: ((LineBox) -> String)? = null,
    ): AnchorResolution {
        // ── 规则 0 ────────────────────────────────────────────────────────────
        if (currentProfile != null && currentProfile != chain.normalization) {
            return AnchorResolution.Orphaned(chain.progressHint)
        }

        // ── 规则 1 ────────────────────────────────────────────────────────────
        if (chain.sourceFingerprint != currentSourceFingerprint) {
            return AnchorResolution.Orphaned(chain.progressHint)
        }

        val scan = if (candidates.size <= maxCandidateScan) {
            candidates
        } else {
            candidates.subList(0, maxCandidateScan)
        }

        val structural = chain.structural
        val quote = chain.textQuote

        // ── 规则 2a ───────────────────────────────────────────────────────────
        // 必须同时具备结构型与文本型选择器才允许自动接受：只有结构路径而没有
        // 文本校验，等于相信"第 12 段的第 40 个字符"这个坐标在换版本后仍然有效。
        if (structural != null && quote != null) {
            val hit = scan.firstOrNull { line ->
                line.anchor.blockPath == structural.blockPath &&
                    rangesOverlap(
                        line.anchor.startOffset,
                        line.anchor.endOffset,
                        structural.startOffset,
                        structural.endOffset,
                    )
            }
            if (hit != null) {
                val consistency = if (contextOf != null) {
                    ConfidenceScorer.structuralConsistency(
                        expected = quote,
                        actualText = contextOf(hit),
                        checkContext = true,
                    )
                } else {
                    ConfidenceScorer.structuralConsistency(
                        expected = quote,
                        actualText = textOf(hit),
                        checkContext = false,
                    )
                }
                if (consistency >= AnchorResolution.CONFIDENT_THRESHOLD) {
                    return AnchorResolution.Resolved(hit, consistency)
                }
                // 一致度不足 → 不返回失败，而是继续走规则 2b，
                // 因为"结构路径对了但文本变了"恰恰是 TextQuote 最该发挥作用的情形。
            }
        }

        // ── 规则 2b ───────────────────────────────────────────────────────────
        if (quote != null && quote.exact.isNotEmpty()) {
            val scored = scan
                .map { it to ConfidenceScorer.textQuoteSimilarity(quote, textOf(it)) }
                .filter { (_, similarity) -> similarity >= AnchorResolution.USABLE_THRESHOLD }
                .sortedByDescending { (_, similarity) -> similarity }

            if (scored.isNotEmpty()) {
                return AnchorResolution.Ambiguous(
                    scored.map { (line, similarity) ->
                        AnchorResolution.Ambiguous.Candidate(line = line, similarity = similarity)
                    },
                )
            }
        }

        // ── 规则 2c ───────────────────────────────────────────────────────────
        return AnchorResolution.Orphaned(chain.progressHint)
    }

    /** 两个字符区间是否有交集（前闭后开）。 */
    private fun rangesOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart < bEnd && bStart < aEnd
}
