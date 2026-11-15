package dev.inkleaf.core.ink

import dev.inkleaf.core.model.InkReflowSemantics

/**
 * "笔记状态"列表里的一行。
 *
 * 为什么这行数据必须由 `:core:ink` 产出，而不是由界面自己拼：
 * **"这条笔记现在能不能画出来、原本贴在哪句话上"依赖当前排版**，
 * 只有拿到排版事实（[LayoutFacts]）的地方才算得出来。界面拿不到排版事实，
 * 也不该把判定逻辑再实现一遍 —— 两侧各判一遍正是本工程已经吃过两次亏的
 * 错误模式（见 M3c §15：两次都是"各自内部自洽，拼起来错"）。
 *
 * [excerpt] 与 [charOffset] 是给"跳到原文"用的：用户看到一条画不出来的笔记时，
 * 最需要的是"它原本贴在哪句话上"，而不是一串锚点字段。
 */
data class InkNoteRow(
    val annotationId: String,
    val state: InkState,
    val semantics: InkReflowSemantics,
    val confidence: Float,
    /** 给用户看的解释，口径与状态行一致。 */
    val reason: String,
    /** 解析到的那一行原文（过长会截断）；解不开时为 null。 */
    val excerpt: String?,
    /** "跳到原文"用的**全文**偏移；解不开时为 null。 */
    val charOffset: Int?,
    /**
     * 这条笔记**当时标在哪句话上**（存在锚点链里的文字）；老数据可能没有。
     *
     * 与 [excerpt] 的区别是"哪来的"：前者来自解析结果（现在落在哪一行），
     * 后者来自库里的锚点链（当时写在哪句话上）。解不开的笔记只有后者，
     * 而它正好是"确认位置"要用的那一条线索 —— 拿它去搜索，就能找到候选位置。
     * 刻意**不加省略号**：它会被填进搜索框当查询词。
     */
    val originalText: String? = null,
    /**
     * 这条笔记有没有墨迹被画到屏幕上（见 [InkResolution.Record.drawn]）。
     *
     * `false` 才是"画不出来"：面板与状态行都按它筛，否则会出现
     * "系统说有笔迹画不出来，用户却在屏幕上看得见"这种自相矛盾的提示。
     */
    val drawn: Boolean = false,
    /**
     * 这条笔记在库里有**笔迹行可挂**吗（见 [InkResolution.Record.hasStrokes]）。
     *
     * 它是"能不能重挂"的唯一判据：没有笔迹点就无从挂起，
     * 给一个点下去必然失败的按钮比不给按钮更糟。
     */
    val hasStrokes: Boolean = false,
) {
    /** 只有解到某一行的笔记才有可跳的位置；孤儿笔记只能解释原因。 */
    val canJump: Boolean get() = charOffset != null

    /**
     * 能不能手动把这条笔记挂到**另一行**上（"确认位置" / "改挂"）。
     *
     * 同一套动作，两种处境，**由用户来分辨**：
     *
     * - 画不出来时（[drawn] == false）它是"**确认位置**"，不做这笔笔记就一直看不见；
     * - 已经画在页面上时它是"**改挂**"，是用户主动说"这笔该记在那一行"。
     *
     * 因此判据不再是状态，而是"**库里有没有笔迹可挂**"（[hasStrokes]）。
     * M3.1 当时写的是 `state != RESOLVED && !drawn`，理由是"面板只列画不出来的笔记，
     * 给已经画好的笔记塞一个按钮会让它看起来像装饰"——那个理由依赖的是**面板当时只列问题**。
     * 面板扩成"本页笔记"之后，那个按钮对已经画出来的笔记终于有了确切含义（改挂），
     * 于是判据随之放开。两件事必须一起改：单独放开判据只会多出一堆装饰按钮。
     *
     * 界面按 [drawn] 选词（前者"确认位置"，后者"改挂"），并在两处都把
     * "固定位置的墨迹不会跟着移"这句话说清楚 —— 冻结几何（PINNED / FREE）
     * 改挂的是**指向**，不是**位置**（见 `InkReanchor`）。
     */
    val canReanchor: Boolean get() = hasStrokes
}

/**
 * 把解析结果变成"笔记状态"列表要显示的行，顺序与 [InkResolution.records] 一致。
 *
 * 结果里**包含**解开的笔记：界面按状态分组显示，不需要在这里过滤 ——
 * 过滤是显示策略，不是判定结论，混在一起会让"为什么这条没出现在列表里"
 * 变成下一个说不清的问题。
 */
fun InkResolution.rowsForDisplay(facts: LayoutFacts, maxExcerptChars: Int = 60): List<InkNoteRow> {
    val limit = maxExcerptChars.coerceAtLeast(1)
    return records.map { record ->
        val line = record.line
        InkNoteRow(
            annotationId = record.annotationId,
            state = record.state,
            semantics = record.semantics,
            confidence = record.confidence,
            reason = record.reason,
            excerpt = line?.let { excerptOf(facts.textOf(it), limit) },
            charOffset = line?.let { facts.absoluteRangeOf(it)?.first },
            originalText = record.originalText,
            drawn = record.drawn,
            hasStrokes = record.hasStrokes,
        )
    }
}

private fun excerptOf(text: String, limit: Int): String? {
    val trimmed = text.trim()
    return when {
        trimmed.isEmpty() -> null
        trimmed.length <= limit -> trimmed
        else -> trimmed.take(limit) + "…"
    }
}
