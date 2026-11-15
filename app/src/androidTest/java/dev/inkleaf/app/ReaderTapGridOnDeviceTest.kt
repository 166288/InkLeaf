package dev.inkleaf.app

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkleaf.core.model.Annotation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阅读界面的手势与按键入口（M39）。
 *
 * ## 这一组测的是"哪一块区域、用什么输入、做了什么"
 *
 * 用户在 M39 提了三条规则，三条都是**命中判定**而不是视觉效果：
 *
 * 1. 音量键翻页失效；
 * 2. 只有点正文**正中间那一格**能唤出/收起工具栏；
 * 3. 手写笔模式下，只有**手指**点那一格才算；用笔时其余滑动不该把工具栏带出来。
 *
 * 这类规则在单测里只能验一半：`readerTapZone`（九宫格换算）与
 * `ReaderChromeState`（显隐状态机）各自都有 JVM 用例，但"这一下到底有没有
 * 到得了那一层"只有真机能回答 —— 手指的按下先经过 `DocumentInkView`，
 * 它按落笔规则决定吃不吃。所以这里用真实的 MotionEvent 派发，不 mock。
 *
 * ## 为什么音量键那条值得单独写
 *
 * 它从 M27 起就是坏的，而且坏得毫无声息：按键被 App 吃掉（系统音量条不弹）、
 * 页码一动不动、日志里每一层读到的状态都对。根因是回调注册得太早，
 * 捕获了排版还没算完那一版的空页表。没有一条用例问过"按下去页码会变吗"，
 * 于是它活了十二轮。
 */
@RunWith(AndroidJUnit4::class)
class ReaderTapGridOnDeviceTest : DeviceHarness() {

    private fun topBarPresent(): Boolean =
        compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isNotEmpty()

    /** 托盘上的页码文字，形如「第 2 / 3 页」。托盘收起时是 null。 */
    private fun pageText(): String? = compose.onAllNodesWithText("页", substring = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        }
        .firstOrNull { Regex("""第 \d+ / \d+ 页""").matches(it) }

    private fun decorW(): Float = (decorView()?.width ?: 0).toFloat()

    private fun decorH(): Float = (decorView()?.height ?: 0).toFloat()

    private fun centerX(): Float = decorW() / 2f

    private fun centerY(): Float = decorH() / 2f

    /** 点一下正文正中（正中间那格）。 */
    private fun tapCenter() {
        dispatchTap(centerX(), centerY())
        compose.waitForIdle()
    }

    private fun hideChrome() {
        if (topBarPresent()) tapCenter()
        awaitTrue("工具栏收起", 5_000) { !topBarPresent() }
    }

    private fun reveal() {
        if (!topBarPresent()) tapCenter()
        awaitTrue("工具栏出现", 5_000) { topBarPresent() }
    }

    /**
     * 音量键翻一页。
     *
     * 用 `sendKeyDownUpSync` 而不是直接调回调：要验的正是"按键能不能走到
     * `MainActivity.onKeyDown`"这一段。只调回调的用例在 M39 之前也是绿的。
     */
    @Test
    fun `音量键按一下前进一页`() {
        reveal()
        val before = pageText() ?: throw AssertionError("托盘上没有页码：${uiSnapshot()}")

        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)

        awaitTrue("按音量下键之后页码应当变（原来是 $before）", 5_000) {
            val now = pageText()
            now != null && now != before
        }
    }

    /** 点正中间那一格：显示时收起，收着时显示。 */
    @Test
    fun `点正中间那一格切换工具栏`() {
        reveal()
        tapCenter()
        awaitTrue("显示时点中间 → 应当收起", 5_000) { !topBarPresent() }

        tapCenter()
        awaitTrue("收着时点中间 → 应当唤出", 5_000) { topBarPresent() }
    }

    /**
     * 中间列的上下两格是**有意留白**（九宫格的第一、三行中间）。
     *
     * 它们最容易被写成"顺手也切成切换" —— 那时候用户点页头想继续读，
     * 工具栏就跳出来了。这条用例守的是"什么都不发生"，
     * 也就是必须**先确认它原本是收着的**，再确认点完还是收着的。
     */
    @Test
    fun `点中间列的上下两格不会唤出工具栏`() {
        hideChrome()

        dispatchTap(centerX(), decorH() / 6f)
        compose.waitForIdle()
        Thread.sleep(600)
        assertTrue("点中间列上格不该唤出工具栏：${uiSnapshot()}", !topBarPresent())

        dispatchTap(centerX(), decorH() * 5f / 6f)
        compose.waitForIdle()
        Thread.sleep(600)
        assertTrue("点中间列下格不该唤出工具栏：${uiSnapshot()}", !topBarPresent())
    }

    /**
     * 竖着滑一下不会把工具栏带出来。
     *
     * M32 起界面层在**按下**那一刻就唤出控件，于是"想往上滑一屏"的每一次
     * 都会顺手把顶栏放出来，滑完它还挂着。这条用例就是那个行为的回归守卫。
     */
    @Test
    fun `竖着滑动不会唤出工具栏`() {
        hideChrome()

        dispatchSwipe(centerX(), decorH() * 0.7f, centerX(), decorH() * 0.3f)
        compose.waitForIdle()
        Thread.sleep(600)
        assertTrue("滑动不该唤出工具栏：${uiSnapshot()}", !topBarPresent())
    }

    /**
     * 手写笔模式下：用笔写一笔之后，**手指**点正中间能收起工具栏。
     *
     * 这条链路上有两个"吃掉触摸"的环节，缺一个动作都不成立：
     * - 笔的按下被 `DocumentInkView` 消费（那是笔迹的起点）；
     * - 手指的按下平时也会被它消费（那时手指在写字），只有在
     *   "最近一次输入是笔"时，正中那一格才让给界面层（见 `centerTapZone`）。
     *
     * 用 `TOOL_TYPE_STYLUS` 派发那第一笔：模拟器没有真笔，
     * 这是"最近一次输入是笔"唯一的来源。
     */
    @Test
    fun `用笔写过之后手指点中间能收起工具栏`() {
        reveal()
        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("进入书写模式（出现撤销按钮）", 5_000) {
            compose.onAllNodesWithContentDescription("撤销上一笔").fetchSemanticsNodes().isNotEmpty()
        }

        // 先用笔写一笔：这一步是"最近一次输入是笔"的证据。
        dispatchSwipeToDocumentView(
            centerX() - 120f, centerY() - 260f,
            centerX() - 60f, centerY() - 200f,
            steps = 8,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
        )
        compose.waitForIdle()
        awaitTrue("笔迹落库", 8_000) { notes().filterIsInstance<Annotation.Ink>().isNotEmpty() }

        // 书写时工具栏不自动退场（暂停计时），所以此刻它一定还在。
        assertTrue("前提：书写时工具栏仍然可见", topBarPresent())

        tapCenter()
        awaitTrue("用手指点正中间 → 书写时也应当能收起工具栏", 5_000) { !topBarPresent() }

        tapCenter()
        awaitTrue("再点一下 → 应当唤出，方便接着选工具", 5_000) { topBarPresent() }
    }
}
