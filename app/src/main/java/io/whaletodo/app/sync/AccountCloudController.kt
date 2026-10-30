package io.whaletodo.app.sync

import io.whaletodo.app.account.*
import io.whaletodo.app.agent.AgentBridge
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.data.*
import io.whaletodo.core.ProfileIdentity
import io.whaletodo.core.DiaryContent
import io.whaletodo.network.ApiException
import io.whaletodo.network.CloudDocument
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class CloudPreview(val owner:String,val upload:Boolean,val method:String,val revision:Int,val localJson:String,val result:ScheduleBackup,val skipped:Int=0)
internal data class AccountCloudState(val owner:String="guest",val vip:Boolean=false,val busy:Boolean=false,val notice:String="",val error:String="",val profileStatus:String="登录后日记和 motto 自动随账号",val profileConflict:Boolean=false,val preview:CloudPreview?=null)

/** No schedule request is made by init/login/timer. Only profile data is automatic. */
internal class AccountCloudController(
 private val repo:DeskRepository,private val scope:CoroutineScope,
 private val bridge:AccountCloudBridge=AccountCloudBridgeHub.bridge,
 private val agent:AgentBridge=AgentBridgeHub.bridge,
 private val onScheduleChanged:suspend ()->Unit={},
) {
 private val mutable=MutableStateFlow(AccountCloudState());val state=mutable.asStateFlow()
 private val profileLock=Mutex()
 init {
  scope.launch{agent.access.collect{a->val owner=a.userId?:"guest";mutable.update{if(it.owner!=owner)AccountCloudState(owner=owner,vip=a.vip) else it.copy(vip=a.vip)}}}
  scope.launch{agent.access.map{it.userId}.distinctUntilChanged().collectLatest{owner->
   if(owner!=null)while(current(owner)){syncProfile(owner);delay(15000)}
  }}
 }
 private fun current(owner:String)=agent.access.value.userId==owner
 private fun allowed(owner:String)=current(owner)&&agent.access.value.vip
 fun cancelPreview(){mutable.update{it.copy(preview=null)}}
 private fun action(block:suspend (String)->Unit){
  val owner=agent.access.value.userId
  if(owner==null){mutable.update{it.copy(error="请先登录")};return}
  if(!allowed(owner)){mutable.update{it.copy(error="日程云端备份与同步需要 VIP")};return}
  if(mutable.value.busy)return
  mutable.update{it.copy(busy=true,error="",notice="",preview=null)}
  scope.launch{try{block(owner)}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{if(current(owner))it.copy(error=message(e))else it}}finally{mutable.update{if(current(owner))it.copy(busy=false)else it}}}
 }
 fun prepareBackup()=action{owner->
  val local=ScheduleBackup.capture(repo,owner);local.validate()
  val remote=bridge.read(owner,"schedule")
  check(allowed(owner));mutable.update{it.copy(preview=CloudPreview(owner,true,"backup",remote.revision,local.json(),local))}
 }
 fun prepareDownload(method:String){require(method in setOf("replace","compatible"));action{owner->
  val local=ScheduleBackup.capture(repo,owner);val remote=bridge.read(owner,"schedule")
  check(remote.revision>0){"云端还没有日程备份"};val cloud=ScheduleBackup.parse(remote.payloadJson,owner)
  val (result,skipped)=if(method=="replace")cloud to 0 else local.compatible(cloud)
  result.validate();check(allowed(owner));mutable.update{it.copy(preview=CloudPreview(owner,false,method,remote.revision,local.json(),result,skipped))}
 }}
 fun confirm(){val preview=mutable.value.preview?:return
  if(mutable.value.busy)return
  mutable.update{it.copy(busy=true,error="")}
  scope.launch{try{
   var remindersPending=false
   check(allowed(preview.owner)){"账户或 VIP 已变化"}
   if(preview.upload){
    check(ScheduleBackup.capture(repo,preview.owner).json()==preview.localJson){"本地日程已变化，请重新预览"}
    bridge.write(preview.owner,"schedule",preview.revision,preview.result.json())
   }else{
    // Re-read before application: an old preview may not restore a backup that has since changed.
    check(bridge.read(preview.owner,"schedule").revision==preview.revision){"云端备份已变化，请重新预览"}
    ScheduleBackup.apply(repo,preview.owner,preview.localJson,preview.result){allowed(preview.owner)}
    remindersPending=runCatching{onScheduleChanged()}.isFailure
   }
   mutable.update{if(current(preview.owner))it.copy(preview=null,notice=if(preview.upload)"云端备份已完成" else if(remindersPending)"云端日程已同步，提醒重建待重试" else "云端日程已同步")else it}
  }catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{if(current(preview.owner))it.copy(error=message(e),preview=null)else it}}
  finally{mutable.update{if(current(preview.owner))it.copy(busy=false)else it}}}
 }
 fun refreshProfile(prefer:String?=null){val owner=agent.access.value.userId?:return;scope.launch{syncProfile(owner,prefer)}}
 private fun message(e:Exception)=when(e){
  is ApiException->when(e.status){401->"登录已过期，请重新登录";403->"日程云端备份与同步需要 VIP";409->"云端内容已变化，请重新读取后确认";else->"云端暂不可用，本地内容已保留"}
  is IllegalStateException,is IllegalArgumentException->e.message?:"数据校验未通过，本地内容已保留"
  else->"网络暂不可用，本地内容已保留，可稍后重试"
 }
 private suspend fun localProfile(owner:String):Map<String,String> = buildMap {
  repo.dao.options(owner).filter{it.key in setOf("profile.motto","profile.nickname")}.forEach{put(it.key,it.value)}
  repo.dao.diaries(owner).forEach{put("diary:"+it.date,it.content)}
 }
 private fun entries(payload:String):Map<String,String>{
  val obj=JSONObject(payload).optJSONObject("entries")?:return emptyMap()
  require(obj.length()<=1500)
  return obj.keys().asSequence().associateWith{key->val value=obj.getString(key)
   when{key=="profile.motto"->ProfileIdentity.motto(value);key=="profile.nickname"->ProfileIdentity.nickname(value);key.startsWith("diary:")->{LocalDate.parse(key.removePrefix("diary:"));DiaryContent.validate(value)};else->error("个人资料字段无效")};value}
 }
 private fun json(entries:Map<String,String>):String=JSONObject().put("entries",JSONObject().apply{entries.toSortedMap().forEach{(k,v)->put(k,v)}}).toString()
 private suspend fun syncProfile(owner:String,prefer:String?=null)=profileLock.withLock {
  if(!current(owner))return@withLock
  try{
   val (local,base)=repo.transaction{localProfile(owner) to entries(repo.dao.options(owner).find{it.key=="cloud.profile.shadow"}?.value?:"{}")}
   val remote=bridge.read(owner,"profile");check(current(owner));val cloud=entries(remote.payloadJson)
   val changed=(base.keys+local.keys).filter{local[it]!=base[it]}
   val conflict=changed.any{cloud[it]!=base[it]&&cloud[it]!=local[it]}
   if(conflict&&prefer==null){mutable.update{if(current(owner))it.copy(profileStatus="日记或资料存在冲突，请选择保留方式",profileConflict=true)else it};return@withLock}
   val merged=if(prefer=="cloud")cloud else cloud.toMutableMap().apply{changed.forEach{k->
    val value=local[k];if(value==null)remove(k) else put(k,value)
   }}
   val settled=if(merged!=cloud)bridge.write(owner,"profile",remote.revision,json(merged)) else remote
   val acknowledged=entries(settled.payloadJson)
   val pending=repo.transaction{
    check(current(owner));val now=localProfile(owner)
    // Only replace fields untouched since the request; retain newer offline edits for the next pass.
    (now.keys+acknowledged.keys).forEach{k->if(now[k]==local[k]){
     val value=acknowledged[k]
     if(k.startsWith("diary:")){val date=k.removePrefix("diary:");val row=repo.dao.diary(owner,date)
      if(value==null){if(row!=null)repo.dao.deleteDiary(owner,date,row.updatedAt)}else if(row?.content!=value)repo.dao.save(DiaryRow(owner,date,value,System.currentTimeMillis().coerceAtLeast((row?.updatedAt?:0)+1)))
     }else if(value==null)repo.dao.removeOption(owner,k) else if(now[k]!=value)repo.dao.save(UiPreferenceRow(owner,k,value))
    }}
    repo.dao.save(UiPreferenceRow(owner,"cloud.profile.shadow",json(acknowledged)));check(current(owner))
    localProfile(owner)!=acknowledged
   }
   mutable.update{if(current(owner))it.copy(profileStatus=if(pending)"个人资料待同步，本地修改已保留" else "日记、昵称与 motto 已跟随账号同步",profileConflict=false)else it}
  }catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{if(current(owner))it.copy(profileStatus=if(e is ApiException&&e.status==400)"个人资料超过云端限制，本地内容已保留" else "个人资料待同步，本地修改已保留")else it}}
 }
}
