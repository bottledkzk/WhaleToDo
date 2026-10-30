package io.whaletodo.app.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun AccountCloudPanel(controller:AccountCloudController){
 val s by controller.state.collectAsState()
 Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("日程云端备份与同步",style=MaterialTheme.typography.titleMedium)
  Text("登录时不会上传、覆盖或合并日程")
  Text(if(s.owner=="guest")"请先登录账户" else if(s.vip)"VIP 权益已开启" else "日程云端备份与同步仅限 VIP")
  Button(onClick=controller::prepareBackup,enabled=!s.busy&&s.vip&&s.owner!="guest",modifier=Modifier.fillMaxWidth()){Text("云端备份")}
  Text("将当前账号的本地事项、安排、打卡和提醒设置上传，替换上一次云端日程备份",style=MaterialTheme.typography.bodySmall)
  Text("同步云端",style=MaterialTheme.typography.titleMedium)
  OutlinedButton(onClick={controller.prepareDownload("replace")},enabled=!s.busy&&s.vip&&s.owner!="guest",modifier=Modifier.fillMaxWidth()){Text("覆盖同步")}
  Text("用云端备份替换当前账号的本地日程，确认前可查看数量",style=MaterialTheme.typography.bodySmall)
  OutlinedButton(onClick={controller.prepareDownload("compatible")},enabled=!s.busy&&s.vip&&s.owner!="guest",modifier=Modifier.fillMaxWidth()){Text("兼容同步")}
  Text("保留本地记录并加入云端独有事项，同编号以本地为准；冲突的云端安排保留为待安排事项",style=MaterialTheme.typography.bodySmall)
  HorizontalDivider()
  Text("随账号自动同步",style=MaterialTheme.typography.titleMedium)
  Text("日记、昵称和 motto 自动跟随账号，无需 VIP")
  Text(s.profileStatus,style=MaterialTheme.typography.bodySmall)
  TextButton(onClick={controller.refreshProfile()}){Text("重试个人资料同步")}
  if(s.profileConflict){
   Text("当前账号的同一条日记或资料在两端有不同修改，请选择本次冲突的保留方式")
   TextButton(onClick={controller.refreshProfile("local")}){Text("保留本地修改并同步")}
   TextButton(onClick={controller.refreshProfile("cloud")}){Text("使用云端资料")}
  }
  Text("Agent 会话仅保存在本机，不随账号上传或恢复",style=MaterialTheme.typography.bodySmall)
  if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(s.error.isNotBlank())Text(s.error,color=MaterialTheme.colorScheme.error)
  if(s.notice.isNotBlank())Text(s.notice,color=MaterialTheme.colorScheme.primary)
 }
 s.preview?.let{p->AlertDialog(
  onDismissRequest={if(!s.busy)controller.cancelPreview()},
  title={Text(if(p.upload)"确认云端备份" else if(p.method=="replace")"确认覆盖本地日程" else "确认兼容同步")},
  text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("确认后：${p.result.tasks.size} 个事项 · ${p.result.blocks.size} 个安排 · ${p.result.checks.size} 条打卡")
   if(p.upload)Text("将替换上一次云端日程备份")
   else if(p.method=="replace")Text("当前账号的本地日程将被替换")
   if(p.skipped>0)Text("${p.skipped} 个冲突时段不导入，其事项仍保留，可重新安排")
   Text("日记、motto 和 Agent 会话不受此次操作影响")
  }},
  confirmButton={TextButton(onClick=controller::confirm,enabled=!s.busy){Text("确认")}},
  dismissButton={TextButton(onClick=controller::cancelPreview,enabled=!s.busy){Text("取消")}},
 )}
}
