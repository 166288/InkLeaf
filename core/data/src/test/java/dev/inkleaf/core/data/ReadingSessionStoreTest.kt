package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 阅读会话表（v3）的真实 SQLite 回归测试。
 *
 * 这一层最容易出的不是逻辑错，而是**表没建出来**：新库靠 [StorageSchema.DDL]、
 * 旧库靠迁移，两条路径必须都落到同一张表上。这里直接打开真实数据库写入并读回，
 * 顺带验证"0 时长不进库"这条业务判断是在仓储层生效的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadingSessionStoreTest {

    private lateinit var store: InkLeafStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        wipe()
        store = InkLeafStore(context)
        store.warmUp()
    }

    @After
    fun tearDown() {
        store.close()
        wipe()
    }

    private fun wipe() {
        context.deleteDatabase(StorageSchema.DATABASE_NAME)
        File(context.filesDir, "documents").deleteRecursively()
        File(context.filesDir, "blobs").deleteRecursively()
        File(context.filesDir, "derived").deleteRecursively()
    }

    @Test
    fun `写入一段阅读_按开始时间读回`() = runBlocking {
        // reading_session.doc_id 有外键，先导入一本最小的文档（走真实导入链路）。
        val meta = store.importer.commit(
            store.importer.inspect(
                DocumentImporter.ImportCandidate(
                    displayName = "测试.txt",
                    title = "测试",
                    kind = dev.inkleaf.core.model.DocumentKind.TXT,
                    bytes = "第一章\n正文".toByteArray(Charsets.UTF_8),
                    sourceUri = null,
                ),
            ),
        )

        assertTrue(store.readingSessions.record(meta.id, 1_000, 6_000, 5_000))
        assertTrue(store.readingSessions.record(meta.id, 10_000, 12_000, 2_000))
        // 0 时长：不写。
        assertFalse(store.readingSessions.record(meta.id, 20_000, 20_000, 0))

        val all = store.readingSessions.all()
        assertEquals(2, all.size)
        assertEquals(1_000L, all[0].startedAt)
        assertEquals(5_000L, all[0].durationMs)
        assertEquals(2, store.readingSessions.count())

        val since = store.readingSessions.since(9_000)
        assertEquals(1, since.size)
        assertEquals(10_000L, since[0].startedAt)
    }
}
