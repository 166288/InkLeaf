package dev.inkleaf.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页搜索清单（正文 + 我的边注）的测试。
 *
 * 这里钉的是**口径**，不是排版：命中怎么算、按什么顺序、上限怎么算、
 * 跳不过去的怎么办、查询变化后默认跳到哪一条。这几件事一旦与正文搜索漂开，
 * 症状是"同一个词，正文里找得到、我写的那句里找不到"，而两边各自的测试都是绿的。
 *
 * 正文用的是《春晓》—— 选它是因为它短到可以把偏移一个个数出来：
 * `春眠不觉晓`=0..4、`处处闻啼鸟`=6..10、`夜来风雨声`=12..16、`花落知多少`=18..22，
 * 换行在第 5 / 11 / 17 位。「风」只出现在 `夜来风雨声` 里（下标 14）。
 */
class ReaderSearchTest {

    private val text = "春眠不觉晓\n处处闻啼鸟\n夜来风雨声\n花落知多少"

    /** 一条打字批注**解析之后**的行。没给偏移 = 现在挂不回任何一行。 */
    private fun row(
        id: String,
        body: String?,
        charOffset: Int?,
        kind: TypedNoteKind = TypedNoteKind.MARGIN_NOTE,
        excerpt: String? = null,
    ): TypedNoteRow = TypedNoteRow(
        annotationId = id,
        kind = kind,
        state = if (charOffset == null) InkState.ORPHANED else InkState.RESOLVED,
        confidence = if (charOffset == null) 0f else 0.95f,
        reason = if (charOffset == null) AnchorReason.ORPHANED_CAUSE else "已定位在这一行。",
        noteText = body,
        excerpt = excerpt,
        charOffset = charOffset,
    )

    private fun ReaderSearch.Result.notes(): List<ReaderSearch.NoteHit> =
        hits.filterIsInstance<ReaderSearch.NoteHit>()

    @Test
    fun `正文与边注按位置合成一份清单，跳不过去的排在最后`() {
        val onWind = row("note-a", "今晚有风，记得关窗", charOffset = 12, excerpt = "夜来风雨声")
        val orphan = row("note-b", "春天的风最舒服", charOffset = null)

        val result = ReaderSearch.search(text, listOf(onWind, orphan), "风")

        assertEquals(3, result.count)
        assertEquals("正文里那一处算一条", 1, result.textCount)
        assertEquals("两条边注各算一条", 2, result.noteCount)
        assertEquals(
            "清单按正文位置排：边注（第 12 位）→ 正文（第 14 位）→ 跳不过去的那条",
            listOf(12, 14, null),
            result.hits.map { it.charOffset },
        )
        assertEquals("note-a", result.notes().first().annotationId)
        assertEquals(
            "跳不过去的也要列出来：少列一条会被用户当成「我写过的那句话丢了」",
            "note-b",
            result.notes().last().annotationId,
        )
    }

    @Test
    fun `默认跳转选正文那一处，哪怕清单第一条是边注`() {
        val result = ReaderSearch.search(
            text,
            listOf(row("note-a", "今晚有风", charOffset = 12)),
            "风",
        )

        assertEquals(1, result.defaultIndex)
        assertTrue("默认目标必须是正文命中", result.hits[result.defaultIndex] is ReaderSearch.TextHit)
    }

    @Test
    fun `正文一处都没有时才退到第一条跳得过去的边注`() {
        val result = ReaderSearch.search(text, listOf(row("note-a", "今晚有风，记得关窗", 12)), "有风")

        assertEquals("有风 只出现在边注里", 0, result.textCount)
        assertEquals(1, result.count)
        assertEquals("只有它可跳，不跳等于搜了没反应", 0, result.defaultIndex)
    }

    @Test
    fun `全都跳不过去时没有默认目标`() {
        val result = ReaderSearch.search(text, listOf(row("note-b", "春天的风最舒服", null)), "春天")

        assertEquals(1, result.count)
        assertEquals(0, result.textCount)
        assertEquals("没有可跳的就不许假装有", -1, result.defaultIndex)
        assertNull(result.hits.single().charOffset)
    }

    @Test
    fun `边注里的命中区间是它自己正文里的区间`() {
        val hit = ReaderSearch.search(
            text,
            listOf(row("note-a", "今晚有风，记得关窗", 12)),
            "有风",
        ).notes().single()

        assertEquals(2, hit.matchStart)
        assertEquals(4, hit.matchEndExclusive)
        assertEquals("有风", hit.noteText.substring(hit.matchStart, hit.matchEndExclusive))
        assertEquals("跳转用的是它挂的那一行，不是命中在它自己正文里的位置", 12, hit.charOffset)
    }

    @Test
    fun `一条边注里出现多次就列多条`() {
        val result = ReaderSearch.search(text, listOf(row("note-c", "风风风", 12)), "风")

        assertEquals(3, result.noteCount)
        assertEquals(
            "重叠命中算多处：不算的话会出现「明明写了三遍却只找到一处」",
            listOf(0, 1, 2),
            result.notes().map { it.matchStart },
        )
    }

    @Test
    fun `匹配口径与正文一致：忽略大小写、折叠查询串空白`() {
        val result = ReaderSearch.search("a b", listOf(row("note-1", "x A B", 3)), "  A   B  ")

        assertEquals("查询串按文档口径折叠，清单标题显示的就是它", "A B", result.query)
        assertEquals(1, result.textCount)
        assertEquals(1, result.noteCount)
    }

    @Test
    fun `空白查询不返回任何东西`() {
        val rows = listOf(row("note-a", "今晚有风", 12))

        for (blank in listOf("", "   ", "\n\t ")) {
            val result = ReaderSearch.search(text, rows, blank)
            assertTrue("空白查询不是「搜全书」", result.isEmpty)
            assertEquals(0, result.noteCount)
            assertEquals(-1, result.defaultIndex)
        }
    }

    @Test
    fun `没有内容的边注不会出现在清单里`() {
        val rows = listOf(row("note-empty", null, 12), row("note-blank", "   ", 12))

        val result = ReaderSearch.search("风来了", rows, "风")

        assertEquals(1, result.textCount)
        assertEquals("空正文没有可匹配的东西，也不该崩", 0, result.noteCount)
    }

    @Test
    fun `边注条数上限会截断并如实说明`() {
        val three = (1..3).map { row("note-$it", "风来了", 12) }

        val capped = ReaderSearch.search(text, three, "风", maxNoteHits = 2)
        assertEquals(2, capped.noteCount)
        assertTrue("截断了就必须说，不许静默少给", capped.noteTruncated)
        assertEquals(
            "上限按库里的顺序取前几条（与正文那 500 处一样是可用性上限，不是位置优先）",
            listOf("note-1", "note-2"),
            capped.notes().map { it.annotationId },
        )

        val exact = ReaderSearch.search(text, three.take(2), "风", maxNoteHits = 2)
        assertEquals(2, exact.noteCount)
        assertFalse("恰好等于上限不算截断", exact.noteTruncated)
    }

    @Test
    fun `两侧各自记自己的截断`() {
        val windy = "风起\n风落"

        val result = ReaderSearch.search(windy, listOf(row("note-a", "风", 3)), "风", maxTextHits = 1)

        assertTrue(result.textTruncated)
        assertEquals(1, result.textCount)
        assertEquals(1, result.noteCount)
        assertFalse("边注这一侧没超上限", result.noteTruncated)
    }

    @Test
    fun `上限为 0 时那一侧完全不出现`() {
        val result = ReaderSearch.search(text, listOf(row("note-a", "风", 12)), "风", maxNoteHits = 0)

        assertEquals("关掉就得是真的关掉，而不是「全部返回」", 0, result.noteCount)
        assertFalse(result.noteTruncated)
    }

    @Test
    fun `同一位置时正文命中排在边注命中前面`() {
        val result = ReaderSearch.search(text, listOf(row("note-a", "夜里有风", 12)), "夜")

        assertEquals(2, result.count)
        assertEquals(12, result.hits[0].charOffset)
        assertEquals(12, result.hits[1].charOffset)
        assertTrue("正文在前：用户找的默认是正文那一处", result.hits[0] is ReaderSearch.TextHit)
        assertTrue(result.hits[1] is ReaderSearch.NoteHit)
    }

    @Test
    fun `高亮与边注同属打字批注，都会带上自己的种类`() {
        val rows = listOf(
            row("note-a", "风", 12),
            row("hl-1", "风", 14, kind = TypedNoteKind.HIGHLIGHT),
        )

        val result = ReaderSearch.search(text, rows, "风")

        assertEquals(2, result.noteCount)
        assertEquals(
            listOf(TypedNoteKind.MARGIN_NOTE, TypedNoteKind.HIGHLIGHT),
            result.notes().map { it.kind },
        )
    }

    @Test
    fun `清单顺序是正文顺序，不随库里的顺序变`() {
        val rows = listOf(row("late", "风", 18), row("early", "风", 0))

        val result = ReaderSearch.search(text, rows, "风")

        assertEquals(
            "库里先读到哪条与用户无关：从上往下读清单就是从上往下翻书",
            listOf("early", "late"),
            result.notes().map { it.annotationId },
        )
        assertEquals(listOf(0, 14, 18), result.hits.map { it.charOffset })
    }

    @Test
    fun `一处都找不到时清单是空的但查询串还在`() {
        val result = ReaderSearch.search(text, listOf(row("note-a", "风", 12)), "不存在的词")

        assertTrue(result.isEmpty)
        assertEquals(0, result.count)
        assertEquals(-1, result.defaultIndex)
        assertEquals("不存在的词", result.query)
    }
}
