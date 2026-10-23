package io.whaletodo.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ActivityCalendarTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 23)
    private val now = day.atTime(12, 0).atZone(zone).toInstant()
    private fun time(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant()
    @Test fun mixedStatusesUseOneTaskPerDay() {
        val rows = listOf(
            CalendarTask("done", time(10), true, time(9), emptyList()),
            CalendarTask("late", time(11), false, null, listOf(Window(time(9), time(10)))),
            CalendarTask("next", time(18), false, null, listOf(Window(time(13), time(14)), Window(time(15), time(16)))))
        assertEquals(DayActivity(1, 1, 1), ActivityCalendar.summary(day, zone, now, rows))
    }
    @Test fun futureIsYellowAndMissedBlockIsRedWithoutDeadline() {
        val rows = listOf(CalendarTask("past", null, false, null, listOf(Window(time(9), time(10)))),
            CalendarTask("future", null, false, null, listOf(Window(time(13), time(14)))))
        assertEquals(DayActivity(0, 1, 1), ActivityCalendar.summary(day, zone, now, rows))
    }
    @Test fun completionBelongsToActualDayAndReopenedTaskIsPending() {
        val done = CalendarTask("a", time(18), true, time(10).minusSeconds(86400), emptyList())
        assertEquals(0, ActivityCalendar.summary(day, zone, now, listOf(done)).total)
        assertEquals(1, ActivityCalendar.summary(day.minusDays(1), zone, now, listOf(done)).completed)
        assertEquals(1, ActivityCalendar.summary(day, zone, now, listOf(done.copy(done = false))).pending)
    }
    @Test fun crossMidnightIsShownBothDaysButTouchingBoundaryIsNot() {
        val row = CalendarTask("a", null, false, null, listOf(Window(time(23), time(23).plusSeconds(7200))))
        assertEquals(1, ActivityCalendar.summary(day.plusDays(1), zone, now, listOf(row)).total)
        val boundary = row.copy(windows = listOf(Window(time(23), day.plusDays(1).atStartOfDay(zone).toInstant())))
        assertEquals(0, ActivityCalendar.summary(day.plusDays(1), zone, now, listOf(boundary)).total)
    }
    @Test fun sameInstantAppearsOnLocalDateWithoutTimestampMutation() {
        val instant = Instant.parse("2026-10-23T01:00:00Z")
        val row = CalendarTask("a", instant, false, null, emptyList())
        assertEquals(1, ActivityCalendar.tasksOn(day, zone, listOf(row)).size)
        assertEquals(1, ActivityCalendar.tasksOn(day.minusDays(1), ZoneId.of("America/Los_Angeles"), listOf(row)).size)
        assertEquals(Instant.parse("2026-10-23T01:00:00Z"), row.deadline)
    }
    @Test fun missingDstTimeRejectedAndRepeatedTimeUsesFirstOccurrence() {
        val dstZone = ZoneId.of("America/Los_Angeles")
        assertThrows(IllegalArgumentException::class.java) { WallTime.resolve(LocalDateTime.of(2026, 3, 8, 2, 30), dstZone) }
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), WallTime.resolve(LocalDateTime.of(2026, 11, 1, 1, 30), dstZone))
    }
    @Test fun dstDayUsesWallDatesNotTwentyFourHourSlices() {
        val dstZone = ZoneId.of("America/Los_Angeles")
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(dstZone).toInstant()
        val row = CalendarTask("a", start.plusSeconds(23 * 3600), false, null, emptyList())
        assertEquals(0, ActivityCalendar.tasksOn(LocalDate.of(2026, 3, 8), dstZone, listOf(row)).size)
        assertEquals(1, ActivityCalendar.tasksOn(LocalDate.of(2026, 3, 9), dstZone, listOf(row)).size)
    }
}
