package dev.inkleaf.core.document

import android.annotation.TargetApi
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.inkleaf.core.model.Box
import java.io.File
import java.io.IOException

/**
 * 平台 `PdfRenderer` 的页源实现 —— PDF 这条路上**唯一**与系统 API 直接接触的地方。
 *
 * 为什么这么薄：M5b-1 已经把「页 → 文本层 → 行盒 → 文档坐标 → 锚点」这一整层
 * 用假页源在 JVM 上钉死了（见 `PdfDocumentSession`）。剩下这一层只做三件事：
 * 把字节写到一个**可 seek 的文件**、调 `PdfRenderer` 取页尺寸、在系统给得出文本层时
 * 把文本与行盒搬进 [PdfPageFacts]。它错了只错在数字上，不会错在坐标系上。
 *
 * ## 文本层：API 35 起才有，读不到 ≠ 扫描件
 *
 * 平台直到 Android 15（API 35）才给 `PdfRenderer.Page` 的文本 API。本工程不用
 * `androidx.pdf`，所以 `PdfBackendPolicy` 里那套 S Extensions 门槛在这里**不适用**：
 * 低版本设备上我们**看不见**文本层，但这不说明文件是扫描件。因此
 * [canReadTextLayer] 必须与「文件里有没有文本」分开表达：
 *
 *   - `canReadTextLayer = false` → 导入闸门**不判**扫描件，只提示「本机读不到文本层」；
 *   - `canReadTextLayer = true` 且每页都没有字 → 才可能是扫描件（交给 `ScannedPdfPolicy`）。
 *
 * 把这两件事混成一个「有没有文本层」的布尔值，会让一台 Android 12 手机把
 * 所有文本型 PDF 都判成扫描件 —— 用户会以为自己的文件坏了。
 *
 * ## 行盒：`getTextContents` 不给包围盒，必须走 `selectContent`
 *
 * 这是 M9 在 Android 17 模拟器上实测出来的、与文档字面不符的一条事实：
 *
 *   - `Page.getTextContents()` 返回**一个** `PdfPageTextContent`，文字是整页的
 *     （行与行之间是 `\n`），而 `getBounds()` **永远是空列表** ——
 *     AOSP 的实现就是 `new PdfPageTextContent(getPageText(pageNum))`，
 *     用的是「无包围盒」那个构造函数。按文档去读 bounds，会得到「有文字、零行盒」，
 *     于是导入闸门把每一份文本型 PDF 都判成扫描件（M9 的第一版就是这样红的）。
 *   - 行盒在 `Page.selectContent(SelectionBoundary(0), SelectionBoundary(len))`
 *     的 `selectedTextContents` 里：每行一个 `RectF`，单位是 point。
 *
 * 所以本实现先用 `getTextContents` 拿文字，再用整页选择拿行盒。两个 API 都是
 * 「长耗时、WorkerThread」，因此 `page()` 只能在 IO 调度器上调用。
 *
 * 如果 `selectContent` 失败或行数与盒数对不上，**退回「有文字、没有行盒」**：
 * 这一页仍然算有文本层（能搜索、不是扫描件），只是不能按行锚定。
 * 这条退路对应 [PdfPageFacts.textOverride]。
 *
 * ## 临时文件
 *
 * `PdfRenderer` 只吃 seekable 的 `ParcelFileDescriptor`，吃不了 `ByteArray`。
 * 页源在 `cacheDir` 里写一份临时副本，[close] 时删掉。**不**写进文档目录：
 * 这是解析中间产物，不是用户的正文副本；导入失败时也不该留下任何东西。
 */
class PlatformPdfPageSource private constructor(
    private val renderer: PdfRenderer,
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
) : PdfPageSource {

    override val pageCount: Int get() = renderer.pageCount

    /** 本机能不能读文本层。见类注释：这是**设备能力**，不是文件属性。 */
    override val canReadTextLayer: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override val textLayerEvidence: String
        get() = if (canReadTextLayer) {
            "平台 PdfRenderer：getTextContents 取文字 + selectContent 取行盒（Android 15+）；" +
                "字号/基线按包围盒估算"
        } else {
            "本机 Android ${Build.VERSION.SDK_INT} 没有 PDF 文本层 API（需要 Android 15 / API 35），只能渲染页面"
        }

    override fun page(index: Int): PdfPageFacts {
        renderer.openPage(index).use { page ->
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            val layer = if (canReadTextLayer) readTextLayer(page) else TextLayer.EMPTY
            return PdfPageFacts(
                index = index,
                widthPx = width,
                heightPx = height,
                lines = layer.lines,
                // 行盒齐全时文字由行盒拼出（两条路必须给出同一份文字）；
                // 只有「有字无盒」时才单独带上文字，见类注释的退路。
                textOverride = if (layer.lines.isEmpty() && layer.text.isNotBlank()) {
                    layer.text
                } else {
                    null
                },
            )
        }
    }

    /**
     * 取一页的文字与行盒。
     *
     * 任何一步失败都退回「没有文本层」而不是抛异常：加密页、字体子集缺失、
     * 厂商 ROM 上的半实现都可能让这两个调用炸掉，而「这一页选不中字」远好过
     * 「打不开文件」。但**不**把失败说成「这是扫描件」—— 证据里已经写明
     * 文本层来自平台 API，界面据此区分「文件没有」与「本机看不见」。
     */
    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun readTextLayer(page: PdfRenderer.Page): TextLayer {
        val text = runCatching {
            page.textContents.joinToString("\n") { it.text }
        }.getOrNull().orEmpty()
        if (text.isBlank()) return TextLayer.EMPTY

        // 整页选择：这是拿到「每行一个矩形」的唯一平台入口（见类注释）。
        val boxes = runCatching {
            page.selectContent(SelectionBoundary(0), SelectionBoundary(text.length))
                ?.selectedTextContents
                ?.flatMap { it.bounds }
                ?.map { Box(it.left, it.top, it.right, it.bottom) }
                .orEmpty()
        }.getOrElse { emptyList() }

        return TextLayer(text = text, lines = PdfTextLineMapper.map(text, boxes))
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { file.delete() }
    }

    private data class TextLayer(val text: String, val lines: List<PdfTextLine>) {
        companion object {
            val EMPTY = TextLayer("", emptyList())
        }
    }

    companion object {

        /** 临时副本目录名。放在 `cacheDir` 下，系统空间不足时可以整体清掉。 */
        private const val TEMP_DIR = "pdf-source"

        /**
         * 给 `DocumentImporter` / 阅读界面用的工厂。
         *
         * @param cacheDir 应用缓存目录（`Context.cacheDir`）。临时副本只活在一次解析期间。
         */
        fun factory(cacheDir: File): PdfPageSource.Factory = PdfPageSource.Factory { bytes ->
            open(bytes, cacheDir)
        }

        /**
         * 打开一份 PDF 字节。失败统一抛 [PdfFormatException]（`BROKEN`）：
         * 上层只需要翻译一种失败，而不是 `IOException` / `SecurityException` / 平台崩溃各写一遍。
         */
        internal fun open(bytes: ByteArray, cacheDir: File): PlatformPdfPageSource {
            val dir = File(cacheDir, TEMP_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                throw PdfFormatException(
                    PdfFormatException.Failure.BROKEN,
                    "无法创建 PDF 解析缓存目录：${dir.absolutePath}",
                )
            }
            val file = File.createTempFile("doc-", ".pdf", dir)
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                file.writeBytes(bytes)
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                if (renderer.pageCount <= 0) {
                    throw PdfFormatException(
                        PdfFormatException.Failure.BROKEN,
                        "这份 PDF 里读不到任何页面（文件可能损坏、被加密，或者根本不是 PDF）",
                    )
                }
                return PlatformPdfPageSource(renderer, file, descriptor)
            } catch (e: PdfFormatException) {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                runCatching { file.delete() }
                throw e
            } catch (e: SecurityException) {
                // 加密 PDF：平台抛 SecurityException 而不是 IOException。
                cleanupQuietly(renderer, descriptor, file)
                throw PdfFormatException(
                    PdfFormatException.Failure.BROKEN,
                    "这份 PDF 有密码保护，本版本打不开",
                    e,
                )
            } catch (e: IOException) {
                cleanupQuietly(renderer, descriptor, file)
                throw PdfFormatException(
                    PdfFormatException.Failure.BROKEN,
                    "读取 PDF 失败：${e.message ?: "文件可能已损坏"}",
                    e,
                )
            } catch (e: RuntimeException) {
                // 平台 pdfium 在畸形文件上会抛各种运行时异常；统一翻成「打不开」。
                cleanupQuietly(renderer, descriptor, file)
                throw PdfFormatException(
                    PdfFormatException.Failure.BROKEN,
                    "解析 PDF 失败：${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
        }

        private fun cleanupQuietly(
            renderer: PdfRenderer?,
            descriptor: ParcelFileDescriptor?,
            file: File,
        ) {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            runCatching { file.delete() }
        }
    }
}

/**
 * 平台文本内容 → [PdfTextLine] 的映射。
 *
 * 单独抽出来是因为它是**唯一**需要判断「这段文字与这几个矩形怎么配对」的地方，
 * 而这件事可以在 JVM 上用纯数据测（不需要 `PdfRenderer`）。
 *
 * 平台的两条通道给出的是：
 *
 *   - 文字：整页一条，行与行之间是 `\n`；
 *   - 行盒：`selectContent` 的 `selectedTextContents`，每行一个矩形。
 *
 * 因此映射规则只有一条：**行数与盒数必须相等，一一配对**。
 * 对不上时返回空列表，让调用方退回「有文字、没有行盒」——
 * 宁可这一页不能按行锚定，也不要按顺序硬配：配错之后行盒与文字永久错位，
 * 症状是「笔迹画在别的行上」，比没有行盒糟得多。
 *
 * 字号与基线是**估算**：平台只给包围盒，不给字号/基线。行高 × 0.82 是
 * 常见正文的 em 比例；基线取盒底上方 0.22 em。这两个数字只影响笔宽与
 * 笔迹的纵向基准（L2 锚定），不影响「落在哪一行」。
 */
internal object PdfTextLineMapper {

    /** 行盒高 → 字号 的经验比例。见对象注释。 */
    const val LINE_HEIGHT_TO_EM = 0.82f

    /** 基线距盒底的距离（em）。 */
    const val BASELINE_FROM_BOTTOM_EM = 0.22f

    /** 退化盒（宽或高为 0）的最小值：宁可给一个很小的行盒，也不要除零。 */
    private const val MIN_SIZE = 0.01f

    fun map(text: String, boxes: List<Box>): List<PdfTextLine> {
        val parts = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        // 有字无盒、或行数与盒数对不上：不猜，交给调用方降级（见对象注释）。
        if (parts.isEmpty() || boxes.isEmpty() || parts.size != boxes.size) return emptyList()
        return parts.mapIndexed { index, part -> lineOf(part, boxes[index]) }
    }

    private fun lineOf(text: String, box: Box): PdfTextLine {
        val left = box.left
        val top = box.top
        val right = if (box.right > box.left) box.right else box.left + MIN_SIZE
        val bottom = if (box.bottom > box.top) box.bottom else box.top + MIN_SIZE
        val fontSize = ((bottom - top) * LINE_HEIGHT_TO_EM).coerceAtLeast(MIN_SIZE)
        return PdfTextLine(
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            baselineYPx = bottom - fontSize * BASELINE_FROM_BOTTOM_EM,
            fontSizePx = fontSize,
        )
    }
}
