package io.whaletodo.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),DeskDatabase::class.java)
    @Test fun migrationThreeToFourRetainsTasksHistoryAndDefaults(){
        val name="profile-migration-${UUID.randomUUID()}.db"
        helper.createDatabase(name,3).apply{
            execSQL("INSERT INTO tasks VALUES('old','保留事项',60,1800000000000,3,0,1000,'私人原备注',60,1,'study')")
            execSQL("INSERT INTO settings VALUES(1,8,24)");execSQL("INSERT INTO daily_work_times VALUES('2030-01-01',555,1107)")
            execSQL("INSERT INTO agent_sessions VALUES('s','owner','保留会话',1,2,0)");close()
        }
        helper.runMigrationsAndValidate(name,4,true,DeskDatabase.MIGRATION_3_4).apply{
            query("SELECT title,note,category FROM tasks").use{assertTrue(it.moveToFirst());assertEquals("保留事项",it.getString(0));assertEquals("私人原备注",it.getString(1));assertEquals("study",it.getString(2))}
            query("SELECT endHour FROM settings").use{it.moveToFirst();assertEquals(24,it.getInt(0))}
            query("SELECT COUNT(*) FROM agent_sessions").use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            for(table in listOf("diaries","task_reminders","ui_preferences"))query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();assertEquals(0,it.getInt(0))};close()
        };context.deleteDatabase(name)
    }
    @Test fun diariesAreOwnerScopedBoundedAndProtectedFromStaleSaveOrDelete()=runBlocking{
        val name="profile-diary-${UUID.randomUUID()}.db";var db=Room.databaseBuilder(context,DeskDatabase::class.java,name).build()
        try{val repo=DeskRepository(db);repo.saveDiary("a","2030-01-01","🐳".repeat(800),null)
            val row=db.dao().diary("a","2030-01-01")!!;assertNull(db.dao().diary("b","2030-01-01"))
            try{repo.saveDiary("a",row.date,"x".repeat(801),row);fail("long diary")}catch(_:IllegalArgumentException){}
            repo.saveDiary("a",row.date,"后来编辑的日记",row)
            try{repo.saveDiary("a",row.date,"过时覆盖",row);fail("stale save")}catch(_:IllegalStateException){}
            try{repo.deleteDiary(row);fail("stale delete")}catch(_:IllegalStateException){}
            db.close();db=Room.databaseBuilder(context,DeskDatabase::class.java,name).build()
            assertEquals("后来编辑的日记",db.dao().diary("a",row.date)!!.content)
            DeskRepository(db).deleteDiary(db.dao().diary("a",row.date)!!);assertNull(db.dao().diary("a",row.date))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun reminderAndTaskChangesAreAtomicAndZonePreferencesDoNotRewriteEpochs()=runBlocking{
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try{val repo=DeskRepository(db);val task=TaskRow("t","原始标题",deadline=1900000000000,ownerId="a");repo.save(task,"a")
            try{repo.saveScheduled(task.copy(title="不能部分保存"),null,TaskReminderRow("a","t",true,"invalid","start"),"a");fail("bad reminder")}catch(_:IllegalArgumentException){}
            assertEquals("原始标题",db.dao().tasks("a").single().title);assertNull(db.dao().reminder("a","t"))
            repo.saveScheduled(task,null,TaskReminderRow("a","t",true,"both","both"),"a");assertTrue(db.dao().reminder("a","t")!!.enabled);assertNull(db.dao().reminder("b","t"))
            val before=repo.snapshot("a");db.dao().save(UiPreferenceRow("device","time.zone","+08:00"));db.dao().save(UiPreferenceRow("device","working.default","555,1065"))
            val after=repo.snapshot("a");assertEquals("+08:00",after.zoneId);assertEquals(before.tasks,after.tasks);assertEquals(MinuteRange(555,1065),DeskOptions.defaults(after.preferences,after.settings))
            try{repo.apply(PlanPreview(before,ScheduleResult(emptyList(),emptyList())),"a");fail("old snapshot")}catch(_:IllegalStateException){}
        }finally{db.close()}
    }
}
