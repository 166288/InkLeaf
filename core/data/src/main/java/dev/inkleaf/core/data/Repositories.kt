package dev.inkleaf.core.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.inkleaf.core.common.AppDispatchers
import dev.inkleaf.core.common.DefaultAppDispatchers
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.HighlightStyle
import dev.inkleaf.core.model.InkReflowSemantics
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.model.ReadingSession
import dev.inkleaf.core.model.Selector
import dev.inkleaf.core.model.Side
import dev.inkleaf.core.model.StrokeAnchor
import dev.inkleaf.core.model.UnderlineStyle
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 仓储层。
 *
 * 这一层承担三件 DAO 刻意不做的事：
 *   1. **事务边界**（"写笔迹要同时写 blob 行"这种原子性）；
 *   2. **领域 ↔ 存储映射**（`Annotation` 的五个子类各有一套列/JSON 分工）；
 *   3. **线程**（DAO 全是阻塞的，仓储的每个公开方法都是 `suspend` 且跑在 io 上）。
 *
 * 第 3 点刻意不交给调用方：让界面自己记得切线程，等于让"忘了切"变成一个
 * 偶发卡顿 bug。在这里一次性收口，比在每处 Compose 里写 `withContext` 可靠。
 */

internal suspend fun <T> AppDispatchers.onIo(block: () -> T): T = withContext(io) { block() }

// ── 领域 ↔ 存储 映射 ─────────────────────────────────────────────────────────

private val tagListSerializer = ListSerializer(String.serializer())

internal object AnnotationCodecs {

    fun encodeTags(tags: List<String>): String =
        AnchorCodec.json.encodeToString(tagListSerializer, tags)

    fun decodeTags(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { AnchorCodec.json.decodeFromString(tagListSerializer, json) }
            .getOrDefault(emptyList())
    }

    fun encodeExtra(extra: AnnotationExtraDto): String =
        AnchorCodec.json.encodeToString(AnnotationExtraDto.serializer(), extra)

    fun decodeExtra(json: String?): AnnotationExtraDto? {
        if (json.isNullOrBlank()) return null
        return runCatching { AnchorCodec.json.decodeFromString(AnnotationExtraDto.serializer(), json) }
            .getOrNull()
    }

    fun encodeSelector(selector: Selector): String =
        AnchorCodec.json.encodeToString(SelectorDto.serializer(), SelectorDto.of(selector))

    fun decodeSelector(json: String?): Selector? {
        if (json.isNullOrBlank()) return null
        return runCatching { AnchorCodec.json.decodeFromString(SelectorDto.serializer(), json).toDomain() }
            .getOrNull()
    }

    /** 领域 → 行。JSON 部分在这里一次写全。 */
    fun toRow(annotation: Annotation): AnnotationRow {
        val base = Builder(annotation)
        return when (annotation) {
            is Annotation.Highlight -> base.build(
                anchorJson = AnchorCodec.encode(annotation.anchor),
                extra = AnnotationExtraDto(highlightStyle = HighlightStyleDto.of(annotation.style)),
            )

            is Annotation.Ink -> base.build(
                anchorJson = annotation.anchor?.let { AnchorCodec.encode(it) },
                extra = AnnotationExtraDto(
                    semantics = annotation.semantics.name,
                    representation = annotation.representation.name,
                    strokeIds = annotation.strokeIds,
                    pages = annotation.pages,
                ),
            )

            is Annotation.MarginNote -> base.build(
                anchorJson = AnchorCodec.encode(annotation.anchor),
                extra = AnnotationExtraDto(side = annotation.side.name),
            )

            is Annotation.Underline -> base.build(
                anchorJson = null,
                extra = AnnotationExtraDto(
                    underlineStyle = UnderlineStyleDto.of(annotation.style),
                    multiAnchors = annotation.anchors.map { AnchorChainDto.of(it) },
                ),
            )

            is Annotation.Bookmark -> base.build(
                anchorJson = annotation.anchor?.let { AnchorCodec.encode(it) },
                extra = AnnotationExtraDto(progressHint = SelectorDto.of(annotation.progressHint)),
            )
        }
    }

    /**
     * 行 → 领域。**读不出来就返回 null**，绝不"用默认值凑一个"。
     *
     * 这一点是整套锚定哲学的最后一环：如果一条标注的选择器读不出来，
     * 却用一个默认的 ProgressHint 把它拼出来，那它会以"正确"的姿态
     * 出现在笔记列表里，然后指向一个错误的位置。宁可少一条、并明确报告。
     */
    fun toDomain(row: AnnotationRow): Annotation? {
        val type = enumOrNull<AnnotationType>(row.kind) ?: return null
        val tags = decodeTags(row.tagsJson)
        val extra = decodeExtra(row.extraJson)
        return when (type) {
            AnnotationType.HIGHLIGHT -> {
                val anchor = AnchorCodec.decode(row.anchorJson) ?: return null
                val style = extra?.highlightStyle?.toDomain() ?: HighlightStyle()
                Annotation.Highlight(row.id, row.docId, row.createdAt, row.colorArgb, tags, row.noteText, anchor, style)
            }

            AnnotationType.MARGIN_NOTE -> {
                val anchor = AnchorCodec.decode(row.anchorJson) ?: return null
                val side = extra?.sideOrNull() ?: Side.RIGHT
                Annotation.MarginNote(row.id, row.docId, row.createdAt, row.colorArgb, tags, row.noteText, anchor, side)
            }

            AnnotationType.UNDERLINE -> {
                val dto = extra ?: return null
                val anchors = dto.multiAnchors.map { it.toDomain() }
                // 有一条链读不出来就整条标注作废：下划线是"逐行"的，
                // 少了中间一行会变成一段看着对、实际断开的线。
                if (anchors.isEmpty() || anchors.any { it == null }) return null
                val style = dto.underlineStyle?.toDomain() ?: UnderlineStyle()
                Annotation.Underline(
                    row.id, row.docId, row.createdAt, row.colorArgb, tags, row.noteText,
                    @Suppress("UNCHECKED_CAST")
                    anchors as List<dev.inkleaf.core.model.TextAnchorChain>,
                    style,
                )
            }

            AnnotationType.BOOKMARK -> {
                val hintDto = extra?.progressHint ?: return null
                val hint = hintDto.toDomain() as? Selector.ProgressHint ?: return null
                Annotation.Bookmark(
                    row.id, row.docId, row.createdAt, row.colorArgb, tags, row.noteText,
                    hint, AnchorCodec.decode(row.anchorJson),
                )
            }

            AnnotationType.INK -> {
                val dto = extra ?: return null
                val semantics: InkReflowSemantics = dto.semanticsOrNull() ?: return null
                Annotation.Ink(
                    id = row.id,
                    docId = row.docId,
                    createdAt = row.createdAt,
                    colorArgb = row.colorArgb,
                    tags = tags,
                    noteText = row.noteText,
                    anchor = AnchorCodec.decode(row.anchorJson),
                    semantics = semantics,
                    representation = dto.representationOrNull() ?: dev.inkleaf.core.model.InkRepresentation.VECTOR,
                    strokeIds = dto.strokeIds,
                    pages = dto.pages,
                )
            }
        }
    }

    private class Builder(private val annotation: Annotation) {
        fun build(anchorJson: String?, extra: AnnotationExtraDto) = AnnotationRow(
            id = annotation.id,
            docId = annotation.docId,
            kind = AnnotationType.of(annotation).name,
            createdAt = annotation.createdAt,
            colorArgb = annotation.colorArgb,
            tagsJson = encodeTags(annotation.tags),
            noteText = annotation.noteText,
            anchorJson = anchorJson,
            extraJson = encodeExtra(extra),
            deleted = false,
        )
    }
}

// ── 加载结果 ────────────────────────────────────────────────────────────────

/**
 * 一个文档的全部笔记 + **读取诊断**。
 *
 * [unreadableAnnotationCount] / [unreadableStrokeCount] 是刻意暴露给界面的：
 * 如果一条笔记的锚点链读不出来，界面必须能告诉用户"有 N 条笔记无法定位"，
 * 而不是让它消失。数据损坏时最糟糕的产品行为是静默少一条。
 */
data class AnnotationSnapshot(
    val annotations: List<Annotation>,
    val strokesByAnnotation: Map<String, List<StrokeAnchor>>,
    val brushesByAnnotation: Map<String, List<BrushSpec>>,
    val unreadableAnnotationCount: Int,
    val unreadableStrokeCount: Int,
) {
    val isEmpty: Boolean get() = annotations.isEmpty()

    fun strokesOf(annotationId: String): List<StrokeAnchor> =
        strokesByAnnotation[annotationId].orEmpty()

    fun brushesOf(annotationId: String): List<BrushSpec> =
        brushesByAnnotation[annotationId].orEmpty()

    companion object {
        val EMPTY = AnnotationSnapshot(emptyList(), emptyMap(), emptyMap(), 0, 0)
    }
}

// ── 文档仓储 ────────────────────────────────────────────────────────────────

class DocumentRepository(
    private val database: InkLeafDatabase,
    private val paths: StoragePaths,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 派生缓存（封面）。
     *
     * 默认值用 [paths] 现造一个，而不是从 [InkLeafStore] 传进来 ——
     * 它没有任何状态，两处造出来也是同一个目录，同一份命名规则。
     */
    private val derived: DerivedCache = DerivedCache(paths),
) {
    private val db get() = database.writableDatabase

    suspend fun list(): List<DocumentMeta> = dispatchers.onIo { DocumentDao(db).listAll() }

    suspend fun byId(id: String): DocumentMeta? = dispatchers.onIo { DocumentDao(db).byId(id) }

    suspend fun count(): Int = dispatchers.onIo { DocumentDao(db).count() }

    suspend fun markOpened(id: String): Unit = dispatchers.onIo {
        DocumentDao(db).markOpened(id, clock())
    }

    /** 读取私有正文副本。文件不存在时抛 [DocumentFileMissingException]。 */
    suspend fun readBytes(meta: DocumentMeta): ByteArray = dispatchers.onIo {
        val file = paths.resolveDocument(meta.relativePath)
        if (!file.exists()) throw DocumentFileMissingException(meta.id, meta.relativePath)
        file.readBytes()
    }

    suspend fun delete(id: String): Unit = dispatchers.onIo {
        val strokeDao = StrokeDao(db)
        val blobDao = BlobDao(db)
        val documentDao = DocumentDao(db)
        val meta = documentDao.byId(id)

        val removedBlobPaths = db.inTransaction {
            val stale = strokeDao.byDoc(id)
                .mapNotNull { it.blobId }
                .distinct()
                .mapNotNull { blobId -> blobDao.byId(blobId)?.also { blobDao.delete(blobId) } }
                .map { it.relativePath }

            // 外键级联会带走 annotation / stroke / reading_progress，
            // 但 external_blob 不是任何表的外键子表 → 必须显式删，否则文件永远没人回收。
            documentDao.delete(id)
            OpLogDao(db).append(
                OpTypes.DELETE_DOCUMENT, StorageSchema.TABLE_DOCUMENT, id, null, clock(),
            )
            stale
        }

        // 文件删除放在提交之后：提交回滚时文件还在、DB 说"还在"，
        // 状态一致；反过来（先删文件再回滚）会出现"DB 指向不存在的文件"。
        for (relative in removedBlobPaths) {
            runCatching { paths.resolveBlob(relative).delete() }
        }
        meta?.let { runCatching { paths.resolveDocument(it.relativePath).delete() } }
        // 封面缓存也一起清：文档没了，它的封面就是纯垃圾。
        // 不清的话 derived/ 只增不减，导入几十本书之后能攒出几十兆。
        runCatching { derived.clearDocument(id) }
    }

    /**
     * 清掉孤儿派生缓存：属于已经不存在的文档的封面文件。
     *
     * 什么时候需要它：删文档的路径已经会清自己的封面了，但还有两条漏网 ——
     * 旧版本删的、以及导入/删除过程中崩在中间。
     * 这些文件既占地方，又在书架点开时让人以为"还有一本没显示的书"。
     */
    suspend fun sweepOrphanDerived(): Int = dispatchers.onIo {
        val liveIds = DocumentDao(db).listAll().map { it.id }.toSet()
        derived.clearOrphanCovers(liveIds)
    }

    /** 派生缓存当前占用。设置页的"清理缓存"要报出清理前有多少。 */
    suspend fun derivedUsage(): DerivedCache.Usage = dispatchers.onIo { derived.usage() }

    /** 清空派生缓存，返回清掉了多少。 */
    suspend fun clearDerived(): DerivedCache.Cleared = dispatchers.onIo { derived.clearAll() }

    suspend fun saveProgress(progress: ReadingProgress): Unit = dispatchers.onIo {
        db.inTransaction {
            ProgressDao(db).upsert(progress)
            OpLogDao(db).append(
                OpTypes.SET_PROGRESS, StorageSchema.TABLE_PROGRESS, progress.docId, null, clock(),
            )
        }
    }

    suspend fun progressOf(docId: String): ReadingProgress? =
        dispatchers.onIo { ProgressDao(db).byDoc(docId) }

    suspend fun allProgress(): List<ReadingProgress> = dispatchers.onIo { ProgressDao(db).all() }

    /** 维护：回收没有任何 stroke 引用的外置 blob（行 + 文件）。 */
    suspend fun sweepOrphanBlobs(): Int = dispatchers.onIo {
        val blobDao = BlobDao(db)
        val orphans = db.inTransaction {
            val ids = blobDao.orphanIds()
            val rows = ids.mapNotNull { blobDao.byId(it) }
            blobDao.deleteAll(ids)
            rows
        }
        for (row in orphans) runCatching { paths.resolveBlob(row.relativePath).delete() }
        orphans.size
    }
}

class DocumentFileMissingException(val docId: String, val relativePath: String) :
    IllegalStateException("文档正文副本不存在：$relativePath（docId=$docId）")

// ── 阅读会话仓储（M12 统计）─────────────────────────────────────────────────

/**
 * 阅读时长。
 *
 * 一段会话就是"阅读界面在前台待了一会儿"。写入频率很低（切后台 / 退出），
 * 所以不做批量、不做 upsert：每一段是**独立的事实**，改一段就等于改历史。
 *
 * 不写 op_log：统计不是用户内容，不需要参与撤销或同步；把它塞进操作日志
 * 只会让"历史里有什么"多一种没人会去看的记录。
 */
class ReadingSessionRepository(
    private val database: InkLeafDatabase,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = database.writableDatabase

    /**
     * 记录一段阅读。
     *
     * 时长为 0 或负数直接丢弃：那通常意味着"刚进来就退出"，
     * 让它进库会把连续天数算成"今天读过"——用户会看到自己没读的日子被点亮。
     */
    suspend fun record(docId: String, startedAt: Long, endedAt: Long, durationMs: Long): Boolean =
        dispatchers.onIo {
            if (durationMs <= 0 || docId.isBlank()) return@onIo false
            ReadingSessionDao(db).insert(
                ReadingSession(
                    id = Ids.new(),
                    docId = docId,
                    startedAt = startedAt,
                    endedAt = endedAt.coerceAtLeast(startedAt),
                    durationMs = durationMs,
                ),
            )
            true
        }

    suspend fun all(): List<ReadingSession> = dispatchers.onIo { ReadingSessionDao(db).all() }

    suspend fun since(fromMs: Long): List<ReadingSession> =
        dispatchers.onIo { ReadingSessionDao(db).since(fromMs) }

    suspend fun count(): Int = dispatchers.onIo { ReadingSessionDao(db).count() }

    /** 最近的会话。统计页只需要近期数据，不必把几年的行都读进内存。 */
    suspend fun recent(limit: Int = 2_000): List<ReadingSession> = dispatchers.onIo {
        val all = ReadingSessionDao(db).all()
        if (all.size <= limit) all else all.takeLast(limit)
    }
}

// ── 标注仓储 ────────────────────────────────────────────────────────────────

class AnnotationRepository(
    private val database: InkLeafDatabase,
    private val paths: StoragePaths,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = database.writableDatabase

    /** 内联上限。超过就外置成文件 —— 理由见 [Repositories.kt] 顶部与方案 §7.2。 */
    companion object {
        const val INLINE_BLOB_LIMIT_BYTES = 64 * 1024
    }

    suspend fun listByDoc(docId: String): AnnotationSnapshot = dispatchers.onIo {
        val rows = AnnotationDao(db).byDoc(docId)
        val strokeRows = StrokeDao(db).byDoc(docId)

        val annotations = ArrayList<Annotation>(rows.size)
        var unreadableAnnotations = 0
        for (row in rows) {
            val domain = AnnotationCodecs.toDomain(row)
            if (domain == null) unreadableAnnotations++ else annotations.add(domain)
        }

        val byAnnotation = LinkedHashMap<String, MutableList<StrokeAnchor>>()
        var unreadableStrokes = 0
        for (row in strokeRows) {
            val stroke = readStroke(row)
            if (stroke == null) {
                unreadableStrokes++
            } else {
                byAnnotation.getOrPut(row.annotationId) { ArrayList() }.add(stroke)
            }
        }

        val brushes = LinkedHashMap<String, List<BrushSpec>>()
        for (row in rows) {
            val dto = AnnotationCodecs.decodeExtra(row.extraJson) ?: continue
            if (dto.brushes.isEmpty()) continue
            brushes[row.id] = dto.brushes.map { it.toDomain() }
        }

        AnnotationSnapshot(
            annotations = annotations,
            strokesByAnnotation = byAnnotation,
            brushesByAnnotation = brushes,
            unreadableAnnotationCount = unreadableAnnotations,
            unreadableStrokeCount = unreadableStrokes,
        )
    }

    suspend fun listAll(): List<Annotation> = dispatchers.onIo {
        AnnotationDao(db).listAll().mapNotNull(AnnotationCodecs::toDomain)
    }

    suspend fun upsert(annotation: Annotation): Unit = dispatchers.onIo {
        db.inTransaction {
            AnnotationDao(db).upsert(AnnotationCodecs.toRow(annotation))
            OpLogDao(db).append(
                OpTypes.UPSERT_ANNOTATION, StorageSchema.TABLE_ANNOTATION, annotation.id, null, clock(),
            )
        }
    }

    /**
     * 写入一段手写笔迹。
     *
     * 事务语义（方案 §7.2 的硬要求）：
     *   标注行、blob 行、stroke 行在**同一个事务**里写。否则崩溃在中间时
     *   会出现"DB 说有这条笔迹、文件却没有"这种读不出来的状态。
     *   文件**写入**也在事务内做（写入失败会让整个事务回滚，这是想要的）；
     *   文件**删除**放到提交之后（见 [DocumentRepository.delete] 的同类说明）。
     */
    suspend fun saveInk(
        annotation: Annotation.Ink,
        strokes: List<StrokeAnchor>,
        brushes: List<BrushSpec>,
    ): Unit = dispatchers.onIo {
        val strokeDao = StrokeDao(db)
        val blobDao = BlobDao(db)
        val now = clock()

        val removedBlobPaths = db.inTransaction {
            val row = AnnotationCodecs.toRow(annotation).copy(
                extraJson = AnnotationCodecs.encodeExtra(
                    AnnotationExtraDto(
                        semantics = annotation.semantics.name,
                        representation = annotation.representation.name,
                        strokeIds = strokes.map { it.strokeId },
                        pages = annotation.pages,
                        brushes = brushes.map { BrushSpecDto.of(it) },
                    ),
                ),
            )
            AnnotationDao(db).upsert(row)

            val stale = ArrayList<String>()
            for (old in strokeDao.deleteByAnnotation(annotation.id)) {
                val blobId = old.blobId ?: continue
                blobDao.byId(blobId)?.let { stale.add(it.relativePath) }
                blobDao.delete(blobId)
            }

            for (stroke in strokes) strokeDao.upsert(toRow(stroke, annotation.id, annotation.docId, now))

            OpLogDao(db).append(
                OpTypes.UPSERT_STROKES, StorageSchema.TABLE_STROKE, annotation.id, null, now,
            )
            stale
        }

        for (relative in removedBlobPaths) runCatching { paths.resolveBlob(relative).delete() }
    }

    /**
     * 软删除标注，并**硬删**它的笔迹。
     *
     * 笔迹不软删：它是唯一带外置文件的数据，软删会让文件永久悬空。
     * 标注本身保留（可撤销），但它的笔迹没了 —— 这个取舍是有意的：
     * 撤销删除后手写内容不会回来，界面上必须说清楚，不能假装还在。
     */
    suspend fun delete(annotationId: String): Unit = dispatchers.onIo {
        val strokeDao = StrokeDao(db)
        val blobDao = BlobDao(db)
        val removedBlobPaths = db.inTransaction {
            val stale = ArrayList<String>()
            for (old in strokeDao.deleteByAnnotation(annotationId)) {
                val blobId = old.blobId ?: continue
                blobDao.byId(blobId)?.let { stale.add(it.relativePath) }
                blobDao.delete(blobId)
            }
            AnnotationDao(db).softDelete(annotationId)
            OpLogDao(db).append(
                OpTypes.DELETE_ANNOTATION, StorageSchema.TABLE_ANNOTATION, annotationId, null, clock(),
            )
            stale
        }
        for (relative in removedBlobPaths) runCatching { paths.resolveBlob(relative).delete() }
    }

    /**
     * 撤销一次软删除 —— [delete] 的逆操作。
     *
     * 它恢复的只是**那一行标注**：`delete` 会把笔迹硬删掉（理由见那里），
     * 所以撤销一条手写笔迹只会得到一条"有锚点、没有墨迹"的记录。
     * 打字批注没有笔迹，撤销是完整的 —— 阅读界面因此只让打字批注走"删除 / 撤销"
     * （见 M3 §25：手写的删除是**整笔擦除**，不是按段删）。
     *
     * @return 真的把一条已删除的标注放回去了才返回 true。本来就活着（或已经撤销过）时
     *         返回 false：界面不该把一次空操作报成"已撤销"。
     */
    suspend fun restore(annotationId: String): Boolean = dispatchers.onIo {
        var restored = false
        db.inTransaction {
            restored = AnnotationDao(db).undelete(annotationId) > 0
            if (restored) {
                OpLogDao(db).append(
                    OpTypes.RESTORE_ANNOTATION, StorageSchema.TABLE_ANNOTATION, annotationId, null, clock(),
                )
            }
        }
        restored
    }

    private fun toRow(stroke: StrokeAnchor, annotationId: String, docId: String, now: Long): StrokeRow {
        val bytes = InkCodec.encode(stroke.localPoints, stroke.localPressures, stroke.frozenMatrix)
        val anchorJson = AnnotationCodecs.encodeSelector(stroke.anchor)
        val pointCount = stroke.localPoints.size / 2

        return if (bytes.size > INLINE_BLOB_LIMIT_BYTES) {
            val blobId = Ids.new()
            val relative = paths.newBlobRelativePath(blobId)
            val file = paths.resolveBlob(relative)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            BlobDao(db).insert(BlobRow(blobId, relative, bytes.size.toLong(), now))
            StrokeRow(
                id = stroke.strokeId,
                annotationId = annotationId,
                docId = docId,
                brushId = stroke.brushId,
                semantics = stroke.semantics.name,
                anchorJson = anchorJson,
                pointCount = pointCount,
                blobId = blobId,
                inlineData = null,
                createdAt = now,
            )
        } else {
            StrokeRow(
                id = stroke.strokeId,
                annotationId = annotationId,
                docId = docId,
                brushId = stroke.brushId,
                semantics = stroke.semantics.name,
                anchorJson = anchorJson,
                pointCount = pointCount,
                blobId = null,
                inlineData = bytes,
                createdAt = now,
            )
        }
    }

    private fun readStroke(row: StrokeRow): StrokeAnchor? {
        val bytes = if (row.blobId != null) {
            val blob = BlobDao(db).byId(row.blobId) ?: return null
            runCatching { paths.resolveBlob(blob.relativePath).readBytes() }.getOrNull() ?: return null
        } else {
            row.inlineData ?: return null
        }
        val decoded = InkCodec.decodeOrNull(bytes) ?: return null
        val anchor = AnnotationCodecs.decodeSelector(row.anchorJson) as? Selector.Structural ?: return null
        val semantics = enumOrNull<InkReflowSemantics>(row.semantics) ?: return null
        return StrokeAnchor(
            strokeId = row.id,
            anchor = anchor,
            semantics = semantics,
            brushId = row.brushId,
            localPoints = decoded.points,
            localPressures = decoded.pressures,
            frozenMatrix = decoded.frozenMatrix,
        )
    }
}

// ── 设置仓储 ────────────────────────────────────────────────────────────────

/**
 * 设置存放于 SQLite（而非 DataStore）。
 *
 * 这不是"顺手放的"：全库导出必须把设置一起带走，而设置存在同一个
 * 数据库文件里就意味着"导出 = 读同一个事务快照"，不需要再协调两处状态。
 */
class SettingsRepository(
    private val database: InkLeafDatabase,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    private val db get() = database.writableDatabase

    suspend fun all(): Map<String, String> = dispatchers.onIo { SettingDao(db).all() }

    suspend fun get(key: String): String? = dispatchers.onIo { SettingDao(db).get(key) }

    suspend fun put(key: String, value: String): Unit = dispatchers.onIo {
        db.inTransaction {
            SettingDao(db).put(key, value)
            OpLogDao(db).append(
                OpTypes.SET_SETTING, StorageSchema.TABLE_SETTING, key,
                """{"value":${jsonString(value)}}""",
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun putAll(values: Map<String, String>): Unit = dispatchers.onIo {
        db.inTransaction {
            val dao = SettingDao(db)
            for ((key, value) in values) dao.put(key, value)
        }
    }

    suspend fun getFloat(key: String, fallback: Float): Float =
        get(key)?.toFloatOrNull() ?: fallback

    suspend fun getInt(key: String, fallback: Int): Int = get(key)?.toIntOrNull() ?: fallback

    suspend fun getBoolean(key: String, fallback: Boolean): Boolean =
        get(key)?.toBooleanStrictOrNull() ?: fallback

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

// ── 手写依赖容器 ────────────────────────────────────────────────────────────

/**
 * 存储层的依赖容器（替代方案 §8 里的 Hilt）。
 *
 * 为什么手写就够：本工程只有**一个**作用域（进程）与十来处依赖，
 * 没有多绑定、没有作用域嵌套、没有需要动态替换的接口。
 * Hilt 在这里能省下的唯一东西是"少写一个构造函数"，
 * 而它要求一条注解处理链路（AGP 9 内建 Kotlin 下不存在，见 design/M1 记录）。
 */
class InkLeafStore(
    context: Context,
    val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    clock: () -> Long = System::currentTimeMillis,
    /**
     * PDF 页源工厂。默认是平台实现；测试可以注入假页源 ——
     * Robolectric 里没有 `PdfRenderer` 的影子，真实现连一次都跑不通。
     */
    pdfPageSourceFactory: dev.inkleaf.core.document.PdfPageSource.Factory =
        dev.inkleaf.core.document.PlatformPdfPageSource.factory(context.applicationContext.cacheDir),
) {
    private val appContext = context.applicationContext

    val paths: StoragePaths = StoragePaths(appContext)

    /**
     * 派生缓存（封面）。
     *
     * 暴露成 store 的一部分，是为了让"封面叫什么名字"只有一个定义：
     * 书架取缓存、删除文档时清缓存、设置页清缓存，用的都是这一套。
     * 三处各写一遍前缀，迟早有一处写错 —— 然后表现为"删了文档，缓存还在涨"。
     */
    val derived: DerivedCache = DerivedCache(paths)

    private val database: InkLeafDatabase = InkLeafDatabase(appContext)

    val documents: DocumentRepository = DocumentRepository(database, paths, dispatchers, clock, derived)

    val annotations: AnnotationRepository = AnnotationRepository(database, paths, dispatchers, clock)

    val readingSessions: ReadingSessionRepository =
        ReadingSessionRepository(database, dispatchers, clock)

    val settings: SettingsRepository = SettingsRepository(database, dispatchers)

    val importer: DocumentImporter = DocumentImporter(
        database = database,
        paths = paths,
        dispatchers = dispatchers,
        clock = clock,
        // PDF 页源是平台实现（PdfRenderer）；把"不可验证的那一层"挡在 core:data 之外。
        pdf = pdfPageSourceFactory,
    )

    val archive: ArchiveRepository = ArchiveRepository(database, paths, annotate = annotations, clock = clock)

    /** 打开数据库。首次调用会建表（或跑迁移）。放显式方法里，读起来比隐式触发清楚。 */
    fun warmUp() {
        database.writableDatabase
    }

    /**
     * 关闭数据库连接。
     *
     * 正常运行时不需要调用（进程退出由系统回收）。存在是为了让**设备端测试**
     * 能在用例之间真正清空数据库文件 —— 连接还开着时 `deleteDatabase` 不会生效，
     * 于是"每个用例从空库开始"会变成一个静默失效的前提。
     */
    fun close() {
        database.close()
    }

    /**
     * 供维护/导出使用的一致性快照：全部读取发生在同一个事务里。
     *
     * `internal` 而不是 public：它返回的是**数据库行形状**（[AnnotationRow] 等），
     * 把它们暴露成公开 API 等于把存储细节变成对外契约，
     * 以后改一列就会变成一次破坏性变更。需要这些数据的是同模块的
     * [ArchiveRepository] 与维护任务，都用不上这层公开。
     */
    internal fun <T> readSnapshot(block: (SnapshotReader) -> T): T {
        val db = database.writableDatabase
        return db.inTransaction { block(SnapshotReader(db)) }
    }
}

internal class SnapshotReader(private val db: SQLiteDatabase) {
    fun documents(): List<DocumentMeta> = DocumentDao(db).listAll()
    fun annotations(): List<AnnotationRow> = AnnotationDao(db).listAll()
    fun strokes(): List<StrokeRow> =
        db.query(StorageSchema.TABLE_STROKE, null, null, null, null, null, null)
            .use { cursor ->
                val out = ArrayList<StrokeRow>()
                while (cursor.moveToNext()) {
                    out.add(readStrokeRow(cursor))
                }
                out
            }

    fun blobs(): List<BlobRow> = BlobDao(db).all()
    fun progress(): List<ReadingProgress> = ProgressDao(db).all()
    fun settings(): Map<String, String> = SettingDao(db).all()
    fun lastOpSeq(): Long = OpLogDao(db).lastSeq()

    private fun readStrokeRow(cursor: Cursor): StrokeRow = StrokeRow(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        annotationId = cursor.getString(cursor.getColumnIndexOrThrow("annotation_id")),
        docId = cursor.getString(cursor.getColumnIndexOrThrow("doc_id")),
        brushId = cursor.getString(cursor.getColumnIndexOrThrow("brush_id")),
        semantics = cursor.getString(cursor.getColumnIndexOrThrow("semantics")),
        anchorJson = cursor.getColumnIndexOrThrow("anchor_json")
            .let { if (cursor.isNull(it)) null else cursor.getString(it) },
        pointCount = cursor.getInt(cursor.getColumnIndexOrThrow("point_count")),
        blobId = cursor.getColumnIndexOrThrow("blob_id")
            .let { if (cursor.isNull(it)) null else cursor.getString(it) },
        inlineData = cursor.getColumnIndexOrThrow("inline_data")
            .let { if (cursor.isNull(it)) null else cursor.getBlob(it) },
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
    )
}
