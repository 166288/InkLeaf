package dev.inkleaf.core.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.DocumentOrigin
import dev.inkleaf.core.model.ReadingProgress
import dev.inkleaf.core.model.ReadingSession
import java.util.UUID

/**
 * 行级 DAO。
 *
 * 风格上的一条约束：**DAO 不开启事务、不做业务判断、不认识 `Annotation` 领域类型**。
 * 事务边界与领域映射都在仓储层（[Repositories.kt]），因为
 * "写笔迹要同时写 blob 行和 stroke 行"这种原子性是业务语义，
 * 放在 DAO 里会让同一张表出现多个语义不同的事务边界。
 */

/** 生成主键。UUID 而不是自增整数：导出/导入合并两份库时不会撞 id。 */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

// ── Cursor 读取小工具 ────────────────────────────────────────────────────────
//
// 每个 getColumnIndexOrThrow 都是一次线性查找，所以不追求"缓存下标"这种优化：
// 这里的调用频率是"每人每文档几百次"，不是渲染热点。

private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name))

private fun Cursor.strOrNull(name: String): String? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

private fun Cursor.longOrNull(name: String): Long? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

private fun Cursor.bool(name: String): Boolean = int(name) != 0

private fun Cursor.float(name: String): Float = getFloat(getColumnIndexOrThrow(name))

private fun Cursor.double(name: String): Double = getDouble(getColumnIndexOrThrow(name))

private fun Cursor.blobOrNull(name: String): ByteArray? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getBlob(index)
}

/**
 * 遍历游标。
 *
 * 刻意声明成 inline + 具名函数引用的用法（`cursor.mapRows(::readMeta)`）：
 * 若把行映射写成**内联 lambda**，里面的 `?: return null` 会变成对**外层函数**的
 * 非局部返回，于是"跳过这一行"会静默变成"丢掉整个结果集"。
 * 用具名函数做映射点可以让这个陷阱在编译期就不可能发生。
 */
private inline fun <T : Any> Cursor.mapRows(read: (Cursor) -> T?): List<T> {
    val out = ArrayList<T>()
    while (moveToNext()) {
        val item = read(this)
        if (item != null) out.add(item)
    }
    return out
}

// ── document ────────────────────────────────────────────────────────────────

internal class DocumentDao(private val db: SQLiteDatabase) {

    fun upsert(meta: DocumentMeta) {
        val values = toValues(meta)
        val updated = db.update(StorageSchema.TABLE_DOCUMENT, values, "id = ?", arrayOf(meta.id))
        if (updated == 0) db.insertOrThrow(StorageSchema.TABLE_DOCUMENT, null, values)
    }

    fun byId(id: String): DocumentMeta? =
        db.query(
            StorageSchema.TABLE_DOCUMENT, null, "id = ?", arrayOf(id),
            null, null, null, "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) readMeta(cursor) else null
        }

    fun listAll(): List<DocumentMeta> =
        db.query(
            StorageSchema.TABLE_DOCUMENT, null, null, null, null, null,
            "COALESCE(last_opened_at, imported_at) DESC, title ASC",
        ).use { it.mapRows(::readMeta) }

    /**
     * 硬删除。
     *
     * 文档不做软删除：它带着一个正文副本文件，"删了但还占着磁盘"是最容易招致
     * 用户投诉的状态。笔记靠 `ON DELETE CASCADE` 一起走（外键在 [InkLeafDatabase] 打开），
     * 删除动作本身记进 op_log，因此仍可追溯。
     */
    fun delete(id: String): Int = db.delete(StorageSchema.TABLE_DOCUMENT, "id = ?", arrayOf(id))

    fun markOpened(id: String, at: Long): Int = db.update(
        StorageSchema.TABLE_DOCUMENT,
        contentValuesOf("last_opened_at" to at),
        "id = ?", arrayOf(id),
    )

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_DOCUMENT}", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    private fun toValues(meta: DocumentMeta) = contentValuesOf(
        "id" to meta.id,
        "title" to meta.title,
        "author" to meta.author,
        "kind" to meta.kind.name,
        "origin" to meta.origin.name,
        "source_fingerprint" to meta.sourceFingerprint,
        "normalization_profile_id" to meta.normalizationProfileId,
        "normalization_version" to meta.normalizationVersion,
        "relative_path" to meta.relativePath,
        "encoding" to meta.encoding,
        "byte_size" to meta.byteSize,
        "char_count" to meta.charCount,
        "block_count" to meta.blockCount,
        "imported_at" to meta.importedAt,
        "last_opened_at" to meta.lastOpenedAt,
        "cover_relative_path" to meta.coverRelativePath,
        "source_uri" to meta.sourceUri,
        "has_text_layer" to if (meta.hasTextLayer) 1 else 0,
    )

    private fun readMeta(cursor: Cursor): DocumentMeta? {
        val kind = enumOrNull<DocumentKind>(cursor.str("kind")) ?: return null
        val origin = enumOrNull<DocumentOrigin>(cursor.str("origin")) ?: return null
        return DocumentMeta(
            id = cursor.str("id"),
            title = cursor.str("title"),
            author = cursor.strOrNull("author"),
            kind = kind,
            origin = origin,
            sourceFingerprint = cursor.str("source_fingerprint"),
            normalizationProfileId = cursor.str("normalization_profile_id"),
            normalizationVersion = cursor.int("normalization_version"),
            relativePath = cursor.str("relative_path"),
            encoding = cursor.str("encoding"),
            byteSize = cursor.long("byte_size"),
            charCount = cursor.int("char_count"),
            blockCount = cursor.int("block_count"),
            importedAt = cursor.long("imported_at"),
            lastOpenedAt = cursor.longOrNull("last_opened_at"),
            coverRelativePath = cursor.strOrNull("cover_relative_path"),
            sourceUri = cursor.strOrNull("source_uri"),
            hasTextLayer = cursor.bool("has_text_layer"),
        )
    }
}

// ── annotation ──────────────────────────────────────────────────────────────

/**
 * 标注的**数据库形状**。
 *
 * 与领域类型分开：`AnnotationExtraDto`/`AnchorCodec` 管 JSON 内容，
 * 这一层只管"哪一列装什么"。领域映射见 [AnnotationRows]。
 */
internal data class AnnotationRow(
    val id: String,
    val docId: String,
    val kind: String,
    val createdAt: Long,
    val colorArgb: Int,
    val tagsJson: String,
    val noteText: String?,
    val anchorJson: String?,
    val extraJson: String?,
    val deleted: Boolean,
)

internal class AnnotationDao(private val db: SQLiteDatabase) {

    fun upsert(row: AnnotationRow) {
        val values = toValues(row)
        val updated = db.update(StorageSchema.TABLE_ANNOTATION, values, "id = ?", arrayOf(row.id))
        if (updated == 0) db.insertOrThrow(StorageSchema.TABLE_ANNOTATION, null, values)
    }

    fun byId(id: String): AnnotationRow? =
        db.query(
            StorageSchema.TABLE_ANNOTATION, null, "id = ?", arrayOf(id),
            null, null, null, "1",
        ).use { if (it.moveToFirst()) readRow(it) else null }

    fun byDoc(docId: String): List<AnnotationRow> =
        db.query(
            StorageSchema.TABLE_ANNOTATION, null, "doc_id = ? AND deleted = 0", arrayOf(docId),
            null, null, "created_at ASC",
        ).use { it.mapRows(::readRow) }

    fun listAll(): List<AnnotationRow> =
        db.query(
            StorageSchema.TABLE_ANNOTATION, null, "deleted = 0", null,
            null, null, "created_at ASC",
        ).use { it.mapRows(::readRow) }

    /**
     * 软删除。
     *
     * 标注可以软删而文档不可以：复盘一条笔记的代价远高于腾出几 KB，
     * 而且 op_log 已经记了删除动作，软删让"误删了刚写的笔记"还有救。
     * 代价是所有读取路径都必须带 `deleted = 0`，这正是在这里集中写死的原因。
     */
    fun softDelete(id: String): Int = db.update(
        StorageSchema.TABLE_ANNOTATION, contentValuesOf("deleted" to 1), "id = ?", arrayOf(id),
    )

    /**
     * 撤销软删除（[softDelete] 的逆操作）。
     *
     * `WHERE ... AND deleted = 1` 是有意的：返回 0 就说明"它本来就没被删"，
     * 调用方可以据此如实回答，而不是把一次空操作报成"已撤销"。
     *
     * 只把 `deleted` 翻回来 —— 别的列在软删时根本没动过，所以"撤销"是逐字节完整的。
     */
    fun undelete(id: String): Int = db.update(
        StorageSchema.TABLE_ANNOTATION, contentValuesOf("deleted" to 0), "id = ? AND deleted = 1", arrayOf(id),
    )

    fun countByDoc(docId: String): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM ${StorageSchema.TABLE_ANNOTATION} WHERE doc_id = ? AND deleted = 0",
            arrayOf(docId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun toValues(row: AnnotationRow) = contentValuesOf(
        "id" to row.id,
        "doc_id" to row.docId,
        "kind" to row.kind,
        "created_at" to row.createdAt,
        "color_argb" to row.colorArgb,
        "tags_json" to row.tagsJson,
        "note_text" to row.noteText,
        "anchor_json" to row.anchorJson,
        "extra_json" to row.extraJson,
        "deleted" to if (row.deleted) 1 else 0,
    )

    private fun readRow(cursor: Cursor) = AnnotationRow(
        id = cursor.str("id"),
        docId = cursor.str("doc_id"),
        kind = cursor.str("kind"),
        createdAt = cursor.long("created_at"),
        colorArgb = cursor.int("color_argb"),
        tagsJson = cursor.str("tags_json"),
        noteText = cursor.strOrNull("note_text"),
        anchorJson = cursor.strOrNull("anchor_json"),
        extraJson = cursor.strOrNull("extra_json"),
        deleted = cursor.bool("deleted"),
    )
}

// ── stroke / external_blob ──────────────────────────────────────────────────

internal data class StrokeRow(
    val id: String,
    val annotationId: String,
    val docId: String,
    val brushId: String,
    val semantics: String,
    val anchorJson: String?,
    val pointCount: Int,
    val blobId: String?,
    val inlineData: ByteArray?,
    val createdAt: Long,
)

internal data class BlobRow(
    val id: String,
    val relativePath: String,
    val byteSize: Long,
    val createdAt: Long,
)

internal class StrokeDao(private val db: SQLiteDatabase) {

    fun upsert(row: StrokeRow) {
        val values = toValues(row)
        val updated = db.update(StorageSchema.TABLE_STROKE, values, "id = ?", arrayOf(row.id))
        if (updated == 0) db.insertOrThrow(StorageSchema.TABLE_STROKE, null, values)
    }

    fun byAnnotation(annotationId: String): List<StrokeRow> =
        db.query(
            StorageSchema.TABLE_STROKE, null, "annotation_id = ?", arrayOf(annotationId),
            null, null, "created_at ASC, id ASC",
        ).use { it.mapRows(::readRow) }

    /**
     * 删除并返回被删除的行。
     *
     * 返回旧行是为了让调用方知道"哪些外置 blob 现在没人引用了"，
     * 从而能在**同一个事务**里清掉 blob 行、在提交后清掉文件。
     * 让 DELETE 也带回信息，比事后全库扫描找孤儿要便宜得多。
     */
    fun deleteByAnnotation(annotationId: String): List<StrokeRow> {
        val existing = byAnnotation(annotationId)
        db.delete(StorageSchema.TABLE_STROKE, "annotation_id = ?", arrayOf(annotationId))
        return existing
    }

    fun byDoc(docId: String): List<StrokeRow> =
        db.query(
            StorageSchema.TABLE_STROKE, null, "doc_id = ?", arrayOf(docId),
            null, null, null,
        ).use { it.mapRows(::readRow) }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_STROKE}", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun countExternal(): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM ${StorageSchema.TABLE_STROKE} WHERE blob_id IS NOT NULL", null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun toValues(row: StrokeRow) = contentValuesOf(
        "id" to row.id,
        "annotation_id" to row.annotationId,
        "doc_id" to row.docId,
        "brush_id" to row.brushId,
        "semantics" to row.semantics,
        "anchor_json" to row.anchorJson,
        "point_count" to row.pointCount,
        "blob_id" to row.blobId,
        "inline_data" to row.inlineData,
        "created_at" to row.createdAt,
    )

    private fun readRow(cursor: Cursor) = StrokeRow(
        id = cursor.str("id"),
        annotationId = cursor.str("annotation_id"),
        docId = cursor.str("doc_id"),
        brushId = cursor.str("brush_id"),
        semantics = cursor.str("semantics"),
        anchorJson = cursor.strOrNull("anchor_json"),
        pointCount = cursor.int("point_count"),
        blobId = cursor.strOrNull("blob_id"),
        inlineData = cursor.blobOrNull("inline_data"),
        createdAt = cursor.long("created_at"),
    )
}

internal class BlobDao(private val db: SQLiteDatabase) {

    fun insert(row: BlobRow) {
        db.insertOrThrow(
            StorageSchema.TABLE_EXTERNAL_BLOB, null,
            contentValuesOf(
                "id" to row.id,
                "relative_path" to row.relativePath,
                "byte_size" to row.byteSize,
                "created_at" to row.createdAt,
            ),
        )
    }

    fun byId(id: String): BlobRow? =
        db.query(
            StorageSchema.TABLE_EXTERNAL_BLOB, null, "id = ?", arrayOf(id),
            null, null, null, "1",
        ).use {
            if (it.moveToFirst()) {
                BlobRow(
                    id = it.str("id"),
                    relativePath = it.str("relative_path"),
                    byteSize = it.long("byte_size"),
                    createdAt = it.long("created_at"),
                )
            } else {
                null
            }
        }

    fun delete(id: String): Int = db.delete(StorageSchema.TABLE_EXTERNAL_BLOB, "id = ?", arrayOf(id))

    fun deleteAll(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return db.delete(
            StorageSchema.TABLE_EXTERNAL_BLOB, "id IN ($placeholders)", ids.toTypedArray(),
        )
    }

    /** 没有任何 stroke 引用的 blob 行。用于维护（导出前/启动时的自检）。 */
    fun orphanIds(): List<String> = db.rawQuery(
        """
        SELECT b.id FROM ${StorageSchema.TABLE_EXTERNAL_BLOB} b
        WHERE NOT EXISTS (
            SELECT 1 FROM ${StorageSchema.TABLE_STROKE} s WHERE s.blob_id = b.id
        )
        """.trimIndent(),
        null,
    ).use {
        val out = ArrayList<String>()
        while (it.moveToNext()) out.add(it.getString(0))
        out
    }

    fun all(): List<BlobRow> =
        db.query(StorageSchema.TABLE_EXTERNAL_BLOB, null, null, null, null, null, "created_at ASC").use {
            it.mapRows { cursor ->
                BlobRow(
                    id = cursor.str("id"),
                    relativePath = cursor.str("relative_path"),
                    byteSize = cursor.long("byte_size"),
                    createdAt = cursor.long("created_at"),
                )
            }
        }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_EXTERNAL_BLOB}", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}

// ── op_log ──────────────────────────────────────────────────────────────────

/** 操作类型。字符串直接入库，因此改动这里等于改动 wire format。 */
object OpTypes {
    const val IMPORT_DOCUMENT = "import_document"
    const val DELETE_DOCUMENT = "delete_document"
    const val UPSERT_ANNOTATION = "upsert_annotation"
    const val DELETE_ANNOTATION = "delete_annotation"
    const val RESTORE_ANNOTATION = "restore_annotation"
    const val UPSERT_STROKES = "upsert_strokes"
    const val SET_PROGRESS = "set_progress"
    const val SET_SETTING = "set_setting"
    const val RESTORE_ARCHIVE = "restore_archive"
}

internal class OpLogDao(private val db: SQLiteDatabase) {

    /**
     * 追加一条操作记录。
     *
     * `seq` 用 `AUTOINCREMENT`：它保证**严格单调递增且不复用**。
     * 普通 `INTEGER PRIMARY KEY` 在删除最大行后会把 rowid 让给下一条，
     * 于是"最后一条 seq"不再能代表"发生过多少次操作" —— 而全库导出/增量同步
     * 恰恰依赖这个单调性来判断"我漏了哪些操作"。
     */
    fun append(opType: String, entityTable: String, entityId: String, payloadJson: String?, at: Long) {
        db.insertOrThrow(
            StorageSchema.TABLE_OP_LOG, null,
            contentValuesOf(
                "op_type" to opType,
                "entity_table" to entityTable,
                "entity_id" to entityId,
                "payload_json" to payloadJson,
                "created_at" to at,
            ),
        )
    }

    fun lastSeq(): Long =
        db.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM ${StorageSchema.TABLE_OP_LOG}", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun count(): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_OP_LOG}", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
}

// ── reading_progress ────────────────────────────────────────────────────────

internal class ProgressDao(private val db: SQLiteDatabase) {

    fun upsert(progress: ReadingProgress) {
        val values = contentValuesOf(
            "doc_id" to progress.docId,
            "char_offset" to progress.charOffset,
            "page_index" to progress.pageIndex,
            "doc_ratio" to progress.docRatio.toDouble(),
            "updated_at" to progress.updatedAt,
        )
        val updated = db.update(
            StorageSchema.TABLE_PROGRESS, values, "doc_id = ?", arrayOf(progress.docId),
        )
        if (updated == 0) db.insertOrThrow(StorageSchema.TABLE_PROGRESS, null, values)
    }

    fun byDoc(docId: String): ReadingProgress? =
        db.query(
            StorageSchema.TABLE_PROGRESS, null, "doc_id = ?", arrayOf(docId),
            null, null, null, "1",
        ).use {
            if (it.moveToFirst()) {
                ReadingProgress(
                    docId = it.str("doc_id"),
                    charOffset = it.int("char_offset"),
                    pageIndex = it.int("page_index"),
                    docRatio = it.double("doc_ratio").toFloat(),
                    updatedAt = it.long("updated_at"),
                )
            } else {
                null
            }
        }

    fun all(): List<ReadingProgress> =
        db.query(StorageSchema.TABLE_PROGRESS, null, null, null, null, null, null).use {
            it.mapRows { cursor ->
                ReadingProgress(
                    docId = cursor.str("doc_id"),
                    charOffset = cursor.int("char_offset"),
                    pageIndex = cursor.int("page_index"),
                    docRatio = cursor.double("doc_ratio").toFloat(),
                    updatedAt = cursor.long("updated_at"),
                )
            }
        }
}

// ── setting ─────────────────────────────────────────────────────────────────

internal class ReadingSessionDao(private val db: SQLiteDatabase) {

    fun insert(session: ReadingSession) {
        db.insertOrThrow(
            StorageSchema.TABLE_READING_SESSION,
            null,
            contentValuesOf(
                "id" to session.id,
                "doc_id" to session.docId,
                "started_at" to session.startedAt,
                "ended_at" to session.endedAt,
                "duration_ms" to session.durationMs,
            ),
        )
    }

    fun all(): List<ReadingSession> =
        db.query(
            StorageSchema.TABLE_READING_SESSION, null, null, null,
            null, null, "started_at ASC",
        ).use { it.mapRows(::readSession) }

    fun since(fromMs: Long): List<ReadingSession> =
        db.query(
            StorageSchema.TABLE_READING_SESSION, null, "started_at >= ?", arrayOf(fromMs.toString()),
            null, null, "started_at ASC",
        ).use { it.mapRows(::readSession) }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_READING_SESSION}", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun readSession(cursor: Cursor): ReadingSession = ReadingSession(
        id = cursor.str("id"),
        docId = cursor.str("doc_id"),
        startedAt = cursor.long("started_at"),
        endedAt = cursor.long("ended_at"),
        durationMs = cursor.long("duration_ms"),
    )
}

internal class SettingDao(private val db: SQLiteDatabase) {

    fun put(key: String, value: String) {
        db.execSQL(
            "INSERT INTO ${StorageSchema.TABLE_SETTING} (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(key, value),
        )
    }

    fun get(key: String): String? =
        db.query(StorageSchema.TABLE_SETTING, arrayOf("value"), "key = ?", arrayOf(key), null, null, null, "1")
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun all(): Map<String, String> =
        db.query(StorageSchema.TABLE_SETTING, null, null, null, null, null, "key ASC").use { cursor ->
            val out = LinkedHashMap<String, String>()
            while (cursor.moveToNext()) {
                out[cursor.str("key")] = cursor.str("value")
            }
            out
        }
}

// ── 事务 ────────────────────────────────────────────────────────────────────

/**
 * 事务包装。
 *
 * 刻意不提供 `beginTransactionNonExclusive`：本层唯一的并发写者是导入，
 * 而 WAL 模式下普通事务已经足够；引入更多事务模式只会让"提交边界在哪"变模糊。
 */
internal inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}
