package io.whaletodo.core

import java.time.*

data class CalendarTask(val id: String, val deadline: Instant?, val done: Boolean,
    val completedAt: Instant?, val windows: List<Window>)
data class DayActivity(val completed: Int = 0, val overdue: Int = 0, val pending: Int = 0) {
    val total get() = completed + overdue + pending
}

/** One task counts once per date, even if it has multiple blocks or a deadline that day. */
object ActivityCalendar {
    fun tasksOn(date: LocalDate, zone: ZoneId, tasks: List<CalendarTask>): List<CalendarTask> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return tasks.filter { t ->
            if (t.done) t.completedAt?.atZone(zone)?.toLocalDate() == date
            else t.deadline?.atZone(zone)?.toLocalDate() == date ||
                t.windows.any { it.start < end && it.end > start }
        }
    }
    fun summary(date: LocalDate, zone: ZoneId, now: Instant, tasks: List<CalendarTask>): DayActivity {
        var done = 0; var late = 0; var pending = 0
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        tasksOn(date, zone, tasks).forEach { t ->
            when {
                t.done -> done++
                t.deadline?.isBefore(now) == true ||
                    t.windows.any { it.end <= now && it.start < end && it.end > date.atStartOfDay(zone).toInstant() } -> late++
                else -> pending++
            }
        }
        return DayActivity(done, late, pending)
    }
}

object WallTime {
    /** Reject missing DST wall times instead of silently scheduling an hour later. */
    fun resolve(local: LocalDateTime, zone: ZoneId): Instant {
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "该时刻因夏令时不存在，请选择其他时间" }
        return local.toInstant(offsets.first()) // The first occurrence is explicit and deterministic at fall-back.
    }
}
