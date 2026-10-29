package io.whaletodo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.SyncOutboxRow
import io.whaletodo.app.data.TaskRow
import io.whaletodo.app.sync.SyncPushEngine
import io.whaletodo.network.AccountProfile
import io.whaletodo.network.ApiException
import io.whaletodo.network.Entitlements
import io.whaletodo.network.SyncPushApi
import io.whaletodo.network.SyncPushItem
import io.whaletodo.network.SyncPushResponse
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncPushEngineTest {
    private lateinit var db: DeskDatabase
    private lateinit var session: AccountSession
    private var credentialSession = "s1"
    private val owner = "owner-a"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, DeskDatabase::class.java).build()
        session = AccountSession { credentialSession }
        check(session.begin("s1"))
        check(
            session.update(
                "s1",
                "a@example.test",
                AccountProfile(owner, "user-a", null),
                Entitlements(false, null),
                0,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun wrongBatchReceiptMustStayRecoverable() = runBlocking {
        val task = seedTask("task-wrong-batch")
        var wrong = true
        val api = FakeApi { body ->
            if (wrong) {
                wrong = false
                response(body, "applied", items(body, "applied"))
                    .copy(batchId = "00000000-0000-4000-8000-000000000000")
            } else {
                success(body)
            }
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        assertEquals("unknown", first.status)
        assertEquals("unknown", db.syncDao().batch(first.batchId!!)!!.state)
        assertEquals("inflight", db.syncDao().outbox("op-task-wrong-batch")!!.state)
        assertEquals(0, db.dao().task(owner, task.id)!!.serverVersion)

        val second = engine.pushOnce(owner, "device-1")
        assertEquals("applied", second.status)
        assertEquals(first.batchId, second.batchId)
        assertEquals(api.bodies[0], api.bodies[1])
        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)
    }

    @Test
    fun appliedUpdatesContentOutboxAndBatch() = runBlocking {
        val task = seedTask("task-1")
        val api = FakeApi { body -> success(body) }
        val outcome = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")

        assertEquals("applied", outcome.status)
        assertNotNull(outcome.batchId)
        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("done", db.syncDao().outbox("op-task-1")!!.state)
        assertEquals("applied", db.syncDao().batch(outcome.batchId!!)!!.state)
    }

    @Test
    fun rejectedMarksOutboxFailedAndDoesNotRetry() = runBlocking {
        seedTask("task-2")
        val api = FakeApi { body ->
            response(body, "rejected", items(body, "rejected"))
        }
        val engine = SyncPushEngine(db, session, api)
        val outcome = engine.pushOnce(owner, "device-1")

        assertEquals("rejected", outcome.status)
        assertEquals("failed", db.syncDao().outbox("op-task-2")!!.state)
        assertEquals("empty", engine.pushOnce(owner, "device-1").status)
        assertEquals(1, api.calls)
    }

    @Test
    fun conflictWritesConflictWithoutTouchingContent() = runBlocking {
        val task = seedTask("task-3")
        val api = FakeApi { body ->
            response(
                body,
                "rejected",
                listOf(
                    SyncPushItem(
                        "task",
                        task.id,
                        "create",
                        "conflict",
                        7,
                        "already_exists",
                    ),
                ),
            )
        }
        val outcome = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")

        assertEquals("rejected", outcome.status)
        assertEquals(0, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("conflict", db.syncDao().outbox("op-task-3")!!.state)
        val conflict = db.syncDao().conflict(owner, "task", task.id)!!
        assertEquals(7, conflict.remoteKnownVersion)
        assertFalse(conflict.remoteDetailFetched)
        assertNull(conflict.remotePayloadJson)
    }

    @Test
    fun fiveHundredRetriesTheSameStoredBodyAndBatch() = runBlocking {
        seedTask("task-4")
        var fail = true
        val api = FakeApi { body ->
            if (fail) {
                fail = false
                throw ApiException("UPSTREAM_UNAVAILABLE", "synthetic", 503, null)
            }
            response(body, "applied", items(body, "applied"))
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        assertEquals("unknown", first.status)
        assertEquals("unknown", db.syncDao().batch(first.batchId!!)!!.state)

        val second = engine.pushOnce(owner, "device-1")
        assertEquals("applied", second.status)
        assertEquals(first.batchId, second.batchId)
        assertEquals(1, db.syncDao().batches(owner, "applied").size)
        assertEquals(2, api.bodies.size)
        assertEquals(api.bodies[0], api.bodies[1])
    }

    @Test
    fun staleFailureAfterNewAttemptSuccessDoesNotRegressTerminalBatch() = runBlocking {
        val task = seedTask("task-stale-failure")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : SyncPushApi {
            override suspend fun push(sessionId: String, bodyJson: String): SyncPushResponse {
                calls++
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    throw IOException("late first attempt")
                }
                return success(bodyJson)
            }
        }
        val first = async { SyncPushEngine(db, session, api).pushOnce(owner, "device-1") }
        firstStarted.await()
        val second = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")
        assertEquals("applied", second.status)
        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)

        releaseFirst.complete(Unit)
        val late = first.await()
        assertEquals("unknown", late.status)
        assertEquals("applied", db.syncDao().batch(second.batchId!!)!!.state)
        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("done", db.syncDao().outbox("op-task-stale-failure")!!.state)
    }

    @Test
    fun staleReceiptAfterNewAttemptSuccessDoesNotReapply() = runBlocking {
        val task = seedTask("task-stale-receipt")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : SyncPushApi {
            override suspend fun push(sessionId: String, bodyJson: String): SyncPushResponse {
                calls++
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                return success(bodyJson)
            }
        }
        val first = async { SyncPushEngine(db, session, api).pushOnce(owner, "device-1") }
        firstStarted.await()
        val second = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")
        assertEquals("applied", second.status)
        releaseFirst.complete(Unit)
        assertEquals("unknown", first.await().status)

        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("done", db.syncDao().outbox("op-task-stale-receipt")!!.state)
        assertEquals(1, db.syncDao().batches(owner, "applied").size)
    }

    @Test
    fun appliedWithNoItemsStaysRecoverable() = runBlocking {
        val task = seedTask("task-no-items")
        var fail = true
        val api = FakeApi { body ->
            if (fail) {
                fail = false
                response(body, "applied", emptyList())
            } else {
                success(body)
            }
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        assertEquals("unknown", first.status)
        assertEquals("unknown", db.syncDao().batch(first.batchId!!)!!.state)
        assertEquals("inflight", db.syncDao().outbox("op-task-no-items")!!.state)
        assertEquals(0, db.dao().task(owner, task.id)!!.serverVersion)

        val second = engine.pushOnce(owner, "device-1")
        assertEquals("applied", second.status)
        assertEquals(first.batchId, second.batchId)
        assertEquals(1, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("done", db.syncDao().outbox("op-task-no-items")!!.state)
    }

    @Test
    fun missingAndUnknownItemsAreMalformedWithoutApplied() = runBlocking {
        seedTask("task-a")
        seedTask("task-b")
        val malformed = FakeApi { body -> response(body, "applied", items(body, "applied").dropLast(1)) }
        val outcome = SyncPushEngine(db, session, malformed).pushOnce(owner, "device-1")
        assertEquals("unknown", outcome.status)
        assertEquals("unknown", db.syncDao().batch(outcome.batchId!!)!!.state)
        assertEquals("inflight", db.syncDao().outbox("op-task-a")!!.state)
        assertEquals(0, db.dao().task(owner, "task-a")!!.serverVersion)
    }

    @Test
    fun duplicateItemsAreMalformedWithoutApplied() = runBlocking {
        seedTask("task-dup")
        val malformed = FakeApi { body ->
            val item = items(body, "applied").single()
            response(body, "applied", listOf(item, item))
        }
        val outcome = SyncPushEngine(db, session, malformed).pushOnce(owner, "device-1")
        assertEquals("unknown", outcome.status)
        assertEquals("inflight", db.syncDao().outbox("op-task-dup")!!.state)
        assertEquals(0, db.dao().task(owner, "task-dup")!!.serverVersion)
    }

    @Test
    fun unknownResultIsMalformedWithoutApplied() = runBlocking {
        seedTask("task-bad-result")
        val malformed = FakeApi { body ->
            response(body, "applied", items(body, "weird"))
        }
        val outcome = SyncPushEngine(db, session, malformed).pushOnce(owner, "device-1")
        assertEquals("unknown", outcome.status)
        assertEquals(0, db.dao().task(owner, "task-bad-result")!!.serverVersion)
        assertEquals("inflight", db.syncDao().outbox("op-task-bad-result")!!.state)
    }

    @Test
    fun missingServerVersionIsMalformedWithoutApplied() = runBlocking {
        seedTask("task-bad-version")
        val malformed = FakeApi { body ->
            val item = items(body, "applied").single().copy(serverVersion = null)
            response(body, "applied", listOf(item))
        }
        val outcome = SyncPushEngine(db, session, malformed).pushOnce(owner, "device-1")
        assertEquals("unknown", outcome.status)
        assertEquals(0, db.dao().task(owner, "task-bad-version")!!.serverVersion)
        assertEquals("inflight", db.syncDao().outbox("op-task-bad-version")!!.state)
    }

    @Test
    fun unknownObjectIdIsMalformedWithoutApplied() = runBlocking {
        seedTask("task-unknown-id")
        val malformed = FakeApi { body ->
            val item = items(body, "applied").single().copy(objectId = "unknown-object")
            response(body, "applied", listOf(item))
        }
        val outcome = SyncPushEngine(db, session, malformed).pushOnce(owner, "device-1")
        assertEquals("unknown", outcome.status)
        assertEquals(0, db.dao().task(owner, "task-unknown-id")!!.serverVersion)
        assertEquals("inflight", db.syncDao().outbox("op-task-unknown-id")!!.state)
    }

    @Test
    fun replayedResponseAppliesOriginalItemsWithoutNewBatch() = runBlocking {
        seedTask("task-5")
        var fail = true
        val api = FakeApi { body ->
            if (fail) {
                fail = false
                throw ApiException("UPSTREAM_UNAVAILABLE", "synthetic", 503, null)
            }
            response(body, "applied", items(body, "applied"), replayed = true)
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        val second = engine.pushOnce(owner, "device-1")

        assertEquals(first.batchId, second.batchId)
        assertTrue(second.applied > 0)
        assertEquals(1, db.syncDao().batches(owner, "applied").size)
        assertEquals("done", db.syncDao().outbox("op-task-5")!!.state)
    }

    @Test
    fun batchConflict409MarksInvalidAndLeavesNoNewBatch() = runBlocking {
        seedTask("task-6")
        val api = FakeApi {
            throw ApiException("CONFLICT", "body changed", 409, null)
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        assertEquals("invalid", first.status)
        assertEquals("invalid", db.syncDao().batch(first.batchId!!)!!.state)
        assertEquals("failed", db.syncDao().outbox("op-task-6")!!.state)
        assertEquals("empty", engine.pushOnce(owner, "device-1").status)
    }

    @Test
    fun ownerMismatchBeforeSendDoesNotCallApiOrCreateBatch() = runBlocking {
        seedTask("task-owner-mismatch")
        credentialSession = "s2"
        check(session.clear("s1"))
        check(session.begin("s2"))
        check(
            session.update(
                "s2",
                "b@example.test",
                AccountProfile("owner-b", "user-b", null),
                Entitlements(false, null),
                0,
            ),
        )
        val api = FakeApi { body -> success(body) }
        val outcome = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")

        assertEquals("session_wait", outcome.status)
        assertEquals(0, api.calls)
        assertTrue(db.syncDao().batches(owner, "sending").isEmpty())
    }

    @Test
    fun ownerSwitchAfterResponseDiscardsContentUpdate() = runBlocking {
        val task = seedTask("task-7")
        val api = FakeApi { body ->
            session.clear("s1")
            response(body, "applied", items(body, "applied"))
        }
        val outcome = SyncPushEngine(db, session, api).pushOnce(owner, "device-1")

        assertEquals("session_wait", outcome.status)
        assertEquals(0, db.dao().task(owner, task.id)!!.serverVersion)
        assertEquals("inflight", db.syncDao().outbox("op-task-7")!!.state)
        assertEquals("session_wait", db.syncDao().batch(outcome.batchId!!)!!.state)
    }

    @Test
    fun moreThanTwentyHeadsProducesTwentyThenRemainder() = runBlocking {
        repeat(21) { index ->
            seedTask("task-batch-$index", createdAt = index.toLong())
        }
        val api = FakeApi { body ->
            response(body, "applied", items(body, "applied"))
        }
        val engine = SyncPushEngine(db, session, api)
        val first = engine.pushOnce(owner, "device-1")
        val second = engine.pushOnce(owner, "device-1")

        assertEquals("applied", first.status)
        assertEquals("applied", second.status)
        assertEquals(2, db.syncDao().batches(owner, "applied").size)
        assertEquals(20, api.bodyObjectCounts[0])
        assertEquals(1, api.bodyObjectCounts[1])
    }

    private suspend fun seedTask(
        id: String,
        createdAt: Long = 1,
    ): TaskRow {
        val task = TaskRow(id, "任务-$id", ownerId = owner)
        db.dao().save(task)
        db.syncDao().insertOutbox(
            SyncOutboxRow(
                opId = "op-$id",
                ownerId = owner,
                objectType = "task",
                objectId = id,
                op = "create",
                payloadJson = "{}",
                baseVersion = null,
                idempotencyKey = "op-$id",
                parentOpId = null,
                isHead = true,
                batchId = null,
                state = "pending",
                attempts = 0,
                nextRetryAt = 0,
                createdAt = createdAt,
                lastErrorCode = null,
            ),
        )
        return task
    }

    private fun success(body: String): SyncPushResponse =
        response(body, "applied", items(body, "applied"))

    private fun response(
        body: String,
        status: String,
        items: List<SyncPushItem>,
        replayed: Boolean = false,
    ) = SyncPushResponse(batchIdOf(body), status, replayed, items)

    private fun items(body: String, result: String): List<SyncPushItem> =
        MUTATION.findAll(body).map { match ->
            SyncPushItem(
                objectType = match.groupValues[1],
                objectId = match.groupValues[2],
                op = match.groupValues[3],
                result = result,
                serverVersion = 1,
                reason = null,
            )
        }.toList()

    private fun batchIdOf(body: String): String =
        Regex("\"batch_id\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private class FakeApi(
        private val handler: suspend (String) -> SyncPushResponse,
    ) : SyncPushApi {
        var calls = 0
        val bodies = mutableListOf<String>()
        val bodyObjectCounts = mutableListOf<Int>()

        override suspend fun push(sessionId: String, bodyJson: String): SyncPushResponse {
            calls++
            bodies += bodyJson
            bodyObjectCounts += Regex("\"object_type\"").findAll(bodyJson).count()
            return handler(bodyJson)
        }
    }

    private companion object {
        val MUTATION = Regex(
            "\"object_type\":\"([^\"]+)\",\"object_id\":\"([^\"]+)\",\"op\":\"([^\"]+)\"",
        )
    }
}
