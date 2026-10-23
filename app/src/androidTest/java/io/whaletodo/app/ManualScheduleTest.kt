package io.whaletodo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import io.whaletodo.core.Window
import java.time.*
import java.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualScheduleTest {
    private fun window(hours: Int, minutes: Long = 60): Window {
        val start = LocalDate.now().plusDays(1).atTime(hours, 0).atZone(ZoneId.systemDefault()).toInstant()
        return Window(start, start.plusSeconds(minutes * 60))
    }
    private fun verify(action: suspend (DeskDatabase, DeskRepository) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "manual-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, DeskDatabase::class.java, name).build()
        try { action(db, DeskRepository(db)) } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun task(id: String = "a") = TaskRow(id, "手动安排验证 $id", minutes = 120,
        deadline = window(20).end.toEpochMilli())
    @Test fun manualReservationKeepsIdentityAndTimeAcrossAutomaticPlans() = verify { db, repo ->
        val task = task(); val manual = window(14)
        repo.saveScheduled(task, manual)
        val original = db.dao().blocks().single()
        repeat(2) { repo.apply(repo.preview()) }
        assertEquals(original, db.dao().blocks().first { it.locked })
        assertEquals(120, db.dao().blocks().filter { it.taskId == task.id }.sumOf { ((it.end - it.start) / 60000).toInt() })
        assertTrue(db.dao().blocks().filter { !it.locked }.none { it.start < original.end && it.end > original.start })
    }
    @Test fun conflictIsAtomicAndTouchingManualWindowsAreAllowed() = verify { db, repo ->
        repo.saveScheduled(task("a"), window(14))
        val before = db.dao().blocks()
        var rejected = false
        try { repo.saveScheduled(task("b"), window(14)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertEquals(before, db.dao().blocks()); assertEquals(listOf("a"), db.dao().tasks().map { it.id })
        repo.saveScheduled(task("b"), window(15)); assertEquals(2, db.dao().blocks().size)
    }
    @Test fun editAndRemoveManualWorkAndDisplaceOverlappingFutureAutoOnly() = verify { db, repo ->
        val start = window(14).start.toEpochMilli()
        repo.save(task("b"))
        db.dao().insertBlocks(listOf(BlockRow("auto", "b", start, start + 3600000),
            BlockRow("past", "b", 1000, 61000)))
        repo.saveScheduled(task(), window(14)); assertFalse(db.dao().blocks().any { it.id == "auto" })
        assertTrue(db.dao().blocks().any { it.id == "past" })
        repo.saveScheduled(task(), window(16)); assertEquals(window(16).start.toEpochMilli(), db.dao().blocks().first { it.locked }.start)
        repo.saveScheduled(task(), null); assertEquals(listOf("past"), db.dao().blocks().map { it.id })
    }
    @Test fun invalidOrLateReservationDoesNotCreateTask() = verify { db, repo ->
        val task = task().copy(deadline = window(13).end.toEpochMilli())
        try { repo.saveScheduled(task, window(14)); fail("late reservation accepted") } catch (_: IllegalArgumentException) { }
        try { repo.saveScheduled(task(), Window(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60))); fail("past accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().tasks().isEmpty()); assertTrue(db.dao().blocks().isEmpty())
    }
    @Test fun zoneChangeInvalidatesProposalWithoutChangingStoredInstant() = verify { db, repo ->
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            repo.saveScheduled(task(), window(14)); val rows = db.dao().blocks(); val preview = repo.preview()
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            try { repo.apply(preview); fail("stale zone accepted") } catch (_: IllegalStateException) { }
            assertEquals(rows, db.dao().blocks())
        } finally { TimeZone.setDefault(original) }
    }
}
