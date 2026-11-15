package dev.inkleaf.core.ink

import kotlin.math.hypot

/**
 * 整笔擦除的命中测试：**点（或一小段拖动）落在哪一笔上**。
 *
 * ## 为什么是纯函数
 *
 * 擦除的判据（"多近算擦到"）是整个功能里唯一会被用户直接感知为"聪不聪明"的东西：
 * 容差太小，用户会觉得"明明点在线上却擦不掉"；太大，会擦掉旁边那一笔。
 * 这种参数必须能在测试里用毫米级的坐标钉死，而不是靠"在手机上试一下感觉还行"。
 * 所以它不碰 Canvas、不碰数据库、不碰触摸事件，只吃"笔迹 + 一个点"。
 *
 * ## 判据
 *
 * 点到**折线**（不是点到点）的距离 ≤ 容差。只看采样点会让"两段直线中间的那一截"
 * 成为盲区 —— 而那一截恰恰是用户最常点的地方（线段中部看起来最"实"）。
 *
 * 容差取 [tolerancePx] 与该笔自身半径的较大者：细笔（0.026em）在 40px 字号下
 * 只有 1px 宽，只给固定容差会显得"擦不掉"；粗笔（马克笔 0.14em ≈ 5.6px）
 * 又应该允许点在线边缘之外一点。
 */
object StrokeHitTest {

    /**
     * 默认容差（dp 的倍数由调用方换算）。
     *
     * 24dp 是 Material 的触摸目标半径（48dp 直径）的一半：用户点一个地方时，
     * 手指实际覆盖的就是这么大一块。比它小会让"我明明点到了"变成一句抱怨。
     */
    const val DEFAULT_TOLERANCE_DP: Float = 24f

    /**
     * 找离 ([x], [y]) 最近、且在容差内的一笔。
     *
     * 返回 null 表示"这一下没擦到任何东西"——调用方必须对此**有反馈**
     * （哪怕只是一句"这里没有笔迹"），静默失败是最难排查的一种体验。
     *
     * @param strokes 已解析、已在**文档坐标**里的笔迹。传入其它坐标系的点会得到
     *        "擦不掉"或"擦错"——而这两种现象在界面上都表现为"橡皮坏了"。
     */
    fun nearest(
        strokes: List<DrawableStroke>,
        x: Float,
        y: Float,
        tolerancePx: Float = 24f,
    ): DrawableStroke? {
        var best: DrawableStroke? = null
        var bestDistance = Float.MAX_VALUE

        for (stroke in strokes) {
            // 不可见的笔迹不参与命中：擦掉一笔"根本看不见的东西"，
            // 用户唯一的感受就是"我点了一下，某条笔记消失了"——这是最坏的一种反馈。
            if (stroke.brush.colorArgb == 0) continue

            val width = stroke.brush.widthEm * stroke.fontSizePx
            val allowed = maxOf(tolerancePx, width / 2f + 2f)
            val distance = distanceToPolyline(stroke.docPoints, x, y)
            if (distance > allowed) continue
            // 严格小于而不是 <=：等距时保留先出现的那一笔，排序稳定，
            // 同一点重复擦除的行为因此可复现（测试可以断言擦掉的是哪一条）。
            if (distance < bestDistance) {
                bestDistance = distance
                best = stroke
            }
        }
        return best
    }

    /**
     * 点到折线的最短距离（文档坐标）。
     *
     * 退化情形（一笔只有一个点、或相邻两点重合）按"点到点"处理：
     * 采样点重合在真实手写笔上很常见（压力采样比坐标采样密），
     * 让线段公式除以 0 会得到 NaN，而 NaN 参与比较的结果永远是 false ——
     * 表现是"这一笔永远擦不掉"，且没有任何异常可看。
     */
    fun distanceToPolyline(points: FloatArray, x: Float, y: Float): Float {
        val count = points.size / 2
        if (count == 0) return Float.MAX_VALUE
        if (count == 1) return hypot(x - points[0], y - points[1])

        var best = Float.MAX_VALUE
        for (i in 0 until count - 1) {
            val ax = points[i * 2]
            val ay = points[i * 2 + 1]
            val bx = points[(i + 1) * 2]
            val by = points[(i + 1) * 2 + 1]
            val d = distanceToSegment(x, y, ax, ay, bx, by)
            if (d < best) best = d
        }
        return best
    }

    /**
     * 套索有没有圈住这一笔 —— **它的任一采样点落在多边形内**就算圈住。
     *
     * ## 为什么是"点落在多边形内"而不是"包围盒相交"
     *
     * 包围盒相交最省事，但它会圈进大量**明显没被圈到**的笔迹：一页上任何一条
     * 横贯的笔迹，它的包围盒几乎横向铺满整页，套索随便画一个小圈都会与它相交。
     * 用户画了圈，看到的却是"半页都被选中了"。
     *
     * 反过来（要求**全部**点都在圈内）又太严：一笔稍微探出圈外一点点就整个落选，
     * 而"我明明把圈画在它周围了"是用户那一刻真实的想法。
     *
     * 所以取"任一采样点在内"：它与"圈住"这个词的直觉一致，
     * 也正好是套索在其它软件里的行为。
     *
     * @param polygon 闭合折线（x0,y0,x1,y1,…）。**不必**显式重复第一个点：
     *        最后一条边由代码自己补上。少于三个点时不构成多边形，一律判不中。
     */
    fun caughtByLasso(stroke: DrawableStroke, polygon: FloatArray): Boolean {
        if (stroke.brush.colorArgb == 0) return false // 看不见的东西不该被选中
        val count = polygon.size / 2
        if (count < 3) return false
        var i = 0
        while (i < stroke.docPoints.size) {
            if (containsPoint(polygon, stroke.docPoints[i], stroke.docPoints[i + 1])) return true
            i += 2
        }
        return false
    }

    /**
     * 圈中的全部笔迹。顺序与 [strokes] 一致 —— 调用方（撤销、批量写库）
     * 依赖这个顺序可复现，乱序会让"撤销之后哪一条回来了"变成不确定。
     */
    fun selectByLasso(strokes: List<DrawableStroke>, polygon: FloatArray): List<DrawableStroke> =
        strokes.filter { caughtByLasso(it, polygon) }

    /**
     * 点是否在多边形内（射线法，奇偶规则）。
     *
     * 不用"绕数"：奇偶规则对自相交的套索（画圈时手抖打了个结）给出的结果
     * 与用户在屏幕上看到的"里面的那一块"一致，而绕数规则会把结扣里的部分算成外面。
     */
    fun containsPoint(polygon: FloatArray, x: Float, y: Float): Boolean {
        val count = polygon.size / 2
        var inside = false
        var i = 0
        var j = count - 1
        while (i < count) {
            val xi = polygon[i * 2]
            val yi = polygon[i * 2 + 1]
            val xj = polygon[j * 2]
            val yj = polygon[j * 2 + 1]
            // 只统计"向上穿过水平射线"的那些边：向下穿的对称处理，
            // 于是水平边（yi == yj）一次都不计数 —— 它们本来就不该影响奇偶。
            val straddles = (yi > y) != (yj > y)
            if (straddles) {
                val at = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < at) inside = !inside
            }
            j = i
            i++
        }
        return inside
    }

    /** 点到线段的最短距离。 */
    fun distanceToSegment(
        x: Float,
        y: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-6f) return hypot(x - ax, y - ay)
        // 投影参数夹进 [0,1]：夹取这一步就是"线段"与"直线"的全部区别，
        // 少了它，延长线上的点会被判成"在线段上"——擦除会变得可以从很远的地方命中。
        val t = (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val px = ax + t * dx
        val py = ay + t * dy
        return hypot(x - px, y - py)
    }
}
