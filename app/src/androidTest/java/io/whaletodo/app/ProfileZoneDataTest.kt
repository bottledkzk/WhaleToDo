package io.whaletodo.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileZoneDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun stamp(text:String)=Instant.parse(text).toEpochMilli()
    @Test fun explicitConversionMovesFutureLocalClocksButPreservesHistoryIdsAndDurations()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try {
            val repo=DeskRepository(db);val dao=db.dao();dao.save(UiPreferenceRow("device","time.zone","UTC"))
            val live=TaskRow("live","未来事项",deadline=stamp("2030-01-02T12:00:00Z"),created=100)
            val done=TaskRow("done","历史事项",deadline=stamp("2030-01-02T12:00:00Z"),done=true,created=200)
            dao.save(live);dao.save(done)
            val b=BlockRow("future","live",stamp("2030-01-02T09:00:00Z"),stamp("2030-01-02T10:00:00Z"),locked=true)
            val history=BlockRow("history","done",stamp("2025-01-02T09:00:00Z"),stamp("2025-01-02T10:00:00Z"),done=true)
            dao.insertBlocks(listOf(b,history));dao.addCheckIn(CheckInRow("check","done",stamp("2025-01-02T10:00:00Z"),5))
            repo.changeZone(DeskZoneChoice("Asia/Shanghai"),true,DeskZoneChoice("UTC"))
            val blocks=dao.blocks();val moved=blocks.first{it.id==b.id}
            assertEquals(stamp("2030-01-02T01:00:00Z"),moved.start);assertEquals(b.end-b.start,moved.end-moved.start);assertTrue(moved.locked)
            assertEquals(history,blocks.first{it.id==history.id});assertEquals(done,dao.tasks().first{it.id==done.id})
            val t=dao.tasks().first{it.id==live.id};assertEquals(stamp("2030-01-02T04:00:00Z"),t.deadline);assertEquals(100L,t.created)
            db.openHelper.readableDatabase.query("SELECT completedAt FROM checkins WHERE id='check'").use{it.moveToFirst();assertEquals(stamp("2025-01-02T10:00:00Z"),it.getLong(0))}
        } finally {db.close()}
    }
    @Test fun noConversionAndStaleSelectionNeverRewriteSchedule()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try {
            val repo=DeskRepository(db);val task=TaskRow("t","保持原时刻",deadline=stamp("2030-01-02T12:00:00Z"));repo.save(task)
            val b=BlockRow("b","t",stamp("2030-01-02T09:00:00Z"),stamp("2030-01-02T10:00:00Z"));db.dao().insertBlocks(listOf(b))
            repo.changeZone(DeskZoneChoice("+05:45"),false,DeskZoneChoice("system"));assertEquals(listOf(task),db.dao().tasks());assertEquals(listOf(b),db.dao().blocks())
            try{repo.changeZone(DeskZoneChoice("UTC"),true,DeskZoneChoice("system"));fail("stale timezone")}catch(_:IllegalStateException){}
            assertEquals("+05:45",repo.snapshot().zoneId);assertEquals(listOf(b),db.dao().blocks())
        } finally {db.close()}
    }
    @Test fun nonexistentLocalTimeRollsBackZoneAndAllRecords()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build()
        try {
            val repo=DeskRepository(db);db.dao().save(UiPreferenceRow("device","time.zone","UTC"))
            repo.save(TaskRow("gap","DST边界",deadline=stamp("2030-03-10T02:30:00Z")))
            val before=repo.snapshot()
            try{repo.changeZone(DeskZoneChoice("America/Los_Angeles"),true,DeskZoneChoice("UTC"));fail("gap must not shift silently")}catch(_:IllegalArgumentException){}
            assertEquals(before,repo.snapshot())
        } finally {db.close()}
    }
}
