package io.whaletodo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test
    fun recordsAndRatingSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "verify-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, DeskDatabase::class.java, name).build()
        try {
            val repo = DeskRepository(db)
            repo.capture("持久化验证")
            val task = db.dao().tasks().single()
            repo.complete(task, 4)
            repo.complete(task, 4)
            assertEquals(1, db.dao().observeCheckIns().first().size)
            db.close()
            db = Room.databaseBuilder(context, DeskDatabase::class.java, name).build()
            assertTrue(db.dao().tasks().single().done)
            assertEquals(4, db.dao().observeCheckIns().first().single().rating)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun changedRecordsInvalidatePreviewWithoutReplacingPlan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "verify-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, DeskDatabase::class.java, name).build()
        try {
            val repo = DeskRepository(db)
            repo.capture("排程事务验证")
            val task = db.dao().tasks().single()
            repo.save(task.copy(minutes = 60, deadline = System.currentTimeMillis() + 172800000))
            val p = repo.preview()
            repo.settings(8, 20)
            var rejected = false
            try {
                repo.apply(p)
            } catch (e: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(db.dao().blocks().isEmpty())
            val valid = repo.preview()
            repo.apply(valid)
            assertFalse(db.dao().blocks().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
