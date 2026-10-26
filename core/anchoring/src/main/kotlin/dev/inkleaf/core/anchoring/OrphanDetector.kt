package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.AnchorHealth
import dev.inkleaf.core.model.AnchorResolution

/**
 * 把三态解析结果投影成 UI 需要的健康度（笔记列表角标、阅读界面提示条）。
 *
 * 刻意做成独立的纯函数（而不是给 [AnchorResolution] 加一个 val）：
 * 状态语义与展示策略应当能各自演化 —— 比如以后想区分"孤儿但源文件还在"
 * 与"孤儿且源文件已被替换"，那是展示层的分级，不该反过来改动核心状态机。
 */
object OrphanDetector {

    fun health(resolution: AnchorResolution): AnchorHealth = when (resolution) {
        // Resolved 的 init 已经强制 confidence >= CONFIDENT_THRESHOLD，
        // 所以这里不需要（也不应该）再做一次阈值判断 ——
        // 那等于把不变量检查散落到两处，早晚会不一致。
        is AnchorResolution.Resolved -> AnchorHealth.OK
        is AnchorResolution.Ambiguous -> AnchorHealth.NEEDS_CONFIRMATION
        is AnchorResolution.Orphaned -> AnchorHealth.ORPHANED
    }

    /** 是否必须在 UI 上向用户显式暴露（而不是悄悄记一条日志）。 */
    fun needsUserAction(resolution: AnchorResolution): Boolean =
        health(resolution) != AnchorHealth.OK
}
