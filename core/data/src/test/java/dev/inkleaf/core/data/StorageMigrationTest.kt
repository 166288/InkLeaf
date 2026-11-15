package dev.inkleaf.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移（M8）的单测。
 *
 * ## 为什么值得单独测
 *
 * 这次迁移改的是**设置项的默认值**，不是表结构 —— 它没有编译期约束，也没有"表不存在"
 * 这种会立刻炸的错误。它最可能的失败方式是**静默不生效**：用户升级之后照样看到
 * "正文只占中间一条"，而且不会有人报 bug（他们只会觉得这个 App 排版不好看）。
 *
 * 所以这里造一个真的 v1 库（只有 `setting` 表、`user_version = 1`），再让
 * [InkLeafDatabase] 按正常路径去升级，然后读值。三条用例分别覆盖：
 *
 * 1. 还停在旧默认值 16 的库 → 改成 4；
 * 2. 用户自己调过的值（20）→ **不许动**；
 * 3. 老库里压根没有这一行 → 升级时补成新默认值 4。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageMigrationTest {

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

    /**
     * 造一个 v1 的库：设置表 + **最小形态**的 stroke 表，版本号 1。
     *
     * 真实 v1 的表远不止这两张，但其余表没有任何迁移脚本碰，所以不必造。
     * `stroke` 是必须的：v4 的迁移要在它上面建索引，而 SQLite 的
     * `CREATE INDEX`（哪怕带 `IF NOT EXISTS`）在表不存在时直接报错 ——
     * `IF NOT EXISTS` 管的是**索引**重名，不是表缺失。
     *
     * 这条依赖本身就是个教训：迁移脚本一旦开始引用"别人的表"，测试里那个
     * 简化库就不再等价于真库了。要么补表，要么别在迁移里碰别的表。
     */
    private fun createV1Database(vararg settings: Pair<String, String>) {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                "CREATE TABLE ${StorageSchema.TABLE_SETTING} (" +
                    "key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
            )
            // 只留 v4 索引需要的两列（doc_id / blob_id），其余列与迁移无关。
            db.execSQL(
                "CREATE TABLE ${StorageSchema.TABLE_STROKE} (" +
                    "id TEXT PRIMARY KEY NOT NULL, doc_id TEXT NOT NULL, blob_id TEXT)",
            )
            for ((key, value) in settings) {
                db.execSQL(
                    "INSERT INTO ${StorageSchema.TABLE_SETTING} (key, value) VALUES (?, ?)",
                    arrayOf(key, value),
                )
            }
            db.version = 1
        } finally {
            db.close()
        }
    }

    /** 按正常路径打开（会触发 onUpgrade），读回一条设置。 */
    private fun readAfterUpgrade(key: String): String? {
        val helper = InkLeafDatabase(context)
        try {
            helper.writableDatabase.rawQuery(
                "SELECT value FROM ${StorageSchema.TABLE_SETTING} WHERE key = ?",
                arrayOf(key),
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `旧默认值十六毫米_升级后变成四毫米`() {
        createV1Database(SettingKeys.PAGE_MARGIN_MM to "16")
        assertEquals("4", readAfterUpgrade(SettingKeys.PAGE_MARGIN_MM))
    }

    @Test
    fun `用户自己调过的边距_升级不许动`() {
        createV1Database(SettingKeys.PAGE_MARGIN_MM to "20")
        assertEquals("20", readAfterUpgrade(SettingKeys.PAGE_MARGIN_MM))
    }

    @Test
    fun `老库里没有这一行_升级时补成新默认值`() {
        createV1Database()
        assertEquals("4", readAfterUpgrade(SettingKeys.PAGE_MARGIN_MM))
    }

    @Test
    fun `升级不会把版本号留在旧值`() {
        createV1Database()
        readAfterUpgrade(SettingKeys.PAGE_MARGIN_MM)
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(StorageSchema.VERSION, db.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `升级到四_三个索引被建出来`() {
        createV1Database()
        val helper = InkLeafDatabase(context)
        try {
            val names = HashSet<String>()
            helper.writableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL", null,
            ).use { cursor ->
                while (cursor.moveToNext()) names.add(cursor.getString(0))
            }
            for (name in listOf("idx_stroke_doc", "idx_stroke_blob", "idx_reading_session_doc")) {
                assertTrue("v4 迁移必须建出 $name（实际：$names）", name in names)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `升级到三_阅读会话表被建出来`() {
        createV1Database()
        val helper = InkLeafDatabase(context)
        try {
            helper.writableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(StorageSchema.TABLE_READING_SESSION),
            ).use { cursor ->
                assertTrue("v3 迁移必须建出 reading_session 表", cursor.moveToFirst())
            }
        } finally {
            helper.close()
        }
    }
}
