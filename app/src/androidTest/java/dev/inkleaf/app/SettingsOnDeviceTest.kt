package dev.inkleaf.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.inkleaf.core.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页。
 *
 * ## 这一组测的是"设置页有没有说实话"
 *
 * 设置页最容易出的问题不是崩溃，是**它说的和它做的不是一回事**：
 *
 * - 滑杆上的数字变了，但预览没变 —— 用户以为调了，其实预览是张截图；
 * - "恢复默认"只复位了字号，行距还留在上一次的值；
 * - 选了"滚动"，设置里还是 SCROLL 之外的值（点击落到别的节点上）。
 *
 * 这三件都不是视觉问题，都能在语义树上钉死。所以这一组不看截图，只读语义树与
 * `container.settings`。
 *
 * ## 为什么每条用例结尾都要收拾
 *
 * 设备上的设置是**持久化**的。一条用例把字号留在 24 sp，下一条进来时基线就变了，
 * 失败信息还会指向完全无关的地方（M15 的 `排版面板` 那条就是这么长期挂红的）。
 * 所以每条用例在改变之前先记下原值，结束时还原，收尾也写成断言。
 */
class SettingsOnDeviceTest : DeviceHarness() {

    /** 从书架进设置。基类已经打开过一本书，先退回书架。 */
    private fun openSettings() {
        compose.runOnUiThread {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
        awaitTrue("回到书架（要找得到设置入口）", 20_000) {
            compose.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("设置").performClick()
        awaitTrue("设置页打开", 20_000) {
            compose.onAllNodesWithText("试读").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 试读区上报的"一行约 N 个字"。
     *
     * 不写死 N：它取决于设备宽度与字体度量，把这台机器的数字写成"永远成立的事实"
     * 是 M15 已经吃过一次的亏（`PdfOnDeviceTest` 里那条）。这里只用它做**相对**比较。
     */
    private fun charsPerLine(): Int = firstInt("一行约", "个字")

    private fun firstInt(prefix: String, suffix: String): Int {
        val pattern = Regex("""$prefix\s*(\d+)\s*$suffix""")
        for (node in compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes()) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text } ?: continue
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].toIntOrNull() ?: -1
        }
        return -1
    }

    private fun click(text: String) {
        // 分组可能落在折叠线以下：`performClick` 对不可见的节点会直接失败，
        // 而失败信息只说"没点到"，不说"它其实在屏幕外面"。
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    @Test
    fun `试读是真的：字号调大，一行能放的字真的变少`() {
        openSettings()

        val before = charsPerLine()
        assertTrue("试读区应当先报出一行能放几个字（读到 $before）", before > 0)

        click("特大")

        awaitTrue("字号变大之后，一行排下的字应当变少（原来 $before）", 20_000) {
            charsPerLine() in 1 until before
        }

        // 收尾：还原，别把 24 sp 留给后面的用例。
        click("恢复默认")
        awaitTrue("恢复默认之后字数回到 $before", 20_000) { charsPerLine() == before }
    }

    @Test
    fun `恢复默认把字号行距边距一起归位`() {
        openSettings()

        compose.onNodeWithContentDescription("字号")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        awaitTrue("滑杆认了 30 这个值（标签变成「字号 30 sp」）", 10_000) {
            compose.onAllNodesWithText("字号 30 sp", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        click("恢复默认")

        // 断的是"回到默认字号"，不是某个写死的字符串 —— 默认值在 ReaderSettings 里。
        val defaultFont = ReaderSettings().fontSizeSp
        awaitTrue("字号回到默认的 $defaultFont sp", 10_000) {
            compose.onAllNodesWithText("字号 ${defaultFont.toInt()} sp", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "设置里真的写回了默认值",
            defaultFont,
            container.settings.value.fontSizeSp,
            0.01f,
        )
    }

    @Test
    fun `选滚动：说明立刻换成人话，设置里也真的写进去了`() {
        openSettings()
        val original = container.settings.value.layoutMode

        click("滚动")

        awaitTrue("说明换成滚动模式那句", 10_000) {
            compose.onAllNodesWithText("连续往下滑", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        awaitTrue("容器里的设置变成 SCROLL", 10_000) {
            container.settings.value.layoutMode == LayoutMode.SCROLL
        }

        // 收尾：还原成进来之前的样子。
        compose.onNodeWithText(
            when (original) {
                LayoutMode.PAGED -> "翻页"
                LayoutMode.SCROLL -> "滚动"
                LayoutMode.LOCKED -> "固定字号"
            },
        ).performScrollTo().performClick()
        awaitTrue("还原成 $original", 10_000) {
            container.settings.value.layoutMode == original
        }
    }

    /**
     * 设置项的读屏名字。
     *
     * 三根滑杆如果只叫"滑杆"，TalkBack 会念一个孤零零的数字，用户不知道这个数字是
     * 字号还是行距。这条用例把"每根滑杆都有名字"钉住 —— 它也是 M15 那次
     * "用下标选滑杆选错对象"的事故的同一条根因。
     */
    @Test
    fun `三根排版滑杆都有自己的名字`() {
        openSettings()
        for (name in listOf("字号", "行距", "页边距")) {
            assertTrue(
                "滑杆「$name」应当能被读屏单独指认：${uiSnapshot()}",
                compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
