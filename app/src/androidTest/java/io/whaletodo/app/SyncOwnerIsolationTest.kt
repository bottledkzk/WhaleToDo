package io.whaletodo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.BlockRow
import io.whaletodo.app.data.CheckInRow
import io.whaletodo.app.data.DailyWorkTimeRow
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.DeskRepository
import io.whaletodo.app.data.SettingRow
import io.whaletodo.app.data.TaskRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncOwnerIsolationTest {
    private lateinit var db: DeskDatabase
    private lateinit var repo: DeskRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, DeskDatabase::class.java).build()
        repo = DeskRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sameContentIdCanCoexistUnderDifferentOwners() = runBlocking {
        val dao = db.dao()
        dao.save(TaskRow("same", "A任务", ownerId = "owner-a"))
        dao.save(TaskRow("same", "B任务", ownerId = "owner-b"))
        assertEquals("A任务", dao.tasks("owner-a").single().title)
        assertEquals("B任务", dao.tasks("owner-b").single().title)

        dao.save(BlockRow("same-block", "same", 1000, 2000, ownerId = "owner-a"))
        dao.save(BlockRow("same-block", "same", 3000, 4000, ownerId = "owner-b"))
        assertEquals(1000L, dao.blocks("owner-a").single().start)
        assertEquals(3000L, dao.blocks("owner-b").single().start)

        dao.addCheckIn(CheckInRow("same-check", "same", 5000, 4, ownerId = "owner-a"))
        dao.addCheckIn(CheckInRow("same-check", "same", 6000, 5, ownerId = "owner-b"))
        assertEquals(5000L, dao.checkIns("owner-a").single().completedAt)
        assertEquals(6000L, dao.checkIns("owner-b").single().completedAt)
        assertEquals(1, dao.tasks("owner-a").size)
        assertEquals(1, dao.blocks("owner-b").size)

        dao.setBlocksDone("same", true, "owner-a")
        assertTrue(dao.blocks("owner-a").single().done)
        assertFalse(dao.blocks("owner-b").single().done)
    }

    @Test
    fun settingsAndWorkTimesAreOwnerScoped() = runBlocking {
        val dao = db.dao()
        dao.save(SettingRow(startHour = 8, endHour = 18, ownerId = "owner-a"))
        dao.save(SettingRow(startHour = 10, endHour = 20, ownerId = "owner-b"))
        assertEquals(8, dao.settings("owner-a")!!.startHour)
        assertEquals(10, dao.settings("owner-b")!!.startHour)

        dao.save(DailyWorkTimeRow("2026-10-28", 480, 720, ownerId = "owner-a"))
        dao.save(DailyWorkTimeRow("2026-10-28", 600, 900, ownerId = "owner-b"))
        assertEquals(480, dao.workTimes("owner-a").single().startMinute)
        assertEquals(600, dao.workTimes("owner-b").single().startMinute)
    }

    @Test
    fun repositoryRejectsCrossOwnerWrites() = runBlocking {
        val taskA = TaskRow("cross", "A任务", ownerId = "owner-a")
        repo.save(taskA, "owner-a")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.save(taskA, "owner-b") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.saveScheduled(taskA, null, owner = "owner-b") }
        }
        val error = runCatching { repo.complete(taskA, 4, "owner-b") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, db.dao().tasks("owner-a").size)
        assertTrue(db.dao().tasks("owner-b").isEmpty())
    }
}
