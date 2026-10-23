package io.whaletodo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.whaletodo.app.data.*
import io.whaletodo.app.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Teal = Color(0xFF176B74)
private val Coral = Color(0xFFCC704F)
private val Cream = Color(0xFFF5F5EE)
private val Ink = Color(0xFF173E42)
private val dateTime =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val clock = DateTimeFormatter.ofPattern("HH:mm")

private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme =
                    if (dark)
                        darkColorScheme(
                            primary = Color(0xFF91D2CD),
                            secondary = Color(0xFFFFBA9C),
                            primaryContainer = Color(0xFF204C50),
                            secondaryContainer = Color(0xFF264C48),
                            onPrimaryContainer = Color(0xFFC0E6DC),
                            surfaceContainerHigh = Color(0xFF213638),
                            surfaceContainerHighest = Color(0xFF2B4243),
                        )
                    else
                        lightColorScheme(
                            primary = Teal,
                            secondary = Coral,
                            background = Cream,
                            surface = Color.White,
                            onBackground = Ink,
                            onSurface = Ink,
                            primaryContainer = Color(0xFFDDECE5),
                            secondaryContainer = Color(0xFFDDECE5),
                            onSecondaryContainer = Ink,
                            onPrimaryContainer = Ink,
                            surfaceContainerHigh = Color(0xFFF1F4ED),
                            surfaceContainerHighest = Color(0xFFE4EADF),
                        )
            ) {
                DeskApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskApp(vm: DeskViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<TaskRow?>(null) }
    var rating by remember { mutableStateOf<TaskRow?>(null) }
    var settings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message)
            vm.message.value = ""
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("今天" to "◉", "计划" to "▦", "待办" to "✓").forEachIndexed { i, (title, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 23.sp) },
                        label = { Text(title) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { capture = true },
                containerColor = Teal,
                contentColor = Color.White,
            ) {
                Text("＋", fontSize = 30.sp)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("WhaleToDo", fontWeight = FontWeight.Bold, fontSize = 25.sp)
                    Text(
                        state.today.format(
                            DateTimeFormatter.ofPattern(
                                "M月d日 EEEE",
                                java.util.Locale.SIMPLIFIED_CHINESE,
                            )
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { settings = true }) {
                    val time = todayRange(state)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WorkTime", fontSize = 12.sp)
                        Text(
                            "${minuteLabel(time.first)}–${minuteLabel(time.second)}",
                            fontSize = 10.sp,
                        )
                    }
                }
                Text("🐋", fontSize = 29.sp)
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 ->
                    TodayPage(
                        state,
                        onCapture = { capture = true },
                        onPlan = { vm.arrange() },
                        onEdit = { edit = it },
                        onComplete = { rating = it },
                    )
                1 -> PlanPage(state, onPlan = { vm.arrange() }, onEdit = { edit = it })
                else ->
                    TasksPage(
                        state,
                        onEdit = { edit = it },
                        onComplete = { if (it.done) vm.reopen(it) else rating = it },
                        onShare = { task ->
                            val text = buildString {
                                append("${if(task.done) "✓" else "○"} ${task.title}")
                                if (task.deadline > 0)
                                    append("\n截止 ${local(task.deadline).format(dateTime)}")
                                append("\n来自 WhaleToDo")
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    },
                                    "分享事项",
                                )
                            )
                        },
                    )
            }
        }
    }
    if (capture)
        CaptureDialog(
            onDismiss = { capture = false },
            onSave = {
                vm.capture(it)
                capture = false
            },
            onDetailedSave = {
                vm.save(it)
                capture = false
            },
        )
    edit?.let { task ->
        EditDialog(
            task,
            onDismiss = { edit = null },
            onSave = {
                vm.save(it)
                edit = null
            },
        )
    }
    rating?.let { task ->
        RatingDialog(
            task,
            onDismiss = { rating = null },
            onRate = {
                vm.complete(task, it)
                rating = null
            },
        )
    }
    if (settings) {
        val date = remember { state.today }
        val range = remember { todayRange(state) }
        WorkTimeDialog(
            range.first,
            range.second,
            onDismiss = { settings = false },
            onSave = { start, end ->
                vm.todayWorkTime(start, end, date)
                settings = false
            },
        )
    }
    preview?.let { proposal ->
        AlertDialog(
            onDismissRequest = { vm.preview.value = null },
            title = { Text("确认新计划") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("未来7天 · ${proposal.result.blocks.size}个时间块", fontWeight = FontWeight.Bold)
                    if (proposal.result.blocks.isEmpty()) Text("暂无可安排事项，请补充工时与截止时间。")
                    proposal.result.blocks.forEach { b ->
                        val title = state.tasks.find { it.id == b.taskId }?.title ?: "事项"
                        Text(
                            "${local(b.window.start.toEpochMilli()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))}–${local(b.window.end.toEpochMilli()).format(clock)}  $title",
                            fontSize = 13.sp,
                        )
                    }
                    proposal.result.unmet.forEach { u ->
                        Text(
                            "${state.tasks.find { it.id==u.taskId }?.title}：剩余${u.remainingMinutes}分钟 · ${u.reason}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.apply() }, enabled = !working) { Text("采用计划") }
            },
            dismissButton = { TextButton(onClick = { vm.preview.value = null }) { Text("返回") } },
        )
    }
}

@Composable
private fun TodayPage(
    s: DeskState,
    onCapture: () -> Unit,
    onPlan: () -> Unit,
    onEdit: (TaskRow) -> Unit,
    onComplete: (TaskRow) -> Unit,
) {
    val done = s.tasks.count { it.done }
    val today = s.today
    val blocks = s.blocks.filter { local(it.start).toLocalDate() == today }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 90.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Teal),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text(
                        "给今天，留一点从容。",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "先记下来，再慢慢安排。",
                        fontSize = 13.sp,
                        color = Color(0xFFD2E8E5),
                        modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Stat("${s.tasks.count { !it.done }}", "待办")
                        Stat("${blocks.size}", "今日时段")
                        Stat("$done", "已完成")
                    }
                    if (s.tasks.isNotEmpty())
                        LinearProgressIndicator(
                            progress = { done.toFloat() / s.tasks.size },
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                            color = Color(0xFFEEBA91),
                            trackColor = Color.White.copy(alpha = .2f),
                        )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onCapture, modifier = Modifier.weight(1f)) {
                    Text("＋ 快速记下")
                }
                OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) { Text("自动安排") }
            }
        }
        item { SectionTitle("今天的节奏", "${blocks.sumOf { (it.end-it.start)/60000 }} 分钟") }
        if (blocks.isEmpty()) item { EmptyCard("今天还没有时间安排", "补齐待办信息后，试试自动安排。") }
        items(blocks, key = { it.id }) { b ->
            TimeCard(b, s.tasks.find { it.id == b.taskId }, onEdit)
        }
        item {
            SectionTitle(
                "收集箱",
                "${s.tasks.count { !it.done && (it.minutes==0 || it.deadline==0L) }} 项待整理",
            )
        }
        val inbox = s.tasks.filter { !it.done && (it.minutes == 0 || it.deadline == 0L) }
        if (inbox.isEmpty()) item { EmptyCard("想到了什么？", "点击 ＋，一句话就能记下来。") }
        items(inbox.take(5), key = { it.id }) { TaskCard(it, onEdit, onComplete) }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 11.sp, color = Color(0xFFD2E8E5))
    }
}

@Composable
private fun SectionTitle(title: String, side: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(side, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun PlanPage(s: DeskState, onPlan: () -> Unit, onEdit: (TaskRow) -> Unit) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    val blocks = s.blocks.filter { local(it.start).toLocalDate() == day }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { day = day.minusDays(1) }) { Text("‹ 前一天") }
            Text(day.format(DateTimeFormatter.ofPattern("M月d日 E")), fontWeight = FontWeight.Bold)
            TextButton(onClick = { day = day.plusDays(1) }) { Text("后一天 ›") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = LocalDate.now() }) { Text("今天") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = onPlan) { Text("重新安排") }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 90.dp),
        ) {
            if (blocks.isEmpty()) item { EmptyCard("留白也是一种节奏", "这一天暂无已安排的事项。") }
            items(blocks, key = { it.id }) { b ->
                TimeCard(b, s.tasks.find { it.id == b.taskId }, onEdit)
            }
        }
    }
}

@Composable
private fun TimeCard(b: BlockRow, t: TaskRow?, onEdit: (TaskRow) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(51.dp)) {
            Text(local(b.start).format(clock), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                local(b.end).format(clock),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            Modifier.weight(1f).clickable { t?.let(onEdit) },
            shape = RoundedCornerShape(15.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (b.done) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                ),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "事项", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${(b.end-b.start)/60000}分钟${if(b.done) " · 已完成" else ""}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    if (b.done) "✓" else "●",
                    color = if ((t?.importance ?: 3) >= 4) Coral else Teal,
                )
            }
        }
    }
}

@Composable
private fun TasksPage(
    s: DeskState,
    onEdit: (TaskRow) -> Unit,
    onComplete: (TaskRow) -> Unit,
    onShare: (TaskRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showDone by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索待办") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("显示已完成", fontSize = 13.sp)
            Switch(
                checked = showDone,
                onCheckedChange = { showDone = it },
                modifier = Modifier.semantics { contentDescription = "显示已完成事项" },
            )
            Spacer(Modifier.weight(1f))
            Text("${s.tasks.count { !it.done }} 项待办", fontSize = 12.sp)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 90.dp),
        ) {
            val filtered =
                s.tasks.filter {
                    (showDone || !it.done) &&
                        (it.title.contains(query, true) || it.note.contains(query, true))
                }
            if (filtered.isEmpty()) item { EmptyCard("这里还很安静", "点击 ＋ 记下一件想做的事。") }
            items(filtered, key = { it.id }) { task ->
                Column {
                    TaskCard(task, onEdit, onComplete)
                    TextButton(
                        onClick = { onShare(task) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("分享", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskRow, onEdit: (TaskRow) -> Unit, onComplete: (TaskRow) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = t.done,
                onCheckedChange = { onComplete(t) },
                modifier = Modifier.semantics { contentDescription = "完成状态 ${t.title}" },
            )
            Column(Modifier.weight(1f).clickable { onEdit(t) }.padding(start = 4.dp)) {
                Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        if (t.deadline > 0)
                            append(
                                local(t.deadline).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                            )
                        else append("未设截止")
                        append(if (t.minutes > 0) " · ${t.minutes}分钟" else " · 待估时")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Text(
                "★".repeat(t.importance),
                color = Coral,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private fun todayRange(state: DeskState): Pair<Int, Int> {
    val today = state.workTimes.find { it.date == state.today.toString() }
    return if (today != null) today.startMinute to today.endMinute
    else (state.settings.startHour * 60) to (state.settings.endHour * 60).coerceAtMost(1439)
}

@Composable
private fun CaptureDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDetailedSave: (TaskRow) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var detailed by remember { mutableStateOf(false) }
    val draft = remember { TaskRow(UUID.randomUUID().toString(), "") }
    if (detailed) {
        EditDialog(
            draft.copy(title = title),
            onDismiss = { detailed = false },
            onSave = onDetailedSave,
            isNew = true,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("记下一件事") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    placeholder = { Text("想做什么？") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text("记下") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { detailed = true }) { Text("详细") }
                }
            },
        )
    }
}

@Composable
private fun EditDialog(
    t: TaskRow,
    onDismiss: () -> Unit,
    onSave: (TaskRow) -> Unit,
    isNew: Boolean = false,
) {
    var title by remember(t.id) { mutableStateOf(t.title) }
    var effort by remember(t.id) { mutableStateOf(if (t.minutes > 0) t.minutes.toString() else "") }
    var unit by remember { mutableIntStateOf(0) }
    var due by remember(t.id) { mutableLongStateOf(t.deadline) }
    var choosingDeadline by remember { mutableStateOf(false) }
    var importance by remember { mutableIntStateOf(t.importance) }
    var note by remember { mutableStateOf(t.note) }
    var split by remember { mutableStateOf(t.splittable) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "记下一件事" else "整理待办") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    label = { Text("标题") },
                    singleLine = true,
                )
                OutlinedCard(
                    onClick = { choosingDeadline = true },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择截止时间" },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "截止时间",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (due > 0) local(due).format(dateTime) else "选择日期与时间",
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                        Text("◷", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (due > 0) TextButton(onClick = { due = 0 }) { Text("清除截止时间", fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = effort,
                        onValueChange = { effort = it },
                        label = { Text("预计工作量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        listOf("分钟", "小时", "天").forEachIndexed { i, u ->
                            Row(
                                Modifier.clickable { unit = i },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = unit == i,
                                    onClick = { unit = i },
                                    modifier = Modifier.size(25.dp),
                                )
                                Text(u, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Text("重要度", fontSize = 12.sp)
                Row {
                    (1..5).forEach { i ->
                        TextButton(
                            onClick = { importance = i },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (i <= importance) "★" else "☆", fontSize = 24.sp, color = Coral)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可拆分为60分钟时间块", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = split, onCheckedChange = { split = it })
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 2000) note = it },
                    label = { Text("备注") },
                    maxLines = 3,
                )
                if (error.isNotEmpty())
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        require(title.isNotBlank()) { "请填写标题" }
                        val raw = if (effort.isBlank()) 0.0 else effort.toDouble()
                        require(raw.isFinite() && raw >= 0) { "请填写有效工作量" }
                        val minutes = raw * listOf(1, 60, 1440)[unit]
                        require(minutes <= 525600 && (raw == 0.0 || minutes >= 1)) { "工作量需为1分钟至1年" }
                        val deadline = due
                        onSave(
                            t.copy(
                                title = title,
                                minutes = kotlin.math.ceil(minutes).toInt(),
                                deadline = deadline,
                                importance = importance,
                                note = note,
                                splittable = split,
                            )
                        )
                    } catch (e: Exception) {
                        error = e.message ?: "请检查输入"
                    }
                }
            ) {
                Text(if (isNew) "记下" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (choosingDeadline)
        DeadlinePickerDialog(
            due,
            onDismiss = { choosingDeadline = false },
            onSave = {
                due = it
                choosingDeadline = false
            },
        )
}

@Composable
private fun RatingDialog(t: TaskRow, onDismiss: () -> Unit, onRate: (Int) -> Unit) {
    var rate by remember { mutableIntStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("完成：${t.title}") },
        text = {
            Column {
                Text("这次感觉怎么样？")
                listOf("失败", "不甚理想", "一般", "不错", "完美").forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable { rate = i + 1 },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = rate == i + 1, onClick = { rate = i + 1 })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRate(rate) }) { Text("完成打卡") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}
