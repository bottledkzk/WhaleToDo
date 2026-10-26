package io.whaletodo.core

import java.time.LocalDate
import java.time.ZoneId

/** Minutes are local wall-clock values. End 1440 is retained for existing defaults only. */
data class MinuteRange(val start: Int, val end: Int) {
    init {
        require(start in 0..1439 && end in start..1440)
    }
}

object DailyWindows {
    fun build(
        first: LocalDate,
        days: Int,
        zone: ZoneId,
        defaults: MinuteRange,
        overrides: Map<LocalDate, MinuteRange>,
        weekdays: Map<java.time.DayOfWeek, MinuteRange> = emptyMap(),
    ): List<Window> {
        require(days in 1..366)
        return (0 until days).mapNotNull { offset ->
            val date = first.plusDays(offset.toLong())
            val range = overrides[date] ?: weekdays[date.dayOfWeek] ?: defaults
            val start =
                date.atStartOfDay().plusMinutes(range.start.toLong()).atZone(zone).toInstant()
            val end = date.atStartOfDay().plusMinutes(range.end.toLong()).atZone(zone).toInstant()
            if (end > start) Window(start, end) else null
        }
    }
}
