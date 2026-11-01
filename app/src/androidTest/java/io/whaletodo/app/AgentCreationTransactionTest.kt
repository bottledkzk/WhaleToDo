package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.agent.*
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCreationTransactionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun newCourseIsAtomicIdempotentAndBoundToTheExactOwnerSnapshot()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try {
            val repo=DeskRepository(db);val owner="creation-owner"
            val source=repo.snapshot(owner);val start=LiveTime.ceilMinute(Instant.now()).plusSeconds(86400).toEpochMilli()
            val changes=listOf(AgentChange.CreateTask("new:1","合成课程","study",start,start+120*60000L,true))
            repo.checkAgentPreview(source,changes,owner)
            assertTrue(db.dao().tasks(owner).isEmpty())
            repo.applyAgent("one-request",owner,source,changes)
            assertEquals(1,db.dao().tasks(owner).size);assertEquals(120,db.dao().tasks(owner).single().minutes)
            assertTrue(db.dao().blocks(owner).single().locked);assertTrue(db.dao().tasks("another-owner").isEmpty())
            assertTrue(runCatching{repo.applyAgent("one-request",owner,source,changes)}.isFailure)
            val after=repo.snapshot(owner)
            assertTrue(runCatching{repo.applyAgent("collision",owner,after,listOf(changes.single().copy(key="new:2")))}.isFailure)
            assertEquals(1,db.dao().tasks(owner).size);assertEquals(1,db.dao().blocks(owner).size)
        } finally {db.close()}
    }
    @Test fun editPreferenceSurvivesControllerRestartWithoutCrossAccountConsent()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=DeskRepository(db)
            val bridge=object:AgentBridge {
                override val access=MutableStateFlow(AgentAccess(userId="account-a"))
                override val models=emptyList<AgentModel>()
                override suspend fun reply(request:AssistantRequest):AssistantReply=error("not called")
            }
            suspend fun waitFor(test:()->Boolean) {withTimeout(5000){while(!test())delay(20)}}
            val first=AssistantController(repo,scope,bridge)
            waitFor{first.state.value.owner=="account-a"};first.consent(true)
            withTimeout(5000){while(repo.dao.options("local-agent:account-a").none{it.key=="edit"&&it.value=="true"})delay(20)}
            first.newSession();first.mode(AgentMode.EDIT);assertTrue(first.state.value.editConsent)
            val restarted=AssistantController(repo,scope,bridge);waitFor{restarted.state.value.owner=="account-a"}
            assertTrue(restarted.state.value.editConsent)
            bridge.access.value=AgentAccess(userId="account-b");waitFor{restarted.state.value.owner=="account-b"}
            assertFalse(restarted.state.value.editConsent)
        } finally {scope.cancel();db.close()}
    }
    @Test fun yesterdayDiaryContainsOnlyThatDaysCompletedFacts() {
        val zone=ZoneId.of("Asia/Shanghai");val date=LocalDate.parse("2026-11-01")
        val task=TaskRow("finished","合成复习",done=true,ownerId="demo")
        val state=DeskSnapshot(listOf(task),emptyList(),SettingRow(ownerId="demo"),emptyList(),date,zone.id)
        val records=listOf(CheckInRow("nov1",task.id,date.atTime(10,0).atZone(zone).toInstant().toEpochMilli(),4,ownerId="demo"),CheckInRow("nov2",task.id,date.plusDays(1).atTime(11,0).atZone(zone).toInstant().toEpochMilli(),5,ownerId="demo"))
        val facts=JSONObject(reflectionFacts(state,records,"demo",false))
        assertEquals("2026-11-01",facts.getString("date"));assertEquals(1,facts.getJSONArray("completed").length())
        assertTrue(facts.getJSONArray("completed").getJSONObject(0).getString("completed_local").startsWith("2026-11-01"))
    }
}
