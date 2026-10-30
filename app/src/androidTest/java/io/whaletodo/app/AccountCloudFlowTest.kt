package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.account.AccountCloudBridge
import io.whaletodo.app.agent.*
import io.whaletodo.app.data.*
import io.whaletodo.app.sync.*
import io.whaletodo.core.*
import io.whaletodo.network.CloudDocument
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountCloudFlowTest {
 private class Agent(owner:String="owner-a",vip:Boolean=true):AgentBridge {
  override val access=MutableStateFlow(AgentAccess(owner,vip,20,true,setOf("deepseek-planner")))
  override val models=listOf(AgentModel("deepseek-planner","编辑",true))
  override suspend fun reply(request:AssistantRequest):AssistantReply=error("No model POST permitted in cloud tests")
 }
 private class Cloud:AccountCloudBridge {
  val docs=mutableMapOf<String,CloudDocument>();val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
  @Volatile var offline=false
  var beforeWrite:(suspend ()->Unit)?=null
  @Synchronized fun put(owner:String,kind:String,payload:String):CloudDocument {val key="$owner:$kind";return CloudDocument((docs[key]?.revision?:0)+1,payload,1).also{docs[key]=it}}
  override suspend fun read(owner:String,kind:String):CloudDocument {calls+="GET:$owner:$kind";if(offline)throw java.io.IOException();return synchronized(this){docs["$owner:$kind"]?:CloudDocument(0,"{}",null)}}
  override suspend fun write(owner:String,kind:String,revision:Int,payload:String):CloudDocument {calls+="POST:$owner:$kind";if(offline)throw java.io.IOException();beforeWrite?.invoke();return synchronized(this){check((docs["$owner:$kind"]?.revision?:0)==revision);put(owner,kind,payload)}}
 }
 private data class Fixture(val db:DeskDatabase,val repo:DeskRepository,val scope:CoroutineScope,val agent:Agent,val controller:AccountCloudController)
 private fun fixture(cloud:Cloud,owner:String="owner-a",vip:Boolean=true,onScheduleChanged:suspend ()->Unit={}):Fixture {
  val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DeskDatabase::class.java).build()
  val repo=DeskRepository(db);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val agent=Agent(owner,vip)
  return Fixture(db,repo,scope,agent,AccountCloudController(repo,scope,cloud,agent,onScheduleChanged))
 }
 private suspend fun await(predicate:()->Boolean){withTimeout(10000){while(!predicate())delay(25)}}
 private fun close(vararg fixtures:Fixture){fixtures.forEach{it.scope.cancel();it.db.close()}}

 @Test fun loginOnlyFollowsProfileWhileSchedulesAndChatStayLocal()=runBlocking {
  val cloud=Cloud();cloud.put("owner-a","profile","""{"entries":{"profile.motto":"同步的座右铭","diary:2030-01-01":"合成日记"}}""")
  val a=fixture(cloud,vip=false)
  try{
   a.repo.capture("本地事项","owner-a");a.repo.dao.save(AgentSessionRow("session-local","owner-a","本地会话",1,1))
   await{a.controller.state.value.profileStatus.contains("已跟随")}
   assertEquals("同步的座右铭",a.repo.dao.options("owner-a").single{it.key=="profile.motto"}.value)
   assertEquals("合成日记",a.repo.dao.diary("owner-a","2030-01-01")!!.content)
   assertEquals("本地事项",a.repo.dao.tasks("owner-a").single().title)
   assertEquals(1,a.repo.dao.sessions("owner-a").size)
   assertTrue(cloud.calls.none{it.endsWith(":schedule")})
   assertTrue(cloud.docs.values.none{it.payloadJson.contains("session-local")||it.payloadJson.contains("本地事项")})
   a.controller.prepareBackup();await{a.controller.state.value.error.contains("VIP")};assertTrue(cloud.calls.none{it.endsWith(":schedule")})
  }finally{close(a)}
 }
 @Test fun offlineProfileEditsAndDeletionReachAnotherDeviceWithoutScheduleSync()=runBlocking {
  val cloud=Cloud();cloud.offline=true;val a=fixture(cloud)
  var b:Fixture?=null
  try{
   a.repo.saveDiary("owner-a","2030-01-01","离线日记",null)
   a.repo.dao.save(UiPreferenceRow("owner-a","profile.motto","离线 motto"))
   await{a.controller.state.value.profileStatus.contains("待同步")}
   assertNull(cloud.docs["owner-a:profile"])
   cloud.offline=false;a.controller.refreshProfile();await{a.controller.state.value.profileStatus.contains("已跟随")}
   assertTrue("New offline diary must be present in the acknowledged server document",cloud.docs.getValue("owner-a:profile").payloadJson.contains("离线日记"))
   b=fixture(cloud);await{b!!.controller.state.value.profileStatus.contains("已跟随")}
   assertEquals("离线日记",b.repo.dao.diary("owner-a","2030-01-01")!!.content)
   a.repo.deleteDiary(a.repo.dao.diary("owner-a","2030-01-01")!!);a.controller.refreshProfile()
   await{!cloud.docs.getValue("owner-a:profile").payloadJson.contains("diary:")}
   b.controller.refreshProfile();await{b!!.controller.state.value.profileStatus.contains("已跟随")}
   withTimeout(5000){while(b.repo.dao.diary("owner-a","2030-01-01")!=null)delay(25)}
   assertTrue(cloud.calls.none{it.endsWith(":schedule")})
  }finally{close(a);b?.let{close(it)}}
 }
 @Test fun explicitBackupReplaceAndCompatibleImportAreOwnerScopedAndPreserveProfileAndChat()=runBlocking {
  val cloud=Cloud();val a=fixture(cloud)
  try{
   await{a.controller.state.value.owner=="owner-a"}
   a.repo.dao.save(TaskRow("same","本地标题",30,ownerId="owner-a"))
   a.repo.dao.save(TaskRow("guest","访客保留",ownerId="guest"))
   a.repo.dao.save(TaskRow("other-owner","另一账户保留",ownerId="owner-b"))
   a.repo.dao.save(AgentSessionRow("chat","owner-a","本地会话",1,1))
   a.controller.prepareBackup();await{a.controller.state.value.preview!=null}
   assertNull(cloud.docs["owner-a:schedule"]);a.controller.confirm();await{!a.controller.state.value.busy&&a.controller.state.value.preview==null}
   assertNotNull(cloud.docs["owner-a:schedule"])
   val remote=ScheduleBackup.capture(a.repo,"owner-a").copy(tasks=listOf(TaskRow("same","云端同编号",30,ownerId="owner-a"),TaskRow("cloud-only","云端独有",30,ownerId="owner-a")))
   cloud.put("owner-a","schedule",remote.json());a.controller.prepareDownload("compatible");await{a.controller.state.value.preview!=null}
   assertEquals(1,a.repo.dao.tasks("owner-a").size);a.controller.confirm();await{!a.controller.state.value.busy&&a.controller.state.value.preview==null}
   assertEquals("本地标题",a.repo.dao.tasks("owner-a").single{it.id=="same"}.title);assertEquals(2,a.repo.dao.tasks("owner-a").size)
   a.controller.prepareDownload("replace");await{a.controller.state.value.preview!=null};a.controller.confirm();await{!a.controller.state.value.busy&&a.controller.state.value.preview==null}
   assertEquals("云端同编号",a.repo.dao.tasks("owner-a").single{it.id=="same"}.title)
   assertEquals("访客保留",a.repo.dao.tasks("guest").single().title);assertEquals("另一账户保留",a.repo.dao.tasks("owner-b").single().title)
   assertEquals(1,a.repo.dao.sessions("owner-a").size)
  }finally{close(a)}
 }
 @Test fun stalePreviewOrRevokedVipCannotWriteAndProfileConflictsNeedChoice()=runBlocking {
  val cloud=Cloud();val a=fixture(cloud)
  try{
   await{a.controller.state.value.owner=="owner-a"};a.repo.capture("原事项","owner-a")
   a.controller.prepareBackup();await{a.controller.state.value.preview!=null};a.repo.capture("后来修改","owner-a");a.controller.confirm();await{!a.controller.state.value.busy}
   assertNull(cloud.docs["owner-a:schedule"]);assertTrue(a.controller.state.value.error.contains("已变化"))
   a.controller.prepareBackup();await{a.controller.state.value.preview!=null};a.agent.access.value=a.agent.access.value.copy(vip=false);a.controller.confirm();await{!a.controller.state.value.busy};assertNull(cloud.docs["owner-a:schedule"])
   a.repo.dao.save(UiPreferenceRow("owner-a","profile.motto","本地不同"));cloud.put("owner-a","profile","""{"entries":{"profile.motto":"云端不同"}}""")
   a.controller.refreshProfile();await{a.controller.state.value.profileConflict};assertEquals("本地不同",a.repo.dao.options("owner-a").single{it.key=="profile.motto"}.value)
   a.controller.refreshProfile("cloud");await{!a.controller.state.value.profileConflict};assertEquals("云端不同",a.repo.dao.options("owner-a").single{it.key=="profile.motto"}.value)
  }finally{close(a)}
 }
 @Test fun newerLocalProfileEditSurvivesOlderInFlightAcknowledgement()=runBlocking {
  val cloud=Cloud();val a=fixture(cloud);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try{
   await{a.controller.state.value.profileStatus.contains("已跟随")}
   a.repo.dao.save(UiPreferenceRow("owner-a","profile.motto","第一次编辑"))
   cloud.beforeWrite={entered.complete(Unit);release.await()};a.controller.refreshProfile();withTimeout(5000){entered.await()}
   a.repo.dao.save(UiPreferenceRow("owner-a","profile.motto","后来的编辑"));cloud.beforeWrite=null;release.complete(Unit)
   await{cloud.docs["owner-a:profile"]?.payloadJson?.contains("第一次编辑")==true}
   assertEquals("后来的编辑",a.repo.dao.options("owner-a").single{it.key=="profile.motto"}.value)
   a.controller.refreshProfile();await{cloud.docs["owner-a:profile"]?.payloadJson?.contains("后来的编辑")==true}
  }finally{release.complete(Unit);close(a)}
 }
 @Test fun compatibleMergeKeepsConflictingCloudTaskWithoutOverlappingBlocks()=runBlocking {
  val cloud=Cloud();val a=fixture(cloud)
  try{
   val local=ScheduleBackup(listOf(TaskRow("local","本地",30)),listOf(BlockRow("lb","local",1000,2000,true)),emptyList(),emptyList(),SettingRow(),emptyList())
   val remote=local.copy(tasks=listOf(TaskRow("cloud","云端",30)),blocks=listOf(BlockRow("cb","cloud",1500,2500)))
   val (merged,skipped)=local.compatible(remote)
   assertEquals(2,merged.tasks.size);assertEquals(1,merged.blocks.size);assertEquals(1,skipped)
  }finally{close(a)}
 }
 @Test fun reminderFailureAfterImportDoesNotMisreportCommittedDataAsUnchanged()=runBlocking {
  val cloud=Cloud();val a=fixture(cloud,onScheduleChanged={error("Synthetic reminder failure")})
  try{
   await{a.controller.state.value.owner=="owner-a"}
   val incoming=ScheduleBackup.capture(a.repo,"owner-a").copy(tasks=listOf(TaskRow("cloud","云端事项",30,ownerId="owner-a")))
   cloud.put("owner-a","schedule",incoming.json());a.controller.prepareDownload("replace");await{a.controller.state.value.preview!=null}
   a.controller.confirm();await{!a.controller.state.value.busy}
   assertEquals("云端事项",a.repo.dao.tasks("owner-a").single().title)
   assertTrue(a.controller.state.value.notice.contains("已同步"));assertTrue(a.controller.state.value.notice.contains("提醒"));assertEquals("",a.controller.state.value.error)
  }finally{close(a)}
 }
}
