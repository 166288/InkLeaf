package dev.inkleaf.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 表结构（§7.2，已按 §M1 修订为手写 SQLite）。
 *
 * 建表语句集中在这一个对象里，理由很实际：手写 SQLite 没有 Room 的编译期校验，
 * 那么"哪张表有哪些列"就必须只有一个地方能回答。迁移代码也依赖它。
 */
object StorageSchema {

    const val DATABASE_NAME = "inkleaf.db"

    /**
     * 版本号。每次改表结构必须 +1，并在 [InkLeafDatabase.migrations] 里补一条迁移。
     *
     * 不提供"删库重建"的兜底路径：那等于用户升级一次 App 就丢掉全部笔记。
     *
     * v2（M8）：只改一条**设置项的默认值**（页边距 16 mm → 4 mm）。
     * 严格说设置不是表结构，但"已经存过 16 的库"不会自己变成 4 ——
     * 而 16 正是让正文只占一半屏宽的那个值。所以借这个唯一必须走的迁移位置改一次，
     * 而不是在启动路径上撒一段「顺手修一下」的代码。
     *
     * v3（M12）：新增 `reading_session` 表（阅读统计）。只增表，不动旧表 ——
     * 旧库升级后已有的文档、笔记、进度原样保留。
     */
    const val VERSION = 3

    const val TABLE_DOCUMENT = "document"
    const val TABLE_ANNOTATION = "annotation"
    const val TABLE_STROKE = "stroke"
    const val TABLE_EXTERNAL_BLOB = "external_blob"
    const val TABLE_OP_LOG = "op_log"
    const val TABLE_PROGRESS = "reading_progress"
    const val TABLE_SETTING = "setting"
    const val TABLE_READING_SESSION = "reading_session"

    /**
     * `reading_session` 的建表语句。
     *
     * 单独抽出来，是因为它同时要进 [DDL]（新库）和 [InkLeafDatabase.migrations]
     * （旧库升级）。抄成两份的建表语句迟早在两边长得不一样，而那个症状是
     * "新装能用、升级后统计崩"——最难在开发机上遇到的一种。
     */
    val READING_SESSION_DDL: String = """
        CREATE TABLE $TABLE_READING_SESSION (
            id          TEXT PRIMARY KEY NOT NULL,
            doc_id      TEXT NOT NULL,
            started_at  INTEGER NOT NULL,
            ended_at    INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            FOREIGN KEY (doc_id) REFERENCES $TABLE_DOCUMENT (id) ON DELETE CASCADE
        )
    """.trimIndent()

    val READING_SESSION_INDEX: String =
        "CREATE INDEX idx_reading_session_started ON $TABLE_READING_SESSION (started_at)"

    val DDL: List<String> = listOf(
        """
        CREATE TABLE $TABLE_DOCUMENT (
            id                      TEXT PRIMARY KEY NOT NULL,
            title                   TEXT NOT NULL,
            author                  TEXT,
            kind                    TEXT NOT NULL,
            origin                  TEXT NOT NULL,
            source_fingerprint      TEXT NOT NULL,
            normalization_profile_id TEXT NOT NULL,
            normalization_version   INTEGER NOT NULL,
            relative_path           TEXT NOT NULL,
            encoding                TEXT NOT NULL,
            byte_size               INTEGER NOT NULL,
            char_count              INTEGER NOT NULL,
            block_count             INTEGER NOT NULL,
            imported_at             INTEGER NOT NULL,
            last_opened_at          INTEGER,
            cover_relative_path     TEXT,
            source_uri              TEXT,
            has_text_layer          INTEGER NOT NULL DEFAULT 1
        )
        """.trimIndent(),

        """
        CREATE TABLE $TABLE_ANNOTATION (
            id           TEXT PRIMARY KEY NOT NULL,
            doc_id       TEXT NOT NULL,
            kind         TEXT NOT NULL,
            created_at   INTEGER NOT NULL,
            color_argb   INTEGER NOT NULL,
            tags_json    TEXT NOT NULL,
            note_text    TEXT,
            anchor_json  TEXT,
            extra_json   TEXT,
            deleted      INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (doc_id) REFERENCES $TABLE_DOCUMENT (id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_annotation_doc ON $TABLE_ANNOTATION (doc_id, created_at)",

        """
        CREATE TABLE $TABLE_STROKE (
            id             TEXT PRIMARY KEY NOT NULL,
            annotation_id  TEXT NOT NULL,
            doc_id         TEXT NOT NULL,
            brush_id       TEXT NOT NULL,
            semantics      TEXT NOT NULL,
            anchor_json    TEXT,
            point_count    INTEGER NOT NULL,
            blob_id        TEXT,
            inline_data    BLOB,
            created_at     INTEGER NOT NULL,
            FOREIGN KEY (annotation_id) REFERENCES $TABLE_ANNOTATION (id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_stroke_annotation ON $TABLE_STROKE (annotation_id)",

        """
        CREATE TABLE $TABLE_EXTERNAL_BLOB (
            id             TEXT PRIMARY KEY NOT NULL,
            relative_path  TEXT NOT NULL,
            byte_size      INTEGER NOT NULL,
            created_at     INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE $TABLE_OP_LOG (
            seq          INTEGER PRIMARY KEY AUTOINCREMENT,
            op_type      TEXT NOT NULL,
            entity_table TEXT NOT NULL,
            entity_id    TEXT NOT NULL,
            payload_json TEXT,
            created_at   INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_op_log_entity ON $TABLE_OP_LOG (entity_table, entity_id)",

        """
        CREATE TABLE $TABLE_PROGRESS (
            doc_id      TEXT PRIMARY KEY NOT NULL,
            char_offset INTEGER NOT NULL,
            page_index  INTEGER NOT NULL,
            doc_ratio   REAL NOT NULL,
            updated_at  INTEGER NOT NULL,
            FOREIGN KEY (doc_id) REFERENCES $TABLE_DOCUMENT (id) ON DELETE CASCADE
        )
        """.trimIndent(),

        """
        CREATE TABLE $TABLE_SETTING (
            key   TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL
        )
        """.trimIndent(),

        READING_SESSION_DDL,
        READING_SESSION_INDEX,
    )

    /** 默认设置。用 `INSERT OR IGNORE` 写入，因此升级时补的新键也会被填上。 */
    val DEFAULT_SETTINGS: Map<String, String> = mapOf(
        SettingKeys.FONT_SIZE_SP to "17",
        SettingKeys.LINE_SPACING to "1.45",
        SettingKeys.PAGE_MARGIN_MM to "4",
        SettingKeys.DARK_MODE to "SYSTEM",
        SettingKeys.LAYOUT_MODE to "PAGED",
        SettingKeys.READER_THEME to "AUTO",
        SettingKeys.HIGHLIGHT_MODE to "MULTIPLY",
        SettingKeys.KEEP_SCREEN_ON to "false",
        SettingKeys.STYLUS_ONLY to "false",
        SettingKeys.LIBRARY_GRID to "false",
        SettingKeys.PAGE_TURN_ZONES to "true",
        SettingKeys.VOLUME_KEY_TURN to "true",
        SettingKeys.READER_FONT_FAMILY to "DEFAULT",
        SettingKeys.INK_UNBUFFERED to "true",
        SettingKeys.INK_STATIC_LAYER to "true",
        SettingKeys.ROTATION_LOCK to "false",
        SettingKeys.PARAGRAPH_SPACING_EM to "0.3",
        SettingKeys.JUSTIFY_TEXT to "true",
        SettingKeys.CJK_TYPESETTING to "true",
        SettingKeys.TURN_EFFECT to "FADE",
    )
}

/** 设置键。集中在这里，避免各处拼字符串导致的"写了但读不到"。 */
object SettingKeys {
    const val FONT_SIZE_SP = "reader.font_size_sp"
    const val LINE_SPACING = "reader.line_spacing"
    const val PAGE_MARGIN_MM = "reader.page_margin_mm"
    /**
     * 界面明暗（阅读页以外的界面）：`SYSTEM` / `LIGHT` / `DARK`。
     *
     * 名字里的 `ui` 就是它的意思 —— 它与 [READER_THEME] 是**两个独立设置**。
     * 曾经有一版让它由阅读主题派生，那是错的：书架用浅色、书里用夜间纸是很常见的用法。
     */
    const val DARK_MODE = "ui.dark_mode"
    const val LAYOUT_MODE = "reader.layout_mode"

    /** 阅读主题（纸色 + 墨色）。值是 `ReaderTheme` 的枚举名。 */
    const val READER_THEME = "reader.theme"
    const val HIGHLIGHT_MODE = "reader.highlight_mode"
    const val KEEP_SCREEN_ON = "reader.keep_screen_on"

    /** 只允许手写笔书写（手指用来翻页）。 */
    const val STYLUS_ONLY = "reader.stylus_only"

    /** 书架用网格还是列表。 */
    const val LIBRARY_GRID = "library.grid"

    /** 书写时手指落在左右两侧是否翻页（翻页分区让渡）。 */
    const val PAGE_TURN_ZONES = "reader.page_turn_zones"

    /** 阅读界面里音量键是否翻页（上键=上一页，下键=下一页）。 */
    const val VOLUME_KEY_TURN = "reader.volume_key_turn"

    /** 书里正文的字体族（M29）：DEFAULT / SERIF / MONOSPACE。 */
    const val READER_FONT_FAMILY = "reader.font_family"

    /** 书写与套索走无缓冲派发（低延迟）。个别设备配合不好时可关。 */
    const val INK_UNBUFFERED = "reader.ink_unbuffered"

    /** 静态层（正文 + 已落盘的墨）是否录进 RenderNode 缓存。排查显示问题时可关。 */
    const val INK_STATIC_LAYER = "reader.ink_static_layer"

    /**
     * 锁住屏幕方向（M31）。
     *
     * 放在 `ui.` 前缀下而不是 `reader.`：它作用在整个应用窗口上（Activity 的
     * `requestedOrientation`），不是排版参数。躺着看书时屏幕跟着手转，
     * 每翻一页都要重新排版一次，这个开关就是为那种姿势准备的。
     */
    const val ROTATION_LOCK = "ui.rotation_lock"

    /**
     * 段间距（M33），单位 em 的十进制字符串。
     *
     * 它是**排版参数**而不是外观：改它就要重新排版、重新分页，
     * 所以每次写入都会触发重排（与字号、行距同一条路）。
     */
    const val PARAGRAPH_SPACING_EM = "reader.paragraph_spacing_em"

    /** 两端对齐（M33）。 */
    const val JUSTIFY_TEXT = "reader.justify_text"

    /** 中文禁则（M33）：标点不落行首 + 连续标点挤压。 */
    const val CJK_TYPESETTING = "reader.cjk_typesetting"

    /** 翻页动效（M33）：`NONE` / `FADE` / `CURL`。 */
    const val TURN_EFFECT = "reader.turn_effect"
}

/**
 * 数据库打开器。
 *
 * 三处刻意的选择：
 *   1. **打开外键约束**。`annotation` / `stroke` 靠 `ON DELETE CASCADE` 实现
 *      "删文档 = 连带删笔记"，而 SQLite 默认**不**执行外键。
 *      不开这个开关，删文档后笔记会变成永远访问不到的垃圾数据。
 *   2. **WAL 模式**。阅读时是"频繁小写（进度）、偶尔大写（笔迹）"的负载，
 *      WAL 让读不被写阻塞，直接改善翻页时的卡顿。
 *   3. **不提供 drop 重建**。见 [StorageSchema.VERSION] 注释。
 */
class InkLeafDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    StorageSchema.DATABASE_NAME,
    null,
    StorageSchema.VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        // 必须在任何事务开始之前设置
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in StorageSchema.DDL) db.execSQL(statement)
        for ((key, value) in StorageSchema.DEFAULT_SETTINGS) {
            db.execSQL(
                "INSERT OR IGNORE INTO ${StorageSchema.TABLE_SETTING} (key, value) VALUES (?, ?)",
                arrayOf(key, value),
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (from in oldVersion until newVersion) {
            val migration = migrations[from]
                ?: error("缺少 $from → ${from + 1} 的迁移脚本：不允许删库重建（会丢掉用户全部笔记）")
            migration.forEach(db::execSQL)
        }
        // 每次升级后补齐新增的默认设置项
        for ((key, value) in StorageSchema.DEFAULT_SETTINGS) {
            db.execSQL(
                "INSERT OR IGNORE INTO ${StorageSchema.TABLE_SETTING} (key, value) VALUES (?, ?)",
                arrayOf(key, value),
            )
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        if (!db.isReadOnly) {
            // WAL：读不被写阻塞。翻页时同时要写进度，这个差别是可见的。
            runCatching { db.enableWriteAheadLogging() }
        }
    }

    companion object {
        /**
         * 迁移脚本，键是"从哪个版本升"。
         *
         * v1 → v2（M8）：把**还停在旧默认值 16 mm** 的页边距改成 4 mm。
         * 为什么不是无条件改：用户可能自己就喜欢 16 mm，迁移不该覆盖他的选择。
         * 但"他主动选的值"与"从没动过的旧默认值"在库里长得一模一样，
         * 所以只能按值判断 —— 这一取舍写在这里，也写进了 M8 记录。
         */
        val migrations: Map<Int, List<String>> = mapOf(
            1 to listOf(
                "UPDATE ${StorageSchema.TABLE_SETTING} SET value = '4' " +
                    "WHERE key = '${SettingKeys.PAGE_MARGIN_MM}' AND value = '16'",
            ),
            2 to listOf(
                StorageSchema.READING_SESSION_DDL,
                StorageSchema.READING_SESSION_INDEX,
            ),
        )
    }
}
