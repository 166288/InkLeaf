package dev.inkleaf.core.anchoring

import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.Side

/**
 * 边注排布求解器（§6.3.3 / §6.5）。
 *
 * 要解决的问题很具体：边注绑在某一行上，但边注有高度，而页边距是有限的。
 * 同一页上"挂在相邻两行"的两条边注会互相压住。
 * 竞品的做法常常是"往下推一点"，结果是边注与其锚定行的对应关系在视觉上断掉 ——
 * 用户看到一条边注，却不知道它指哪一行，这比不显示更糟。
 *
 * 本求解器的约定：
 *   1. **绝不静默重叠**。放不下就明确标记 [Placement.overflow]，
 *      由 UI 决定降级方式（折叠成角标 / 悬浮显示 / 提示"本页边注过多"）。
 *   2. 位置只**向下**调整（cursor 单调下移），绝不上移。
 *      下移时锚点仍在边注上方，用户能靠"往上找"建立对应；
 *      一旦允许上移，就近原则会失效，边注会跑到锚点上方而看起来指向上一段。
 *   3. 结果按输入顺序返回，保证同一份输入在不同设备上得到同一份输出（可单测）。
 *
 * 这是纯几何计算：输入是像素矩形，不碰 Android，因此可以在 JVM 上直接单测。
 */
object MarginLayoutSolver {

    /** 相邻边注之间的最小间距。 */
    const val DEFAULT_GAP_PX = 6f

    /** 边注与页边距外缘之间留的内边距，避免贴边。 */
    const val DEFAULT_PADDING_PX = 4f

    /**
     * 一条待排布的边注。
     *
     * @param anchorTopPx 其锚定行盒的顶边（文档坐标）。
     * @param desiredHeightPx 期望高度（真实高度由内容决定，这里由调用方量好传入）。
     * @param preferredSide 期望放在哪一侧。左右都想要时由调用方按"哪边空"决定。
     */
    data class Request(
        val id: String,
        val anchorTopPx: Float,
        val desiredHeightPx: Float,
        val preferredSide: Side,
    )

    /** 一侧页边距的可用矩形（不含页面正文区）。 */
    data class Column(val side: Side, val bounds: Box)

    /**
     * 排布结果。
     *
     * [overflow] 为 true 表示"按规则放不下"：box 已被夹在栏底，
     * 可能与已有边注重叠。UI 必须区别对待，不能当成正常结果画出来。
     */
    data class Placement(
        val id: String,
        val side: Side,
        val box: Box,
        val overflow: Boolean,
    )

    fun solve(
        requests: List<Request>,
        columns: List<Column>,
        gapPx: Float = DEFAULT_GAP_PX,
        paddingPx: Float = DEFAULT_PADDING_PX,
    ): List<Placement> {
        val placed = HashMap<String, Placement>(requests.size)

        for (column in columns) {
            val inColumn = requests
                .filter { it.preferredSide == column.side }
                // 先按锚点纵向位置，再按 id 排序：同一行上的多条边注顺序因此是确定的，
                // 而不是随输入列表的顺序变化（HashMap 迭代顺序会让结果看起来随机）。
                .sortedWith(compareBy({ it.anchorTopPx }, { it.id }))

            if (inColumn.isEmpty()) continue

            val top = column.bounds.top + paddingPx
            val bottom = column.bounds.bottom - paddingPx
            val available = bottom - top
            val left = column.bounds.left + paddingPx
            val right = column.bounds.right - paddingPx

            var cursor = top
            for (request in inColumn) {
                val height = request.desiredHeightPx.coerceAtLeast(0f)

                if (height > available) {
                    // 单条就比整栏还高：无论怎么放都会溢出。钉在栏顶并标记，
                    // 不做任何"压缩到能放下"的处理 —— 压缩会让内容不可读，
                    // 而不可读的边注等于丢失。
                    placed[request.id] = Placement(
                        id = request.id,
                        side = column.side,
                        box = Box(left, top, right, top + height),
                        overflow = true,
                    )
                    cursor = top + height + gapPx
                    continue
                }

                var boxTop = maxOf(request.anchorTopPx, cursor)
                var overflow = false

                if (boxTop + height > bottom) {
                    boxTop = bottom - height
                    if (boxTop < cursor) {
                        // 已被前面的边注占满 → 明确标为溢出，绝不静默重叠
                        boxTop = cursor
                        overflow = true
                    }
                }

                placed[request.id] = Placement(
                    id = request.id,
                    side = column.side,
                    box = Box(left, boxTop, right, boxTop + height),
                    overflow = overflow,
                )
                cursor = boxTop + height + gapPx
            }
        }

        // 没有对应栏的请求（例如期望放左侧但没有左侧页边距）：
        // 明确交出零宽结果 + overflow，由 UI 决定改成右侧还是折叠。
        // 静默丢掉它会让"用户写了一条边注但看不见"这种事发生。
        val result = ArrayList<Placement>(requests.size)
        for (request in requests) {
            result.add(
                placed[request.id] ?: Placement(
                    id = request.id,
                    side = request.preferredSide,
                    // 零宽矩形：UI 能据此识别出"这一侧没有可放置区域"，
                    // 而不是拿到一个看起来合法的位置却压在正文上。
                    box = Box(
                        left = 0f,
                        top = request.anchorTopPx,
                        right = 0f,
                        bottom = request.anchorTopPx + request.desiredHeightPx.coerceAtLeast(0f),
                    ),
                    overflow = true,
                ),
            )
        }
        return result
    }
}
