package dev.inkleaf.app

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.inkleaf.core.model.PageGeometry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读版面到底把多少屏幕给了正文（M34）。
 *
 * 这条测试不是为了验算 `PageGeometry`（它自己有单测），而是为了钉住**调用方的口径**：
 * 阅读界面传给 `solve` 的那两个让位值，必须只是"呼吸"，不能是"控件的身高"。
 *
 * M33 就是这么写错的 —— 顶部让位塞了 `READER_TOP_BAR_HEIGHT + READER_TOP_BAR_GAP`
 * （58 dp），底部又留着托盘常驻的高度（52 dp），于是真机上一页 492 px（16% 屏高）
 * 是空的，而那块地方 99% 的时间什么都没有。单测全绿，因为没人量过这个比例。
 *
 * 数字取真机：小米 25079RPDCC，1880×3008、450 dpi（density 2.8125），
 * 状态栏 92 px、手势条 63 px —— 三项都是从截图与 `wm size/density` 读出来的，
 * 不是估的。
 */
class ReaderLayoutBudgetTest {

    /** 450 dpi → density 2.8125。 */
    private val density = Density(2.8125f)

    private fun px(value: Dp): Float = with(density) { value.toPx() }

    private val screenHeightPx = 3008
    private val statusBarPx = 92f
    private val navBarPx = 63f

    /** 正文区：骨架层先扣掉安全区与手势条，剩下的才是 `PageGeometry` 的输入。 */
    private val areaHeightPx = (screenHeightPx - statusBarPx - navBarPx).toInt()

    private fun metrics() = PageGeometry.solve(
        widthPx = 1880,
        heightPx = areaHeightPx,
        // 4 mm @ 450 dpi ≈ 71 px。纵向已经不看它了，这里只为贴近真实调用。
        userMarginPx = 71f,
        textSizePx = 48f,
        density = density.density,
        topReservePx = px(READER_TOP_BREATH),
        bottomReservePx = px(READER_BOTTOM_GAP),
    )

    @Test
    fun `正文框至少占屏高的九成`() {
        val ratio = metrics().contentHeightPx / screenHeightPx.toFloat()
        assertTrue(
            "正文框只占屏高 ${(ratio * 100).toInt()}%；M34 之后不该低于 90%" +
                "（M33 的口径是 83%，一页白扔 492 px）",
            ratio >= 0.90f,
        )
    }

    @Test
    fun `纵向只留呼吸，不留控件的位置`() {
        val total = px(READER_TOP_BREATH) + px(READER_BOTTOM_GAP)
        assertTrue(
            "上下呼吸合计 ${total.toInt()}px，超过 20dp 就说明又在给浮层让位了",
            total <= px(20.dp),
        )
    }

    @Test
    fun `顶栏是盖在正文上的，不是被让出来的`() {
        // 这两个常量的大小关系就是 M34 的口径本身：呼吸远小于顶栏高度，
        // 顶栏才会真的压到首行上。若哪天有人把呼吸调回 52dp，这条会先红。
        assertTrue(
            "顶部呼吸 ${READER_TOP_BREATH} 不该达到顶栏高度 ${READER_TOP_BAR_HEIGHT}",
            READER_TOP_BREATH < READER_TOP_BAR_HEIGHT,
        )
    }
}
