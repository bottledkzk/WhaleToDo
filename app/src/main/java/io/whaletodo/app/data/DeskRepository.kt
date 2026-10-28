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
    val preferences: List<UiPreferenceRow> = emptyList(),
)

data class PlanPreview(val source: DeskSnapshot, val result: ScheduleResult)

class DeskRepository(private val db: DeskDatabase) {
    val dao = db.dao()
    fun close() = db.close()

    suspend fun capture(title: String, owner: String = "guest") {
        require(title.isNotBlank())
        dao.save(TaskRow(UUID.randomUUID().toString(), title.trim(), ownerId = owner))
    }

    suspend fun save(task: TaskRow, owner: String = "guest") {
        require(owner.isNotBlank())
        require(task.ownerId == owner || task.ownerId == "guest") { "不能跨账户保存事项" }
        require(task.title.isNotBlank())
        require(task.minutes in 0..525600)
        require(task.importance in 1..5)
        require(task.deadline >= 0)
        require(TaskCategory.entries.any { it.code == task.category })
        dao.save(task.copy(ownerId = owner, title = task.title.trim()))
    }

    /** Future manual reservations are edited atomically with their task. Past work is immutable. */
    suspend fun saveScheduled(
        task: TaskRow,
        manual: Window?,
        reminder: TaskReminderRow? = null,
        owner: String = "guest",
    ) = db.withTransaction {
        require(task.ownerId == owner || task.ownerId == "guest") { "不能跨账户安排事项" }
        val now = System.currentTimeMillis()
        val old = dao.blocks(owner)
        val task = task.copy(ownerId = owner)
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
        save(task, owner)
        // Remove replaceable work for this task; a manual reservation also displaces overlapping auto work.
        val retained = old.filterNot { b ->
            b in editable && (b.locked || manual != null) ||
                manual != null && !b.locked && !b.done && b.start > now &&
                b.start < manual.end.toEpochMilli() && b.end > manual.start.toEpochMilli()
        }
        dao.clearBlocks(owner)
        dao.insertBlocks(retained + listOfNotNull(manual?.let {
            BlockRow(
                UUID.randomUUID().toString(),
                task.id,
                it.start.toEpochMilli(),
                it.end.toEpochMilli(),
                locked = true,
                ownerId = owner,
            )
        }))
        if (reminder != null) {
            require(reminder.taskId == task.id)
            require(reminder.ownerId == owner) { "提醒与事项不属于同一账户" }
            saveReminder(reminder)
        }
    }


    /** Same preview/transaction boundary as manual planning; a receipt prevents repeated application. */
    suspend fun applyAgent(id: String, owner: String, source: DeskSnapshot, changes: List<AgentChange>) = db.withTransaction {
        check(dao.receiptCount(id, owner) == 0) { "此提案已采用" }
        check(snapshot(owner) == source) { "日程已变化，请重新生成提案" }
        validateAgentChanges(source, changes)
        val updated = source.tasks.associateBy { it.id }.toMutableMap()
        val blocks = source.blocks.associateBy { it.id }.toMutableMap()
        changes.forEach { c -> when(c) {
            is AgentChange.RenameTask -> updated[c.taskId] = updated.getValue(c.taskId).copy(title=c.title.trim(), ownerId=owner)
            is AgentChange.CategorizeTask -> updated[c.taskId] = updated.getValue(c.taskId).copy(category=c.category, ownerId=owner)
            is AgentChange.MoveBlock -> blocks[c.blockId] = blocks.getValue(c.blockId).copy(start=c.start,end=c.end,ownerId=owner)
        } }
        updated.values.forEach { dao.save(it) }
        dao.clearBlocks(owner);dao.insertBlocks(blocks.values.map { it.copy(ownerId = owner) })
        dao.addReceipt(AgentReceiptRow(id,owner,System.currentTimeMillis()))
    }

    /** Read-only admission to the confirmation dialog; commit repeats these checks. */
    suspend fun checkAgentPreview(
        source: DeskSnapshot,
        changes: List<AgentChange>,
        owner: String = "guest",
    ) {
        check(snapshot(owner) == source) { "日程已变化，请重新生成提案" }
        validateAgentChanges(source, changes)
    }
    private fun validateAgentChanges(source: DeskSnapshot, changes: List<AgentChange>) {
        val zone = ZoneId.of(source.zoneId)
        val windows = DailyWindows.build(source.date, 14, zone,
            DeskOptions.defaults(source.preferences,source.settings),
            source.workTimes.associate { LocalDate.parse(it.date) to MinuteRange(it.startMinute,it.endMinute) }, DeskOptions.weekdays(source.preferences))
        AgentChanges.validate(changes, source.tasks.map { AgentTaskFact(it.id,it.done,it.deadline) },
            source.blocks.map { AgentBlockFact(it.id,it.taskId,it.start,it.end,it.locked,it.done) }, System.currentTimeMillis(),windows)
    }

    suspend fun complete(task: TaskRow, rating: Int, owner: String = "guest") {
        require(rating in 1..5)
        db.withTransaction {
            val current = dao.tasks(owner).find { it.id == task.id } ?: error("事项已不存在")
            if (!current.done) {
                dao.save(current.copy(done = true))
                dao.setBlocksDone(current.id, true, owner)
                dao.addCheckIn(
                    CheckInRow(
                        UUID.randomUUID().toString(),
                        current.id,
                        System.currentTimeMillis(),
                        rating,
                        ownerId = owner,
                    )
                )
            }
        }
    }

    suspend fun reopen(task: TaskRow, owner: String = "guest") {
        require(task.ownerId == owner || task.ownerId == "guest") { "不能跨账户恢复事项" }
        db.withTransaction {
            dao.save(task.copy(ownerId = owner, done = false))
            dao.setBlocksDone(task.id, false, owner)
        }
    }

    suspend fun settings(start: Int, end: Int, owner: String = "guest") {
        require(start in 0..23 && end in 1..24 && start < end)
        val existing = dao.settings(owner)
        dao.save(
            SettingRow(
                startHour = start,
                endHour = end,
                ownerId = owner,
                clientId = existing?.clientId,
                serverVersion = existing?.serverVersion ?: 0,
                updatedAt = existing?.updatedAt ?: 0,
                timeZone = existing?.timeZone,
            )
        )
    }

    suspend fun todayWorkTime(start: Int, end: Int, date: LocalDate, owner: String = "guest") {
        require(date == LocalDate.now(DeskOptions.zone(dao.deviceOptions()))) { "日期已变化，请重新选择今日时段" }
        require(start in 0..1439 && end in start..1439) { "请选择有效起止时刻" }
        val existing = dao.workTimes(owner).find { it.date == date.toString() }
        dao.save(
            DailyWorkTimeRow(
                date.toString(),
                start,
                end,
                ownerId = owner,
                clientId = existing?.clientId,
                serverVersion = existing?.serverVersion ?: 0,
                updatedAt = existing?.updatedAt ?: 0,
                timeZone = existing?.timeZone,
            )
        )
    }

    /** Explicit user-confirmed conversion; historical instants and durations remain intact. */
    suspend fun changeZone(
        choice: DeskZoneChoice,
        adjustSchedule: Boolean,
        expected: DeskZoneChoice,
        expectedZoneId: String? = null,
        owner: String = "guest",
    ) = db.withTransaction {
        val options=dao.deviceOptions()
        check(DeskOptions.zoneChoice(options)==expected) { "时区已变化，请重新选择" }
        val now=System.currentTimeMillis();val from=DeskOptions.zone(options);val to=choice.effective(Instant.ofEpochMilli(now))
        check(expectedZoneId==null||from.id==expectedZoneId) { "系统时区已变化，请重新选择" }
        if(adjustSchedule&&from!=to) {
            val tasks=dao.tasks(owner);val blocks=dao.blocks(owner)
            val shiftedTasks=tasks.map { t->if(!t.done&&t.deadline>now)t.copy(deadline=DeskZones.moveKeepingLocal(t.deadline,from,to))else t }
            require(shiftedTasks.none { !it.done&&it.deadline>0&&tasks.first { old->old.id==it.id }.deadline>now&&it.deadline<=now }) { "同步后有截止时间早于现在" }
            val moved=blocks.filter { !it.done&&it.start>now }.associate { b->
                val start=DeskZones.moveKeepingLocal(b.start,from,to)
                require(start>now) { "同步后有安排早于现在" }
                b.id to b.copy(start=start,end=Math.addExact(start,b.end-b.start))
            }
            val result=blocks.map { moved[it.id]?:it }
            for(b in moved.values) {
                require(result.none { it.id!=b.id&&it.start<b.end&&it.end>b.start }) { "同步后有安排重叠" }
                val task=shiftedTasks.find{it.id==b.taskId}
                require(task==null||task.deadline==0L||b.end<=task.deadline) { "同步后安排晚于截止时间" }
            }
            for(b in result.filter{!it.done}) {
                val task=shiftedTasks.find{it.id==b.taskId}
                require(task==null||task.done||task.deadline==0L||b.end<=task.deadline) { "同步后安排晚于截止时间" }
            }
            shiftedTasks.filterIndexed { i,t->t!=tasks[i] }.forEach { dao.save(it) }
            if(moved.isNotEmpty()){dao.clearBlocks(owner);dao.insertBlocks(result)}
        }
        dao.saveOptions(listOf(UiPreferenceRow("device","time.zone",choice.id),UiPreferenceRow("device","time.dst",choice.daylight.toString())))
    }

    suspend fun snapshot(owner: String = "guest"):DeskSnapshot {
        val options=dao.deviceOptions();val zone=DeskOptions.zone(options)
        return DeskSnapshot(
            dao.tasks(owner),
            dao.blocks(owner),
            dao.settings(owner) ?: SettingRow(ownerId = owner),
            dao.workTimes(owner),
            LocalDate.now(zone),
            zone.id,
            options,
        )
    }
    suspend fun saveDiary(owner:String,date:String,content:String,expected:DiaryRow?,sourceMessageId:String?=null) = db.withTransaction {
        require(owner.isNotBlank());LocalDate.parse(date)
        check(dao.diary(owner,date)==expected){"日记已变化，请重新打开"}
        dao.save(DiaryRow(owner,date,DiaryContent.validate(content),System.currentTimeMillis().coerceAtLeast((expected?.updatedAt?:0)+1),sourceMessageId))
    }
    suspend fun deleteDiary(row:DiaryRow) { check(dao.deleteDiary(row.ownerId,row.date,row.updatedAt)==1){"日记已变化，请重新打开"} }
    suspend fun saveReminder(row:TaskReminderRow) {
        require(dao.tasks(row.ownerId).any{it.id==row.taskId}){"事项不存在"}
        require(ReminderMethod.entries.any{it.code==row.method}&&ReminderTiming.entries.any{it.code==row.timing})
        dao.save(row)
    }

    suspend fun preview(owner: String = "guest"): PlanPreview = db.withTransaction {
        val source = snapshot(owner)
        val now = Instant.now()
        val zone = ZoneId.of(source.zoneId)
        val today = source.date
        val windows =
            DailyWindows.build(
                today,
                7,
                zone,
                DeskOptions.defaults(source.preferences,source.settings),
                source.workTimes.associate {
                    LocalDate.parse(it.date) to MinuteRange(it.startMinute, it.endMinute)
                },
                DeskOptions.weekdays(source.preferences),
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

    suspend fun apply(preview: PlanPreview, owner: String = "guest") {
        db.withTransaction {
            check(snapshot(owner) == preview.source) { "事项或计划已变化，请重新预览" }
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
            dao.clearBlocks(owner)
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
                        ownerId = owner,
                    )
                }
            )
        }
    }
}
