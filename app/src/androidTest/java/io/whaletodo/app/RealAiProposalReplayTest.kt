package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.app.agent.reflectionFacts
import io.whaletodo.core.AgentChange
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real model receipts from synthetic tasks, replayed locally without model/email traffic or device data clearing. */
class RealAiProposalReplayTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private val fixture get()=JSONObject(instrument.context.assets.open("real-ai-edit-replay.json").bufferedReader().use{it.readText()})
    private val owner="synthetic-real-ai-replay"
    @Test fun reflectionUsesOnlyOwnedDatedCheckinsAndDoesNotInventActualDuration()=runBlocking{
        withFixture{db,repo,f,_->
            val taskId=f.getString("task_id")
            db.dao().save(TaskRow("old-done","没有当日打卡的旧完成事项",done=true,ownerId=owner))
            val source=repo.snapshot(owner).copy(date=LocalDate.of(2030,1,2),zoneId="Asia/Shanghai")
            val today=Instant.parse("2030-01-01T16:05:00Z").toEpochMilli()
            val yesterday=Instant.parse("2030-01-01T15:59:00Z").toEpochMilli()
            val records=listOf(CheckInRow("today",taskId,today,3,owner),CheckInRow("yesterday","old-done",yesterday,3,owner),CheckInRow("other",taskId,today,3,"another-owner"))
            val text=reflectionFacts(source,records,owner,false);val value=JSONObject(text)
            assertEquals(1,value.getJSONArray("completed").length());assertEquals(taskId,value.getJSONArray("completed").getJSONObject(0).getString("id"))
            assertFalse(text.contains("estimated_minutes"));assertFalse(text.contains("没有当日打卡"));assertFalse(value.getBoolean("actual_duration_recorded"))
            assertEquals(0,value.getJSONArray("unfinished").length())
        }
    }
    private suspend fun withFixture(action:suspend(DeskDatabase,DeskRepository,JSONObject,Long)->Unit){
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try{
            val f=fixture;val zone=ZoneId.of("Asia/Shanghai");val day=LocalDate.now(zone).plusDays(1)
            val start=day.atTime(9,0).atZone(zone).toInstant().toEpochMilli();val shift=start-f.getLong("source_start_ms")
            db.dao().save(UiPreferenceRow("device","time.zone",zone.id))
            db.dao().save(TaskRow(f.getString("task_id"),"阅读第3章",minutes=30,deadline=day.atTime(17,0).atZone(zone).toInstant().toEpochMilli(),note="合成备注保持原样",ownerId=owner))
            db.dao().insertBlocks(listOf(BlockRow(f.getString("block_id"),f.getString("task_id"),start,start+1800000,ownerId=owner)))
            action(db,DeskRepository(db),f,shift)
        }finally{db.close()}
    }
    private fun changes(values:JSONArray,shift:Long):List<AgentChange> = (0 until values.length()).map{i->
        val c=values.getJSONObject(i);when(c.getString("type")){
            "rename_task"->AgentChange.RenameTask(c.getString("target_id"),c.getString("title"))
            "set_category"->AgentChange.CategorizeTask(c.getString("target_id"),c.getString("category"))
            "move_block"->AgentChange.MoveBlock(c.getString("target_id"),c.getLong("start_ms")+shift,c.getLong("end_ms")+shift)
            else->error("Unsupported receipt fixture")
        }
    }
    @Test fun realRenameCategoryMoveAndOpinionApplyOnlyAfterConfirmation()=runBlocking{
        for(index in 0 until fixture.getJSONArray("positive").length())withFixture{db,repo,f,shift->
            val item=f.getJSONArray("positive").getJSONObject(index);val id="real-replay-"+item.getString("case_id")
            val edits=changes(item.getJSONArray("changes"),shift);val before=repo.snapshot(owner)
            repo.checkAgentPreview(before,edits,owner)
            assertEquals(before,repo.snapshot(owner));assertEquals(0,db.dao().receiptCount(id,owner))
            // Refusing a preview performs no write. Only the explicit acceptance reaches applyAgent.
            assertEquals("阅读第3章",db.dao().tasks(owner).single().title)
            repo.applyAgent(id,owner,before,edits)
            assertEquals(1,db.dao().receiptCount(id,owner));assertEquals("合成备注保持原样",db.dao().tasks(owner).single().note)
            edits.forEach{c->when(c){
                is AgentChange.RenameTask->assertEquals(c.title,db.dao().tasks(owner).single().title)
                is AgentChange.CategorizeTask->assertEquals(c.category,db.dao().tasks(owner).single().category)
                is AgentChange.MoveBlock->{assertEquals(c.start,db.dao().blocks(owner).single().start);assertEquals(c.end,db.dao().blocks(owner).single().end)}
            }}
            try{repo.applyAgent(id,owner,before,edits);fail("Duplicate acceptance must be rejected")}catch(_:IllegalStateException){}
            assertEquals(1,db.dao().receiptCount(id,owner))
        }
    }
    @Test fun unsafeActualModelCompletedBlockAndStaleSnapshotCannotWrite()=runBlocking{
        withFixture{db,repo,f,shift->
            val block=db.dao().blocks(owner).single();db.dao().save(block.copy(done=true))
            val source=repo.snapshot(owner);val unsafe=changes(f.getJSONArray("unsafe_completed_counterexample"),shift)
            try{repo.checkAgentPreview(source,unsafe,owner);fail("Actual unsafe model output must not reach acceptance")}catch(_:IllegalArgumentException){}
            assertEquals(block.start,db.dao().blocks(owner).single().start);assertEquals(0,db.dao().receiptCount("unsafe",owner))
            db.dao().save(block)
            val old=repo.snapshot(owner);val edit=changes(f.getJSONArray("positive").getJSONObject(0).getJSONArray("changes"),shift)
            db.dao().save(db.dao().tasks(owner).single().copy(title="用户后来编辑的合成标题"))
            try{repo.applyAgent("stale",owner,old,edit);fail("Stale real-model proposal cannot replace a newer task")}catch(_:IllegalStateException){}
            assertEquals("用户后来编辑的合成标题",db.dao().tasks(owner).single().title);assertEquals(0,db.dao().receiptCount("stale",owner))
        }
    }
}
