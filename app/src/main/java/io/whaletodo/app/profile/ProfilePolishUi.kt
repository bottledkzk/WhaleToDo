package io.whaletodo.app.profile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.whaletodo.app.DeskState
import io.whaletodo.app.data.DeskOptions
import io.whaletodo.core.*
import java.time.*

@Composable internal fun ProfileHero(s:ProfileState,desk:DeskState,c:ProfileController) {
    val logged=s.access.userId!=null
    val days=ProfileIdentity.vipDays(s.details?.vipExpiresAt,Instant.ofEpochMilli(desk.now))
    val vip=logged&&s.access.vip&&(days==null||days>0)
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.clickable{c.open(MyDialog.AVATAR)}.semantics{contentDescription="编辑头像"}) {
                        ProfileAvatar(s)
                        Text("✎",Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(20.dp)).padding(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if(logged)s.nickname else "本机访客",style=MaterialTheme.typography.titleMedium,fontSize=if(logged)16.sp else 20.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(!logged)TextButton(onClick={c.open(MyDialog.LOGIN)},contentPadding=PaddingValues(0.dp)){Text("登录 / 注册")}
                    }
                }
                Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("${if(logged)s.access.availablePoints else 0} 积分",style=MaterialTheme.typography.titleMedium,
                            color=if(logged)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick={c.open(MyDialog.POINTS)},Modifier.size(32.dp).semantics{contentDescription="积分获取途径"}){Text("？")}
                    }
                    Surface(onClick={c.open(MyDialog.RECHARGE_VIP)},shape=RoundedCornerShape(16.dp),
                        color=if(vip)Color(0xFFF2D890)else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier=Modifier.semantics{contentDescription=if(vip)"VIP会员" else "开通VIP会员"}) {
                        Text(if(vip&&days!=null)"♛ VIP · ${days}天" else "♛ VIP",Modifier.padding(horizontal=10.dp,vertical=6.dp),
                            color=if(vip)Color(0xFF6D4B05)else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if(logged&&s.motto.isNotBlank())Text(s.motto,Modifier.fillMaxWidth().semantics{contentDescription="个人Motto"},style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${desk.tasks.count{it.done}} 件已完成",Modifier.align(Alignment.End),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun EditableIdentity(title:String,value:String,owner:String,limit:(String)->Int,maximum:Int,enabled:Boolean,onSave:(String)->Boolean) {
    var editing by remember(owner,title){mutableStateOf(false)}
    var draft by remember(owner,title,value){mutableStateOf(value)}
    if(editing)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(draft,{draft=if(title=="Motto")ProfileIdentity.clipMotto(it)else ProfileIdentity.clipNickname(it)},singleLine=true,enabled=enabled,label={Text(title)},modifier=Modifier.weight(1f).semantics{contentDescription="编辑$title"})
        IconButton(onClick={if(onSave(draft))editing=false},Modifier.semantics{contentDescription="保存$title"},enabled=enabled&&(title=="Motto"||draft.isNotBlank())&&limit(draft)<=maximum){Text("√",fontSize=24.sp,color=MaterialTheme.colorScheme.primary)}
        IconButton(onClick={draft=value;editing=false},Modifier.semantics{contentDescription="取消编辑$title"}){Text("✕",fontSize=22.sp)}
    } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank{"—"},style=MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick={draft=value;editing=true},enabled=enabled,modifier=Modifier.semantics{contentDescription="编辑$title"}){Text("✎",fontSize=24.sp)}
    }
}

@Composable internal fun AccountPanel(s:ProfileState,c:ProfileController) {
    var expanded by remember(s.owner){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        ProfileAvatar(s)
        if(s.access.userId==null) {
            Button(onClick={c.open(MyDialog.LOGIN)}){Text("登录 / 注册")}
        } else {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.semantics{contentDescription=if(expanded)"收起用户ID" else "展开用户ID"},verticalAlignment=Alignment.CenterVertically) {
                        Text("用户ID",Modifier.weight(1f),fontWeight=FontWeight.Medium);Text(if(expanded)"⌃" else "⌄",fontSize=24.sp)
                    }
                    if(expanded) {
                        EditableIdentity("用户昵称",s.nickname,s.owner,ProfileIdentity::nicknameLength,16,!s.busy){c.saveIdentity(true,it)}
                        HorizontalDivider()
                        Text(s.details?.publicId?.takeIf{it.isNotBlank()}?:s.owner,Modifier.semantics{contentDescription="用户ID值"},style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {Box(Modifier.padding(14.dp)){EditableIdentity("Motto",s.motto,s.owner,ProfileIdentity::mottoLength,30,!s.busy){c.saveIdentity(false,it)}}}
            Text("邮箱 · ${s.details?.email?:"—"}",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={c.open(MyDialog.RESET_PASSWORD)}){Text("重置密码")}
            TextButton(onClick=c::signOut,enabled=!s.busy){Text("退出登录")}
        }
    }
}

@Composable internal fun RegionPanel(s:ProfileState,desk:DeskState,c:ProfileController) {
    val choice=DeskOptions.zoneChoice(s.deviceOptions);val system=choice.id=="system"
    var query by remember{mutableStateOf("")}
    val now=Instant.ofEpochMilli(desk.now)
    val zones=remember(query) {
        if(query.isBlank())listOf("Asia/Shanghai","Asia/Tokyo","America/Los_Angeles","Europe/London","UTC")
        else if(query.equals("PDT",true)||query.equals("PST",true))listOf("America/Los_Angeles")
        else ZoneId.getAvailableZoneIds().filter{it.contains(query,true)}.sorted().take(40)
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("跟随系统",Modifier.weight(1f));Switch(system,{c.proposeZone(if(it)"system" else desk.zoneId)},enabled=!s.busy,modifier=Modifier.semantics{contentDescription="跟随系统时区"})
        }
        Text("${DeskZones.utcLabel(ZoneId.of(desk.zoneId),now)} · ${if(system)ZoneId.systemDefault().id else choice.id}",style=MaterialTheme.typography.bodyMedium)
        if(!system) {
            ProfileRow("◷","UTC时区",DeskZones.utcLabel(choice.effective(now),now)){c.open(MyDialog.UTC_ZONES)}
            if(DeskZones.hasDaylight(ZoneId.of(choice.id),now))Row(verticalAlignment=Alignment.CenterVertically) {
                Text("夏令时",Modifier.weight(1f));Switch(choice.daylight,{c.proposeZone(choice.id,it)},enabled=!s.busy,modifier=Modifier.semantics{contentDescription="夏令时开关"})
            }
            OutlinedTextField(query,{query=it},label={Text("搜索或自定义时区")},placeholder={Text("Asia/Shanghai 或 +08:00")},singleLine=true,modifier=Modifier.fillMaxWidth().semantics{contentDescription="时区搜索"})
            TextButton(onClick={c.proposeZone(query.trim(),if(query.trim().equals("PST",true))false else null)},enabled=query.isNotBlank()&&!s.busy){Text("应用自定义时区")}
            LazyColumn(Modifier.weight(1f)) {items(zones) {id->
                val zone=ZoneId.of(id)
                ListItem(headlineContent={Text(id)},supportingContent={Text(DeskZones.utcLabel(DeskZoneChoice(id,if(id==choice.id)choice.daylight else true).effective(now),now))},trailingContent={if(choice.id==id)Text("✓")},
                    modifier=Modifier.clickable(enabled=!s.busy){c.proposeZone(id)})
            }}
        }
    }
}

@Composable internal fun UtcZoneDialog(s:ProfileState,c:ProfileController) {
    AlertDialog(onDismissRequest=c::dismiss,title={Text("选择UTC时区")},text={
        LazyColumn(Modifier.heightIn(max=380.dp)) {items(DeskZones.offsets) {zone->
            ListItem(headlineContent={Text(DeskZones.utcLabel(zone))},modifier=Modifier.clickable(enabled=!s.busy){c.proposeZone(zone.id)})
        }}
    },confirmButton={TextButton(onClick=c::dismiss){Text("取消")}})
}
@Composable internal fun ZoneConfirmation(s:ProfileState,c:ProfileController) {
    val draft=s.zoneDraft?:return
    var adjust by remember(draft){mutableStateOf(false)}
    AlertDialog(onDismissRequest=c::dismiss,title={Text("更改时区")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("${draft.from} → ${draft.to}",style=MaterialTheme.typography.bodyMedium)
        Text("是否同步调整日程时间？")
        listOf(false to "不调整日程",true to "同步日程时间").forEach{(value,label)->
            Row(Modifier.fillMaxWidth().clickable(enabled=!s.busy){adjust=value},verticalAlignment=Alignment.CenterVertically) {RadioButton(adjust==value,{adjust=value},enabled=!s.busy);Text(label)}
        }
        if(s.error.isNotBlank())Text(s.error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={c.confirmZone(adjust)},enabled=!s.busy){Text("确认更改")}},dismissButton={TextButton(onClick=c::dismiss,enabled=!s.busy){Text("取消")}})
}