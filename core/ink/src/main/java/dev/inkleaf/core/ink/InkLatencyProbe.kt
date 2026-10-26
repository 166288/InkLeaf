package dev.inkleaf.core.ink

import android.util.Log

/**
 * 跟手延迟与单帧绘制成本的埋点。
 *
 * 两个数字回答两个不同的问题：
 *
 * - **`p50/p95`（输入 → 绘制）**：`MotionEvent.eventTime` 到这一帧 `onDraw` 被调用。
 *   它是"笔尖动一下，到屏幕上更新"里我们**能改**的那一段（不含屏扫描出）。
 *   这个数字大，说明主线程排队了 —— 也就是"线追不上笔"。
 * - **`drawN`（单帧耗时）**：这一帧 `View.draw` 花了多久。
 *   它区分"排队的因"和"排队的果"：单帧 3ms 而 p95 40ms，说明瓶颈不在绘制本身；
 *   单帧就 20ms，那不管怎么调度都来不及。
 *
 * 采样有上限（一笔写很长时不至于把内存吃掉），一笔结束时打一行并清空 ——
 * 于是日志是一笔一行，好读也好算。
 */
internal class InkLatencyProbe(private val label: String) {

    private var inputMs: Long = 0L
    private var awaitingDraw: Boolean = false
    private val samples = ArrayList<Long>(SAMPLE_CAP)
    private val drawNanos = ArrayList<Long>(SAMPLE_CAP)

    /** 收到一个输入事件。 */
    fun onInput(eventTimeMs: Long) {
        inputMs = eventTimeMs
        awaitingDraw = true
    }

    /** 刚画完一帧。同一个事件只会被算一次。 */
    fun onDrawn(nowMs: Long) {
        if (!awaitingDraw) return
        awaitingDraw = false
        if (samples.size >= SAMPLE_CAP) return
        samples.add((nowMs - inputMs).coerceAtLeast(0L))
    }

    /** 这一帧画了多久。 */
    fun onFrame(durationNanos: Long) {
        if (drawNanos.size >= SAMPLE_CAP) return
        drawNanos.add(durationNanos)
    }

    /**
     * 一笔结束时打一行汇总。
     *
     * 两边的样本都不足 [MIN_SAMPLES] 就不打：点一下也会走一次 DOWN/UP，那不是样本。
     */
    fun reportOnStrokeEnd() {
        if (samples.size >= MIN_SAMPLES || drawNanos.size >= MIN_SAMPLES) {
            val lat = samples.sorted()
            val draw = drawNanos.sorted()
            Log.i(
                TAG,
                "$TAG $label n=${lat.size}" +
                    (if (lat.isEmpty()) "" else " p50=${percentile(lat, 50)} p95=${percentile(lat, 95)} p99=${percentile(lat, 99)}") +
                    " | drawN=${draw.size}" +
                    (if (draw.isEmpty()) "" else " p50=${micros(draw, 50)}us p95=${micros(draw, 95)}us max=${micros(draw, 100)}us"),
            )
        }
        samples.clear()
        drawNanos.clear()
    }

    private fun percentile(sorted: List<Long>, p: Int): Long =
        sorted[((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)]

    private fun micros(sortedNanos: List<Long>, p: Int): Long = percentile(sortedNanos, p) / 1_000

    private companion object {
        const val TAG = "INKLEAF_INK_LAT"
        const val SAMPLE_CAP = 512
        const val MIN_SAMPLES = 8
    }
}
