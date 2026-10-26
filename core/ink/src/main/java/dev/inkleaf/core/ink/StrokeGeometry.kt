package dev.inkleaf.core.ink

import android.graphics.Path
import dev.inkleaf.core.model.BrushSpec

/**
 * 一笔的**粗细**与**形状**——屏幕渲染与 PDF 导出共用的唯一实现。
 *
 * ## 为什么必须抽出来
 *
 * 这两件事本来只在一个地方：`InkStrokeRenderer` 的私有方法里。它们在屏幕上是对的。
 * 但 M14 要把同一批墨迹写进 PDF 文件，而 PDF 那边需要的恰好是同两个值：
 * 「这一笔多粗」和「这一笔是什么形状的折线」。
 *
 * 最容易走错的一步是"照着写一遍"：`Path` 的二次贝塞尔平滑、压力到笔宽的换算，
 * 抄过去后**屏幕上不会立刻出问题**，出问题的是导出的 PDF —— 而导出的 PDF
 * 在我们自己的模拟器上根本没法验证（S Ext = 0），等到用户拿 Acrobat 打开
 * 才发现"导出的笔迹比屏幕上细一档"，那时已经隔了好几个版本。
 *
 * 所以这里的取舍很明确：**宁可让渲染器多一层调用，也不让两处各有一份算法。**
 *
 * ## 单位
 *
 * 进来的坐标是**文档坐标**，笔宽也是文档单位。对重排格式，文档单位是屏幕像素；
 * 对 PDF，文档单位就是 PDF point（见 `PdfDocumentSession` 的类注释）。
 * 本文件不做任何单位换算 —— 它不知道自己被谁调用，这正是它能被两边共用的原因。
 */
object StrokeGeometry {

    /**
     * em 笔宽 → 文档单位笔宽。
     *
     * [fontSizePx] 是**这一笔所属行**的字号。为什么不用一个全局字号：笔宽以 em 存
     * （§5.7，px 笔宽在 Android 14+ 的非线性字号缩放下比例会错），而 em → px
     * 必须靠该行的字号，否则"在标题行写字、在正文行写字"会得到同一根粗细，
     * 而用户看到的是"字号一大，笔就细了"。
     *
     * 下界 0.5 是"任何字号下都能看见"：0 宽度的线在 Canvas 上什么都不画，
     * 在 PDF 里则是一个不可见的注释，两种都像是"我写了但没存上"。
     */
    fun widthOf(brush: BrushSpec, fontSizePx: Float, pressure: Float): Float {
        val scale = if (brush.pressureEnabled) {
            1f + (brush.pressureScaleMax - 1f) * pressure.coerceIn(0f, 1f)
        } else {
            1f
        }
        return (brush.widthEm * fontSizePx * scale).coerceAtLeast(0.5f)
    }

    /** 用整笔的平均压力代表这一笔的粗细（v1 的简化，理由见 `InkStrokeRenderer` 的注释）。 */
    fun meanPressure(pressures: FloatArray): Float {
        if (pressures.isEmpty()) return 1f
        var sum = 0f
        for (p in pressures) sum += p
        return (sum / pressures.size).coerceIn(0f, 1f)
    }

    /** 一笔的最终笔宽：字号 + 压力一起决定。渲染与导出都调它。 */
    fun strokeWidth(stroke: DrawableStroke): Float =
        widthOf(stroke.brush, stroke.fontSizePx, meanPressure(stroke.docPressures))

    /**
     * 折线 → [Path]。采样点之间的折角用二次贝塞尔抹圆。
     *
     * 以相邻两点的中点为曲线锚点、以采样点为控制点：一笔里只有一个点的"记忆"，
     * 却能让折角变圆。采样密时等效于直线（不会把直线画弯），采样疏时明显更顺。
     *
     * [out] 由调用方提供并会被 [Path.reset] 清空：渲染器每帧画几十笔，
     * 每笔新建一个 `Path` 会造成可见的 GC 抖动，而这段代码就在 60Hz 的路径上。
     */
    fun polyline(points: FloatArray, out: Path): Path {
        out.reset()
        val count = points.size / 2
        if (count < 2) return out
        out.moveTo(points[0], points[1])
        if (count == 2) {
            out.lineTo(points[2], points[3])
            return out
        }
        for (i in 1 until count - 1) {
            val cx = points[i * 2]
            val cy = points[i * 2 + 1]
            val mx = (cx + points[(i + 1) * 2]) / 2f
            val my = (cy + points[(i + 1) * 2 + 1]) / 2f
            out.quadTo(cx, cy, mx, my)
        }
        out.lineTo(points[(count - 1) * 2], points[(count - 1) * 2 + 1])
        return out
    }

    /**
     * 下划线 / 波浪线贴在哪、多粗。
     *
     * 返回 `(y, thickness)`，都是文档坐标。渲染器用它画线，导出用它算那条"细高亮带"——
     * 两边必须算出**同一个 y**，否则导出 PDF 里的下划线会比屏幕上偏一点，
     * 而这一点在中文行高下足以压到下一行的字上。
     */
    fun underlineOf(band: DrawableUnderline): Pair<Float, Float> {
        val thickness = (band.style.thicknessEm * band.fontSizePx).coerceAtLeast(1f)
        val y = band.baselineY + band.style.baselineOffsetEm * band.fontSizePx
        return y to thickness
    }
}
