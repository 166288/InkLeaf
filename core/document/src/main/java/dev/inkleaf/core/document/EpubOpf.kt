package dev.inkleaf.core.document

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * OPF（package document）解析。
 *
 * 这里用 DOM 而不是正则，与 [HtmlTextExtractor] 的选择相反 —— 理由是**输入性质不同**：
 *   - OPF 由工具生成，现实里几乎都是良构 XML，DOM 能一次拿到全部属性（含 `&amp;` 反转义）；
 *   - 正则会在此处引入"href 里带 `&amp;` 就找不到条目"这类隐蔽 bug。
 *
 * **非命名空间感知**：`dc:title` 与默认命名空间下的 `title` 都能用同一个
 * "取局部名"规则匹配（[localNameOrName]），不必关心前缀。
 *
 * 不解析 `toc.ncx` / EPUB3 nav 文档：章节层级目录留待后续里程碑（见 M4 记录），
 * 当前目录按 **spine 一项一章** 生成。
 */
internal class EpubOpf(
    val title: String?,
    val author: String?,
    val language: String?,
    /** 阅读顺序。非 XHTML 媒体类型在这里就被过滤掉了。 */
    val spine: List<Item>,
    /** `rendition:layout` 的值（reflowable / pre-paginated）；没有则为 null。 */
    val layout: String?,
    /**
     * 封面图片在包内的路径（已相对 OPF 目录解析）；没有声明则为 null。
     *
     * 取法按规范与现实的优先级：EPUB3 的 `properties="cover-image"` →
     * EPUB2 的 `<meta name="cover" content="id">` → 文件名启发式。
     * 只返回**路径**，读图由调用方决定（[EpubCoverExtractor]）——
     * 解析 OPF 时顺手把几 MB 的封面读进内存，对只关心正文的导入流程是浪费。
     */
    val coverHref: String? = null,
    /**
     * EPUB3 的 nav 文档路径（已相对包根解析）；没有则为 null。
     *
     * 判据是 manifest 条目的 `properties` 里含 `nav` token —— 规范只认这个。
     * 找不到时不要去猜文件名：EPUB3 的 nav 文档叫什么名字都行（`nav.xhtml`、
     * `toc.xhtml`、`content.opf` 同级…），猜错会读到无关文件。
     */
    val navHref: String? = null,
    /**
     * EPUB2 的 `toc.ncx` 路径（已相对包根解析）；没有则为 null。
     *
     * 判据是媒体类型 `application/x-dtbncx+xml`；退化到"href 以 .ncx 结尾"
     * 是因为有些转换工具写不对媒体类型。
     */
    val ncxHref: String? = null,
) {
    class Item(val href: String, val mediaType: String, val properties: String)
}

internal object EpubOpfParser {

    /** 能当正文的媒体类型。EPUB3 要求 `application/xhtml+xml`，EPUB2 常见 `text/html`。 */
    private val TEXT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html", "application/x-dtbook+xml")

    fun parse(opfBytes: ByteArray, opfPath: String): EpubOpf {
        val doc = parseXml(opfBytes) ?: throw EpubFormatException(
            EpubFormatException.Failure.OPF_MISSING,
            "EPUB 的包文档（$opfPath）不是良构 XML，无法解析",
        )

        val title = firstText(doc, "title")
        val author = firstText(doc, "creator")
        val language = firstText(doc, "language")

        // manifest：id → (href, media-type, properties)
        val manifest = HashMap<String, EpubOpf.Item>()
        for (item in doc.elementsByLocalName("item")) {
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isBlank() || href.isBlank()) continue
            manifest[id] = EpubOpf.Item(
                href = resolve(baseDir = dirOf(opfPath), href = href),
                mediaType = item.getAttribute("media-type").substringBefore(';').trim().lowercase(),
                properties = item.getAttribute("properties"),
            )
        }

        // spine：idref 顺序即阅读顺序
        val spine = ArrayList<EpubOpf.Item>()
        for (ref in doc.elementsByLocalName("itemref")) {
            val idref = ref.getAttribute("idref")
            val item = manifest[idref] ?: continue
            if (item.mediaType !in TEXT_MEDIA_TYPES) continue
            spine.add(item)
        }
        if (spine.isEmpty()) {
            throw EpubFormatException(
                EpubFormatException.Failure.OPF_MISSING,
                "EPUB 的 spine 里没有可阅读的 XHTML 文档（可能是纯图片或结构损坏的电子书）",
            )
        }

        return EpubOpf(
            title = title,
            author = author,
            language = language,
            spine = spine,
            layout = renditionLayout(doc),
            coverHref = resolveCoverHref(doc, manifest),
            navHref = resolveNavHref(manifest),
            ncxHref = resolveNcxHref(manifest),
        )
    }

    // ── OPF 细节 ────────────────────────────────────────────────────────────

    /**
     * 找封面图片的路径。
     *
     * 三级兜底（顺序有依据，不是随手写的）：
     *   1. **EPUB3**：manifest 条目的 `properties` 是空格分隔的 token 列表，
     *      其中含 `cover-image` 的那一条就是封面。只比较 token，不用 `contains("cover")`
     *      —— 否则 `cover-thumbnail` 这类扩展属性会被误命中。
     *   2. **EPUB2**：`<meta name="cover" content="id">`，`content` 指向 manifest 里的 id。
     *   3. **启发式**：很多转换工具既不加 properties 也不写 meta（封面图片在 manifest 里，
     *      但没被声明）。此时按 `image/` 前缀 + href 含 `cover` 猜，优先非 SVG：
     *      EPUB 里的 SVG 往往是"封面页"而不是封面图本身，BitmapFactory 也解不了。
     *
     * 找不到就返回 null —— 书架会用自绘封面兜底，不是错误。
     */
    private fun resolveCoverHref(doc: Document, manifest: Map<String, EpubOpf.Item>): String? {
        manifest.values.firstOrNull { item ->
            item.properties.split(' ', '\t', '\n', '\r').any { it.trim() == "cover-image" }
        }?.let { return it.href }

        val coverId = doc.elementsByLocalName("meta")
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
            ?.getAttribute("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (coverId != null) {
            manifest[coverId]?.let { return it.href }
        }

        val images = manifest.values.filter { it.mediaType.startsWith("image/") }
        return (images.firstOrNull { !it.mediaType.contains("svg") && it.href.contains("cover", ignoreCase = true) }
            ?: images.firstOrNull { it.href.contains("cover", ignoreCase = true) })
            ?.href
    }

    /**
     * EPUB3 的 nav 文档：`properties` 里含 `nav` token 的那条。
     *
     * 只认 token，不认 `contains("nav")` —— 后者会把 `nav-chapter` 之类的
     * 扩展属性也命中。
     */
    private fun resolveNavHref(manifest: Map<String, EpubOpf.Item>): String? =
        manifest.values.firstOrNull { item ->
            item.properties.split(' ', '\t', '\n', '\r').any { it.trim() == "nav" }
        }?.href

    /**
     * EPUB2 的 `toc.ncx`：媒体类型 `application/x-dtbncx+xml`，
     * 退化到"href 以 .ncx 结尾"（转换工具经常写不对媒体类型）。
     */
    private fun resolveNcxHref(manifest: Map<String, EpubOpf.Item>): String? {
        manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.let { return it.href }
        return manifest.values.firstOrNull { it.href.endsWith(".ncx", ignoreCase = true) }?.href
    }

    /** `rendition:layout`：EPUB3 用 `<meta property="rendition:layout">`，旧写法用 `<meta name= content=>`。 */    private fun renditionLayout(doc: Document): String? {
        for (meta in doc.elementsByLocalName("meta")) {
            if (meta.getAttribute("property").equals("rendition:layout", ignoreCase = true)) {
                return meta.textContent?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }
            if (meta.getAttribute("name").equals("rendition:layout", ignoreCase = true)) {
                return meta.getAttribute("content").trim().lowercase().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** 取第一个非空文本元素（`dc:title` / `dc:creator` / `dc:language`）。 */
    private fun firstText(doc: Document, localName: String): String? =
        doc.elementsByLocalName(localName)
            .firstNotNullOfOrNull { it.textContent?.trim()?.takeIf { text -> text.isNotEmpty() } }

    private fun dirOf(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index < 0) "" else path.substring(0, index)
    }

    /** 相对 href 相对 OPF 所在目录解析；`../` 在此被折叠。 */
    private fun resolve(baseDir: String, href: String): String {
        val path = href.substringBefore('#')
        val decoded = EpubArchive.percentDecode(path)
        return EpubArchive.normalizePath(if (baseDir.isEmpty()) decoded else "$baseDir/$decoded")
    }

    /**
     * 解析 XML。
     *
     * **不给 `disallow-doctype-decl` 置 true**：EPUB2 的 OPF 常带 DOCTYPE，
     * 禁掉它会把本来能读的书判成损坏。改为只关掉外部实体/DTD 加载 ——
     * 这才是 XXE 的真正入口（电子书是外来文件，不该让它去读本地文件或联网）。
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
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull()
    }
}

// ── DOM 小工具（非命名空间感知，按"局部名"匹配）───────────────────────────

internal fun Element.localNameOrName(): String = nodeName.substringAfterLast(':').lowercase()

/**
 * 按局部名取元素。
 *
 * 比较**忽略大小写**：[localNameOrName] 会把名字转成小写，而 XML 里的元素名
 * 是驼峰的（`navPoint`、`navLabel`）—— 不忽略大小写就永远匹配不上。
 * OPF 里的名字本来就是小写（`title`/`item`/`itemref`），所以放宽只会更宽松。
 */
internal fun Document.elementsByLocalName(name: String): List<Element> {
    val nodes = getElementsByTagName("*")
    val out = ArrayList<Element>()
    for (i in 0 until nodes.length) {
        val element = nodes.item(i) as? Element ?: continue
        if (element.localNameOrName().equals(name, ignoreCase = true)) out.add(element)
    }
    return out
}
