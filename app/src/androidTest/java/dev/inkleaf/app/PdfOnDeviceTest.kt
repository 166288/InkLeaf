package dev.inkleaf.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkleaf.core.data.DocumentImporter
import dev.inkleaf.core.document.DocumentCodec
import dev.inkleaf.core.document.PdfBackendProbe
import dev.inkleaf.core.document.PdfDocumentSession
import dev.inkleaf.core.ink.InkResolver
import dev.inkleaf.core.ink.PdfAnnotationWriter
import dev.inkleaf.core.ink.PdfInkPageView
import dev.inkleaf.core.ink.PdfLayoutFacts
import dev.inkleaf.core.ink.PdfTextBands
import dev.inkleaf.core.ink.highlightRanges
import dev.inkleaf.core.ink.lineHighlightRange
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.Box
import dev.inkleaf.core.model.DocumentMeta
import dev.inkleaf.core.model.PageLayoutMetrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PDF 第一次真的出现在屏幕上（M9）。
 *
 * 这一层只测"Robolectric 测不了"的东西：
 *
 *   1. 平台 `PdfRenderer` 能不能打开我们写的 PDF、能不能渲染出可见的像素；
 *   2. 翻页之后屏幕上**内容真的变了**（不是只有页码文字变了）；
 *   3. 页源对"本机能不能读文本层"的上报，与系统的实际能力一致。
 *
 * 文本层判定、闸门策略、坐标映射已经在 JVM 上用假页源测透了
 * （`PdfImportGateTest` / `PdfTextLineMapperTest` / `PdfSessionTest`）。
 * 这里**不重复**那些断言，只回答"真机上到底成不成立"。
 */
@RunWith(AndroidJUnit4::class)
class PdfOnDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<InkLeafApplication>().container

    /** 起好的 Activity。两条路的选择见 [ActivityLauncher]（MIUI 会取消应用自己发起的启动）。 */
    private lateinit var launched: ActivityLauncher.Launched

    private val activity: MainActivity get() = launched.activity
    private lateinit var meta: DocumentMeta

    @Before
    fun openSeededPdf() {
        awaitTrue("设置加载完成", 30_000) { container.ready.value }

        val stamp = System.currentTimeMillis()
        // 第 1 页 10 行、第 2 页 2 行：深色像素数会差出几倍，
        // 于是"翻页了"可以用帧缓冲证明，而不是只信页码文字。
        meta = importPdf(
            bytes = TestPdf.textPdf(listOf(10, 2)),
            title = "PDF 样例 $stamp",
        )

        launched = ActivityLauncher.launch()
        awaitTrue("书架里出现《${meta.title}》", 30_000) {
            compose.onAllNodesWithText(meta.title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(meta.title).performClick()
        awaitTrue("PDF 阅读界面出现（第 1 / 2 页）", 30_000) {
            compose.onAllNodesWithText("第 1 / 2 页").fetchSemanticsNodes().isNotEmpty()
        }
        // 等这一页真的渲染出来（渲染在 IO 线程上）。
        awaitPagePainted(page = 1, file = "pdf-page1.png", minDark = 500)
    }

    @After
    fun closeActivity() {
        if (::launched.isInitialized) launched.close()
    }

    /** 在主线程上跑一段要碰 Activity / 视图的代码（视图树、decorView 都只能在主线程读）。 */
    private fun onMain(block: (MainActivity) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block(activity) }
    }

    @Test
    fun `PDF 的页面真的画到了屏幕上，而不是一张白纸`() {
        val shot = screenshot("pdf-page1-check.png")
        val dark = countDarkPixels(shot, top = 400, bottom = 2_600)

        assertTrue(
            "第 1 页应当有 10 行文字；页面区深色像素=$dark\n${viewTree()}",
            dark > 1_000,
        )
    }

    /**
     * 写回能力必须**如实上报**。
     *
     * ## 这条断言原本写死了"必须不支持"，本轮撞了墙
     *
     * 它当初的依据是"M14 的模拟器是 API 31、系统扩展 0"。换到 API 37 / 系统扩展 22
     * 的镜像之后，`isSupported()` 正确地返回了 true —— 而测试还在断言 false。
     * **产品是对的，测试是错的**：它把"某一台设备的事实"写成了"永远成立的事实"。
     *
     * 所以现在断的是**自洽性**而不是某个具体值：能力判断与它给出的理由必须一致。
     * 支持时不该再留着一条"写不了"的理由；不支持时理由必须带得出可核对的数字。
     * 这条在任何设备上都成立，也仍然是"判错了会走进未验证代码"的那个守卫。
     */
    @Test
    fun `写回能力如实上报：能力与理由必须自洽`() {
        val supported = PdfAnnotationWriter.isSupported()
        val reason = PdfAnnotationWriter.unsupportedReason()
        val facts = "API ${Build.VERSION.SDK_INT}、系统扩展 ${PdfBackendProbe.sExtensionVersion()}"

        if (supported) {
            assertTrue("$facts 声称能写回，却还留着一条理由：$reason", reason.isBlank())
        } else {
            assertTrue("$facts 说不支持，理由里要带得出可核对的数字：$reason", Regex("\\d+").containsMatchIn(reason))
            assertTrue("理由要说清是哪一项版本不够，否则用户无从判断：$reason", reason.contains("扩展"))
        }
    }

    /**
     * 点导出必须走**门禁**：说清为什么，而且不产生任何文件。
     *
     * 这条测试跑的是完整的计划路径（真实的 `PdfDocumentSession` 页框、真实的库、真实的标注），
     * 只是最后一步被门禁挡住。它能证明的是"标注被读出来了、书签被识别成写不了、
     * 那句话被送到了界面上" —— 这三件在写回本身跑不了的情况下仍然可以验证。
     *
     * ## 为什么不支持时才跑
     *
     * 支持写回的设备上点下去会弹系统文件选择器，这条用例就变成了另一件事。
     * 原本这里用 `assumeFalse` 跳过 —— **在 AndroidJUnitRunner 下它不跳过，它报失败**：
     * 报告里是一条 `<failure>`，构建因此红掉。换成"分支断言"：
     * 支持时改为断言"界面不再冒出写不了"，那正是 §7.4 ③ 那个缺陷的用户可见症状。
     */
    @Test
    fun `点写回 PDF：不支持时被门禁拦下并说清理由，支持时不再说写不了`() {
        val supported = PdfAnnotationWriter.isSupported()

        // 放一个书签：这台机器读不到 PDF 文本层，所以高亮/下划线都标不上，
        // 而书签不需要文本层 —— 它是这台设备上唯一能真实造出来的标注。
        compose.onNodeWithContentDescription("更多").performClick()
        awaitTrue("菜单里有「放书签」", 10_000) {
            compose.onAllNodesWithText("在这里放书签").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("在这里放书签").performClick()
        awaitTrue("书签落进库里", 20_000) {
            runBlocking {
                container.store.annotations.listByDoc(meta.id).annotations
                    .any { it is Annotation.Bookmark }
            }
        }

        compose.onNodeWithContentDescription("更多").performClick()
        awaitTrue("菜单里有「批注写回 PDF」", 10_000) {
            compose.onAllNodesWithText("批注写回 PDF", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("批注写回 PDF", substring = true).performClick()

        if (supported) {
            // 支持写回的设备上，书签**依然**写不进文件 —— PDF 没有"书签"这种注释类型，
            // 这与设备能力无关。所以这里不是"不该出现写不了"，而是"理由必须换一个"：
            // 说得出是书签的问题，就不能再把锅推给系统扩展版本。
            awaitTrue("支持写回时，界面仍要如实说清是书签写不进去", 20_000) {
                compose.onAllNodesWithText("写不了", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            val semantics = compose.onRoot().printToString()
            assertTrue(
                "说明里要指名是书签：\n$semantics",
                semantics.contains("书签"),
            )
            assertTrue(
                "本机支持写回（S Ext = ${PdfBackendProbe.sExtensionVersion()}），" +
                    "理由里却出现设备能力 —— 能力判断与文案又打架了：\n$semantics",
                !semantics.contains("系统 PDF 扩展"),
            )
            return
        }

        // 界面上必须出现"写不了"这句话，并且指出是书签。
        awaitTrue("状态行说明书签写不了（而不是静默失败）", 20_000) {
            compose.onAllNodesWithText("写不了", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val semantics = compose.onRoot().printToString()
        assertTrue(
            "说明里要指名是书签，否则用户不知道该放弃哪一类标注：\n$semantics",
            semantics.contains("书签"),
        )

        // 没有生成任何文件：不支持时连选择器都不该弹，更不该留下半成品。
        val stray = container.store.paths.documentsRoot().walkTopDown()
            .filter { it.isFile && it.name.contains("annotated") }
            .map { it.name }
            .toList()
        assertTrue("不支持时不该产生文件，实际有：$stray", stray.isEmpty())
    }

    @Test
    fun `点下一页：屏幕上的内容真的换了，进度也记住了`() {
        val before = countDarkPixels(screenshot("pdf-before-turn.png"), top = 400, bottom = 2_600)

        compose.onNodeWithContentDescription("下一页").performClick()
        awaitTrue("翻到第 2 页", 20_000) {
            compose.onAllNodesWithText("第 2 / 2 页").fetchSemanticsNodes().isNotEmpty()
        }
        awaitPagePainted(page = 2, file = "pdf-page2.png", minDark = 1, timeoutMs = 20_000)
        SystemClock.sleep(300)
        val after = countDarkPixels(screenshot("pdf-after-turn.png"), top = 400, bottom = 2_600)

        // 第 2 页只有 2 行，深色像素必须明显少于第 1 页的 10 行。
        assertTrue(
            "翻页后内容没有变：第 1 页 $before 个深色像素，第 2 页 $after 个\n${viewTree()}",
            after < before * 0.7f,
        )

        // 进度：去抖 800ms 之后库里应当记下第 2 页。
        awaitTrue("进度落库为第 2 页", 10_000) {
            runBlocking { container.store.documents.progressOf(meta.id)?.pageIndex } == 1
        }

        // 热区：页面是 Compose 的 Image，点它的左三分之一应当回到第 1 页。
        // 这条断言盯的是"手指点页面"这条路径 —— 按钮那条已经在上面走过了。
        compose.onNodeWithContentDescription("第 2 页").performTouchInput {
            click(Offset(width * 0.1f, height * 0.5f))
        }
        awaitTrue("点左三分之一回到第 1 页", 20_000) {
            compose.onAllNodesWithText("第 1 / 2 页").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 页源对能力的上报必须与系统一致。
     *
     * 这条测试在 Android 12（API 31）与 Android 15+ 上都会跑，但断言不同：
     * 前者**必须**承认自己读不到文本层，后者**必须**真的读出文本。
     * 如果哪天有人把"读不到"当成"文件是扫描件"，两种系统上会各红一条。
     */
    @Test
    fun `页源如实上报文本层能力：旧系统说读不到，新系统真的读得出`() {
        val text = importCandidateForInspect(TestPdf.textPdf(listOf(3, 3)), "能力探测.pdf")
        val scanned = importCandidateForInspect(TestPdf.imageOnlyPdf(2), "扫描件.pdf")

        if (Build.VERSION.SDK_INT >= 35) {
            val textInspection = container.importer.inspect(text)
            assertTrue("Android 15+ 必须读得出文本层", textInspection.hasTextLayer)
            assertTrue("文本型 PDF 必须有正文", textInspection.charCount > 0)
            assertEquals(2, textInspection.pageCount)

            // 文字还不够：按行锚定要的是**行盒**。平台的 getTextContents 只给文字、
            // bounds 永远是空的；行盒来自 selectContent（见 PlatformPdfPageSource 类注释）。
            // 这条断言盯的就是那条绕路 —— 它要是断了，症状是"所有 PDF 都判成扫描件"。
            val pdf = textInspection.session as dev.inkleaf.core.document.PdfDocumentSession
            val boxes = pdf.lineBoxes(
                dev.inkleaf.core.model.PageLayoutMetrics(
                    contentWidthPx = 595,
                    contentHeightPx = 842,
                    marginLeftPx = 0f,
                    marginRightPx = 0f,
                    marginTopPx = 0f,
                    marginBottomPx = 0f,
                ),
            )
            assertEquals("3 行 × 2 页：selectContent 必须给出 6 个行盒", 6, boxes.size)

            val error = runCatching { container.importer.inspect(scanned) }.exceptionOrNull()
            assertTrue(
                "扫描件应当被闸门拦住，实际=$error",
                error is DocumentImporter.ImportException &&
                    error.reason == DocumentImporter.RejectReason.SCANNED_PDF,
            )
            val forced = container.importer.inspect(scanned, allowScannedPdf = true)
            assertFalse("扫描件没有文本层", forced.hasTextLayer)
        } else {
            val textInspection = container.importer.inspect(text)
            assertFalse("API ${Build.VERSION.SDK_INT} 读不到 PDF 文本层", textInspection.hasTextLayer)
            assertTrue(
                "必须提示是本机能力问题，而不是把文件判成扫描件：${textInspection.warnings}",
                textInspection.warnings.any { it.contains("本机") && it.contains("文本层") },
            )
            // 同一台机器上，"扫描件"也必须能进来（闸门没有资格判）。
            val scannedInspection = container.importer.inspect(scanned)
            assertFalse(scannedInspection.hasTextLayer)
        }
    }

    /**
     * PDF 上落笔（M10）：一笔必须**按行**落到文本层上，撤销要能把它收回去。
     *
     * 两台模拟器上断言不同，因为能力不同：
     *
     *   - API < 35：读不到文本层 → 没有行盒 → 「书写」必须禁用（整份只读）；
     *   - API ≥ 35：真的注入一次滑动 → 库里出现 `Annotation.Ink` → 用阅读界面
     *     **同一个** `InkResolver` 解回第一页的那一行 → 撤销后库里没有、屏幕上也没有。
     *
     * 最后一条断言（截屏）盯的是合并画布：库里有一条笔记，不等于用户看得见它。
     * 位图与笔迹分处两个 View 时，这条断言会红，而库里的断言照样绿。
     */
    @Test
    fun `PDF 上手写：一笔落在文本层那一行上，撤销能收回去`() {
        if (Build.VERSION.SDK_INT < 35) {
            // 没有文本层就没有行盒；此时"允许写"等于允许用户写一条永远解不开的笔记。
            compose.onNodeWithContentDescription("书写").assertIsNotEnabled()
            return
        }

        val session = pdfSession()
        val facts = PdfLayoutFacts(session, inkMetricsOf(session), docRatio = 0f)
        val line = facts.lines.firstOrNull()
        assertNotNull("文本层必须至少给出一行，才有可锚定的位置", line)
        val box = line!!.rectDoc

        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("进入书写模式", 10_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }

        val view = awaitPdfInkView()
        // 落在行盒**底部**（仍在盒内：`y < rect.bottom`）：那里在基线以下，
        // 大半个带宽是空白，笔迹带来的深色像素才数得出来。画在行中央会与字形重叠，
        // "墨迹真的画上去了"在像素上只剩几十个采样点。
        val strokeY = box.bottom - 2f
        // 直接派发给原生 View：Compose 的 AndroidView 不把窗口事件转给子 View（M7 记录 §5）。
        val from = docToViewOnUi(view, box.left + 20f, strokeY)
        val to = docToViewOnUi(view, box.left + 160f, strokeY)

        // 只数**这一笔会经过的那一条带**：全页深色像素里文字占绝大多数，
        // 一笔画在字上只多出几十个采样点，阈值再低也说明不了"墨迹真的画在这里"。
        // 带的位置由 `docToView` 算出 —— 它错了，这条断言就会红。
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        val bandLeft = (origin[0] + minOf(from.first, to.first) - 20f).toInt().coerceAtLeast(0)
        val bandRight = (origin[0] + maxOf(from.first, to.first) + 20f).toInt()
        val bandTop = (origin[1] + from.second - 20f).toInt().coerceAtLeast(0)
        val bandBottom = (origin[1] + from.second + 20f).toInt()
        val before = countDarkPixelsIn(
            screenshot("pdf-ink-before.png"),
            left = bandLeft,
            top = bandTop,
            right = bandRight,
            bottom = bandBottom,
        )
        compose.runOnUiThread {
            dispatchSwipe(view, from.first, from.second, to.first, to.second)
        }

        awaitTrue("笔迹落库", 20_000) {
            inks().isNotEmpty()
        }

        // 用与阅读界面同一个解析器解回：这一笔必须挂在第一页的那一行上。
        // 先断言解析，再等界面 —— 解析为空时界面**不会**重组（状态值没变），
        // 那种失败看起来会像"撤销按钮不亮"，但根因在锚点。
        val snapshot = runBlocking { container.store.annotations.listByDoc(meta.id) }
        val resolution = InkResolver.resolve(
            inks = snapshot.annotations.filterIsInstance<Annotation.Ink>(),
            strokesOf = snapshot::strokesOf,
            brushesOf = snapshot::brushesOf,
            facts = facts,
        )
        assertEquals(
            "解析失败：records=${resolution.records.map { "${it.state} ${it.reason}" }}",
            1,
            resolution.drawable.size,
        )

        // 落库 ≠ 已经进入撤销栈、也 ≠ 已经重绘。等"撤销上一笔"真的可用，
        // 它同时证明控制器收到了这一笔，重组也已经发生。
        awaitTrue("撤销按钮可用（语义树：${compose.onRoot().printToString(maxDepth = 12)}）", 20_000) {
            runCatching {
                compose.onNodeWithContentDescription("撤销上一笔").assertIsEnabled()
            }.isSuccess
        }

        // `submitInk` 只是 `invalidate()`：**下一帧**才画出来。设备测试没有持续刷新的
        // vsync 帧，截图很容易停在上一帧上 —— 这一条在 Gradle 驱动的整包跑里红了两次
        // （一次报"319 → 319"，一次干脆是一条空消息），而单跑/手工整包都是绿的。
        // 与"点一行标高亮"那条用例同一个理由、同一个解法：重试到画面真的变了。
        fun measureUntil(name: String, ready: (Int) -> Boolean): Int {
            var last = -1
            for (attempt in 0 until 10) {
                compose.waitForIdle()
                SystemClock.sleep(if (attempt == 0) 200 else 400)
                last = countDarkPixelsIn(
                    screenshot(name),
                    left = bandLeft,
                    top = bandTop,
                    right = bandRight,
                    bottom = bandBottom,
                )
                if (ready(last)) return last
            }
            return last
        }

        val after = measureUntil("pdf-ink-after.png") { it > before + 100 }
        assertTrue(
            "画了笔迹之后这一条带上的深色像素应当明显增加：$before → $after" +
                "（带 = $bandLeft,$bandTop..$bandRight,$bandBottom）",
            after > before + 100,
        )
        val drawn = resolution.drawable.single()
        val ys = (0 until drawn.docPoints.size / 2).map { drawn.docPoints[it * 2 + 1] }
        assertTrue(
            "笔迹必须落在第 1 页的文档坐标里（y=${ys.minOrNull()}..${ys.maxOrNull()}，" +
                "第 2 页从 ${session.pageTopPx(1)} 开始）",
            ys.all { it >= 0f && it < session.pageTopPx(1) },
        )
        assertNotNull(
            "按行锚定的笔记必须有锚点链",
            snapshot.annotations.filterIsInstance<Annotation.Ink>().single().anchor,
        )

        compose.onNodeWithContentDescription("撤销上一笔").performClick()
        awaitTrue("撤销后库里没有笔迹", 20_000) {
            inks().isEmpty()
        }
        val undone = measureUntil("pdf-ink-undone.png") { it < after - 50 }
        assertTrue("撤销后墨迹必须从屏幕上消失：$after → $undone", undone < after - 50)
    }

    /**
     * PDF 上按行标高亮（M11）。
     *
     * 两台模拟器上断言不同：API < 35 没有文本层，「高亮」必须禁用（与「书写」同一条规则）；
     * API ≥ 35 上点一行 → 库里出现一条 `Annotation.Highlight` → 用阅读界面**同一个**
     * `highlightRanges` 解回那一行 → 屏幕上真的出现黄色色带。
     *
     * 最后一条断言盯的是"库里有"与"用户看得见"的差别：PDF 的文字在位图里，
     * 色带必须由 [dev.inkleaf.core.ink.PdfInkPageView] 用与文字同一个变换画上去。
     * 如果哪天有人把色带画到了位图下面（或忘了提交 bands），库里的断言照样绿。
     */
    @Test
    fun `PDF 上点一行就能标高亮：库里按行锚定，屏幕上真的有色带`() {
        if (Build.VERSION.SDK_INT < 35) {
            compose.onNodeWithContentDescription("高亮").assertIsNotEnabled()
            return
        }

        val session = pdfSession()
        val facts = PdfLayoutFacts(session, inkMetricsOf(session), docRatio = 0f)
        val line = facts.lines.firstOrNull()
        assertNotNull("文本层必须至少给出一行，才有可标的位置", line)
        val box = line!!.rectDoc
        val expected = facts.lineHighlightRange(line)!!

        compose.onNodeWithContentDescription("高亮").performClick()
        awaitTrue("进入高亮模式", 10_000) {
            compose.onAllNodesWithContentDescription("退出高亮").fetchSemanticsNodes().isNotEmpty()
        }
        SystemClock.sleep(200)

        val view = awaitPdfInkView()
        val rect = screenRectOf(view, box)
        val before = countYellowPixelsIn(
            screenshot("pdf-highlight-before.png"),
            rect[0],
            rect[1],
            rect[2],
            rect[3],
        )

        // 点在行盒中央：这一行一定有字，横向也一定在页内。
        val point = docToViewOnUi(view, box.left + 40f, (box.top + box.bottom) / 2f)
        compose.runOnUiThread { dispatchTap(view, point.first, point.second) }

        awaitTrue("高亮落库", 20_000) { highlights().isNotEmpty() }

        val snapshot = runBlocking { container.store.annotations.listByDoc(meta.id) }
        val annotation = snapshot.annotations.filterIsInstance<Annotation.Highlight>().single()
        assertEquals(
            "页键要进兜底提示：PDF 没有可重排的字符偏移，页号是最后的定位依据",
            "p1",
            annotation.anchor.progressHint.pageKey,
        )

        // 用阅读界面同一个解析出口解回：这条高亮必须落在刚才点的那一行上。
        val resolved = highlightRanges(listOf(annotation), facts).single()
        assertEquals("按行锚定：区间就是这一行", expected, resolved.range)

        val band = PdfTextBands.ofHighlights(listOf(resolved), facts).single()
        assertEquals("色带矩形就是这一行的行盒", box, band.rect)

        var after = 0
        for (attempt in 0 until 10) {
            // 高亮是在 `LaunchedEffect` 的续体里写进 Compose 状态的（先 `withContext` 解析，
            // 再回主线程赋值）。设备测试没有持续刷新的 vsync 帧，所以要显式让 Compose
            // 跑完这一轮重组 —— 否则截图里的页面还停在"色带尚未提交"的那一帧。
            compose.waitForIdle()
            SystemClock.sleep(if (attempt == 0) 200 else 400)
            after = countYellowPixelsIn(
                screenshot("pdf-highlight-after.png"),
                rect[0],
                rect[1],
                rect[2],
                rect[3],
            )
            if (after > before + 500) break
        }
        assertTrue(
            "标上高亮后这一行上应当出现黄色色带：$before → $after" +
                "（区域 = ${rect[0]},${rect[1]}..${rect[2]},${rect[3]}）\n${viewTree()}",
            after > before + 500,
        )
    }

    /**
     * PDF 全文搜索（M11）：命中第 2 页的词 → 跳到第 2 页 → 色带只铺在**命中那一行**。
     *
     * 为什么拿这条断言证明"按行"：第 2 页有两行，两行都含 "Page 2"，但搜索命中的是
     * 第一行里的那 6 个字符。色带按行铺（L2），所以只有第一行该有蓝色；第二行一处都没有。
     * 如果谁把"命中"渲染成整页、或者把偏移映射错了行，这个比值会立刻塌掉。
     *
     * 截图是在搜索面板**还开着**的时候取的：面板的遮罩会把整页压暗，所以判据不是
     * 绝对颜色，而是同一次截图里"命中行 vs 下一行"的蓝色像素比 —— 遮罩对两行是同一层。
     */
    @Test
    fun `PDF 搜索：命中第 2 页的词就跳到第 2 页，色带只铺在命中那一行`() {
        if (Build.VERSION.SDK_INT < 35) {
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("全文搜索").assertIsNotEnabled()
            return
        }

        val session = pdfSession()
        val facts = PdfLayoutFacts(session, inkMetricsOf(session), docRatio = 0f)
        val pageTwoLines = facts.lines.filter { it.rectDoc.top >= session.pageTopPx(1) }
        assertEquals("测试前提：第 2 页有两行", 2, pageTwoLines.size)

        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("全文搜索").performClick()
        awaitTrue("搜索框出现", 10_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performTextInput("Page 2")

        awaitTrue("命中后跳到第 2 页", 20_000) {
            compose.onAllNodesWithText("第 2 / 2 页").fetchSemanticsNodes().isNotEmpty()
        }
        awaitPagePainted(page = 2, file = "pdf-search-page2.png", minDark = 1)

        val view = awaitPdfInkView()
        val hitRect = screenRectOf(view, pageTwoLines[0].rectDoc)
        val otherRect = screenRectOf(view, pageTwoLines[1].rectDoc)

        var hitBlue = 0
        var otherBlue = 0
        for (attempt in 0 until 10) {
            compose.waitForIdle()
            SystemClock.sleep(if (attempt == 0) 300 else 400)
            val shot = screenshot("pdf-search-band.png")
            hitBlue = countBluePixelsIn(shot, hitRect[0], hitRect[1], hitRect[2], hitRect[3])
            otherBlue = countBluePixelsIn(shot, otherRect[0], otherRect[1], otherRect[2], otherRect[3])
            if (hitBlue > 200 && hitBlue > otherBlue * 3 + 50) break
        }
        assertTrue(
            "命中行应当有蓝色色带、下一行不该有：命中行=$hitBlue，下一行=$otherBlue\n${viewTree()}",
            hitBlue > 200 && hitBlue > otherBlue * 3 + 50,
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun inks(): List<Annotation.Ink> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.filterIsInstance<Annotation.Ink>()
    }

    private fun highlights(): List<Annotation.Highlight> = runBlocking {
        container.store.annotations.listByDoc(meta.id).annotations.filterIsInstance<Annotation.Highlight>()
    }

    /** 用与阅读界面**同一个入口**重建会话：不能另建一套"只看页尺寸"的假事实。 */
    private fun pdfSession(): PdfDocumentSession = runBlocking {
        val build = DocumentCodec.sessionFromBytes(
            docId = meta.id,
            kind = meta.kind,
            bytes = container.store.documents.readBytes(meta),
            forcedCharset = meta.encoding,
            pdf = container.pdfPageSource,
        )
        build.session as PdfDocumentSession
    }

    private fun inkMetricsOf(session: PdfDocumentSession) = PageLayoutMetrics(
        contentWidthPx = session.maxPageWidthPx.toInt(),
        contentHeightPx = session.maxPageHeightPx.toInt(),
        marginLeftPx = 0f,
        marginRightPx = 0f,
        marginTopPx = 0f,
        marginBottomPx = 0f,
    )

    private fun awaitPdfInkView(timeoutMs: Long = 30_000): PdfInkPageView {
        var found: PdfInkPageView? = null
        awaitTrue("视图树里出现有大小的 PdfInkPageView", timeoutMs) {
            var view: PdfInkPageView? = null
            onMain { view = findPdfInkView(it.window.decorView) }
            found = view
            view != null && view!!.width > 0 && view!!.height > 0
        }
        @Suppress("UNCHECKED_CAST")
        return found as PdfInkPageView
    }

    private fun findPdfInkView(root: View?): PdfInkPageView? = when {
        root == null -> null
        root is PdfInkPageView -> root
        root is ViewGroup ->
            (0 until root.childCount).firstNotNullOfOrNull { findPdfInkView(root.getChildAt(it)) }

        else -> null
    }

    /** 文档坐标 → View 坐标（必须在 UI 线程读：它读的是 `onSizeChanged` 算出来的摆放）。 */
    private fun docToViewOnUi(view: PdfInkPageView, docX: Float, docY: Float): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        compose.runOnUiThread {
            val point = view.docToView(docX, docY)
            x = point.x
            y = point.y
        }
        return x to y
    }

    private fun dispatchSwipe(
        view: View,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 24,
        stepMs: Long = 8,
    ) {
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(makeTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY))
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            view.dispatchTouchEvent(
                makeTouchEvent(
                    downTime,
                    downTime + i * stepMs,
                    MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * f,
                    fromY + (toY - fromY) * f,
                ),
            )
        }
        view.dispatchTouchEvent(
            makeTouchEvent(downTime, downTime + (steps + 1) * stepMs, MotionEvent.ACTION_UP, toX, toY),
        )
    }

    /**
     * `toolType` 显式写成手指：注入事件的默认 toolType 不是 FINGER，
     * 而 `PdfInkPageView` 会按 toolType 决定要不要走无缓冲派发（M7 同一条经验）。
     */
    private fun makeTouchEvent(
        downTime: Long,
        at: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime,
            at,
            action,
            1,
            arrayOf(props),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    /**
     * 一次干净的点击：按下后立刻抬起，位移为 0。
     *
     * 高亮模式只认"几乎没动"的点击（拖动是翻页手势），所以这里不能复用 swipe。
     */
    private fun dispatchTap(view: View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(makeTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(makeTouchEvent(downTime, downTime + 30, MotionEvent.ACTION_UP, x, y))
    }

    /** 文档坐标的矩形 → 屏幕像素矩形（截屏是整屏坐标）。 */
    private fun screenRectOf(view: PdfInkPageView, box: Box): IntArray {
        val a = docToViewOnUi(view, box.left, box.top)
        val b = docToViewOnUi(view, box.right, box.bottom)
        val origin = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(origin) }
        return intArrayOf(
            (origin[0] + minOf(a.first, b.first)).toInt().coerceAtLeast(0),
            (origin[1] + minOf(a.second, b.second)).toInt().coerceAtLeast(0),
            (origin[0] + maxOf(a.first, b.first)).toInt(),
            (origin[1] + maxOf(a.second, b.second)).toInt(),
        )
    }

    /**
     * 数"高亮黄"像素：琥珀色 `0x66FFD54F` 叠在白马纸上得到 ≈(255,238,185)。
     *
     * 判据用 `r-b` 而不是精确色值：正片叠底/半透明覆盖两种画法在白底上结果相同，
     * 而纸张有轻微偏色时也不会误判。
     */
    private fun countYellowPixelsIn(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        var y = top.coerceAtLeast(0)
        while (y < minOf(bottom, bitmap.height)) {
            var x = left.coerceAtLeast(0)
            while (x < minOf(right, bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                if (r >= 225 && g >= 205 && b <= 220 && r - b >= 30) count++
                x += 2
            }
            y += 2
        }
        return count
    }

    /**
     * 数"搜索蓝"像素：浅蓝 `0x6656CCF2` 叠在白底上得到 ≈(187,235,250)。
     *
     * 搜索面板的遮罩会把整页压暗，所以这里只看"蓝多于红"（白/灰的 b-r≈0），
     * 不设绝对亮度下限 —— 遮罩对命中行与相邻行是同一层，比较才有意义。
     */
    private fun countBluePixelsIn(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        var y = top.coerceAtLeast(0)
        while (y < minOf(bottom, bitmap.height)) {
            var x = left.coerceAtLeast(0)
            while (x < minOf(right, bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val r = c shr 16 and 0xFF
                val b = c and 0xFF
                if (b - r >= 20 && b >= 110) count++
                x += 2
            }
            y += 2
        }
        return count
    }

    private fun importCandidateForInspect(bytes: ByteArray, name: String): DocumentImporter.ImportCandidate {
        val stamp = System.currentTimeMillis()
        val file = File(container.context.cacheDir, "$stamp-$name")
        file.writeBytes(bytes)
        return runBlocking {
            container.importer.readFromUri(
                resolver = container.context.contentResolver,
                uri = Uri.fromFile(file),
                fallbackTitle = name,
            ).copy(displayName = name, title = name.substringBeforeLast('.'))
        }
    }

    private fun importPdf(bytes: ByteArray, title: String): DocumentMeta = runBlocking {
        val stamp = System.currentTimeMillis()
        val file = File(container.context.cacheDir, "$stamp.pdf")
        file.writeBytes(bytes)
        val candidate = container.importer.readFromUri(
            resolver = container.context.contentResolver,
            uri = Uri.fromFile(file),
            fallbackTitle = "$title.pdf",
        ).copy(displayName = "$title.pdf", title = title)
        container.importer.commit(container.importer.inspect(candidate))
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("等了 ${timeoutMs}ms 仍未满足：$what")
    }

    /**
     * 等页面真的被画到屏幕上。
     *
     * 分两步，因为"渲染好了"与"画到屏幕上了"是两件事：
     *
     *   1. 先等 `Image` 进入语义树（`contentDescription = "第 N 页"`）—— 它只在
     *      `pageBitmap` 有值时才存在，这是一次便宜的 Compose 语义查询；
     *   2. 再看帧缓冲里的深色像素 —— 这才证明用户真的看得见。
     *
     * 为什么不在一个循环里反复截图：截图很贵（SwiftShader 上尤甚），
     * M9 实测过"每 50ms 截一张"会把渲染线程饿到 30 秒都画不出第一帧。
     * 所以第一步走语义、第二步限次数。失败时另存一张超时帧，便于区分
     * "一直没画"与"画了但阈值不对"。
     */
    private fun awaitPagePainted(
        page: Int,
        file: String,
        minDark: Int,
        timeoutMs: Long = 30_000,
    ) {
        awaitTrue("第 $page 页位图进入界面", timeoutMs) {
            compose.onAllNodesWithContentDescription("第 $page 页").fetchSemanticsNodes().isNotEmpty()
        }

        var lastDark = -1
        repeat(10) { attempt ->
            SystemClock.sleep(if (attempt == 0) 300 else 500)
            lastDark = countDarkPixels(screenshot(file), top = 400, bottom = 2_600)
            if (lastDark >= minDark) return
        }
        val timeoutShot = screenshot("pdf-timeout.png")
        val timeoutDark = countDarkPixels(timeoutShot, top = 400, bottom = 2_600)

        // ── 先分清"抓帧失败"与"页面没画"（M13 补）─────────────────────────────
        // 这台模拟器上 `takeScreenshot` 会间歇性返回一帧**空白**（同一台设备、
        // 同一个 App，前一轮全绿、下一轮整片 0；重启模拟器后又好了）。
        // 判据：连顶部工具栏都没画出深色像素，那这一帧根本没抓到内容 ——
        // 工具栏不可能因为 PDF 页面没渲染而消失。
        //
        // 遇到这种情况不直接报"页面没画"：那会把失败原因指向错误的地方。
        // 改用视图自绘（`renderToBitmap`）验证"这一页真的被渲染出来了"，
        // 并明确降级一次 —— 它不再声称"合成器把它显示出来了"。
        if (countDarkPixels(timeoutShot, top = 0, bottom = 400) == 0) {
            val view = awaitPdfInkView()
            var bitmap: Bitmap? = null
            compose.runOnUiThread { bitmap = view.renderToBitmap() }
            val dark = bitmap?.let { countDarkPixels(it, top = 0, bottom = it.height) } ?: -1
            println("INKLEAF_CAPTURE_FALLBACK page=$page dark=$dark min=$minDark")
            if (dark < 0 || dark < minDark) {
                throw AssertionError(
                    "第 $page 页没有被渲染出来（帧缓冲抓不到，改用视图自绘判定）：深色像素=$dark" +
                        "（阈值 $minDark）\n${viewTree()}",
                )
            }
            return
        }

        throw AssertionError(
            "第 $page 页没有画到屏幕上：最后深色像素=$lastDark，超时帧深色像素=$timeoutDark" +
                "（阈值 $minDark）\n${viewTree()}",
        )
    }

    private fun screenshot(name: String): Bitmap {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(container.context.getExternalFilesDir(null), "device-test").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** 只数一个矩形区域里的深色像素（判据与 [countDarkPixels] 相同）。 */
    private fun countDarkPixelsIn(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var dark = 0
        var y = top.coerceAtLeast(0)
        while (y < minOf(bottom, bitmap.height)) {
            var x = left.coerceAtLeast(0)
            while (x < minOf(right, bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val luminance =
                    ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (luminance < 100) dark++
                x += 3
            }
            y += 3
        }
        return dark
    }

    private fun countDarkPixels(bitmap: Bitmap, top: Int, bottom: Int): Int {
        var dark = 0
        var y = top
        while (y < minOf(bottom, bitmap.height)) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val luminance =
                    ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (luminance < 100) dark++
                x += 3
            }
            y += 3
        }
        return dark
    }

    private fun viewTree(): String {
        var text = "（取不到 Activity）"
        onMain { activity ->
            val sb = StringBuilder("视图树：\n")
            appendTree(activity.window.decorView, 0, sb)
            text = sb.toString()
        }
        return text
    }

    private fun appendTree(view: android.view.View, depth: Int, sb: StringBuilder) {
        sb.append("  ".repeat(depth)).append(view.javaClass.simpleName)
            .append(" [").append(view.left).append(',').append(view.top).append(' ')
            .append(view.width).append('x').append(view.height).append("]\n")
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) appendTree(view.getChildAt(i), depth + 1, sb)
        }
    }
}
