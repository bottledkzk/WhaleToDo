package io.whaletodo.core

/** A single notification instant for a task; kind drives the notification copy. */
data class ReminderTrigger(val atMillis: Long, val kind: String)

/**
 * Pure reminder-time calculation. Only future instants are returned, sorted ascending;
 * when start and end fall on the same millisecond the start trigger wins.
 */
object ReminderPlanner {
    fun triggers(
        deadlineMillis: Long,
        blocks: List<Window>,
        timing: String,
        nowMillis: Long,
    ): List<ReminderTrigger> {
        val futureDeadline = deadlineMillis.takeIf { it > nowMillis }
        val earliestStart = blocks
            .map { it.start.toEpochMilli() }
            .filter { it > nowMillis }
            .minOrNull()
        val latestEnd = blocks
            .map { it.end.toEpochMilli() }
            .filter { it > nowMillis }
            .maxOrNull()

        val result = when (timing) {
            "start" -> listOfNotNull(
                (earliestStart ?: futureDeadline)?.let { ReminderTrigger(it, "start") },
            )
            "end" -> listOfNotNull(
                (latestEnd ?: futureDeadline)?.let { ReminderTrigger(it, "end") },
            )
            "both" -> listOfNotNull(
                (earliestStart ?: futureDeadline)?.let { ReminderTrigger(it, "start") },
                (latestEnd ?: futureDeadline)?.let { ReminderTrigger(it, "end") },
            )
            else -> emptyList()
        }

        return result
            .filter { it.atMillis > nowMillis }
            .distinctBy { it.atMillis }
            .sortedBy { it.atMillis }
    }
}
