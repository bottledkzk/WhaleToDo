package io.whaletodo.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import java.time.format.DateTimeFormatter

private val Green = Color(0xFF27845B)
private val Red = Color(0xFFBE4D4A)
private val Yellow = Color(0xFFB98515)
private val fullTime = DateTimeFormatter.ofPattern("M/d HH:mm")
private fun at(ms: Long, s: DeskState) = Instant.ofEpochMilli(ms).atZone(ZoneId.of(s.zoneId))

internal fun taskScheduleLabels(s: DeskState, taskId: String, day: LocalDate? = null): List<String> =
    s.blocks.filter { it.taskId == taskId && (day == null ||
        at(it.start, s).toLocalDate() <= day && at(it.end - 1, s).toLocalDate() >= day) }
        .sortedBy { it.start }.map {
            val start = at(it.start, s); val end = at(it.end, s)
            val format = if (day != null && start.toLocalDate() == day && end.toLocalDate() == day)
                DateTimeFormatter.ofPattern("HH:mm") else fullTime
            "${start.format(format)}–${end.format(format)} · ${if (it.locked) "手动" else "自动"}"
        }

internal fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
    }, "分享事项"))
}

internal fun calendarTasks(s: DeskState): List<CalendarTask> {
    val completions = s.checkIns.groupBy { it.taskId }.mapValues { (_, checks) -> checks.maxOf { it.completedAt } }
    return s.tasks.map { t -> CalendarTask(t.id, t.deadline.takeIf { it > 0 }?.let(Instant::ofEpochMilli), t.done,
        completions[t.id]?.let(Instant::ofEpochMilli),
        s.blocks.filter { it.taskId == t.id }.map { Window(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end)) }) }
}
private fun dayTasks(s: DeskState, day: LocalDate): List<TaskRow> {
    val ids = ActivityCalendar.tasksOn(day, ZoneId.of(s.zoneId), calendarTasks(s)).map { it.id }.toSet()
    return s.tasks.filter { it.id in ids }
}
private fun taskText(t: TaskRow, s: DeskState): String = buildString {
    append("${if (t.done) "✓" else "○"} ${t.title}")
    if (t.deadline > 0) append("\n截止 ${at(t.deadline, s).format(fullTime)}")
    s.blocks.filter { it.taskId == t.id }.forEach {
        append("\n${at(it.start, s).format(fullTime)}–${at(it.end, s).format(fullTime)}${if (it.locked) " · 手动" else ""}")
    }
}
private fun dayText(s: DeskState, date: LocalDate): String =
    "$date · ${s.zoneId}\n" + dayTasks(s, date).joinToString("\n\n") { taskText(it, s) } + "\n来自 WhaleToDo"

@Composable
internal fun AgendaPage(s: DeskState, onPlan: () -> Unit, onEdit: (TaskRow) -> Unit,
    onComplete: (TaskRow) -> Unit, onShare: (String) -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var selectedDate by rememberSaveable { mutableStateOf(s.today.toString()) }
    val day = LocalDate.parse(selectedDate)
    var datePicker by remember { mutableStateOf(false) }
    val matches: (TaskRow) -> Boolean = { t -> (showDone || !t.done) &&
        (query.isBlank() || t.title.contains(query.trim(), true) || t.note.contains(query.trim(), true)) }
    val all = s.tasks.filter(matches)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("待办", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { search = !search; if (!search) query = "" },
                modifier = Modifier.semantics { contentDescription = "搜索事务" }) { Text("🔍", fontSize = 22.sp) }
            FilledTonalButton(onClick = onPlan) { Text("自动安排") }
        }
        if (search) OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text("搜索标题或备注") }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "搜索事务输入" },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Text("×") } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(view == 0, { view = 0 }, { Text("清单") })
            Spacer(Modifier.width(8.dp))
            FilterChip(view == 1, { view = 1 }, { Text("日程") })
            Spacer(Modifier.weight(1f))
            Text("已完成", style = MaterialTheme.typography.labelMedium)
            Switch(showDone, { showDone = it }, Modifier.semantics { contentDescription = "显示已完成事项" })
        }
        if (view == 1) DateBar(day, { selectedDate = it.toString() }, { datePicker = true },
            { onShare(dayText(s, day)) }, dayTasks(s, day).isNotEmpty())
        val rows = if (view == 0) all else dayTasks(s, day).filter(matches).sortedBy { t ->
            s.blocks.filter { it.taskId == t.id && at(it.start, s).toLocalDate() <= day &&
                at(it.end - 1, s).toLocalDate() >= day }.minOfOrNull { it.start } ?: t.deadline
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (view == 0) item { Text("${rows.size} 件事", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rows.isEmpty()) item { Text(if (query.isNotBlank()) "未找到匹配事务" else "暂无事项",
                Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.id }) { t ->
                AgendaTask(t, taskScheduleLabels(s, t.id, day.takeIf { view == 1 }), onEdit, onComplete,
                    { onShare(taskText(t, s) + "\n${s.zoneId} · 来自 WhaleToDo") })
            }
        }
    }
    if (datePicker) DayPicker(day, { datePicker = false }, { selectedDate = it.toString(); datePicker = false })
}

@Composable
private fun AgendaTask(t: TaskRow, times: List<String>, onEdit: (TaskRow) -> Unit,
    onComplete: (TaskRow) -> Unit, onShare: () -> Unit) {
    Column {
        TaskCard(t, onEdit, onComplete)
        times.take(4).forEach { time ->
            Text("◷ $time", Modifier.fillMaxWidth().clickable { onEdit(t) }.padding(start = 16.dp, top = 5.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (times.size > 4) TextButton(onClick = { onEdit(t) }) { Text("另有${times.size - 4}段安排") }
        TextButton(onClick = onShare, modifier = Modifier.align(Alignment.End).height(34.dp)
            .semantics { contentDescription = "分享 ${t.title}" }, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("分享", fontSize = 12.sp)
        }
    }
}

@Composable
private fun DateBar(day: LocalDate, onDate: (LocalDate) -> Unit, pick: () -> Unit,
    share: () -> Unit, canShare: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDate(day.minusDays(1)) }, Modifier.semantics { contentDescription = "前一天" }) { Text("‹", fontSize = 26.sp) }
        TextButton(onClick = pick) { Text(day.format(DateTimeFormatter.ofPattern("M月d日"))) }
        IconButton(onClick = { onDate(day.plusDays(1)) }, Modifier.semantics { contentDescription = "后一天" }) { Text("›", fontSize = 26.sp) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = share, enabled = canShare) { Text("分享当天") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(day: LocalDate, dismiss: () -> Unit, select: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
        TextButton(onClick = { picker.selectedDateMillis?.let { select(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            enabled = picker.selectedDateMillis != null) { Text("确定") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }) { DatePicker(picker, showModeToggle = false) }
}

@Composable
internal fun CalendarPage(s: DeskState, onEdit: (TaskRow) -> Unit, onComplete: (TaskRow) -> Unit,
    onShare: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(s.today.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(s.today).toString()) }
    val day = LocalDate.parse(selected)
    val month = YearMonth.parse(monthText)
    var pickingDate by remember { mutableStateOf(false) }
    fun navigateMonth(next: YearMonth) {
        monthText = next.toString()
        selected = next.atDay(day.dayOfMonth.coerceAtMost(next.lengthOfMonth())).toString()
    }
    val facts = remember(s.tasks, s.blocks, s.checkIns) { calendarTasks(s) }
    val zone = ZoneId.of(s.zoneId)
    val monthDays = (1..month.lengthOfMonth()).associate { n ->
        val date = month.atDay(n)
        date to ActivityCalendar.summary(date, zone, Instant.ofEpochMilli(s.now), facts)
    }
    val total = monthDays.values.fold(DayActivity()) { a, b ->
        DayActivity(a.completed + b.completed, a.overdue + b.overdue, a.pending + b.pending)
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("活动预览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = s.today.toString(); monthText = YearMonth.from(s.today).toString() }) { Text("今天") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigateMonth(month.minusMonths(1)) }, Modifier.semantics { contentDescription = "上个月" }) { Text("‹", fontSize = 26.sp) }
                Text("${month.year}年 ${month.monthValue}月", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { navigateMonth(month.plusMonths(1)) }, Modifier.semantics { contentDescription = "下个月" }) { Text("›", fontSize = 26.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
            repeat(cells / 7) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { weekday ->
                        val n = week * 7 + weekday - offset + 1
                        if (n !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(53.dp))
                        else {
                            val date = month.atDay(n); val counts = monthDays.getValue(date)
                            Column(Modifier.weight(1f).height(53.dp).padding(vertical = 2.dp)
                                .border(if (date == day) 2.dp else 0.dp,
                                    if (date == day) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                                .clickable { selected = date.toString() }
                                .semantics { contentDescription = "$date 完成${counts.completed} 逾期${counts.overdue} 待办${counts.pending}" }
                                .padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(n.toString(), fontSize = 13.sp, fontWeight = if (date == s.today) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.weight(1f))
                                Row(Modifier.fillMaxWidth().height(9.dp)) {
                                    listOf(counts.completed to Green, counts.overdue to Red, counts.pending to Yellow).forEach { (num, color) ->
                                        if (num > 0) Box(Modifier.weight(num.toFloat()).fillMaxHeight().background(color.copy(alpha = (0.55f + counts.total.coerceAtMost(5) * 0.09f))))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("完成 ${total.completed}" to Green, "逾期 ${total.overdue}" to Red, "待办 ${total.pending}" to Yellow).forEach { (label, color) ->
                    Text("● $label", color = color, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { DateBar(day, { selected = it.toString(); monthText = YearMonth.from(it).toString() },
            { pickingDate = true }, { onShare(dayText(s, day)) }, dayTasks(s, day).isNotEmpty()) }
        val tasks = dayTasks(s, day)
        if (tasks.isEmpty()) item { Text("这一天暂无记录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { t -> AgendaTask(t, taskScheduleLabels(s, t.id, day), onEdit, onComplete, { onShare(taskText(t, s) + "\n来自 WhaleToDo") }) }
    }
    if (pickingDate) DayPicker(day, { pickingDate = false }, {
        selected = it.toString(); monthText = YearMonth.from(it).toString(); pickingDate = false
    })
}
