package dev.inkleaf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTimeTrackerTest {

    private var elapsed = 1_000L
    private var wall = 1_700_000_000_000L
    private val segments = mutableListOf<Triple<Long, Long, Long>>()

    private fun tracker(minDurationMs: Long = ReadingTimeTracker.MIN_DURATION_MS) = ReadingTimeTracker(
        onSegment = { startedAt, endedAt, durationMs ->
            segments += Triple(startedAt, endedAt, durationMs)
        },
        elapsedMs = { elapsed },
        wallMs = { wall },
        minDurationMs = minDurationMs,
    )

    @Test
    fun `在前台待够时间才记一段`() {
        val t = tracker()
        t.enterForeground()
        elapsed += 5_000
        assertTrue(t.leaveForeground())
        assertEquals(1, segments.size)
        assertEquals(wall, segments[0].first)
        assertEquals(5_000L, segments[0].third)
        assertEquals(wall + 5_000, segments[0].second)
    }

    @Test
    fun `太短的一段直接丢掉`() {
        val t = tracker()
        t.enterForeground()
        elapsed += 2_999
        assertFalse(t.leaveForeground())
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `重复进入前台不会把一段切成两段`() {
        val t = tracker()
        t.enterForeground()
        elapsed += 2_000
        t.enterForeground()
        elapsed += 3_000
        t.leaveForeground()
        assertEquals(1, segments.size)
        assertEquals(5_000L, segments[0].third)
    }

    @Test
    fun `没有开始过时leave什么都不做`() {
        val t = tracker()
        assertFalse(t.leaveForeground())
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `切后台再回来是两段`() {
        val t = tracker()
        t.enterForeground()
        elapsed += 10_000
        t.leaveForeground()
        wall += 60_000 // 后台待了一分钟，不该计入
        elapsed += 60_000
        t.enterForeground()
        elapsed += 4_000
        t.leaveForeground()
        assertEquals(2, segments.size)
        assertEquals(10_000L, segments[0].third)
        assertEquals(4_000L, segments[1].third)
    }

    @Test
    fun `墙钟被改过_时长仍按单调时钟`() {
        val t = tracker()
        t.enterForeground()
        elapsed += 6_000
        wall -= 3_600_000 // 用户把系统时间往回拨了一小时
        t.leaveForeground()
        assertEquals(6_000L, segments[0].third)
    }
}
