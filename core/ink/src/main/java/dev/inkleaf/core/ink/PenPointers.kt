package dev.inkleaf.core.ink

import android.view.MotionEvent

/**
 * 多指针事件里"这一笔到底该跟谁"。
 *
 * 抽出来的直接原因是一个真实缺陷：两个 View 都拿 `event.x` / `event.y` 当坐标，
 * 而那等于 `getX(0)` / `getY(0)` —— **第 0 号指针不一定是笔**。
 * `stylusOnly` 的默认值是 false（手指也能写），于是手掌先落屏时，
 * 手掌成了 pointer 0、笔是 pointer 1，这一笔的坐标全程来自那只**不动的手掌**，
 * 屏幕上的线就停在原地。用户看到的是"写快了笔迹会断"。
 *
 * 判据集中在这里而不是各写一遍：两个 View（重排 / PDF）的触摸逻辑本来就该
 * 对同一串事件给出同一个答案，分开写迟早会分家。
 *
 * 全部方法都是纯函数（只读事件），因此单测可以直接构造 [MotionEvent] 验它。
 */
internal object PenPointers {

    /** 没有活动指针。用 `MotionEvent` 自己的常量，避免与"指针下标 -1"混淆。 */
    const val NONE: Int = MotionEvent.INVALID_POINTER_ID

    /** 第 [index] 号指针是不是手写笔。`TOOL_TYPE_ERASER` 是笔尾的橡皮头，也算笔。 */
    fun isStylus(event: MotionEvent, index: Int): Boolean {
        if (index < 0 || index >= event.pointerCount) return false
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** 第一支笔的指针**下标**；没有笔时 -1。 */
    fun firstStylusIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            if (isStylus(event, i)) return i
        }
        return -1
    }

    /**
     * 一次手势开始时要跟住的指针 id：**优先手写笔**，否则第一根。
     *
     * 为什么不是"永远跟 0 号"：见本文件顶部。为什么不是"永远只认笔"：
     * 设备可能没有笔、用户也可能用手指写（方案 §6.2 允许）。
     */
    fun initialId(event: MotionEvent): Int {
        if (event.pointerCount == 0) return NONE
        val stylus = firstStylusIndex(event)
        return event.getPointerId(if (stylus >= 0) stylus else 0)
    }
}
