package io.whaletodo.app.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.whaletodo.app.data.DeskOptions
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimedReminderDialog(s:ProfileState,c:ProfileController,dismiss:()->Unit) {
    val zone=DeskOptions.zone(s.deviceOptions)
    var title by remember{mutableStateOf("")}
    var relative by remember{mutableStateOf(true)}
    var hours by remember{mutableStateOf("0")}
    var minutes by remember{mutableStateOf("10")}
    var method by remember{mutableStateOf("message")}
    var local by remember{mutableStateOf(LocalDateTime.now(zone).plusMinutes(10).withSecond(0).withNano(0))}
    var datePicker by remember{mutableStateOf(false)}
    var timePicker by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    if(datePicker) {
        val date=rememberDatePickerState(local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={date.selectedDateMillis?.let{local=LocalDateTime.of(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(),local.toLocalTime());datePicker=false;timePicker=true}}){Text("下一步")}},dismissButton={TextButton(onClick={datePicker=false}){Text("取消")}}){DatePicker(date,showModeToggle=false)}
        return
    }
    if(timePicker) {
        val time=rememberTimePickerState(local.hour,local.minute,is24Hour=true)
        AlertDialog(onDismissRequest={timePicker=false},title={Text("提醒时间")},text={TimePicker(time)},confirmButton={TextButton(onClick={local=local.withHour(time.hour).withMinute(time.minute);timePicker=false}){Text("确定")}},dismissButton={TextButton(onClick={timePicker=false}){Text("取消")}})
        return
    }
    AlertDialog(onDismissRequest=dismiss,title={Text("设置提醒")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it.take(120);error="";c.clearError()},label={Text("提醒内容")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(relative,{relative=true;error=""},{Text("相对时间")})
            FilterChip(!relative,{relative=false;error=""},{Text("定时提醒")})
        }
        if(relative)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(hours,{hours=it.filter(Char::isDigit).take(5);error=""},label={Text("小时")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f))
            OutlinedTextField(minutes,{minutes=it.filter(Char::isDigit).take(2);error=""},label={Text("分钟")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f))
        }else OutlinedButton(onClick={datePicker=true},modifier=Modifier.fillMaxWidth()){Text(local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))}
        ReminderChoices(method,{method=it})
        if(error.isNotBlank()||s.error.isNotBlank())Text(error.ifBlank{s.error},color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!s.busy,onClick={
        val content=title.trim().ifBlank{"提醒我"}
        if(relative) {
            val h=hours.toLongOrNull()?:0;val m=minutes.toLongOrNull()?:0;val delay=h*60+m
            if(m !in 0..59||delay !in 1..525600)error="请输入有效的小时和分钟"
            else c.timedReminder(content,method,afterMinutes=delay)
        }else {
            val offsets=zone.rules.getValidOffsets(local)
            val at=offsets.singleOrNull()?.let{local.toInstant(it).toEpochMilli()}
            if(at==null||at<=System.currentTimeMillis())error="请选择未来的有效时间"
            else c.timedReminder(content,method,atMillis=at)
        }
    }){Text("保存提醒")}},dismissButton={TextButton(onClick=dismiss,enabled=!s.busy){Text("取消")}})
}
