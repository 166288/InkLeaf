package dev.inkleaf.core.data

import androidx.test.core.app.ApplicationProvider
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
 * 派生缓存（封面）的清理。
 *
 * ## 为什么这些规则值得钉住
 *
 * 封面缓存是**文件**，不是数据库行。它的问题不会在数据库里留下痕迹，
 * 只会表现为"书架上的封面不见了"或者"删了书，缓存还在涨"——
 * 后者更是要导入几十本书之后才看得出来，等到发现时缓存已经涨到几十兆。
 *
 * 三条规则各有各的失败方式：
 *
 * 1. **删文档要清自己的缓存** —— 漏了就永久泄漏。
 * 2. **孤儿要能被扫出来** —— 崩溃或旧版本留下的孤儿会一直占地方。
 * 3. **`.none` 不能被当成缓存清掉** —— 它是"这本没有封面"的事实标记，
 *    删了之后每次进书架都要重新解一遍整本 EPUB 才能得到同一个结论。
 *
 * 第 3 条是这里最容易写错的一条，所以单独测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DerivedCacheTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val paths get() = StoragePaths(context)

    /**
     * 缓存实例。
     *
     * 刻意是**一个**实例而不是每次现造：`epoch` 是实例状态，
     * 两个实例各记各的版本号，"清完缓存版本号有没有前进"这条断言就成了空话。
     */
    private val cache by lazy { DerivedCache(paths) }

    /** 造一个像样的 UUID 形态 docId —— 命名规则只认这种形态。 */
    private fun docId(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    /**
     * 造一个 16 位十六进制指纹。
     *
     * **前 12 位必须各不相同**：文件名只取前 12 位，
     * 前 12 位相同的两个指纹会落到同一个文件名上，
     * 那条"换内容自然不命中旧缓存"的断言就测不到东西了。
     */
    private fun fingerprint(n: Int): String = "%012x%04x".format(n, n)

    private fun writeCover(doc: String, fp: String, bytes: ByteArray = ByteArray(64)) {
        cache.coverImage(doc, fp).writeBytes(bytes)
    }

    private fun writeMissing(doc: String, fp: String) {
        cache.coverMissingMarker(doc, fp).writeText("")
    }

    @Before
    fun setUp() {
        File(context.filesDir, "derived").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "derived").deleteRecursively()
    }

    @Test
    fun `统计只算封面图片不算 none 标记`() {
        writeCover(docId(1), fingerprint(1), ByteArray(100))
        writeMissing(docId(2), fingerprint(2))

        val usage = cache.usage()

        assertEquals("两张封面图算两个文件", 1, usage.fileCount)
        assertEquals(100L, usage.bytes)
    }

    @Test
    fun `删文档会连它的图片和 none 标记一起清掉`() {
        val doc = docId(1)
        writeCover(doc, fingerprint(1))
        writeMissing(doc, fingerprint(1))

        val removed = cache.clearDocument(doc)

        assertEquals("两份文件都要清", 2, removed)
        assertFalse(cache.coverImage(doc, fingerprint(1)).exists())
        assertFalse(cache.coverMissingMarker(doc, fingerprint(1)).exists())
    }

    @Test
    fun `删文档不会误伤别的书`() {
        val a = docId(1)
        val b = docId(2)
        writeCover(a, fingerprint(1))
        writeCover(b, fingerprint(2))

        cache.clearDocument(a)

        assertTrue("另一本书的封面必须还在", cache.coverImage(b, fingerprint(2)).exists())
    }

    @Test
    fun `孤儿缓存会被扫出来活着的留下`() {
        val alive = docId(1)
        val dead = docId(2)
        writeCover(alive, fingerprint(1))
        writeCover(dead, fingerprint(2))

        val removed = cache.clearOrphanCovers(setOf(alive))

        assertEquals(1, removed)
        assertTrue(cache.coverImage(alive, fingerprint(1)).exists())
        assertFalse(cache.coverImage(dead, fingerprint(2)).exists())
    }

    @Test
    fun `不是封面缓存的文件不会被当成孤儿删掉`() {
        // 将来 derived/ 里放别的东西时，这条规则保证它们不会被误删。
        val stray = File(paths.derivedDir, "something-else.bin")
        stray.writeBytes(ByteArray(10))

        val removed = cache.clearOrphanCovers(emptySet())

        assertEquals("认不出的文件不该被动", 0, removed)
        assertTrue(stray.exists())
    }

    @Test
    fun `清空缓存只删图片不动 none 标记`() {
        val withCover = docId(1)
        val withoutCover = docId(2)
        writeCover(withCover, fingerprint(1), ByteArray(500))
        writeMissing(withoutCover, fingerprint(2))

        val cleared = cache.clearAll()

        assertEquals(1, cleared.fileCount)
        assertEquals(500L, cleared.bytes)
        assertFalse(cache.coverImage(withCover, fingerprint(1)).exists())
        assertTrue(
            "none 是「这本没有封面」的事实，不是缓存，不能被清掉",
            cache.coverMissingMarker(withoutCover, fingerprint(2)).exists(),
        )
    }

    @Test
    fun `清缓存会让版本号前进好让界面重抽封面`() {
        val doc = docId(1)
        writeCover(doc, fingerprint(1))
        val before = cache.epoch.value

        cache.clearAll()

        assertTrue("版本号必须前进，否则界面还攥着已失效的路径", cache.epoch.value > before)
    }

    @Test
    fun `清空一个空目录不会让版本号乱跳`() {
        val before = cache.epoch.value

        val cleared = cache.clearAll()

        assertEquals(0, cleared.fileCount)
        assertEquals("没有文件被删，版本号不该动", before, cache.epoch.value)
    }

    @Test
    fun `文件名规则带着指纹所以换内容自然不命中旧缓存`() {
        val doc = docId(1)
        writeCover(doc, fingerprint(1))

        assertFalse(
            "指纹变了就该当成另一张图，而不是命中旧的",
            cache.coverImage(doc, fingerprint(2)).exists(),
        )
    }
}
