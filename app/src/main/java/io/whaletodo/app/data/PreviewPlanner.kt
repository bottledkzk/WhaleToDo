package io.whaletodo.app.data

import io.whaletodo.core.*
import java.time.Instant

/** Missing task fields must neither hide unplanned work nor erase existing arrangements. */
internal fun previewPlan(tasks: List<TaskRow>, rows: List<BlockRow>, windows: List<Window>, now: Instant): ScheduleResult {
    val eligible = tasks.filter { !it.done && it.minutes > 0 && it.deadline > 0 }
    val ids = eligible.map { it.id }.toSet()
    fun block(row: BlockRow) = Block(row.taskId,
        Window(Instant.ofEpochMilli(row.start), Instant.ofEpochMilli(row.end)), row.locked, row.done)
    val retained = rows.filter { it.taskId !in ids }.map(::block)
    val planned = Scheduler.plan(eligible.map {
        Task(it.id, it.title, it.minutes, Instant.ofEpochMilli(it.deadline), it.importance,
            chunkMinutes = it.chunkMinutes, splittable = it.splittable)
    }, windows, retained.map { it.window }, rows.filter { it.taskId in ids }.map(::block), now)
    val incomplete = tasks.filter { !it.done && it.id !in ids }.mapNotNull { task ->
        val existing = retained.filter { it.taskId == task.id }
        val remaining = (task.minutes - existing.sumOf { it.window.minutes }).coerceAtLeast(0).toInt()
        if (remaining == 0 && (task.minutes > 0 || existing.isNotEmpty())) null
        else Unscheduled(task.id, remaining, buildList {
            if (task.minutes <= 0) add("预计工时")
            if (task.deadline <= 0) add("截止时间")
        }.joinToString("与", prefix = "缺少"))
    }
    return ScheduleResult((planned.blocks + retained).sortedWith(compareBy<Block> { it.window.start }.thenBy { it.taskId }),
        planned.unmet + incomplete)
}
