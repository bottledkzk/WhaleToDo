package io.whaletodo.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AgentDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),DeskDatabase::class.java)
    @Test fun versionTwoMigrationPreservesRecordsAndAddsEmptyPrivateHistory() {
        val name="agent-migrate-${UUID.randomUUID()}.db"
        helper.createDatabase(name,2).apply{
            execSQL("INSERT INTO tasks VALUES('old','保留事项',90,1800000000000,4,0,1000,'保留备注',60,1)")
            execSQL("INSERT INTO daily_work_times VALUES('2030-01-01',540,1080)")
            execSQL("INSERT INTO blocks VALUES('b','old',10000,20000,1,0)");close()
        }
        helper.runMigrationsAndValidate(name,3,true,DeskDatabase.MIGRATION_2_3).apply{
            query("SELECT title,note,category FROM tasks WHERE id='old'").use{assertTrue(it.moveToFirst());assertEquals("保留事项",it.getString(0));assertEquals("保留备注",it.getString(1));assertEquals("none",it.getString(2))}
            query("SELECT COUNT(*) FROM daily_work_times").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            query("SELECT COUNT(*) FROM agent_messages").use{it.moveToFirst();assertEquals(0,it.getInt(0))};close()
        }
    }
    @Test fun proposalAppliesAtomicallyOnceAndRejectsStaleOrInvalidChanges()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try{
            val repo=DeskRepository(db);val task=TaskRow("task","原始标题",note="私人备注",ownerId="owner");repo.save(task,"owner")
            val source=repo.snapshot("owner");repo.applyAgent("proposal-1","owner",source,listOf(AgentChange.RenameTask(task.id,"新标题"),AgentChange.CategorizeTask(task.id,"study")))
            assertEquals("新标题",db.dao().tasks("owner").single().title);assertEquals("study",db.dao().tasks("owner").single().category)
            try{repo.applyAgent("proposal-1","owner",source,listOf(AgentChange.RenameTask(task.id,"重复")));fail("Receipt must reject replay")}catch(_:IllegalStateException){}
            val fresh=repo.snapshot("owner");repo.save(db.dao().tasks("owner").single().copy(note="之后编辑"),"owner")
            try{repo.applyAgent("proposal-2","owner",fresh,listOf(AgentChange.RenameTask(task.id,"过时")));fail("Snapshot changed")}catch(_:IllegalStateException){}
            val latest=repo.snapshot("owner")
            try{repo.applyAgent("proposal-3","owner",latest,listOf(AgentChange.RenameTask(task.id,"不能部分写入"),AgentChange.CategorizeTask("alien","work")));fail("Unknown target")}catch(_:IllegalArgumentException){}
            assertEquals("新标题",db.dao().tasks("owner").single().title);assertEquals(0,db.dao().receiptCount("proposal-3","owner"))
        }finally{db.close()}
    }
    @Test fun localConversationQueriesAreOwnerScopedAndArchivePreservesMessages()=runBlocking {
        val name="agent-history-${UUID.randomUUID()}.db";var db=Room.databaseBuilder(context,DeskDatabase::class.java,name).build()
        try{
            val session=AgentSessionRow("session","owner-a","学习建议",1,2);db.dao().save(session)
            db.dao().addMessage(AgentMessageRow("m","session","owner-a","assistant","chat","合成答复",2,1250))
            db.close();db=Room.databaseBuilder(context,DeskDatabase::class.java,name).build()
            assertEquals(1,db.dao().sessions("owner-a").size);assertTrue(db.dao().messages("session","owner-b").isEmpty())
            assertEquals(1250,db.dao().messages("session","owner-a").single().totalTokens)
            db.dao().archiveSession("session","owner-a");assertTrue(db.dao().sessions("owner-a").isEmpty());assertEquals(1,db.dao().messages("session","owner-a").size)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
