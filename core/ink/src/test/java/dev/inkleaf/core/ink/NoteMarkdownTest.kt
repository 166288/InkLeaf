package dev.inkleaf.core.ink

import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.InkReflowSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class NoteMarkdownTest {

    private val exportedAt = Instant.parse("2026-10-21T10:30:00Z").toEpochMilli()

    @Test
    fun `空笔记也导出一个可读的文件`() {
        val text = NoteMarkdown.render("无名书", null, emptyList(), exportedAt, ZoneOffset.UTC)
        assertTrue(text.startsWith("# 无名书"))
        assertTrue(text.contains("- 笔记：无"))
        assertTrue(text.contains("还没有笔记。"))
        assertTrue(text.contains("2026-10-21 10:30"))
    }

    @Test
    fun `引文逐行加引用前缀_位置与批注都在`() {
        val text = NoteMarkdown.render(
            title = "测试书",
            author = "某作者",
            notes = listOf(
                ExportableNote(
                    kind = "高亮",
                    quote = "第一行\n第二行",
                    noteText = "我的想法",
                    pageNumber = 3,
                    ratio = 0.25f,
                    createdAt = exportedAt,
                    colorArgb = 0x66FFD54F,
                ),
                ExportableNote(
                    kind = "书签",
                    quote = null,
                    noteText = null,
                    pageNumber = 5,
                    ratio = 0.6f,
                    createdAt = exportedAt + 1,
                    colorArgb = 0xFF000000.toInt(),
                ),
            ),
            exportedAt = exportedAt,
            zone = ZoneOffset.UTC,
        )
        assertTrue(text.contains("- 作者：某作者"))
        assertTrue(text.contains("- 笔记：高亮 1 · 书签 1"))
        assertTrue(text.contains("## 高亮"))
        assertTrue(text.contains("### 第 3 页 · 25%"))
        assertTrue(text.contains("> 第一行"))
        assertTrue(text.contains("> 第二行"))
        assertTrue(text.contains("我的想法"))
        assertTrue(text.contains("## 书签"))
        assertTrue(text.contains("### 第 5 页 · 60%"))
        // 高亮在书签之前：导出顺序与书里的前后顺序一致，而不是按创建时间。
        assertTrue(text.indexOf("## 高亮") < text.indexOf("## 书签"))
    }

    @Test
    fun `没有页号时用进度标位置`() {
        val text = NoteMarkdown.render(
            "重排格式",
            null,
            listOf(
                ExportableNote(
                    kind = "边注",
                    quote = "一句话",
                    noteText = "批注",
                    pageNumber = null,
                    ratio = 0.42f,
                    createdAt = exportedAt,
                    colorArgb = 0,
                ),
            ),
            exportedAt,
            ZoneOffset.UTC,
        )
        assertTrue(text.contains("### 42%"))
        assertTrue(text.contains("## 边注"))
    }

    @Test
    fun `从领域模型映射时保留页号与笔迹段数`() {
        val facts = TestDoc(count = 2).facts()
        val chain = requireNotNull(facts.chainForRange(0, 4, pageKey = "p4"))
        val annotations = listOf(
            Annotation.Highlight(
                id = "h1",
                docId = "d1",
                createdAt = 1,
                colorArgb = 0x66FFD54F,
                noteText = "想法",
                anchor = chain,
            ),
            Annotation.Ink(
                id = "i1",
                docId = "d1",
                createdAt = 2,
                colorArgb = 0,
                anchor = null,
                semantics = InkReflowSemantics.FOLLOWER,
                pages = listOf("p2"),
            ),
        )
        val notes = NoteMarkdown.fromAnnotations(annotations) { id -> if (id == "i1") 3 else 0 }
        assertEquals(listOf("手写", "高亮"), notes.map { it.kind }) // 2 页在 4 页之前
        assertEquals(4, notes[1].pageNumber)
        assertEquals(3, notes[0].strokeCount)
        assertEquals(2, notes[0].pageNumber)
    }
}
