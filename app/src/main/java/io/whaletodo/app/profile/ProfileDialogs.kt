package io.whaletodo.app.profile

import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.*
import io.whaletodo.app.DeskState
import io.whaletodo.app.data.DeskOptions
import io.whaletodo.app.ui.WorkTimeDialog
import io.whaletodo.core.*
import java.time.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProfileDialogHost(controller:ProfileController,desk:DeskState,blocked:Boolean=false){
    val s by controller.state.collectAsState()
    LaunchedEffect(s.pendingDiary,s.dialog,s.busy,blocked){if(!blocked)controller.showPendingDiary()}
    if(s.dialog==MyDialog.NONE)return
    key(s.owner,s.dialog){when(s.dialog){
        MyDialog.WORKING_CLOCK->{val range=s.workingDay?.let{DeskOptions.weekdays(s.deviceOptions)[DayOfWeek.of(it)]}?:DeskOptions.defaults(s.deviceOptions,desk.settings)
            WorkTimeDialog(range.start,range.end.coerceAtMost(1439),controller::dismiss,{a,b->controller.saveWorking(a,b,s.workingDay)},
                label=if(s.workingDay==null)"默认 Working" else "星期${listOf("一","二","三","四","五","六","日")[s.workingDay!!-1]} Working")}
        MyDialog.CHECKIN->SignInDialog(s,controller)
        MyDialog.POINTS->PointsDialog(controller)
        MyDialog.RECHARGE_POINTS,MyDialog.RECHARGE_VIP->RechargeDialog(s,controller)
        MyDialog.LOGIN,MyDialog.REGISTER,MyDialog.RESET_PASSWORD->AuthDialog(s,controller)
        MyDialog.AVATAR->AvatarDialog(s,controller)
        MyDialog.UTC_ZONES->UtcZoneDialog(s,controller)
        MyDialog.ZONE_CONFIRM->ZoneConfirmation(s,controller)
        MyDialog.REMINDERS->ReminderDefaultsDialog(s,controller)
        MyDialog.DIARY->DiaryDialog(s,controller)
        MyDialog.DIARY_DATE->{val date=LocalDate.parse(s.diary?.date?:desk.today.toString());val picker=rememberDatePickerState(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={controller.open(MyDialog.DIARY)},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{controller.openDiary(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())}},enabled=picker.selectedDateMillis!=null&&!s.busy){Text("查看日记")}},dismissButton={TextButton(onClick={controller.open(MyDialog.DIARY)}){Text("返回")}}){DatePicker(picker,showModeToggle=false)}}
        MyDialog.DELETE_DIARY->AlertDialog(onDismissRequest={controller.open(MyDialog.DIARY)},title={Text("删除这篇日记？")},text={Column{Text("${s.diary?.date} 的日记将被删除。");DialogError(s)}},
            confirmButton={TextButton(onClick=controller::confirmDeleteDiary,enabled=!s.busy){Text("确认删除")}},dismissButton={TextButton(onClick={controller.open(MyDialog.DIARY)}){Text("保留日记")}})
        MyDialog.OVERWRITE_DIARY->AlertDialog(onDismissRequest={controller.open(MyDialog.DIARY)},title={Text("替换当日日记？")},text={Column{Text("${s.diary?.date} 已有日记，确认后将保存当前预览。");DialogError(s)}},
            confirmButton={TextButton(onClick={controller.saveDiary(true)},enabled=!s.busy){Text("替换并保存")}},dismissButton={TextButton(onClick={controller.open(MyDialog.DIARY)}){Text("继续编辑")}})
        else->Unit
    }}
}
@Composable private fun DialogError(s:ProfileState){if(s.error.isNotBlank())Text(s.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
@Composable private fun SignInDialog(s:ProfileState,c:ProfileController){
    val progress=SignInProgress.from(s.details?.signInDates.orEmpty(),s.details?.businessDate?:LocalDate.now())
    AlertDialog(onDismissRequest=c::dismiss,title={Text("每日签到")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("连续打卡 ${if(s.access.userId==null)0 else progress.streak} 天",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){(1..7).forEach{day->
            val done=s.access.userId!=null&&progress.completed(day)
            Column(Modifier.weight(1f).background(if(done)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,RoundedCornerShape(9.dp)).padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("${day}天",fontSize=10.sp);Text(if(done)"✓" else "+${SignInProgress.reward(day)}",fontWeight=FontWeight.Bold,color=if(day==7)Color(0xFFB57B12)else MaterialTheme.colorScheme.primary)
            }
        }}
        Text("第1–6天 +1积分 · 第7天 +5积分",style=MaterialTheme.typography.bodySmall)
        if(s.access.userId==null)Text("登录后领取每日积分",color=MaterialTheme.colorScheme.onSurfaceVariant)
        else if(progress.celebrating)Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(12.dp)){
            Text("🎉 坚持一周，做得真棒！",fontWeight=FontWeight.Bold);Text("继续坚持，下周也一起前进。",style=MaterialTheme.typography.bodySmall)}}
        DialogError(s)
    }},confirmButton={TextButton(onClick=c::signIn,enabled=!s.busy&&(s.access.userId==null||!progress.signedToday)){
        Text(if(s.access.userId==null)"登录后签到" else if(progress.signedToday)"今日已签到" else "签到领取")}},dismissButton={TextButton(onClick=c::dismiss,enabled=!s.busy){Text("关闭")}})
}
@Composable private fun PointsDialog(c:ProfileController){val context=LocalContext.current
    AlertDialog(onDismissRequest=c::dismiss,title={Text("积分获取途径")},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        ProfileRow("✓","去签到","每日打卡领取积分"){c.open(MyDialog.CHECKIN)}
        ProfileRow("＋","去充值","购买积分"){c.open(MyDialog.RECHARGE_POINTS)}
        ProfileRow("♛","开通VIP","查看会员方案"){c.open(MyDialog.RECHARGE_VIP)}
        ProfileRow("✎","提出建议"){c.dismiss();openProjectLink(context,true)}
    }},confirmButton={TextButton(onClick=c::dismiss){Text("关闭")}})
}
@Composable private fun RechargeDialog(s:ProfileState,c:ProfileController){
    val vip=s.dialog==MyDialog.RECHARGE_VIP;var selected by remember{mutableIntStateOf(0)}
    AlertDialog(onDismissRequest=c::dismiss,title={Text("充值")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!vip,{c.open(MyDialog.RECHARGE_POINTS)},{Text("积分购买")});FilterChip(vip,{c.open(MyDialog.RECHARGE_VIP)},{Text("会员充值")})}
        (if(vip)listOf("月度会员","季度会员","年度会员")else listOf("100积分","500积分","1000积分")).forEachIndexed{i,label->
            OutlinedCard(onClick={selected=i},border=BorderStroke(if(selected==i)2.dp else 1.dp,if(selected==i)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Text(if(selected==i)"✓" else "○")}}
        }
        Text(if(vip)"会员权益与价格待公布" else "价格待公布",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        DialogError(s)
    }},confirmButton={TextButton(onClick={if(s.access.userId==null)c.open(MyDialog.LOGIN)else c.rechargeUnavailable()}){Text(if(s.access.userId==null)"登录后继续" else "查看方案")}},dismissButton={TextButton(onClick=c::dismiss){Text("关闭")}})
}
@Composable private fun AuthDialog(s:ProfileState,c:ProfileController){
    val register=s.dialog==MyDialog.REGISTER;val reset=s.dialog==MyDialog.RESET_PASSWORD
    var email by remember{mutableStateOf(if(reset)s.details?.email.orEmpty() else "")}
    var nickname by remember{mutableStateOf(ProfileIdentity.defaultNickname(java.util.UUID.randomUUID().toString()))}
    var customNickname by remember{mutableStateOf(false)}
    var password by remember{mutableStateOf("")};var confirmation by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
    var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())}
    LaunchedEffect(s.codeUntil){while(s.codeUntil>SystemClock.elapsedRealtime()){now=SystemClock.elapsedRealtime();delay(1000)};now=SystemClock.elapsedRealtime()}
    val remaining=if(s.codeEmail==email.trim()&&s.codePurpose==(if(reset)CodePurpose.RESET_PASSWORD else CodePurpose.REGISTER))((s.codeUntil-now+999)/1000).coerceAtLeast(0)else 0
    AlertDialog(onDismissRequest=c::dismiss,title={Text(if(reset)"重置密码" else if(register)"注册" else "登录")},text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(email,{if(it.length<=254)email=it},label={Text("邮箱")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.semantics{contentDescription="账户邮箱"})
        if(register)OutlinedTextField(nickname,{nickname=ProfileIdentity.clipNickname(it);customNickname=true},label={Text("用户昵称")},singleLine=true,modifier=Modifier.fillMaxWidth().semantics{contentDescription="注册昵称"})
        if(register||reset){Row(verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(code,{if(it.length<=8)code=it.filter(Char::isDigit)},label={Text("邮箱验证码")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).semantics{contentDescription="邮箱验证码"})
            TextButton(onClick={c.requestCode(email,if(reset)CodePurpose.RESET_PASSWORD else CodePurpose.REGISTER)},enabled=!s.busy&&remaining==0L){Text(if(remaining>0)"${remaining}s" else "发送验证码")}}
        }
        OutlinedTextField(password,{if(it.length<=200)password=it},label={Text(if(reset)"新密码" else "密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.semantics{contentDescription="账户密码"})
        if(register||reset)OutlinedTextField(confirmation,{if(it.length<=200)confirmation=it},label={Text("确认密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.semantics{contentDescription="确认密码"})
        if(!register&&!reset)Row{TextButton(onClick={c.open(MyDialog.REGISTER)}){Text("注册账户")};TextButton(onClick={c.open(MyDialog.RESET_PASSWORD)}){Text("忘记密码")}}
        if(register||reset)TextButton(onClick={c.open(MyDialog.LOGIN)}){Text("返回登录")}
        DialogError(s)
    }},confirmButton={TextButton(onClick={c.authenticate(email,password,code,register,reset,if(customNickname)nickname else null)},enabled=!s.busy&&email.isNotBlank()&&password.isNotBlank()&&(!register||runCatching{ProfileIdentity.nickname(nickname)}.isSuccess)&&(!(register||reset)||code.isNotBlank()&&confirmation==password)){
        Text(if(reset)"重置密码" else if(register)"创建账户" else "登录")}},dismissButton={TextButton(onClick=c::dismiss,enabled=!s.busy){Text("取消")}})
}
@Composable private fun AvatarDialog(s:ProfileState,c:ProfileController){
    AlertDialog(onDismissRequest=c::dismiss,title={Text("编辑头像")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        ProfileAvatar(s,Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){listOf("ocean" to "浅海","mint" to "薄荷","dusk" to "落日").forEach{(code,title)->OutlinedButton(onClick={c.saveUserOption("avatar.style",code)},contentPadding=PaddingValues(10.dp)){Text(title)}}}
        ProfileRow("▧","从相册选择"){c.avatar(AvatarSource.GALLERY)}
        ProfileRow("◎","拍摄照片"){c.avatar(AvatarSource.CAMERA)}
        DialogError(s)
    }},confirmButton={TextButton(onClick=c::dismiss,enabled=!s.busy){Text("关闭")}})
}
@Composable internal fun ReminderChoices(method:String,onMethod:(String)->Unit,timing:String?=null,onTiming:(String)->Unit={}){
    Text("提醒方式",style=MaterialTheme.typography.labelLarge)
    ReminderMethod.entries.forEach{m->Row(Modifier.fillMaxWidth().clickable{onMethod(m.code)},verticalAlignment=Alignment.CenterVertically){RadioButton(method==m.code,{onMethod(m.code)});Text(m.label)}}
    if(timing!=null){Text("提醒时间",style=MaterialTheme.typography.labelLarge);ReminderTiming.entries.forEach{t->Row(Modifier.fillMaxWidth().clickable{onTiming(t.code)},verticalAlignment=Alignment.CenterVertically){RadioButton(timing==t.code,{onTiming(t.code)});Text(t.label)}}}
}
@Composable private fun ReminderDefaultsDialog(s:ProfileState,c:ProfileController){
    fun option(key:String,default:String)=s.options.find{it.key==key}?.value?:default
    var enabled by remember{mutableStateOf(option("reminder.enabled","false")=="true")}
    var method by remember{mutableStateOf(option("reminder.method","message"))};var stars by remember{mutableIntStateOf(option("reminder.stars","0").toIntOrNull()?:0)}
    AlertDialog(onDismissRequest=c::dismiss,title={Text("自动提醒")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("开启提醒",Modifier.weight(1f));Switch(enabled,{enabled=it},Modifier.semantics{contentDescription="自动提醒开关"})}
        if(enabled){ReminderChoices(method,{method=it});Text("覆盖范围",style=MaterialTheme.typography.labelLarge)
            FilterChip(stars==0,{stars=0},{Text("全部事务")})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){(1..5).forEach{n->FilterChip(stars==n,{stars=n},{Text("≥$n★",fontSize=11.sp)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(10.dp))}}
        };DialogError(s)
    }},confirmButton={TextButton(onClick={c.saveReminderDefaults(enabled,method,stars)},enabled=!s.busy){Text("保存偏好")}},dismissButton={TextButton(onClick=c::dismiss,enabled=!s.busy){Text("取消")}})
}
@Composable private fun DiaryDialog(s:ProfileState,c:ProfileController){val d=s.diary?:return
    val count=DiaryContent.count(d.text.trim())
    AlertDialog(onDismissRequest=c::dismiss,title={Text(if(d.generated)"保存这篇日记？" else "日记")},text={Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(d.editing)Text(d.date,style=MaterialTheme.typography.labelLarge)
        else Row(verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={c.openDiary(LocalDate.parse(d.date).minusDays(1))},enabled=!s.busy,modifier=Modifier.semantics{contentDescription="前一天日记"}){Text("‹")}
            TextButton(onClick={c.open(MyDialog.DIARY_DATE)},enabled=!s.busy,modifier=Modifier.weight(1f)){Text(d.date)}
            IconButton(onClick={c.openDiary(LocalDate.parse(d.date).plusDays(1))},enabled=!s.busy,modifier=Modifier.semantics{contentDescription="后一天日记"}){Text("›")}
        }
        if(d.editing){OutlinedTextField(d.text,c::diaryText,label={Text("日记内容")},minLines=5,maxLines=12,modifier=Modifier.fillMaxWidth().semantics{contentDescription="日记内容"})
            Text("$count / 800字",color=if(count>800)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
        else Text(d.text.ifBlank{"这一天还没有日记"},style=MaterialTheme.typography.bodyLarge)
        DialogError(s)
    }},confirmButton={TextButton(onClick={if(d.editing)c.saveDiary()else c.editDiary()},enabled=!s.busy&&(!d.editing||count in 1..800)){
        Text(if(d.editing)"保存日记" else if(d.original==null)"写日记" else "编辑日记")}},
        dismissButton={Row{if(!d.editing&&d.original!=null)TextButton(onClick={c.open(MyDialog.DELETE_DIARY)},enabled=!s.busy){Text("删除日记")};TextButton(onClick=c::dismiss,enabled=!s.busy){Text(if(d.editing)"取消" else "关闭")}}})
}
