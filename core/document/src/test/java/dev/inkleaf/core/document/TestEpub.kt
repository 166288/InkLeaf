package dev.inkleaf.core.document

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 构造 EPUB 的测试夹具。
 *
 * 为什么不用一个真实的 `.epub` 二进制夹具：评审时看不懂里面是什么，
 * 也无法为"OPF 缺 spine""固定版式""正文被加密"这些分支各造一个最小样本 ——
 * 而错误路径恰恰是最需要被钉住的部分。
 *
 * ZIP 写出顺序刻意是 mimetype → container.xml → OPF → 章节，
 * 这样 `EpubArchive` 的多趟扫描每次都能在读到自己要找的条目后就拿到数据。
 */
internal object TestEpub {

    data class Chapter(
        val file: String,
        val body: String,
        val mediaType: String = "application/xhtml+xml",
        /** false 表示该章节文件存在、但不进 spine（不该出现在正文里）。 */
        val inSpine: Boolean = true,
        /** 非空时用原始字节当正文（用于测编码回退）。 */
        val rawBytes: ByteArray? = null,
    )

    /** 封面在 OPF 里的**声明方式**。三种对应现实里的三类书。 */
    enum class CoverDecl {
        /** EPUB3：manifest 条目的 `properties="cover-image"`。 */
        EPUB3,

        /** EPUB2：`<meta name="cover" content="id">`。 */
        EPUB2,

        /** 没声明，只能靠文件名猜（转换工具的常见毛病）。 */
        NONE,
    }

    data class Cover(
        val file: String = "cover.jpg",
        val mediaType: String = "image/jpeg",
        val id: String = "cover-img",
        val decl: CoverDecl = CoverDecl.EPUB3,
        /** 用几个字节假扮图片：解析层不解码，只校验"取到的是不是这一段"。 */
        val bytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()),
    )

    /** 目录载体。EPUB3 用 nav 文档，EPUB2 用 `toc.ncx`。 */
    enum class TocKind {
        /** EPUB3：manifest 里 `properties="nav"` 的那个 XHTML。 */
        NAV,

        /** EPUB2：`application/x-dtbncx+xml`。 */
        NCX,
    }

    data class Toc(
        val kind: TocKind,
        /** 相对 OPF 目录的文件名。 */
        val file: String = "nav.xhtml",
        /** 文件内容（目录项的 body）。 */
        val body: String,
        /** 放进 manifest 时用的媒体类型；null 表示用该 [kind] 的默认类型。 */
        val mediaType: String? = null,
    )

    data class Spec(
        val title: String = "测试书",
        val author: String = "某作者",
        val language: String = "zh",
        /** 非空时写入 `rendition:layout`（"pre-paginated" 触发固定版式拒绝）。 */
        val layout: String? = null,
        /** 非空时写入 META-INF/encryption.xml，列出这些被加密的 URI。 */
        val encryptedRefs: List<String> = emptyList(),
        /** 非空时写入一张封面并按其声明方式进 OPF。 */
        val cover: Cover? = null,
        /** 非空时写入目录文档（nav 或 ncx）并在 OPF 的 manifest 里声明。 */
        val toc: Toc? = null,
        val opfPath: String = "OEBPS/content.opf",
        val chapters: List<Chapter> = listOf(
            Chapter("ch1.xhtml", "<h1>第一章</h1><p>第一段正文。</p>"),
            Chapter("ch2.xhtml", "<h1>第二章</h1><p>第二段正文。</p>"),
        ),
        /** spine 的 idref 顺序；null 表示按 [chapters] 的顺序。 */
        val spineOrder: List<String>? = null,
        /** 额外写入（或覆盖）的条目。 */
        val extra: Map<String, ByteArray> = emptyMap(),
        /** 覆盖 container.xml（用于构造"缺 rootfile"等损坏样本）。 */
        val containerXml: String? = null,
    )

    fun build(spec: Spec = Spec()): ByteArray {
        val baseDir = spec.opfPath.substringBeforeLast('/', "")
        fun fullPath(file: String) = if (baseDir.isEmpty()) file else "$baseDir/$file"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype：按 OCF 规范不压缩且第一个
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            }
            zip.putNextEntry(entry)
            zip.write(mimetype)
            zip.closeEntry()

            put(
                zip,
                "META-INF/container.xml",
                spec.containerXml ?: """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="${spec.opfPath}" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""",
            )

            put(zip, spec.opfPath, opf(spec))

            for (chapter in spec.chapters) {
                val path = fullPath(chapter.file)
                val data = chapter.rawBytes ?: xhtml(chapter.body).toByteArray(Charsets.UTF_8)
                put(zip, path, data)
            }

            if (spec.encryptedRefs.isNotEmpty()) {
                put(zip, "META-INF/encryption.xml", encryption(spec.encryptedRefs))
            }

            spec.cover?.let { cover -> put(zip, fullPath(cover.file), cover.bytes) }

            spec.toc?.let { toc -> put(zip, fullPath(toc.file), toc.body.toByteArray(Charsets.UTF_8)) }

            for ((name, data) in spec.extra) put(zip, name, data)
        }
        return out.toByteArray()
    }

    /** 把 [file] 名里的空格写成 `%20`（href 是 URI，条目名是字面量）。 */
    private fun href(file: String): String = file.replace(" ", "%20")

    private fun opf(spec: Spec): String {
        val chapters = spec.chapters.mapIndexed { index, chapter ->
            """    <item id="id$index" href="${href(chapter.file)}" media-type="${chapter.mediaType}"/>"""
        }
        val coverManifest = spec.cover?.let { c ->
            val props = if (c.decl == CoverDecl.EPUB3) " properties=\"cover-image\"" else ""
            """    <item id="${c.id}" href="${href(c.file)}" media-type="${c.mediaType}"$props/>"""
        }
        val tocManifest = spec.toc?.let { t ->
            when (t.kind) {
                TocKind.NAV -> """    <item id="inkleaf-nav" href="${href(t.file)}" media-type="${t.mediaType ?: "application/xhtml+xml"}" properties="nav"/>"""
                TocKind.NCX -> """    <item id="inkleaf-ncx" href="${href(t.file)}" media-type="${t.mediaType ?: "application/x-dtbncx+xml"}"/>"""
            }
        }
        val manifest = (chapters + listOfNotNull(coverManifest, tocManifest)).joinToString("\n")

        val order = spec.spineOrder ?: spec.chapters.filter { it.inSpine }.map { it.file }
        val spine = order.mapNotNull { file ->
            val index = spec.chapters.indexOfFirst { it.file == file }
            if (index < 0) null else """    <itemref idref="id$index"/>"""
        }.joinToString("\n")

        val layoutMeta = spec.layout?.let { """    <meta property="rendition:layout">$it</meta>""" } ?: ""
        val coverMeta = spec.cover
            ?.takeIf { it.decl == CoverDecl.EPUB2 }
            ?.let { """    <meta name="cover" content="${it.id}"/>""" }
            ?: ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:inkleaf-test</dc:identifier>
    <dc:title>${spec.title}</dc:title>
    <dc:creator>${spec.author}</dc:creator>
    <dc:language>${spec.language}</dc:language>
$layoutMeta
$coverMeta
  </metadata>
  <manifest>
$manifest
  </manifest>
  <spine>
$spine
  </spine>
</package>
"""
    }

    private fun xhtml(body: String): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>t</title><meta charset="utf-8"/></head>
  <body>
$body
  </body>
</html>
"""

    private fun encryption(refs: List<String>): String = buildString {
        append("<?xml version=\"1.0\"?>\n")
        append("<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" ")
        append("xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">\n")
        for (ref in refs) {
            append("  <enc:EncryptedData>\n")
            append("    <enc:EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/>\n")
            append("    <enc:CipherData><enc:CipherReference URI=\"$ref\"/></enc:CipherData>\n")
            append("  </enc:EncryptedData>\n")
        }
        append("</encryption>\n")
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) =
        put(zip, name, text.toByteArray(Charsets.UTF_8))

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }
}
