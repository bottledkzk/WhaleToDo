package io.whaletodo.app.sync

import androidx.room.withTransaction
import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.data.DailyWorkTimeRow
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.SyncBatchRow
import io.whaletodo.app.data.SyncConflictRow
import io.whaletodo.app.data.SyncOutboxRow
import io.whaletodo.app.data.SyncStateRow
import io.whaletodo.app.data.SyncTombstoneRow
import io.whaletodo.network.ApiException
import io.whaletodo.network.SyncPushApi
import io.whaletodo.network.SyncPushItem
import io.whaletodo.network.SyncPushResponse
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PushOutcome(
    val status: String,
    val batchId: String? = null,
    val applied: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val error: String? = null,
)

/**
 * First push block: one batch per call, no worker/loop, no pull/cursor.
 * A batch body is persisted before HTTP and never reserialized on retry.
 */
class SyncPushEngine(
    private val db: DeskDatabase,
    private val session: AccountSession,
    private val api: SyncPushApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.syncDao()
    private val content = db.dao()

    suspend fun pushOnce(ownerId: String, deviceId: String): PushOutcome {
        val sessionId = currentSessionFor(ownerId)
            ?: return PushOutcome("session_wait", error = "SESSION_CHANGED")

        // A previous batch must be finished before selecting new pending heads.
        dao.batches(ownerId, "session_wait").firstOrNull()?.let {
            return PushOutcome("session_wait", it.batchId, error = it.lastErrorCode ?: "SESSION_WAIT")
        }
        val retry = RETRY_STATES
            .flatMap { state -> dao.batches(ownerId, state) }
            .minByOrNull { it.createdAt }
        if (retry != null) {
            val attempt = retry.attemptSessionId ?: retry.createdSessionId
            if (attempt != sessionId) {
                markBatch(retry.batchId, "session_wait", "SESSION_CHANGED")
                return PushOutcome("session_wait", retry.batchId, error = "SESSION_CHANGED")
            }
            if (currentSessionFor(ownerId) != sessionId) {
                markBatch(retry.batchId, "session_wait", "SESSION_CHANGED")
                return PushOutcome("session_wait", retry.batchId, error = "SESSION_CHANGED")
            }
            db.withTransaction {
                dao.updateBatchState(ownerId, retry.batchId, "sending", null)
                dao.recordBatchAttempt(ownerId, retry.batchId, sessionId, clock())
            }
            return send(ownerId, deviceId, retry.batchId, sessionId, retry.bodyJson)
        }

        val built = db.withTransaction {
            val rows = dao.pendingHeadsForPush(ownerId, SyncPushBatchBuilder.MAX_MUTATIONS)
            if (rows.isEmpty()) return@withTransaction null
            val batchId = UUID.randomUUID().toString()
            val built = SyncPushBatchBuilder.build(ownerId, deviceId, batchId, rows)
            dao.upsertBatch(
                SyncBatchRow(
                    batchId = batchId,
                    ownerId = ownerId,
                    createdSessionId = sessionId,
                    attemptSessionId = sessionId,
                    deviceId = deviceId,
                    bodyJson = built.bodyJson,
                    bodySha256 = built.bodySha256,
                    createdAt = clock(),
                    state = "sending",
                    attempts = 1,
                    lastAttemptAt = clock(),
                ),
            )
            built.rows.forEach { row ->
                dao.updateOutbox(
                    row.copy(
                        batchId = batchId,
                        state = "inflight",
                        attempts = row.attempts + 1,
                        lastErrorCode = null,
                    ),
                )
            }
            built
        } ?: return PushOutcome("empty")

        if (currentSessionFor(ownerId) != sessionId) {
            markBatch(built.batchId, "session_wait", "SESSION_CHANGED")
            return PushOutcome("session_wait", built.batchId, error = "SESSION_CHANGED")
        }
        return send(ownerId, deviceId, built.batchId, sessionId, built.bodyJson)
    }

    private suspend fun send(
        ownerId: String,
        deviceId: String,
        batchId: String,
        sessionId: String,
        bodyJson: String,
    ): PushOutcome {
        if (currentSessionFor(ownerId) != sessionId) {
            markBatch(batchId, "session_wait", "SESSION_CHANGED")
            return PushOutcome("session_wait", batchId, error = "SESSION_CHANGED")
        }
        val response = try {
            withContext(Dispatchers.IO) { api.push(sessionId, bodyJson) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return handleApiError(ownerId, batchId, sessionId, e)
        } catch (_: IOException) {
            markUnknown(ownerId, batchId, sessionId, "NETWORK_ERROR")
            return PushOutcome("unknown", batchId, error = "NETWORK_ERROR")
        } catch (_: Exception) {
            markUnknown(ownerId, batchId, sessionId, "UNKNOWN_ERROR")
            return PushOutcome("unknown", batchId, error = "UNKNOWN_ERROR")
        }

        if (currentSessionFor(ownerId) != sessionId) {
            markBatch(batchId, "session_wait", "SESSION_CHANGED")
            return PushOutcome("session_wait", batchId, error = "SESSION_CHANGED")
        }
        if (response.batchId != batchId) {
            markBatch(batchId, "invalid", "BATCH_ID_MISMATCH")
            return PushOutcome("invalid", batchId, error = "BATCH_ID_MISMATCH")
        }
        return applyResponse(ownerId, deviceId, batchId, response)
    }

    private suspend fun handleApiError(
        ownerId: String,
        batchId: String,
        sessionId: String,
        error: ApiException,
    ): PushOutcome {
        when {
            error.status == 401 -> {
                markBatch(batchId, "session_wait", "AUTH_401")
                return PushOutcome("session_wait", batchId, error = "AUTH_401")
            }
            error.status == 409 -> {
                markInvalidBatch(ownerId, batchId, "BATCH_CONFLICT_409")
                return PushOutcome("invalid", batchId, error = "BATCH_CONFLICT_409")
            }
            error.status == 403 -> {
                markBatch(batchId, "invalid", "FORBIDDEN_403")
                return PushOutcome("invalid", batchId, error = "FORBIDDEN_403")
            }
            error.status == 429 -> {
                markRetryable(ownerId, batchId, sessionId, "RATE_LIMITED_429")
                return PushOutcome("retryable", batchId, error = "RATE_LIMITED_429")
            }
            error.status in 500..599 -> {
                markUnknown(ownerId, batchId, sessionId, "SERVER_5XX")
                return PushOutcome("unknown", batchId, error = "SERVER_5XX")
            }
            error.status in 400..499 -> {
                markInvalidBatch(ownerId, batchId, "HTTP_${error.status}")
                return PushOutcome("invalid", batchId, error = "HTTP_${error.status}")
            }
            else -> {
                markUnknown(ownerId, batchId, sessionId, error.code)
                return PushOutcome("unknown", batchId, error = error.code)
            }
        }
    }

    private suspend fun applyResponse(
        ownerId: String,
        deviceId: String,
        batchId: String,
        response: SyncPushResponse,
    ): PushOutcome = db.withTransaction {
        val batch = dao.batch(batchId)
            ?: return@withTransaction PushOutcome("invalid", batchId, error = "BATCH_MISSING")
        if (batch.ownerId != ownerId) {
            return@withTransaction PushOutcome("invalid", batchId, error = "OWNER_MISMATCH")
        }
        val rows = dao.outboxForBatch(ownerId, batchId)
        val byKey = response.items.associateBy { Triple(it.objectType, it.objectId, it.op) }
        var applied = 0
        var conflicts = 0
        var rejected = 0
        val now = clock()

        for (row in rows) {
            val item = byKey[Triple(row.objectType, row.objectId, row.op)] ?: continue
            when (item.result) {
                "applied" -> {
                    if (row.op == "delete") {
                        applyAppliedDelete(ownerId, row, item, now)
                    } else {
                        applyAppliedContent(ownerId, row.objectType, row.objectId, item, row.payloadJson, now)
                    }
                    dao.updateOutbox(row.copy(state = "done", lastErrorCode = null))
                    applied++
                }
                "conflict" -> {
                    dao.updateOutbox(
                        row.copy(
                            state = "conflict",
                            lastErrorCode = item.reason ?: "CONFLICT",
                        ),
                    )
                    dao.upsertConflict(
                        SyncConflictRow(
                            ownerId = ownerId,
                            objectType = row.objectType,
                            objectId = row.objectId,
                            localPayloadJson = row.payloadJson,
                            localBaseVersion = row.baseVersion,
                            remoteKnownVersion = item.serverVersion,
                            remoteDetailFetched = false,
                            remotePayloadJson = null,
                            remoteDeleted = null,
                            reason = item.reason ?: "conflict",
                            createdAt = now,
                            state = "open",
                        ),
                    )
                    conflicts++
                }
                "rejected" -> {
                    dao.updateOutbox(
                        row.copy(
                            state = "failed",
                            lastErrorCode = item.reason ?: "REJECTED",
                        ),
                    )
                    rejected++
                }
            }
        }

        val status = response.status.takeIf { it in BATCH_STATUSES } ?: "unknown"
        dao.updateBatchState(ownerId, batchId, status, null)
        if (applied > 0) {
            val state = dao.state(ownerId)
            dao.upsertState(
                SyncStateRow(
                    ownerId = ownerId,
                    cursor = state?.cursor ?: 0,
                    lastPushAt = now,
                    lastPullAt = state?.lastPullAt,
                    deviceId = state?.deviceId ?: deviceId,
                ),
            )
        }
        PushOutcome(
            status = status,
            batchId = batchId,
            applied = applied,
            conflicts = conflicts,
            rejected = rejected,
        )
    }

    private suspend fun applyAppliedContent(
        ownerId: String,
        objectType: String,
        objectId: String,
        item: SyncPushItem,
        payloadJson: String?,
        now: Long,
    ) {
        val version = item.serverVersion
        when (objectType) {
            "task" -> content.task(ownerId, objectId)?.let {
                content.save(it.copy(serverVersion = version ?: it.serverVersion, updatedAt = now))
            }
            "block" -> content.block(ownerId, objectId)?.let {
                content.save(it.copy(serverVersion = version ?: it.serverVersion, updatedAt = now))
            }
            "checkin" -> content.checkIn(ownerId, objectId)?.let {
                content.save(it.copy(serverVersion = version ?: it.serverVersion, updatedAt = now))
            }
            "settings" -> content.settings(ownerId)?.let {
                content.save(it.copy(serverVersion = version ?: it.serverVersion, updatedAt = now))
            }
            "availability" -> {
                val date = payloadDate(payloadJson) ?: return
                content.workTime(ownerId, date)?.let {
                    content.save(
                        DailyWorkTimeRow(
                            date = it.date,
                            startMinute = it.startMinute,
                            endMinute = it.endMinute,
                            ownerId = ownerId,
                            clientId = it.clientId,
                            serverVersion = version ?: it.serverVersion,
                            updatedAt = now,
                            timeZone = it.timeZone,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun applyAppliedDelete(
        ownerId: String,
        row: SyncOutboxRow,
        item: SyncPushItem,
        now: Long,
    ) {
        when (row.objectType) {
            "task" -> content.deleteTask(ownerId, row.objectId)
            "block" -> content.deleteBlock(ownerId, row.objectId)
            "checkin" -> content.deleteCheckIn(ownerId, row.objectId)
            "settings" -> content.deleteSettings(ownerId)
            "availability" -> payloadDate(row.payloadJson)?.let { content.deleteWorkTime(ownerId, it) }
        }
        dao.upsertTombstone(
            SyncTombstoneRow(
                ownerId = ownerId,
                objectType = row.objectType,
                objectId = row.objectId,
                deletedAt = now,
                version = item.serverVersion ?: 0,
            ),
        )
    }

    private suspend fun markBatch(batchId: String, state: String, error: String?) {
        db.withTransaction {
            val batch = dao.batch(batchId) ?: return@withTransaction
            dao.updateBatchState(batch.ownerId, batchId, state, error)
        }
    }

    private suspend fun markUnknown(ownerId: String, batchId: String, sessionId: String, error: String) {
        db.withTransaction {
            dao.updateBatchState(ownerId, batchId, "unknown", error)
            dao.recordBatchAttempt(ownerId, batchId, sessionId, clock())
        }
    }

    private suspend fun markRetryable(ownerId: String, batchId: String, sessionId: String, error: String) {
        db.withTransaction {
            dao.updateBatchState(ownerId, batchId, "retryable", error)
            dao.recordBatchAttempt(ownerId, batchId, sessionId, clock())
        }
    }

    private suspend fun markInvalidBatch(ownerId: String, batchId: String, error: String) {
        db.withTransaction {
            dao.outboxForBatch(ownerId, batchId).forEach {
                dao.updateOutbox(it.copy(state = "failed", lastErrorCode = error))
            }
            dao.updateBatchState(ownerId, batchId, "invalid", error)
        }
    }

    private fun currentSessionFor(ownerId: String): String? {
        if (session.access.value.userId != ownerId) return null
        return session.currentSessionId()
    }

    private fun payloadDate(payloadJson: String?): String? =
        payloadJson?.let { PAYLOAD_DATE.find(it)?.groupValues?.getOrNull(1) }

    private companion object {
        val RETRY_STATES = listOf("sending", "retryable", "unknown")
        val BATCH_STATUSES = setOf("applied", "partial", "rejected")
        val PAYLOAD_DATE = Regex("\"date\"\\s*:\\s*\"([^\"]+)\"")
    }
}
