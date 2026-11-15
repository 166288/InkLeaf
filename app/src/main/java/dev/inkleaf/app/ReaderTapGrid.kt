package dev.inkleaf.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/**
 * 正文上一次点按落在九个宫格里的哪一个。
 *
 * ## 为什么要点名"格"，而不是只说"左边/右边/中间"
 *
 * M31 的划分只有三列：左 1/3 上一页、右 1/3 下一页、**中间整列**切换控件。
 * 中间列是从屏幕顶端通到底的一条竖带，于是在靠近页头、页尾的地方点一下 ——
 * 那多半是"想把页面顶上去"或"随手一碰"—— 控件就跳出来了。
 * 切到九宫格之后，"切换控件"只归**正中间那一格**（宽高各 1/3），
 * 上下两格还给纸：点它们什么也不发生。
 *
 * ## 左右两列不分行
 *
 * 翻页是要"够得着"的：拇指通常落在屏幕下半部，如果只有中间那一行的左右两格能翻页，
 * 读完一页要先把手指抬到屏幕中线附近。所以左右两列**整列**都是翻页区，
 * 九宫格在这里退化成三列 —— 这是有意的，不是漏写。
 *
 * ## 停用区不是缺陷
 *
 * 中间列的上、下两格什么都不做，看起来像"死区"。但它们是**必要的**：
 * 那两格里任何动作都会被读成误触（尤其是竖划），给它们派活只会让人
 * 在"我什么也没想干"的时候翻页或者弹出工具栏。
 *
 * @param x 点击位置，正文坐标系（像素）。
 * @param y 同上。
 * @param width 正文区宽度；`<= 0` 视为无法判定。
 * @param height 正文区高度。
 */
internal enum class ReaderTapZone {
    /** 正中间那一格：切换顶栏与托盘。 */
    TOGGLE_CHROME,

    /** 左列：上一页 / 往回滚一屏。 */
    TURN_BACK,

    /** 右列：下一页 / 往后滚一屏。 */
    TURN_FORWARD,

    /** 中间列的上、下两格，或尺寸还没量出来：什么都不做。 */
    NONE,
}

/**
 * 把一次点按换算成 [ReaderTapZone]。
 *
 * 边界取值：正好落在 1/3 线上算中间列（往"少做事"的方向靠），
 * 正好落在中间格的上下边界算中间格。
 */
internal fun readerTapZone(x: Float, y: Float, width: Float, height: Float): ReaderTapZone {
    if (width <= 0f || height <= 0f) return ReaderTapZone.NONE
    val thirdW = width / 3f
    val column = when {
        x < thirdW -> -1
        x > thirdW * 2f -> 1
        else -> 0
    }
    // 左右两列整列都翻页（见类注释"左右两列不分行"）。
    if (column < 0) return ReaderTapZone.TURN_BACK
    if (column > 0) return ReaderTapZone.TURN_FORWARD
    val thirdH = height / 3f
    return if (y >= thirdH && y <= thirdH * 2f) ReaderTapZone.TOGGLE_CHROME else ReaderTapZone.NONE
}

/**
 * 只认**手指**的点按：按下 → 抬起之间没有移动，才交给 [onTap]。
 *
 * ## 为什么不用 `detectTapGestures`
 *
 * 它不告诉你这一下是谁按的。而"谁按的"在本应用里是一条硬规矩：
 * 笔尖落在正文上是写字，写字的间隙不该顺手把工具栏收起来；
 * 唤出/收起控件是**手指**的动作。手写笔模式下这条规矩尤其重要 ——
 * 那是用户特意开了"只用手写笔书写"，手指的全部意义就是操作界面。
 *
 * 已经被子 View 消费掉的按下（比如书写时手指落笔、笔尖落笔）不会到达这里：
 * [awaitFirstDown] 要求 `requireUnconsumed`，所以"正在写字时点一下中间会切控件"
 * 这种事不会发生。
 */
internal fun Modifier.readerTap(
    key: Any?,
    onTap: (Offset, IntSize) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        // 笔、鼠标（含手写笔悬停）都不是"点一下正文"这个手势。
        if (!down.type.isTouchLike) return@awaitEachGesture
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        onTap(up.position, size)
    }
}

/**
 * 触摸屏报的是 [PointerType.Touch]（Compose 1.11 之前的名字是 `Finger`）；
 * 部分机型/输入法会报 [PointerType.Unknown]。两者都按手指对待，
 * 否则"点一下没反应"会变成一台设备一个样。
 */
private val PointerType.isTouchLike: Boolean
    get() = this == PointerType.Touch || this == PointerType.Unknown
