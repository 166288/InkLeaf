package dev.inkleaf.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkleaf.core.model.Annotation
import dev.inkleaf.core.model.ReadingSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M13 新增的四件事，在设备上跑一遍：橡皮、换笔、导出 PNG、阅读统计。
 *
 * ## 为什么这四条必须上设备
 *
 * 它们各自都有一层"Robolectric 验不到"的东西：
 *
 * | 功能 | 只有设备能验的部分 |
 * | --- | --- |
 * | 橡皮 | `MotionEvent` 真的走进 `onTouchEvent` 的擦除分支，且坐标换算（视图 → 文档）真的对 |
 * | 换笔 | 面板上真实点击 → 状态 → 写进库的 `BrushSpec` 是真的那一支 |
 * | PNG 导出 | 真 Skia 的 `renderToBitmap`，尺寸与内容与屏幕上一致 |
 * | 阅读统计 | `ON_STOP` 真的会到，且**后台那段时间真的不算** |
 *
 * 第四条尤其重要：M12 的追踪器只有 JVM 单测（注入时钟），"切后台到底会不会
 * 触发 ON_STOP""退出时最后一段会不会丢"这两件事，只有真 Activity 能回答。
 */
@RunWith(AndroidJUnit4::class)
class InkToolsOnDeviceTest : DeviceHarness() {

    /** 一条横线用的 y：与 M7 落笔测试成功的那条斜线同一高度，能落在行上。 */
    private val strokeY = 1_500f
    private val strokeFromX = 620f
    private val strokeToX = 860f

    /**
     * 橡皮：划过去擦掉一笔，状态行撤销把它**连点集一起**放回来。
     *
     * 后半句才是重点。擦除是硬删（`ink` 行真的没了），撤销必须用 `saveInk`
     * 重建 —— 如果谁把它实现成"标记删除再撤销"，这条测试会发现：库里确实又有
     * 那条标注了，但 `localPoints` 是空的，解析出来什么都画不出。
     */
    @Test
    fun `橡皮：划过去真的擦掉一笔，撤销之后点集也回来了`() {
        enterPenMode()

        val ink = drawOneStroke()
        val pointsBefore = strokePointCount(ink.id)
        assertTrue("落笔应当写出点集，实际 $pointsBefore 个点", pointsBefore > 1)
        // 让视图把这一笔读回来重新解析 —— 橡皮的命中是在**已提交的笔迹**上做的，
        // 库里有、视图还没解析到时擦，会擦不到（那时状态行会报"没擦到"）。
        SystemClock.sleep(600)

        // 切到橡皮：chip 在工具面板里，选中后它的语义描述会变。
        compose.onAllNodesWithContentDescription("橡皮")[0].performClick()
        awaitTrue("橡皮进入使用中", 10_000) {
            compose.onAllNodesWithContentDescription("橡皮（使用中）").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("ink-eraser-on.png")

        // 沿着刚画的横线擦一下。
        dispatchSwipeToDocumentView(strokeFromX - 20f, strokeY, strokeToX + 20f, strokeY, steps = 16)

        val remaining = awaitInksExactly(0, 10_000)
        assertTrue(
            "橡皮划过去之后库里不该还有笔迹，实际=${remaining.map { it.id }}\n${uiSnapshot()}",
            remaining.isEmpty(),
        )

        // 状态行变成"可撤销"。点击它是用户真正的路径，所以按文案找它。
        val undoNode = awaitNodeWithText("点这里撤销", 10_000)
        screenshot("ink-erased.png")

        // 无障碍：这一行此刻是一个**按钮**，TalkBack 必须能听出"可以撤销"。
        // 这一条不是装饰 —— 它一开始是不成立的（语义挂在 Text 自己的 modifier 上
        // 没有出现在语义树里），是本测试把整棵节点打出来才发现的，见 M13 §7.4。
        val described = compose.onAllNodesWithContentDescription("点按撤销", substring = true)
            .fetchSemanticsNodes().size
        assertTrue(
            "状态行必须带「点按撤销」的无障碍描述，否则 TalkBack 只读到一句说明：" +
                "\n${undoNode.printToString(maxDepth = 2)}",
            described > 0,
        )

        undoNode.performClick()

        // 撤销之后不仅要有那条标注，还要有**同样多的点**。
        var restored: List<Annotation.Ink> = emptyList()
        awaitTrue("撤销之后笔迹回到库里", 20_000) {
            restored = notes().filterIsInstance<Annotation.Ink>()
            restored.isNotEmpty()
        }
        val pointsAfter = strokePointCount(restored.first().id)
        assertEquals(
            "撤销必须把点集原样放回去（硬删之后只能重建），而不是留一条空标注",
            pointsBefore,
            pointsAfter,
        )
        screenshot("ink-erase-undo.png")
    }

    /**
     * 换笔：选马克笔之后写的笔迹，笔宽真的是马克笔的宽度，而墨色跟着走没被换掉。
     *
     * 墨色那一半是 `InkBrushPanel` 的一条明确规则（换笔保留当前墨色），
     * 它很容易在重构里被顺手改成"用新笔的默认色"—— 那会让用户每换一次笔
     * 颜色就变一次，而界面上的色点还显示着旧颜色。
     */
    @Test
    fun `换笔：马克笔写出来的笔迹更粗，墨色保持原样`() {
        enterPenMode()

        val penInk = drawOneStroke()
        val penBrush = snapshot().brushesOf(penInk.id).firstOrNull()
        assertNotNull("笔迹必须记下笔刷", penBrush)

        // 换马克笔：chip 的名字是"马克笔，未选中"，选中后变"已选中"。
        compose.onAllNodesWithContentDescription("马克笔", substring = true)[0].performClick()
        awaitTrue("马克笔已选中", 10_000) {
            compose.onAllNodesWithContentDescription("马克笔，已选中").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("ink-brush-panel.png")

        val markerInk = drawOneStroke(offsetY = 120f)
        val markerBrush = snapshot().brushesOf(markerInk.id).firstOrNull()
        assertNotNull("马克笔笔迹也要记下笔刷", markerBrush)

        assertTrue(
            "马克笔必须比钢笔粗：钢笔=${penBrush!!.widthEm}em 马克笔=${markerBrush!!.widthEm}em",
            markerBrush.widthEm > penBrush.widthEm * 2f,
        )
        assertEquals("换笔不该改墨色", penBrush.colorArgb, markerBrush.colorArgb)
    }

    /**
     * 导出 PNG：位图是这一页，而且**墨迹层真的被画进去了**。
     *
     * ## 为什么不用"导出图 vs 截屏"做比对
     *
     * 第一版就是那么写的：把截屏裁到文档视图的矩形，比两边的深色像素数。
     * 它在设备上失败，而且**失败得毫无道理**：整屏有 39637 个深色像素，
     * 可文档视图那块矩形（origin=(0,518)、1440×2434，与视图树完全对得上）
     * 数出来是 0。同一个 App、同一台设备，上一条测试刚在同一块区域数出上千个。
     *
     * 结论：`UiAutomation.takeScreenshot()` 在这台模拟器上不可靠（换一帧再取能过，
     * 说明是抓帧时机的问题，不是我的矩形算错了）。**不要用一个不可靠的东西去
     * 当另一个功能的判据** —— 那只会让失败原因指向错误的地方。
     *
     * ## 换成什么判据
     *
     * 用**颜色**把墨迹从正文里分出来：选朱红墨色、画一笔，然后数导出位图里的
     * 红色像素。正文是黑的，红色只可能来自墨迹层。这比"深色像素数"强得多：
     * 它证明的不是"位图里有内容"，而是"**我画的那一笔在位图里**"，
     * 而且完全绕开了帧缓冲。
     */
    @Test
    fun `导出 PNG：位图里有刚画的那一笔，尺寸等于视图`() {
        enterPenMode()

        // 用**马克笔**：0.026em 的钢笔线只有一像素多宽，白底上抗锯齿之后
        // 大半像素是浅粉色，`r - g > 50` 这种判据会数出个位数 —— 那不是
        // "没画出墨迹"，只是"取样被混色骗了"。马克笔 0.14em 宽，信号足够强。
        compose.onAllNodesWithContentDescription("马克笔", substring = true)[0].performClick()
        awaitTrue("马克笔已选中", 10_000) {
            compose.onAllNodesWithContentDescription("马克笔，已选中").fetchSemanticsNodes().isNotEmpty()
        }
        // 再换成朱红：颜色是把"墨迹"与"正文"分开的唯一手段。
        compose.onAllNodesWithContentDescription("朱红", substring = true)[0].performClick()
        awaitTrue("朱红已选中", 10_000) {
            compose.onAllNodesWithContentDescription("朱红，已选中").fetchSemanticsNodes().isNotEmpty()
        }

        val ink = drawOneStroke()
        assertEquals("这一笔必须是朱红色的", RED_ARGB, snapshot().brushesOf(ink.id).first().colorArgb)

        // ★ 必须让 Compose 跑完一轮再去渲染位图。
        //   笔迹进库之后，界面要经过一次 `LaunchedEffect` 的续体才会把它解析出来
        //   并 `submit` 给原生 View。生产环境主线程一直在跑 vsync 帧，这次提交会
        //   自然发生；instrumentation 环境里 Compose 的重组被推迟到"测试推进帧"时
        //   （M11 §7 记过同一件事）。少了这两行，`renderToBitmap` 拿到的是一张
        //   **只有正文、没有墨迹**的图 —— 第一次跑就是这样，红色的像素数是 0。
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)

        val view = awaitDocumentView()
        var bitmap: Bitmap? = null
        compose.runOnUiThread { bitmap = view.renderToBitmap(0xFFFFFFFF.toInt()) }

        val shot = bitmap
        assertNotNull("renderToBitmap 不该返回 null（视图有大小）", shot)
        assertEquals("导出的位图宽度必须等于视图宽度", view.width, shot!!.width)
        assertEquals("导出的位图高度必须等于视图高度", view.height, shot.height)

        val darkInExport = countDarkPixels(shot, top = 0, bottom = shot.height)
        assertTrue("导出的位图里必须有正文，实际深色像素=$darkInExport", darkInExport > 1_000)

        val redInExport = countPixels(shot, step = 2) { c ->
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            r > 90 && r - g > 50 && r - b > 50
        }
        assertTrue(
            "导出位图里必须能看见刚画的那一笔朱红墨迹（漏画墨迹层时这里是 0）；" +
                "实际红色像素=$redInExport，深色像素=$darkInExport\n${viewTree()}",
            redInExport > 150,
        )

        // 把导出的位图落盘，方便人眼看一眼。
        val dir = java.io.File(container.context.getExternalFilesDir(null), "device-test").apply { mkdirs() }
        java.io.File(dir, "ink-export.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * 阅读统计：在前台读几秒 → 切后台 → 这一段真的落库，且后台那段时间不算。
     *
     * `scenario.moveToState(CREATED)` 会让 Activity 走 `ON_STOP`，正是
     * `ReadingTimeEffect` 记账的时机。之后在后台等 2.5 秒，再断言时长没有跟着涨 ——
     * 这条是"只统计前台"这个承诺的唯一端到端证据。
     */
    @Test
    fun `阅读统计：前台的时间落库，后台的时间不算`() {
        // 之前的测试可能已经留下会话（模拟器上的库是持久的），所以比"新增了哪些"。
        val before = runBlocking { container.store.readingSessions.all() }.map { it.id }.toSet()

        // 至少 3 秒才会被记账（ReadingTimeTracker.MIN_DURATION_MS）。
        SystemClock.sleep(3_600)
        // 真的按 HOME 退到后台：时长由 Activity 的 ON_STOP 提交，伪造一次
        // `callActivityOnStop` 不改变 App 的前后台状态，观察者也收不到事件。
        moveToBackground()

        var added: List<ReadingSession> = emptyList()
        awaitTrue("切后台之后阅读时长落库", 20_000) {
            added = runBlocking { container.store.readingSessions.all() }.filter { it.id !in before }
            added.isNotEmpty()
        }
        // 取最新那一段：别的测试留下的异步写入理论上可能刚好在这一刻落库，
        // 用 single() 会因为"多了一条"而失败在一个与本测试无关的原因上。
        val session = added.maxByOrNull { it.startedAt }!!
        assertTrue(
            "这一段至少 3 秒（太短会被丢弃），实际 ${session.durationMs}ms",
            session.durationMs >= 3_000,
        )
        assertTrue(
            "前台只待了几秒，不该记成 ${session.durationMs}ms",
            session.durationMs < 20_000,
        )

        // 在后台待一会儿：不应该多出会话，也不该把这 2.5 秒加进那一段。
        SystemClock.sleep(2_500)
        val afterBackground = runBlocking { container.store.readingSessions.all() }
        assertEquals(
            "后台不该产生新的会话：${afterBackground.map { it.id }}",
            added.size,
            afterBackground.count { it.id !in before },
        )
        assertEquals(
            "后台的时间不该被算进刚才那一段",
            session.durationMs,
            afterBackground.first { it.id == session.id }.durationMs,
        )
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────────

    /** 等到界面上出现含 [substring] 的节点，返回它（用于点击或打印语义）。 */
    private fun awaitNodeWithText(substring: String, timeoutMs: Long): SemanticsNodeInteraction {
        var found: SemanticsNodeInteraction? = null
        awaitTrue("界面上出现「$substring」", timeoutMs) {
            val all = compose.onAllNodesWithText(substring, substring = true)
            if (all.fetchSemanticsNodes().isNotEmpty()) {
                found = all[0]
                true
            } else {
                false
            }
        }
        return found!!
    }

    /**
     * 进书写模式，并**把笔画面板展开**。
     *
     * 这一步是 M17 之后必须加的：进书写时面板默认收起（屏幕让给字），
     * 而这一组用例要点的「橡皮」「马克笔」都在面板里。
     * 不展开的话失败信息是"界面上没有橡皮"，看起来像面板被删了，
     * 而不是"它按设计收起来了"。展开用的是面板自己的入口按钮 ——
     * 与真实用户走同一条路，不做"直接改状态"的捷径。
     */
    private fun enterPenMode() {
        compose.onNodeWithContentDescription("书写").performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        awaitTrue("笔模式已打开", 10_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }
        // 按钮的名字写的是"点了会发生什么"，所以收起状态下的名字带括号。
        compose.onNodeWithContentDescription("笔画设置（笔形、墨色、粗细）").performClick()
        awaitTrue("笔画面板展开（橡皮出现）", 10_000) {
            compose.onAllNodesWithContentDescription("橡皮").fetchSemanticsNodes().isNotEmpty()
        }
        // 面板是浮层，等一帧让它在点击之前真正就位。
        SystemClock.sleep(300)
    }

    /**
     * 画一条**横线**并返回它落成的标注。
     *
     * 横线（y 不变）只会在同一行里，所以通常只落成一条标注 —— 斜线跨几行就是几条，
     * 而"擦掉一笔"的测试需要"一笔"这个前提。
     *
     * 断言的是"**多出来**一条"，不是"总共一条"：换笔那条测试要在同一篇文档上画第二笔，
     * 那时候库里已经有一条了。写 `awaitInksExactly(1)` 会让它一直等到超时，
     * 然后把**第一笔**当成第二笔返回 —— 于是"马克笔和钢笔一样宽"这种假结论
     * 反而看起来像产品 bug（这条确实在第一次运行时骗过我一次）。
     */
    private fun drawOneStroke(offsetY: Float = 0f): Annotation.Ink {
        val before = notes().filterIsInstance<Annotation.Ink>().map { it.id }.toSet()
        val y = strokeY + offsetY
        dispatchSwipeToDocumentView(strokeFromX, y, strokeToX, y, steps = 12)

        var fresh: Annotation.Ink? = null
        awaitTrue("新的一笔落进库里（原有 ${before.size} 条）", 20_000) {
            fresh = notes().filterIsInstance<Annotation.Ink>().firstOrNull { it.id !in before }
            fresh != null
        }
        return fresh!!
    }

    private fun snapshot() = runBlocking { container.store.annotations.listByDoc(meta.id) }
    private fun strokePointCount(annotationId: String): Int =
        snapshot().strokesOf(annotationId).sumOf { it.localPoints.size / 2 }

    /** 截图里一块矩形内的深色像素数（判据与 [countDarkPixels] 一致）。 */
    private fun countPixels(bitmap: Bitmap, step: Int = 3, predicate: (Int) -> Boolean): Int {
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (predicate(bitmap.getPixel(x, y))) n++
                x += step
            }
            y += step
        }
        return n
    }

    private companion object {
        /** 与 `BrushSpec.PEN_COLORS` 里的"朱红"同一个值。 */
        val RED_ARGB: Int = 0xFFB3261E.toInt()
    }
}
