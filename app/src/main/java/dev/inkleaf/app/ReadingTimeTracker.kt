package dev.inkleaf.app

/**
 * 把"阅读界面在前台待着"的时间切成一段一段。
 *
 * 规则只有三条：
 *
 *  1. 只统计**前台**时间。切后台、锁屏、被别的应用盖住时不算阅读 ——
 *     否则"把书打开放在桌上一下午"会变成一下午的阅读时长。
 *  2. 太短的一段丢掉（默认 3 秒）。刚点开就退出的那一下会进库的话，
 *     统计页的"今天读过"会被无数次误触点亮。
 *  3. 时长用**单调时钟**（`elapsedRealtime`）算，墙钟只用来记"这段从哪天开始"。
 *     用户改系统时间不该让"读了 5 分钟"变成 3 小时或负数。
 *
 * 时钟由调用方注入，因此上面三条都能在 JVM 上测，不需要模拟器。
 */
class ReadingTimeTracker(
    private val onSegment: (startedAt: Long, endedAt: Long, durationMs: Long) -> Unit,
    private val elapsedMs: () -> Long,
    private val wallMs: () -> Long,
    private val minDurationMs: Long = MIN_DURATION_MS,
) {
    private var activeSinceElapsed: Long? = null
    private var activeSinceWall: Long = 0L

    val isActive: Boolean get() = activeSinceElapsed != null

    /** 进入前台。重复调用不会重开一段（`ON_START` 可能来自观察者注册与显式检查两次）。 */
    fun enterForeground() {
        if (activeSinceElapsed != null) return
        activeSinceElapsed = elapsedMs()
        activeSinceWall = wallMs()
    }

    /**
     * 离开前台。返回 true 表示这一段被提交了（达到最短时长）。
     *
     * 没有开始过时什么都不做：`ON_STOP` 可能在 `ON_START` 之前到达
     * （观察者注册时机），那不代表"读了一段 0 毫秒"。
     */
    fun leaveForeground(): Boolean {
        val since = activeSinceElapsed ?: return false
        activeSinceElapsed = null
        val duration = (elapsedMs() - since).coerceAtLeast(0L)
        if (duration < minDurationMs) return false
        val startedAt = activeSinceWall
        // 结束时间按"开始 + 单调时长"算：墙钟在阅读期间被改过时，
        // 这个值仍然与读过的时长自洽（日期归属本来就只看 startedAt）。
        onSegment(startedAt, startedAt + duration, duration)
        return true
    }

    companion object {
        const val MIN_DURATION_MS: Long = 3_000
    }
}
