package dev.inkleaf.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 层级目录：EPUB3 nav、EPUB2 `toc.ncx`，以及"读不到就退回 spine"这条底线。
 *
 * 为什么这些用例重要：目录是**锦上添花**，正文才是主菜。
 * 一本书因为目录解析不了就读不了，那是本末倒置 —— 所以"退回 spine"
 * 这条路必须被钉住，而不是只在注释里写一句。
 */
class EpubTocTest {

    private fun nav(body: String) = TestEpub.Toc(
        kind = TestEpub.TocKind.NAV,
        file = "nav.xhtml",
        body = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc" id="toc">
              <ol>
                $body
              </ol>
            </nav>
          </body>
        </html>
        """.trimIndent().trim(),
    )

    private fun ncx(body: String) = TestEpub.Toc(
        kind = TestEpub.TocKind.NCX,
        file = "toc.ncx",
        body = """
        <?xml version="1.0" encoding="utf-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <head><meta name="dtb:uid" content="x"/></head>
          <docTitle><text>目录</text></docTitle>
          <navMap>
            $body
          </navMap>
        </ncx>
        """.trimIndent().trim(),
    )

    // ── EPUB3 nav ───────────────────────────────────────────────────────────

    @Test
    fun `EPUB3 的 nav 读出层级目录`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = nav(
                    """
                    <li><a href="ch1.xhtml">第一章</a>
                      <ol>
                        <li><a href="ch1.xhtml#s1">第一节</a></li>
                        <li><a href="ch1.xhtml#s2">第二节</a></li>
                      </ol>
                    </li>
                    <li><a href="ch2.xhtml">第二章</a></li>
                    """.trimIndent(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(listOf("第一章", "第一节", "第二节", "第二章"), chapters.map { it.title })
        assertEquals(listOf(1, 2, 2, 1), chapters.map { it.level })
    }

    @Test
    fun `nav 的目录项按文件定位到正文`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                chapters = listOf(
                    TestEpub.Chapter("ch1.xhtml", "<h1>第一章</h1><p>第一段正文。</p>"),
                    TestEpub.Chapter("ch2.xhtml", "<h1>第二章</h1><p>第二段正文。</p>"),
                ),
                toc = nav(
                    """
                    <li><a href="ch2.xhtml">第二章</a></li>
                    <li><a href="ch1.xhtml">第一章</a></li>
                    """.trimIndent(),
                ),
            ),
        )

        val text = EpubExtractor.extract(book)
        val chapters = text.chapters
        assertEquals(listOf("第二章", "第一章"), chapters.map { it.title })

        // 第二章的偏移必须落在它自己的正文里：偏移之后紧接着是"第二章"。
        val second = chapters[0].charOffset
        assertTrue("偏移应指向第二章", text.text.substring(second).startsWith("第二章"))
        val first = chapters[1].charOffset
        assertTrue("偏移应指向第一章", text.text.substring(first).startsWith("第一章"))
    }

    @Test
    fun `nav 指向 spine 之外的文件的目录项被跳过`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = nav(
                    """
                    <li><a href="appendix.xhtml">附录（不在 spine 里）</a></li>
                    <li><a href="ch1.xhtml">第一章</a></li>
                    """.trimIndent(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(listOf("第一章"), chapters.map { it.title })
    }

    // ── EPUB2 toc.ncx ──────────────────────────────────────────────────────

    @Test
    fun `EPUB2 的 toc ncx 读出层级目录`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = ncx(
                    """
                    <navPoint id="np1" playOrder="1">
                      <navLabel><text>第一章</text></navLabel>
                      <content src="ch1.xhtml"/>
                      <navPoint id="np1_1" playOrder="2">
                        <navLabel><text>第一节</text></navLabel>
                        <content src="ch1.xhtml#s1"/>
                      </navPoint>
                    </navPoint>
                    <navPoint id="np2" playOrder="3">
                      <navLabel><text>第二章</text></navLabel>
                      <content src="ch2.xhtml"/>
                    </navPoint>
                    """.trimIndent(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(listOf("第一章", "第一节", "第二章"), chapters.map { it.title })
        assertEquals(listOf(1, 2, 1), chapters.map { it.level })
    }

    @Test
    fun `ncx 的 content 用相对上级目录的路径也能定位`() {
        // ncx 常常放在 EPUB2 的 TOC 目录里，src 写成 "../text/ch1.xhtml"。
        // 这时算出来的路径与 spine 的路径对不上，只能按文件名兜底。
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = ncx(
                    """
                    <navPoint id="np1">
                      <navLabel><text>第一章</text></navLabel>
                      <content src="../OEBPS/ch1.xhtml"/>
                    </navPoint>
                    """.trimIndent(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(listOf("第一章"), chapters.map { it.title })
    }

    // ── 优先级与兜底 ────────────────────────────────────────────────────────

    @Test
    fun `nav 与 ncx 同时存在时用 nav`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = nav("""<li><a href="ch1.xhtml">来自 nav</a></li>"""),
                extra = mapOf(
                    "OEBPS/toc.ncx" to """
                        <?xml version="1.0" encoding="utf-8"?>
                        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                          <navMap>
                            <navPoint><navLabel><text>来自 ncx</text></navLabel><content src="ch1.xhtml"/></navPoint>
                          </navMap>
                        </ncx>
                    """.trimIndent().toByteArray(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(listOf("来自 nav"), chapters.map { it.title })
    }

    @Test
    fun `没有目录时退回 spine 一章一项`() {
        val book = TestEpub.build(TestEpub.Spec())

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(2, chapters.size)
        assertEquals(listOf(1, 1), chapters.map { it.level })
    }

    @Test
    fun `目录文件读不出来时退回 spine 而不是让书读不了`() {
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = TestEpub.Toc(
                    kind = TestEpub.TocKind.NCX,
                    file = "toc.ncx",
                    body = "这不是 XML，也不是 ncx",
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(2, chapters.size)
        assertTrue("退回 spine 后应当仍有内容", chapters.all { it.title.isNotBlank() })
    }

    @Test
    fun `目录项全部指向 spine 之外时退回 spine`() {
        // 映射成功 0 条 → 路径基准猜错了，用 spine 更稳。
        val book = TestEpub.build(
            TestEpub.Spec(
                toc = nav(
                    """
                    <li><a href="other/a.xhtml">甲</a></li>
                    <li><a href="other/b.xhtml">乙</a></li>
                    """.trimIndent(),
                ),
            ),
        )

        val chapters = EpubExtractor.extract(book).chapters

        assertEquals(2, chapters.size)
        assertTrue("应退回 spine 的章节", chapters.none { it.title == "甲" || it.title == "乙" })
    }
}
