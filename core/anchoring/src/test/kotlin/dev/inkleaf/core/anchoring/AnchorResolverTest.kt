package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.AnchorHealth
import dev.inkleaf.core.model.AnchorResolution
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.LineBox
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.TextAnchorChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点解析的行为契约测试。
 *
 * 这些用例的作用不是"覆盖率"，而是把方案 §6.3.1 里那几条安全规则钉死成可执行断言。
 * 其中最重要的两条：
 *   1. 只靠文本查找命中的锚点，**永远**不能被自动接受；
 *   2. 指纹不符时**永远**不许尝试重锚。
 * 这两条一旦被后来的重构破坏，测试会立刻红；而如果只靠文档描述，
 * 大概率会在某个"优化一下匹配率"的 PR 里被悄悄改掉。
 */
class AnchorResolverTest {

    private val profile = NormalizationProfile()
    private val fingerprint = "fp-1"

    /** 足够长（>= 8 字）的 exact，避免触发"短串降权"，用于验证正常路径。 */
    private val longExact = "这是原文的第一句话"
    private val longText = "开头这是原文的第一句话结尾"

    private fun line(
        blockPath: String,
        start: Int = 0,
        end: Int = 6,
        top: Float = 0f,
    ) = LineBox(
        anchor = Selector.Structural(blockPath, start, end, baselineOffsetEm = 0.8f),
        rectDoc = Box(0f, top, 400f, top + 40f),
        baselineY = top + 30f,
        fontSizePx = 48f,
    )

    private fun chain(
        structural: Selector.Structural?,
        quote: Selector.TextQuote?,
        sourceFingerprint: String = fingerprint,
    ): TextAnchorChain {
        val selectors = buildList<Selector> {
            structural?.let(::add)
            quote?.let(::add)
            // 不变量：链尾必须是 ProgressHint
            add(Selector.ProgressHint(docRatio = 0.31f, pageKey = "ch1", nearbyText = "开头"))
        }
        return TextAnchorChain(selectors, profile, sourceFingerprint)
    }

    private fun textIndex(pairs: List<Pair<LineBox, String>>): (LineBox) -> String {
        val map = pairs.toMap()
        return { line -> map[line].orEmpty() }
    }

    // ── 规则 1 ────────────────────────────────────────────────────────────────

    @Test
    fun `源指纹不符时直接判为孤儿_即使候选完美匹配也不重锚`() {
        val target = line("blk[3]")
        val stale = chain(
            structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
            quote = Selector.TextQuote(longExact, "开头", "结尾"),
            sourceFingerprint = "fp-OLD", // 文档已被替换/重解析
        )

        val result = AnchorResolver.resolve(
            chain = stale,
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to longText)),
        )

        assertTrue("指纹不符必须孤儿，实际=$result", result is AnchorResolution.Orphaned)
        assertEquals(0.31f, (result as AnchorResolution.Orphaned).hint.docRatio, 1e-6f)
        assertTrue(OrphanDetector.needsUserAction(result))
    }

    // ── 规则 2a ───────────────────────────────────────────────────────────────

    @Test
    fun `结构命中且文本一致时自动解析并给出达标置信度`() {
        val target = line("blk[3]")

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = Selector.TextQuote(longExact, "开头", "结尾"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to longText)),
        )

        assertTrue("实际=$result", result is AnchorResolution.Resolved)
        result as AnchorResolution.Resolved
        assertEquals(target, result.line)
        assertTrue(
            "置信度必须 >= ${AnchorResolution.CONFIDENT_THRESHOLD}，实际=${result.confidence}",
            result.confidence >= AnchorResolution.CONFIDENT_THRESHOLD,
        )
        assertEquals(AnchorHealth.OK, OrphanDetector.health(result))
        assertFalse(OrphanDetector.needsUserAction(result))
    }

    @Test
    fun `结构命中但命中的文本过短时降级为需确认_而不是自动接受`() {
        val target = line("blk[3]")
        val short = "他说" // 2 字：全文里必然遍地都是

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = Selector.TextQuote(short),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to "他说完这句话就走了")),
        )

        // 这条用例走的是**没有上下文窗口**的配置（不传 contextOf），
        // 于是结构命中之外只剩"引文长度"这一路证据：2 字确实不足，
        // 一致性 1.0 - 0.2 = 0.8，达不到 0.9 → 走 TextQuote → 唯一命中 → Ambiguous。
        // 反面对照见下一条用例。
        assertTrue("不能自动接受，实际=$result", result !is AnchorResolution.Resolved)
        assertTrue("实际=$result", result is AnchorResolution.Ambiguous)
        assertEquals(AnchorHealth.NEEDS_CONFIRMATION, OrphanDetector.health(result))
    }

    @Test
    fun `结构命中且上下文可校验时_短引文同样能自动接受`() {
        // 短行（标题、诗句、短对白）上的笔记是最常见的输入之一。
        // 它的位置由结构选择器唯一确定，前后缀又都吻合 —— 此时再拿"引文短"
        // 扣分，会让这类笔记写下去就**永远**停在"需要确认"；而按硬规则
        // Ambiguous 的笔迹是不画的，用户看到的就是"刚写的字不见了"。
        // 所以引文长度只在"上下文没法校验"时才降权（见 ConfidenceScorer）。
        val target = line("blk[3]")
        val short = "他说"
        val windowText = "老王点了点头。\n" + short + "完这句话就走了，屋里安静下来。\n"

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = Selector.TextQuote(short, "老王点了点头。\n", "完这句话就走了"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to windowText)),
            contextOf = { windowText },
        )

        result as AnchorResolution.Resolved
        assertEquals(target, result.line)
        assertEquals("结构路径 + 前缀 + 后缀三路一致，就该是满分", 1.0f, result.confidence, 1e-4f)
    }

    @Test
    fun `结构命中但该行文本已漂移时不得返回已解析`() {
        val target = line("blk[3]")

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = Selector.TextQuote(longExact, "开头", "结尾"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to "这一行已经完全不一样了，一个字都对不上。")),
        )

        assertTrue("实际=$result", result is AnchorResolution.Orphaned)
    }

    @Test
    fun `相邻两行文字相同时_第二行上的笔记同样能自动接受`() {
        // 相邻重复行是最常见的输入之一（连续对白、"他点了点头。"）。
        // 结构命中时传进来的上下文窗口是 ±64 字符，因此**重复行必然同时在窗口里**：
        // 若校验只盯着 exact 在窗口里第一次出现的位置，第二行上的笔记就会被按
        // "第一行的上下文"校验、前缀必然不符、一致性掉到 0.85，
        // 判定从"已定位"变成"需要确认" —— 而 Ambiguous 的笔迹是不画的。
        // 症状因此是"刚写下的字不见了"（见 ConfidenceScorer.locate 的修订说明）。
        val first = line("blk[0]", 0, 6, top = 0f)
        val second = line("blk[1]", 7, 13, top = 50f)
        val text = "同样的一句话\n同样的一句话"
        val lines = listOf(first, second)
        val perLine = textIndex(listOf(first to "同样的一句话", second to "同样的一句话"))
        val window = { _: LineBox -> text } // 窗口比整篇还宽：两行都在里面

        fun resolveOn(target: LineBox) = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural(target.anchor.blockPath, target.anchor.startOffset, target.anchor.endOffset, 0.8f),
                quote = Selector.TextQuote(
                    exact = "同样的一句话",
                    prefix = if (target === first) "" else "同样的一句话\n",
                    suffix = if (target === first) "\n同样的一句话" else "",
                ),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = lines,
            textOf = perLine,
            contextOf = window,
        )

        for (target in lines) {
            val result = resolveOn(target)
            assertTrue("${target.anchor.blockPath} 上的笔记必须自动接受，实际=$result", result is AnchorResolution.Resolved)
            assertEquals(target, (result as AnchorResolution.Resolved).line)
            assertEquals("两路上下文证据都该吻合，实际=${result.confidence}", 1.0f, result.confidence, 1e-4f)
        }
    }

    // ── 规则 2b ───────────────────────────────────────────────────────────────

    @Test
    fun `文本搬到另一行时给出候选并要求用户确认_相似度不足以自动接受`() {
        val stale = line("blk[3]", 0, 6)
        val moved = line("blk[7]", 100, 120, top = 200f)

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = Selector.TextQuote(longExact, "前缀", "后缀"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(stale, moved),
            textOf = textIndex(
                listOf(
                    stale to "这一行变了",
                    moved to "前缀${longExact}后缀",
                ),
            ),
        )

        assertTrue("实际=$result", result is AnchorResolution.Ambiguous)
        result as AnchorResolution.Ambiguous
        assertEquals(1, result.candidates.size)
        assertEquals(moved, result.candidates[0].line)
        assertTrue(
            "纯文本命中的相似度必须严格低于自动接受阈值",
            result.candidates[0].similarity < AnchorResolution.CONFIDENT_THRESHOLD,
        )
    }

    @Test
    fun `多处相同文本时按相似度降序给出多个候选`() {
        val weak = line("blk[1]", 0, 6, top = 0f)
        val strong = line("blk[9]", 50, 70, top = 300f)

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = null, // 连结构路径都没有：纯文本降级路径
                quote = Selector.TextQuote(longExact, "前缀", "后缀"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(weak, strong),
            textOf = textIndex(
                listOf(
                    weak to "无关内容${longExact}无关内容",
                    strong to "前缀${longExact}后缀",
                ),
            ),
        )

        assertTrue("实际=$result", result is AnchorResolution.Ambiguous)
        result as AnchorResolution.Ambiguous
        assertEquals(2, result.candidates.size)
        assertEquals("前缀/后缀都吻合的候选应排在前面", strong, result.candidates[0].line)
        assertTrue(result.candidates[0].similarity >= result.candidates[1].similarity)
    }

    // ── 工作量上限 ────────────────────────────────────────────────────────────

    @Test
    fun `候选超出扫描上限时当场降级为孤儿_保证工作量可预测`() {
        val lines = List(3) { i -> line("blk[$i]", 0, 6, top = i * 50f) }
        val target = lines[2]

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[2]", 0, 6, 0.8f),
                quote = Selector.TextQuote(longExact, "前缀", "后缀"),
            ),
            currentSourceFingerprint = fingerprint,
            candidates = lines,
            textOf = textIndex(
                listOf(
                    lines[0] to "无关",
                    lines[1] to "无关",
                    target to "前缀${longExact}后缀",
                ),
            ),
            maxCandidateScan = 2, // 目标行落在窗口之外
        )

        assertTrue("实际=$result", result is AnchorResolution.Orphaned)
    }

    // ── 打分器自身的边界 ──────────────────────────────────────────────────────

    @Test
    fun `纯粹靠文本命中时相似度上限严格低于自动接受阈值`() {
        val perfect = Selector.TextQuote(exact = "a-very-long-exact-string", prefix = "p", suffix = "s")
        val score = ConfidenceScorer.textQuoteSimilarity(perfect, "pa-very-long-exact-string" + "s")

        assertEquals(ConfidenceScorer.TEXT_QUOTE_ONLY_CEILING, score, 1e-6f)
        assertTrue(
            "安全边界被破坏：纯文本命中拿到了可自动接受的分数 $score",
            score < AnchorResolution.CONFIDENT_THRESHOLD,
        )
    }

    @Test
    fun `没有文本校验样本时结构路径命中也不足以自动接受`() {
        val target = line("blk[3]")

        val result = AnchorResolver.resolve(
            chain = chain(
                structural = Selector.Structural("blk[3]", 0, 6, 0.8f),
                quote = null,
            ),
            currentSourceFingerprint = fingerprint,
            candidates = listOf(target),
            textOf = textIndex(listOf(target to longText)),
        )

        assertTrue("实际=$result", result is AnchorResolution.Orphaned)
    }
}
