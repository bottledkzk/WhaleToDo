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
    val zoneId: String,
)

data class PlanPreview(val source: DeskSnapshot, val result: ScheduleResult)

class DeskRepository(private val db: DeskDatabase) {
    val dao = db.dao()
    fun close() = db.close()

    suspend fun capture(title: String) {
        require(title.isNotBlank())
        dao.save(TaskRow(UUID.randomUUID().toString(), title.trim()))
    }

    suspend fun save(task: TaskRow) {
        require(task.title.isNotBlank())
        require(task.minutes in 0..525600)
        require(task.importance in 1..5)
        require(task.deadline >= 0)
        require(TaskCategory.entries.any { it.code == task.category })
        dao.save(task.copy(title = task.title.trim()))
    }

    /** Future manual reservations are edited atomically with their task. Past work is immutable. */
    suspend fun saveScheduled(task: TaskRow, manual: Window?) = db.withTransaction {
        val now = System.currentTimeMillis()
        val old = dao.blocks()
        val editable = old.filter { it.taskId == task.id && it.start > now && !it.done }
        if (manual != null) {
            require(!task.done) { "已完成事项请先恢复为待办" }
            require(manual.start.toEpochMilli() > now) { "安排开始时间需晚于现在" }
            require(java.time.Duration.between(manual.start, manual.end).toMinutes() >= 1) { "时段至少1分钟" }
            require(task.deadline == 0L || manual.end.toEpochMilli() <= task.deadline) { "安排结束不能晚于截止时间" }
            val reserved = old.filter { it !in editable && (it.locked || it.done || it.start <= now) }
            require(reserved.none { it.start < manual.end.toEpochMilli() && it.end > manual.start.toEpochMilli() }) {
                "与已固定或已开始的安排重叠，请选择其他时段"
            }
        }
        save(task)
        // Remove replaceable work for this task; a manual reservation also displaces overlapping auto work.
        val retained = old.filterNot { b ->
            b in editable && (b.locked || manual != null) ||
                manual != null && !b.locked && !b.done && b.start > now &&
                b.start < manual.end.toEpochMilli() && b.end > manual.start.toEpochMilli()
        }
        dao.clearBlocks()
        dao.insertBlocks(retained + listOfNotNull(manual?.let {
            BlockRow(UUID.randomUUID().toString(), task.id, it.start.toEpochMilli(), it.end.toEpochMilli(), locked = true)
        }))
    }


    /** Same preview/transaction boundary as manual planning; a receipt prevents repeated application. */
    suspend fun applyAgent(id: String, owner: String, source: DeskSnapshot, changes: List<AgentChange>) = db.withTransaction {
        check(dao.receiptCount(id, owner) == 0) { "此提案已采用" }
        check(snapshot() == source) { "日程已变化，请重新生成提案" }
        val zone = ZoneId.of(source.zoneId)
        val windows = DailyWindows.build(source.date, 14, zone,
            MinuteRange(source.settings.startHour*60, source.settings.endHour*60),
            source.workTimes.associate { LocalDate.parse(it.date) to MinuteRange(it.startMinute,it.endMinute) })
        AgentChanges.validate(changes, source.tasks.map { AgentTaskFact(it.id,it.done,it.deadline) },
            source.blocks.map { AgentBlockFact(it.id,it.taskId,it.start,it.end,it.locked,it.done) }, System.currentTimeMillis(),windows)
        val updated = source.tasks.associateBy { it.id }.toMutableMap()
        val blocks = source.blocks.associateBy { it.id }.toMutableMap()
        changes.forEach { c -> when(c) {
            is AgentChange.RenameTask -> updated[c.taskId] = updated.getValue(c.taskId).copy(title=c.title.trim())
            is AgentChange.CategorizeTask -> updated[c.taskId] = updated.getValue(c.taskId).copy(category=c.category)
            is AgentChange.MoveBlock -> blocks[c.blockId] = blocks.getValue(c.blockId).copy(start=c.start,end=c.end)
        } }
        updated.values.forEach { dao.save(it) }
        dao.clearBlocks();dao.insertBlocks(blocks.values.toList())
        dao.addReceipt(AgentReceiptRow(id,owner,System.currentTimeMillis()))
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

    suspend fun snapshot() =
        DeskSnapshot(
            dao.tasks(),
            dao.blocks(),
            dao.settings() ?: SettingRow(),
            dao.workTimes(),
            LocalDate.now(),
            ZoneId.systemDefault().id,
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
                        preview.source.blocks.find { old ->
                            old.taskId == it.taskId && old.start == it.window.start.toEpochMilli() &&
                                old.end == it.window.end.toEpochMilli() && old.locked == it.locked && old.done == it.done
                        }?.id ?: UUID.randomUUID().toString(),
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
