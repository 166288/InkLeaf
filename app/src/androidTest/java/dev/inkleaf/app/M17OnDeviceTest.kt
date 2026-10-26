package dev.inkleaf.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkleaf.core.ink.DocumentInkView
import dev.inkleaf.core.model.Annotation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M17 的四件"用户能看见的变化"，各自要一份设备上的证据。
 *
 * | 用户说的话 | 只有设备能回答的那一半 |
 * | --- | --- |
 * | 书架加一个网格模式，能来回切 | 卡片**真的**变成半宽（不是只换了个图标） |
 * | 书写时能翻页（M25 改写：原来是不能翻） | 托盘按钮**真的**变成可用态，点它**真的**翻页 |
 * | 点笔记模式时笔刷选项收起 | 面板**真的**不在树上，且还留着一个能点开的入口 |
 * | 手写笔模式：只有笔能写 | 手指的事件**真的**被 `onTouchEvent` 放行，笔的**真的**画出笔迹 |
 *
 * ## 为什么这四条不能只靠单测
 *
 * 它们的实现都落在"Compose 状态 → 原生 View"这条缝上：单测能证明
 * `attachStylusOnly(true)` 之后 `onTouchEvent` 返回 false，但证明不了
 * "改设置会让 View 真的收到那个 true"；能证明 `enabled = false` 算出来是 false，
 * 但证明不了用户看见的那个按钮是灰的。这条缝在本工程里出过事（M7 §5、M15 排版面板），
 * 所以这一组一律走真实点击 + 真实 `MotionEvent`。
 *
 * ## 收尾
 *
 * 设备上的设置是持久化的（同 [SettingsOnDeviceTest] 的教训）：字号、书架模式、
 * 手写笔模式都会留给下一条用例。每条用例在动手之前记下原值，结束时还原，
 * 还原也写成断言 —— 否则下一条用例的红会指向完全无关的地方。
 */
@RunWith(AndroidJUnit4::class)
class M17OnDeviceTest : DeviceHarness() {

    // ── 1. 书架：网格 / 列表 ────────────────────────────────────────────────

    /**
     * 网格与列表来回切一次。
     *
     * 断言分三层，缺一层这条测试就没有意义：
     *
     * 1. 按钮的无障碍描述跟着翻（"切换为网格" ↔ "切换为列表"）；
     * 2. 设置真的写进了 `library.grid`（否则冷启动又回到列表，用户会觉得开关坏了）；
     * 3. **卡片宽度真的变了** —— 这一条才是"换了排布"的证据。
     *    只断言前两层的话，把 `LazyVerticalGrid` 换回 `LazyColumn` 但忘了删按钮，
     *    测试照样绿。
     */
    @Test
    fun `书架：网格与列表来回切换，卡片真的换了排布`() {
        backToShelf()

        val original = container.settings.value.libraryGrid
        // 基线自己摆正：上一次跑可能把设备留在了网格模式。
        if (original) toggleGrid()

        assertFalse("起步应当是列表模式", container.settings.value.libraryGrid)
        val listWidth = titleWidthPx()

        toggleGrid()

        assertTrue("设置里真的切到了网格", container.settings.value.libraryGrid)
        assertTrue(
            "切到网格之后，按钮该换成「切换为列表」：${uiSnapshot()}",
            hasNode("切换为列表"),
        )
        val gridWidth = titleWidthPx()

        // 1440px 宽的屏，列表卡片占满一行（约 1356px），网格两张并排（约 660px）。
        // 用一个宽松的上界（0.7）而不是写死像素：窗口尺寸不该是这条测试的前提。
        assertTrue(
            "网格里的卡片应当明显变窄：列表 ${listWidth}px → 网格 ${gridWidth}px",
            gridWidth < listWidth * 0.7f,
        )

        // 收尾：回到进来之前的样子。
        if (container.settings.value.libraryGrid != original) toggleGrid()
        assertEquals("收尾：书架模式回到原样", original, container.settings.value.libraryGrid)
    }

    // ── 2. 书写时的翻页（M17 旧契约 → M25 新契约）──────────────────────────

    /**
     * 书写时**能**翻页了：按钮不再置灰，点它就翻。
     *
     * ## 为什么这条断言改写了
     *
     * M17 原来的契约是"书写时不翻页"：翻页按钮置灰，划一下也不翻。
     * 理由是"翻页与书写落在同一区域，必须二选一"。
     *
     * M25 换掉了这个前提：二选一**下沉到了 View 那一层**——
     * 笔的触摸被原生 View 消费（落笔），根本到不了翻页手势；
     * 手指的左右各 1/3 让给翻页，中间照常写字。
     * 所以"翻页与书写会互相干扰"这件事已经不成立了，
     * 继续把按钮置灰反而是在挡路。
     *
     * 这条用例留着，是因为"书写时能不能翻页"是用户最容易感知的一条：
     * 按钮灰着，用户只会以为它坏了。
     */
    @Test
    fun `书写时能翻页：按钮不再置灰，点它就翻`() {
        val originalFont = container.settings.value.fontSizeSp
        try {
            runBlocking { container.updateSettings { it.copy(fontSizeSp = 30f) } }
            awaitTrue("字号调到 30 sp 后正文不止一页", 30_000) { pageCount() >= 2 }

            // 前置条件必须由用例自己保证：第 1 页上「上一页」本来就该是灰的
            // （那不是书写模式的毛病），而"打开时停在第几页"取决于上次运行留下的
            // 阅读进度 —— 一度因为上一轮把进度留在了第 1 页而在这里误报。
            // 先在**阅读模式**里翻一页，确保现在不是第一页，再去验书写时的按钮状态。
            revealChrome()
            val labelBeforeSeeding = awaitPageLabel()
            compose.onNodeWithContentDescription("下一页").performClick()
            awaitTrue("先翻到第 2 页（给「上一页」一个可用的前提）", 10_000) {
                pageLabel() != null && pageLabel() != labelBeforeSeeding
            }

            val labelBefore = awaitPageLabel()
            // 等设置落盘花掉了时间，沉浸可能已经把托盘收起来了。
            revealChrome()
            compose.onNodeWithContentDescription("书写").performClick()
            awaitTrue("进入书写", 10_000) { hasNode("退出书写") }

            assertTrue(
                "书写时「下一页」必须可用（它是 enabled，不是消失）：${uiSnapshot()}",
                !isDisabled("下一页"),
            )
            assertTrue("书写时「上一页」也必须可用", !isDisabled("上一页"))

            // 点它就翻 —— 这一步是"真的能翻"，不是"按钮不是灰的"。
            compose.onNodeWithContentDescription("下一页").performClick()
            awaitTrue("书写时点「下一页」真的翻到下一页", 10_000) {
                pageLabel() != null && pageLabel() != labelBefore
            }

            compose.onNodeWithContentDescription("退出书写").performClick()
            awaitTrue("退出书写", 10_000) { !hasNode("退出书写") }
        } finally {
            runBlocking { container.updateSettings { it.copy(fontSizeSp = originalFont) } }
        }
    }

    // ── 3. 进书写时面板收起 ────────────────────────────────────────────────

    /**
     * 读的时候没有面板；进书写也不展开，只留一个入口；点开才出现。
     *
     * "收起"这个需求很容易做成"看不见了但还占着地方"或者"整个删掉再也点不出来"。
     * 所以这条测试既断言**面板不在**（橡皮 chip 找不到），也断言**入口在**
     * （"笔画设置（笔形、墨色、粗细）"找得到），再走一遍展开 → 收起。
     */
    @Test
    fun `进书写时笔画面板收起，点开才出现`() {
        // 读的时候：既没有面板，也没有入口 —— 那时不存在"笔的属性"这回事。
        assertFalse("读的时候不该有笔画设置按钮", hasNode("笔画设置（笔形、墨色、粗细）"))
        assertFalse("读的时候面板当然也不在", hasNode("橡皮"))

        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("进入书写", 10_000) { hasNode("退出书写") }

        assertFalse(
            "进书写时面板必须是收起的：只剩按钮高亮，屏幕让给字\n${uiSnapshot()}",
            hasNode("橡皮"),
        )
        assertTrue(
            "收起之后必须留一个能点开的入口：${uiSnapshot()}",
            hasNode("笔画设置（笔形、墨色、粗细）"),
        )

        compose.onNodeWithContentDescription("笔画设置（笔形、墨色、粗细）").performClick()
        awaitTrue("点开之后出现橡皮", 10_000) { hasNode("橡皮") }
        assertTrue("展开后按钮的名字变成「收起」", hasNode("收起笔画设置"))

        compose.onNodeWithContentDescription("收起笔画设置").performClick()
        awaitTrue("再点一次能收回", 10_000) { !hasNode("橡皮") }

        compose.onNodeWithContentDescription("退出书写").performClick()
        awaitTrue("回到读的状态", 10_000) { !hasNode("退出书写") }
    }

    // ── 4. 只用手写笔 ──────────────────────────────────────────────────────

    /**
     * 开着"只用手写笔"时：手指划过去**没有**笔迹，笔划过去**有**。
     *
     * 两条都要断言，少一条就没意义：只断言"手指没留下笔迹"的话，
     * 把整个绘制功能关掉也能满足；只断言"笔留下了笔迹"的话，
     * 那条判据可能压根没生效（模拟器上手指和笔的差别只有 `toolType`）。
     *
     * 等的是 `DocumentInkView.stylusOnlyEnabled` 而不是 sleep：
     * 设置变了到 View 收到之间隔着一次重组，赌时间会把失败指向错误的方向。
     */
    @Test
    fun `只用手写笔：手指画不出笔迹，手写笔可以`() {
        val original = container.settings.value.stylusOnly
        runBlocking { container.updateSettings { it.copy(stylusOnly = true) } }
        try {
            val view = awaitDocumentView()
            // 等的是"View 上那个开关真的到位"，不是 sleep 一段时间赌它到位。
            //
            // 每轮必须先 `waitForIdle()`：Compose 测试框架**自己掌握帧时钟**，
            // 重组只在它推进一帧的时候才应用。光 Thread.sleep 的话，待应用的重组
            // 永远不跑，View 上那个值就永远停在旧值上 —— 第一次跑这条用例时
            // 就是因此超时 20 秒的（而产品代码一直是好的）。
            awaitTrue("View 收到「只用手写笔」=true", 20_000) {
                compose.waitForIdle()
                readStylusOnly(view)
            }
            assertTrue(
                "设置里明明是 ${container.settings.value.stylusOnly}，View 上却是 false：" +
                    "说明设置没有传到 View\n${viewTree()}",
                readStylusOnly(view),
            )

            revealChrome()
            compose.onNodeWithContentDescription("书写").performClick()
            awaitTrue("进入书写", 10_000) { hasNode("退出书写") }

            val before = inkCount()

            // 手指：同一个位置、同一个手势，只有 toolType 不同。
            dispatchSwipeToDocumentView(
                fromX = 620f, fromY = 1_500f, toX = 860f, toY = 1_500f,
                steps = 12, toolType = MotionEvent.TOOL_TYPE_FINGER,
            )
            // 给足时间：如果它真会落笔，两秒之内一定进库（M7 实测一笔 < 5s）。
            SystemClock.sleep(2_000)
            assertEquals(
                "只用手写笔时，手指不该留下任何笔迹（库里有 ${inkCount() - before} 条新的）\n" +
                    uiSnapshot(),
                before,
                inkCount(),
            )

            // 笔：紧贴着上一条的位置再划一次。
            dispatchSwipeToDocumentView(
                fromX = 620f, fromY = 1_620f, toX = 860f, toY = 1_620f,
                steps = 12, toolType = MotionEvent.TOOL_TYPE_STYLUS,
            )
            awaitTrue("手写笔的一笔落进库里（原来 $before 条）", 20_000) { inkCount() > before }
        } finally {
            runBlocking { container.updateSettings { it.copy(stylusOnly = original) } }
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    /** 从阅读界面退回书架。基类已经把一本书打开了。 */
    private fun backToShelf() {
        compose.runOnUiThread { activity?.onBackPressedDispatcher?.onBackPressed() }
        awaitTrue("回到书架", 20_000) {
            compose.onAllNodesWithText("书架").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 点一次顶栏的模式按钮，等到设置真的翻面。 */
    private fun toggleGrid() {
        val before = container.settings.value.libraryGrid
        val target = if (before) "切换为列表" else "切换为网格"
        compose.onNodeWithContentDescription(target).performClick()
        awaitTrue("书架切到${if (before) "列表" else "网格"}", 10_000) {
            container.settings.value.libraryGrid != before
        }
        compose.waitForIdle()
    }

    /**
     * 书架上《这篇文档》那张卡片的宽度（px）。
     *
     * 取语义节点而不是 `DocumentTile` 内部：卡片是 `clickable`（合并子节点），
     * 所以标题文字会合到卡片那个节点上，量出来的就是卡片本身的宽度。
     */
    private fun titleWidthPx(): Float {
        val nodes = compose.onAllNodesWithText(meta.title).fetchSemanticsNodes()
        assertNotNull("书架上应当找得到《${meta.title}》：${uiSnapshot()}", nodes.firstOrNull())
        return nodes.maxOf { it.boundsInRoot.width }
    }

    private fun hasNode(desc: String): Boolean =
        compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()

    /**
     * 这个按钮当前是不是禁用态。
     *
     * 读 `SemanticsProperties.Disabled` 而不是 `assertIsNotEnabled()`：
     * 后者在节点根本不存在时抛的是"没找到节点"，而这两件事在这里必须分得清
     * —— "按钮消失了"和"按钮变灰了"对用户是完全不同的两件事。
     */
    private fun isDisabled(desc: String): Boolean {
        val nodes = compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes()
        assertTrue("界面上找不到「$desc」：${uiSnapshot()}", nodes.isNotEmpty())
        return nodes.all { it.config.contains(SemanticsProperties.Disabled) }
    }

    /** 翻页条上那串"第 N / M 页"。找不到就抛，不返回 null 让调用方去猜。 */
    private fun awaitPageLabel(timeoutMs: Long = 20_000): String {
        var label: String? = null
        awaitTrue("翻页条报出页码", timeoutMs) {
            label = pageLabel()
            label != null
        }
        return label!!
    }

    private fun pageLabel(): String? {
        val pattern = Regex("""第\s*\d+\s*/\s*\d+\s*页""")
        for (node in compose.onAllNodesWithText("页", substring = true).fetchSemanticsNodes()) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text } ?: continue
            pattern.find(text)?.let { return it.value }
        }
        return null
    }

    private fun inkCount(): Int = notes().count { it is Annotation.Ink }

    /** 在主线程上读 View 的开关：runOnMainSync 顺带给了跨线程可见性。 */
    private fun readStylusOnly(view: DocumentInkView): Boolean {
        var value = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            value = view.stylusOnlyEnabled
        }
        return value
    }
}
