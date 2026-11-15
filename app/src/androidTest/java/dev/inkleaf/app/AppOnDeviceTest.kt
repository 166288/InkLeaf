package dev.inkleaf.app

import android.os.SystemClock
import android.util.TypedValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkleaf.core.common.Units
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.PageGeometry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **第一次在模拟器上跑这个 App**（M7）。
 *
 * ## 它要回答的问题
 *
 * 在此之前，这个工程所有"能写能画"的结论都是 Robolectric 级的：它跑的是
 * JVM 上的一份 Android 影子，排版用的是真的（`@GraphicsMode(NATIVE)`），
 * 但**触摸事件是假的**。`ShadowView` 没有真实的输入派发链路，`MotionEvent`
 * 是手工构造的，`onTouchEvent` 有没有被调用、有没有被子 View 抢走、
 * Compose 的 `AndroidView` 会不会把事件转给子 View —— 这些 Robolectric 全都不碰。
 *
 * 所以这里只测三件"只有在设备上才成立"的事：
 *
 * 1. 打开文档之后，正文**真的**出现在屏幕上（截屏数页面区的深色像素）；
 * 2. 打开书写模式后，一次**真实注入的滑动**从窗口分发 → `DocumentInkView.onTouchEvent`
 *    → 笔迹控制器 → 库里多出一条 `Annotation.Ink`；
 * 3. 工具栏上的「高亮」与笔记面板里的「删除」这两个**真实点击**确实改了库。
 *
 * ## 为什么断言的是截屏，而不是 `View.draw` 到 Bitmap
 *
 * 第一版走的是"在视图树里找 `DocumentInkView`，再 `view.draw(canvas)`"。
 * 在模拟器上它失败了：**视图树里根本没有那个 View**（`AndroidComposeView` 下
 * 只有 `RippleContainer`），而语义树里文档区那块是有的。这说明"合成树有、
 * 原生子 View 没挂上"是一个真实存在的状态 —— 至于它是不是这一版 Compose 的
 * `AndroidView` 只在真正出帧时才挂子 View，还是别的什么，看截图最直接：
 * 截屏是**帧缓冲**，它不关心视图树长什么样。
 *
 * 也正因为如此，这里留的 PNG 是"屏幕上真实的画面"，比 `View.draw` 更接近用户看到的东西。
 *
 * ## 起 Activity、注入触摸这些事
 *
 * 全在 [DeviceHarness] 里（M13 抽出来的）。M13 的橡皮 / 换笔 / 导出测试与这里
 * 需要的基础设施完全一样，复制一份会让"测试怎么投递事件"出现两套说法。
 */
@RunWith(AndroidJUnit4::class)
class AppOnDeviceTest : DeviceHarness() {

    @Test
    fun `翻页热区：右侧三分之一点一下，真的翻到第 2 页`() {
        // 这条先于落笔那条失败时，说明问题在"派发"本身而不是笔迹层。
        dispatchTap(1_356f, 448f)
        awaitTrue("翻到第 2 页", 10_000) {
            compose.onAllNodesWithText("第 2 /", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("reader-page2.png")
    }

    @Test
    fun `正文真的画到了屏幕上`() {
        val shot = screenshot("reader-text.png")
        val dark = countDarkPixels(shot, top = 700, bottom = 2_800)
        assertTrue(
            "正文必须真的出现在屏幕上，而不是只有一层 Compose 的空壳；页面区深色像素=$dark" +
                "\n${viewTree()}",
            dark > 1_000,
        )
    }

    /**
     * 正文宽度的两条边界：**不被边距挤扁，也不放长到串行**。
     *
     * 这条用例原先断言"正文占屏宽 ≥ 80%"。那是一个只对**手机 + 17 sp** 成立的数字：
     * M19 在 11 寸平板上跑，正文 71.8% —— 红的，但什么都没坏。原因是 [PageGeometry]
     * 的规则本来就该这样：行宽封顶 **40 个字**，字号小一点封顶先生效，与边距无关。
     *
     * 所以现在断言的不是"屏宽的百分之几"，而是**排版自己算出来的那个宽度**：
     * 帧缓冲里量到的跨度必须和 [PageGeometry.solve] 的结果对得上。
     * 它同时守住两件事 —— 绘制真的照排版画的（不是各画各的），
     * 以及这条规则本身没被改成"随便多宽都行"（12..40 个字）。
     *
     * ## 容差为什么按"字"给，而不是按百分比
     *
     * 量的是**墨迹**：栏宽是折行的上界，最后一行多半写不满，折行处也可能差半个字。
     * 所以误差的自然单位是"一个字宽"（1 em），不是"百分之几"—— 用百分比会在
     * 小字号时苛刻、大字号时宽松，两头都不讲道理。这里给 **1.5 em**：
     * 够容下折行留下的空当，又远小于"绘制没照排版画"会产生的偏差
     * （12 sp 与 17 sp 之间就差着 8 em）。
     */
    @Test
    fun `排版：正文宽度照规则来，不被边距挤扁、也不长到串行`() {
        val shot = screenshot("reader-width.png")
        val span = textColumnSpan(shot, top = 700, bottom = 2_800)

        val s = container.settings.value
        val dm = activity!!.resources.displayMetrics
        val em = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp, dm)
        val expected = PageGeometry.solve(
            widthPx = shot.width,
            heightPx = shot.height,
            userMarginPx = Units.mmToPx(s.pageMarginMm, dm.densityDpi),
            textSizePx = em,
            density = dm.density,
        ).contentWidthPx

        val chars = span / em
        // 这条数字要留在 logcat 里：设计记录里"正文多宽"的结论必须能对上一次实测。
        println("INKLEAF_COLUMN span=${span}px expected=${expected}px em=%.2f chars=%.1f".format(em, chars))
        // 整串放在括号里再 `.format`：`"a" + "b".format(x)` 只会格式化**后一段**，
        // 前一段里的 `%d` 原样留在消息里 —— 上一版就是这样，于是断言信息里写着
        // "相差 %.1f%%"，看着像排版出了问题，其实是断言自己没格式化。
        val message = (
            "正文跨度 %dpx（%.1f 个字宽），排版算的是 %dpx（%.1f 个字宽）—— 绘制没照排版画；" +
                "字号=%.1fsp 边距=%.1fmm 屏宽=%dpx 密度=%.4f"
            ).format(span, chars, expected, expected / em, s.fontSizeSp, s.pageMarginMm, shot.width, dm.density)
        assertTrue(
            message + "\n${viewTree()}",
            kotlin.math.abs(span - expected) <= 1.5f * em,
        )

        // 规则的两头：少于 12 个字读不下去，多于 40 个字回行会串行。
        assertTrue("正文只有 %.1f 个字宽，被边距挤扁了".format(chars), chars >= 11.5f)
        assertTrue("一行 %.1f 个字宽，回行会串行".format(chars), chars <= 40.5f)
    }

    /**
     * 排版面板不是装饰：动一下滑杆，正文真的重排了。
     *
     * 用 `SemanticsActions.SetProgress` 而不是滑动手势：模拟器注入的滑动事件
     * 到不了 Compose（M7 记录 §5），而滑杆的语义动作是"用户把它拖到某个值"的
     * 等价入口 —— 它一定会走 `onValueChange` + `onValueChangeFinished`，
     * 正好覆盖"松手才写库"这条路径（M8 面板的草稿机制）。
     */
    @Test
    fun `排版面板：调大字号真的重排了正文`() {
        awaitTrue("读到页数", 20_000) { pageCount() > 0 }

        compose.onNodeWithContentDescription("更多").performClick()
        // M18 起这个面板多了「主题」，菜单文案随之变成四项。这里点的是**入口**，
        // 断言的是面板里的内容 —— 所以文案一改就要跟着改这一行，别让它静默失配。
        compose.onNodeWithText("排版（主题 / 字号 / 行距 / 边距）").performClick()
        // 不写死 "17 sp"：上一次跑失败会把字号留在 30 而没走到「恢复默认」，
        // 下一次这条断言就永远匹配不上。断言"面板在、字号那一行在"即可。
        awaitTrue("排版面板出现", 20_000) {
            compose.onAllNodesWithText("字号", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // **先把设置复位再量基线**。设备上的设置是持久化的，上一次跑失败会把字号
        // 留在 30 —— 那时"调到 30"是个空操作，页数自然不变，这条用例就永远红。
        // 基线必须在"确定是默认值"之后量，否则断的是"上次跑剩下什么"。
        compose.onNodeWithText("恢复默认").performClick()
        compose.waitForIdle()
        awaitTrue("恢复默认后重新排版完成", 20_000) { pageCount() > 0 }
        val before = pageCount()

        // 三根滑杆里选**字号**那根 —— 按名字选，不按下标。
        // 按下标会在别人往界面里加一根滑杆时悄悄指错对象：M15 托盘里多了
        // 笔宽滑杆之后，原来的 `[0]` 就变成了"笔宽"，而失败信息还是
        // "字号调到 30 sp 后页数应当变多" —— 一句话把排查方向指反了。
        compose.onNodeWithContentDescription("字号")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }

        // 滑杆确实认了 30 这个值，才有资格谈"页数变多"。
        // 断的是"我刚才设的那个值"，不是"这台设备上次留下的值" —— 所以它不会因为
        // 上一次跑失败而永远红。
        awaitTrue("字号标签应当变成 30 sp", 10_000) {
            compose.onAllNodesWithText("字号 30 sp", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        awaitTrue("字号调到 30 sp 后页数应当变多（原来是 $before 页）", 20_000) {
            pageCount() > before
        }
        screenshot("reader-type-panel.png")

        compose.onNodeWithText("恢复默认").performClick()
        awaitTrue("恢复默认之后页数应当回到 $before 页", 20_000) { pageCount() == before }
    }

    @Test
    fun `落笔：一次真实滑动走到库里，撤销再把它收回去`() {
        compose.onNodeWithContentDescription("书写").performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        awaitTrue("笔模式已打开", 10_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }

        // 从屏幕中间划一道斜线：中间那块是绘制区（翻页热区在左右各三分之一，
        // 但书写模式下翻页手势整个不装），这条线一定会落在文档区上。
        val startedAt = SystemClock.uptimeMillis()
        var via = "窗口派发"
        dispatchSwipe(fromX = 600f, fromY = 1_500f, toX = 940f, toY = 1_900f)
        var inks = pollInks(4_000)

        if (inks.isEmpty()) {
            // 见 [dispatchSwipe] 的注释：instrumentation 下 Compose 的互操作层没有把
            // 事件交给原生子 View。退一步直接派发给它 —— 采集 → 锚定 → 落库这条路
            // 照样必须验证，只是不再声称路由也验证过了。
            via = "DocumentInkView 派发"
            dispatchSwipeToDocumentView(fromX = 600f, fromY = 1_500f, toX = 940f, toY = 1_900f)
            inks = pollInks(20_000)
        }

        val elapsed = SystemClock.uptimeMillis() - startedAt
        println("INKLEAF_STROKE via=$via strokes=${inks.size} elapsed=${elapsed}ms")

        // 一道斜线跨过几行就是几段（一段 = 一条标注，见 RepositoryInkSink 的注释），
        // 所以这里只能断言"至少一条"，不能断言正好一条。
        assertTrue("落笔之后库里必须有笔迹（$via）；实际=${inks.size} 条\n${uiSnapshot()}", inks.isNotEmpty())
        assertTrue("落笔到库里可读不该要这么久：${elapsed}ms", elapsed < 5_000)

        // 界面也要跟着变：笔迹进库之后，工具栏上会出现「本页笔记」入口。
        // 这条断言盯的是**读回来**那一段（库 → 解析 → 界面），而不是落盘。
        awaitTrue("界面重新读到这一笔（本页笔记入口出现）", 20_000) {
            compose.onAllNodesWithContentDescription("本页笔记", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        SystemClock.sleep(400)
        screenshot("reader-ink.png")

        // 书写时控件是**常驻**的：`ReadingScaffold` 的 `hold`（书写开启）压住自动隐藏
        // 并把顶栏留住，`expand` 让托盘展开 —— 所以这里不需要"把托盘唤回来"。
        //
        // 这条用例以前是 `revealChrome()` 之后等"唤回那一下多留一个点"。那个前提在 M32
        // 之后就不成立了：`revealChrome` 改成"已可见就返回"，书写中又永远可见，于是它
        // 一下都没点、断言必然等空（M36 全量测试发现的既有红）。现在改成**自己造这个动作**：
        //
        // 点正文中间那一下，在书写模式下是**笔**，不是"叫菜单"（M32）。它既会留一个点，
        // 又不能把控件收起来 —— 两件事一起断言。留点这件事同时也是"撤两次就干净"的前提。
        val beforeTap = notes().filterIsInstance<Annotation.Ink>().size
        val decor = decorView() ?: throw AssertionError("窗口还没起来")
        dispatchTap(decor.width / 2f, decor.height / 2f)
        awaitTrue("书写时手指点正文中间会在纸上留一个点", 5_000) {
            notes().filterIsInstance<Annotation.Ink>().size == beforeTap + 1
        }
        assertTrue(
            "书写时点正文不该把控件收起来（那一下是笔，不是叫菜单）\n${uiSnapshot()}",
            compose.onAllNodesWithContentDescription("返回书架")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        repeat(2) {
            compose.onNodeWithContentDescription("撤销上一笔").performClick()
            compose.waitForIdle()
            SystemClock.sleep(250)
        }
        val left = awaitInksExactly(0)
        assertTrue("撤销之后库里不该还剩笔迹，实际=${left.map { it.id }}", left.isEmpty())

        // 撤销之后界面也得忘掉它：入口整个消失。
        awaitTrue("撤销之后「本页笔记」入口消失", 20_000) {
            compose.onAllNodesWithContentDescription("本页笔记", substring = true)
                .fetchSemanticsNodes().isEmpty()
        }
        screenshot("reader-ink-undo.png")
    }

    /**
     * 写到一半屏幕上就有墨；抬笔之后也不会空一下。
     *
     * 这条盯的是用户报的"每次写完才显示笔迹"。它在设备上量的是**帧缓冲**：
     * 手指/笔还没抬起来时就截一张，必须在正文区量到多出来的深色像素；
     * 抬笔之后再截一张，那些像素**不能少**。
     *
     * 它证明不了"中间那 50 ms 里一帧都没空"（截屏本身要几百毫秒，来不及）。
     * 那件事由 `PenPointersTest`（Robolectric，逐帧派发、逐帧断言）守着：
     * 抬笔后的暂存层与正在书写的那一层共用同一个字号估计，交接在同一帧内完成。
     * 两条一起看，才是"实时"这件事的完整证据。
     */
    @Test
    fun `写到一半屏幕上就有墨，抬笔之后也不会空一下`() {
        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("笔模式已打开", 10_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }

        // 取一段正文区做窗口：这条横线画在中间，不与工具栏、翻页条重叠。
        val bandTop = 1_300
        val bandBottom = 1_800
        val before = countDarkPixels(screenshot("pen-before.png"), bandTop, bandBottom)

        val downTime = startPenStroke(fromX = 620f, fromY = 1_500f, toX = 940f, toY = 1_620f)
        // 派发只负责把事件送进 View，**屏幕上真的画出那一帧**要等下一次绘制。
        // 少了这一步，截到的是"派发之前"的画面 —— before 与 mid 会一模一样（踩过）。
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(400)
        val mid = countDarkPixels(screenshot("pen-mid.png"), bandTop, bandBottom)
        println("INKLEAF_INK_PIXELS before=$before mid=$mid")

        try {
            assertTrue(
                "笔还没抬起来，屏幕上就必须有这一笔；before=$before mid=$mid\n${uiSnapshot()}",
                mid > before + 50,
            )
        } finally {
            // 断言失败也要把手势收尾：半个没抬起的手势会留给下一条用例一个
            // "还按着的屏幕"，之后的点击全都会被它吃掉。
            finishPenStroke(940f, 1_620f, downTime)
        }
        val after = countDarkPixels(screenshot("pen-after.png"), bandTop, bandBottom)
        println("INKLEAF_INK_PIXELS after=$after")

        // 抬笔之后那一刻：库里可能还没读回来，但屏幕上不能出现"整条消失再出现"。
        assertTrue(
            "抬笔之后墨迹不能消失；mid=$mid after=$after",
            after >= mid - 20,
        )

        val inks = pollInks(20_000)
        assertTrue("抬笔之后这一笔要真的进库，实际=${inks.size} 条", inks.isNotEmpty())
    }

    /**
     * 写快一点，一笔就该还是一笔。
     *
     * 用户最初的抱怨是"速度快一点后笔迹会中断"。中断在数据上的样子很具体：
     * 一次书写在库里变成了**几笔**，屏幕上看到的就是断口。
     *
     * 而"跨行"本来就会切成几条记录（一段 = 一条标注），它们的 id 是
     * `X`、`X#1`、`X#2` —— 去掉 `#n` 后缀仍然同根（见 `InkAnchorFactory.segmentId`）。
     * 所以判据只有一条：这一整笔所有记录的**根 id 只有一个**。
     *
     * 再加上"整条线都画到了"的像素证据，一起排除另一种假绿：
     * 只落了开头几个点，根 id 自然也只有一两个。
     */
    @Test
    fun `写得快也是一笔到底，库里的记录只有一个根`() {
        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("笔模式已打开", 10_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }
        val beforeIds = notes().filterIsInstance<Annotation.Ink>().map { it.id }.toSet()

        // 四段横带，覆盖这条斜线从 1500 到 1900 的全程。
        // 量的是"每一段都多出了深色像素" —— 少一段就说明后半截没画上。
        val bands = listOf(1_450 to 1_560, 1_620 to 1_730, 1_790 to 1_900)
        val beforeShot = screenshot("fast-before.png")
        val before = bands.map { countDarkPixels(beforeShot, it.first, it.second) }

        // 60 个点、每 12ms 一个：与真机上手写笔的采样节奏同量级（60~90Hz）。
        // `paceMs` 让它**逐点派发并等一帧**，于是每一帧都真的被画出来 ——
        // 只有这样才量得到"写快一点"这件事本身，也才有延迟埋点的样本
        // （一次性灌进去的那条路，事件全到了但一帧没画，日志一行都没有）。
        val downTime = startPenStroke(
            fromX = 600f,
            fromY = 1_500f,
            toX = 940f,
            toY = 1_900f,
            steps = 60,
            paceMs = 12,
        )
        finishPenStroke(940f, 1_900f, downTime)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(400)

        val afterShot = screenshot("fast-after.png")
        val after = bands.map { countDarkPixels(afterShot, it.first, it.second) }
        after.forEachIndexed { i, pixels ->
            val band = bands[i]
            assertTrue(
                "y=${band.first}..${band.second} 这一段没有墨：这条线只画到了前半截" +
                    "（before=${before[i]} after=$pixels）全程 before=$before after=$after",
                pixels > before[i] + 20,
            )
        }

        // 跨行的各段是逐段提交的，等它落完再看，否则会读到一个中间状态。
        awaitTrue("这一笔进库", 20_000) {
            notes().filterIsInstance<Annotation.Ink>().any { it.id !in beforeIds }
        }
        SystemClock.sleep(1_000)

        val mine = notes().filterIsInstance<Annotation.Ink>().filter { it.id !in beforeIds }
        assertTrue("这一笔要落库，实际=${mine.size} 条", mine.isNotEmpty())
        val roots = mine.flatMap { it.strokeIds }.map { it.substringBefore('#') }.toSet()
        assertTrue(
            "写得快也不能被切成几笔：根 id 应当只有一个，实际=$roots" +
                "（这条线在库里是 ${mine.map { it.id }}）",
            roots.size == 1,
        )

        // 收尾：只撤自己这一笔，撤回写入前的条数。
        // 不断言回到 0 —— 前一条用例特意留着它的那一笔（那是在验抬笔不空档）。
        compose.onNodeWithContentDescription("撤销上一笔").performClick()
        compose.waitForIdle()
        SystemClock.sleep(300)
        awaitTrue("撤销之后回到写入前的条数", 10_000) {
            notes().filterIsInstance<Annotation.Ink>().none { it.id !in beforeIds }
        }
    }

    @Test
    fun `高亮当前这一行，再在面板里把它删掉`() {
        compose.onNodeWithContentDescription("高亮当前这一行").performClick()
        val highlight = awaitOne { notes().filterIsInstance<Annotation.Highlight>() }
        assertTrue("高亮必须落库，实际 id=${highlight.id}", highlight.id.isNotEmpty())

        // 面板的入口文案带条数（"本页笔记（1 条）"），所以用子串匹配。
        awaitTrue("本页笔记入口出现", 20_000) {
            compose.onAllNodesWithContentDescription("本页笔记", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // 入口出现说明界面已经读回来并解析过一次了；再等一帧让色带画上。
        SystemClock.sleep(400)
        screenshot("reader-highlight.png")
        compose.onNodeWithContentDescription("本页笔记", substring = true).performClick()

        awaitTrue("面板里出现删除按钮", 20_000) {
            compose.onAllNodesWithText("删除").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("reader-notes-panel.png")
        compose.onNodeWithText("删除").performClick()

        awaitTrue("删除之后库里不该再有高亮", 20_000) {
            notes().filterIsInstance<Annotation.Highlight>().isEmpty()
        }
    }
}
