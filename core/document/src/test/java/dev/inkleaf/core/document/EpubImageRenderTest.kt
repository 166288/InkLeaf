package dev.inkleaf.core.document

import android.graphics.Bitmap
import dev.inkleaf.core.model.DocumentKind
import dev.inkleaf.core.model.PageLayoutMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * EPUB 正文图片（M31）的回归测试。
 *
 * ## 这些断言为什么值得写
 *
 * 图片这条链路上有三个"看起来能跑、其实错位"的坑，它们都不会崩、只会让书变得奇怪：
 *
 *  1. **图片不占字符** → 图后面的所有文字偏移整体差一位，锚点、选中、搜索高亮全错；
 *  2. **行高不把图算进去** → 图会盖住下一行，读起来像"后半段被撕掉了"；
 *  3. **不按目标宽度解码** → 一张 3000×4000 的插图原样解开是 48 MB，几页就 OOM。
 *
 * 三条都在这里钉住：占位字符在文本里（`normalized.text[start]`）、
 * 行盒高度随图增长、`EpubImageProvider` 按上限缩放。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubImageRenderTest {

    private val theme = DocumentTheme.light(textSizePx = 32f)

    private fun metrics(width: Int = 900, height: Int = 1200) = PageLayoutMetrics(
        contentWidthPx = width,
        contentHeightPx = height,
        marginLeftPx = 0f,
        marginRightPx = 0f,
        marginTopPx = 0f,
        marginBottomPx = 0f,
    )

    /** 生成一张真实可解码的纯色 PNG —— 假字节测不出"能不能解码"这件事。 */
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun epubWithImage(src: String = "pic.png", bytes: ByteArray = png(120, 80)): ByteArray =
        TestEpub.build(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>前面的话。</p><img src=\"$src\"/>"),
                ),
                // 章节 href 已由 OPF 解析成包内全路径（OEBPS/ch1.xhtml），图也一样
                extra = mapOf("OEBPS/$src" to bytes),
            ),
        )

    @Test
    fun `图片块带书内路径且占一个字符`() {
        val session = DocumentCodec.sessionFromBytes("d", DocumentKind.EPUB, epubWithImage()).session
        val block = session.blocks.single { it.kind == BlockKind.IMAGE }
        assertEquals("OEBPS/pic.png", block.imageHref)
        // "占一个字符"是整条链路的前提：偏移不差位，锚点与选中才不会整体错开。
        assertEquals(1, block.span.length)
        assertEquals(IMAGE_PLACEHOLDER, session.normalized.text[block.absoluteStart])
    }

    @Test
    fun `图片高度进入行高`() {
        val session = DocumentCodec.sessionFromBytes(
            "d",
            DocumentKind.EPUB,
            epubWithImage(bytes = png(300, 200)),
        ).session
        val layout = session.layout(metrics(), theme)
        val block = session.blocks.single { it.kind == BlockKind.IMAGE }
        val line = layout.lineBoxes().single { it.anchor.blockPath == block.blockPath }
        val height = line.rectDoc.bottom - line.rectDoc.top
        // 行高必须至少容得下这张 200px 高的图；否则图会压到下一行。
        assertTrue("图片行高应把图算进去，实际 $height px", height >= 200f)
    }

    @Test
    fun `图片按栏宽缩小`() {
        val provider = EpubImageProvider(mapOf("big.png" to png(400, 200)))
        val drawable = provider.drawable("big.png", maxWidthPx = 100, maxHeightPx = 1000)!!
        assertEquals(100, drawable.intrinsicWidth)
        assertEquals(50, drawable.intrinsicHeight)
    }

    @Test
    fun `超出高度上限的图按高度缩`() {
        val provider = EpubImageProvider(mapOf("tall.png" to png(200, 800)))
        val drawable = provider.drawable("tall.png", maxWidthPx = 1000, maxHeightPx = 200)!!
        assertEquals(200, drawable.intrinsicHeight)
        assertEquals(50, drawable.intrinsicWidth)
    }

    @Test
    fun `条目缺失时不留下指向空的图片块`() {
        val bytes = TestEpub.build(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("ch1.xhtml", "<h1>正文</h1><p>字。</p><img src=\"none.png\"/>"),
                ),
            ),
        )
        val session = DocumentCodec.sessionFromBytes("d", DocumentKind.EPUB, bytes).session
        val block = session.blocks.single { it.kind == BlockKind.IMAGE }
        assertNull("找不到的图不该带着 href 进排版", block.imageHref)
        // 排版仍要能跑完：占位字符被置为透明，不抛异常、不改行高
        assertEquals(1, block.span.length)
        session.layout(metrics(), theme)
    }

    @Test
    fun `预算之外不返回图也不崩`() {
        // 1 KB 预算：一张 400×200 的位图约 320 KB，必须被拒，而不是把内存吃穿。
        val provider = EpubImageProvider(mapOf("big.png" to png(400, 200)), budgetKb = 1)
        assertNull(provider.drawable("big.png", maxWidthPx = 400, maxHeightPx = 400))
    }
}
