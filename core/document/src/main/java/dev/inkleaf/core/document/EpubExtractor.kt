package dev.inkleaf.core.document

import java.nio.charset.Charset

/**
 * EPUB 解析失败的分类。
 *
 * `:core:document` 不能依赖 `:core:data`（导入层的 `RejectReason`），
 * 所以这里给出**格式层**的原因，由导入层翻译成给用户看的一句话。
 * 分类而不是只给字符串：用户界面需要按原因决定"能不能强制打开"、
 * "值不值得让用户换个文件"（§5.5 的思路同样适用于 EPUB）。
 */
class EpubFormatException(val failure: Failure, message: String) : Exception(message) {

    enum class Failure {
        /** 不是 ZIP，或 ZIP 结构损坏。 */
        BROKEN_ZIP,

        /** 缺 `META-INF/container.xml`。 */
        CONTAINER_MISSING,

        /** container.xml/OPF 结构上找不到可读的正文（含 spine 为空、OPF 不是良构 XML）。 */
        OPF_MISSING,

        /** 正文被加密（DRM）。字体混淆不算。 */
        ENCRYPTED,

        /** 固定版式（pre-paginated）：页面型文档，不是重排文本。 */
        FIXED_LAYOUT,

        /** 解压出的正文超过上限（同时用于防解压炸弹）。 */
        TOO_LARGE,

        /** 能解开，但没有可提取的文本（纯图片漫画等）。 */
        EMPTY,
    }
}

/**
 * EPUB → 正文文本 + 章节。
 *
 * **设计要点：EPUB 走的是 TXT 同一条下游管线。**
 * 提取结果是一份"一行一段"的纯文本，随后与 TXT 一样做归一化、块切分、
 * 分页与锚定（[dev.inkleaf.core.anchoring.NormalizationProfiles.PLAIN_TEXT] +
 * [BlockSplitter.splitPlainText]）。这样做而不是另建一套"EPUB 会话"的理由：
 *
 *   1. 锚定坐标系只有一套。EPUB 与 TXT 的块路径都是全局 `blk[i]`，
 *      重排、改字号、换设备后的行为完全一致 —— 不需要为 EPUB 单独验证一遍；
 *   2. 渲染层只有一套（StaticLayout 行盒）。EPUB 的 CSS 版式本来就不会被使用
 *      （见 M2.2 的取舍：行盒必须来自真实排版），所以"保留 HTML 结构"没有收益；
 *   3. 可测。整条链路在 JVM 单测里可执行，不依赖设备。
 *
 * 代价（明确记录，不是疏漏）：EPUB 的 CSS 样式、脚注弹窗、多栏表格不进 v1。
 * 正文里的 `<img>` 从 M31 起**会显示**（见 [EpubImage]）：图以占位字符立于正文，
 * 排版时换成实际位图；矢量图（`<svg>` / `<image>`）与封面页仍不进正文。
 *
 * 目录（M25 起）：读 **EPUB3 nav** 与 **EPUB2 `toc.ncx`**，得到带层级的目录；
 * 两种都读不到时退回"spine 一项一章"。目录项按**文件级**定位
 * （`#fragment` 暂不精确到小节）—— 与"解不开就不画"同源：
 * 跳到章首是可预期的偏差，跳到某个猜测的位置是在骗用户。
 */
object EpubExtractor {

    /**
     * 存进 `DocumentMeta.encoding` 的标签。
     *
     * 每个 XHTML 文件可以声明自己的编码，所以"整个 EPUB 的编码"没有单一答案；
     * 这个标签让书架上显示的是事实（编码来自书内声明），不是一个编造的 UTF-8。
     */
    const val CHARSET_LABEL = "EPUB 内嵌编码"

    /** 提取出的正文字符上限。8M 字符 ≈ 16MB UTF-16，也顺带挡住解压炸弹。 */
    const val MAX_TEXT_CHARS = 8_000_000

    data class Chapter(
        val title: String,
        val href: String,
        /** 章节首行在 [EpubText.text] 里的字符偏移。 */
        val charOffset: Int,
        /** 层级，1 起。spine 生成的目录恒为 1；nav / ncx 的目录按嵌套深度给。 */
        val level: Int = 1,
    )

    /**
     * 正文里的一张图：占位字符在 [EpubText.text] 里的偏移 + 书内路径（ZIP 条目名）。
     *
     * [charOffset] 是**未归一化**正文的偏移，与 [Chapter.charOffset] 同一坐标系。
     * 归一化之后的落点由 `DocumentCodec` 负责换算（它才知道用哪套口径）。
     */
    data class EpubImage(val charOffset: Int, val href: String)

    data class EpubText(
        val text: String,
        val title: String?,
        val author: String?,
        val language: String?,
        val chapters: List<Chapter>,
        val spineItemCount: Int,
        /** 解开了、但没有文字的正文文档数（封面、图片页）。 */
        val skippedSpineItems: Int,
        /** spine 里声明了、但在压缩包里找不到（或超过单条目上限）的正文文档数。 */
        val missingSpineItems: Int,
        /** `<img>` 标签总数（含取不到地址、找不到文件的）。 */
        val imageCount: Int,
        /** 正文里真有占位、且文件也在包里的图。 */
        val images: List<EpubImage> = emptyList(),
        /** 这些图的原始字节（条目名 → 压缩后的数据），供排版时解码。 */
        val imageBytes: Map<String, ByteArray> = emptyMap(),
    ) {
        /** 给导入界面看的"依据"一句话。 */
        fun evidence(): String = buildString {
            append("EPUB 解包：")
            append(spineItemCount)
            append(" 个正文文档 → ")
            append(chapters.size)
            append(" 章")
            if (skippedSpineItems > 0) {
                append("，其中 ")
                append(skippedSpineItems)
                append(" 个没有文字（可能是封面/图片页）")
            }
            if (missingSpineItems > 0) {
                append("，另有 ")
                append(missingSpineItems)
                append(" 个正文文档在压缩包里找不到（文件可能损坏）")
            }
            if (imageCount > 0) {
                append("，正文含 ")
                append(imageCount)
                append(" 张图片（")
                append(images.size)
                append(" 张可显示）")
            }
        }
    }

    /** 正文/包文档的扩展名：这些被加密就是 DRM，不是字体混淆。 */
    private val TEXT_SUFFIXES = listOf(".xhtml", ".html", ".htm", ".opf", ".ncx", ".xml", ".txt")

    private val ENCRYPTED_URI = Regex("""URI\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CHARSET_DECL = Regex(
        """(?:encoding|charset)\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 解包 + 提取。失败时抛 [EpubFormatException]（**不**返回半成品：
     * 用户宁可看到"这本读不了"，也不愿看到一本缺了一半正文的书）。
     */
    fun extract(bytes: ByteArray): EpubText {
        val archive = EpubArchive(bytes)
        val opfPath = archive.findOpfPath()
        val opfBytes = archive.readAll(setOf(opfPath))[opfPath]
            ?: throw EpubFormatException(
                EpubFormatException.Failure.OPF_MISSING,
                "EPUB 里找不到包文档（$opfPath）",
            )
        val opf = EpubOpfParser.parse(opfBytes, opfPath)

        if (opf.layout != null && "pre-paginated" in opf.layout) {
            throw EpubFormatException(
                EpubFormatException.Failure.FIXED_LAYOUT,
                "这是固定版式 EPUB（pre-paginated）：每一页都是预先摆好的版面，属于页面型文档，计划与 PDF 一同支持",
            )
        }

        checkNotEncrypted(archive)

        val wanted = LinkedHashSet(opf.spine.map { it.href })
        val spineIndexByHref = HashMap<String, Int>(opf.spine.size * 2)
        opf.spine.forEachIndexed { index, item -> spineIndexByHref.putIfAbsent(item.href, index) }

        // 一次遍历取出正文文本。**只留文本，不留压缩前的字节** ——
        // 8M 字符的上限就是在这里兜底的，超了立刻停止读后续条目。
        val extracted = HashMap<Int, HtmlTextExtractor.Result>(opf.spine.size * 2)
        var totalChars = 0
        var overflow = false
        archive.forEachEntry(wanted) { href, data ->
            val spineIndex = spineIndexByHref[href] ?: return@forEachEntry true
            val result = HtmlTextExtractor.extract(decodeXhtml(data))
            extracted[spineIndex] = result
            totalChars += result.text.length
            if (totalChars > MAX_TEXT_CHARS) {
                overflow = true
                return@forEachEntry false
            }
            true
        }
        if (overflow) {
            throw EpubFormatException(
                EpubFormatException.Failure.TOO_LARGE,
                "EPUB 解压出的正文超过 ${MAX_TEXT_CHARS / 10_000} 万字上限（可能是压缩炸弹或异常巨大的电子书）",
            )
        }

        // 按 spine 顺序拼接（ZIP 顺序与阅读顺序不一定一致）
        val out = StringBuilder(minOf(totalChars + opf.spine.size * 2, 1 shl 24))
        val spineChapters = ArrayList<Chapter>(opf.spine.size)
        /** spine 项路径 → 它在 [out] 里的起始偏移。目录项靠它定位。 */
        val spineStart = HashMap<String, Int>(opf.spine.size * 2)
        var skippedSpineItems = 0
        var imageCount = 0
        /** 正文里留下占位的图（未过滤"文件是否存在"）。 */
        val pendingImages = ArrayList<EpubImage>()
        /** 需要取字节的条目名。 */
        val wantedImages = LinkedHashSet<String>()
        opf.spine.forEachIndexed { index, item ->
            val result = extracted[index] ?: return@forEachIndexed
            imageCount += result.imageCount
            val text = result.text
            if (text.isBlank()) {
                skippedSpineItems++
                return@forEachIndexed
            }
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            val charOffset = out.length
            spineStart[item.href] = charOffset
            out.append(text)
            // 占位字符与 result.images 一一对应（提取器只在该留占位时记地址）。
            // 第 k 个占位 ↔ 第 k 个地址，靠顺序而不是靠再匹配一次标签。
            var k = 0
            for (i in text.indices) {
                if (text[i] != IMAGE_PLACEHOLDER) continue
                val rawSrc = result.images.getOrNull(k) ?: break
                k++
                val path = resolveImagePath(item.href, rawSrc) ?: continue
                pendingImages.add(EpubImage(charOffset + i, path))
                wantedImages.add(path)
            }
            spineChapters.add(
                Chapter(
                    title = result.firstHeading ?: "第 ${spineChapters.size + 1} 节",
                    href = item.href,
                    charOffset = charOffset,
                ),
            )
        }

        // 取图：只取正文引用到的条目，一次遍历，并有总量上限。
        // 不把整包图片都读进内存 —— 一本画册 EPUB 就是几百 MB。
        val imageBytes = readImageBytes(archive, wantedImages)
        // 文件不在包里（或超上限）的图，不留在正文里当占位：
        // 留一个"永远空着的格子"比什么都不留更像故障。
        val images = pendingImages.filter { imageBytes.containsKey(it.href) }

        // 目录：EPUB3 nav → EPUB2 toc.ncx → 退回 spine 一章一项。
        val chapters = resolveChapters(archive, opf, spineChapters, spineStart)

        if (chapters.isEmpty()) {
            throw EpubFormatException(
                EpubFormatException.Failure.EMPTY,
                "这本 EPUB 里没有可提取的文字（纯图片漫画，或正文是加密的），无法批注与选择文本",
            )
        }

        return EpubText(
            text = out.toString(),
            title = opf.title,
            author = opf.author,
            language = opf.language,
            chapters = chapters,
            spineItemCount = opf.spine.size,
            skippedSpineItems = skippedSpineItems,
            // spine 里声明了、却没读到对应条目的正文文档。
            // 出现它的原因只有两种：压缩包被截断/损坏，或某个条目超过单条目上限。
            // 无论哪种，用户看到的都是一本"少了几章"的书 —— 必须说出来，
            // 不能静默交付半本书。
            missingSpineItems = (opf.spine.size - extracted.size).coerceAtLeast(0),
            imageCount = imageCount,
            images = images,
            imageBytes = imageBytes,
        )
    }

    // ── 图片 ────────────────────────────────────────────────────────────────

    /** 单张图的上限。超了就不显示：一张几十 MB 的扫描件解出来必然爆内存。 */
    private const val MAX_SINGLE_IMAGE_BYTES = 8 * 1024 * 1024

    /** 正文引用到的图合计上限（压缩后的字节）。 */
    private const val MAX_IMAGE_BYTES = 40 * 1024 * 1024

    /**
     * 把 `<img src>` 解析成书内路径。
     *
     * 三件事必须都做，少一件就取不到图：
     *   1. **相对本章目录**解析（`../Images/a.jpg` 是相对 XHTML 所在目录的，不是相对包根）；
     *   2. **百分号解码**（href 里的空格是 `%20`，ZIP 条目名里是字面空格）；
     *   3. **归一化 `..`/`./`/重复斜杠**（`a/../b` 与 `b` 是同一个条目）。
     *
     * 外链（`http://`）与内联 data URI 直接放弃：前者要联网，后者是 base64 大字符串，
     * 两者都不该在"打开一本书"的路径上。
     */
    private fun resolveImagePath(chapterHref: String, rawSrc: String): String? {
        val src = rawSrc.trim().substringBefore('#').substringBefore('?')
        if (src.isEmpty()) return null
        if (src.startsWith("data:") || "://" in src) return null
        val dir = dirOf(chapterHref)
        val joined = when {
            src.startsWith("/") -> src.trimStart('/')
            dir.isEmpty() -> src
            else -> "$dir/$src"
        }
        return EpubArchive.normalizePath(EpubArchive.percentDecode(joined)).ifEmpty { null }
    }

    /**
     * 取正文引用到的图片字节。
     *
     * 总量上限是**必须**的：一本画册型 EPUB 的图片有几百 MB，全读进来就是一个 OOM。
     * 超限之后**按顺序**放弃后面的图（前面的先显示），而不是平均分配 ——
     * 读者总是从前往后读，先看到前几张比"每张都残缺"更有用。
     */
    private fun readImageBytes(archive: EpubArchive, wanted: Set<String>): Map<String, ByteArray> {
        if (wanted.isEmpty()) return emptyMap()
        val read = archive.readAll(wanted)
        val out = HashMap<String, ByteArray>(wanted.size * 2)
        var total = 0
        for (path in wanted) {
            val bytes = read[path] ?: continue
            if (bytes.isEmpty() || bytes.size > MAX_SINGLE_IMAGE_BYTES) continue
            if (total + bytes.size > MAX_IMAGE_BYTES) break
            out[path] = bytes
            total += bytes.size
        }
        return out
    }

    // ── 目录 ────────────────────────────────────────────────────────────────

    /**
     * 目录来源的优先级：**EPUB3 nav → EPUB2 `toc.ncx` → spine 一项一章**。
     *
     * nav 在前是有理由的：EPUB3 的 nav 是规范要求必须存在的（转换工具至少要生成一个），
     * 而 `toc.ncx` 在 EPUB3 书里常常是"转换工具顺手留下的旧文件"，
     * 内容可能已经与正文脱节 —— 它的条目往往还指向已经不存在的文件。
     *
     * 任何一级失败都往下一级退，**绝不因为目录读不出来就让整本书读不了**。
     */
    private fun resolveChapters(
        archive: EpubArchive,
        opf: EpubOpf,
        spineChapters: List<Chapter>,
        spineStart: Map<String, Int>,
    ): List<Chapter> {
        val entries = readTocEntries(archive, opf)
        if (entries.isEmpty()) return spineChapters
        val mapped = mapTocToChapters(entries, spineStart)
        // 映射成功得太少 → 路径基准猜错了（nav 与正文不在同一个目录）。
        // 这时用 spine 生成的目录：它粗，但每一项都指得对。
        if (mapped.size * 2 < entries.size) return spineChapters
        return mapped
    }

    private fun readTocEntries(archive: EpubArchive, opf: EpubOpf): List<EpubTocEntry> {
        opf.navHref?.let { href ->
            val bytes = archive.readAll(setOf(href))[href]
            if (bytes != null) {
                val entries = EpubTocParser.parseNav(bytes, dirOf(href))
                if (entries.isNotEmpty()) return entries
            }
        }
        opf.ncxHref?.let { href ->
            val bytes = archive.readAll(setOf(href))[href]
            if (bytes != null) {
                val entries = EpubTocParser.parseNcx(bytes, dirOf(href))
                if (entries.isNotEmpty()) return entries
            }
        }
        return emptyList()
    }

    /**
     * 目录项 → 章节。
     *
     * 定位到**文件级**：`#fragment` 暂不精确到小节。
     *
     * 为什么不按 fragment 精确定位：正文提取器不保留元素 id，
     * 要精确定位就得改提取器（核心链路），或者靠标题文本猜位置。
     * 后者会跳到"某个猜的地方" —— 与"解不开就不画"同一条原则：
     * **跳到章首是可预期的偏差，跳到猜的位置是在骗用户。**
     *
     * 路径匹配两级：先按完整路径，匹配不上再按文件名
     * （nav 与正文不同目录时，基准目录算出来的路径对不上，但文件名是一样的）。
     */
    private fun mapTocToChapters(
        entries: List<EpubTocEntry>,
        spineStart: Map<String, Int>,
    ): List<Chapter> {
        val byFileName = HashMap<String, Int>(spineStart.size * 2)
        for ((path, offset) in spineStart) byFileName.putIfAbsent(fileNameOf(path), offset)

        // 层级规整：目录里可能从 1 级直接跳到 3 级（工具生成时常见）。
        // 界面按 level 缩进，跳级的缩进会很怪，所以把"出现过的层级"压成连续的几级。
        val levelMap = HashMap<Int, Int>()
        var nextLevel = 0

        val out = ArrayList<Chapter>(entries.size)
        for (entry in entries) {
            val offset = spineStart[entry.path] ?: byFileName[fileNameOf(entry.path)] ?: continue
            val level = levelMap.getOrPut(entry.level) {
                nextLevel += 1
                nextLevel
            }
            out.add(
                Chapter(
                    title = entry.title,
                    href = entry.path,
                    charOffset = offset,
                    level = level,
                ),
            )
        }
        return out
    }

    private fun dirOf(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index < 0) "" else path.substring(0, index)
    }

    private fun fileNameOf(path: String): String = path.substringAfterLast('/')

    // ── 加密检测 ────────────────────────────────────────────────────────────

    private fun checkNotEncrypted(archive: EpubArchive) {
        val xml = archive.readAll(setOf(EpubArchive.ENCRYPTION_PATH))[EpubArchive.ENCRYPTION_PATH] ?: return
        val text = String(xml, Charsets.UTF_8)
        val refs = ENCRYPTED_URI.findAll(text).map { it.groupValues[1].lowercase().substringBefore('#') }
        val textEncrypted = refs.any { uri -> TEXT_SUFFIXES.any { uri.endsWith(it) } }
        if (textEncrypted) {
            throw EpubFormatException(
                EpubFormatException.Failure.ENCRYPTED,
                "这本 EPUB 的正文被加密（DRM）。当前版本只支持无 DRM 的电子书",
            )
        }
        // 加密的字体不构成阻碍：我们用系统字体排版，不加载书内字体
    }

    // ── XHTML 解码 ──────────────────────────────────────────────────────────

    /**
     * XHTML 字节 → 字符串。
     *
     * 顺序：BOM → 无 BOM 的 UTF-16 特征 → 文件内声明 → 通用编码探测。
     * 最后一档很重要：现实里"声明 UTF-8、实际 GBK"的电子书并不罕见，
     * 只要声明结果里替换字符（U+FFFD）比例过高就改走探测。
     */
    internal fun decodeXhtml(data: ByteArray): String {
        if (data.isEmpty()) return ""

        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return String(data, 3, data.size - 3, Charsets.UTF_8)
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        }
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        }
        if (data.size >= 4) {
            if (data[0].toInt() == 0x00 && data[1] == '<'.code.toByte()) {
                return String(data, 0, data.size, Charsets.UTF_16BE)
            }
            if (data[0] == '<'.code.toByte() && data[1].toInt() == 0x00) {
                return String(data, 0, data.size, Charsets.UTF_16LE)
            }
        }

        declaredCharset(data)?.let { charset ->
            val text = runCatching { String(data, 0, data.size, charset) }.getOrNull()
            if (text != null && replacementRatio(text) < 0.05f) return text
        }

        val detected = CharsetDetector.detect(data)
        return CharsetDetector.decode(data, detected)
    }

    private fun declaredCharset(data: ByteArray): Charset? {
        val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
        val name = CHARSET_DECL.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun replacementRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        var count = 0
        for (c in text) if (c == '\uFFFD') count++
        return count.toFloat() / text.length
    }
}
