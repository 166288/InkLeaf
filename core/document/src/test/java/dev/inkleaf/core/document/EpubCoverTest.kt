package dev.inkleaf.core.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面提取：三种声明方式都要认，认不出来时要安静地返回 null。
 *
 * 这条链路书架依赖它，但**失败不该是错误**（没封面的书照样要出现在架上），
 * 所以"取不到"的用例和"取得到"的用例一样重要。
 */
class EpubCoverTest {

    @Test
    fun `EPUB3 的 cover-image 属性能被识别`() {
        val cover = TestEpub.Cover(decl = TestEpub.CoverDecl.EPUB3)
        val book = TestEpub.build(TestEpub.Spec(cover = cover))

        assertArrayEquals(cover.bytes, EpubCoverExtractor.extract(book))
    }

    @Test
    fun `EPUB2 的 meta name=cover 能被识别`() {
        val cover = TestEpub.Cover(decl = TestEpub.CoverDecl.EPUB2)
        val book = TestEpub.build(TestEpub.Spec(cover = cover))

        assertArrayEquals(cover.bytes, EpubCoverExtractor.extract(book))
    }

    @Test
    fun `没声明但文件名带 cover 也能猜到`() {
        // 转换工具的常见输出：图片在 manifest 里，但既不写 properties 也不写 meta。
        val cover = TestEpub.Cover(file = "images/cover.jpg", decl = TestEpub.CoverDecl.NONE)
        val book = TestEpub.build(TestEpub.Spec(cover = cover))

        assertArrayEquals(cover.bytes, EpubCoverExtractor.extract(book))
    }

    @Test
    fun `没有封面时返回 null 而不是抛异常`() {
        val book = TestEpub.build(TestEpub.Spec())

        assertNull(EpubCoverExtractor.extract(book))
    }

    @Test
    fun `SVG 封面返回 null`() {
        // BitmapFactory 解不了 SVG；返回字节只会让界面拿到一张画不出来的图。
        val cover = TestEpub.Cover(
            file = "cover.svg",
            mediaType = "image/svg+xml",
            decl = TestEpub.CoverDecl.EPUB3,
            bytes = "<svg/>".toByteArray(),
        )
        val book = TestEpub.build(TestEpub.Spec(cover = cover))

        assertNull(EpubCoverExtractor.extract(book))
    }

    @Test
    fun `坏掉的 epub 返回 null`() {
        assertNull(EpubCoverExtractor.extract(byteArrayOf(1, 2, 3, 4)))
    }
}
