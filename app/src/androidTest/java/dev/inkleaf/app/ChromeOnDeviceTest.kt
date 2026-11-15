package dev.inkleaf.app

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阅读界面外框（M15）：控件下沉、可缩小、状态栏适时隐藏。
 *
 * ## 这里唯一不能让步的断言
 *
 * 「沉浸前后正文可用高度一个像素都不变」。
 *
 * 这一条不是审美，是正确性。正文区变一寸就要重新分页（`PageGeometry.solve` →
 * `StaticLayout`），每个字符都会挪位置：用户正在读的那一行跳走，正在写的那一笔
 * 按新排版重新锚定。本工程为这件事吃过两次亏（M8 记过一次，M13 §8 第 9 条又记过），
 * 所以这一版把控件全部改成覆盖层，并且用一个**常量**给正文让出底部空间。
 *
 * 这条断言在 JVM 上验不了：它要的是"同一个 View 在两种界面状态下的真实高度"，
 * 只有在设备上跑真实测量才有意义。
 *
 * ## 其余几条
 *
 * 沉浸计时到点顶栏真的退场、点正文真的能唤回**也能收起**、托盘收起之后核心动作
 * （翻页）还能用、书写时点正文不把控件收走。合起来才是"可以缩小"，
 * 只有第一条是"变没了"。
 *
 * 其中"唤回 / 收起"必须分成两条：M31 的"按下唤出、抬手收起"把这一对互相抵消了，
 * 而只断言"最终能唤回"的用例在那种实现下依然会绿（它等的是终点，而终点恰好对）。
 */
@RunWith(AndroidJUnit4::class)
class ChromeOnDeviceTest : DeviceHarness() {

    private fun topBarPresent(): Boolean =
        compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isNotEmpty()

    /**
     * 翻一页，然后等界面进入沉浸。
     *
     * 为什么用翻页而不是随便戳一下屏幕：**隐藏计时的触发器是"在读"这个动作**，
     * 不是任意触摸。点工具栏、开菜单是"我在操作界面"，那些不该让界面淡出
     * —— 否则用户点开菜单犹豫三秒，界面就在他眼前消失了。
     */
    private fun turnAndWaitForImmersion() {
        revealChrome()
        compose.onAllNodesWithContentDescription("下一页")[0].performClick()
        awaitTrue("翻页后停手 2.8 秒，顶栏退场（进入沉浸）", 8_000) { !topBarPresent() }
    }

    private fun centerX(): Float = (decorView()?.width ?: 0) / 2f

    private fun centerY(): Float = (decorView()?.height ?: 0) / 2f

    @Test
    fun `沉浸前后正文可用高度一个像素都不变`() {
        val before = awaitDocumentView().height
        assertTrue("正文区应当有高度（实际 $before）", before > 0)

        turnAndWaitForImmersion()

        val after = awaitDocumentView().height
        assertEquals(
            "沉浸只是把控件淡出去，不能动正文的可用高度 —— 动了就要重新分页，" +
                "正在读的那一行会跳走",
            before,
            after,
        )
    }

    @Test
    fun `沉浸之后点一下正文中间就把顶栏唤回来`() {
        turnAndWaitForImmersion()
        // 点正中：既不是左/右三分之一的翻页热区，也不是托盘。
        dispatchTap(centerX(), centerY())
        awaitTrue("点正文中央之后顶栏回来", 5_000) { topBarPresent() }
    }

    /**
     * 切换的另一半：显示时点一下应当**收起来**。
     *
     * 两条合起来才是"点一下切换"。只有前一条时，M31 那个"按下唤出、抬手收起"
     * 的抵消 bug 是看不出来的（唤出那条也是绿的，因为测试等的是最终状态）。
     */
    @Test
    fun `显示时点一下正文中间就把顶栏收起来`() {
        revealChrome()
        assertTrue("前提：顶栏在屏幕上", topBarPresent())

        dispatchTap(centerX(), centerY())
        awaitTrue("点正文中央之后顶栏退场", 5_000) { !topBarPresent() }
    }

    /**
     * 记笔迹的时候，手指点正文**不该**把控件收起来（M32 用户反馈）。
     *
     * 滚动模式最容易撞上：滚动本身不消费触摸，点击会一路冒到界面层的
     * "点正文"手势上。写两个字顶栏和托盘跳一下，谁也没法专心。
     */
    @Test
    fun `书写模式下点正文中间不会把控件收起来`() {
        revealChrome()
        compose.onNodeWithContentDescription("书写").performClick()
        awaitTrue("进入书写模式", 5_000) {
            compose.onAllNodesWithContentDescription("退出书写").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("书写时顶栏本来就在", topBarPresent())

        dispatchTap(centerX(), centerY())
        compose.waitForIdle()

        assertTrue(
            "书写中点正文等于落笔/误触，不该顺手把工具栏收起来",
            topBarPresent(),
        )
    }

    @Test
    fun `收起之后页码仍然读得到，不把位置信息一起抹掉`() {
        val pages = pageCount()
        assertTrue("托盘上应当已经给出总页数（读到 $pages）", pages > 0)

        turnAndWaitForImmersion()

        // 收起态只有 6 dp：页码**看不见**了，但它仍然在语义树里 —— 收起只该
        // 发生在视觉层，读屏用户的位置感不该跟着一起消失。
        //
        // 这一点本版曾经写反。当时的证据是 `pageCount()` 返回 -1，看起来像
        // "连语义节点都没有了"；实际上那是辅助函数自己的 bug —— 它只认
        // "第 1 /"，翻开第二页之后永远返回 -1。M15 §8 的原文照抄了这个结论，
        // 这条断言就是用来把它钉回事实的。
        assertEquals(
            "收起之后页码不该从语义树里消失（读屏用户仍然要能报出读到第几页）",
            pages,
            pageCount(),
        )

        // 唤出也必须是即刻的：点一下正文中间，页码连同整条托盘一起回来。
        revealChrome()
        awaitTrue("唤出之后页码还在（$pages 页）", 5_000) { pageCount() == pages }
    }

    @Test
    fun `托盘收起之后仍然能把页翻过去`() {
        turnAndWaitForImmersion()

        // 收起态的托盘只有 6 dp，图标不在可视区里 —— 先按真实路径唤醒，
        // 这一步本身也是产品行为：点正文中间会把控件叫回来。
        revealChrome()
        compose.waitForIdle()
        awaitTrue("唤醒之后托盘上重新有「下一页」", 5_000) {
            compose.onAllNodesWithContentDescription("下一页").fetchSemanticsNodes().isNotEmpty()
        }

        // **不写死"翻到第 3 页"**。总页数取决于版式与字号，M19 起 `@Before` 会把
        // 排版参数复位成默认值（17 sp），同一篇样例正文在平板上正好 2 页 ——
        // 于是"再翻一页到第 3 页"永远不会有。这条用例之前是**靠上一条用例把字号
        // 留在 30 sp** 才绿的：它证明的其实不是"托盘收起后还能翻页"，而是
        // "上一个用例改过的字号还在"。现在断言的是页码本身往前走一步。
        val total = pageCount()
        assertTrue("托盘收起之后仍然读得到总页数（读到 $total）", total > 0)

        if (currentPage() >= total) {
            // 已经在末页：先退一页，保证"再往前翻"真的有的翻。
            val atEnd = currentPage()
            compose.onAllNodesWithContentDescription("上一页")[0].performClick()
            awaitTrue("先退回一页（原先在第 $atEnd / $total 页）", 5_000) {
                currentPage() == atEnd - 1
            }
        }

        val from = currentPage()
        compose.onAllNodesWithContentDescription("下一页")[0].performClick()
        awaitTrue("收起托盘后点「下一页」，页码应当从 $from 走到 ${from + 1}", 8_000) {
            currentPage() == from + 1
        }
    }
}
