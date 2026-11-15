package dev.inkleaf.core.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.inkleaf.core.common.AppDispatchers
import dev.inkleaf.core.common.DefaultAppDispatchers
import dev.inkleaf.core.document.CharsetDetector
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.DocumentSession
import dev.inkleaf.core.document.EpubFormatException
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfFormatException
import dev.inkleaf.core.document.PdfPageSource
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.DocumentOrigin
import kotlinx.coroutines.withContext

/**
 * 导入流程。
 *
 * 刻意拆成 **两步**（[inspect] → [commit]）而不是一个 `import(uri)`：
 *
 *   1. 中文 TXT 的编码是概率判断（[CharsetDetector]），用户必须有机会
 *      "看一眼摘录，发现是乱码，换一个编码再看"。一次性的 API 做不到这件事。
 *   2. 把字节留在内存里（[ImportCandidate]），用户在编码下拉框里切换时
 *      只做解码+断行，不需要重新读 SAF 流。SAF 的 URI 在进程被回收后可能失效，
 *      反复读 20MB 文件也是纯浪费。
 *
 * 代价是内存里要放一份文件字节，因此有 [MAX_IMPORT_BYTES] 上限。
 * 一个 64MB 的纯文本小说约 2000 万字，远超实际需要，超过就明确拒绝而不是 OOM。
 */
class DocumentImporter(
    private val database: InkLeafDatabase,
    private val paths: StoragePaths,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * PDF 页源工厂。
     *
     * 默认 null 而不是内置一个平台实现：`:core:data` 的 JVM 测试不该被迫拉起
     * `PdfRenderer`（它没有影子实现），而"没有页源"必须在导入时**明确失败**
     * （`PdfFormatException.NO_PAGE_SOURCE`），不能悄悄当成空文档。
     * `InkLeafStore` 会把平台实现注入进来。
     */
    private val pdf: PdfPageSource.Factory? = null,
) {

    companion object {
        /** 单文件上限。见类注释的理由。 */
        const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024
    }

    /** 导入阶段的所有拒绝理由。每一条都要能在界面上给出一句人话。 */
    enum class RejectReason {
        UNSUPPORTED_FORMAT,
        EMPTY_DOCUMENT,
        TOO_LARGE,
        READ_FAILED,
        WRITE_FAILED,

        /**
         * PDF 没有文本层（扫描件）。**不是**"不能导入"：界面上还有
         * "仍要以只读方式打开"这一条路（方案 §5.5）。
         */
        SCANNED_PDF,

        /** PDF 打不开（损坏、加密、内部不一致）。与 [SCANNED_PDF] 分开：这条没有逃生口。 */
        PDF_BROKEN,
    }

    class ImportException(val reason: RejectReason, message: String) : Exception(message)

    /** 已读进内存的待导入文档。 */
    data class ImportCandidate(
        val displayName: String,
        val title: String,
        val kind: DocumentKind,
        val bytes: ByteArray,
        val sourceUri: String?,
    ) {
        val byteSize: Long get() = bytes.size.toLong()

        // data class 的 equals 对 ByteArray 是引用比较；显式实现以符合直觉。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImportCandidate) return false
            return displayName == other.displayName && title == other.title &&
                kind == other.kind && sourceUri == other.sourceUri &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = displayName.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + kind.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /** 一次"看一遍"的结果。持有会话，因此 commit 不需要重做归一化与块切分。 */
    class ImportInspection internal constructor(
        val candidate: ImportCandidate,
        val decoded: DocumentCodec.DecodedText,
        /**
         * 会话。类型是**接口**：PDF 是固定版式，会话实现与重排的不是同一个
         * （见 `DocumentCodec.SessionBuild.session`），而导入流程只需要契约上的那几样
         * （正文、块数、指纹、口径）。
         */
        val session: DocumentSession,
        val excerpt: String,
        val metadataTitle: String? = null,
        val author: String? = null,
        val warnings: List<String> = emptyList(),
    ) {
        val kind: DocumentKind get() = candidate.kind
        val charsetName: String get() = decoded.charsetName
        val confidence: Float get() = decoded.confidence
        val evidence: String get() = decoded.evidence
        val isLowConfidence: Boolean get() = decoded.lowConfidence
        val charCount: Int get() = session.normalized.length
        val blockCount: Int get() = session.blocks.size
        val byteSize: Long get() = candidate.byteSize

        /**
         * 显示用标题。
         *
         * 优先用文档**内部**声明的标题：电子书的文件名常常是 `book` 或一串哈希
         * （下载站改的），而 `dc:title` 才是作者写的书名。TXT 没有内部标题，
         * 只能退回文件名。
         */
        val title: String
            get() = metadataTitle?.trim()?.takeIf { it.isNotEmpty() } ?: candidate.title

        /** 供"编码猜错了"场景下的手动切换列表。 */
        val manualChoices: List<String> get() = CharsetDetector.MANUAL_CHOICES

        /**
         * 这个格式的编码能不能由用户改。
         *
         * EPUB 每个 XHTML 文件自带编码声明，让用户"强制换一个编码"是不可能的
         * （换的是哪个文件的？），所以界面上不该出现这个选择器 ——
         * 出现一个点了没有效果的按钮，比没有按钮更糟。
         */
        val charsetIsUserChangeable: Boolean
            get() = kind == DocumentKind.TXT || kind == DocumentKind.MARKDOWN

        /** 固定版式（PDF）：界面上的统计口径是"页"，不是"字/段"。 */
        val isFixedLayout: Boolean get() = session is PdfDocumentSession

        /** PDF 的页数；非 PDF 为 0。 */
        val pageCount: Int get() = (session as? PdfDocumentSession)?.pages?.size ?: 0

        /**
         * 这份 PDF 有没有可用的文本层。
         *
         * 对非 PDF 恒为 true（它们的"文本层"就是解码出来的正文）。
         * 落库时写进 `document.has_text_layer`，供书架与批注层判断
         * "能不能按行定位"。
         */
        val hasTextLayer: Boolean
            get() = (session as? PdfDocumentSession)?.hasTextLayer ?: true
    }

    // ── 第一步：读取 ─────────────────────────────────────────────────────────

    /**
     * 从 SAF URI 读出候选文档。
     *
     * 读取失败、超限、格式未实现都在这里拒绝 —— **不要把"读不出来"拖到导入中途**，
     * 否则用户会看到文档已经出现在书架里、点开却是空的。
     */
    suspend fun readFromUri(
        resolver: ContentResolver,
        uri: Uri,
        fallbackTitle: String = "未命名",
    ): ImportCandidate = withContext(dispatchers.io) {
        val displayName = queryDisplayName(resolver, uri) ?: fallbackTitle
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw ImportException(RejectReason.READ_FAILED, "无法打开所选文件（输入流为空）")
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException(RejectReason.READ_FAILED, "读取文件失败：${e.message ?: e::class.java.simpleName}")
        }

        if (bytes.size.toLong() > MAX_IMPORT_BYTES) {
            throw ImportException(
                RejectReason.TOO_LARGE,
                "文件过大（${bytes.size / 1024 / 1024} MB），上限为 ${MAX_IMPORT_BYTES / 1024 / 1024} MB",
            )
        }

        val kind = DocumentCodec.kindFromFileName(displayName)

        ImportCandidate(
            displayName = displayName,
            title = displayName.substringBeforeLast('.').ifBlank { fallbackTitle },
            kind = kind,
            bytes = bytes,
            sourceUri = uri.toString(),
        )
    }

    // ── 第二步：看一遍（可反复切换编码）────────────────────────────────────────

    /**
     * 解码 + 归一化 + 切块。
     *
     * 不做任何持久化，因此可以在用户切换编码时反复调用。
     * 超过 [MAX_IMPORT_BYTES] 的正文在切块时也不会爆内存：
     * 归一化是 O(n) 且只保留一份文本。
     *
     * @param allowScannedPdf 扫描件闸门的逃生口。为 false 时，**只有在本机读得出
     *   文本层**（`pdfTextLayerAvailable`）且判定为扫描件的情况下才拒绝；
     *   读不出文本层的旧系统上，拒绝等于把所有文本型 PDF 都判死（见
     *   `PdfPageSource.canReadTextLayer`）。界面上点「仍要打开」之后传 true。
     */
    fun inspect(
        candidate: ImportCandidate,
        forcedCharset: String? = null,
        allowScannedPdf: Boolean = false,
    ): ImportInspection {
        val build = try {
            DocumentCodec.sessionFromBytes(
                docId = "pending",
                kind = candidate.kind,
                bytes = candidate.bytes,
                forcedCharset = forcedCharset,
                pdf = pdf,
            )
        } catch (e: EpubFormatException) {
            // 格式层的原因 → 用户能看懂的一句话。分类保留：将来"强制只读打开"
            // 之类的逃生口要按原因决定给不给（§5.5 对扫描件 PDF 是同一思路）。
            throw ImportException(e.failure.toRejectReason(), e.message ?: "EPUB 解析失败")
        } catch (e: PdfFormatException) {
            throw ImportException(e.failure.toRejectReason(), e.message ?: "PDF 解析失败")
        }

        val assessment = build.pdfAssessment
        if (assessment != null &&
            build.pdfTextLayerAvailable &&
            assessment.textLayer == dev.inkleaf.core.model.PdfTextLayer.NONE &&
            !allowScannedPdf
        ) {
            throw ImportException(
                RejectReason.SCANNED_PDF,
                assessment.reason + " 仍可以按页查看，但不能选中文字或按行锚定笔记。",
            )
        }

        if (build.session.blocks.isEmpty() && build.session !is PdfDocumentSession) {
            throw ImportException(
                RejectReason.EMPTY_DOCUMENT,
                "这篇文档没有可阅读的正文（空文件或只有空白字符）",
            )
        }
        return ImportInspection(
            candidate = candidate,
            decoded = build.decoded,
            session = build.session,
            excerpt = DocumentCodec.excerpt(build.session),
            metadataTitle = build.title,
            author = build.author,
            warnings = build.warnings,
        )
    }

    private fun EpubFormatException.Failure.toRejectReason(): RejectReason = when (this) {
        EpubFormatException.Failure.TOO_LARGE -> RejectReason.TOO_LARGE
        EpubFormatException.Failure.EMPTY -> RejectReason.EMPTY_DOCUMENT
        EpubFormatException.Failure.BROKEN_ZIP,
        EpubFormatException.Failure.CONTAINER_MISSING,
        EpubFormatException.Failure.OPF_MISSING,
        EpubFormatException.Failure.ENCRYPTED,
        EpubFormatException.Failure.FIXED_LAYOUT,
        -> RejectReason.UNSUPPORTED_FORMAT
    }

    private fun PdfFormatException.Failure.toRejectReason(): RejectReason = when (this) {
        PdfFormatException.Failure.BROKEN,
        PdfFormatException.Failure.INCONSISTENT,
        -> RejectReason.PDF_BROKEN

        // 没有页源是接线漏了，不是文件的问题：照样要能说清，但归到"不支持"，
        // 因为用户唯一能做的就是换一个版本/换一个文件。
        PdfFormatException.Failure.NO_PAGE_SOURCE -> RejectReason.UNSUPPORTED_FORMAT
    }

    // ── 第三步：落库 ─────────────────────────────────────────────────────────

    /**
     * 写入私有副本 + 元数据行。
     *
     * 顺序上**先写文件再写 DB 行**（同一个逻辑单元，但没法放进同一个事务：
     * 文件系统不参与 SQLite 事务）：
     *   - 先文件后 DB：崩溃只会留下一个没有 DB 行的孤儿文件 —— 不占用户注意力，
     *     可被维护任务回收；
     *   - 先 DB 后文件：崩溃会留下"书架里有、点开报错"的文档，这是用户可见的坏状态。
     * 因此宁可容忍孤儿文件。
     */
    suspend fun commit(inspection: ImportInspection): DocumentMeta = withContext(dispatchers.io) {
        val docId = Ids.new()
        val extension = inspection.candidate.displayName.substringAfterLast('.', "txt")
        val relativePath = paths.newDocumentRelativePath(docId, extension)
        val now = clock()

        val meta = DocumentMeta(
            id = docId,
            title = inspection.title,
            author = inspection.author,
            kind = inspection.kind,
            // 一律复制进私有目录：源文件在 SAF 上随时可能被删除/移动/换机器，
            // 而笔记是永久数据。私有副本是"笔记永远能打开"的唯一保证。
            origin = DocumentOrigin.SAF_COPY,
            sourceFingerprint = inspection.session.fingerprint,
            normalizationProfileId = inspection.session.profile.profileId,
            normalizationVersion = inspection.session.profile.version,
            relativePath = relativePath,
            encoding = inspection.charsetName,
            byteSize = inspection.byteSize,
            charCount = inspection.charCount,
            blockCount = inspection.blockCount,
            importedAt = now,
            lastOpenedAt = null,
            coverRelativePath = null,
            sourceUri = inspection.candidate.sourceUri,
            // 扫描件 / 旧系统读不到文本层时为 false：批注层据此判断"能不能按行定位"。
            hasTextLayer = inspection.hasTextLayer,
        )

        val file = paths.resolveDocument(relativePath)
        file.parentFile?.mkdirs()
        try {
            // 存**原始字节**而不是解码后的字符串：将来换一个编码重新解码
            // 不需要用户重新导入（方案 §5.1 的"编码猜错"处理路径）。
            file.writeBytes(inspection.candidate.bytes)
        } catch (e: Exception) {
            throw ImportException(RejectReason.WRITE_FAILED, "写入失败：${e.message ?: "未知原因"}")
        }

        val db = database.writableDatabase
        try {
            db.inTransaction {
                DocumentDao(db).upsert(meta)
                OpLogDao(db).append(
                    opType = OpTypes.IMPORT_DOCUMENT,
                    entityTable = StorageSchema.TABLE_DOCUMENT,
                    entityId = docId,
                    payloadJson = """{"title":${quote(meta.title)},"kind":"${meta.kind.name}","encoding":"${meta.encoding}"}""",
                    at = now,
                )
            }
        } catch (e: Exception) {
            // DB 写失败 → 回收刚写的文件，不留孤儿
            runCatching { file.delete() }
            throw ImportException(RejectReason.WRITE_FAILED, "保存元数据失败：${e.message ?: "未知原因"}")
        }

        meta
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
