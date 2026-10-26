package dev.inkleaf.core.common

import android.util.Log

/**
 * 日志。
 *
 * 为什么还要包一层而不是直接用 `android.util.Log`：
 *   1. 统一 tag 前缀与开关，release 下可整体静音（Log.isLoggable 由系统过滤，不改代码）；
 *   2. 关键路径（书写、分页、锚点解析）需要**低开销的耗时打点**，
 *      但绝不能因为打点本身掉帧。这里所有打点都是"先判断开关，再拼字符串"。
 *
 * 刻意不做的事：不上报、不落盘、不采样。本地优先应用没有遥测。
 */
object AppLog {

    /**
     * `@PublishedApi internal` 而不是 `private`：
     * [trace] 是 public inline，Kotlin 不允许它触达非 public API
     * （内联后调用方会直接引用这些符号，private 在调用方不可见）。
     * 把它们标成 @PublishedApi 是对这条规则的标准处置，
     * 比"把 trace 改成非 inline"更合适 —— 后者会让每次打点都多分配一个 lambda，
     * 而打点恰恰是出现在渲染热点附近的东西。
     */
    @PublishedApi
    internal const val TAG_PREFIX = "InkLeaf"

    /** 由 Application 在启动时按 BuildConfig.DEBUG 设置。 */
    @Volatile
    var enabled: Boolean = false

    /** 性能打点开关。与 [enabled] 分开：日志可以关，但性能问题要能单独打开复现。 */
    @Volatile
    var perfEnabled: Boolean = false

    fun d(tag: String, message: () -> String) {
        if (enabled) Log.d(tag(tag), message())
    }

    fun i(tag: String, message: () -> String) {
        if (enabled) Log.i(tag(tag), message())
    }

    fun w(tag: String, message: () -> String, tr: Throwable? = null) {
        if (enabled) Log.w(tag(tag), message(), tr)
    }

    fun e(tag: String, message: () -> String, tr: Throwable? = null) {
        // 错误日志不看开关：出错信息必须留下。
        Log.e(tag(tag), message(), tr)
    }

    /** 执行 [block] 并记录耗时。返回 block 的结果，不吞异常。 */
    inline fun <T> trace(tag: String, label: String, block: () -> T): T {
        if (!perfEnabled) return block()
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val ms = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            Log.d(tag(tag), "$label 耗时 ${"%.2f".format(ms)} ms")
        }
    }

    @PublishedApi
    internal fun tag(tag: String) = "$TAG_PREFIX/$tag"
}
