package dev.inkleaf.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ReadingStatsCalculatorTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Long = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()

    private fun session(
        docId: String = "doc-1",
        startIso: String,
        minutes: Long,
        id: String = startIso + docId,
    ): ReadingSession {
        val start = Instant.parse(startIso).toEpochMilli()
        val duration = minutes * 60_000
        return ReadingSession(
            id = id,
            docId = docId,
            startedAt = start,
            endedAt = start + duration,
            durationMs = duration,
        )
    }

    private fun epochDay(iso: String): Long =
        LocalDate.parse(iso.take(10)).toEpochDay()

    @Test
    fun `没有会话时一切都是零`() {
        val stats = ReadingStatsCalculator.compute(emptyList(), now, zone)
        assertEquals(0L, stats.totalMs)
        assertEquals(0L, stats.todayMs)
        assertEquals(0, stats.daysRead)
        assertEquals(0, stats.streakDays)
        assertEquals(7, stats.last7Days.size)
        assertEquals(0L, stats.last7Days.sumOf { it.durationMs })
    }

    @Test
    fun `总时长与今日时长只算有效会话`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(startIso = "2026-10-21T09:00:00Z", minutes = 30),
                session(startIso = "2026-10-21T11:00:00Z", minutes = 15),
                session(startIso = "2026-10-20T22:00:00Z", minutes = 20),
                // 0 时长的会话：通常来自"刚点开就退出"，不该制造阅读记录。
                session(startIso = "2026-10-19T10:00:00Z", minutes = 0),
            ),
            now,
            zone,
        )
        assertEquals(65L * 60_000, stats.totalMs)
        assertEquals(45L * 60_000, stats.todayMs)
        assertEquals(2, stats.daysRead)
    }

    @Test
    fun `最近七天按时间从早到晚且补零`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(startIso = "2026-10-21T09:00:00Z", minutes = 10),
                session(startIso = "2026-10-18T09:00:00Z", minutes = 5),
                // 八天前：不该出现在柱状图里，但仍计入总时长。
                session(startIso = "2026-10-13T09:00:00Z", minutes = 7),
            ),
            now,
            zone,
        )
        assertEquals(7, stats.last7Days.size)
        assertEquals(epochDay("2026-10-15"), stats.last7Days.first().epochDay)
        assertEquals(epochDay("2026-10-21"), stats.last7Days.last().epochDay)
        assertEquals(0L, stats.last7Days[0].durationMs)
        assertEquals(5L * 60_000, stats.last7Days[3].durationMs)
        assertEquals(10L * 60_000, stats.last7Days.last().durationMs)
        assertEquals(22L * 60_000, stats.totalMs)
    }

    @Test
    fun `连续三天读过的日子_连续天数为三`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(startIso = "2026-10-21T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-20T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-19T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-16T09:00:00Z", minutes = 1),
            ),
            now,
            zone,
        )
        assertEquals(3, stats.streakDays)
        assertEquals(4, stats.daysRead)
    }

    @Test
    fun `今天还没读时从昨天往回数_昨天的连续不算断`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(startIso = "2026-10-20T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-19T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-18T09:00:00Z", minutes = 1),
            ),
            now,
            zone,
        )
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun `中间断了一天_连续天数只算最近一截`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(startIso = "2026-10-21T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-19T09:00:00Z", minutes = 1),
                session(startIso = "2026-10-18T09:00:00Z", minutes = 1),
            ),
            now,
            zone,
        )
        assertEquals(1, stats.streakDays)
        assertEquals(3, stats.daysRead)
    }

    @Test
    fun `跨零点的一段算在开始那天`() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(startIso = "2026-10-20T23:58:00Z", minutes = 4)),
            now,
            zone,
        )
        assertEquals(0L, stats.todayMs)
        assertEquals(4L * 60_000, stats.last7Days[5].durationMs)
    }

    @Test
    fun `每本书的时长按 docId 聚合`() {
        val totals = ReadingStatsCalculator.perDocument(
            listOf(
                session(docId = "a", startIso = "2026-10-21T09:00:00Z", minutes = 5),
                session(docId = "a", startIso = "2026-10-20T09:00:00Z", minutes = 7),
                session(docId = "b", startIso = "2026-10-19T09:00:00Z", minutes = 2),
                session(docId = "c", startIso = "2026-10-19T10:00:00Z", minutes = 0),
            ),
        )
        assertEquals(12L * 60_000, totals["a"])
        assertEquals(2L * 60_000, totals["b"])
        assertEquals(null, totals["c"])
    }
}
