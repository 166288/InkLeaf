package dev.inkleaf.app

import dev.inkleaf.core.anchoring.InkAnchorFactory
import dev.inkleaf.core.common.AppLog
import dev.inkleaf.core.ink.InkSink
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.BrushSpec
import dev.inkleaf.core.model.InkRepresentation
import kotlinx.coroutines.CancellationException

/**
 * 把笔迹层接到仓储上的那一层薄胶水。
 *
 * 它刻意待在 `:app` 而不是 `:core:ink`：后者只认识 [InkSink] 接口，
 * 于是笔迹层不知道"文档存在数据库里"这件事，它的 28 个测试也不需要数据库。
 * 反过来，这一层没有任何几何/坐标逻辑 —— 它只做 DTO 转换与事务调用。
 *
 * 写成 `internal` 顶层的类而不是某个界面文件里的 private 类，是为了**可测**：
 * app 级的整链路测试（导入 → 排版 → 落笔 → 回读解析 → 撤销）要能直接用它。
 * 一个只有界面能构造的类，等于这条链路永远没有端到端测试。
 */
internal class RepositoryInkSink(
    private val container: AppContainer,
    private val docId: String,
) : InkSink {

    override suspend fun commit(
        annotationId: String,
        segment: InkAnchorFactory.Segment,
        brush: BrushSpec,
    ): Boolean = try {
        // 一段 = 一条标注。这是模型决定的：Annotation.Ink 只有一条锚点链，
        // 所以"一笔跨两行"必然是两条独立可解析的记录，而不是一条带两条链的记录。
        container.store.annotations.saveInk(
            Annotation.Ink(
                id = annotationId,
                docId = docId,
                createdAt = System.currentTimeMillis(),
                colorArgb = brush.colorArgb,
                anchor = segment.chain,
                semantics = segment.stroke.semantics,
                representation = InkRepresentation.VECTOR,
                strokeIds = listOf(segment.stroke.strokeId),
            ),
            strokes = listOf(segment.stroke),
            brushes = listOf(brush),
        )
        true
    } catch (cancelled: CancellationException) {
        // 取消不是写失败：吞掉它会让协程的取消传播失效（结构化并发的硬要求）。
        throw cancelled
    } catch (e: Exception) {
        AppLog.e("Ink", { "笔迹落盘失败：$annotationId" }, e)
        false
    }

    override suspend fun revoke(annotationIds: List<String>): Int {
        var removed = 0
        for (id in annotationIds) {
            // 逐条删除并逐条计数：仓储的 delete 是**软删除**（deleted=1）。
            // 任何一条失败都必须反映在返回值里 —— 谎报"撤销成功"会让撤销栈
            // 与实际存储错位，之后每一次撤销都撤错东西。
            val ok = try {
                container.store.annotations.delete(id)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLog.e("Ink", { "撤销笔迹失败：$id" }, e)
                false
            }
            if (ok) removed++
        }
        return removed
    }
}
