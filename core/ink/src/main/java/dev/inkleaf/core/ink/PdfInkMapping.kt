package dev.inkleaf.core.ink

import dev.inkleaf.core.document.PdfPageFit

/**
 * 一页 PDF 在视图里的**摆放**，以及文档坐标 ↔ 视图坐标的换算。
 *
 * 这不是"又一个坐标工具"，而是 M10 那条纪律的可测试部分：
 * 页面位图与笔迹必须共用**同一个**变换。位图画在 [left]/[top] 处、按 [scale] 缩放，
 * 笔迹也按同一个 [scale] 缩放、按同一个 [left]/[top] 平移 —— 两者之间没有第二套
 * 换算，因此"笔迹与它标注的那一行错开"在数学上不可能发生。
 *
 * 坐标约定（与 [dev.inkleaf.core.document.PdfDocumentSession] 一致）：
 *   - **文档坐标**：页内坐标 + 页偏移 `pageTopPx(页号)`，单位是 PDF point
 *     （`PdfRenderer` 给的页宽高就是 point）。页与页之间的空隙也在这套坐标里。
 *   - **视图坐标**：本 View 的左上角为原点，单位是屏幕像素。
 *
 * [pageWidthPx]/[pageHeightPx] 的单位是文档单位（point），不是屏幕像素；
 * 名字里的 `Px` 沿用 `PdfPageFacts` 的命名，别把它读成"屏幕像素"。
 */
data class PdfPagePlacement(
    /** 文档单位 → 视图像素。与页面位图的渲染比例是同一个值。 */
    val scale: Float,
    /** 页面左边界在视图里的 x（居中的结果）。 */
    val left: Float,
    /** 页面上边界在视图里的 y（居中的结果）。 */
    val top: Float,
    val pageWidthPx: Float,
    val pageHeightPx: Float,
    /** 输入是否足以算出一个可画的页面。false 时调用方应当什么也不画、什么也不收。 */
    val usable: Boolean,
) {

    /** 页面在视图里的宽度（像素）。 */
    val widthInViewPx: Float get() = pageWidthPx * scale

    /** 页面在视图里的高度（像素）。 */
    val heightInViewPx: Float get() = pageHeightPx * scale

    /** 视图 x → 文档 x。 */
    fun docX(viewX: Float): Float = (viewX - left) / scale

    /** 视图 y → 文档 y。需要页偏移，因为文档 y 是"整篇叠起来"的坐标。 */
    fun docY(viewY: Float, pageTopPx: Float): Float = (viewY - top) / scale + pageTopPx

    /** 文档 x → 视图 x。 */
    fun viewX(docX: Float): Float = left + docX * scale

    /** 文档 y → 视图 y。 */
    fun viewY(docY: Float, pageTopPx: Float): Float = top + (docY - pageTopPx) * scale
}

/**
 * 求解页面在视图里的摆放。
 *
 * 规则只有一条，而且**直接复用**渲染页位图时用的 [PdfPageFit]：等比缩放到视口内、
 * 居中、不裁切。若这里另写一套"差不多"的算法，位图与笔迹就会差一点点 ——
 * 差一点点在阅读器里就是"字被划在下面那一行"。
 *
 * 退化输入（视口或页面尺寸为 0）返回 [PdfPagePlacement.usable] = false，
 * 而不是一个会除零的比例：看不见与没渲染在用户眼里是同一件事，但 NaN 会一路传到
 * 锚点链里，变成一条永远解不开的笔记。
 */
object PdfInkMapping {

    fun solve(
        viewWidthPx: Float,
        viewHeightPx: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
    ): PdfPagePlacement {
        if (viewWidthPx <= 0f || viewHeightPx <= 0f || pageWidthPx <= 0f || pageHeightPx <= 0f) {
            return PdfPagePlacement(
                scale = 1f,
                left = 0f,
                top = 0f,
                pageWidthPx = pageWidthPx.coerceAtLeast(0f),
                pageHeightPx = pageHeightPx.coerceAtLeast(0f),
                usable = false,
            )
        }
        val scale = PdfPageFit.scale(
            availableWidthPx = viewWidthPx,
            availableHeightPx = viewHeightPx,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
        )
        val width = pageWidthPx * scale
        val height = pageHeightPx * scale
        return PdfPagePlacement(
            scale = scale,
            left = (viewWidthPx - width) / 2f,
            top = (viewHeightPx - height) / 2f,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            usable = true,
        )
    }
}
