package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.app.agent.*
import io.whaletodo.core.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCalendarApplyTest {
 @Test fun reflectionFactsUseTheUsersLocalDateAcrossUtcMidnight() {
  val date=LocalDate.parse("2030-01-02");val task=TaskRow("t","阅读",90,done=true,ownerId="owner")
  val check=CheckInRow("c","t",Instant.parse("2030-01-01T18:00:00Z").toEpochMilli(),4,ownerId="owner")
  val snapshot=DeskSnapshot(listOf(task),emptyList(),SettingRow(),emptyList(),date,"Asia/Shanghai")
  val wire=JSONObject(reflectionFacts(snapshot,listOf(check),"owner",true))
  val completed=wire.getJSONArray("completed").getJSONObject(0)
  assertEquals("2030-01-02 02:00",completed.getString("completed_local"))
  assertEquals("2030-01-02T02:00+08:00",completed.getString("completed_at"))
  assertFalse(wire.getBoolean("actual_duration_recorded"));assertEquals("2030-01-02",wire.getString("date"))
 }
 @Test fun advicePreviewRequiresAcceptAndPersistsAnAtomicOwnerBoundPlanWithoutAnotherModelCall()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DeskDatabase::class.java).build()
  val repo=DeskRepository(db);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val owner="advice-owner"
  try{
   val date=repo.snapshot(owner).date.plusDays(1);val zone=ZoneId.of(repo.snapshot(owner).zoneId)
   val meetingStart=date.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
   repo.dao.save(TaskRow("report","报告",90,date.atTime(18,0).atZone(zone).toInstant().toEpochMilli(),ownerId=owner))
   repo.dao.save(TaskRow("meeting","例会",30,ownerId=owner))
   val fixed=BlockRow("fixed","meeting",meetingStart,meetingStart+1800000,locked=true,ownerId=owner);repo.dao.insertBlocks(listOf(fixed))
   var calls=0
   val bridge=object:AgentBridge {
    override val access=MutableStateFlow(AgentAccess(owner,true,1,true,setOf("planner")))
    override val models=listOf(AgentModel("chat","聊天"),AgentModel("planner","编辑",true))
    override suspend fun reply(request:AssistantRequest):AssistantReply {calls++;return AssistantReply("建议优先安排报告，保留例会",400,chargedPoints=1)}
   }
   val controller=AssistantController(repo,scope,bridge)
   withTimeout(5000){while(controller.state.value.owner!=owner)delay(25)}
   controller.mode(AgentMode.PLAN);controller.send("安排报告，连续工作60分钟休息10分钟")
   withTimeout(5000){while(controller.state.value.advice==null||controller.state.value.busySince!=null)delay(25)}
   controller.previewAdvice();withTimeout(5000){while(controller.state.value.arrangement==null||controller.state.value.busySince!=null)delay(25)}
   assertEquals(listOf(fixed),repo.dao.blocks(owner));assertEquals(1,calls)
   val draft=controller.state.value.arrangement!!
   bridge.access.value=bridge.access.value.copy(availablePoints=0)
   withTimeout(5000){while(controller.state.value.access.availablePoints!=0)delay(25)}
   controller.applyArrangement();withTimeout(5000){while(repo.dao.receiptCount(draft.id,owner)==0)delay(25)}
   assertTrue(repo.dao.blocks(owner).contains(fixed));assertEquals(90,repo.dao.blocks(owner).filter{it.taskId=="report"}.sumOf{((it.end-it.start)/60000).toInt()})
   assertEquals(1,calls);assertTrue(repo.dao.blocks("other-owner").isEmpty())
   val after=repo.dao.blocks(owner)
   try{repo.applyAdvice(draft.id,owner,draft.arrangement);fail("repeat must reject")}catch(_:IllegalStateException){}
   try{repo.applyAdvice("other-id","other-owner",draft.arrangement);fail("owner must reject")}catch(_:IllegalStateException){}
   assertEquals(after,repo.dao.blocks(owner));assertTrue(repo.dao.blocks("other-owner").isEmpty())
  }finally{scope.cancel();db.close()}
 }
 @Test fun scheduledTaskRequiresPreviewThenPersistsOnceAndStaleProposalCannotApply()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DeskDatabase::class.java).build()
  val repo=DeskRepository(db);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val owner="calendar-owner"
  try{
   repo.dao.save(TaskRow("reading","阅读",30,ownerId=owner))
   val source=repo.snapshot(owner);val start=source.date.plusDays(1).atTime(10,0).atZone(ZoneId.of(source.zoneId)).toInstant().toEpochMilli()
   var modelCalls=0
   val bridge=object:AgentBridge {
    override val access=MutableStateFlow(AgentAccess(owner,true,20,true,setOf("deepseek-planner")))
    override val models=listOf(AgentModel("deepseek-planner","编辑",true))
    override suspend fun reply(request:AssistantRequest):AssistantReply {
     modelCalls++;val wire=JSONObject(request.context);assertEquals("Actual edit requests must advertise schedule_task support",2,wire.optInt("action_version",1));assertEquals(source.zoneId,wire.getString("timezone"));assertTrue(wire.getJSONArray("available").length()>0)
     return AssistantReply("以下是待确认的日程提案",500,chargedPoints=0,changes=listOf(AgentChange.ScheduleTask("reading",start,start+1800000)))
    }
   }
   val controller=AssistantController(repo,scope,bridge)
   withTimeout(5000){while(controller.state.value.owner!=owner)delay(25)}
   controller.mode(AgentMode.EDIT);controller.consent(true);controller.send("把阅读安排到明天10:00")
   withTimeout(5000){while(controller.state.value.preview==null)delay(25)}
   assertTrue(repo.dao.blocks(owner).isEmpty());val id=controller.state.value.preview!!.id
   controller.applyPreview();withTimeout(5000){while(repo.dao.blocks(owner).isEmpty())delay(25)}
   assertEquals(start,repo.dao.blocks(owner).single().start);assertEquals(1,repo.dao.receiptCount(id,owner));assertEquals(1,modelCalls)
   controller.applyPreview();delay(100);assertEquals(1,repo.dao.blocks(owner).size)
   val changed=repo.snapshot(owner);repo.dao.save(changed.tasks.single().copy(title="手动修改"))
   try{repo.applyAgent("stale",owner,changed,listOf(AgentChange.MoveBlock(repo.dao.blocks(owner).single().id,start+3600000,start+5400000)));fail("stale must reject")}catch(_:IllegalStateException){}
   assertEquals(start,repo.dao.blocks(owner).single().start)
  }finally{scope.cancel();db.close()}
 }
}
