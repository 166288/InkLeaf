package dev.inkleaf.core.ink

import dev.inkleaf.core.document.TextSearch

/**
 * 阅读页搜索面板的结果清单：**正文命中 + 我写的批注命中**，拼成一份按正文位置排好的列表。
 *
 * 为什么要多这一层，而不是界面自己把两份东西拼起来：
 *
 *   1. "命中"的口径只有一处（[TextSearch]）。批注是**用户自己打的字**，它必须与正文
 *      用同一个匹配口径 —— 否则会出现"同一个词，正文里找得到、我写的那句里找不到"，
 *      而用户没有办法知道这两者的区别。折叠查询串空白、忽略大小写、半开区间，全都照抄。
 *   2. 排序是**事实**，不是显示偏好：清单按字符偏移排，用户从上往下读就是从上往下翻书。
 *      让界面自己排，这件事就会变成一处随手写的实现。
 *   3. 计数只有一份：[Result.textCount] / [Result.noteCount] 给"第 3 / 17 处（含 2 条批注）"
 *      这种说法提供唯一依据，不会出现"清单里有 7 条、标题写着 5 条"。
 *
 * 搜索范围按方案 §1.3：**文本层 + 打字批注**。手写内容不参与搜索 —— 那是刻意的：
 * 不做半吊子的墨迹影像检索，也不依赖没人用的手写识别。
 *
 * 这里刻意**不做**的三件事：
 *
 *   * **不猜**：解析不到任何一行的批注照样列出来，只是 [Hit.charOffset] 为 null，
 *     界面据此把它显示成一条点不动的结果并说明原因。少列一条比列一条点不动的更糟 ——
 *     用户会以为"我写过的那句话丢了"，而它其实就在笔记面板里。
 *   * **不跨字段**：只搜批注的正文（用户写下的那句话），不搜它挂在哪一行。
 *     后者是"跳过去"能到达的地方（[TypedNoteRow.excerpt]），不是"搜出来"的内容；
 *     把它也算命中，会让"搜一个正文里的词→冒出十几条批注"变成常态。
 *   * **不跨格式**：手写笔迹与书签不在这里出现（书签不绑文本，笔迹见上）。
 */
object ReaderSearch {

    /**
     * 批注命中的条数上限。
     *
     * 与正文的 500（[TextSearch.MAX_HITS]）是两件事：正文是整本书，批注是用户自己的几十条。
     * 50 已经远超"我要找的那一条"；再多说明他搜的是一个极常见的字，那时候清单多长都没用。
     * 截断时明确说出来（[Result.noteTruncated]），不静默少给。
     */
    const val MAX_NOTE_HITS = 50

    /** 清单里的一条。 */
    sealed interface Hit {
        /**
         * 跳转目标：归一化全文里的一个偏移。
         *
         * null 表示**现在跳不过去**。只有批注会这样（它暂时解析不到正文的哪一行）；
         * 正文命中永远有位置 —— 它本来就是在正文里找到的。
         */
        val charOffset: Int?
    }

    /** 正文里的一处命中。区间半开 `[start, endExclusive)`，与 [TextSearch.Hit] 同口径。 */
    data class TextHit(val start: Int, val endExclusive: Int) : Hit {
        override val charOffset: Int get() = start
    }

    /**
     * 一条批注里的命中。
     *
     * [matchStart] / [matchEndExclusive] 是**在这条批注正文里**的区间，不是全文偏移 ——
     * 批注的字不在正文里，它没有全文偏移。要跳转用的是 [row]（它挂在哪一行）。
     *
     * 直接持有 [row] 而不是把它的字段抄一份：这条命中要用的东西（用户写的话、挂在哪一行、
     * 挂不上时的原因、跳不跳得过去）全都是行本身已有的，抄一遍就会漂一次。
     */
    data class NoteHit(
        val row: TypedNoteRow,
        val matchStart: Int,
        val matchEndExclusive: Int,
    ) : Hit {
        val annotationId: String get() = row.annotationId

        /** 这一条命中的种类：边注，或高亮（它们同属"打字批注"）。 */
        val kind: TypedNoteKind get() = row.kind

        /** 用户写下的那句话。空内容不会进库，所以这里不会是空白串。 */
        val noteText: String get() = row.noteText.orEmpty()

        override val charOffset: Int? get() = row.charOffset
    }

    data class Result(
        /** 实际用于匹配的查询串（已按文档口径折叠空白）。 */
        val query: String,
        val hits: List<Hit>,
        /** 正文命中是否被 [TextSearch.MAX_HITS] 截断。 */
        val textTruncated: Boolean,
        /** 批注命中是否被 [MAX_NOTE_HITS] 截断。 */
        val noteTruncated: Boolean,
    ) {
        val isEmpty: Boolean get() = hits.isEmpty()
        val count: Int get() = hits.size
        val textCount: Int get() = hits.count { it is TextHit }
        val noteCount: Int get() = hits.count { it is NoteHit }

        /**
         * 查询变化之后默认跳到第几条（[hits] 的下标）。
         *
         * 规则刻意是"**正文优先**"：用户搜索的默认预期是"在书里找到那句话"，
         * 而一条批注命中说明的只是"我写的那句话里出现过这个词"。
         * 正文里一处都没有时才退到第一条跳得过去的批注 —— 那时清单上只有它，
         * 不跳等于"搜了没反应"。全都跳不过去时返回 -1，界面据此说实话。
         */
        val defaultIndex: Int
            get() = hits.indexOfFirst { it is TextHit }
                .takeIf { it >= 0 }
                ?: hits.indexOfFirst { it.charOffset != null }

        companion object {
            val EMPTY: Result = Result("", emptyList(), textTruncated = false, noteTruncated = false)
        }
    }

    /**
     * 搜索一次。
     *
     * @param text 归一化全文（[LayoutFacts.normalizedText]）—— 与锚点、行盒、跳转同一个坐标系。
     * @param notes 打字批注**按当前排版解析过的**行（[typedNoteRows]）。传原始标注没有用：
     *        跳转要的偏移是解析结果，不是库里存的那条链。
     * @param maxTextHits 正文命中的上限（默认沿用 [TextSearch.MAX_HITS]）。
     * @param maxNoteHits 批注命中的上限（默认 [MAX_NOTE_HITS]）。两者独立：一本十万字的书
     *        与用户自己写的几十条批注，能容纳的条数根本不是一个量级。
     */
    fun search(
        text: String,
        notes: List<TypedNoteRow>,
        query: String,
        maxTextHits: Int = TextSearch.MAX_HITS,
        maxNoteHits: Int = MAX_NOTE_HITS,
    ): Result {
        val textResult = TextSearch.findIn(text, query, maxHits = maxTextHits)
        // 空查询（含纯空白）不返回任何东西：它不是"搜全书"的合法查询，
        // 而 TextSearch 已经把这条口径写死了 —— 这里不另写一套判断。
        if (textResult.query.isEmpty()) return Result.EMPTY

        val found = ArrayList<Hit>(textResult.count + 8)
        for (hit in textResult.hits) {
            found += TextHit(hit.start, hit.endExclusive)
        }

        var noteTruncated = false
        var noteCount = 0
        if (maxNoteHits > 0) {
            noteLoop@ for (row in notes) {
                val body = row.noteText.orEmpty()
                if (body.isEmpty()) continue
                // 多要一条：它的**存在**就是"还有更多"的证据。少要一条的话，
                // 命中数恰好等于上限时无法区分"刚好这么多"与"还有更多"。
                val perNote = TextSearch.findIn(body, query, maxHits = maxNoteHits - noteCount + 1)
                for (hit in perNote.hits) {
                    if (noteCount >= maxNoteHits) {
                        noteTruncated = true
                        break@noteLoop
                    }
                    found += NoteHit(row, hit.start, hit.endExclusive)
                    noteCount++
                }
            }
        }

        // 按正文位置排序 —— 这就是"从上往下翻书"的顺序。跳不过去的批注（取不到偏移）
        // 一律排在最后：它们没有位置，插在中间会让人以为它们在那里。
        // `sortWith` 是**稳定**排序，所以同一位置上的正文命中排在批注命中前面（先加入）。
        found.sortWith(compareBy { it.charOffset ?: Int.MAX_VALUE })

        return Result(
            query = textResult.query,
            hits = found,
            textTruncated = textResult.truncated,
            noteTruncated = noteTruncated,
        )
    }
}
