package dev.inkleaf.core.document

/**
 * 从 EPUB 里取出**封面图片的原始字节**。
 *
 * ## 为什么单独一个入口，而不是塞进 [EpubExtractor]
 *
 * 导入流程只关心正文。把封面顺手读进 [EpubExtractor] 的结果里，会让"导入一本书"
 * 永远多解压一张几 MB 的图，而绝大多数导入路径（编码预览、指纹、分页）根本用不到它。
 * 书架需要时才取，是更合适的时机。
 *
 * ## 它不改任何数据
 *
 * 只读：输入是 EPUB 字节，输出是图片字节或 null。不落盘、不写库、不依赖 Android。
 * 书架把它产出的字节缓存到 `derived/` 下，缓存策略由界面层决定 —— 解析器不该
 * 知道"缓存放在哪、什么时候失效"。
 *
 * ## 失败即 null
 *
 * 取不到封面不是错误：没声明封面、图片条目损坏、格式是 SVG（`BitmapFactory`
 * 解不了）…… 这些书的书架都会落到自绘封面，用户看到的仍然是"一本书"。
 * 因此这里不抛异常，也不区分原因。
 */
object EpubCoverExtractor {

    /**
     * @param epubBytes 整个 `.epub` 文件的字节（ZIP）。
     * @return 封面图片的原始字节（JPEG/PNG/WebP/GIF 原样返回，不解码）；没有则 null。
     */
    fun extract(epubBytes: ByteArray): ByteArray? = runCatching {
        val archive = EpubArchive(epubBytes)
        val opfPath = archive.findOpfPath()
        val opfBytes = archive.readAll(setOf(opfPath))[opfPath] ?: return@runCatching null
        val opf = EpubOpfParser.parse(opfBytes, opfPath)
        val href = opf.coverHref ?: return@runCatching null

        // SVG 封面交给调用方画兜底图：BitmapFactory 没有 SVG 解码器，
        // 返回一堆字节只会让界面层拿到一张解不出来的图。
        if (href.endsWith(".svg", ignoreCase = true)) return@runCatching null

        archive.readAll(setOf(href))[href]
    }.getOrNull()
}
