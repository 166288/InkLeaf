package dev.inkleaf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阅读器路由的编解码。
 *
 * 这条链路失败时**不报错**：拼错跳到另一个文档，拆错跳不过去。
 * 所以它值得有单测。
 */
class ReaderRouteTest {

    @Test
    fun `不带笔记的路由`() {
        assertEquals("reader/doc-1", ReaderRoute.build("doc-1"))
        assertEquals("doc-1" to null, ReaderRoute.parse("reader/doc-1"))
    }

    @Test
    fun `带笔记的路由`() {
        val route = ReaderRoute.build("doc-1", "note-7")
        assertEquals("reader/doc-1?note=note-7", route)
        assertEquals("doc-1" to "note-7", ReaderRoute.parse(route))
    }

    @Test
    fun `空的笔记 id 等于没有笔记`() {
        assertEquals("reader/doc-1", ReaderRoute.build("doc-1", ""))
        assertEquals("reader/doc-1", ReaderRoute.build("doc-1", null))
        assertEquals("doc-1" to null, ReaderRoute.parse("reader/doc-1?note="))
    }

    @Test
    fun `畸形输入不猜`() {
        // 文档 id 由系统生成，不含问号 —— 这是路由格式的前提。
        // 万一真来了畸形路由：按第一个问号切文档 id，
        // 参数里找不到 "note=" 就不给笔记 id（**不猜**，猜出来的目标会跳到别处）。
        val (docId, noteId) = ReaderRoute.parse("reader/a?b?note=n1")
        assertEquals("a", docId)
        assertNull(noteId)
    }
}
