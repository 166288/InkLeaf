package dev.inkleaf.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * EPUB 容器（OCF）读取：一个 ZIP，外加 `META-INF/container.xml` 指向的 OPF。
 *
 * 这里只负责"把需要的条目拿出来"，不负责解释 OPF（那是 [EpubOpfParser]），
 * 也不负责正文提取（那是 [EpubExtractor]）。
 *
 * **刻意不校验 OCF 的"mimetype 必须是第一个且不压缩"规则**：现实里有大量
 * 不合规但完全可读的 EPUB（Calibre 之外的导出工具、二次打包过的文件）。
 * 校验它只会拒绝用户本来能读的书，收益为零。
 *
 * 防解压炸弹：单条目解压超过 [MAX_ENTRY_BYTES] 直接放弃该条目（由调用方计数），
 * 而不是让它把内存吃穿。
 */
internal class EpubArchive(private val bytes: ByteArray) {

    companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val ENCRYPTION_PATH = "META-INF/encryption.xml"

        /** 单个条目解压后的字节上限。一章的 XHTML 超过 16MB 已属异常。 */
        const val MAX_ENTRY_BYTES: Int = 16 * 1024 * 1024

        private val ROOTFILE_TAG = Regex("""<\s*[\w.-]*:?\s*rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val FULL_PATH_ATTR = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /** 把 ZIP 条目名 / href 归一成 `a/b/c` 形式（去掉 `./`、折叠 `..`、统一分隔符）。 */
        internal fun normalizePath(raw: String): String {
            val cleaned = raw.replace('\\', '/')
            val segments = ArrayList<String>()
            for (segment in cleaned.split('/')) {
                when (segment) {
                    "", "." -> {}
                    ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeAt(segments.size - 1)
                    else -> segments.add(segment)
                }
            }
            return segments.joinToString("/")
        }

        /**
         * `%XX` 解码。
         *
         * href 是 URI（空格是 `%20`），而 ZIP 条目名是字面量 —— 不解码就永远
         * 找不到含空格/中文名的条目。刻意不用 `URLDecoder`：它会把 `+` 解成空格，
         * 而 `+` 在文件名里是合法普通字符。
         */
        internal fun percentDecode(raw: String): String {
            if ('%' !in raw) return raw
            val out = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%' && i + 2 < raw.length) {
                    val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                out.write(c.code and 0xFF)
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** OPF 的路径（相对 ZIP 根）。 */
    fun findOpfPath(): String {
        val container = readAll(setOf(CONTAINER_PATH))[CONTAINER_PATH]
            ?: throw EpubFormatException(
                EpubFormatException.Failure.CONTAINER_MISSING,
                "这不是有效的 EPUB：压缩包里没有 META-INF/container.xml",
            )
        val xml = String(container, Charsets.UTF_8)
        val tag = ROOTFILE_TAG.find(xml)?.value
            ?: throw EpubFormatException(
                EpubFormatException.Failure.OPF_MISSING,
                "EPUB 的 container.xml 里没有 rootfile，无法定位包文档",
            )
        val path = FULL_PATH_ATTR.find(tag)?.groupValues?.get(1)
            ?: throw EpubFormatException(
                EpubFormatException.Failure.OPF_MISSING,
                "EPUB 的 rootfile 缺少 full-path 属性",
            )
        return normalizePath(percentDecode(path))
    }

    /** 一次性取出若干条目。找不到的条目**不会**出现在返回值里。 */
    fun readAll(wanted: Set<String>): Map<String, ByteArray> {
        if (wanted.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>(wanted.size * 2)
        forEachEntry(wanted) { name, data ->
            out[name] = data
            true
        }
        return out
    }

    /**
     * 按 ZIP 内部顺序遍历 [wanted] 里的条目，逐个交给 [action]；
     * [action] 返回 false 时立刻停止（用于"正文已经超上限，不必再读"）。
     *
     * 顺序是 ZIP 顺序而不是调用方期望的顺序 —— 顺序敏感的调用方要自己按
     * 期望的顺序重排（见 [EpubExtractor]）。
     */
    fun forEachEntry(wanted: Set<String>, action: (name: String, data: ByteArray) -> Boolean) {
        if (wanted.isEmpty()) return
        val zip = try {
            ZipInputStream(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw brokenZip(e)
        }
        zip.use { stream ->
            while (true) {
                // 整块包在 try 里：读取、closeEntry（它会读完剩余数据，截断的条目
                // 在这里抛 EOF）都可能暴露"文件坏了"，都要变成分类失败。
                try {
                    val entry = stream.nextEntry ?: return
                    if (entry.isDirectory) {
                        stream.closeEntry()
                        continue
                    }
                    val name = normalizePath(entry.name)
                    if (name !in wanted) {
                        stream.closeEntry()
                        continue
                    }
                    // data == null 表示该条目超过上限：跳过它，让调用方去计数
                    val data = readBounded(stream)
                    stream.closeEntry()
                    if (data != null && !action(name, data)) return
                } catch (e: EpubFormatException) {
                    throw e
                } catch (e: Exception) {
                    throw brokenZip(e)
                }
            }
        }
    }

    /** 读一个条目，超过 [MAX_ENTRY_BYTES] 返回 null。 */
    private fun readBounded(stream: ZipInputStream): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun brokenZip(e: Exception): EpubFormatException = EpubFormatException(
        EpubFormatException.Failure.BROKEN_ZIP,
        "EPUB 压缩包损坏或不是 ZIP 格式：${e.message ?: e::class.java.simpleName}",
    )
}
