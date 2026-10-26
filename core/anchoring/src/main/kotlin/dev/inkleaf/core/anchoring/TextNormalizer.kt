package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.LineEnding
import dev.inkleaf.core.model.NormalizationProfile
import dev.inkleaf.core.model.SpacePolicy
import dev.inkleaf.core.model.UnicodeForm
import java.text.Normalizer

/**
 * 归一化后的文本 + 到原文的偏移映射。
 *
 * [offsetMap] 的定义：`offsetMap[i]` = 归一化文本第 i 个字符来自原文的哪个下标；
 * `offsetMap[text.length]` = 原文长度。它**必然非递减**（输出字符的顺序与原文一致）。
 *
 * 为什么不只存归一化后的文本：用户需要的是"笔记还在原来的地方"。
 * 一旦只有归一化文本，UI 就无法把锚点偏移映射回真实字符位置去做高亮、
 * 选择与滚动定位 —— 归一化会删字符（BOM、拖尾空白、标点），
 * 删除之后"第 120 个字符"在两种文本里指的是两个不同的位置。
 */
class NormalizedText(
    val text: String,
    private val offsetMap: IntArray,
) {
    init {
        require(offsetMap.size == text.length + 1) {
            "offsetMap 长度必须是 text.length + 1 = ${text.length + 1}，实际 ${offsetMap.size}"
        }
        for (i in 1 until offsetMap.size) {
            require(offsetMap[i] >= offsetMap[i - 1]) {
                "offsetMap 必须非递减：offsetMap[$i]=${offsetMap[i]} < offsetMap[${i - 1}]=${offsetMap[i - 1]}"
            }
        }
    }

    val length: Int get() = text.length
    val isEmpty: Boolean get() = text.isEmpty()

    /** 归一化下标 → 原文下标。越界被夹到合法范围（锚点链末端会走到这里）。 */
    fun rawIndexOf(normalizedIndex: Int): Int =
        offsetMap[normalizedIndex.coerceIn(0, text.length)]

    /**
     * 原文下标 → 归一化下标。
     *
     * 定义：最小的 i 使 `offsetMap[i] >= rawIndex`。
     * 直觉是"原文这个位置在归一化文本里大约落在哪"。当该位置正好被归一化删掉时，
     * 它给出的是删掉之后的位置 —— 对锚点而言这是最安全的落点（不会指向被删字符）。
     */
    fun normalizedIndexOf(rawIndex: Int): Int {
        if (rawIndex <= 0) return 0
        var lo = 0
        var hi = offsetMap.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (offsetMap[mid] < rawIndex) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 取 [start, end) 片段，越界自动夹紧（锚点持久化后再次读取时很常见）。 */
    fun substring(start: Int, end: Int): String {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return text.substring(s, e)
    }

    override fun toString(): String = "NormalizedText(len=$length)"
}

/**
 * 文本归一化引擎（§5.6）。
 *
 * 设计要点：
 *   1. **口径必须可版本化**。归一化规则变了，全库锚点必须重算，
 *      因为锚点的偏移是相对归一化文本的。见 [NormalizationProfile.version]。
 *   2. **串行多趟**，每趟单独可测。每趟的输入输出都带偏移映射，
 *      所以最终映射直接落在**原文**下标上，不需要事后合并映射表
 *      （合并映射表是这类代码最容易出错的地方）。
 *   3. **刻意不做整串 NFC 组合**。整串 NFC 会把 "e"+U+0301 合成 "é"（2 字符 → 1 字符），
 *      偏移映射会变得无法精确表达。这里按码点归一化，分解形式保持分解形式。
 *      代价是归一化文本不是 Unicode 意义上的完全 NFC；
 *      收益是"写入时的口径"与"读取时的口径"逐字符一致，锚点不会漂。
 *      这对锚定系统是正确取舍：我们要的是**可复现**，不是最规范。
 */
object TextNormalizer {

    fun normalize(raw: String, profile: NormalizationProfile = NormalizationProfile()): NormalizedText {
        var current = identity(raw)
        if (profile.stripBom) current = stripBomPass(current)
        if (profile.lineEnding == LineEnding.NORMALIZE_LF) current = lineEndingPass(current)
        if (profile.trimTrailing) current = trimTrailingPass(current)
        current = when (profile.spacePolicy) {
            SpacePolicy.COLLAPSE_HALF_WIDTH -> collapseHalfWidthPass(current)
            SpacePolicy.REMOVE_ALL -> removeAllSpacesPass(current)
            SpacePolicy.PRESERVE -> current
        }
        if (profile.unicodeForm != UnicodeForm.NONE) current = unicodeFormPass(current, profile.unicodeForm)
        if (profile.caseFold) current = caseFoldPass(current)
        if (profile.stripPunctuation) current = stripPunctuationPass(current)
        return current
    }

    /** 恒等映射。原文本身就是"归一化结果"的最初版本。 */
    fun identity(raw: String): NormalizedText = NormalizedText(raw, IntArray(raw.length + 1) { it })

    // ── 各趟实现 ─────────────────────────────────────────────────────────────

    /**
     * 输出收集器。每次写入都带上"这个字符来自输入的第几个位置"，
     * 并立刻换算成**原文**下标，避免事后合并多张映射表。
     */
    private class Sink(private val input: NormalizedText) {
        private val sb = StringBuilder(input.length)
        private val map = ArrayList<Int>(input.length + 1)

        /** 写入一个字符，它来自输入下标 [inputIndex]。 */
        fun put(c: Char, inputIndex: Int) {
            sb.append(c)
            map.add(input.rawIndexOf(inputIndex))
        }

        /** 写入一串字符，全部归因到输入下标 [inputIndex]（码点归一化会 1→N）。 */
        fun putEach(s: CharSequence, inputIndex: Int) {
            if (s.isEmpty()) return
            sb.append(s)
            val raw = input.rawIndexOf(inputIndex)
            repeat(s.length) { map.add(raw) }
        }

        /** 丢弃输入下标 [inputIndex]（不产生输出字符）。 */
        @Suppress("UNUSED_PARAMETER")
        fun drop(inputIndex: Int) = Unit

        fun finish(): NormalizedText {
            // 末项 = 原文长度，使 rawIndexOf(length) 有意义
            map.add(input.rawIndexOf(input.length))
            return NormalizedText(sb.toString(), map.toIntArray())
        }
    }

    private fun stripBomPass(input: NormalizedText): NormalizedText {
        if (input.text.isEmpty() || input.text[0] != '\uFEFF') return input
        val sink = Sink(input)
        for (i in 1 until input.length) sink.put(input.text[i], i)
        return sink.finish()
    }

    private fun lineEndingPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\r') {
                sink.put('\n', i)
                // CRLF 视为**一个**换行：把 \n 一起吃掉，否则每个段落后会多出一个空行，
                // 块切分结果会与 LF 版本的同一份文档不一致 —— 锚点会因此整篇错位。
                if (i + 1 < t.length && t[i + 1] == '\n') i++
            } else {
                sink.put(c, i)
            }
            i++
        }
        return sink.finish()
    }

    private fun trimTrailingPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == ' ' || c == '\t') {
                var j = i
                while (j < t.length && (t[j] == ' ' || t[j] == '\t')) j++
                val atLineEnd = j >= t.length || t[j] == '\n' || t[j] == '\r'
                if (!atLineEnd) {
                    for (k in i until j) sink.put(t[k], k)
                }
                i = j
            } else {
                sink.put(c, i)
                i++
            }
        }
        return sink.finish()
    }

    private fun collapseHalfWidthPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == ' ' || c == '\t') {
                var j = i
                while (j < t.length && (t[j] == ' ' || t[j] == '\t')) j++
                sink.put(' ', i)
                i = j
            } else {
                // 注意：全角空格 U+3000 走这一支，原样保留
                sink.put(c, i)
                i++
            }
        }
        return sink.finish()
    }

    private fun removeAllSpacesPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        for (i in t.indices) {
            val c = t[i]
            // 换行必须保留：删掉换行会把段落粘成一段，块结构整体失效，
            // 而块结构正是结构型选择器的骨架。
            if (c != ' ' && c != '\t' && c != '\u3000') sink.put(c, i)
        }
        return sink.finish()
    }

    private fun unicodeFormPass(input: NormalizedText, form: UnicodeForm): NormalizedText {
        val t = input.text
        val target = if (form == UnicodeForm.NFC) Normalizer.Form.NFC else Normalizer.Form.NFKC
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val charCount = Character.charCount(cp)
            val piece = t.substring(i, i + charCount)
            val normalized = try {
                Normalizer.normalize(piece, target)
            } catch (e: Exception) {
                // 归一化不该抛异常；万一抛了也绝不能因此丢掉这个字符，
                // 否则锚点会静默错位。原样保留并继续。
                piece
            }
            sink.putEach(normalized, i)
            i += charCount
        }
        return sink.finish()
    }

    private fun caseFoldPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val lowered = Character.toLowerCase(cp)
            sink.putEach(String(Character.toChars(lowered)), i)
            i += Character.charCount(cp)
        }
        return sink.finish()
    }

    private fun stripPunctuationPass(input: NormalizedText): NormalizedText {
        val t = input.text
        val sink = Sink(input)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            if (!isPunctuation(cp)) {
                sink.putEach(String(Character.toChars(cp)), i)
            } else {
                sink.drop(i)
            }
            i += Character.charCount(cp)
        }
        return sink.finish()
    }

    /**
     * Unicode 标点判定。
     *
     * 刻意不自己维护一张 "，。！？；：" 的字符表：中文标点到繁体、日文、韩文再到
     * 各种引号，"列举"永远列不全，而且列不全的后果是锚点在含漏列标点的文档上失配。
     * 用 Unicode 分类是完备的。
     */
    fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true

        else -> false
    }
}
