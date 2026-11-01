package io.whaletodo.core

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A fresh clock is supplied at each operation; display dates are never a scheduling clock. */
object LiveTime {
    fun ceilMinute(now: Instant): Instant = now.truncatedTo(ChronoUnit.MINUTES).let { if (it < now) it.plusSeconds(60) else it }

    fun reflectionDate(request: String, today: LocalDate): LocalDate {
        val explicit = Regex("(?:(\\d{4})[-/年])?(\\d{1,2})[-/月](\\d{1,2})日?").find(request)
        if (explicit != null) return LocalDate.of(explicit.groupValues[1].toIntOrNull() ?: today.year, explicit.groupValues[2].toInt(), explicit.groupValues[3].toInt())
        return when {
            "前天" in request -> today.minusDays(2)
            "昨天" in request || "昨日" in request -> today.minusDays(1)
            else -> today
        }
    }
}
