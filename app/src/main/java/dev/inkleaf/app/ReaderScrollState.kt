package dev.inkleaf.app

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 滚动模式的滚动状态：**自己持有偏移**，不依赖被测内容把 View 撑高。
 *
 * ## 为什么不用 `rememberScrollState()` + `Modifier.verticalScroll`
 *
 * `verticalScroll` 要求内容比视口高，靠"内容有多高"算出可滚动范围 ——
 * 于是 `DocumentInkView` 必须报出整本书的高度：17001 行 ≈ **155 万像素**。
 * hwui 要为这么高的 View 建显示列表与裁剪区，真机上直接崩在原生层
 * （Scudo 堆校验失败 / `std::vector` length_error，崩点每次不同）。
 *
 * 这个类把"能滚多远"从"View 有多高"里摘出来：可滚动范围由排版结果算出
 * （正文总高 − 视口高），View 永远只有一屏高，滚动时只是换一段内容来画。
 * 这就是纵向虚拟化。
 *
 * ## 与 `ScrollState` 的关系
 *
 * 只是实现了同一个 `ScrollableState` 接口，所以 `Modifier.scrollable` 的
 * 拖动、惯性、边缘回弹照旧由 Compose 提供 —— 这里不重写手势，只回答
 * "还能滚多少"与"实际滚了多少"。
 *
 * ## 单位
 *
 * [value] 是**文档坐标**里的像素（0 = 正文第一行的顶边对齐视口上沿）。
 * 与分页模式的 `windowTopPx` 同一个口径，两套坐标不需要来回换算。
 */
internal class ReaderScrollState(initial: Int = 0) : ScrollableState {

    var value: Int by mutableIntStateOf(initial)
        private set

    /** 可滚动范围的上界。由调用方按排版结果算出（见 `ScrollBody`）。 */
    var maxValue: Int by mutableIntStateOf(0)
        private set

    private var inProgress by mutableStateOf(false)

    /**
     * 偏移变化时的同步回调（不走重组）。
     *
     * 滚动必须**当场**告诉 View：走重组再传进去会晚一帧，屏幕上就是
     * "画面追不上手指"。这里在 `dispatchRawDelta` 的调用栈里直接调。
     */
    var onValueChange: ((Int) -> Unit)? = null

    /**
     * 不足一像素的余量。
     *
     * 惯性动画与慢速拖动一次可能只给 0.4 像素。逐次四舍五入会一直得到 0，
     * 画面看着像"卡住不走"，然后突然跳一格。攒着，够一格再走。
     */
    private var fraction = 0f

    private val mutex = MutatorMutex()

    override val isScrollInProgress: Boolean get() = inProgress

    override val canScrollForward: Boolean get() = value < maxValue

    override val canScrollBackward: Boolean get() = value > 0

    /** 排版换过、视口变过时重设上界。当前偏移越界就跟着夹回来。 */
    fun setMax(newMax: Int) {
        val clamped = newMax.coerceAtLeast(0)
        if (clamped == maxValue) return
        maxValue = clamped
        if (value > clamped) apply(clamped)
    }

    /**
     * 手势增量为什么是**负**的（M36 实测，M35 写反了）
     *
     * [value] 是"视口顶端在文档里的纵坐标"：变大就是往后读。
     * 而 `Modifier.scrollable` 传进来的是**手指的位移** —— 手指往上划，y 变小，
     * 增量是负数。往上划要看后面的内容，也就是 value 要变大。两者符号相反。
     *
     * 写反了的症状非常具体：**往上划看到的是前文，往下划往后跑**。它在
     * M35 的 13 条单测里一条都测不出来 —— 夹紧、实耗、攒余量、撞边清零
     * 全都与符号无关。这不是"测试不够多"，而是没有一条断言问过"方向对不对"。
     * 现在 `ReaderScrollStateTest` 里那条"手指往上划 → 偏移变大"就是补这一刀。
     *
     * 返回值也要换回手势的方向：调用方问的是"这一个手势增量里消耗了多少"，
     * 拿偏移量去答（方向相反）会让惯性动画以为还能滑，滚到底后多跑一段。
     */
    override fun dispatchRawDelta(delta: Float): Float = -scrollByPixels(-delta)

    /**
     * 瞬时跳到某个位置（目录跳转、恢复阅读位置、音量键滚一屏都用它）。
     *
     * 用 [MutatePriority.UserInput]：比惯性动画的默认优先级高，会**打断**
     * 正在跑的 fling —— 否则目录跳过去之后，上一次滑动的惯性还会把它接着推走。
     *
     * 参数是**段落坐标**（要跳到文档的哪个 y），不是增量，所以这里不翻符号。
     */
    suspend fun scrollTo(target: Int) {
        val clamped = target.coerceIn(0, maxValue)
        if (clamped == value) return
        scroll(MutatePriority.UserInput) {
            scrollByPixels((clamped - value).toFloat())
        }
    }

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit,
    ) {
        mutex.mutate(scrollPriority) {
            inProgress = true
            try {
                object : ScrollScope {
                    // 手势增量 → 偏移，**符号要反过来**（见 [dispatchRawDelta]）。
                    // 返回值同样换回手势方向：惯性动画照它判断"还能不能继续"。
                    override fun scrollBy(pixels: Float): Float = -scrollByPixels(-pixels)
                }.block()
            } finally {
                inProgress = false
                fraction = 0f
            }
        }
    }

    /**
     * 按像素滚，返回**实际**消耗的像素。
     *
     * 返回值必须诚实：惯性动画靠它判断"还能不能继续"，返回需求值而不是实耗值时，
     * 撞到底之后动画还会空跑一段（表现为滚到底再松手，界面要愣一下才停）。
     */
    private fun scrollByPixels(pixels: Float): Float {
        if (pixels == 0f || maxValue <= 0) return 0f
        fraction += pixels
        val step = if (fraction > 0f) floor(fraction) else ceil(fraction)
        if (step == 0f) return 0f
        val before = value
        val next = (before + step.toInt()).coerceIn(0, maxValue)
        if (next == before) {
            // 撞到边界：余量作废。留着它，往回滚的第一下会先"还债"，
            // 表现为手指动了画面不动。
            fraction = 0f
            return 0f
        }
        val consumed = (next - before).toFloat()
        fraction -= consumed
        apply(next)
        return consumed
    }

    private fun apply(next: Int) {
        if (next == value) return
        value = next
        onValueChange?.invoke(next)
    }
}

/**
 * 记住一个滚动状态。
 *
 * @param key 换值就等于换一个状态。调用方传**书的 id** —— 阅读位置是"这本书读到哪"，
 *        不是"这个界面滚到哪"。不传时按"这个界面的这一次"算（单测与预览用）。
 */
@Composable
internal fun rememberReaderScrollState(key: Any? = Unit): ReaderScrollState =
    remember(key) { ReaderScrollState() }

/**
 * 滚动模式的可滚动上界（文档坐标像素）。
 *
 * 抽成纯函数是为了能测 —— 它错了的症状特别隐蔽：界面照常能滚、进度条也照常动，
 * 只是**最后几行永远滚不到**。这类"少一截"的问题肉眼很难发现，除非有人真的
 * 读到了全书最后。
 *
 * @param contentHeightPx 文档在纵向占的总高：正文总高 + 上下边距。
 * @param trayPx 末尾额外留出的高度（迷你托盘）。滚到底时末行停在托盘上沿，
 *        否则托盘正盖在最后一行上，读不到。
 * @param viewportPx 视口高度（一屏）。
 */
internal fun scrollMaxFor(contentHeightPx: Int, trayPx: Int, viewportPx: Int): Int =
    (contentHeightPx + trayPx - viewportPx).coerceAtLeast(0)

/**
 * 托盘上那句"读到哪儿了"。
 *
 * 分页模式给页码：它精确，而且对得上手里那张纸。
 * 滚动模式给百分比：那里没有"页"这个单位，硬塞一个页码就是给用户一个随时会说谎的数字
 * （滚动时页码一直是进书那一页，滚完整章都不动）。
 *
 * 抽成纯函数是为了能测 —— 这句话的两种形态各自只有一个正确写法，
 * 而"滚动模式显示了页码"这种错，肉眼看上去完全正常。
 */
internal fun trayPositionLabel(
    paged: Boolean,
    pageIndex: Int,
    pageCount: Int,
    ratio: Float,
): String = when {
    paged && pageCount <= 0 -> "排版中…"
    paged -> "第 ${pageIndex + 1} / $pageCount 页"
    // 向下取整：99.6% 说成 99% 比说成 100% 好 —— 后者会让人以为已经到底了。
    else -> "${(ratio.coerceIn(0f, 1f) * 100).toInt()}%"
}
