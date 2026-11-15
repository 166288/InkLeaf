package dev.inkleaf.core.ink

import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.component.FreeTextAnnotation
import android.graphics.pdf.component.HighlightAnnotation
import android.graphics.pdf.component.PdfPagePathObject
import android.graphics.pdf.component.StampAnnotation
import android.os.ParcelFileDescriptor
import dev.inkleaf.core.document.PdfAnnotationPlan
import dev.inkleaf.core.document.PdfAnnotationPlanner
import dev.inkleaf.core.document.PdfBackendProbe
import dev.inkleaf.core.document.PdfBandSource
import dev.inkleaf.core.document.PdfDocStroke
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.document.PdfInkSource
import dev.inkleaf.core.document.PdfMark
import dev.inkleaf.core.document.PdfNoteSource
import dev.inkleaf.core.document.PdfPageBox
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.PdfBackend
import dev.inkleaf.core.model.PdfBackendPolicy
import java.io.File
import java.io.IOException

/**
 * 把库里已有的标注**翻译成**写回计划需要的输入。
 *
 * ## 为什么从"已经解析过的可画对象"翻译，而不是重新解析锚点
 *
 * 屏幕上画出来的色带、下划线、笔迹，是 `InkResolver` / `highlightRanges` / `underlineBands`
 * 已经解析过一次的结果。写回 PDF 时如果拿着标注再解析一遍，就有两份解析结果 ——
 * 而它们一定会在某个角落分叉（比如某条高亮的锚点在第二次解析时解成了相邻的一行）。
 * 症状是"我以为标了这一行，导出来在下一行"，而且只出现在别人的阅读器里。
 *
 * 所以这里的输入是**屏幕上正在画的那些对象**，不是库里的原始记录。
 * 这与 PNG 导出必须走 `PdfInkPageView.renderToBitmap()` 是同一条纪律：
 * 用户看到的和导出的，必须是同一份几何。
 *
 * 唯一的例外是边注：它没有"可画对象"（屏幕上它显示在笔记面板里，不在页面上），
 * 所以只能从锚点链 + 行盒里取位置。取不到就跳过并说明，不猜。
 */
object PdfExportSources {

    /** 每页的坐标框。文档坐标的页偏移**只从 `PdfDocumentSession` 拿**，视图不许另算一套。 */
    fun pageBoxes(session: PdfDocumentSession): List<PdfPageBox> =
        session.pages.mapIndexed { index, page ->
            PdfPageBox(topDoc = session.pageTopPx(index), widthPt = page.widthPx, heightPt = page.heightPx)
        }

    /**
     * 高亮 → 色带矩形。
     *
     * 用 [PdfTextBands.linesOverlapping] 而不是自己按区间找行：那就是屏幕上铺色带用的
     * 同一个判据（含"半开区间"这个容易写错的边界）。
     */
    fun highlightBands(highlights: List<DrawableHighlight>, facts: LayoutFacts): List<PdfBandSource> =
        highlights.map { highlight ->
            PdfBandSource(
                annotationId = highlight.annotationId,
                rects = PdfTextBands.linesOverlapping(highlight.range, facts).map { it.rectDoc },
                colorArgb = highlight.colorArgb,
                label = "高亮",
            )
        }

    /**
     * 下划线 / 波浪线 → 贴着基线的一条**细矩形**。
     *
     * PDF 没有"下划线"这种注释（平台的注释类型只有批注、高亮、图章），所以只能写成
     * 一个高度等于线宽的 Highlight。位置与粗细来自 [StrokeGeometry.underlineOf] ——
     * 与屏幕上画那根线的是同一个函数，因此不会差一行。
     *
     * **线型会丢**：波浪线在别的阅读器里变成一条直的高亮带。这件事不能瞒着用户，
     * 由 [PdfAnnotationPlan.summary] 与界面提示如实说出来。
     */
    fun underlineBands(underlines: List<DrawableUnderline>): List<PdfBandSource> =
        underlines
            // 一条下划线按行产出多条 [DrawableUnderline]（跨行时会拆开），
            // 但它们在 PDF 里属于同一个注释 —— 按标注 id 归组，否则一个跨行的
            // 下划线会变成两个彼此独立的注释，用户删一条还剩一条。
            .groupBy { it.annotationId }
            .map { (annotationId, bands) ->
                PdfBandSource(
                    annotationId = annotationId,
                    rects = bands.map { band ->
                        val (y, thickness) = StrokeGeometry.underlineOf(band)
                        Box(
                            left = band.left,
                            top = y - thickness / 2f,
                            right = band.right,
                            bottom = y + thickness / 2f,
                        )
                    },
                    colorArgb = bands.first().colorArgb,
                    label = if (bands.first().style.wavy) "波浪线" else "下划线",
                )
            }

    /**
     * 笔迹 → 按标注归组的图章来源。
     *
     * [strokes] 是**屏幕上那一批**（`inkStrokes`，含全部页），[annotationIdOf] 给出
     * "某个 strokeId 属于哪条标注"。分组在标注这一层而不是笔迹这一层：
     * 一个 `Annotation.Ink` 里的多笔在 PDF 里应当是一个可整体选中、整体移动的图章，
     * 拆成一笔一个注释会让"我在 PDF 里删掉这段手写"变成删十次。
     */
    fun inkSources(
        strokes: List<DrawableStroke>,
        annotationIdOf: (strokeId: String) -> String?,
    ): List<PdfInkSource> {
        val grouped = LinkedHashMap<String, MutableList<PdfDocStroke>>()
        for (stroke in strokes) {
            val annotationId = annotationIdOf(stroke.strokeId) ?: continue
            grouped.getOrPut(annotationId) { ArrayList() } += PdfDocStroke(
                points = stroke.docPoints,
                widthPt = StrokeGeometry.strokeWidth(stroke),
                colorArgb = stroke.brush.colorArgb,
            )
        }
        return grouped.map { (annotationId, list) -> PdfInkSource(annotationId, list) }
    }

    /**
     * 边注 / 打字批注 → 贴着目标行的批注框。
     *
     * 位置从锚点链的**第一个结构型选择器**取：PDF 里一行 = 一块，所以 `blockPath`
     * 唯一确定一行，不需要解析整条链。这是 [LayoutFacts] 已经能给出的最小承诺，
     * 也正是 L2（行级）锚定精度的含义 —— 我们本来就没承诺"批注指向某几个字"。
     *
     * 解不到行的批注**不猜位置**：猜出来的批注框会飞到一个和内容无关的地方，
     * 用户看到的是"我的批注跑到书里别的地方去了"。
     */
    fun noteSources(
        notes: List<Annotation.MarginNote>,
        facts: LayoutFacts,
    ): List<PdfNoteSource> = notes.mapNotNull { note ->
        val structural = note.anchor.structural ?: return@mapNotNull null
        val line = facts.lines.firstOrNull { it.anchor.blockPath == structural.blockPath }
            ?: return@mapNotNull null
        val text = note.noteText?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        PdfNoteSource(
            annotationId = note.id,
            rect = line.rectDoc,
            text = text,
            colorArgb = note.colorArgb,
        )
    }
}

/** 写回的结果。三种情况必须能被界面区分：写成了、这台机器不行、写的时候出错了。 */
sealed interface PdfWriteOutcome {

    /** 写成了。[bytes] 是写出去的大小，用来在状态行里给用户一个"真的写了"的凭据。 */
    data class Written(val bytes: Long, val plan: PdfAnnotationPlan) : PdfWriteOutcome

    /**
     * 这台设备不支持。
     *
     * 它与 [Failed] 分开不是洁癖：不支持是**设备的属性**，用户无论重试多少次都不会变，
     * 界面上该说的是"你的系统扩展版本太低"，而不是"写回失败，请重试"。
     */
    data class Unsupported(val reason: String) : PdfWriteOutcome

    /** 试过了但没成。[reason] 必须说清是哪一步，不能只说"失败了"。 */
    data class Failed(val reason: String, val cause: Throwable? = null) : PdfWriteOutcome
}

/**
 * 把计划写进一个新的 PDF 文件（`PdfRendererPreV` + `addPageAnnotation` + `write`）。
 *
 * ## 默认另存，源文件永不打开写权限
 *
 * 产品决策：默认**另存**为 `原名-annotated.pdf`，不就地写回（见 [dev.inkleaf.core.document.PdfExportTarget]）。
 * 于是"一次自动备份"以最强的形式被满足 —— 源文件从头到尾只以 `MODE_READ_ONLY` 打开过。
 *
 * ## 这段代码在本机跑不到
 *
 * `addPageAnnotation` 需要 S Extensions ≥ 18（实测签名：`api-versions.xml` 里
 * `sdks="31:18,33:18,…"`）。本项目当前模拟器是 API 31 且**没有系统扩展**（读到的版本是 0），
 * 也就是说这台机器上连 `PdfRendererPreV` 本身都构造不出来。
 * 因此这里的正确性**没有被验证过** —— 这是本项目里唯一一处"写完了但没跑过"的代码，
 * 记在 `design/M14-PDF批注写回.md` 的"没验证到的"一节，不假装验过。
 *
 * 能做的都做了：几何与页归属被切到 `PdfAnnotationPlanner`（纯逻辑，JVM 上逐条钉死），
 * 门禁放在 [isSupported] 且**在任何平台类被触碰之前**，失败一律带原因返回。
 */
object PdfAnnotationWriter {

    /** 这台设备能不能写回。界面拿它决定菜单项是"能点"还是"点了给你解释"。 */
    fun isSupported(): Boolean = PdfBackendProbe.current() == PdfBackend.EDITABLE

    /**
     * 不支持时给用户的那句话（与 [PdfBackendPolicy] 的能力说明同源，不另编一套）。
     *
     * **支持时返回空串。** 不能让"能写"和"版本太低"同时成立 ——
     * 调用方目前都是先判 [isSupported] 再用它，所以这个矛盾还没在界面上出现过；
     * 但一个"只在调用顺序正确时才说得通"的 API，下一个人一定会用错。
     * 设备测试就是这样撞上的：换到 S Ext 22 的镜像之后，
     * `isSupported()` 为 true 而这段文案照旧返回，两条断言同时为真。
     */
    fun unsupportedReason(): String = if (isSupported()) {
        ""
    } else {
        "这台设备的系统 PDF 扩展版本太低" +
            "（写回批注需要 S Extensions ${PdfBackendPolicy.WRITE_BACK_MIN_EXTENSION} 及以上），" +
            "所以批注只能留在应用内。"
    }

    /**
     * 执行写回。
     *
     * [source] 是库里的原文件（只读打开）；[target] 是用户用系统选择器选定的另存目标，
     * **由调用方负责关闭** —— 这里不关，因为写失败时调用方还要用它来清理半成品。
     *
     * 整个过程在调用方的 IO 调度器上跑：`PdfRendererPreV` 与 `PdfRenderer` 一样不是线程安全的，
     * 但这里每次都是新建实例、用完即关，不存在并发共享。
     */
    fun write(source: File, target: ParcelFileDescriptor, plan: PdfAnnotationPlan): PdfWriteOutcome {
        if (!isSupported()) return PdfWriteOutcome.Unsupported(unsupportedReason())
        if (!source.isFile || !source.canRead()) {
            return PdfWriteOutcome.Failed("找不到原文件：${source.name}。")
        }
        if (plan.isEmpty) {
            return PdfWriteOutcome.Failed("没有可写回的标注，就没有生成文件。")
        }
        return runCatching { PlatformWriter.write(source, target, plan) }
            .getOrElse { PdfWriteOutcome.Failed(failureReason(it), it) }
    }

    private fun failureReason(error: Throwable): String = when (error) {
        is IOException -> "写文件时出错：${error.message ?: error.javaClass.simpleName}。"
        is SecurityException -> "没有写入这个位置的权限，换一个目录试试。"
        is UnsupportedOperationException ->
            "这台设备的系统 PDF 扩展不支持写回批注（系统版本可能在写回能力之下）。"
        // 平台在缺扩展时抛的是裸 RuntimeException（`android.jar` 的桩就是这么写的），
        // 所以这一条要按"最可能的真相"解释，但同时带上原始类型，便于排查。
        else -> "写回失败：${error.javaClass.simpleName}：${error.message ?: "没有更多信息"}。"
    }

    /**
     * 真正碰平台类的那一层。
     *
     * 单独一个对象，是为了让 `PdfRendererPreV` 与 `android.graphics.pdf.component.*`
     * 只在**门禁通过之后**才被解析/加载：这些类在缺少系统扩展的设备上可能整个不存在，
     * 而"类不存在"是 `NoClassDefFoundError`，属于 `Error` 不是 `Exception`，
     * 兜不住就会把应用带下去。
     */
    private object PlatformWriter {

        fun write(source: File, target: ParcelFileDescriptor, plan: PdfAnnotationPlan): PdfWriteOutcome {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { sourceFd ->
                PdfRendererPreV(sourceFd).use { renderer ->
                    if (renderer.pageCount != plan.pages) {
                        // 页数对不上说明写的不是同一份文件（比如同名但已被替换）。
                        // 宁可不写，也不要把注释按错的页序糊上去。
                        return PdfWriteOutcome.Failed(
                            "原文件的页数（${renderer.pageCount}）与打开时的记录（${plan.pages}）不一致，" +
                                "为免把批注写到错误的页上，这次没有写。",
                        )
                    }

                    val byPage = plan.marks.groupBy { it.pageIndex }
                    for ((pageIndex, marks) in byPage) {
                        if (pageIndex !in 0 until plan.pages) continue
                        renderer.openPage(pageIndex).use { page ->
                            for (mark in marks) {
                                val annotation = annotationOf(mark, page.width.toFloat(), page.height.toFloat())
                                    ?: return PdfWriteOutcome.Failed(
                                        "第 ${pageIndex + 1} 页上有一条批注的几何是空的，写到文件里会变成一个看不见的注释，" +
                                            "所以整次写回被取消。",
                                    )
                                page.addPageAnnotation(annotation)
                            }
                        }
                    }

                    // false = 不动原文件的密码保护。我们不掌握用户的 PDF 是否加密，
                    // 悄悄去掉保护是一个我们没被授权做的改动。
                    renderer.write(target, false)
                }
            }
            // write() 不返回写了多少，读一下目标文件的实际长度：状态行要给出"真的写了"的凭据。
            // 用 `statSize` 而不是去读 /proc/self/fd：后者依赖 Linux 的 procfs 细节，
            // 而这是给用户看的一个数字，不该成为新的失败点（读到 -1 就当不知道）。
            val bytes = target.statSize.coerceAtLeast(0L)
            return PdfWriteOutcome.Written(bytes, plan)
        }

        /** 计划里的一条注释 → 平台的注释对象。返回 null 表示几何不可用（调用方据此中止）。 */
        private fun annotationOf(
            mark: PdfMark,
            pageWidth: Float,
            pageHeight: Float,
        ): android.graphics.pdf.component.PdfAnnotation? = when (mark) {
            is PdfMark.Highlight -> {
                val rects = mark.rects
                    .map { RectF(it.left, it.top, it.right, it.bottom) }
                    .filter { it.width() > 0f && it.height() > 0f && insidePage(it, pageWidth, pageHeight) }
                if (rects.isEmpty()) {
                    null
                } else {
                    HighlightAnnotation(rects).apply {
                        // 平台不给颜色时高亮默认是黄色；不设的话"我选的青色"会变成黄色。
                        color = mark.colorArgb
                    }
                }
            }

            is PdfMark.Note -> {
                val box = RectF(mark.box.left, mark.box.top, mark.box.right, mark.box.bottom)
                if (box.width() <= 0f || box.height() <= 0f || !insidePage(box, pageWidth, pageHeight)) {
                    null
                } else {
                    FreeTextAnnotation(box, mark.text).apply {
                        // 背景取白、字取墨：别的阅读器里未必有我们的主题，白底深字是
                        // 唯一在浅色页与深色页上都读得清的组合。用户选的色只在本应用内生效。
                        backgroundColor = NOTE_BACKGROUND
                        textColor = NOTE_TEXT
                    }
                }
            }

            is PdfMark.Ink -> {
                val bounds = RectF(
                    mark.bounds.left,
                    mark.bounds.top,
                    mark.bounds.right,
                    mark.bounds.bottom,
                )
                if (bounds.width() <= 0f || bounds.height() <= 0f || !insidePage(bounds, pageWidth, pageHeight)) {
                    null
                } else {
                    val stamp = StampAnnotation(bounds)
                    val path = Path()
                    for (stroke in mark.strokes) {
                        StrokeGeometry.polyline(stroke.points, path)
                        // polyline 会 reset 传入的 Path，所以每条笔迹都要立刻转移成独立的 Path 对象：
                        // PdfPagePathObject 持有的是它，复用同一个会被后一笔覆盖。
                        val objectPath = Path(path)
                        stamp.addObject(
                            PdfPagePathObject(objectPath).apply {
                                renderMode = PdfPagePathObject.RENDER_MODE_STROKE
                                strokeColor = stroke.colorArgb
                                strokeWidth = stroke.widthPt
                            },
                        )
                    }
                    stamp
                }
            }
        }

        /**
         * 注释整体落在页内吗。
         *
         * 计划阶段已经裁过一次，这里是**第二道**：平台对越界注释的行为没有文档承诺，
         * 而我们宁可在写之前就发现（并中止整次写回），也不要生成一份"某些页上多出一个
         * 点不中的物件"的文件 —— 那种文件用户只会说"导出坏了"，而没人能复现。
         */
        private fun insidePage(rect: RectF, pageWidth: Float, pageHeight: Float): Boolean =
            rect.left >= -1f && rect.top >= -1f &&
                rect.right <= pageWidth + 1f && rect.bottom <= pageHeight + 1f

        /** 批注框的背景：略偏暖的白。与正文的纸色接近，在纸上看起来像一张便签而不是一块补丁。 */
        private const val NOTE_BACKGROUND = 0xFFFFFBF0.toInt()

        /** 批注的字色：与主题的墨色同一个值（`InkLeafTokens` 的墨色）。 */
        private const val NOTE_TEXT = 0xFF1A1C18.toInt()
    }
}
