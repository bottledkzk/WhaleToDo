package io.whaletodo.app.data

import androidx.room.withTransaction
import io.whaletodo.core.*
import java.time.*
import java.util.UUID

/** Database snapshot binds a proposal to the exact records the user previewed. */
data class DeskSnapshot(
    val tasks: List<TaskRow>,
    val blocks: List<BlockRow>,
    val settings: SettingRow,
    val workTimes: List<DailyWorkTimeRow>,
    val date: LocalDate,
)

data class PlanPreview(val source: DeskSnapshot, val result: ScheduleResult)

class DeskRepository(private val db: DeskDatabase) {
    val dao = db.dao()

    suspend fun capture(title: String) {
        require(title.isNotBlank())
        dao.save(TaskRow(UUID.randomUUID().toString(), title.trim()))
    }

    suspend fun save(task: TaskRow) {
        require(task.title.isNotBlank())
        require(task.minutes in 0..525600)
        require(task.importance in 1..5)
        require(task.deadline >= 0)
        dao.save(task.copy(title = task.title.trim()))
    }

    suspend fun complete(task: TaskRow, rating: Int) {
        require(rating in 1..5)
        db.withTransaction {
            val current = dao.tasks().find { it.id == task.id } ?: error("事项已不存在")
            if (!current.done) {
                dao.save(current.copy(done = true))
                dao.setBlocksDone(current.id, true)
                dao.addCheckIn(
                    CheckInRow(
                        UUID.randomUUID().toString(),
                        current.id,
                        System.currentTimeMillis(),
                        rating,
                    )
                )
            }
        }
    }

    suspend fun reopen(task: TaskRow) {
        db.withTransaction {
            dao.save(task.copy(done = false))
            dao.setBlocksDone(task.id, false)
        }
    }

    suspend fun settings(start: Int, end: Int) {
        require(start in 0..23 && end in 1..24 && start < end)
        dao.save(SettingRow(startHour = start, endHour = end))
    }

    suspend fun todayWorkTime(start: Int, end: Int, date: LocalDate) {
        require(date == LocalDate.now()) { "日期已变化，请重新选择今日时段" }
        require(start in 0..1439 && end in start..1439) { "请选择有效起止时刻" }
        dao.save(DailyWorkTimeRow(date.toString(), start, end))
    }

    private suspend fun snapshot() =
        DeskSnapshot(
            dao.tasks(),
            dao.blocks(),
            dao.settings() ?: SettingRow(),
            dao.workTimes(),
            LocalDate.now(),
        )

    suspend fun preview(): PlanPreview = db.withTransaction {
        val source = snapshot()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windows =
            DailyWindows.build(
                today,
                7,
                zone,
                MinuteRange(source.settings.startHour * 60, source.settings.endHour * 60),
                source.workTimes.associate {
                    LocalDate.parse(it.date) to MinuteRange(it.startMinute, it.endMinute)
                },
            )
        val tasks =
            source.tasks
                .filter { !it.done && it.minutes > 0 && it.deadline > 0 }
                .map {
                    Task(
                        it.id,
                        it.title,
                        it.minutes,
                        Instant.ofEpochMilli(it.deadline),
                        it.importance,
                        chunkMinutes = it.chunkMinutes,
                        splittable = it.splittable,
                    )
                }
        val blocks =
            source.blocks.map {
                Block(
                    it.taskId,
                    Window(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end)),
                    it.locked,
                    it.done,
                )
            }
        PlanPreview(source, Scheduler.plan(tasks, windows, emptyList(), blocks, now))
    }

    suspend fun apply(preview: PlanPreview) {
        db.withTransaction {
            check(snapshot() == preview.source) { "事项或计划已变化，请重新预览" }
            // Do not apply a stale suggestion that moves work into the past.
            check(
                preview.result.blocks
                    .filter { b ->
                        preview.source.blocks.none {
                            it.taskId == b.taskId &&
                                it.start == b.window.start.toEpochMilli() &&
                                it.end == b.window.end.toEpochMilli()
                        }
                    }
                    .none { it.window.start < Instant.now().minusSeconds(120) }
            ) {
                "预览已过期，请重新安排"
            }
            dao.clearBlocks()
            dao.insertBlocks(
                preview.result.blocks.map {
                    BlockRow(
                        UUID.randomUUID().toString(),
                        it.taskId,
                        it.window.start.toEpochMilli(),
                        it.window.end.toEpochMilli(),
                        it.locked,
                        it.done,
                    )
                }
            )
        }
    }
}
