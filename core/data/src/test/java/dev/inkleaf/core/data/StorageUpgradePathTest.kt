package dev.inkleaf.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 迁移路径的**完整**覆盖：v1 → v2 → v3 逐版升级 + 全新安装建库。
 *
 * ## 为什么这一组测试的发版前优先级最高
 *
 * `StorageSchema.VERSION = 3` 是**手写 SQLite**，没有 Room 那种"改字段忘了迁移，
 * 编译期就报错"的兜底。迁移脚本漏写一条，症状是**老用户升级后 App 崩溃**，
 * 而新装的用户完全正常 —— 这意味着开发期（装的是新库）永远看不到它。
 *
 * M24 审阅把它列为 B 档（发版前必做）就是这个原因。
 *
 * ## 与已有的 v1 测试的区别
 *
 * [StorageMigrationTest] 只造了一张 `setting` 表的"极简 v1 库"，
 * 验的是**那一条迁移**（页边距默认值）本身。
 * 本文件造的是**真实的旧库**：该有的表都有、该有的数据都有，
 * 验的是"从旧库升上来之后，老用户的东西还在不在"。
 *
 * 两条缺一不可：一条验迁移脚本对不对，一条验升级这条路走不走得通。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageUpgradePathTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile get() = context.getDatabasePath(StorageSchema.DATABASE_NAME)

    @Before
    fun clean() {
        context.deleteDatabase(StorageSchema.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(StorageSchema.DATABASE_NAME)
    }

    // ── 造真实的旧库 ────────────────────────────────────────────────────────

    /**
     * 造一个 **v1** 的库。
     *
     * v1 的表结构 = 现在的 [StorageSchema.DDL] 减去 `reading_session`
     * （那是 v3 才加的）。这里照着 DDL 建其余所有表，
     * 所以这个库和"当年真的装过 1.0"的结构是一样的。
     */
    private fun createV1Database(): SQLiteDatabase {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (statement in StorageSchema.DDL) {
                // reading_session 是 v3 才有的表，v1 里不该有。
                if (statement.contains(StorageSchema.TABLE_READING_SESSION)) continue
                db.execSQL(statement)
            }
            db.version = 1
        } finally {
            db.close()
        }
        return openRaw()
    }

    /** 造一个 **v2** 的库：表结构同 v1（v2 只改了一条设置默认值），版本号 2。 */
    private fun createV2Database(): SQLiteDatabase {
        val db = createV1Database()
        db.execSQL(
            "UPDATE ${StorageSchema.TABLE_SETTING} SET value = '4' " +
                "WHERE key = '${SettingKeys.PAGE_MARGIN_MM}'",
        )
        db.version = 2
        db.close()
        return openRaw()
    }

    private fun openRaw(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)

    /** 往库里塞一篇文档 + 一条笔记 + 一条进度 + 一条阅读会话以外的一切。 */
    private fun seedUserData(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ${StorageSchema.TABLE_DOCUMENT} (" +
                "id, title, author, kind, origin, source_fingerprint, normalization_profile_id, " +
                "normalization_version, relative_path, encoding, byte_size, char_count, block_count, " +
                "imported_at, last_opened_at, cover_relative_path, source_uri, has_text_layer" +
                ") VALUES (" +
                "'doc-1', '老用户的书', '某人', 'TXT', 'SAF_COPY', 'fp-0123456789abcdef', " +
                "'default', 1, 'doc_doc-1.txt', 'UTF-8', 100, 20, 2, 1000, 2000, NULL, NULL, 1)",
        )
        db.execSQL(
            "INSERT INTO ${StorageSchema.TABLE_ANNOTATION} (" +
                "id, doc_id, kind, created_at, color_argb, tags_json, note_text, " +
                "anchor_json, extra_json, deleted" +
                ") VALUES ('note-1', 'doc-1', 'MARGIN_NOTE', 1500, -16777216, '[]', " +
                "'升级前写的笔记', NULL, NULL, 0)",
        )
        db.execSQL(
            "INSERT INTO ${StorageSchema.TABLE_PROGRESS} " +
                "(doc_id, char_offset, page_index, doc_ratio, updated_at) " +
                "VALUES ('doc-1', 42, 3, 0.5, 2500)",
        )
        db.execSQL(
            "INSERT INTO ${StorageSchema.TABLE_SETTING} (key, value) VALUES ('${SettingKeys.FONT_SIZE_SP}', '21')",
        )
    }

    /** 按正常路径打开（会触发 onUpgrade），读回库里的内容。 */
    private fun upgradeAndRead(): Map<String, Any?> {
        val helper = InkLeafDatabase(context)
        try {
            val db = helper.writableDatabase
            return mapOf(
                "docTitle" to db.rawQuery(
                    "SELECT title FROM ${StorageSchema.TABLE_DOCUMENT} WHERE id = 'doc-1'", null,
                ).use { if (it.moveToFirst()) it.getString(0) else null },
                "noteText" to db.rawQuery(
                    "SELECT note_text FROM ${StorageSchema.TABLE_ANNOTATION} WHERE id = 'note-1'", null,
                ).use { if (it.moveToFirst()) it.getString(0) else null },
                "charOffset" to db.rawQuery(
                    "SELECT char_offset FROM ${StorageSchema.TABLE_PROGRESS} WHERE doc_id = 'doc-1'", null,
                ).use { if (it.moveToFirst()) it.getInt(0) else null },
                "fontSize" to db.rawQuery(
                    "SELECT value FROM ${StorageSchema.TABLE_SETTING} WHERE key = '${SettingKeys.FONT_SIZE_SP}'",
                    null,
                ).use { if (it.moveToFirst()) it.getString(0) else null },
                "version" to db.version,
            )
        } finally {
            helper.close()
        }
    }

    private fun tableExists(name: String): Boolean {
        val helper = InkLeafDatabase(context)
        return try {
            helper.writableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name),
            ).use { it.moveToFirst() }
        } finally {
            helper.close()
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    fun `v1 库升级到 v3 后老用户的数据都还在`() {
        val db = createV1Database()
        seedUserData(db)
        db.close()

        val after = upgradeAndRead()

        assertEquals("书还在", "老用户的书", after["docTitle"])
        assertEquals("笔记还在", "升级前写的笔记", after["noteText"])
        assertEquals("阅读进度还在", 42, after["charOffset"])
        assertEquals("用户自己调过的字号不该被迁移动", "21", after["fontSize"])
        assertEquals("版本号必须落到当前版本", StorageSchema.VERSION, after["version"])
    }

    @Test
    fun `v2 库升级到 v3 后老用户的数据都还在`() {
        // v2 是"只改了页边距默认值"的那一版：数据表与 v1 相同。
        // 它是最常见的真实起点 —— 大多数用户是从 v2 升上来的，不是从 v1。
        val db = createV2Database()
        seedUserData(db)
        db.close()

        val after = upgradeAndRead()

        assertEquals("书还在", "老用户的书", after["docTitle"])
        assertEquals("笔记还在", "升级前写的笔记", after["noteText"])
        assertEquals("阅读进度还在", 42, after["charOffset"])
        assertEquals(StorageSchema.VERSION, after["version"])
    }

    @Test
    fun `从 v1 逐版升级每一版都有对应的迁移脚本`() {
        // 缺一条迁移脚本会在 onUpgrade 里 error()，老用户升级即崩。
        // 这条断言把"版本号 +1 必须补一条脚本"这条规矩变成可执行的检查。
        for (from in 1 until StorageSchema.VERSION) {
            assertNotNull(
                "缺少 $from → ${from + 1} 的迁移脚本：老用户升级到这一版会崩",
                InkLeafDatabase.migrations[from],
            )
        }
    }

    @Test
    fun `v1 升级后 reading_session 表被建出来`() {
        val db = createV1Database()
        db.close()

        upgradeAndRead()

        assertTrue(
            "v3 的阅读会话表必须在升级后存在",
            tableExists(StorageSchema.TABLE_READING_SESSION),
        )
    }

    @Test
    fun `全新安装会建出全部表`() {
        // 这是与升级**并列**的第二条路径：新装的用户走 onCreate，不是 onUpgrade。
        // 只测升级不测新装，等于放过了"新装用户根本进不去"这种最严重的问题。
        val helper = InkLeafDatabase(context)
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }

        for (table in listOf(
            StorageSchema.TABLE_DOCUMENT,
            StorageSchema.TABLE_ANNOTATION,
            StorageSchema.TABLE_STROKE,
            StorageSchema.TABLE_EXTERNAL_BLOB,
            StorageSchema.TABLE_OP_LOG,
            StorageSchema.TABLE_PROGRESS,
            StorageSchema.TABLE_SETTING,
            StorageSchema.TABLE_READING_SESSION,
        )) {
            assertTrue("全新安装必须建出 $table", tableExists(table))
        }
    }

    @Test
    fun `全新安装会写入默认设置`() {
        // 新装的库不能是"设置全空"：那样设置界面与排版引擎会各自用一套默认值，
        // 而这个不一致只在第一次启动时出现，最难被发现。
        val helper = InkLeafDatabase(context)
        try {
            val db = helper.writableDatabase
            for ((key, value) in StorageSchema.DEFAULT_SETTINGS) {
                db.rawQuery(
                    "SELECT value FROM ${StorageSchema.TABLE_SETTING} WHERE key = ?",
                    arrayOf(key),
                ).use { cursor ->
                    assertTrue("默认设置 $key 必须存在", cursor.moveToFirst())
                    assertEquals("默认设置 $key 的值不对", value, cursor.getString(0))
                }
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `升级不会把用户的数据清空`() {
        val db = createV1Database()
        seedUserData(db)
        db.close()

        upgradeAndRead()

        val helper = InkLeafDatabase(context)
        try {
            val db2 = helper.writableDatabase
            db2.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_DOCUMENT}", null).use {
                assertTrue("文档行数不该变", it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db2.rawQuery("SELECT COUNT(*) FROM ${StorageSchema.TABLE_ANNOTATION}", null).use {
                assertTrue("标注行数不该变", it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            helper.close()
        }
    }
}
