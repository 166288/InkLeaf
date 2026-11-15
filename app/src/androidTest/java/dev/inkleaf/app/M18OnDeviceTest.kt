package dev.inkleaf.app

import android.os.SystemClock
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M18：主题拆成"书里的"和"外面的"两件事。
 *
 * 这一版改的是一个**语义**，不是两个开关的位置：以前「阅读主题」同时管书里的纸和
 * 全应用的明暗，理由是"整套界面同一套明暗才不割裂"。实际用起来恰好相反 ——
 * 书架留浅色、只在夜里把书换成深色纸，是很常见的用法。
 *
 * 所以这里断的不是"按钮在不在"，而是：
 *
 * - 在**阅读界面**换纸色，屏幕上那张纸真的换了颜色（量帧缓冲的主色，不读设置值）；
 * - 在**设置页**改界面明暗，书里的纸**一个字节都没动**；
 * - 设置页里**没有**阅读主题色卡（它归阅读界面管）。
 */
@RunWith(AndroidJUnit4::class)
class M18OnDeviceTest : DeviceHarness() {

    @Test
    fun `排版面板里换纸色，正文真的换纸`() {
        val original = container.settings.value.readerTheme
        try {
            // 默认是「跟随系统」，系统浅色时解析成纸张。换成羊皮纸之后纸色一定不同 ——
            // 否则这条用例证明不了任何事，所以先把"起点不是羊皮纸"这件事钉住。
            assertNotEquals(ReaderTheme.SEPIA, original.resolve(false))
            val before = dominantColor("m18-paper-before")

            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("排版（主题 / 字号 / 行距 / 边距）").performClick()
            compose.waitForIdle()

            // 色卡按无障碍描述点：卡下面还有一行同样的文字（label），
            // 按文字点会命中两个节点。
            awaitTrue("主题色卡出现在排版面板里", 20_000) {
                compose.onAllNodesWithContentDescription("阅读主题：羊皮纸")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("阅读主题：羊皮纸").performClick()

            awaitTrue("设置里记下了羊皮纸", 20_000) {
                container.settings.value.readerTheme == ReaderTheme.SEPIA
            }

            compose.onNodeWithText("完成").performClick()
            compose.waitForIdle()
            // 面板退场有动画，退场途中截图会被 scrim 压暗。
            SystemClock.sleep(800)

            val after = dominantColor("m18-paper-after")
            assertTrue(
                "换纸后整屏的主色仍是 #%06X，与羊皮纸 #%06X 差得太远 —— " +
                    "设置改了但书里的纸没换".format(after, ReaderTheme.SEPIA.paperArgb and 0xFFFFFF),
                closeTo(after, ReaderTheme.SEPIA.paperArgb and 0xFFFFFF),
            )
            assertNotEquals("换纸前后主色一样，说明纸根本没变", before, after)
        } finally {
            runBlocking { container.updateSettings { it.copy(readerTheme = original) } }
        }
    }

    @Test
    fun `改界面外观不动书里的纸`() {
        val bookTheme = container.settings.value.readerTheme
        val originalUi = container.settings.value.uiDarkMode
        try {
            // 从阅读页回到书架，再进设置。走的都是用户真的会走的两步。
            compose.onNodeWithContentDescription("返回书架").performClick()
            awaitTrue("回到书架", 20_000) {
                compose.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("设置").performClick()
            awaitTrue("设置页打开", 20_000) {
                compose.onAllNodesWithText("界面外观").fetchSemanticsNodes().isNotEmpty()
            }

            // 设置页不该再有阅读主题色卡：它管的是"外面"，纸归阅读界面的排版面板。
            assertTrue(
                "设置页里还能找到阅读主题色卡 —— 主题又被放回设置页了",
                compose.onAllNodesWithContentDescription(
                    "阅读主题：",
                    substring = true,
                ).fetchSemanticsNodes().isEmpty(),
            )

            // 设置页比一屏长：「界面外观」在「文字」（三根滑杆）之后，
            // 平板上要滚下去才看得见。不先滚到它，performClick 会把点击注入到
            // 屏幕外的坐标 —— 不报错，但什么都不会发生（第一次就是这么红的）。
            compose.onNodeWithText("深色").performScrollTo().performClick()
            awaitTrue("界面明暗记下了深色", 20_000) {
                container.settings.value.uiDarkMode == DarkModePreference.DARK
            }

            // 这一条是这次拆分的全部意义：改外面，里面不动。
            assertEquals(
                "改界面外观把书里的纸也带着改了 —— 两者又耦合上了",
                bookTheme,
                container.settings.value.readerTheme,
            )
        } finally {
            runBlocking { container.updateSettings { it.copy(uiDarkMode = originalUi) } }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /**
     * 整屏**出现次数最多**的颜色。
     *
     * 阅读界面的纸色是面积最大的那一块（正文只占一小部分），所以众数就是纸色。
     * 不按固定坐标取样：那里可能是顶栏、翻页条、或者某一行正文，改一次排版就漂了。
     */
    private fun dominantColor(name: String): Int {
        val bitmap = screenshot(name)
        val counts = HashMap<Int, Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val rgb = bitmap.getPixel(x, y) and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
                x += 5
            }
            y += 5
        }
        return counts.maxByOrNull { it.value }!!.key
    }

    /** 三个通道都不差超过 8：截图会过一遍色彩管理，不该要求逐字节相等。 */
    private fun closeTo(actual: Int, expected: Int): Boolean {
        val dr = kotlin.math.abs(((actual shr 16) and 0xFF) - ((expected shr 16) and 0xFF))
        val dg = kotlin.math.abs(((actual shr 8) and 0xFF) - ((expected shr 8) and 0xFF))
        val db = kotlin.math.abs((actual and 0xFF) - (expected and 0xFF))
        return dr <= 8 && dg <= 8 && db <= 8
    }
}
