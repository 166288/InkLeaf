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
 * 阅读界面"点一下正文 = 切换顶栏与托盘"（M31 引入，M32 修成真·切换）。
 *
 * ## 为什么要单独测这个状态对象
 *
 * 这里只有三个字段（顶栏可见、托盘档位、按下时是否收着），但它们的组合决定了
 * 用户唯一的手势体验。M31 上线后用户报的是"要长按才能把工具栏叫出来" ——
 * 根因就在状态机的**时序**上，而不是某个控件画错了：
 *
 * ```
 * 手指按下 → 全局 Initial 观察到 Press → 唤出控件
 * 手指抬起 → 点击判定 → 看到"现在是显示态" → 收起
 * ```
 *
 * 两次状态迁移互相抵消，一次点击净效果为零；按住不放没有"抬起"，控件才留得住。
 * 所以判据必须是**按下之前**的状态（`onPress` 记下、`onTap` 用掉），
 * 而不是抬手那一瞬间的状态。
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
    fun `收起时点一下正文中间就把控件唤出来，不会被按下那一下抵消`() {
        val chrome = chrome()
        chrome.hideChrome()
        assertFalse("先确认前提：界面是收着的", chrome.isVisible)

        chrome.onPress() // 手指落下
        chrome.onTap() // 手指抬起

        assertTrue("收起时点一下 → 顶栏应当出现", chrome.topBarVisible)
        assertEquals("托盘至少回到迷你档", TrayLevel.MINI, chrome.level)
    }

    @Test
    fun `显示时点一下正文中间就把控件收起来`() {
        val chrome = chrome()
        assertTrue("新界面默认是显示态", chrome.isVisible)

        chrome.onPress()
        chrome.onTap()

        assertFalse("显示时点一下 → 顶栏应当收起来", chrome.topBarVisible)
        assertEquals(TrayLevel.HIDDEN, chrome.level)
    }

    @Test
    fun `点出来的控件也会在两点八秒后退场`() {
        val chrome = chrome()
        chrome.hideChrome()
        chrome.onPress()
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
        chrome.onPress()
        chrome.onTap()
        assertTrue("前提：这一下把它点出来了", chrome.topBarVisible)

        chrome.onPress()
        chrome.onTap()

        assertFalse("再点一下应当立刻收起", chrome.isVisible)
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

    @Test
    fun `没有观察到按下事件时点一下仍然能唤出`() {
        // 自动化测试直接派发抬手（或将来有人从代码里调用）时没有 Press 事件。
        // 这条兜底保证"收着就唤出、显示就收起"永远成立。
        val chrome = chrome()
        chrome.hideChrome()

        chrome.onTap()

        assertTrue(chrome.topBarVisible)
    }

    @Test
    fun `翻页不会把已经点开的控件顺手收走`() {
        val chrome = chrome()
        chrome.onRead()
        idleFor(4)
        assertFalse("前提：已经进入沉浸", chrome.isVisible)

        chrome.onPress()
        chrome.onTap()
        assertTrue("点出来", chrome.isVisible)

        chrome.onRead() // 再翻一页
        idleFor(4)
        assertFalse("再翻页属于「在读」，控件该退场了", chrome.isVisible)
    }
}
