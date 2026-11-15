package dev.inkleaf.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 九宫格命中测试（M39）。
 *
 * 这一层能测的原因很朴素：把"点在哪 ≈ 干什么"抽成了一个只吃坐标的函数。
 * 手势本身（是不是手指、有没有被 View 消费掉）只能在设备上验，
 * 但"正中间那一格换控件、左右两列翻页、中间列上下两格不动"这条规则
 * 可以在这里一格格点名，不用等真机。
 */
class ReaderTapGridTest {

    private val w = 1080f
    private val h = 1920f

    /** 九个格子的中心点 → 期望的动作。 */
    @Test
    fun `九个宫格各归各的动作`() {
        val expected = listOf(
            // 第一行：左翻 / 不动 / 右翻
            Triple(1, 1, ReaderTapZone.TURN_BACK),
            Triple(2, 1, ReaderTapZone.NONE),
            Triple(3, 1, ReaderTapZone.TURN_FORWARD),
            // 第二行：左翻 / 切控件 / 右翻
            Triple(1, 2, ReaderTapZone.TURN_BACK),
            Triple(2, 2, ReaderTapZone.TOGGLE_CHROME),
            Triple(3, 2, ReaderTapZone.TURN_FORWARD),
            // 第三行：左翻 / 不动 / 右翻
            Triple(1, 3, ReaderTapZone.TURN_BACK),
            Triple(2, 3, ReaderTapZone.NONE),
            Triple(3, 3, ReaderTapZone.TURN_FORWARD),
        )
        expected.forEach { (col, row, zone) ->
            val x = w * (col - 0.5f) / 3f
            val y = h * (row - 0.5f) / 3f
            assertEquals(
                "第 ${row} 行第 $col 列（$x, $y）",
                zone,
                readerTapZone(x, y, w, h),
            )
        }
    }

    /**
     * 中间列的上、下两格是"有意留白"。
     *
     * 这一条单独写，是因为它看起来最像 bug：点页头点页尾都该有反应才对。
     * 但派给它们任何动作（翻页 / 切控件）都会在"顺手一碰"时误触发 ——
     * 真正需要的动作在左右两列与正中间那一格上。
     */
    @Test
    fun `中间列上下两格不响应`() {
        val cx = w / 2
        assertEquals(ReaderTapZone.NONE, readerTapZone(cx, h * 0.05f, w, h))
        assertEquals(ReaderTapZone.NONE, readerTapZone(cx, h * 0.95f, w, h))
    }

    /** 左右两列不分行：拇指落在屏幕下半部也该能翻页，不必抬到中线。 */
    @Test
    fun `左右两列从上到下都能翻页`() {
        val left = w * 0.1f
        val right = w * 0.9f
        listOf(0.02f, 0.3f, 0.5f, 0.7f, 0.98f).forEach { ratio ->
            assertEquals(
                "左侧 y=${ratio}",
                ReaderTapZone.TURN_BACK,
                readerTapZone(left, h * ratio, w, h),
            )
            assertEquals(
                "右侧 y=${ratio}",
                ReaderTapZone.TURN_FORWARD,
                readerTapZone(right, h * ratio, w, h),
            )
        }
    }

    /**
     * 落在 1/3 线上算中间列。
     *
     * 边界往"少做事"的方向靠：正好压在分界线上时，宁可什么也不做，
     * 也不要在用户以为点的是中间时突然翻页。
     */
    @Test
    fun `正压分界线时算中间列`() {
        assertEquals(ReaderTapZone.NONE, readerTapZone(w / 3f, h * 0.1f, w, h))
        assertEquals(ReaderTapZone.NONE, readerTapZone(w * 2f / 3f, h * 0.1f, w, h))
        assertEquals(ReaderTapZone.TOGGLE_CHROME, readerTapZone(w / 3f, h / 2f, w, h))
    }

    /** 中间格的上下边界：压线算中间格。 */
    @Test
    fun `中间格上下边界算中间格`() {
        assertEquals(ReaderTapZone.TOGGLE_CHROME, readerTapZone(w / 2f, h / 3f, w, h))
        assertEquals(ReaderTapZone.TOGGLE_CHROME, readerTapZone(w / 2f, h * 2f / 3f, w, h))
    }

    /** 尺寸还没量出来时不做任何判断 —— 否则会拿 0 当分母算出随机结果。 */
    @Test
    fun `尺寸为 0 时不响应`() {
        assertEquals(ReaderTapZone.NONE, readerTapZone(0f, 0f, 0f, 0f))
        assertEquals(ReaderTapZone.NONE, readerTapZone(10f, 10f, 0f, 100f))
        assertEquals(ReaderTapZone.NONE, readerTapZone(10f, 10f, 100f, 0f))
    }

    /**
     * 整块正文里**只有**中间那一格能切控件。
     *
     * 用网格扫一遍而不是只点九个中心：手指出手的位置是连续的，
     * "中间一格会不会被算成翻页区"这种错，只在边界附近才露出来。
     */
    @Test
    fun `扫一遍全屏_切控件的区域正好是中间那一格`() {
        val steps = 30
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = w * (i + 0.5f) / steps
                val y = h * (j + 0.5f) / steps
                val zone = readerTapZone(x, y, w, h)
                val inCenterCell = x > w / 3f && x < w * 2f / 3f &&
                    y > h / 3f && y < h * 2f / 3f
                assertEquals(
                    "($x, $y) 的判定与中间宫格不一致",
                    inCenterCell,
                    zone == ReaderTapZone.TOGGLE_CHROME,
                )
            }
        }
    }
}
