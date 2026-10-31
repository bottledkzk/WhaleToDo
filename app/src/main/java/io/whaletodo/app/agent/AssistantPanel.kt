package io.whaletodo.app.agent

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.whaletodo.core.*
import kotlinx.coroutines.delay

@Composable internal fun AssistantPanel(controller:AssistantController,onAccount:()->Unit,onVip:()->Unit,onPoints:()->Unit) {
    val s by controller.state.collectAsState()
    var draft by rememberSaveable {mutableStateOf("")}
    var modeMenu by remember {mutableStateOf(false)}
    var styleMenu by remember { mutableStateOf(false) }
    var diaryStyleMenu by remember { mutableStateOf(false) }
    var history by remember {mutableStateOf(false)}
    var historySearch by rememberSaveable {mutableStateOf("")}
    val list=rememberLazyListState()
    LaunchedEffect(s.sessionId) { draft="" }
    LaunchedEffect(s.owner) { draft="";history=false;historySearch="" }
    LaunchedEffect(s.messages.lastOrNull()?.id) { if(s.messages.lastOrNull()?.role=="user"&&s.busySince!=null)draft="" }
    var elapsed by remember {mutableLongStateOf(0)}
    LaunchedEffect(s.busySince){while(s.busySince!=null){elapsed=(SystemClock.elapsedRealtime()-s.busySince!!)/1000;delay(1000)}}
    LaunchedEffect(s.messages.size,s.busySince){if(s.messages.isNotEmpty())list.animateScrollToItem(s.messages.size-1)}
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={controller.refresh();history=true},enabled=s.busySince==null){Text("历史")}
            Text(s.title,Modifier.weight(1f),maxLines=1,fontWeight=FontWeight.Bold)
            TextButton(onClick=controller::newSession,enabled=s.busySince==null){Text("＋ 新对话")}
        }
        if(s.messages.isEmpty())Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
            WhaleAvatar(Modifier.size(72.dp));Spacer(Modifier.height(16.dp));Text("把想法，变成下一步",style=MaterialTheme.typography.titleLarge)
        }else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)){
            items(s.messages,key={it.id}){m->
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(m.role=="user")Alignment.End else Alignment.Start){
                    Surface(shape=RoundedCornerShape(18.dp),color=if(m.role=="user")MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh){
                        SelectionContainerCompat(if(m.role=="assistant")AssistantIdentity.display(m.text)else m.text)
                    }
                    if(m.totalTokens>0) Row(Modifier.padding(6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${TokenDisplay.k(m.totalTokens)} tokens",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        m.chargedPoints?.let {Text("${it} 积分",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
        s.busySince?.let {Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text(TokenDisplay.elapsed(elapsed),Modifier.padding(start=8.dp),style=MaterialTheme.typography.labelMedium)
        }}
        if(s.notice.isNotEmpty())Text(s.notice,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(vertical=5.dp))
        if(s.messages.any{it.role=="assistant"})Text("本会话 ${s.messages.count{it.role=="assistant"}} 次 · ${TokenDisplay.k(s.messages.sumOf{it.totalTokens})} tokens",
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(s.advice!=null&&s.mode==AgentMode.PLAN)TextButton(onClick=controller::previewAdvice,enabled=s.busySince==null&&s.pendingId==null){Text("优化并预览安排")}
        Row(verticalAlignment=Alignment.CenterVertically){
            Box {AssistChip(onClick={modeMenu=true},label={Text("${s.mode.label} ▾")},enabled=s.busySince==null)
                DropdownMenu(modeMenu,{modeMenu=false}){AgentMode.entries.forEach {m->DropdownMenuItem(text={Text(m.label)},onClick={controller.mode(m);modeMenu=false})}}}
            Spacer(Modifier.width(8.dp))
            TextButton(onClick=controller::refreshCapabilities,enabled=s.busySince==null){Text("刷新权限")}
        }
        if(s.mode.edits)Text(if(s.models.any{it.canEdit}&&s.access.vip)"日程编辑可用 · 采纳前会预览" else "日程编辑暂不可用，可刷新权限",style=MaterialTheme.typography.labelSmall)
        if(s.mode==AgentMode.DIARY)Box {
            TextButton(onClick={diaryStyleMenu=true},enabled=s.busySince==null,modifier=Modifier.semantics{contentDescription="日记风格"}){Text("风格 · ${DiaryStyle.entries.find{it.code==s.diaryStyle}?.label?:"精简"} ▾")}
            DropdownMenu(diaryStyleMenu,{diaryStyleMenu=false}){DiaryStyle.entries.forEach{style->DropdownMenuItem(text={Text(style.label)},onClick={controller.diaryStyle(style.code);diaryStyleMenu=false})}}
        }
        if(s.mode==AgentMode.SHARE_COPY)Box {
            TextButton(onClick={styleMenu=true}){Text("风格 · ${ShareStyle.entries.find{it.code==s.shareStyle}?.label?:"极简"} ▾")}
            DropdownMenu(styleMenu,{styleMenu=false}) {ShareStyle.entries.forEach{style->DropdownMenuItem(text={Text(style.label)},onClick={controller.style(style.code);styleMenu=false})}}
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Text("日程读取",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
            Switch(s.includeAgenda,controller::agenda,enabled=s.busySince==null,modifier=Modifier.semantics{contentDescription="附带日程开关"})
            if(s.mode.edits){Text("日常编辑",style=MaterialTheme.typography.labelMedium);Switch(s.editConsent,controller::consent,enabled=s.busySince==null,
                modifier=Modifier.semantics{contentDescription="日常编辑开关"})}
        }
        OutlinedTextField(draft,{if(it.length<=16000)draft=it},placeholder={Text(s.mode.hint)},maxLines=4,
            modifier=Modifier.fillMaxWidth().semantics{contentDescription="助手输入"},shape=RoundedCornerShape(20.dp),enabled=s.busySince==null)
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(s.access.userId==null)"未登录" else "≈ ${TokenDisplay.k(TokenDisplay.estimate(draft))} · ${s.access.availablePoints} 积分",
                Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.pendingId!=null)TextButton(onClick=controller::queryPending,enabled=s.busySince==null){Text("查询结果")}
            FilledTonalButton(onClick={controller.send(draft)},enabled=draft.isNotBlank()&&s.busySince==null&&s.pendingId==null){Text("发送")}
        }
    }
    s.gate?.let {AccessDialog(it,controller::dismissGate,onAccount,onVip,onPoints,onConsent={controller.consent(true)})}

    if (history) {
        AlertDialog(
            onDismissRequest = { history = false }, title = { Text("会话历史") },
            text = {
                Column {
                    OutlinedTextField(historySearch, { historySearch = it }, placeholder = { Text("搜索会话") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(s.sessions.filter { it.title.contains(historySearch, true) }, key = { it.id }) { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { controller.openSession(row); history = false }, modifier = Modifier.weight(1f)) { Text(row.title, maxLines = 2) }
                                TextButton(onClick = { controller.archive(row) }) { Text("归档") }
                            }
                        }
                    }
                    if (s.sessions.isEmpty()) Text("还没有历史会话", Modifier.padding(16.dp))
                }
            },
            confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } }
        )
    }
    s.preview?.let { proposal ->
        AlertDialog(
            onDismissRequest = controller::dismissPreview, title = { Text("采纳日程调整？") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${proposal.changes.size} 项变更", fontWeight = FontWeight.Bold)
                    proposal.lines().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = controller::applyPreview, enabled=s.busySince==null) { Text("Accept") } },
            dismissButton = { Row {
                TextButton(onClick = controller::requestRevision, enabled=s.busySince==null) { Text("意见") }
                TextButton(onClick = controller::dismissPreview, enabled=s.busySince==null) { Text("Refuse") }
            } }
        )
    }
    s.arrangement?.let { proposal ->
        val plan=proposal.arrangement
        val zone=java.time.ZoneId.of(plan.preview.source.zoneId)
        val format=java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone)
        AlertDialog(onDismissRequest=controller::dismissArrangement,title={Text("采纳安排？")},text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text("${plan.taskIds.size}件事项 · ${zone.id}",fontWeight=FontWeight.Bold)
                if(plan.rest>0)Text("连续工作最多${plan.maxWork}分钟 · 休息${plan.rest}分钟")
                plan.preview.result.blocks.filter{it.taskId in plan.taskIds}.forEach{b->
                    val title=plan.preview.source.tasks.find{it.id==b.taskId}?.title.orEmpty()
                    Text("${format.format(b.window.start)}–${format.format(b.window.end)}  $title${if(b.locked)" · 固定" else ""}",style=MaterialTheme.typography.bodyMedium)
                }
            }
        },confirmButton={TextButton(onClick=controller::applyArrangement,enabled=s.busySince==null){Text("Accept")}},
            dismissButton={TextButton(onClick=controller::dismissArrangement,enabled=s.busySince==null){Text("Refuse")}})
    }
    s.revision?.let { proposal ->
        var opinion by remember(proposal.id) {mutableStateOf("")}
        AlertDialog(
            onDismissRequest=controller::dismissRevision, title={Text("告诉小鲸鱼你的意见")},
            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(opinion,{if(it.length<=2000)opinion=it},placeholder={Text("希望怎样调整？")},maxLines=5,
                    modifier=Modifier.semantics{contentDescription="提案意见"})
                Text("新提案仍需你确认",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("日常编辑",Modifier.weight(1f))
                    Switch(s.editConsent,controller::consent,modifier=Modifier.semantics{contentDescription="意见编辑开关"})
                }
            }},
            confirmButton={TextButton(onClick={controller.revisePreview(opinion)},enabled=opinion.isNotBlank()&&s.editConsent){Text("发送意见")}},
            dismissButton={TextButton(onClick=controller::dismissRevision){Text("取消")}}
        )
    }
}
@Composable private fun SelectionContainerCompat(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
