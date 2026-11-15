package dev.inkleaf.core.data

import dev.inkleaf.core.common.AppDispatchers
import dev.inkleaf.core.common.DefaultAppDispatchers
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.DocumentOrigin
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.StrokeAnchor
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全库导出 / 导入（方案 §7.1 的第三层，**强制**存在的那一层）。
 *
 * 为什么必须有它：前两层（私有目录 + 可选 SAF 侧车）都在同一台设备同一个 App 里。
 * 用户换手机、清数据、装到新设备时，只有"导出一个文件、再导回来"能救回笔记。
 * 这不是 Nice-to-have，是"本地优先应用不丢数据"的唯一出口。
 *
 * ## 格式
 * ```
 * manifest.json                     全部元数据（文档 / 标注 / 笔迹索引 / 进度 / 设置）
 * documents/<相对路径>               正文副本，原样字节
 * strokes/<annotationId>/<strokeId>.ink   笔迹点位载荷（同一套二进制编码）
 * ```
 *
 * manifest **不**内联笔迹点位：手写数据是体积主体，把它塞进 JSON 会让
 * 一份档案膨胀数倍，而且 base64 之后不再是"可被其它程序读懂的"格式。
 *
 * ## 导入语义：只合并且绝不覆盖
 *
 * 同一个 id 已存在就跳过并计数。理由：这是数据恢复路径，用户对它的期待是
 * "把丢的东西找回来"，而不是"把现在的东西换掉"。任何覆盖都可能在用户
 * 无意间造成新的数据丢失 —— 而这条路径存在的全部意义就是防止丢失。
 */
class ArchiveRepository(
    private val database: InkLeafDatabase,
    private val paths: StoragePaths,
    private val annotate: AnnotationRepository,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val FORMAT_VERSION = 1
        private const val DOC_PREFIX = "documents/"
        private const val STROKE_PREFIX = "strokes/"
    }

    @Serializable
    data class Manifest(
        val format: Int = FORMAT_VERSION,
        val appVersion: String = "",
        val exportedAt: Long = 0,
        val lastOpSeq: Long = 0,
        val documents: List<ArchivedDocument> = emptyList(),
        val annotations: List<ArchivedAnnotation> = emptyList(),
        val progress: List<ArchivedProgress> = emptyList(),
        val settings: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class ArchivedDocument(
        val id: String,
        val title: String,
        val author: String? = null,
        val kind: String,
        val origin: String,
        val sourceFingerprint: String,
        val normalizationProfileId: String,
        val normalizationVersion: Int,
        val relativePath: String,
        val encoding: String,
        val byteSize: Long,
        val charCount: Int,
        val blockCount: Int,
        val importedAt: Long,
        val lastOpenedAt: Long? = null,
        val coverRelativePath: String? = null,
        val sourceUri: String? = null,
        val hasTextLayer: Boolean = true,
        val contentEntry: String,
    )

    @Serializable
    data class ArchivedStroke(
        val id: String,
        val brushId: String,
        val semantics: String,
        val anchorJson: String? = null,
        val pointCount: Int,
        val payloadEntry: String,
    )

    /**
     * 标注行。
     *
     * [anchorJson] / [extraJson] / [tagsJson] 刻意存成**字符串**（即在 JSON 里
     * 再嵌一层 JSON 文本），而不是展开成结构化的字段。
     * 原因：存档要搬的就是"数据库行"，保持行形状不变意味着
     * **只有一套映射代码**（[AnnotationCodecs]）—— 导出/导入各自再写一套
     * 结构化映射，等于给同一份数据两个会独立漂移的定义。
     */
    @Serializable
    data class ArchivedAnnotation(
        val id: String,
        val docId: String,
        val kind: String,
        val createdAt: Long,
        val colorArgb: Int,
        val tagsJson: String = "[]",
        val noteText: String? = null,
        val anchorJson: String? = null,
        val extraJson: String? = null,
        val strokes: List<ArchivedStroke> = emptyList(),
    )

    @Serializable
    data class ArchivedProgress(
        val docId: String,
        val charOffset: Int,
        val pageIndex: Int,
        val docRatio: Float,
        val updatedAt: Long,
    )

    data class ExportSummary(
        val documentCount: Int,
        val annotationCount: Int,
        val strokeCount: Int,
        val lastOpSeq: Long,
    )

    data class ImportSummary(
        val importedDocuments: Int,
        val importedAnnotations: Int,
        val skippedDocuments: Int,
        val skippedAnnotations: Int,
        val skippedStrokes: Int,
        val appliedSettings: Boolean,
    )

    class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── 导出 ─────────────────────────────────────────────────────────────────

    /**
     * 写出档案。
     *
     * **不关闭 [out]**：调用方可能把 SAF 的输出流交给我们（在那里 close 是 SDK 的责任）。
     * 用 `finish()` 写出中央目录即可结束一个合法 zip。
     */
    suspend fun exportToStream(out: OutputStream, appVersion: String): ExportSummary =
        withContext(dispatchers.io) {
            val db = database.writableDatabase
            var summary = ExportSummary(0, 0, 0, 0)

            // 整个导出在一个读事务里完成：否则"导出到一半用户又划了一笔"
            // 会让 manifest 与实际文件不一致。WAL 下这个事务不阻塞写入者。
            db.inTransaction {
                val documents = DocumentDao(db).listAll()
                val annotationRows = AnnotationDao(db).listAll()
                val progress = ProgressDao(db).all()
                val settings = SettingDao(db).all()
                val lastOpSeq = OpLogDao(db).lastSeq()

                val archivedDocuments = ArrayList<ArchivedDocument>(documents.size)
                for (meta in documents) {
                    archivedDocuments.add(
                        meta.toArchived("$DOC_PREFIX${meta.relativePath}"),
                    )
                }

                var strokeCount = 0
                val archivedAnnotations = ArrayList<ArchivedAnnotation>(annotationRows.size)
                for (row in annotationRows) {
                    val strokes = ArrayList<ArchivedStroke>()
                    for (stroke in StrokeDao(db).byAnnotation(row.id)) {
                        strokes.add(
                            ArchivedStroke(
                                id = stroke.id,
                                brushId = stroke.brushId,
                                semantics = stroke.semantics,
                                anchorJson = stroke.anchorJson,
                                pointCount = stroke.pointCount,
                                payloadEntry = "$STROKE_PREFIX${row.id}/${stroke.id}.ink",
                            ),
                        )
                        strokeCount++
                    }
                    archivedAnnotations.add(row.toArchived(strokes))
                }

                val manifest = Manifest(
                    format = FORMAT_VERSION,
                    appVersion = appVersion,
                    exportedAt = clock(),
                    lastOpSeq = lastOpSeq,
                    documents = archivedDocuments,
                    annotations = archivedAnnotations,
                    progress = progress.map { ArchivedProgress(it.docId, it.charOffset, it.pageIndex, it.docRatio, it.updatedAt) },
                    settings = settings,
                )

                val zip = ZipOutputStream(out)

                // manifest 放在第一个条目：导入时可以边流式读边按名字分发，
                // 不需要先解压整个档案再回读（这样导入的内存与磁盘都是 O(1) 之外的常数）。
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(AnchorCodec.json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                for ((index, meta) in documents.withIndex()) {
                    val file = paths.resolveDocument(meta.relativePath)
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry(archivedDocuments[index].contentEntry))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                for (row in annotationRows) {
                    for (stroke in StrokeDao(db).byAnnotation(row.id)) {
                        val payload = readStrokePayload(stroke) ?: run {
                            // 读不出来的笔迹不进档案。**不能**写一个 0 字节条目冒充它：
                            // 那会让"损坏"在导入侧变成一条合法但错位的笔迹。
                            continue
                        }
                        zip.putNextEntry(ZipEntry("$STROKE_PREFIX${row.id}/${stroke.id}.ink"))
                        zip.write(payload)
                        zip.closeEntry()
                    }
                }

                zip.finish()
                zip.flush()

                summary = ExportSummary(
                    documentCount = archivedDocuments.size,
                    annotationCount = archivedAnnotations.size,
                    strokeCount = strokeCount,
                    lastOpSeq = lastOpSeq,
                )
            }
            summary
        }

    // ── 导入 ─────────────────────────────────────────────────────────────────

    /**
     * 读入档案。
     *
     * 先整体解到临时目录再应用。代价是峰值磁盘占用 2×，换来的是
     * "可以按任意顺序访问条目"以及"应用阶段不需要与 zip 流的状态耦合"——
     * 后者在导入这么容易出错的路径上比省一半磁盘重要。
     * 临时目录在 `cacheDir/exports` 下，无论如何都会在 finally 里删掉。
     */
    suspend fun importFromStream(
        input: InputStream,
        applySettings: Boolean = false,
    ): ImportSummary = withContext(dispatchers.io) {
        val staging = File(paths.exportsDir, "import-${Ids.new()}")
        try {
            unzip(input, staging)
            val manifestFile = File(staging, MANIFEST_ENTRY)
            if (!manifestFile.exists()) {
                throw ArchiveException("档案缺少 $MANIFEST_ENTRY，不是 InkLeaf 导出的文件")
            }
            val manifest = try {
                AnchorCodec.json.decodeFromString(
                    Manifest.serializer(),
                    manifestFile.readText(Charsets.UTF_8),
                )
            } catch (e: Exception) {
                throw ArchiveException("档案清单解析失败：${e.message ?: "格式不匹配"}", e)
            }
            if (manifest.format != FORMAT_VERSION) {
                throw ArchiveException("档案格式版本为 ${manifest.format}，本版本只能读 $FORMAT_VERSION")
            }
            applyManifest(manifest, staging, applySettings)
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    private suspend fun applyManifest(
        manifest: Manifest,
        staging: File,
        applySettings: Boolean,
    ): ImportSummary {
        val db = database.writableDatabase
        var importedDocuments = 0
        var skippedDocuments = 0
        var importedAnnotations = 0
        var skippedAnnotations = 0
        var skippedStrokes = 0
        val now = clock()

        for (archived in manifest.documents) {
            val meta = archived.toMeta()
            if (meta == null) {
                skippedDocuments++
                continue
            }
            // 只合并且绝不覆盖。见类注释。
            if (DocumentDao(db).byId(meta.id) != null) {
                skippedDocuments++
                continue
            }
            val source = runCatching { paths.resolveSafely(staging, archived.contentEntry) }.getOrNull()
            if (source == null || !source.exists()) {
                skippedDocuments++
                continue
            }
            val target = paths.resolveDocument(meta.relativePath)
            target.parentFile?.mkdirs()
            runCatching { source.copyTo(target, overwrite = true) }.getOrElse {
                skippedDocuments++
                continue
            }
            db.inTransaction {
                DocumentDao(db).upsert(meta)
                OpLogDao(db).append(
                    OpTypes.RESTORE_ARCHIVE, StorageSchema.TABLE_DOCUMENT, meta.id, null, now,
                )
            }
            importedDocuments++
        }

        for (archived in manifest.annotations) {
            val row = archived.toRow()
            if (DocumentDao(db).byId(row.docId) == null) {
                skippedAnnotations++
                continue
            }
            if (AnnotationDao(db).byId(row.id) != null) {
                skippedAnnotations++
                continue
            }
            val domain = AnnotationCodecs.toDomain(row)
            if (domain == null) {
                skippedAnnotations++
                continue
            }

            if (domain is Annotation.Ink) {
                val strokes = ArrayList<StrokeAnchor>(archived.strokes.size)
                for (stroke in archived.strokes) {
                    val restored = restoreStroke(stroke, staging)
                    if (restored == null) skippedStrokes++ else strokes.add(restored)
                }
                val brushes = AnnotationCodecs.decodeExtra(row.extraJson)?.brushes?.map { it.toDomain() }
                    ?: emptyList<BrushSpec>()
                // 走仓储的写路径：内联/外置策略、事务边界、op_log 全部复用，
                // 导入不需要第两套写逻辑（那必然与正常写入漂移）。
                annotate.saveInk(domain, strokes, brushes)
            } else {
                annotate.upsert(domain)
            }
            importedAnnotations++
        }

        for (p in manifest.progress) {
            val progress = ReadingProgress(p.docId, p.charOffset, p.pageIndex, p.docRatio, p.updatedAt)
            ProgressDao(db).upsert(progress)
        }

        if (applySettings) {
            SettingDao(db).all() // 读一次确保表的读路径被热身（无副作用，仅表意）
            for ((key, value) in manifest.settings) SettingDao(db).put(key, value)
        }

        return ImportSummary(
            importedDocuments = importedDocuments,
            importedAnnotations = importedAnnotations,
            skippedDocuments = skippedDocuments,
            skippedAnnotations = skippedAnnotations,
            skippedStrokes = skippedStrokes,
            appliedSettings = applySettings,
        )
    }

    // ── 细节 ─────────────────────────────────────────────────────────────────

    private fun readStrokePayload(row: StrokeRow): ByteArray? {
        row.inlineData?.let { return it }
        val blobId = row.blobId ?: return null
        val blob = BlobDao(database.writableDatabase).byId(blobId) ?: return null
        return runCatching { paths.resolveBlob(blob.relativePath).readBytes() }.getOrNull()
    }

    private fun restoreStroke(archived: ArchivedStroke, staging: File): StrokeAnchor? {
        val payload = runCatching { paths.resolveSafely(staging, archived.payloadEntry) }.getOrNull()
            ?: return null
        if (!payload.exists()) return null
        val decoded = InkCodec.decodeOrNull(runCatching { payload.readBytes() }.getOrNull()) ?: return null
        val anchor = AnnotationCodecs.decodeSelector(archived.anchorJson) as? Selector.Structural
            ?: return null
        val semantics = enumOrNull<dev.inkleaf.core.model.InkReflowSemantics>(archived.semantics)
            ?: return null
        return StrokeAnchor(
            strokeId = archived.id,
            anchor = anchor,
            semantics = semantics,
            brushId = archived.brushId,
            localPoints = decoded.points,
            localPressures = decoded.pressures,
            // 冻结几何必须跟着走：导出包是"用户数据的完整副本"，丢了这个字段，
            // 边注/涂鸦在导入后就会从"钉在页面上"退化成"跟着最近一行走"。
            frozenMatrix = decoded.frozenMatrix,
        )
    }

    /**
     * 解压到 [target]。
     *
     * 逐条校验条目名：zip 里的路径是**外部输入**，一个
     * `../../../databases/inkleaf.db` 就能覆盖掉 App 自己的数据库。
     * [StoragePaths.resolveSafely] 已经做了规范化与越界检查，这里必须用它而不是
     * `File(staging, name)`。
     */
    private fun unzip(input: InputStream, target: File) {
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = try {
                    paths.resolveSafely(target, entry.name)
                } catch (e: IllegalArgumentException) {
                    throw ArchiveException("档案内条目路径非法：${entry.name}", e)
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun DocumentMeta.toArchived(contentEntry: String) = ArchivedDocument(
        id = id,
        title = title,
        author = author,
        kind = kind.name,
        origin = origin.name,
        sourceFingerprint = sourceFingerprint,
        normalizationProfileId = normalizationProfileId,
        normalizationVersion = normalizationVersion,
        relativePath = relativePath,
        encoding = encoding,
        byteSize = byteSize,
        charCount = charCount,
        blockCount = blockCount,
        importedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
        coverRelativePath = coverRelativePath,
        sourceUri = sourceUri,
        hasTextLayer = hasTextLayer,
        contentEntry = contentEntry,
    )

    private fun ArchivedDocument.toMeta(): DocumentMeta? {
        val kind = enumOrNull<DocumentKind>(kind) ?: return null
        val origin = enumOrNull<DocumentOrigin>(origin) ?: return null
        if (id.isBlank() || relativePath.isBlank()) return null
        return DocumentMeta(
            id = id,
            title = title,
            author = author,
            kind = kind,
            origin = origin,
            sourceFingerprint = sourceFingerprint,
            normalizationProfileId = normalizationProfileId,
            normalizationVersion = normalizationVersion,
            relativePath = relativePath,
            encoding = encoding,
            byteSize = byteSize,
            charCount = charCount,
            blockCount = blockCount,
            importedAt = importedAt,
            lastOpenedAt = lastOpenedAt,
            coverRelativePath = coverRelativePath,
            sourceUri = sourceUri,
            hasTextLayer = hasTextLayer,
        )
    }

    private fun AnnotationRow.toArchived(strokes: List<ArchivedStroke>) = ArchivedAnnotation(
        id = id,
        docId = docId,
        kind = kind,
        createdAt = createdAt,
        colorArgb = colorArgb,
        tagsJson = tagsJson,
        noteText = noteText,
        anchorJson = anchorJson,
        extraJson = extraJson,
        strokes = strokes,
    )

    private fun ArchivedAnnotation.toRow() = AnnotationRow(
        id = id,
        docId = docId,
        kind = kind,
        createdAt = createdAt,
        colorArgb = colorArgb,
        tagsJson = tagsJson,
        noteText = noteText,
        anchorJson = anchorJson,
        extraJson = extraJson,
        deleted = false,
    )
}
