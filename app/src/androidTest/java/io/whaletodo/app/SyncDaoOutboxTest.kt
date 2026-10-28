package io.whaletodo.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.SyncOutboxRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncDaoOutboxTest {
    private lateinit var db: DeskDatabase
    private lateinit var dao: io.whaletodo.app.data.SyncDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, DeskDatabase::class.java)
            .addCallback(DeskDatabase.SYNC_OUTBOX_CALLBACK)
            .build()
        dao = db.syncDao()
        // Force Room to open and run the onOpen callback before assertions.
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun onOpenCreatesPartialUniqueHeadIndex() {
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_sync_outbox_head'",
        ).use {
            assertTrue(it.moveToFirst())
        }
    }

    @Test
    fun newHeadWithDifferentOpIdConflictsAndKeepsOp1() = runBlocking {
        dao.insertOutbox(row("op1"))
        var conflict: Throwable? = null
        try {
            dao.insertOrUpdateOutbox(row("op2"))
        } catch (e: Throwable) {
            conflict = e
        }
        assertTrue("expected unique constraint, got $conflict", conflict is SQLiteConstraintException)
        assertEquals("op1", dao.outbox("op1")?.opId)
        assertNull(dao.outbox("op2"))
    }

    @Test
    fun sameOpIdIsUpdatedWithoutInsertingDuplicate() = runBlocking {
        dao.insertOutbox(row("op1"))
        val updated = row("op1").copy(state = "inflight", attempts = 2)
        dao.insertOrUpdateOutbox(updated)
        assertEquals(updated, dao.outbox("op1"))
        assertEquals(1, dao.outboxByState("owner-a", "inflight").size)
    }

    @Test
    fun differentOwnerAndNonHeadSuccessorCanBeInserted() = runBlocking {
        dao.insertOutbox(row("op1", ownerId = "owner-a"))
        dao.insertOrUpdateOutbox(row("op2", ownerId = "owner-b"))
        dao.insertOrUpdateOutbox(row("op3", ownerId = "owner-a", isHead = false))
        assertEquals("op1", dao.head("owner-a", "task", "obj-1")?.opId)
        assertEquals("op2", dao.head("owner-b", "task", "obj-1")?.opId)
        assertEquals("op3", dao.outbox("op3")?.opId)
    }

    @Test
    fun failedTransactionLeavesNoHalfBatch() = runBlocking {
        var conflict: Throwable? = null
        try {
            db.withTransaction {
                dao.insertOutbox(row("tx1"))
                dao.insertOutbox(row("tx2"))
            }
        } catch (e: Throwable) {
            conflict = e
        }
        assertTrue("expected unique constraint, got $conflict", conflict is SQLiteConstraintException)
        assertNull(dao.outbox("tx1"))
        assertNull(dao.outbox("tx2"))
    }

    private fun row(
        opId: String,
        ownerId: String = "owner-a",
        isHead: Boolean = true,
    ) = SyncOutboxRow(
        opId = opId,
        ownerId = ownerId,
        objectType = "task",
        objectId = "obj-1",
        op = "create",
        payloadJson = "{}",
        baseVersion = null,
        idempotencyKey = "idem-$opId",
        parentOpId = null,
        isHead = isHead,
        batchId = null,
        state = "pending",
        attempts = 0,
        nextRetryAt = 0,
        createdAt = 1_000L,
        lastErrorCode = null,
    )
}
