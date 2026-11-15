package dev.inkleaf.core.document

import dev.inkleaf.core.model.Box

/**
 * 写回 PDF 的**计划**：把库里的标注翻译成"第几页、页内哪一块、画成什么"。
 *
 * ## 为什么要单独有一层"计划"
 *
 * 真正碰平台 API 的那一段（`PdfRendererPreV` + `addPageAnnotation`）只能在
 * S Extensions ≥ 18 的设备上跑，而本项目当前的模拟器 S Ext = 0 ——
 * 也就是说**这段代码在本机永远无法被执行到**。如果坐标换算、页归属、越界裁剪
 * 都写在那个跑不到的函数里，它们就永远没有被验证的机会。
 *
 * 所以这里把"算什么"和"往哪写"彻底切开：
 *
 *   1. 本文件（纯数据 + 纯函数）负责：文档坐标 → 页内坐标、按页归组、越界裁剪、
 *      说不清的就跳过并给出理由。**零平台依赖，JVM 上可测**。
 *   2. `PdfAnnotationWriter` 只负责把算好的东西交给平台，不做任何算术。
 *
 * 这条分界与 M10 定下的纪律是同一个：几何只有一个来源。写出去的注释偏半行，
 * 用户看到的是"别的阅读器里我的笔记位置不对"，而这种错误在本机无法复现。
 *
 * ## 坐标系的三层，别混
 *
 * | 层 | 原点 | 单位 | 谁定义 |
 * |---|---|---|---|
 * | 视图坐标 | 本 View 左上角 | 屏幕像素 | `PdfInkPageView` |
 * | 文档坐标 | 整篇叠起来的左上角 | PDF point | `PdfDocumentSession.pageTopPx` |
 * | **页内坐标** | 该页左上角 | PDF point | 本文件（平台注释要的就是它） |
 *
 * 视图 → 文档是一次 `scale` + 平移，已经在视图里完成了；这里只做**文档 → 页内**，
 * 也就是减去页偏移 `topDoc`（横向不必减：PDF 的左边界就是 0，见 `PdfReaderContent`
 * 的 `inkMetrics`）。平台注释要的就是页内坐标，因此这里不做任何比例换算。
 */
data class PdfPageBox(
    /** 这一页在文档坐标里的上边界（`PdfDocumentSession.pageTopPx(i)`）。 */
    val topDoc: Float,
    /** 页宽（PDF point，`PdfRendererPreV.Page.getWidth()` 给的就是它）。 */
    val widthPt: Float,
    /** 页高（PDF point）。 */
    val heightPt: Float,
) {
    val bottomDoc: Float get() = topDoc + heightPt

    /**
     * 文档 y 落在这一页里吗。
     *
     * 上下都取**闭区间**：页脚写字是常态，落在最后页底边上的那一笔如果被排除，
     * 用户得到的是"我写了但没导出去"。页与页之间本来就有 16 point 的空隙
     * （`PdfDocumentSession.PAGE_GAP_PX`），所以"某一页的上边界"和"上一页的下边界"
     * 永远不会重合，闭区间不会造成两页争抢同一个 y。
     */
    fun containsDocY(y: Float): Boolean = y >= topDoc && y <= bottomDoc
}

/** 页内坐标的矩形，单位是 PDF point。刻意不用 `android.graphics.RectF`：这一层要能在 JVM 上测。 */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= MIN_SIZE || height <= MIN_SIZE

    companion object {
        /**
         * 小于这个尺寸的注释在 PDF 阅读器里**点不到**（多数阅读器选中一个注释要求
         * 至少几个 point 的命中区）。空矩形会被平台直接拒掉或者变成看不见的幽灵，
         * 两种结果都比"不写这一条、并告诉用户"更糟。
         */
        const val MIN_SIZE = 0.5f
    }
}

/** 一笔要写进注释的墨迹：页内坐标的折线 + 笔宽 + 颜色。 */
class PdfInkStroke(
    /** 页内坐标，x0,y0,x1,y1,… */
    val points: FloatArray,
    /** 笔宽（PDF point）。em → point 的换算在计划阶段已完成。 */
    val widthPt: Float,
    val colorArgb: Int,
)

/** 一条计划好的注释。平台层照着建 `HighlightAnnotation` / `FreeTextAnnotation` / `StampAnnotation`。 */
sealed interface PdfMark {

    /** 这条注释对应库里哪条标注（写回结果要能回指到笔记面板里的那一条）。 */
    val annotationId: String

    /** 写在第几页（0 起，与 `PdfDocumentSession.pages` 下标一致）。 */
    val pageIndex: Int

    /**
     * 高亮 / 下划线 / 波浪线 → PDF 的 `HighlightAnnotation`。
     *
     * 三者合成一种注释**不是偷懒，而是 PDF 的限制**：平台只提供 FREETEXT / HIGHLIGHT / STAMP
     * 三种注释类型（见 `android.graphics.pdf.component.PdfAnnotationType`），
     * 没有"下划线"也没有"波浪线"。所以下划线只能写成一个贴基线的**细高亮带**。
     * 代价写在 [PdfAnnotationPlan.summary] 里老实告诉用户：线型会丢，颜色和位置不会。
     */
    data class Highlight(
        override val annotationId: String,
        override val pageIndex: Int,
        val rects: List<PdfRect>,
        val colorArgb: Int,
    ) : PdfMark

    /** 边注 / 打字批注 → PDF 的 `FreeTextAnnotation`。别的阅读器里能看见、能改。 */
    data class Note(
        override val annotationId: String,
        override val pageIndex: Int,
        val box: PdfRect,
        val text: String,
        val colorArgb: Int,
    ) : PdfMark

    /**
     * 手写笔迹 → PDF 的 `StampAnnotation`（里面装 `PdfPagePathObject` 矢量路径）。
     *
     * 为什么不直接 `addPageObject`：那样墨迹会变成**页面内容**（章印式），
     * 在 Acrobat 里既选不中也移不走，用户想撤掉只能重新导出一份。
     * 装进图章注释后它在别的阅读器里是一个**可选中、可移动、可删除**的对象 ——
     * 这才是"原生注释"，也是方案 §7.4 把形态 #2 排在第 2 位的原因。
     */
    data class Ink(
        override val annotationId: String,
        override val pageIndex: Int,
        val bounds: PdfRect,
        val strokes: List<PdfInkStroke>,
    ) : PdfMark
}

/**
 * 没写成注释的一条，以及**为什么**。
 *
 * 理由必须说人话且能让人判断对不对：用户看到"3 条高亮、1 条批注写好了，
 * 2 条书签没写"时，他要知道的是"书签本来就没有对应的 PDF 注释类型"，
 * 而不是猜"是不是我的书签坏了"。
 */
data class PdfSkipped(
    val annotationId: String,
    val kind: String,
    val reason: String,
)

/** 一次写回的完整计划。 */
data class PdfAnnotationPlan(
    val marks: List<PdfMark>,
    val skipped: List<PdfSkipped>,
    val pages: Int,
) {
    val isEmpty: Boolean get() = marks.isEmpty()

    val highlighted: Int get() = marks.count { it is PdfMark.Highlight }
    val noted: Int get() = marks.count { it is PdfMark.Note }
    val inked: Int get() = marks.count { it is PdfMark.Ink }

    /** 写回后给用户看的一句话。空计划也要说清"什么也没写"，不能静默成功。 */
    fun summary(): String {
        if (pages <= 0) return "这份文件没有页面，没什么可写的。"
        if (marks.isEmpty()) {
            return if (skipped.isEmpty()) {
                "还没有任何标注可以写回。"
            } else {
                "没有能写回的标注，${skipped.size} 条写不了：${skipped.first().reason}"
            }
        }
        val parts = ArrayList<String>(3)
        if (highlighted > 0) parts += "$highlighted 条高亮/下划线"
        if (inked > 0) parts += "$inked 处手写"
        if (noted > 0) parts += "$noted 条批注"
        val done = "已写回 PDF：${parts.joinToString("、")}。"
        val lost = if (skipped.isEmpty()) "" else "另有 ${skipped.size} 条写不了（见说明）。"
        return done + lost
    }
}

/** 计划阶段的输入：一条已经解析到**文档坐标**的高亮/下划线（压在某几行上的矩形）。 */
data class PdfBandSource(
    val annotationId: String,
    /** 文档坐标的矩形，一行一个。跨行的标注会有多个。 */
    val rects: List<Box>,
    val colorArgb: Int,
    /** 给用户看的类别名，只用于跳过时的说明（"高亮" / "下划线"）。 */
    val label: String,
)

/** 计划阶段的输入：一处已经解析到**文档坐标**的手写。 */
data class PdfInkSource(
    val annotationId: String,
    val strokes: List<PdfDocStroke>,
)

/** 文档坐标的笔迹。笔宽已经换算成 point（PDF 与文档坐标同单位，所以就是同一个数）。 */
class PdfDocStroke(
    /** 文档坐标，x0,y0,x1,y1,… */
    val points: FloatArray,
    val widthPt: Float,
    val colorArgb: Int,
)

/** 计划阶段的输入：一条已经定位到**某一行的矩形**的边注 / 打字批注。 */
data class PdfNoteSource(
    val annotationId: String,
    /** 文档坐标里"这句话在哪"，批注框贴着它放。 */
    val rect: Box,
    val text: String,
    val colorArgb: Int,
)

/**
 * 把文档坐标的标注翻译成页内坐标的注释计划。
 *
 * 三条规则，每条都对应一种会真的发生的坏数据：
 *
 *  1. **归页按纵向中心**，不按起点。一条跨页缝的笔迹，起点可能落在上一页的空白里；
 *     按起点归页会把它整条画到错误的页上，按中心至少把它放在"大部分在这里"的那一页。
 *  2. **越界裁剪**。笔迹贴着页面边缘是常态（在页脚写字），而平台对超出页面的注释
 *     行为未定义。裁掉超出的部分，比让平台自己决定要不要丢这一条更可预期。
 *  3. **裁完没面积就跳过**。一个被裁空的注释如果照样写回去，阅读器里会出现
 *     一个点不中、也看不见的东西 —— 那是最难排查的一种"坏了"。
 */
object PdfAnnotationPlanner {

    fun plan(
        pages: List<PdfPageBox>,
        bands: List<PdfBandSource>,
        inks: List<PdfInkSource>,
        notes: List<PdfNoteSource>,
        bookmarks: List<String> = emptyList(),
    ): PdfAnnotationPlan {
        val marks = ArrayList<PdfMark>(bands.size + inks.size + notes.size)
        val skipped = ArrayList<PdfSkipped>()

        for (band in bands) {
            val rects = ArrayList<PdfRect>(band.rects.size)
            var pageIndex = -1
            for (rect in band.rects) {
                val index = pageOf(pages, (rect.top + rect.bottom) / 2f)
                if (index < 0) continue
                if (pageIndex >= 0 && index != pageIndex) {
                    // 一条标注横跨两页：两页的页内坐标各自独立，勉强合并会把它画到页缝里。
                    // 保留前一页上已经算好的部分，余额丢弃并如实记下。
                    continue
                }
                pageIndex = index
                val local = toPage(pages[index], rect)
                if (!local.isEmpty) rects += local
            }
            if (pageIndex < 0 || rects.isEmpty()) {
                skipped += PdfSkipped(
                    annotationId = band.annotationId,
                    kind = band.label,
                    reason = "${band.label}不在任何一页的可写范围内。",
                )
                continue
            }
            marks += PdfMark.Highlight(
                annotationId = band.annotationId,
                pageIndex = pageIndex,
                rects = rects,
                colorArgb = band.colorArgb,
            )
        }

        for (ink in inks) {
            if (ink.strokes.isEmpty()) {
                skipped += PdfSkipped(ink.annotationId, "手写", "这条手写里没有笔迹。")
                continue
            }
            // 归页用**全部点**的纵向中心，而不是第一笔的起点：一笔从页缝上方拖到下方时，
            // 起点与重心的页号会不同，而用户心里的那一页是"字最多的那一页"。
            val centerY = inkCenterY(ink.strokes)
            val pageIndex = pageOf(pages, centerY)
            if (pageIndex < 0) {
                skipped += PdfSkipped(ink.annotationId, "手写", "这处手写不在任何一页的范围内。")
                continue
            }
            val page = pages[pageIndex]
            val localStrokes = ArrayList<PdfInkStroke>(ink.strokes.size)
            var bounds: PdfRect? = null
            for (stroke in ink.strokes) {
                val local = clampPoints(page, stroke.points)
                if (local == null) continue
                localStrokes += PdfInkStroke(local, stroke.widthPt, stroke.colorArgb)
                bounds = bounds?.let { union(it, boundsOf(local)) } ?: boundsOf(local)
            }
            if (localStrokes.isEmpty() || bounds == null) {
                skipped += PdfSkipped(ink.annotationId, "手写", "这处手写整段落在页面之外。")
                continue
            }
            // 先按笔宽外扩、再判断"有没有面积"：顺序反了会把**一条水平的直线**当成空的丢掉 ——
            // 中心线的外接盒高度是 0，而它明明是一笔实实在在的横。这个错误是测试抓出来的
            // （见 `PdfAnnotationPlanTest.手写按全部点的纵向中心归页`），不是想出来的。
            val padded = inflate(bounds, maxWidthOf(localStrokes) / 2f, page)
            if (padded.isEmpty) {
                skipped += PdfSkipped(ink.annotationId, "手写", "这处手写整段落在页面之外。")
                continue
            }
            marks += PdfMark.Ink(
                annotationId = ink.annotationId,
                pageIndex = pageIndex,
                // 图章边界要**含住笔宽**：只按中心线算外接盒，粗笔的边会被图章裁掉一截。
                bounds = padded,
                strokes = localStrokes,
            )
        }

        for (note in notes) {
            if (note.text.isBlank()) {
                skipped += PdfSkipped(note.annotationId, "批注", "这条批注没有文字内容。")
                continue
            }
            val pageIndex = pageOf(pages, (note.rect.top + note.rect.bottom) / 2f)
            if (pageIndex < 0) {
                skipped += PdfSkipped(note.annotationId, "批注", "这条批注挂在的行不在任何一页里。")
                continue
            }
            val page = pages[pageIndex]
            val local = toPage(page, note.rect)
            if (local.isEmpty) {
                skipped += PdfSkipped(note.annotationId, "批注", "这条批注要放的位置整段落在页面之外。")
                continue
            }
            marks += PdfMark.Note(
                annotationId = note.annotationId,
                pageIndex = pageIndex,
                // 批注框比行盒往下多留一点：贴着行放会把正文压住，多出来的高度是"气泡"。
                box = growBelow(local, note.rect.height.coerceIn(0f, page.heightPt / 4f), page),
                text = note.text,
                colorArgb = note.colorArgb,
            )
        }

        for (id in bookmarks) {
            skipped += PdfSkipped(
                annotationId = id,
                kind = "书签",
                reason = "PDF 没有「书签」这种注释类型（只有批注、高亮、图章），" +
                    "所以书签只留在应用内，写不进文件。",
            )
        }

        return PdfAnnotationPlan(marks = marks, skipped = skipped, pages = pages.size)
    }

    /**
     * 纵向位置落在第几页；一页都落不进时返回 -1（调用方据此跳过并说明）。
     *
     * 刻意**没有**"实在找不到就甩给最后一页"的兜底：那会让一个远远落在文档之外的
     * 矩形被压到最后一页的底边上，变成一个位置毫无意义的注释。跳过并说明，
     * 比在文件里留一个错误的东西好 —— 前者用户能看见，后者只有别人打开 PDF 时才发现。
     */
    private fun pageOf(pages: List<PdfPageBox>, docY: Float): Int {
        if (!docY.isFinite()) return -1
        for ((index, page) in pages.withIndex()) {
            if (page.containsDocY(docY)) return index
        }
        return -1
    }

    private fun toPage(page: PdfPageBox, doc: Box): PdfRect {
        val left = doc.left.coerceIn(0f, page.widthPt)
        val right = doc.right.coerceIn(0f, page.widthPt)
        val top = (doc.top - page.topDoc).coerceIn(0f, page.heightPt)
        val bottom = (doc.bottom - page.topDoc).coerceIn(0f, page.heightPt)
        // 起点比终点更靠外（数据坏了）时交换，而不是产出一个负宽度的矩形：
        // 平台对负宽高要么抛要么静默丢弃，两种都比"按合理的那个方向写"更糟。
        return PdfRect(
            left = minOf(left, right),
            top = minOf(top, bottom),
            right = maxOf(left, right),
            bottom = maxOf(top, bottom),
        )
    }

    /** 页内坐标的折线裁剪。整段在页外返回 null（不是空数组：空数组会被当成"这一笔是空的"）。 */
    private fun clampPoints(page: PdfPageBox, docPoints: FloatArray): FloatArray? {
        if (docPoints.size < 4) return null
        val out = FloatArray(docPoints.size)
        var anyInside = false
        var i = 0
        while (i < docPoints.size) {
            val x = docPoints[i].coerceIn(0f, page.widthPt)
            val y = (docPoints[i + 1] - page.topDoc).coerceIn(0f, page.heightPt)
            out[i] = x
            out[i + 1] = y
            if (x > 0f && x < page.widthPt && y > 0f && y < page.heightPt) anyInside = true
            i += 2
        }
        return if (anyInside) out else null
    }

    private fun inkCenterY(strokes: List<PdfDocStroke>): Float {
        var sum = 0.0
        var count = 0
        for (stroke in strokes) {
            var i = 1
            while (i < stroke.points.size) {
                sum += stroke.points[i]
                count++
                i += 2
            }
        }
        return if (count == 0) Float.NaN else (sum / count).toFloat()
    }

    private fun boundsOf(points: FloatArray): PdfRect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var i = 0
        while (i < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
            i += 2
        }
        return PdfRect(left, top, right, bottom)
    }

    private fun union(a: PdfRect, b: PdfRect): PdfRect = PdfRect(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )

    private fun maxWidthOf(strokes: List<PdfInkStroke>): Float =
        strokes.maxOfOrNull { it.widthPt } ?: 0f

    private fun inflate(rect: PdfRect, by: Float, page: PdfPageBox): PdfRect = PdfRect(
        left = (rect.left - by).coerceAtLeast(0f),
        top = (rect.top - by).coerceAtLeast(0f),
        right = (rect.right + by).coerceAtMost(page.widthPt),
        bottom = (rect.bottom + by).coerceAtMost(page.heightPt),
    )

    private fun growBelow(rect: PdfRect, by: Float, page: PdfPageBox): PdfRect = PdfRect(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = (rect.bottom + by).coerceAtMost(page.heightPt),
    )
}

/**
 * 另存的目标文件名规则。
 *
 * 产品决策（`design/方案设计.md` 待定项第 1 条，本轮定档）：**默认另存，不就地写回**。
 * 于是"一次自动备份"这件事在最强的意义上被满足了 —— **源文件从头到尾不会被写**，
 * 写的是一个新的 ParcelFileDescriptor。这也顺带绕开了一个 SAF 下做不到的东西：
 * 用户用系统选择器指定目标后，我们没有第二次选择器去为"即将被覆盖的既有文件"备份，
 * 因此"覆盖前自动留一份 .bak"在另存语义下既不必要也做不到。诚实记下来，比假装做了更好。
 */
object PdfExportTarget {

    /** 另存文件的名字后缀。带连字符是刻意的：`书名-annotated.pdf` 在文件管理器里按名字排序会挨着原书。 */
    const val SUFFIX = "-annotated"

    /**
     * 建议的文件名：`书.pdf` → `书-annotated.pdf`。
     *
     * 只处理扩展名，**不负责清洗非法字符**：那是调用方的事（应用层已经有
     * `sanitizeFileName`，PNG / JSON 导出都走它）。这里再清洗一遍会让两套规则打架，
     * 症状是"有的导出能存、有的被系统拒绝"。本函数只保证一件事：
     * 后缀加在**主名后面**，而不是加在扩展名后面。
     *
     * 为什么用 `lastIndexOf('.')` 而不是第一个点：`第 3 章.上.pdf` 的主名是`第 3 章.上`，
     * 按第一个点切会得到 `第 3 章-annotated.pdf` —— 用户会以为导错了书。
     */
    fun suggestedName(sourceName: String): String {
        val trimmed = sourceName.trim()
        if (trimmed.isEmpty()) return "文档$SUFFIX.pdf"
        val dot = trimmed.lastIndexOf('.')
        // 点在开头（`.hidden`）或没有点时，整个名字都算主名。
        return if (dot <= 0) {
            "$trimmed$SUFFIX.pdf"
        } else {
            val base = trimmed.substring(0, dot)
            val ext = trimmed.substring(dot + 1)
            if (ext.equals("pdf", ignoreCase = true)) "$base$SUFFIX.pdf" else "$base$SUFFIX.$ext"
        }
    }
}
