package dev.inkleaf.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一段**前台阅读时间**。
 *
 * 为什么是一段一段的，而不是"每本书一个累计秒数"：
 *
 *  - 统计页要按天画 7 根柱子，也要算连续天数。累计值一旦加上去，
 *    "昨天读了多久"就再也拆不出来了。
 *  - 阅读器的生命周期本来就是分段的：进来、切后台、回来、退出。
 *    每次 `ON_STOP` 落一段，进程被杀时最多丢最后一段，而不会丢整本书的历史。
 *
 * [durationMs] 与 [endedAt] - [startedAt] 可能不同：后者来自墙钟，前者来自
 * `SystemClock.elapsedRealtime()`（单调）。用户手动改系统时间时，
 * 墙钟会跳，而"读了多久"不该跟着跳。
 */
data class ReadingSession(
    val id: String,
    val docId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
)

/** 统计页里的一根柱子：某一天（本地时区）读了多久。 */
data class ReadingDay(
    val epochDay: Long,
    val durationMs: Long,
) {
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.ofEpochDay(epochDay)
}

/**
 * 阅读统计。
 *
 * 这里只有**已经算好**的事实，界面不再二次判断。所有跨天的口径都集中在本文件，
 * 否则"今天读了多久"在统计页、说明文字、导出里会各有一个答案。
 */
data class ReadingStats(
    val totalMs: Long,
    val todayMs: Long,
    /** 有阅读记录的天数（不是"连续天数"）。 */
    val daysRead: Int,
    /** 从今天（今天没读则从昨天）往回数，连续有阅读的天数。 */
    val streakDays: Int,
    /** 含今天在内的 7 天，按时间从早到晚，没有阅读的日子也在列表里（时长为 0）。 */
    val last7Days: List<ReadingDay>,
)

object ReadingStatsCalculator {

    const val RECENT_DAYS = 7

    /**
     * 把会话列表算成统计。
     *
     * 口径（写下来是因为它们都可以有别的选法）：
     *
     *  - 一段会话整段算在**开始那天**。会话最长几分钟（阅读器在切后台时落盘），
     *    跨零点的那一段最多让前一天多几分钟，不会出现"昨晚读了两小时全算今天"。
     *  - 时区取调用方传入的 [zone]。统计页用系统时区；单测传 UTC 才能稳定。
     *  - 连续天数：从今天往回数；今天还没读时从昨天往回数 —— 上午打开 App
     *    看到"连续 0 天"会把昨天读的也判没了，这不是用户理解的"断了"。
     *  - 时长为 0 的会话不制造"读过"的一天，但它仍然计入总时长（0 无影响）。
     */
    fun compute(
        sessions: List<ReadingSession>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReadingStats {
        val valid = sessions.filter { it.durationMs > 0 }
        val totalMs = valid.sumOf { it.durationMs }

        val byDay = valid.groupBy { epochDayOf(it.startedAt, zone) }
            .mapValues { (_, list) -> list.sumOf { it.durationMs } }

        val today = epochDayOf(now, zone)
        val todayMs = byDay[today] ?: 0L
        val daysRead = byDay.size

        var streak = 0
        var cursor = if (byDay.containsKey(today)) today else today - 1
        while (byDay.containsKey(cursor)) {
            streak++
            cursor--
        }

        val last7Days = (RECENT_DAYS - 1 downTo 0).map { back ->
            val day = today - back
            ReadingDay(epochDay = day, durationMs = byDay[day] ?: 0L)
        }

        return ReadingStats(
            totalMs = totalMs,
            todayMs = todayMs,
            daysRead = daysRead,
            streakDays = streak,
            last7Days = last7Days,
        )
    }

    /** 每本书的累计阅读时长（docId → ms）。只返回真的读过的书。 */
    fun perDocument(sessions: List<ReadingSession>): Map<String, Long> =
        sessions.asSequence()
            .filter { it.durationMs > 0 }
            .groupingBy { it.docId }
            .fold(0L) { acc, session -> acc + session.durationMs }

    private fun epochDayOf(timestampMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toEpochDay()
}
