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
