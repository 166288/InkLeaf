package dev.inkleaf.core.model

/**
 * 锚点解析结果，只有三态（§6.3.1）。
 *
 * 关键约束：**没有第四种状态**，也不存在"低置信度但已自动接受"的中间态。
 * 原因是竞品的失败模式几乎全部来自"悄悄重锚"：
 *   - reMarkable 把 EPUB 转成 PDF 后重新排版，不告诉用户笔迹已错位；
 *   - BOOX 在插入页面后把标注整体移位而不提示；
 *   - Hypothesis 更糟，它会自动把标注挂到一段"看起来很像"的文本上。
 * 一旦允许静默重锚，用户就再也无法信任任何一条笔记 —— 这比丢一条笔记更贵。
 */
sealed interface AnchorResolution {

    data class Resolved(
        val line: LineBox,
        /** 一致性 0..1。只有 >= [CONFIDENT_THRESHOLD] 才允许构造 Resolved。 */
        val confidence: Float,
    ) : AnchorResolution {
        init {
            require(confidence >= CONFIDENT_THRESHOLD) {
                "Resolved 的置信度必须 >= $CONFIDENT_THRESHOLD（方案 §6.3.1：绝不静默重锚），实际=$confidence"
            }
        }
    }

    /**
     * 多个候选，必须由用户点选确认。
     *
     * 注意：**唯一命中 TextQuote 也属于 Ambiguous**。文本相同的段落太常见
     * （"他说"、"……"、连续的对话），唯一性并不等于正确性。
     */
    data class Ambiguous(
        /** 按相似度降序。至少一项。 */
        val candidates: List<Candidate>,
    ) : AnchorResolution {
        init {
            require(candidates.isNotEmpty()) { "Ambiguous 至少需要一个候选" }
        }

        data class Candidate(
            val line: LineBox,
            val similarity: Float,
        )
    }

    /** 无法定位。必须显式告知用户，并把视线送到 [hint] 附近，而不是假装成功。 */
    data class Orphaned(val hint: Selector.ProgressHint) : AnchorResolution

    companion object {
        /**
         * 结构路径命中 + 文本一致度达标 → 可直接接受。
         * 0.9 不是随便取的：见 §6.3.1，这条线同时是"允许自动"和"必须问用户"的分界。
         */
        const val CONFIDENT_THRESHOLD = 0.9f

        /** 低于这条线，连"候选"都不值得展示给用户（避免候选列表变成噪声）。 */
        const val USABLE_THRESHOLD = 0.6f
    }
}

/**
 * 锚点健康度 —— 给 UI 用的聚合视图（笔记列表上的红点/角标）。
 * 与 [AnchorResolution] 是投影关系，不引入新的状态语义。
 */
enum class AnchorHealth {
    /** 已解析且置信度达标。 */
    OK,

    /** 需要用户确认（Ambiguous，或 Resolved 但置信度仅勉强达标）。 */
    NEEDS_CONFIRMATION,

    /** 孤儿：需要用户手动重新挂接。 */
    ORPHANED,
}

/**
 * 笔迹相对文本行的方位。用于版式锁定模式下的边注与折页角标（§6.3.3）。
 */
enum class Side { LEFT, RIGHT, TOP, BOTTOM }
