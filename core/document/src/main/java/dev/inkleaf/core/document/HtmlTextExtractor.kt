package dev.inkleaf.core.document

/**
 * XHTML → 纯文本。
 *
 * 为什么不用现成的 XML/HTML 解析器（`DocumentBuilder`、SAX、`android.text.Html`）：
 *
 *   1. **容错**。真实 EPUB 里存在大量不是合法 XML 的 XHTML：HTML5 序列化的
 *      `<br>`（未自闭合）、未在 DTD 里声明的 `&nbsp;`、未闭合的 `<p>`。
 *      XML 解析器遇到这些会**整章抛异常**，用户看到的是"某一章凭空消失"。
 *      对阅读器来说，丢一章远重于丢一个标签 —— 所以这里选择"能读就读"。
 *   2. **可测**。这里没有任何 Android / XML API，全部行为都能在 JVM 单测里
 *      逐条断言（`HtmlTextExtractorTest`）。用解析器则必须构造合法 XML 才能测，
 *      而"脏 HTML"恰恰是最需要测的那部分。
 *
 * 输出约定（**这些约定是锚点坐标系的一部分，改动等于换坐标系**）：
 *
 *   - 每个块级元素占一行；
 *   - 行内与行尾空白折叠成一个半角空格；行首空白丢弃；
 *   - 空块级元素不产生空行（连续的换行被压成一个），因此提取结果里
 *     只可能出现"内容行"与 `pre` 内部的真实空行；
 *   - 行尾不留空格 —— 与 [dev.inkleaf.core.anchoring.NormalizationProfiles.PLAIN_TEXT]
 *     的 `trimTrailing` 一致，使归一化对本输出**近似恒等**（近似：NFC 仍可能组合字符）；
 *   - `<br>` / `<hr>` 与块级元素等价（都产生换行）；
 *   - `<img src>` **占一行一个占位字符**（U+FFFC，见 [IMAGE_PLACEHOLDER]），
 *     地址记进 [Result.images]。图不删、不改文本长度：删掉它会让后面所有偏移前移，
 *     锚点全部错位（同 Markdown 标记只隐藏不删除的理由）；
 *   - `&nbsp;`（U+00A0）按空白折叠成半角空格，但 **U+3000 全角空格原样保留**
 *     （与 [dev.inkleaf.core.model.SpacePolicy.COLLAPSE_HALF_WIDTH] 一致：
 *     全角空格是中文排版的语义字符，不是空白）；
 *   - 脚本、样式、注音（`<rt>`）、矢量图、公式不进正文，只计数。
 *
 * 已知边界（明确的取舍，不是遗漏）：
 *   - CSS 驱动的隐藏/排序（`display:none`、`order`）不生效 —— 需要 CSS 引擎；
 *   - 不认识的命名实体原样保留（`&eacute;` 会以字面量出现），
 *     因为"可见的怪符号"优于"静默丢字"；
 *   - 表格按单元格拆行，不还原二维结构。
 */
object HtmlTextExtractor {

    /** 提取结果。 */
    data class Result(
        val text: String,
        /** 第一个 `h1`..`h6` 的文本；没有标题则为 null。（EPUB 章节标题的来源。） */
        val firstHeading: String?,
        /** 上面那个标题的层级 1..6；没有标题为 0。 */
        val firstHeadingLevel: Int,
        /** 正文里出现的图片数量（含取不到地址、被跳过的）。 */
        val imageCount: Int,
        /**
         * 正文里**留下了占位字符**的图片地址，按出现顺序排列（原始 `src`，未解析路径）。
         *
         * 与 [text] 里的 U+FFFC 一一对应：第 i 个占位字符就是 [images] 的第 i 项。
         * 解析成书内路径是 EPUB 层的事（它才知道这一章的目录在哪）。
         */
        val images: List<String> = emptyList(),
        /** 被整体丢弃的富内容元素数量（脚本/样式/注音/矢量图/公式/嵌入对象）。 */
        val skippedElements: Int,
    )

    /** 块级元素：开始与结束都产生换行。 */
    private val BLOCK_ELEMENTS: Set<String> = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd", "menu", "dir",
        "blockquote", "pre", "section", "article", "aside", "header", "footer",
        "nav", "main", "figure", "figcaption", "hgroup", "address", "center",
        "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td",
        "form", "fieldset", "legend", "details", "summary", "hr", "br", "output",
    )

    /** 内容整体丢弃的元素。`head`/`title` 也在这里（它们的文本不是正文）。 */
    private val SKIP_ELEMENTS: Set<String> = setOf(
        "script", "style", "head", "title", "rt", "rp", "svg", "math",
        "template", "noscript", "iframe", "object", "canvas", "map", "area",
        "video", "audio", "source", "track", "embed", "applet", "frameset",
    )

    /** 被丢弃、且值得计入"跳过了多少富内容"的元素。 */
    private val COUNTED_SKIP: Set<String> = setOf(
        "script", "style", "svg", "math", "iframe", "object", "canvas",
        "video", "audio", "embed", "applet", "template", "noscript",
    )

    /** 自闭合（void）元素：不会进入跳过层计数，也不会等待结束标签。 */
    private val VOID_ELEMENTS: Set<String> = setOf(
        "br", "hr", "img", "meta", "link", "base", "col", "area",
        "embed", "input", "param", "source", "track", "wbr",
    )

    /** 视作空白的字符。刻意**不含 U+3000 全角空格**（见类注释）。 */
    private fun isCollapsibleSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\u00A0'

    private fun isLineBreak(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** 常见命名实体。不在这里的实体原样保留（含 `&` 与 `;`）。 */
    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "shy" to "\u00AD",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "zwnj" to "\u200C",
        "zwj" to "\u200D",
        "mdash" to "—",
        "ndash" to "–",
        "horbar" to "―",
        "hellip" to "…",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "laquo" to "«",
        "raquo" to "»",
        "lsaquo" to "‹",
        "rsaquo" to "›",
        "times" to "×",
        "divide" to "÷",
        "middot" to "·",
        "bull" to "•",
        "hellips" to "…",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "deg" to "°",
        "plusmn" to "±",
        "sect" to "§",
        "para" to "¶",
        "dagger" to "†",
        "Dagger" to "‡",
        "permil" to "‰",
        "prime" to "′",
        "Prime" to "″",
        "euro" to "€",
        "pound" to "£",
        "yen" to "¥",
        "cent" to "¢",
        "curren" to "¤",
        "frac12" to "½",
        "frac14" to "¼",
        "frac34" to "¾",
        "sup1" to "¹",
        "sup2" to "²",
        "sup3" to "³",
        "micro" to "µ",
        "ne" to "≠",
        "le" to "≤",
        "ge" to "≥",
        "infin" to "∞",
        "larr" to "←",
        "uarr" to "↑",
        "rarr" to "→",
        "darr" to "↓",
        "harr" to "↔",
        "emsp13" to " ",
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "pi" to "π",
        "sigma" to "σ",
        "omega" to "ω",
    )

    /** 提取。任何输入都不抛异常（除了 [OutOfMemoryError] 这类本质错误）。 */
    fun extract(html: String): Result {
        val out = StringBuilder(html.length.coerceAtMost(1 shl 20))

        // ── 状态 ────────────────────────────────────────────────────────────
        var lineHasContent = false
        var skipDepth = 0
        var preDepth = 0
        var firstHeading: String? = null
        var firstHeadingLevel = 0
        var headingTag: String? = null
        var headingLevel = 0
        var headingBuf: StringBuilder? = null
        var imageCount = 0
        /** 留下了占位字符的图片地址，顺序与正文里的 U+FFFC 一致。 */
        val images = ArrayList<String>()
        var skippedElements = 0

        // ── 输出原语 ────────────────────────────────────────────────────────

        /** 结束当前行。空行不产生（连续换行被压成一个），行尾空格先抹掉。 */
        fun breakLine() {
            if (!lineHasContent) return
            while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
            if (out.isEmpty()) {
                lineHasContent = false
                return
            }
            if (out.last() != '\n') out.append('\n')
            lineHasContent = false
        }

        fun appendSpace() {
            if (!lineHasContent) return
            val last = out.lastOrNull() ?: return
            if (last != ' ' && last != '\n') {
                out.append(' ')
                // 标题文本要跟着一起记：否则"第一章 起风"在目录里会变成"第一章起风"，
                // 与正文里的那一行不一致 —— 目录项是要被用来对回正文的。
                headingBuf?.append(' ')
            }
        }

        /**
         * 放一张图：**单独占一行**，行内只有一个占位字符。
         *
         * 单独成行而不是内联在段落里，是因为图片高度通常远大于行高，
         * 混在段落中间会让那一行的行高突兀地被一张图撑开，前后文字像被扯开。
         * 书里的插图本来也几乎都是块级的。
         */
        fun appendImage(src: String) {
            breakLine()
            out.append(IMAGE_PLACEHOLDER)
            lineHasContent = true
            images.add(src)
            breakLine()
        }

        fun appendChar(c: Char) {
            if (isLineBreak(c)) {
                if (preDepth > 0) breakLine() else appendSpace()
                return
            }
            if (isCollapsibleSpace(c)) {
                if (preDepth > 0) {
                    out.append(c)
                    lineHasContent = true
                    headingBuf?.append(c)
                } else {
                    appendSpace()
                }
                return
            }
            out.append(c)
            lineHasContent = true
            headingBuf?.append(c)
        }

        // ── 标签处理 ────────────────────────────────────────────────────────
        fun startHeadingCapture(tag: String, level: Int) {
            if (firstHeading != null || headingBuf != null) return
            headingTag = tag
            headingLevel = level
            headingBuf = StringBuilder()
        }

        fun finishHeadingCapture() {
            val buf = headingBuf ?: return
            val text = buf.toString().trim()
            if (text.isNotEmpty()) {
                firstHeading = text.take(120)
                firstHeadingLevel = headingLevel
            }
            headingTag = null
            headingLevel = 0
            headingBuf = null
        }

        fun handleOpen(tag: Tag, start: Int) {
            val name = tag.name
            if (name in SKIP_ELEMENTS) {
                if (name !in VOID_ELEMENTS && !tag.selfClosing) {
                    skipDepth++
                    if (name in COUNTED_SKIP) skippedElements++
                }
                return
            }
            if (name == "img") {
                imageCount++
                // 跳过层里的图（例如 `<svg><image>` 里的矢量包装）不留占位：
                // 那一层的文字与图形本来就不进正文，留一个空格子只会让人以为图坏了。
                if (skipDepth == 0) {
                    val src = imageSource(html, start, tag.next)
                    if (!src.isNullOrBlank()) appendImage(src)
                }
                return
            }
            if (skipDepth > 0) return

            if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
                startHeadingCapture(name, name[1] - '0')
            }
            if (name == "pre") preDepth++
            if (name in BLOCK_ELEMENTS) breakLine()
        }

        fun handleClose(name: String) {
            if (name in SKIP_ELEMENTS) {
                if (name !in VOID_ELEMENTS && skipDepth > 0) skipDepth--
                return
            }
            if (skipDepth > 0) return
            if (name == headingTag) finishHeadingCapture()
            if (name == "pre" && preDepth > 0) preDepth--
            if (name in BLOCK_ELEMENTS) breakLine()
        }

        // ── 主循环 ──────────────────────────────────────────────────────────
        val n = html.length
        var i = 0
        while (i < n) {
            val c = html[i]

            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                    continue
                }
                if (regionMatchesIgnoreCase(html, i, "<![CDATA[")) {
                    val contentStart = i + 9
                    val end = html.indexOf("]]>", contentStart)
                    val stop = if (end < 0) n else end
                    if (skipDepth == 0) {
                        var k = contentStart
                        while (k < stop) appendChar(html[k++])
                    }
                    i = if (end < 0) n else end + 3
                    continue
                }
                // DOCTYPE / 处理指令：整段跳过（DOCTYPE 内部可能含 '>'，但这里丢掉的是声明，不影响正文）
                if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                    val gt = html.indexOf('>', i)
                    i = if (gt < 0) n else gt + 1
                    continue
                }
                // HTML5 规则：'<' 后面不是字母或 '/' 就不是标签起始（"a < b"、"<3"）
                val after = if (i + 1 < n) html[i + 1] else ' '
                if (!after.isLetter() && after != '/') {
                    if (skipDepth == 0) appendChar('<')
                    i++
                    continue
                }
                val tag = parseTag(html, i)
                if (tag == null) {
                    // 落单的 '<'（未转义的比较符）：当正文处理，不吞掉后面的内容
                    if (skipDepth == 0) appendChar('<')
                    i++
                    continue
                }
                if (tag.closing) handleClose(tag.name) else handleOpen(tag, i)
                i = tag.next
                continue
            }

            if (c == '&' && skipDepth == 0) {
                val decoded = decodeEntity(html, i)
                if (decoded != null) {
                    for (ch in decoded.text) appendChar(ch)
                    i = decoded.next
                    continue
                }
            }

            if (skipDepth == 0) appendChar(c)
            i++
        }

        // 收尾：抹掉最后一行的空白与结尾换行。
        // 结尾不留 '\n' 是刻意的：多出来的空行会让"行号 = 块号"这类朴素判断
        // 在最后一行出现 off-by-one，而它没有任何信息量。
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\n')) {
            out.deleteCharAt(out.length - 1)
        }

        return Result(
            text = out.toString(),
            firstHeading = firstHeading,
            firstHeadingLevel = firstHeadingLevel,
            imageCount = imageCount,
            images = images,
            skippedElements = skippedElements,
        )
    }

    // ── 图片地址 ────────────────────────────────────────────────────────────

    /**
     * `<img>` 的地址属性。
     *
     * 三个候选名按"常见程度"排在同一个模式里而不是分三轮匹配：真实的标签里
     * 常常同时有 `src` 与 `data-src`（懒加载），我们希望得到**先出现的那个** ——
     * 它才是渲染时真正显示的地址。`xlink:href` 是 XHTML 里 `<image>` 的写法。
     */
    private val IMG_SRC = Regex(
        """(?:xlink:href|src|data-src)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 取 `<img>` 的地址。取不到返回 null（此时这张图只计数量，不留占位）。
     *
     * 只看这一个标签的原文（`[start, end)` 由 [parseTag] 给出），不跨标签找：
     * 跨标签找会把下一段文字里的 "src=" 误当成这一张图的地址。
     */
    private fun imageSource(html: String, start: Int, end: Int): String? {
        val tagText = html.substring(start, end.coerceAtMost(html.length))
        val match = IMG_SRC.find(tagText) ?: return null
        val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return value.trim().ifEmpty { null }
    }

    // ── 标签与实体 ──────────────────────────────────────────────────────────

    internal class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val next: Int,
    )

    /**
     * 解析 `<` 开头的标签，返回它覆盖到的下一个下标。
     *
     * 返回 null 表示这**不是**一个标签（例如正文里的裸 `<`）。
     * 属性值里的 `>` 会被正确跳过（引号优先）—— 这是"扫到第一个 `>`"的做法
     * 会在 `<a title="a > b">` 上错位的地方。
     */
    internal fun parseTag(s: String, start: Int): Tag? {
        var i = start + 1
        if (i >= s.length) return null
        var closing = false
        if (s[i] == '/') {
            closing = true
            i++
        }
        while (i < s.length && isAsciiSpace(s[i])) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-' || s[i] == '_')) i++
        if (i == nameStart) return null

        val name = s.substring(nameStart, i).lowercase()
        var quote = ' '
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                var k = j - 1
                while (k > nameStart && isAsciiSpace(s[k])) k--
                return Tag(name, closing, selfClosing = s[k] == '/', next = j + 1)
            }
            j++
        }
        // 未闭合的标签：按"到文本末尾"处理，避免因此丢掉整章
        return Tag(name, closing, selfClosing = true, next = s.length)
    }

    internal class Decoded(val text: String, val next: Int)

    /**
     * 解码 `&` 开头的实体。
     *
     * 返回 null 表示"不是可识别的实体"：调用方会把 `&` 当普通字符输出，
     * 后面的 `amp;` 等也逐字符输出 —— 于是 `&eacute;` 变成字面量而不是被删掉。
     */
    internal fun decodeEntity(s: String, start: Int): Decoded? {
        val semi = s.indexOf(';', start + 1)
        if (semi < 0 || semi - start > 12) return null
        val body = s.substring(start + 1, semi)
        if (body.isEmpty()) return null

        if (body[0] == '#') {
            val cp = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull(10)
            }
            if (cp == null || cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
                return Decoded("\uFFFD", semi + 1)
            }
            return Decoded(String(Character.toChars(cp)), semi + 1)
        }

        val mapped = NAMED_ENTITIES[body] ?: return null
        return Decoded(mapped, semi + 1)
    }

    private fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun regionMatchesIgnoreCase(s: String, start: Int, prefix: String): Boolean {
        if (start + prefix.length > s.length) return false
        for (k in prefix.indices) {
            if (s[start + k].lowercaseChar() != prefix[k].lowercaseChar()) return false
        }
        return true
    }
}
