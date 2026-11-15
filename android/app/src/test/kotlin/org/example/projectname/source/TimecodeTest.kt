package org.example.projectname.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TimecodeTest {
    private fun millisOfDay(hour: Int, minute: Int, second: Int, millis: Int = 0): Long =
        LocalTime.of(hour, minute, second, millis * 1_000_000).toNanoOfDay() / 1_000_000

    @Test
    fun formatsHoursMinutesSecondsAndFrames() {
        assertEquals("00:00:00:00", Timecode.format(0, 30))
        assertEquals("00:00:00:29", Timecode.format(29, 30))
        assertEquals("00:00:01:00", Timecode.format(30, 30))
        assertEquals("00:01:00:00", Timecode.format(30L * 60, 30))
        assertEquals("01:00:00:00", Timecode.format(30L * 3600, 30))
        assertEquals("12:34:56:07", Timecode.format(30L * (12 * 3600 + 34 * 60 + 56) + 7, 30))
    }

    @Test
    fun hoursWrapAtTwentyFour() {
        assertEquals("00:00:00:00", Timecode.format(30L * 24 * 3600, 30))
        assertEquals("23:59:59:29", Timecode.format(30L * 24 * 3600 - 1, 30))
    }

    @Test
    fun framesCountToTheFrameRate() {
        assertEquals("00:00:00:59", Timecode.format(59, 60))
        assertEquals("00:00:01:00", Timecode.format(60, 60))
    }

    @Test
    fun frameAtFindsTheSlotForAnElapsedTime() {
        assertEquals(0, Timecode.frameAt(0, 30))
        assertEquals(0, Timecode.frameAt(33, 30))
        assertEquals(1, Timecode.frameAt(34, 30))
        assertEquals(30, Timecode.frameAt(1000, 30))
        assertEquals(0, Timecode.frameAt(-5, 30))
    }

    @Test
    fun dueAtIsTheFirstMillisecondOfEachSlot() {
        for (frame in 0L..300L) {
            val due = Timecode.dueAt(frame, 30)
            assertEquals("frame $frame starts at $due ms", frame, Timecode.frameAt(due, 30))
            assertTrue("the millisecond before belongs to the slot before", due == 0L || Timecode.frameAt(due - 1, 30) == frame - 1)
        }
        assertEquals(listOf(0L, 34L, 67L, 100L), (0L..3L).map { Timecode.dueAt(it, 30) })
    }

    @Test
    fun thirtyFramesAreDueInEverySecond() {
        val dueInFirstMinute = (0L until 1800L).map { Timecode.dueAt(it, 30) }
        for (second in 0 until 60) {
            assertEquals(30, dueInFirstMinute.count { it / 1000 == second.toLong() })
        }
    }

    @Test
    fun theClockShowsTheLocalTimeOfDay() {
        // 09:30:05.500 local time is read when the uptime clock says 7,000 ms.
        val clock = TimecodeClock(millisOfDayAtStart = millisOfDay(9, 30, 5, 500), uptimeAtStart = 7_000, fps = 30)
        assertEquals("09:30:05:15", Timecode.format(clock.frameAt(7_000), 30))
        assertEquals("09:30:06:00", Timecode.format(clock.frameAt(7_500), 30))
        assertEquals("09:31:05:15", Timecode.format(clock.frameAt(67_000), 30))
    }

    @Test
    fun theClockAdvancesOneFramePerFrameSlot() {
        val clock = TimecodeClock(millisOfDayAtStart = millisOfDay(14, 0, 0, 123), uptimeAtStart = 50_000, fps = 30)
        val first = clock.frameAt(50_000)
        for (frame in first + 1..first + 300) {
            val due = clock.dueAt(frame)
            assertEquals("slot $frame starts at uptime $due", frame, clock.frameAt(due))
            assertEquals("the millisecond before is still the slot before", frame - 1, clock.frameAt(due - 1))
        }
    }

    @Test
    fun frameZeroOfEverySecondStartsOnTheWallClocksSecond() {
        val start = millisOfDay(8, 15, 42, 731)
        val uptimeAtStart = 123_456L
        val clock = TimecodeClock(millisOfDayAtStart = start, uptimeAtStart = uptimeAtStart, fps = 30)
        val nextWholeSecond = clock.frameAt(uptimeAtStart) / 30 * 30 + 30
        for (frame in nextWholeSecond..nextWholeSecond + 30L * 10 step 30) {
            val wallMillis = start + (clock.dueAt(frame) - uptimeAtStart)
            assertEquals("frame $frame", 0L, wallMillis % 1000)
            assertTrue(Timecode.format(frame, 30).endsWith(":00"))
        }
    }

    @Test
    fun theClockRunsOnPastMidnight() {
        val clock = TimecodeClock(millisOfDayAtStart = millisOfDay(23, 59, 59, 990), uptimeAtStart = 0, fps = 30)
        assertEquals("23:59:59:29", Timecode.format(clock.frameAt(0), 30))
        assertEquals("00:00:00:00", Timecode.format(clock.frameAt(10), 30))
        assertEquals("00:00:01:00", Timecode.format(clock.frameAt(1_010), 30))
    }
}
