package dev.inkleaf.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 导出的**转义**与结构。
 *
 * 这是全工程唯一一个"手写序列化"的地方，所以它必须有自己的测试：
 * 一个没转义的双引号就能让整份 JSON 变成非法文件，而那个错误不会在 App 里
 * 以任何形式暴露 —— 用户只会发现"导出的文件打不开"。
 */
class NoteJsonTest {

    private fun note(
        kind: String = "高亮",
        quote: String? = "被标记的那句话",
        text: String? = null,
        page: Int? = 3,
        ratio: Float = 0.25f,
    ) = ExportableNote(
        kind = kind,
        quote = quote,
        noteText = text,
        pageNumber = page,
        ratio = ratio,
        createdAt = 1_700_000_000_000L,
        colorArgb = 0x66FFD54F.toInt(),
    )

    @Test
    fun `引号与反斜杠会被转义`() {
        assertEquals("\"a\\\"b\"", NoteJson.quote("a\"b"))
        assertEquals("\"a\\\\b\"", NoteJson.quote("a\\b"))
    }

    @Test
    fun `换行与制表符用短转义`() {
        assertEquals("\"a\\nb\"", NoteJson.quote("a\nb"))
        assertEquals("\"a\\tb\"", NoteJson.quote("a\tb"))
        assertEquals("\"a\\rb\"", NoteJson.quote("a\rb"))
    }

    @Test
    fun `控制字符用四位 unicode 转义`() {
        assertEquals("\"\\u0001\"", NoteJson.quote("\u0001"))
    }

    @Test
    fun `中文与 emoji 原样输出`() {
        // 转成 \u 序列会让文件变成没人愿意打开的乱码，而 JSON 本来就是 UTF-8。
        assertEquals("\"中文😀\"", NoteJson.quote("中文😀"))
    }

    @Test
    fun `没有笔记时也是合法 JSON`() {
        val json = NoteJson.render(
            title = "书名",
            author = null,
            docId = "doc-1",
            kind = "TXT",
            notes = emptyList(),
            exportedAt = 1L,
        )
        assertTrue("空清单必须是 []，不能少这个括号", json.contains("\"notes\": []"))
        assertTrue(json.trimEnd().endsWith("}"))
    }

    @Test
    fun `字段齐全且页码可空`() {
        val json = NoteJson.render(
            title = "书\"名",
            author = "作者",
            docId = "doc-1",
            kind = "PDF",
            notes = listOf(note(page = null), note(kind = "手写", text = "批注")),
            exportedAt = 1L,
        )
        assertTrue("书名里的引号必须被转义", json.contains("\"title\": \"书\\\"名\""))
        assertTrue("没有页号时写 null 而不是 0", json.contains("\"page\": null"))
        assertTrue(json.contains("\"kind\": \"手写\""))
        assertTrue(json.contains("\"text\": \"批注\""))
        // 手写笔记带段数，其它类型不带（省掉一堆恒为 0 的字段）。
        assertEquals(1, Regex("\"strokeCount\"").findAll(json).count())
    }

    @Test
    fun `进度保留三位小数且被夹进 0 到 1`() {
        val json = NoteJson.render("t", null, "d", "TXT", listOf(note(ratio = 1.7f)), 1L)
        assertTrue(json.contains("\"ratio\": 1.000"))

        val small = NoteJson.render("t", null, "d", "TXT", listOf(note(ratio = 0.123456f)), 1L)
        assertTrue(small.contains("\"ratio\": 0.123"))
    }

    @Test
    fun `版本号写进文件`() {
        val json = NoteJson.render("t", null, "d", "TXT", emptyList(), 1L)
        assertTrue("外部解析器要靠它判断字段含义", json.contains("\"version\": ${NoteJson.VERSION}"))
    }
}
