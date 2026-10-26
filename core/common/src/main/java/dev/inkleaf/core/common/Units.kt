package dev.inkleaf.core.common

/**
 * 单位换算。
 *
 * 刻意只接受原始数值（Int/Float），不接收 `Context` / `DisplayMetrics`：
 * 这样它能在 JVM 单元测试里直接跑，不需要模拟器。
 * 需要 Android 的地方（如从 Context 取 densityDpi）由调用方取值后传进来。
 *
 * 为什么毫米（mm）要出现在这里：方案 §5.6 / §8.3 的排版参数用物理单位定义
 * （页边距、最小触控区、行距），这样在不同 DPI 的设备上手写体验一致 ——
 * 用 px 定义会让同一份文档在平板上行距变窄。
 */
object Units {

    /** 1 英寸 = 25.4 毫米（国际英寸定义，精确值）。 */
    const val MM_PER_INCH: Float = 25.4f

    /** 毫米 → 像素。[densityDpi] 取自 `DisplayMetrics.densityDpi`。 */
    fun mmToPx(mm: Float, densityDpi: Int): Float = mm / MM_PER_INCH * densityDpi

    /** 像素 → 毫米。 */
    fun pxToMm(px: Float, densityDpi: Int): Float = px * MM_PER_INCH / densityDpi

    /** sp → 像素。[scaledDensity] 取自 `DisplayMetrics.scaledDensity`（已含用户字号缩放）。 */
    fun spToPx(sp: Float, scaledDensity: Float): Float = sp * scaledDensity

    /** 像素 → sp。 */
    fun pxToSp(px: Float, scaledDensity: Float): Float = if (scaledDensity <= 0f) 0f else px / scaledDensity

    /**
     * em → 像素。
     *
     * 笔迹宽度、下划线粗细、边注偏移都以 em 存储（见 BrushSpec 注释），
     * 换算时**必须**用该行自己的字号，而不是全局字号 —— 标题行与正文行的字号不同。
     */
    fun emToPx(em: Float, fontSizePx: Float): Float = em * fontSizePx

    /** 像素 → em。[fontSizePx] 为 0 时返回 0（避免产生 Inf，Inf 会污染锚点数据）。 */
    fun pxToEm(px: Float, fontSizePx: Float): Float = if (fontSizePx <= 0f) 0f else px / fontSizePx

    /** 把数值限制在 [min]..[max]。坐标换算链路里到处需要它。 */
    fun clamp(value: Float, min: Float, max: Float): Float = when {
        value < min -> min
        value > max -> max
        else -> value
    }
}
