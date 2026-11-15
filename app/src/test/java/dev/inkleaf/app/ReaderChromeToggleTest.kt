package dev.inkleaf.app

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 阅读界面"点正中间那一格 = 切换顶栏与托盘"（M31 引入，M32 改成真·切换，M39 重新做了一遍）。
 *
 * ## 三版之间的差别，正是这个小状态机唯一值得测的地方
 *
 * - **M31**：点一下就唤出。收不起来 —— 用户想让它让开时没辙。
 * - **M32**：为了修上一条，改成"按下先唤出、抬手再按按下前的状态决定收不收"。
 *   逻辑上是真·切换了，但代价是**任何**触摸都会把控件叫出来：
 *   手指刚落上屏幕想滑一屏，顶栏就出现了，滑完还挂着。
 * - **M39**：按下的那一下什么都不做。只有"点中间宫格"这一个**被识别出来的手势**
 *   才切换，滑动、翻页、点两侧都不会把控件叫出来。判据回到最朴素的"现在看得见吗"。
 *
 * 所以这个文件里不该再出现 `onPress` —— 它的消失就是这轮修复本身。
 * 剩下的部分测的是"切换"与"退场"这两条时间线上的规则。
 *
 * 这类时序错误在设备测试里很难稳定复现（要精确控制 DOWN/UP 的间隔），
 * 在纯状态机上却是几条直线。两条路都留着：这里测时序，设备测试测"真的点得出来"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderChromeToggleTest {

    private fun chrome() = ReaderChromeState()

    /** Robolectric 的主线程 Looper 是"手动"的：把时间往前推，计时器才会到点。 */
    private fun idleFor(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    @Test
    fun `收起时点一下中间就把控件唤出来`() {
        val chrome = chrome()
        chrome.hideChrome()
        assertFalse("先确认前提：界面是收着的", chrome.isVisible)

        chrome.onTap()

        assertTrue("收起时点一下 → 顶栏应当出现", chrome.topBarVisible)
        assertEquals("托盘至少回到迷你档", TrayLevel.MINI, chrome.level)
    }

    @Test
    fun `显示时点一下中间就把控件收起来`() {
        val chrome = chrome()
        assertTrue("新界面默认是显示态", chrome.isVisible)

        chrome.onTap()

        assertFalse("显示时点一下 → 顶栏应当收起来", chrome.topBarVisible)
        assertEquals(TrayLevel.HIDDEN, chrome.level)
    }

    @Test
    fun `点出来的控件也会在两点八秒后退场`() {
        val chrome = chrome()
        chrome.hideChrome()
        chrome.onTap()

        idleFor(4)

        // M33 改了这条口径：M32 让"点出来的"永久留着，实际用起来是一块永远占着的横条 ——
        // 用户点一下往往只是想瞥一眼页码。现在它和翻页后一样，2.8 秒后自己退场。
        assertFalse("点出来只是为了看一眼，看完该让开", chrome.topBarVisible)
        assertEquals(TrayLevel.HIDDEN, chrome.level)
    }

    @Test
    fun `点出来之后又点一下，退场立刻发生而不是等计时`() {
        val chrome = chrome()
        chrome.hideChrome()
        chrome.onTap()
        assertTrue("前提：这一下把它点出来了", chrome.topBarVisible)

        chrome.onTap()

        assertFalse("再点一下应当立刻收起", chrome.isVisible)
    }

    /**
     * 书写时点中间：唤出，但**不开始退场计时**。
     *
     * 写着字的时候工具栏自己溜走是最烦的一种：手上还捏着笔，屏幕上那块
     * 刚才还在的面板没了。要收就自己点一下收（所以"再点一下收起"仍然有效）。
     */
    @Test
    fun `书写时点出来的控件不会自己溜走`() {
        val chrome = chrome()
        chrome.hideChrome()

        chrome.onTap(autoHide = false)

        assertTrue("书写时也要能点出来", chrome.isVisible)
        idleFor(8)
        assertTrue("写字的人在动笔，控件不该自己退场", chrome.isVisible)
    }

    /**
     * 收起来再点出来，托盘回到**收起前的档位**。
     *
     * 书写时托盘是展开的（笔刷面板）。如果收一下再点开就退回迷你档，
     * 用户得重新点一次笔刷按钮才能接着调粗细 —— 收起来只是"让开屏幕"，
     * 不是"换了工具"。
     */
    @Test
    fun `展开的面板收起后再点出来仍然是展开的`() {
        val chrome = chrome()
        chrome.level = TrayLevel.EXPANDED

        chrome.onTap() // 收起
        assertEquals(TrayLevel.HIDDEN, chrome.level)

        chrome.onTap() // 再点出来
        assertEquals("托盘要回到收起前的形态", TrayLevel.EXPANDED, chrome.level)
    }

    @Test
    fun `刚从书架进来，控件也会在两点八秒后退场`() {
        // 这条是真机复现出来的：退场计时只在「翻页」和「点正文」里启动，
        // 而"刚打开这本书"两者都不是 —— 于是初始的 topBarVisible = true
        // 一直挂着，读者看到一块永远不走的横条，2.8 秒那条规则等于不存在。
        val chrome = chrome()
        assertTrue("前提：进书的时候控件是显示的（要先看见书名）", chrome.isVisible)

        chrome.enterReader()
        assertTrue("进书那一下不该把控件收掉", chrome.topBarVisible)

        idleFor(4)

        assertFalse("读者只是进来看书，2.8 秒后界面该让开", chrome.topBarVisible)
        assertEquals(TrayLevel.HIDDEN, chrome.level)
    }

    @Test
    fun `翻页之后停手仍然会进入沉浸`() {
        val chrome = chrome()
        chrome.onRead() // 翻一页

        idleFor(4)

        assertFalse("读了两页就停手，控件应当退场把屏幕让给正文", chrome.topBarVisible)
        assertEquals(TrayLevel.HIDDEN, chrome.level)
    }

    /**
     * 翻页**不会**把收起的控件唤出来（M39）。
     *
     * 这条是用户规则的原话："只有点击中间宫格可以唤出和隐藏上下底栏"。
     * 此前的实现里 [ReaderChromeState.onRead] 顺手调了唤出，于是用音量键连翻十页，
     * 顶栏就在屏幕上闪十次。翻页只该把"停手计时"往后推。
     */
    @Test
    fun `翻页不会把收起的控件唤出来`() {
        val chrome = chrome()
        chrome.onRead()
        idleFor(4)
        assertFalse("前提：已经进入沉浸", chrome.isVisible)

        chrome.onRead()
        chrome.onRead()

        assertFalse("翻页只重置计时，不改变可见性", chrome.isVisible)
    }

    @Test
    fun `翻页不会把已经点开的控件顺手收走`() {
        val chrome = chrome()
        chrome.onRead()
        idleFor(4)
        assertFalse("前提：已经进入沉浸", chrome.isVisible)

        chrome.onTap()
        assertTrue("点出来", chrome.isVisible)

        chrome.onRead() // 再翻一页
        idleFor(4)
        assertFalse("再翻页属于「在读」，控件该退场了", chrome.isVisible)
    }

    /**
     * 收起来之后，**只有点中间宫格**能让控件回来。
     *
     * 这条测的是"滑动不会唤出"：M32 的实现在手指按下时就唤出，
     * 所以一次滑屏必然把顶栏带出来。M39 拆掉了那条时序，状态机里
     * 不再有任何"被动唤出"的入口 —— 这条用例守的就是"没有"这件事：
     * 什么都不调用，界面就一直收着。
     */
    @Test
    fun `没有任何手势时控件不会自己回来`() {
        val chrome = chrome()
        chrome.onRead()
        idleFor(4)
        assertFalse("前提：已经进入沉浸", chrome.isVisible)

        // 模拟"手指在纸上滑动"：它不产生点击，也不该产生任何状态迁移。
        idleFor(10)

        assertFalse("滑屏不是点中间，控件必须留在外面", chrome.isVisible)
    }
}
