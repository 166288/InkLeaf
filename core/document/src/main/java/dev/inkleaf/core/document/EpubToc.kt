package dev.inkleaf.core.document

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * EPUB 的**层级目录**解析。
 *
 * ## 为什么要单独一个文件
 *
 * 目录有两种完全不同的载体：
 *   - **EPUB2**：`toc.ncx`（XML），结构是 `navMap → navPoint（可嵌套）`；
 *   - **EPUB3**：nav 文档（XHTML），结构是 `nav → ol → li（可嵌套）`。
 *
 * 它们连"目录项长什么样"都不一样，但对外要给出**同一种**东西：
 * 一串带层级的 `(标题, 目标, 层级)`。所以解析分开写，映射统一在这里收口。
 *
 * ## 与 [EpubOpfParser] 的分工
 *
 * OPF 只负责**找到**这两个文件在哪（manifest 里）；找到之后读不读得动、
 * 读出来是什么，是这里的事。分开的理由是 [EpubOpfParser] 已经在解析
 * OPF 本体，它不该顺手把另一个文件的内容也读进来。
 *
 * ## 失败一律"没有目录"，不是"书坏了"
 *
 * 目录解析失败（XML 不良构、文件缺失、格式没见过）返回空列表，
 * 由调用方退回 spine 生成的一章一项。**目录是锦上添花，正文才是主菜** ——
 * 一本书因为目录解析不了就读不了，那是本末倒置。
 */
internal data class EpubTocEntry(
    val title: String,
    /** 包内路径（相对包根，已折叠 `../`）；不含 fragment。 */
    val path: String,
    /** `#` 之后的锚点名；没有则为 null。 */
    val fragment: String?,
    /** 层级，1 起。 */
    val level: Int,
)

internal object EpubTocParser {

    /**
     * 解析 EPUB2 的 `toc.ncx`。
     *
     * 结构：
     * ```
     * <navMap>
     *   <navPoint>
     *     <navLabel><text>第一章</text></navLabel>
     *     <content src="ch1.xhtml"/>
     *     <navPoint>…</navPoint>   ← 子节点，递归
     *   </navPoint>
     * </navMap>
     * ```
     *
     * @param baseDir ncx 文件所在目录（相对包根），`src` 相对它解析。
     */
    fun parseNcx(bytes: ByteArray, baseDir: String): List<EpubTocEntry> {
        val doc = parseXml(bytes) ?: return emptyList()
        val navMap = doc.elementsByLocalName("navMap").firstOrNull() ?: return emptyList()
        val out = ArrayList<EpubTocEntry>()
        collectNcxPoints(navMap, baseDir, 1, out, depth = 0)
        return out
    }

    private fun collectNcxPoints(
        parent: Element,
        baseDir: String,
        level: Int,
        out: MutableList<EpubTocEntry>,
        depth: Int,
    ) {
        // 目录层级偶尔被工具生成得很深（十层以上）。递归不设上限的话，
        // 恶意或损坏的 ncx 能把栈打满 —— 这里按"够用"封顶。
        if (depth > 12) return
        for (point in parent.childElements("navPoint")) {
            val title = point.childElements("navLabel")
                .firstOrNull()
                ?.childElements("text")
                ?.firstOrNull()
                ?.textContent
                ?.trim()
                .orEmpty()
            val src = point.childElements("content").firstOrNull()?.getAttribute("src").orEmpty()
            if (src.isNotBlank()) {
                val entry = resolveEntry(title, src, baseDir, level)
                if (entry != null) out.add(entry)
            }
            // 子目录在父目录**之后**：先加自己再递归，读起来才像目录。
            collectNcxPoints(point, baseDir, level + 1, out, depth + 1)
        }
    }

    /**
     * 解析 EPUB3 的 nav 文档。
     *
     * 结构：
     * ```
     * <nav epub:type="toc">
     *   <ol>
     *     <li><a href="ch1.xhtml">第一章</a>
     *       <ol><li><a href="ch1.xhtml#s2">第二节</a></li></ol>
     *     </li>
     *   </ol>
     * </nav>
     * ```
     *
     * nav 文档是 XHTML，但现实里不少是 HTML5 写法的非良构 XML ——
     * 所以这里**先试 DOM，失败再试宽松的标签扫描**，两条都不成才返回空列表。
     */
    fun parseNav(bytes: ByteArray, baseDir: String): List<EpubTocEntry> {
        val doc = parseXml(bytes)
        if (doc != null) {
            val nav = pickTocNav(doc)
            if (nav != null) {
                val out = ArrayList<EpubTocEntry>()
                val ol = nav.childElements("ol").firstOrNull() ?: nav.descendants("ol").firstOrNull()
                if (ol != null) {
                    collectNavItems(ol, baseDir, 1, out, depth = 0)
                    if (out.isNotEmpty()) return out
                }
            }
        }
        return parseNavLoose(bytes, baseDir)
    }

    /** 找目录用的那个 `nav`：优先 `epub:type="toc"`，否则取第一个 `nav`。 */
    private fun pickTocNav(doc: Document): Element? {
        val navs = doc.elementsByLocalName("nav")
        if (navs.isEmpty()) return null
        return navs.firstOrNull { it.getAttribute("epub:type").equals("toc", ignoreCase = true) }
            ?: navs.firstOrNull { it.getAttribute("type").equals("toc", ignoreCase = true) }
            ?: navs.first()
    }

    private fun collectNavItems(
        list: Element,
        baseDir: String,
        level: Int,
        out: MutableList<EpubTocEntry>,
        depth: Int,
    ) {
        if (depth > 12) return
        for (li in list.childElements("li")) {
            val anchor = li.childElements("a").firstOrNull()
            if (anchor != null) {
                val title = anchor.textContent?.trim().orEmpty()
                val href = anchor.getAttribute("href").orEmpty()
                if (href.isNotBlank()) {
                    val entry = resolveEntry(title, href, baseDir, level)
                    if (entry != null) out.add(entry)
                }
            }
            // 子列表在 `li` 里（不是 `a` 里）。
            for (sub in li.childElements("ol")) {
                collectNavItems(sub, baseDir, level + 1, out, depth + 1)
            }
        }
    }

    /**
     * DOM 失败时的兜底：按标签扫 `<a href=…>标题</a>`。
     *
     * 只在这一条路上做，因为正则解析 HTML 天然脆弱 —— 它能救回的只是
     * "良构性差了、但标签结构还算干净"的那类文件（现实里不少）。
     * 层级在这里丢掉了（都当成 1 级），但**有目录总比没目录好**。
     */
    private fun parseNavLoose(bytes: ByteArray, baseDir: String): List<EpubTocEntry> {
        val text = String(bytes, Charsets.UTF_8)
        val out = ArrayList<EpubTocEntry>()
        val pattern = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(text)) {
            val href = match.groupValues[1]
            if (href.isBlank() || href.startsWith("#")) continue
            val title = match.groupValues[2]
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
            val entry = resolveEntry(title, href, baseDir, 1)
            if (entry != null) out.add(entry)
        }
        return out
    }

    /**
     * 把 `href` 拆成路径与锚点，并把路径解析到包根相对。
     *
     * `href` 形如 `ch1.xhtml`、`ch1.xhtml#s2`、`../text/ch2.xhtml#x`。
     */
    private fun resolveEntry(title: String, href: String, baseDir: String, level: Int): EpubTocEntry? {
        val raw = href.trim()
        if (raw.isEmpty()) return null
        val pathPart = raw.substringBefore('#')
        val fragment = raw.substringAfter('#', "").takeIf { it.isNotEmpty() }
        if (pathPart.isBlank()) return null
        val decoded = EpubArchive.percentDecode(pathPart)
        val full = EpubArchive.normalizePath(if (baseDir.isEmpty()) decoded else "$baseDir/$decoded")
        return EpubTocEntry(
            title = title.ifBlank { "未命名章节" },
            path = full,
            fragment = fragment,
            level = level,
        )
    }

    // ── XML 小工具（非命名空间感知，按"局部名"匹配）───────────────────────

    /**
     * 解析 XML。
     *
     * 与 [EpubOpfParser] 同样的安全设置：不禁 DOCTYPE（EPUB2 的 OPF 常带），
     * 只关掉外部实体/DTD 加载 —— 那才是 XXE 的入口。
     *
     * 解析前会**去掉 BOM 与前导空白**（[stripPrologue]）：XML 规范要求
     * `<?xml …?>` 声明必须位于文档最前，前面多一个空格或换行，
     * 解析器就会把整份文件判为"损坏"—— 而现实里由工具生成的 nav 文档
     * 带前导空白并不罕见。与其赌书都干净，不如解析前先收拾干净。
     */
    private fun parseXml(bytes: ByteArray): Document? {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.isNamespaceAware = false }
        runCatching { factory.isValidating = false }
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        return runCatching {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(stripPrologue(bytes)))
        }.getOrNull()
    }

    /** 去掉 UTF-8 BOM 与前导空白字符。 */
    private fun stripPrologue(bytes: ByteArray): ByteArray {
        var start = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            start = 3
        }
        while (start < bytes.size) {
            val b = bytes[start]
            val isSpace = b == ' '.code.toByte() || b == '\n'.code.toByte() ||
                b == '\r'.code.toByte() || b == '\t'.code.toByte()
            if (!isSpace) break
            start++
        }
        return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
    }
}

// ── DOM 小工具 ─────────────────────────────────────────────────────────────

/**
 * 直接子元素里局部名匹配的那些。
 *
 * 比较**忽略大小写**：[org.w3c.dom.Element.localNameOrName] 会把名字转成小写，
 * 而 XML 里的元素名是驼峰的（`navPoint`、`navLabel`）——
 * 不忽略大小写就永远匹配不上，那些名字在 DOM 里只能以 `navpoint` 的形式出现。
 */
internal fun Element.childElements(localName: String): List<Element> {
    val out = ArrayList<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node is Element && node.localNameOrName().equals(localName, ignoreCase = true)) out.add(node)
    }
    return out
}

/**
 * 任意深度的后代元素里局部名匹配的那些（同样忽略大小写）。
 *
 * 挂在 [Element] 上；整份文档里找用 [dev.inkleaf.core.document.elementsByLocalName]。
 */
internal fun Element.descendants(localName: String): List<Element> {
    val out = ArrayList<Element>()
    val nodes = getElementsByTagName("*")
    for (i in 0 until nodes.length) {
        val element = nodes.item(i) as? Element ?: continue
        if (element.localNameOrName().equals(localName, ignoreCase = true)) out.add(element)
    }
    return out
}
