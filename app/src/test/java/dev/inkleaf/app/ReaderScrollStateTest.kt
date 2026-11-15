package dev.inkleaf.app

import androidx.compose.foundation.MutatePriority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动状态的边界行为。
 *
 * 这个类看着简单，但它替掉的是 `ScrollState` —— 也就是说它一个人扛下了
 * 原先由 Compose 负责的三件事：夹紧、惯性动画的消耗反馈、屏幕外的同步回调。
 * 三件里任何一件算错，症状都不是"滚不动"这么明显：可能是滚到底要愣一下、
 * 可能是慢速拖动时画面一跳一跳、也可能是在某个偏移上永远差 1 像素。
 *
 * M36 补了**第四件事**：方向。上一版把符号写反了，而上面那三件事的断言
 * 没有一条与符号有关 —— 全部照绿。所以现在有两处单独钉方向：
 * `dispatchRawDelta`（滚轮 / 无障碍滚动）与 `ScrollScope.scrollBy`（拖动实际走的路）。
 */
class ReaderScrollStateTest {

    @Test
    fun `上界未知时滚不动`() {
        val s = ReaderScrollState()
        assertEquals("还没算上界，滚了会回到 0，不如不滚", 0f, s.dispatchRawDelta(-120f), 0f)
        assertEquals(0, s.value)
    }

    /**
     * 方向：手指往上划要看到**后面**的内容。
     *
     * 这是 M36 补的一刀。M35 那 13 条单测全都与符号无关 —— 夹紧、实耗、
     * 攒余量、撞边清零，把符号反过来照样全绿，而真机上"往上划看前文"。
     * 所以这里把方向单独写成一个用例，不让它再藏进别的断言里。
     */
    @Test
    fun `手指往上划偏移变大_往下划偏移变小`() {
        val s = ReaderScrollState()
        s.setMax(1_000)
        // `Modifier.scrollable` 传进来的是**手指位移**：往上划 y 变小，增量是负数。
        assertEquals("这一次手势全部被吃掉", -120f, s.dispatchRawDelta(-120f), 0f)
        assertEquals("往上划 = 往后读", 120, s.value)
        assertEquals(50f, s.dispatchRawDelta(50f), 0f)
        assertEquals("往下划 = 回到前文", 70, s.value)
    }

    @Test
    fun `滚到底就停在底上并如实报告消耗`() {
        val s = ReaderScrollState()
        s.setMax(100)
        assertEquals("离底还远，要多少给多少", -60f, s.dispatchRawDelta(-60f), 0f)
        assertEquals(
            "只剩 40，返回 60 会让惯性动画以为还能滑，滚到底之后多跑一段",
            -40f,
            s.dispatchRawDelta(-60f),
            0f,
        )
        assertEquals(100, s.value)
        assertFalse("已经到底", s.canScrollForward)
    }

    @Test
    fun `往回滚不会越过开头`() {
        val s = ReaderScrollState()
        s.setMax(100)
        s.dispatchRawDelta(-50f)
        assertEquals("离头还远", 30f, s.dispatchRawDelta(30f), 0f)
        assertEquals(20, s.value)
        assertEquals("只剩 20，要 30 只能给 20", 20f, s.dispatchRawDelta(30f), 0f)
        assertEquals(0, s.value)
        assertFalse("已经在开头", s.canScrollBackward)
    }

    @Test
    fun `不足一像素的拖动会攒起来而不是被扔掉`() {
        val s = ReaderScrollState()
        s.setMax(1000)
        // 慢速拖动一帧可能只给 0.3 px。逐次取整永远是 0 —— 画面看着像卡住，
        // 然后手指再动一下突然跳一格。攒四次就走一格。
        repeat(3) { assertEquals(0f, s.dispatchRawDelta(-0.3f), 0f) }
        assertEquals(0, s.value)
        assertEquals(-1f, s.dispatchRawDelta(-0.3f), 0f)
        assertEquals(1, s.value)
    }

    @Test
    fun `撞边之后余量作废`() {
        val s = ReaderScrollState()
        s.setMax(10)
        s.dispatchRawDelta(-10f)
        assertEquals(10, s.value)
        // 不足一格的点先攒着，这本身不该动。
        assertEquals(0f, s.dispatchRawDelta(-0.6f), 0f)
        assertEquals(10, s.value)
        // 攒够一格往上推 —— 撞到边界，余量（含这 0.6）一起作废：
        // 留着它，往回调的第一下会先"还债"，表现为手指动了画面不动。
        assertEquals(0f, s.dispatchRawDelta(-0.5f), 0f)
        assertEquals(10, s.value)
        assertEquals(1f, s.dispatchRawDelta(1f), 0f)
        assertEquals(9, s.value)
    }

    @Test
    fun `上界变小后当前偏移跟着夹回`() {
        val s = ReaderScrollState()
        s.setMax(1000)
        s.dispatchRawDelta(-800f)
        assertEquals(800, s.value)
        // 换字号后全书变短：还停在 800 就会滚进一片空白。
        s.setMax(500)
        assertEquals(500, s.value)
        assertEquals(500, s.maxValue)
    }

    @Test
    fun `偏移变化会同步通知观察者`() {
        val s = ReaderScrollState()
        s.setMax(100)
        val seen = mutableListOf<Int>()
        s.onValueChange = { seen += it }
        s.dispatchRawDelta(-30f)
        s.dispatchRawDelta(-30f)
        // 已经是 60，再滚 0 像素不该再通知一遍（每帧都回调会让 View 无谓重绘）。
        s.dispatchRawDelta(0f)
        assertEquals(listOf(30, 60), seen)
    }

    @Test
    fun `拖动走 scroll 作用域时方向同样正确`() = runBlocking {
        // 拖动实际走的是 `scroll {}` 里那个 ScrollScope，**不是** dispatchRawDelta。
        // 只改一处的话，真机上依然反着走 —— 而两条路径的符号各写一次，
        // 正是这类错误最容易活下来的地方。
        val s = ReaderScrollState()
        s.setMax(500)
        var consumed = 0f
        s.scroll(MutatePriority.UserInput) { consumed = scrollBy(-120f) }
        assertEquals("消耗量按手势方向报", -120f, consumed, 0f)
        assertEquals("往上划 = 往后读", 120, s.value)
    }

    @Test
    fun `瞬时跳转会夹到合法范围并打断惯性`() = runBlocking {
        val s = ReaderScrollState()
        s.setMax(200)
        s.scrollTo(500)
        assertEquals("超过上界", 200, s.value)
        assertFalse("跳完之后没有在滚", s.isScrollInProgress)
        s.scrollTo(-50)
        assertEquals(0, s.value)
        s.scrollTo(120)
        assertEquals(120, s.value)
    }

    @Test
    fun `上界为 0 时目标位置就是 0`() = runBlocking {
        val s = ReaderScrollState()
        // 还没排版：任何跳转都该安静地停在开头，不能抛，也不能留下一个待跳的请求。
        s.scrollTo(999)
        assertEquals(0, s.value)
    }

    @Test
    fun `滚到底时进度是满的`() {
        val s = ReaderScrollState()
        s.setMax(300)
        s.dispatchRawDelta(-300f)
        assertTrue("滚到底", s.value == s.maxValue)
        assertFalse(s.canScrollForward)
        assertTrue(s.canScrollBackward)
    }

    /**
     * 上界公式。它错了的症状是"最后几行永远滚不到" —— 界面照常能滚、
     * 进度条照常动，只有真读到全书最后的人才会发现少了一截。
     */
    @Test
    fun `上界等于内容高减去一屏`() {
        assertEquals(1_500_000 - 3_008, scrollMaxFor(1_500_000, 0, 3_008))
    }

    @Test
    fun `末尾那块托盘空白会抬高上界`() {
        // 滚到底时末行要停在托盘上沿，这一块必须算进可滚动范围里，
        // 否则最后一行正好被托盘盖住。
        assertEquals(1_500_000 - 3_008 + 146, scrollMaxFor(1_500_000, 146, 3_008))
    }

    @Test
    fun `内容比一屏还短时不能滚`() {
        // 负数上界会让 scrollBy 夹到一个负区间，偏移直接变成负数 —— 画面会跳到书外。
        assertEquals(0, scrollMaxFor(800, 146, 3_008))
        assertEquals(0, scrollMaxFor(3_008, 0, 3_008))
    }

    /**
     * 托盘上那句"读到哪儿了"。
     *
     * 滚动模式此前用的是 `safePageIndex` —— 那个值在滚动界面里从来不变，
     * 于是页码整章不动、进度线也整章不动。界面看上去完全正常，只有真的滚起来
     * 才发现"这个数字是死的"。所以这里逐条钉住两种形态。
     */
    @Test
    fun `分页模式的托盘给页码`() {
        assertEquals("第 3 / 232 页", trayPositionLabel(true, pageIndex = 2, pageCount = 232, ratio = 0f))
        assertEquals("第 1 / 232 页", trayPositionLabel(true, pageIndex = 0, pageCount = 232, ratio = 0.9f))
    }

    @Test
    fun `分页模式还没排版好时说排版中`() {
        assertEquals("排版中…", trayPositionLabel(true, pageIndex = 0, pageCount = 0, ratio = 0f))
    }

    @Test
    fun `滚动模式的托盘给百分比而不是页码`() {
        assertEquals("0%", trayPositionLabel(false, pageIndex = 0, pageCount = 232, ratio = 0f))
        assertEquals("37%", trayPositionLabel(false, pageIndex = 0, pageCount = 232, ratio = 0.376f))
        assertEquals("100%", trayPositionLabel(false, pageIndex = 0, pageCount = 232, ratio = 1f))
        // 页码在滚动模式里是无关量：给它一个离谱的值也不该影响结果。
        assertEquals("50%", trayPositionLabel(false, pageIndex = 9999, pageCount = 232, ratio = 0.5f))
    }

    @Test
    fun `百分比向下取整不会提前说满`() {
        // 99.6% 说成 100% 会让人以为已经到底了，于是不再往下读。
        assertEquals("99%", trayPositionLabel(false, 0, 232, 0.996f))
        // 越界的比例夹回来，绝不出现 "-3%" 或 "140%"。
        assertEquals("0%", trayPositionLabel(false, 0, 232, -0.03f))
        assertEquals("100%", trayPositionLabel(false, 0, 232, 1.4f))
    }
}
