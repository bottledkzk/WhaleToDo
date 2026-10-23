package io.whaletodo.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DailyWindowsTest {
    private val date = LocalDate.of(2026, 10, 23)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun todayOverridePreservesOtherDaysAndMinutes() {
        val windows =
            DailyWindows.build(
                date,
                7,
                zone,
                MinuteRange(540, 1260),
                mapOf(date to MinuteRange(555, 1107)),
            )
        assertEquals(7, windows.size)
        assertEquals(LocalTime.of(9, 15), windows[0].start.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(18, 27), windows[0].end.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(9, 0), windows[1].start.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(21, 0), windows[1].end.atZone(zone).toLocalTime())
    }

    @Test
    fun oldDayDoesNotBecomeTomorrowsOverride() {
        val windows =
            DailyWindows.build(
                date.plusDays(1),
                1,
                zone,
                MinuteRange(540, 1260),
                mapOf(date to MinuteRange(600, 900)),
            )
        assertEquals(720, windows.single().minutes.toInt())
    }

    @Test
    fun equalEndpointsRepresentNoAvailabilityIncluding2359() {
        assertTrue(
            DailyWindows.build(
                    date,
                    1,
                    zone,
                    MinuteRange(0, 1439),
                    mapOf(date to MinuteRange(1439, 1439)),
                )
                .isEmpty()
        )
    }

    @Test
    fun lastMinuteAndLegacyMidnightAreDistinct() {
        assertEquals(
            1439,
            DailyWindows.build(
                    date,
                    1,
                    zone,
                    MinuteRange(0, 1440),
                    mapOf(date to MinuteRange(0, 1439)),
                )
                .single()
                .minutes
                .toInt(),
        )
        assertEquals(
            1440,
            DailyWindows.build(date, 1, zone, MinuteRange(0, 1440), emptyMap())
                .single()
                .minutes
                .toInt(),
        )
    }

    @Test
    fun daylightSavingUsesLocalClockInsteadOfElapsedHours() {
        val ny = ZoneId.of("America/New_York")
        val window =
            DailyWindows.build(LocalDate.of(2026, 3, 8), 1, ny, MinuteRange(540, 1260), emptyMap())
                .single()
        assertEquals(LocalTime.of(9, 0), window.start.atZone(ny).toLocalTime())
        assertEquals(LocalTime.of(21, 0), window.end.atZone(ny).toLocalTime())
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeRejected() {
        MinuteRange(800, 799)
    }
}
