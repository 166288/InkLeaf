package dev.inkleaf.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 派生资源缓存（`derived/` 目录）。
 *
 * ## 这里面现在只有封面
 *
 * 封面是**可再生**的：EPUB 里本来就带着，抽一份出来只是为了让书架
 * 不用每次进界面都解一遍 zip。所以"缓存丢了不要紧"——
 * 顶多是下次进书架慢一点，这一点决定了下面所有清理策略都可以放心地删。
 *
 * ## 两种文件，两种性质
 *
 * - `cover_<docId>_<指纹前12位>.img` —— 抽出来的封面字节。**这是缓存**：
 *   可重建、占地方、可以随时删。
 * - `cover_<docId>_<指纹前12位>.none` —— "这本没有封面"的标记。**这不是缓存**：
 *   docId 与指纹绑死了这本书，它有没有封面是确定的，
 *   而抽一次封面要把整本 EPUB 解一遍 —— 留着这个 0 字节的标记，
 *   省掉的是每次进书架都重解一遍那些没有封面的书。
 *
 * 同一个 (docId, 指纹) 下这两个文件**不会同时存在**：
 * 抽到了就写 `.img`，抽不到才写 `.none`。所以清 `.img` 不会让任何一本书
 * 误判成"无封面"，清 `.none` 也不会让有封面的书丢封面。
 *
 * ## 命名只在这里定
 *
 * 文件名里带 docId 与源指纹，是为了让两件事成为可能：
 *
 * 1. 同一本书换了内容（重新导入）→ 指纹变了 → 缓存自然不命中，不会显示旧封面；
 * 2. 按 docId 精确清理 → 删一本就只删它自己的那两份文件。
 *
 * docId 是 UUID（不含下划线），指纹是十六进制（不含下划线），
 * 所以 `_` 在这里是**无歧义的分隔符** —— 按 docId 匹配不会误伤别的书。
 */
class DerivedCache(private val paths: StoragePaths) {

    /**
     * 缓存版本号。
     *
     * 任何一次删除都会让它 +1。界面拿它当"缓存变了"的信号：
     * 书架据此重抽一次封面，而不是继续拿着一份已经被删掉的路径显示空白。
     *
     * 为什么需要它：封面缓存是**文件**，界面拿到的是路径。
     * 文件被清掉之后，路径还在手上，但那个路径已经指向一个不存在的文件 ——
     * 没有通知机制的话，界面会一直画着一张"读不出来"的封面。
     */
    private val _epoch = MutableStateFlow(0)
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    companion object {
        private const val COVER_PREFIX = "cover_"
        const val COVER_IMAGE_SUFFIX = ".img"
        const val COVER_MISSING_SUFFIX = ".none"

        /** 指纹取前 12 位：够区分两本书，又不至于让文件名长到难看。 */
        private const val FINGERPRINT_KEEP = 12

        /**
         * 封面缓存的文件名主干：`cover_<docId>_<指纹前12位>`。
         */
        fun coverBase(docId: String, fingerprint: String): String =
            "$COVER_PREFIX${docId}_${fingerprint.take(FINGERPRINT_KEEP)}"

        /**
         * 认出"这是一个封面缓存文件"，并取出它属于哪本书。
         *
         * 只认 UUID 形态的 docId：将来 `derived/` 里放别的东西时，
         * 这个函数会返回 null，那些文件就不会被当成孤儿封面误删。
         */
        private val COVER_NAME = Regex("^cover_([A-Za-z0-9-]{36})_[0-9a-fA-F]{12}\\.(img|none)$")

        fun coverDocIdOf(fileName: String): String? =
            COVER_NAME.matchEntire(fileName)?.groupValues?.get(1)
    }

    /**
     * 当前占用：多少张封面图、共多少字节。
     *
     * 只算 `.img` —— `.none` 是 0 字节的事实标记，把它算进来会让
     * "缓存 12 个文件"这种话说得比实际占的地方多，而 `.none` 也不该被清掉。
     */
    fun usage(): Usage {
        val images = paths.derivedDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(COVER_IMAGE_SUFFIX) }
            ?: emptyList()
        return Usage(images.size, images.sumOf { it.length() })
    }

    /** 抽出来的封面字节。 */
    fun coverImage(docId: String, fingerprint: String): File =
        File(paths.derivedDir, coverBase(docId, fingerprint) + COVER_IMAGE_SUFFIX)

    /** "这本没有封面"的标记。 */
    fun coverMissingMarker(docId: String, fingerprint: String): File =
        File(paths.derivedDir, coverBase(docId, fingerprint) + COVER_MISSING_SUFFIX)

    /**
     * 清掉某个文档自己的封面缓存。
     *
     * `.img` 与 `.none` 一起删：文档都没了，"它有没有封面"这个事实
     * 也不再有人关心，留着只是垃圾。返回被删掉的文件数。
     */
    fun clearDocument(docId: String): Int {
        val prefix = "$COVER_PREFIX${docId}_"
        var removed = 0
        paths.derivedDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(prefix) && file.delete()) removed++
        }
        if (removed > 0) bumpEpoch()
        return removed
    }

    /**
     * 清掉**孤儿**缓存：属于已经不存在的文档的那些文件。
     *
     * 孤儿从哪来：旧版本删文档时没清缓存、或者导入/删除过程中崩在中间。
     * 它们既占地方，又让人误以为"还有一本没显示的书"。
     */
    fun clearOrphanCovers(liveDocIds: Set<String>): Int {
        var removed = 0
        paths.derivedDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val docId = coverDocIdOf(file.name) ?: return@forEach
            if (docId !in liveDocIds && file.delete()) removed++
        }
        if (removed > 0) bumpEpoch()
        return removed
    }

    /**
     * 清空封面**图片**。
     *
     * 只删 `.img`，保留 `.none`（见类注释）：`.none` 不占空间，
     * 而保留它能让"没封面的那批书"在清完缓存后不用重新解一遍 EPUB
     * 去确认同一件事。
     *
     * 返回**清掉了多少** —— 界面要报的是这个。
     */
    fun clearAll(): Cleared {
        var files = 0
        var bytes = 0L
        paths.derivedDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.name.endsWith(COVER_IMAGE_SUFFIX)) return@forEach
            val size = file.length()
            if (file.delete()) {
                files++
                bytes += size
            }
        }
        if (files > 0) bumpEpoch()
        return Cleared(files, bytes)
    }

    /** 缓存变了：让界面知道"手上有路径可能已经失效"。 */
    private fun bumpEpoch() {
        _epoch.value = _epoch.value + 1
    }

    /** 当前占用。 */
    data class Usage(val fileCount: Int, val bytes: Long)

    /** 清掉了多少。 */
    data class Cleared(val fileCount: Int, val bytes: Long)
}
