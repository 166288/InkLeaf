package dev.inkleaf.app

/**
 * 阅读器路由的编解码。
 *
 * 路由形如：
 *   - `reader/{docId}` —— 从书架打开
 *   - `reader/{docId}?note={noteId}` —— 从笔记清单"跳回原位"打开（A3）
 *
 * 为什么要抽出来：路由字符串在 [MainActivity] 里被拼、被拆，
 * 而**拼错与拆错都不会报错**——拼错就是跳到另一个不存在的文档，
 * 拆错就是笔记跳不过去（而且静默）。所以它得有一个能被单测钉住的地方。
 */
internal object ReaderRoute {

    /** 拼一个阅读器路由。 */
    fun build(docId: String, noteId: String? = null): String =
        if (noteId.isNullOrBlank()) "reader/$docId" else "reader/$docId?note=$noteId"

    /**
     * 拆一个阅读器路由。
     *
     * @return 文档 id 与笔记 id（没有则为 null）。
     */
    fun parse(route: String): Pair<String, String?> {
        val body = route.removePrefix("reader/")
        val docId = body.substringBefore('?')
        // 参数按 `&` 分开，逐个找 "note="。找不到就不给笔记 id ——
        // **不猜**：猜出来的目标会跳到别的地方，比不跳更糟。
        val noteId = body.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("note=") }
            ?.removePrefix("note=")
            ?.takeIf { it.isNotBlank() }
        return docId to noteId
    }
}
