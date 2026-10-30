package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.core.AgentChange
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Replays 3 actual provider receipts on synthetic local data; timestamps shift by whole days. */
class RealAiCalendarReplayTest {
 @Test fun realNewMoveAndOpinionReceiptsOnlyWriteAfterAcceptance()=runBlocking {
  val instrument=InstrumentationRegistry.getInstrumentation()
  val f=JSONObject(instrument.context.assets.open("real-ai-calendar-20261030.json").bufferedReader().use{it.readText()})
  val zone=ZoneId.of("Asia/Shanghai");val day=LocalDate.now(zone).plusDays(1)
  val shift=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(f.getString("anchor_date")),day)*86400000
  val cases=f.getJSONArray("cases")
  for(i in 0 until cases.length()){
   val db=Room.inMemoryDatabaseBuilder(instrument.targetContext,DeskDatabase::class.java).build()
   try{
    val repo=DeskRepository(db);val owner="real-calendar-replay";val task=f.getString("task_id");val item=cases.getJSONObject(i)
    repo.dao.save(UiPreferenceRow("device","time.zone",zone.id))
    repo.dao.save(TaskRow(task,"阅读第3章",30,day.plusDays(3).atTime(17,0).atZone(zone).toInstant().toEpochMilli(),ownerId=owner))
    val c=item.getJSONArray("changes").getJSONObject(0);val start=c.getLong("start_ms")+shift;val end=c.getLong("end_ms")+shift
    val action=if(c.getString("type")=="schedule_task")AgentChange.ScheduleTask(task,start,end) else {
     val initial=day.atTime(9,0).atZone(zone).toInstant().toEpochMilli();repo.dao.save(BlockRow(f.getString("block_id"),task,initial,initial+1800000,ownerId=owner));AgentChange.MoveBlock(f.getString("block_id"),start,end)
    }
    val before=repo.snapshot(owner);val id=item.getString("case_id")
    repo.checkAgentPreview(before,listOf(action),owner);assertEquals(before,repo.snapshot(owner));assertEquals(0,repo.dao.receiptCount(id,owner))
    repo.applyAgent(id,owner,before,listOf(action));assertEquals(start,repo.dao.blocks(owner).single().start);assertEquals(end,repo.dao.blocks(owner).single().end)
    assertEquals(1,repo.dao.receiptCount(id,owner));assertTrue(item.getInt("usage_tokens")>0)
    try{repo.applyAgent(id,owner,before,listOf(action));fail("Duplicate acceptance must not write")}catch(_:IllegalStateException){}
    assertEquals(1,repo.dao.blocks(owner).size)
   }finally{db.close()}
  }
 }
}
