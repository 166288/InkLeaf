package dev.inkleaf.core.model

/**
 * 轴对齐矩形。
 *
 * 刻意**不**使用 `android.graphics.RectF`：方案 §4.1 规定坐标模型必须是纯 Kotlin，
 * 否则这个模块无法在 JVM 上单测，未来也无法剥离给其他平台。
 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    fun union(other: Box): Box = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun intersection(other: Box): Box {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = maxOf(l, minOf(right, other.right))
        val b = maxOf(t, minOf(bottom, other.bottom))
        return Box(l, t, r, b)
    }

    /**
     * 本矩形有多大比例落在 [other] 内。
     *
     * 这是 §6.3.3 语义判定的基础：行盒重合度 ≥ 60% → FOLLOWER；
     * 荧光笔吸附文本行的阈值是 0.85。
     */
    fun coverageRatio(other: Box): Float {
        if (area <= 0f) return 0f
        return intersection(other).area / area
    }

    fun translate(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        val ZERO = Box(0f, 0f, 0f, 0f)

        fun ofSize(left: Float, top: Float, width: Float, height: Float) =
            Box(left, top, left + width, top + height)
    }
}

/**
 * 2D 仿射变换，行主序 3×3 的 (a, b, c / d, e, f) 表示：
 *
 * ```
 * | a  b  c |     x' = a·x + b·y + c
 * | d  e  f |     y' = d·x + e·y + f
 * | 0  0  1 |
 * ```
 *
 * 同样刻意不用 `android.graphics.Matrix`（§4.1）。文档坐标与行局部坐标之间的
 * 所有换算都必须走这个类型。
 */
data class Affine2D(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    /** 复合变换：先应用 [other]，再应用本变换。 */
    fun times(other: Affine2D): Affine2D = Affine2D(
        a = a * other.a + b * other.d,
        b = a * other.b + b * other.e,
        c = a * other.c + b * other.f + c,
        d = d * other.a + e * other.d,
        e = d * other.b + e * other.e,
        f = d * other.c + e * other.f + f,
    )

    fun mapX(x: Float, y: Float): Float = a * x + b * y + c

    fun mapY(x: Float, y: Float): Float = d * x + e * y + f

    /**
     * 变换一个矩形。
     *
     * 取四个角点的包围盒（保守实现）。v1 只需要 0/90/180/270 度旋转与缩放，
     * 这个实现已经精确；任意角度下它是"外接盒"，作为碰撞/裁剪区域足够。
     */
    fun mapBox(box: Box): Box {
        val x0 = mapX(box.left, box.top)
        val y0 = mapY(box.left, box.top)
        val x1 = mapX(box.right, box.top)
        val y1 = mapY(box.right, box.top)
        val x2 = mapX(box.left, box.bottom)
        val y2 = mapY(box.left, box.bottom)
        val x3 = mapX(box.right, box.bottom)
        val y3 = mapY(box.right, box.bottom)
        return Box(
            minOf(x0, x1, x2, x3),
            minOf(y0, y1, y2, y3),
            maxOf(x0, x1, x2, x3),
            maxOf(y0, y1, y2, y3),
        )
    }

    val determinant: Float get() = a * e - b * d

    /**
     * 这个变换隐含的"em → px"比例，即 [a]。
     *
     * 存在的理由只有一个：**冻结几何**（见 `StrokeAnchor.frozenMatrix`）要恢复笔宽。
     * 笔宽以 em 存储（[BrushSpec.widthEm]，§5.7），画的时候必须乘回 px；
     * FOLLOWER 笔迹的这个比例来自"当前行盒的字号"，而冻结笔迹只能来自它自己那份变换。
     * 对 [emToDoc] 造出来的变换来说这个值就是 `fontSizePx`。
     */
    val emScale: Float get() = a

    fun isInvertible(): Boolean = kotlin.math.abs(determinant) > EPSILON

    /** 逆变换；退化矩阵返回 null 而不是抛异常（调用方负责判定为锚点异常）。 */
    fun invert(): Affine2D? {
        val det = determinant
        if (kotlin.math.abs(det) <= EPSILON) return null
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine2D(
            a = ia,
            b = ib,
            c = -(ia * c + ib * f),
            d = id,
            e = ie,
            f = -(id * c + ie * f),
        )
    }

    companion object {
        /** 线性无关判定阈值。文档坐标量级是像素，1e-6 足够宽松。 */
        const val EPSILON = 1e-6f

        val IDENTITY = Affine2D(1f, 0f, 0f, 0f, 1f, 0f)

        fun translation(tx: Float, ty: Float) = Affine2D(1f, 0f, tx, 0f, 1f, ty)

        fun scaling(sx: Float, sy: Float = sx) = Affine2D(sx, 0f, 0f, 0f, sy, 0f)

        /**
         * "行局部坐标（单位 em）→ 文档坐标"的变换。
         *
         * 这是锚定体系的核心换算（§6.3）：笔迹一律以 em 为单位存在行局部空间里，
         * 渲染时再由行盒提供 [fontSizePx] 与原点，换算成像素。
         * 这样字号变化时笔迹会自动等比跟随。
         */
        fun emToDoc(fontSizePx: Float, originLeft: Float, originTop: Float): Affine2D =
            Affine2D(fontSizePx, 0f, originLeft, 0f, fontSizePx, originTop)
    }
}
