package io.whaletodo.core

import java.time.Instant
import java.time.Duration

/** Half-open interval; touching windows are continuous, genuine gaps remain gaps. */
data class Window(val start: Instant, val end: Instant) {
    init { require(end > start) { "Window must have positive duration" } }
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}
data class Task(val id: String, val title: String, val minutes: Int, val deadline: Instant,
                val importance: Int = 3, val earliest: Instant? = null, val chunkMinutes: Int = 60,
                val splittable: Boolean = true, val done: Boolean = false) {
    init { require(id.isNotBlank() && title.isNotBlank()); require(minutes in 1..525600); require(importance in 1..5); require(chunkMinutes in 15..480) }
}
data class Block(val taskId: String, val window: Window, val locked: Boolean = false, val done: Boolean = false)
data class Unscheduled(val taskId: String, val remainingMinutes: Int, val reason: String)
data class ScheduleResult(val blocks: List<Block>, val unmet: List<Unscheduled>)

object Scheduler {
    fun union(windows: List<Window>): List<Window> {
        val result = mutableListOf<Window>()
        for (window in windows.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && window.start <= last.end) result[result.lastIndex] = Window(last.start, maxOf(last.end, window.end))
            else result += window
        }
        return result
    }
    fun subtract(windows: List<Window>, blocked: List<Window>): List<Window> {
        var result = union(windows)
        for (busy in union(blocked)) result = result.flatMap { w ->
            if (busy.end <= w.start || busy.start >= w.end) listOf(w)
            else buildList { if (busy.start > w.start) add(Window(w.start, busy.start)); if (busy.end < w.end) add(Window(busy.end, w.end)) }
        }
        return result
    }
    /** Deterministic deadline-first heuristic. Preserves completed, locked and already-started blocks. */
    fun plan(tasks: List<Task>, availability: List<Window>, busy: List<Window>, existing: List<Block>, now: Instant): ScheduleResult {
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Duplicate task IDs" }
        val protected = existing.filter { it.done || it.locked || it.window.start <= now }
        val slots = availability.mapNotNull { w -> val start = maxOf(w.start, now); if (w.end > start) Window(start, w.end) else null }
        var free = subtract(slots, busy + protected.map { it.window })
        val planned = protected.toMutableList()
        val unmet = mutableListOf<Unscheduled>()
        for (task in tasks.filterNot { it.done }.sortedWith(compareBy<Task> { it.deadline }.thenByDescending { it.importance }.thenBy { it.id })) {
            val allocated = protected.filter { it.taskId == task.id }.sumOf { it.window.minutes }.coerceAtMost(task.minutes.toLong()).toInt()
            var remaining = task.minutes - allocated
            val candidates = free.mapNotNull { w ->
                val start = maxOf(w.start, task.earliest ?: now)
                val end = minOf(w.end, task.deadline)
                if (end > start) Window(start, end) else null
            }
            for (slot in candidates) {
                if (remaining <= 0) break
                var cursor = slot.start
                while (remaining > 0 && cursor < slot.end) {
                    val capacity = Duration.between(cursor, slot.end).toMinutes().toInt()
                    val take = if (!task.splittable) { if (capacity >= remaining) remaining else 0 }
                               else minOf(remaining, task.chunkMinutes, capacity)
                    if (take <= 0 || (take < 15 && take < remaining)) break
                    val block = Block(task.id, Window(cursor, cursor.plusSeconds(take * 60L)))
                    planned += block
                    free = subtract(free, listOf(block.window))
                    cursor = block.window.end
                    remaining -= take
                    if (!task.splittable) break
                }
            }
            if (remaining > 0) unmet += Unscheduled(task.id, remaining, if (task.deadline <= now) "截止时间已过" else "截止前空闲不足")
        }
        return ScheduleResult(planned.sortedWith(compareBy<Block> { it.window.start }.thenBy { it.taskId }), unmet)
    }
}
