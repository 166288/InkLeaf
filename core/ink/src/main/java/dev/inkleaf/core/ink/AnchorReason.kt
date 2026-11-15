package dev.inkleaf.core.ink

import java.util.Locale

/**
 * 解析结论 → 给用户看的**固定说法**。
 *
 * 抽出来只为一件事：手写笔迹与打字批注（边注、高亮）必须用**同一套说法**。
 * 用户是在**同一个面板**里看到这两类笔记的，如果同一个状态有两句话
 * （一条说"找不到原来的位置"、另一条说"锚点失效"），他会以为这是两种毛病 ——
 * 而判定本来就是同一段代码（`AnchorResolver`）给出的同一个结论。
 *
 * 这里只放**与笔记类型无关**的那几句和它们的公共前缀；每种笔记自己独有的后半句
 * （"这一笔固定在页面上……"）留在各自的解析器里拼 —— 那句话讲的是墨迹，
 * 打字批注没有墨迹，硬塞进来只会让两边都说谎。
 *
 * 选 `internal` 而不是 `private`：`InkResolver` 与打字批注的解析入口是同一个模块里的
 * 两个文件，而这两句话**必须**能被同模块的测试一起钉住（改了一处忘另一处，
 * 症状是同一个状态在两处显示不同 —— 只有并排的断言才发现得了）。
 */
internal object AnchorReason {

    /** 老数据没有锚点链时的公共前缀。 */
    const val NO_CHAIN_CAUSE = "这条笔记没有锚点链（可能是旧版本数据）"

    /**
     * 孤儿（找不到原来那一行）的**第一句**，两类笔记共用。
     *
     * 它只陈述"为什么解不开"，不陈述"所以你现在看见什么" —— 后半句是墨迹才有的问题。
     */
    const val ORPHANED_CAUSE = "找不到原来的位置（文档内容或归一化口径已经变了）。"

    /** 解析成功：`Resolved` 的含义是"这一行 + 行内区间 + 引文都对上了"。 */
    fun resolved(blockPath: String, confidence: Float): String =
        "已定位到 $blockPath 这一行（一致性 ${format(confidence)}）。"

    /** 多处都可能：方案 §6.3.1 要求交给用户点选，所以这里只报告有几处。 */
    fun ambiguous(candidates: Int): String = "有 $candidates 处文本都可能，需要你确认是哪一处。"

    /** 一致性显示成两位小数：多给一位会让用户以为差别有意义。 */
    private fun format(value: Float): String =
        String.format(Locale.US, "%.2f", value)
}
