package io.whaletodo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter

fun minuteLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(
    title: String,
    initial: Int,
    minimum: Int = 0,
    confirm: String = "确定",
    onDismiss: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true,
        )
    val minutes = state.hour * 60 + state.minute
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = state, layoutType = TimePickerLayoutType.Vertical)
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val next = (minutes - 1).coerceAtLeast(minimum)
                            state.hour = next / 60
                            state.minute = next % 60
                        },
                        enabled = minutes > minimum,
                        modifier = Modifier.semantics { contentDescription = "减少一分钟" },
                    ) {
                        Text("−")
                    }
                    Text(minuteLabel(minutes), style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = {
                            val next = (minutes + 1).coerceAtLeast(minimum).coerceAtMost(1439)
                            state.hour = next / 60
                            state.minute = next % 60
                        },
                        enabled = minutes < 1439,
                        modifier = Modifier.semantics { contentDescription = "增加一分钟" },
                    ) {
                        Text("＋")
                    }
                }
                Column(
                    Modifier.fillMaxWidth().height(44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${minuteLabel(minimum)}–23:59",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (minutes < minimum) "结束时间不能早于开始时间" else " ",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(minutes) }, enabled = minutes >= minimum) {
                Text(confirm)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                if (onPrevious != null) TextButton(onClick = onPrevious) { Text("上一步") }
            }
        },
    )
}

@Composable
fun WorkTimeDialog(
    initialStart: Int,
    initialEnd: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(initialStart) }
    key(step) {
        if (step == 0)
            ClockDialog(
                "今日 WorkTime · 开始",
                start,
                confirm = "下一步",
                onDismiss = onDismiss,
                onPick = {
                    start = it
                    step = 1
                },
            )
        else
            ClockDialog(
                "今日 WorkTime · 结束",
                maxOf(start, initialEnd),
                minimum = start,
                confirm = "保存",
                onDismiss = onDismiss,
                onPrevious = { step = 0 },
                onPick = { onSave(start, it) },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlinePickerDialog(initial: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val existing = remember {
        if (initial > 0) Instant.ofEpochMilli(initial).atZone(zone)
        else ZonedDateTime.now(zone).plusHours(1).withSecond(0).withNano(0)
    }
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                existing.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    var step by remember { mutableIntStateOf(0) }
    if (step == 0)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { step = 1 }, enabled = dateState.selectedDateMillis != null) {
                    Text("下一步")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        ) {
            DatePicker(
                state = dateState,
                title = { Text("截止日期", Modifier.padding(start = 24.dp, top = 18.dp)) },
                showModeToggle = false,
            )
        }
    else {
        val date =
            Instant.ofEpochMilli(dateState.selectedDateMillis!!)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        ClockDialog(
            "截止时间 · ${date.format(DateTimeFormatter.ofPattern("M月d日"))}",
            existing.hour * 60 + existing.minute,
            onDismiss = onDismiss,
            onPrevious = { step = 0 },
            onPick = {
                onSave(date.atTime(it / 60, it % 60).atZone(zone).toInstant().toEpochMilli())
            },
        )
    }
}
