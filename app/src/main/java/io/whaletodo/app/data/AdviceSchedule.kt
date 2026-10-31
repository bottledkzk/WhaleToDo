package io.whaletodo.app.data

import io.whaletodo.core.*
import java.time.*
import java.time.format.DateTimeFormatter

data class AdviceArrangement(val preview: PlanPreview, val taskIds: Set<String>, val maxWork: Int,
    val rest: Int, val generatedAt: Long, val firstDate: LocalDate = preview.source.date, val days: Int = 7,
    val requestedWindow: MinuteRange? = null)

internal fun AdviceArrangement.display(): String = buildString {
    val source=preview.source
    val zone=ZoneId.of(source.zoneId)
    val format=DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone)
    append("${taskIds.size}件事项的安排建议 · ${source.zoneId}\n")
    if(rest>0)append("连续工作最多${maxWork}分钟，至少休息${rest}分钟\n")
    var previous: Block?=null
    for(b in preview.result.blocks.filter{it.taskId in taskIds}) {
        previous?.let { p ->
            if(rest>0&&p.window.end<b.window.start&&p.window.end.atZone(zone).toLocalDate()==b.window.start.atZone(zone).toLocalDate()) {
                val free=Scheduler.subtract(listOf(Window(p.window.end,b.window.start)),preview.result.blocks.map{it.window})
                for(gap in free.filter{it.minutes>=rest})
                    append("${format.format(gap.start)}–${format.format(gap.end)} 休息（${gap.minutes}分钟）\n")
            }
        }
        append("${format.format(b.window.start)}–${format.format(b.window.end)} ${source.tasks.first{it.id==b.taskId}.title}（${b.window.minutes}分钟${if(b.locked)"，固定" else ""}）\n")
        previous=b
    }
    append("预览后可采纳安排")
}

/** The model identifies relevant work; the local scheduler computes every executable clock. */
internal fun arrangeAdvice(source: DeskSnapshot, question: String, answer: String, now: Long): AdviceArrangement {
    val unfinished = source.tasks.filterNot { it.done }
    val selected = unfinished.filter { task ->
        val short = task.title.substringAfterLast('·').trim()
        val names=listOf(task.title)+listOfNotNull(short.takeIf{it.length>=2&&unfinished.count{other->other.title.substringAfterLast('·').trim()==it}==1})
        val excluded=names.any { name ->
            Regex("(?:不安排|不排|不要|别|不必|不用|无需|排除|除了)[^，。；\\n]{0,60}"+Regex.escape(name)).containsMatchIn(question)||
                Regex(Regex.escape(name)+"[^，。；\\n]{0,8}(?:不|不要|不必|不用|无需)(?:安排|排|调整|移动)").containsMatchIn(question)
        }
        val explicit=names.any{question.contains(it)}
        !excluded&&(explicit||!Regex("只(?:安排|调整|优化|处理)").containsMatchIn(question)&&names.any{answer.contains(it)})
    }
    require(selected.isNotEmpty()) { "请在建议中明确要安排的事项" }
    require(selected.size <= 40) { "请缩小本次安排范围" }
    require(selected.all { it.minutes > 0 }) { "请先补充相关事项的预计工时" }
    val work = Regex("(?:连续(?:工作)?|每工作)\\s*(\\d{1,3})\\s*分钟").find(question)
    val pause = Regex("休息\\s*(\\d{1,3})\\s*分钟").find(question)
    require(!question.contains("连续")||work!=null) { "请用分钟明确连续工作时长" }
    require(work==null||pause!=null) { "请补充每次休息多少分钟，再生成安排" }
    require(!question.contains("休息")||pause!=null) { "请用分钟明确休息时长" }
    val maxWork = work?.groupValues?.get(1)?.toInt() ?: 480
    val rest = pause?.groupValues?.get(1)?.toInt() ?: 0
    require(maxWork in 15..480 && rest in 0..120 && (pause==null||rest>0)) { "请使用15–480分钟的连续工作时长和1–120分钟的休息" }
    val dayRange=Regex("(?:\\d{1,2}\\s*月\\s*\\d{1,2}\\s*(?:日|号)?|\\d{1,2}\\s*(?:日|号))\\s*(?:到|至|[-–~～])\\s*(?:\\d{1,2}\\s*月\\s*)?\\d{1,2}\\s*(?:日|号)")
    require(!dayRange.containsMatchIn(question)) { "暂不支持日期区间，请指定一天、本周或下周" }
    val datePattern=Regex("(\\d{4})(?:年|[-/])(\\d{1,2})(?:月|[-/])(\\d{1,2})日?")
    val dates=runCatching {
        val full=datePattern.findAll(question).map { LocalDate.of(it.groupValues[1].toInt(),it.groupValues[2].toInt(),it.groupValues[3].toInt()) }.toList()
        val short=Regex("(?<![\\d:/-])(\\d{1,2})(?:月|/|-)(\\d{1,2})日?(?![\\d:])").findAll(datePattern.replace(question,""))
            .map{LocalDate.of(source.date.year,it.groupValues[1].toInt(),it.groupValues[2].toInt())}.toList()
        (full+short).distinct()
    }.getOrElse { throw IllegalArgumentException("日期无效，请核对年月日") }
    val relative=Regex("今天|明天|后天|下周|本周").findAll(question).map{it.value}.distinct().toList()
    require(dates.size<=1) { "暂不支持日期区间，请指定一天、本周或下周" }
    require(dates.size+relative.size<=1&&!Regex("大后天|明年|下个月|下月|周末|月底|明早|明晚|(?:周|星期)[一二三四五六日天]").containsMatchIn(question)) { "请明确本次安排的单个日期或周范围" }
    val firstDate=dates.firstOrNull()?:when(relative.firstOrNull()) {
        "明天"->source.date.plusDays(1);"后天"->source.date.plusDays(2)
        "下周"->source.date.plusDays((8-source.date.dayOfWeek.value).toLong())
        else->source.date
    }
    val days=when { dates.isNotEmpty()||relative.firstOrNull() in setOf("今天","明天","后天")->1
        relative.firstOrNull()=="本周"->8-source.date.dayOfWeek.value;else->7 }
    require(!firstDate.isBefore(source.date)) { "请选择今天或未来日期" }
    val hours=Regex("(?:可用(?:时间)?|空闲(?:时间)?|工作时间|默认|安排时间)(?:是|为)?\\s*(\\d{1,2})(?::([0-5]\\d))?点?\\s*(?:到|至|[-–])\\s*(\\d{1,2})(?::([0-5]\\d))?点?").find(question)
        ?:Regex("(?<![\\d:/-])(\\d{1,2})(?::([0-5]\\d)|点)\\s*(?:到|至|[-–])\\s*(\\d{1,2})(?::([0-5]\\d)|点)(?!\\d)").find(question)
    require(hours!=null||!Regex("\\d{1,2}[:点]\\d{0,2}\\s*(?:到|至|[-–])").containsMatchIn(question)) { "请核对时间区间，使用HH:mm到HH:mm" }
    val period=Regex("(?:今天|明天|后天)\\s*(上午|下午|晚上|中午|早上)").find(question)?.groupValues?.get(1)
    val requestedWindow=if(hours!=null) {
        val start=hours.groupValues[1].toInt()*60+(hours.groupValues[2].toIntOrNull()?:0)
        val end=hours.groupValues[3].toInt()*60+(hours.groupValues[4].toIntOrNull()?:0)
        require(start in 0..1439&&end in 1..1440&&end>start){"请给出同一天内有效的可用时间"};MinuteRange(start,end)
    } else when(period) {"上午","早上"->MinuteRange(480,720);"下午"->MinuteRange(720,1080);"晚上"->MinuteRange(1080,1440);"中午"->MinuteRange(660,840);else->null}
    val ids = selected.map { it.id }.toSet()
    return AdviceArrangement(PlanPreview(source, executableAdvicePlan(source, ids, maxWork, rest, now, firstDate, days, requestedWindow)), ids, maxWork, rest, now, firstDate, days, requestedWindow)
}

internal fun executableAdvicePlan(source: DeskSnapshot, ids: Set<String>, maxWork: Int, rest: Int, now: Long,
    firstDate: LocalDate = source.date, days: Int = 7, requestedWindow: MinuteRange? = null): ScheduleResult {
    require(ids.isNotEmpty() && ids.size <= 40 && maxWork in 15..480 && rest in 0..120 && days in 1..7 && firstDate >= source.date)
    require(rest>0||maxWork==480) { "请补充休息时长，连续工作上限不能被忽略" }
    val tasks = source.tasks.filter { it.id in ids }
    require(tasks.size == ids.size && tasks.all { !it.done && it.minutes > 0 }) { "安排事项已变化，请重新生成建议" }
    require(rest == 0 || tasks.none { !it.splittable && it.category !in setOf("rest", "exercise") && it.minutes > maxWork }) {
        "不可拆分事项超过连续工作上限，请先调整要求"
    }
    val zone = ZoneId.of(source.zoneId)
    val configured = DailyWindows.build(firstDate, days, zone, DeskOptions.defaults(source.preferences, source.settings),
        source.workTimes.associate { LocalDate.parse(it.date) to MinuteRange(it.startMinute, it.endMinute) }, DeskOptions.weekdays(source.preferences))
    val windows=if(requestedWindow==null)configured else {
        val requested=DailyWindows.build(firstDate,days,zone,requestedWindow,emptyMap())
        configured.flatMap { original -> requested.mapNotNull { allowed ->
            val start=maxOf(original.start,allowed.start);val end=minOf(original.end,allowed.end)
            if(start<end)Window(start,end)else null
        } }
    }
    val from = Instant.ofEpochMilli(((now + 59_999) / 60_000) * 60_000)
    fun block(row: BlockRow) = Block(row.taskId, Window(Instant.ofEpochMilli(row.start), Instant.ofEpochMilli(row.end)), row.locked, row.done)
    val retained = source.blocks.filter { it.taskId !in ids || it.locked || it.done || it.start <= now }.map(::block)
    val planned = retained.toMutableList()
    val resting = source.tasks.filter { it.category in setOf("rest", "exercise") }.map { it.id }.toSet()
    fun acceptable(candidate: Block?): Boolean {
        if (rest == 0) return true
        val work = (planned + listOfNotNull(candidate)).filter { !it.done && it.taskId !in resting && it.window.end > from &&
            it.window.end > firstDate.atStartOfDay(zone).toInstant() && it.window.start < firstDate.plusDays(days.toLong()).atStartOfDay(zone).toInstant() }.sortedBy { it.window.start }
        var end: Instant? = null
        var run = 0L
        for (b in work) {
            if (end == null || Duration.between(end, b.window.start).toMinutes() >= rest) run = 0
            run += b.window.minutes
            if (run > maxWork) return false
            end = b.window.end
        }
        return true
    }
    require(acceptable(null)) { "固定安排已超过连续工作上限，请先调整要求" }
    for (task in tasks.sortedWith(compareBy<TaskRow> { if (it.deadline > 0) it.deadline else Long.MAX_VALUE }.thenByDescending { it.importance }.thenBy { it.id })) {
        var remaining = (task.minutes - retained.filter { it.taskId == task.id }.sumOf { it.window.minutes }).coerceAtLeast(0).toInt()
        require(task.splittable || retained.none { it.taskId == task.id } || remaining == 0) { "${task.title}不可拆分，请先核对已有安排" }
        val free = Scheduler.subtract(windows.mapNotNull { w -> maxOf(w.start, from).let { if (it < w.end) Window(it, w.end) else null } }, planned.map { it.window })
        val deadline = task.deadline.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        for (slot in free) {
            if (remaining == 0) break
            val limit = minOf(slot.end, deadline ?: slot.end)
            var cursor = slot.start
            while (remaining > 0 && cursor < limit) {
                val capacity = Duration.between(cursor, limit).toMinutes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                var take = if (task.splittable) minOf(remaining, task.chunkMinutes, maxWork, capacity) else remaining.takeIf { it <= capacity } ?: 0
                var candidate: Block? = null
                while (take > 0) {
                    if (take < 15 && take < remaining) break
                    val proposed = Block(task.id, Window(cursor, cursor.plusSeconds(take * 60L)))
                    if (acceptable(proposed)) { candidate = proposed; break }
                    if (!task.splittable) break
                    take--
                }
                if (candidate == null) cursor = cursor.plusSeconds(60)
                else { planned += candidate; remaining -= take; cursor = candidate.window.end }
                require(planned.size <= retained.size + 120) { "本次安排过多，请缩小范围" }
            }
        }
        require(remaining == 0) { "${task.title}仍缺${remaining}分钟可用时间，请调整截止或休息要求" }
    }
    return ScheduleResult(planned.sortedWith(compareBy<Block> { it.window.start }.thenBy { it.taskId }), emptyList())
}
