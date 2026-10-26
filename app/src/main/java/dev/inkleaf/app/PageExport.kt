package dev.inkleaf.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.inkleaf.core.document.PdfAnnotationPlan
import dev.inkleaf.core.ink.PdfAnnotationWriter
import dev.inkleaf.core.ink.PdfWriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * 一次 PNG 导出说的"一版"到底是什么。
 *
 * 分页模式有"页"这个单位 —— 版式是固定的，翻过去就是一页。滚动模式**没有**：
 * 屏幕上是连续的文字，能导出的上限就是眼前这一屏。
 *
 * 这不是妥协，而是唯一说得通的答案：整章导出要么按整章高度建位图
 * （这本书 17001 行 ≈ 155 万像素，必然 OOM，见 `ReaderScrollState` 的类注释），
 * 要么拼一张又长又不能看的条图 —— 那种图交给 OCR 都嫌长。
 *
 * 所以滚动模式导出的是**这一屏**，并且三处都如实这么叫：菜单、文件名、结果提示。
 * 名字和作用不一致的按钮，用户要按两次才敢相信它。
 */
internal enum class PageUnit(val noun: String) {
    PAGE("这一页"),
    SCREEN("这一屏"),
    ;

    /** 写成功后状态行上那句话。 */
    val exportedMessage: String get() = "已导出${noun}的 PNG"
}

/**
 * 阅读界面的四种出口：**单页 PNG**、**分享这一页**、**全书笔记 JSON**、**PDF 原生注释**。
 *
 * ## 为什么放在一个文件里
 *
 * 三者共享同一件麻烦事：系统文件选择器（SAF）是异步的，回调里拿不到"要写什么"。
 * 所以真正的内容必须在**按下导出那一刻**就定格到 `pending` 里 ——
 * 否则用户在对话框里挑目录、改名、犹豫十秒之后，写出去的是"此刻的新状态"，
 * 而不是"他刚才看到的那一页"。这个坑在 Markdown 导出（`NotesScreen`）里已经踩过一次，
 * 三处必须写成同一套。
 *
 * 分享（第四种）不用 SAF：它不是"存到某个目录"，而是"交给某个 App"。
 * 手写检索的 L2 要的正是后者 —— 用户挑的是相册或第三方 OCR，
 * 而那些 App 里很多根本不会注册成 SAF 的文档提供者，走 SAF 就永远选不到它们。
 *
 * ## 为什么 PNG 不自己画
 *
 * 位图来自视图的 `renderToBitmap()` —— 那是与屏幕**同一段绘制代码**。
 * 在这里重新拼一次"文字 + 墨迹 + 色带"会得到一张"看起来差不多"的图，
 * 而"差不多"正是导出功能最不能接受的形容词。
 *
 * ## PDF 原生注释为什么先把计划算好
 *
 * 同理：计划（`PdfAnnotationPlan`）在按下那一刻就定下来，用户在选择器里犹豫的时候
 * 不会让写出去的东西跟着变。而且计划是**纯逻辑**，可以脱离设备测；
 * 真正碰平台 API 的只有最后一步（见 `PdfAnnotationWriter`）。
 */
internal class PageExporter(
    private val exportPng: (Bitmap, String, String) -> Unit,
    private val sharePng: (Bitmap, String, PageUnit) -> Unit,
    private val exportText: (String, String, String) -> Unit,
    private val exportAnnotatedPdf: (File, PdfAnnotationPlan, String) -> Unit,
) {
    /**
     * 导出当前一版（一页或一屏）的位图。
     *
     * @param pageLabel 写进文件名的那一段（`第3页` / `第3屏`），null 表示不写。
     * @param unit 决定写成功后那句话怎么说 —— 与 [pageLabel] 由同一个判断给出，
     *        两处各算各的就会出现"文件名写第 3 屏、提示说已导出这一页"。
     */
    fun png(
        bitmap: Bitmap?,
        title: String,
        pageLabel: String? = null,
        unit: PageUnit = PageUnit.PAGE,
    ) {
        if (bitmap == null) return
        exportPng(bitmap, pagePngFileName(title, pageLabel), unit.exportedMessage)
    }

    /** 把当前一版（一页或一屏）交给别的 App（相册 / 第三方 OCR）。 */
    fun share(
        bitmap: Bitmap?,
        title: String,
        pageLabel: String? = null,
        unit: PageUnit = PageUnit.PAGE,
    ) {
        if (bitmap == null) return
        sharePng(bitmap, pagePngFileName(title, pageLabel), unit)
    }

    /** 导出 JSON 文本。[suggestedName] 不带扩展名。 */
    fun json(text: String, title: String) {
        exportText(text, "application/json", noteJsonFileName(title))
    }

    /** 把批注作为 PDF 注释**另存**为 `书名-annotated.pdf`。原文件不会被写。 */
    fun annotatedPdf(source: File, plan: PdfAnnotationPlan, title: String) {
        exportAnnotatedPdf(source, plan, pdfAnnotatedFileName(title))
    }
}

/** 待写回的 PDF：源文件 + 已经算好的计划。两者一起定格，缺一不可。 */
private class PendingPdf(val source: File, val plan: PdfAnnotationPlan)

/**
 * 待写出的 PNG：位图 + 结果提示语。
 *
 * 两者必须一起定格。分成两个槽就会出现"按下时是分页的第 3 页、回调时界面上
 * 已经是滚动模式"，于是写出的是第 3 页的图、说的是"已导出这一屏"。
 */
private class PendingPng(val bitmap: Bitmap, val doneMessage: String)

/**
 * 注册两个 SAF 启动器，返回一个可以直接调用的出口。
 *
 * @param onResult 写入结束后回调一句给用户看的话（成功或失败）。界面把它放到状态行，
 *        不弹对话框 —— 导出是"我按了，它告诉我结果"的动作，不需要二次确认。
 */
@Composable
internal fun rememberPageExporter(onResult: (String) -> Unit): PageExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 待写入的内容。三份分开存，因为三个启动器的回调互不相干，
    // 共用一个"待写"槽会让"先点 PNG 再点 PDF"时前者写成后者。
    var pendingBitmap by remember { mutableStateOf<PendingPng?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<PendingPdf?>(null) }

    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val pending = pendingBitmap
        pendingBitmap = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writePng(context, uri, pending.bitmap) }
            }
            onResult(
                result.fold(
                    onSuccess = { pending.doneMessage },
                    onFailure = { "导出失败：${it.message ?: "无法写入所选文件"}" },
                ),
            )
        }
    }

    val textLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("系统没有给出可写的文件")
                }
            }
            onResult(
                result.fold(
                    onSuccess = { "已导出笔记 JSON" },
                    onFailure = { "导出失败：${it.message ?: "无法写入所选文件"}" },
                ),
            )
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val pending = pendingPdf
        pendingPdf = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // "rw" 而不是 "w"：写完之后要读目标文件的大小，用它给用户一个
                    // "真的写了"的凭据。描述符由 `.use` 关闭 —— 写失败时也关，
                    // 否则 SAF 那边会留下一个 0 字节的空文件。
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                        PdfAnnotationWriter.write(pending.source, fd, pending.plan)
                    } ?: PdfWriteOutcome.Failed("系统没有给出可写的文件。")
                }.getOrElse {
                    PdfWriteOutcome.Failed("打开目标文件时出错：${it.message ?: it.javaClass.simpleName}。", it)
                }
            }
            onResult(describePdfOutcome(outcome))
        }
    }

    return remember(pngLauncher, textLauncher, pdfLauncher) {
        PageExporter(
            exportPng = { bitmap, name, doneMessage ->
                pendingBitmap = PendingPng(bitmap, doneMessage)
                pngLauncher.launch(name)
            },
            sharePng = { bitmap, name, unit ->
                val message = runCatching { sharePagePng(context, bitmap, name) }
                    // 分享成功时也说清交出去的是"这一页"还是"这一屏"：
                    // 从相册回来看见两张图时，用户分不清哪张是滚动模式下截的。
                    .fold(
                        onSuccess = { "已把${unit.noun}交给别的 App" },
                        onFailure = { "分享失败：${it.message ?: "系统没有可接收的应用"}" },
                    )
                onResult(message)
            },
            exportText = { text, _, name ->
                pendingText = text
                // 启动器本身已经用 `application/json` 注册了 MIME；
                // SAF 的 CreateDocument 不接受"逐个文件换 MIME"，所以这里不再传。
                textLauncher.launch(name)
            },
            exportAnnotatedPdf = { source, plan, name ->
                pendingPdf = PendingPdf(source, plan)
                pdfLauncher.launch(name)
            },
        )
    }
}

/**
 * 写回结果 → 状态行上的一句话。
 *
 * 三种结果必须被区分：写成了（报大小与各类数量）、这台设备不支持（说清是系统扩展版本不够，
 * 重试没用）、写的时候出错（说清哪一步）。把后两者混成"导出失败"会让用户白白重试，
 * 而"重试"在不支持的那一类里永远不会成功。
 */
internal fun describePdfOutcome(outcome: PdfWriteOutcome): String = when (outcome) {
    is PdfWriteOutcome.Written -> {
        val size = if (outcome.bytes > 0) "（${formatBytes(outcome.bytes)}）" else ""
        outcome.plan.summary() + size
    }

    is PdfWriteOutcome.Unsupported -> outcome.reason
    is PdfWriteOutcome.Failed -> outcome.reason
}

/** 字节数 → 人能读的大小。只用于状态行，两位有效数字足够。 */
internal fun formatBytes(bytes: Long): String = when {
    // 显式指定 Locale.US：跟着系统语言走的话，某些区域会输出 "1,5 MB"，
    // 而状态行里的这个数字是给用户看的，不该随设备语言变成另一种小数点。
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 写 PNG。
 *
 * 压缩质量参数对 PNG 是**忽略**的（PNG 无损），传 100 只是为了让调用点看起来
 * 与 JPEG 的写法一致；真正影响体积的是位图尺寸 —— 它等于视图在屏幕上的像素尺寸，
 * 也就是"你看到多大，导出多大"。
 */
internal fun writePng(context: Context, uri: Uri, bitmap: Bitmap) {
    val out = context.contentResolver.openOutputStream(uri) ?: error("系统没有给出可写的文件")
    out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** 分享用的临时目录（在 cacheDir 下）。与 `res/xml/file_paths.xml` 里那条一一对应。 */
private const val SHARE_DIR = "shared"

/** 临时文件保留多久。超过就清掉 —— 它们是"已经交给别人的那一页"，留着没有意义。 */
private const val SHARE_KEEP_MS = 24 * 60 * 60 * 1000L

/**
 * 把一页交给别的 App（系统相册 / 第三方 OCR）。
 *
 * 这是手写检索 L2 的另一半：**导出**解决"存下来"，**分享**解决"用起来"。
 * 整页图里既有印刷正文也有手写，交给第三方 OCR 是"让专业的做专业的事" ——
 * 本应用不内置模型（见 `HandwritingOcr` 的注释）。
 *
 * @throws Exception 写不进缓存目录、或没有 App 能接收 `image/png`。
 *         调用方把它翻成一句给用户看的话，而不是静默失败。
 */
internal fun sharePagePng(context: Context, bitmap: Bitmap, fileName: String) {
    val dir = File(context.cacheDir, SHARE_DIR)
    check(dir.mkdirs() || dir.isDirectory) { "建不出临时目录" }

    // 顺手清掉过期的一页。不做的话，每分享一次就多留一张整页 PNG，
    // 而它们在 cacheDir 里既不会被用户看到、也不会被算进"清理缓存"。
    val now = System.currentTimeMillis()
    dir.listFiles()?.forEach { if (now - it.lastModified() > SHARE_KEEP_MS) it.delete() }

    val file = File(dir, fileName)
    file.outputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "这一页没能写成 PNG" }
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        // 有些接收方只看 ClipData 不看 EXTRA_STREAM（尤其是老版本的相册类 App）。
        // 两个都给，代价只是一个对象。
        clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "把这一页交给…"))
}

/** PNG 的默认文件名：`书名-第3页.png`。没有页码时（滚动模式的旧调用点）只写书名。 */
internal fun pagePngFileName(title: String, pageLabel: String?): String {
    val base = sanitizeFileName(title, "导出页")
    val suffix = pageLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
    return "$base$suffix.png"
}

/**
 * 滚动模式下的"第几屏"（1 起）。
 *
 * 滚动模式没有页号，但导出总得有个名字 —— 连续导出两张时，
 * 都叫 `书名.png` 会让第二张覆盖第一张，而这正是"我明明导了两次"的现场。
 *
 * 用滚动偏移除以视口高度：屏幕上顶着的这一屏就是第 N 屏，与手势动作对得上。
 * 用户不需要这个数字精确，他需要的是"这两张不是同一张"。
 *
 * @param offsetPx 当前滚动偏移（文档坐标像素，0 = 正文第一行贴着视口上沿）。
 * @param viewportPx 视口高度。量不出来时（0）一律算第 1 屏 —— 宁可重复，
 *        也不要让文件名里出现 `第0屏` 或除零。
 */
internal fun scrollScreenIndex(offsetPx: Int, viewportPx: Int): Int =
    if (viewportPx <= 0) 1 else offsetPx.coerceAtLeast(0) / viewportPx + 1

/** JSON 的默认文件名：`书名-笔记.json`。 */
internal fun noteJsonFileName(title: String): String = "${sanitizeFileName(title, "笔记")}-笔记.json"

/**
 * PDF 另存的文件名：`书名-annotated.pdf`。
 *
 * 命名规则（后缀加在主名后面，而不是加在扩展名后面）来自
 * [dev.inkleaf.core.document.PdfExportTarget]，**清洗**仍然走本文件的 [sanitizeFileName]
 * （与 PNG / JSON 同一套）。两件事分开是刻意的：规则要能被单测逐条钉死，
 * 清洗要在一个地方，否则"有的导出能存、有的被系统拒绝"。
 */
internal fun pdfAnnotatedFileName(title: String): String =
    dev.inkleaf.core.document.PdfExportTarget.suggestedName("${sanitizeFileName(title, "文档")}.pdf")

/**
 * 文件名清洗：去掉文件系统不能接受的字符、限长。
 *
 * 与 `exportFileName`（Markdown）同一套规则，但抽成公共函数 ——
 * 两处各写一份正则，早晚会在"要不要去点号"这种细节上分家，
 * 而分家之后的症状是"有的导出能存、有的导出被系统拒绝"。
 */
internal fun sanitizeFileName(title: String, fallback: String): String = title.trim()
    .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
    .trim('.', ' ')
    .ifEmpty { fallback }
    .take(max(1, 60))
