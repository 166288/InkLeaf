package dev.inkleaf.core.document

/**
 * 全文搜索（方案 §1.3：v1 只覆盖**文本层**，手写内容不参与搜索）。
 *
 * 为什么是"子串匹配"而不是方案里提到的 ICU `BreakIterator` + n-gram：
 * 那两个东西解决的是**不知道要搜什么词**的问题（分词、模糊召回），
 * 而用户输入的是一个明确的字符串。对确定字符串做子串匹配是**精确**的 ——
 * 命中就是命中，位置能精确到字符；而分词召回会把"找到的位置"变成概率问题
 * （方案 §5.2 记的 Readium `SearchService` 定位精度不保证正是这个问题）。
 * 中文没有词边界，子串匹配反而是最自然的形态。
 *
 * 搜索对象是**归一化之后的文本**（[dev.inkleaf.core.document.DocumentSession.normalized]）：
 * 那是全工程唯一的"文字坐标"，锚点、行盒、跳转都用它。若拿原文去搜，
 * 命中的偏移就与所有其他位置量不在同一套坐标系里 —— 那会是一个很贵的错。
 */
object TextSearch {

    /**
     * 结果上限。
     *
     * 500 不是性能限制，是**可用性**限制：一次搜到几千处时用户不会去翻，
     * 而界面上要按条高亮/跳转，条数越多越没人知道自己在第几处。
     * 截断时明确告诉用户"只显示了前 N 处"（[Result.truncated]），
     * 而不是悄悄少给结果。
     */
    const val MAX_HITS = 500

    /** 一处命中。区间是**半开**的：`[start, endExclusive)`，与全工程的偏移口径一致。 */
    data class Hit(val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
    }

    data class Result(
        /** 实际用于匹配的查询串（已按文档口径折叠空白，见 [normalizeQuery]）。 */
        val query: String,
        val hits: List<Hit>,
        /** 是否还有更多命中没返回。 */
        val truncated: Boolean,
    ) {
        val isEmpty: Boolean get() = hits.isEmpty()
        val count: Int get() = hits.size
    }

    /** 命中附近的上下文，给"搜索"界面显示用（用户靠它确认"是不是我要找的那一处"）。 */
    data class Context(val before: String, val match: String, val after: String)

    fun findIn(
        text: String,
        query: String,
        ignoreCase: Boolean = true,
        maxHits: Int = MAX_HITS,
    ): Result {
        val needle = normalizeQuery(query)
        if (needle.isEmpty() || text.isEmpty() || maxHits <= 0) {
            return Result(needle, emptyList(), truncated = false)
        }

        val hits = ArrayList<Hit>()
        var truncated = false
        val lastStart = text.length - needle.length
        var i = 0
        while (i <= lastStart) {
            // 先比首字符再比整串：绝大多数位置在这里就被否掉，
            // 于是整篇扫描的代价接近 O(文本长度) 而不是 O(文本长度 × 查询长度)。
            if (firstCharMatches(text[i], needle[0], ignoreCase) &&
                text.regionMatches(i, needle, 0, needle.length, ignoreCase)
            ) {
                if (hits.size >= maxHits) {
                    truncated = true
                    break
                }
                hits.add(Hit(i, i + needle.length))
                // 只前进一个字符：`aaa` 里搜 `aa` 应该有两处命中。
                // "重叠命中算不算两处"没有标准答案，这里选了"算" ——
                // 因为不算的话，用户会遇到"明明有两处，却只找到一处"。
            }
            i++
        }
        return Result(needle, hits, truncated)
    }

    /**
     * 命中处前后各 [contextChars] 个字符。
     *
     * 返回值已经把换行折成空格：列表里一行要放得下（文档的归一化文本里
     * 换行是与块边界同义的，直接显示会在界面上断开成两行）。
     */
    fun contextOf(text: String, hit: Hit, contextChars: Int = 24): Context {
        val context = contextChars.coerceAtLeast(0)
        val start = clipToCodePointStart(text, (hit.start - context).coerceAtLeast(0))
        val end = clipToCodePointEnd(text, (hit.endExclusive + context).coerceAtMost(text.length))
        return Context(
            before = text.substring(start, hit.start).replace('\n', ' '),
            match = text.substring(hit.start, hit.endExclusive).replace('\n', ' '),
            after = text.substring(hit.endExclusive, end).replace('\n', ' '),
        )
    }

    /**
     * 查询串的口径：与文档的归一化口径**对齐**，否则"从别处复制来的文字搜不到"。
     *
     * 文档的 `SpacePolicy.COLLAPSE_HALF_WIDTH` 会把半角空格与制表符的连续串折成
     * 一个半角空格，并保留全角空格 U+3000 与换行（换行即块边界）。
     * 所以这里做三件事：去掉首尾空白、把 `' '`/`'\t'` 连续串折成一个半角空格、
     * 其余字符（含 U+3000 与换行）原样保留。
     *
     * 刻意**不做** Unicode 归一化（NFC/NFKC 之类）：那会让"用户输入的字符"与
     * "匹配到的字符"不再是同一个字符串，命中长度也可能变，而偏移是要拿去跳转的。
     */
    fun normalizeQuery(query: String): String {
        var from = 0
        var to = query.length
        while (from < to && isIgnorableEdge(query[from])) from++
        while (to > from && isIgnorableEdge(query[to - 1])) to--
        if (from >= to) return ""

        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = query[i]
            if (c == ' ' || c == '\t') {
                sb.append(' ')
                while (i < to && (query[i] == ' ' || query[i] == '\t')) i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun isIgnorableEdge(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun firstCharMatches(a: Char, b: Char, ignoreCase: Boolean): Boolean =
        a == b || (ignoreCase && (a.equals(b, ignoreCase = true)))

    /**
     * 把区间端点挪到码点边界上。
     *
     * 摘要里截出半个代理对会显示成一个方块字符（emoji 尤其明显）。
     * 只挪一位，不动命中区间本身 —— 那段区间是**真的**位置。
     */
    private fun clipToCodePointStart(text: String, index: Int): Int =
        if (index > 0 && index < text.length && text[index].isLowSurrogate()) index - 1 else index

    private fun clipToCodePointEnd(text: String, index: Int): Int =
        if (index > 0 && index < text.length && text[index - 1].isHighSurrogate()) index - 1 else index
}
