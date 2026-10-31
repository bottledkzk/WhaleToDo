package io.whaletodo.app.sync

import androidx.room.withTransaction
import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.data.BlockRow
import io.whaletodo.app.data.CheckInRow
import io.whaletodo.app.data.DailyWorkTimeRow
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.SettingRow
import io.whaletodo.app.data.SyncConflictRow
import io.whaletodo.app.data.SyncIdMappingRow
import io.whaletodo.app.data.SyncStateRow
import io.whaletodo.app.data.SyncTombstoneRow
import io.whaletodo.app.data.TaskRow
import io.whaletodo.network.ApiException
import io.whaletodo.network.SyncPullApi
import io.whaletodo.network.SyncPullChange
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PullOutcome(
    val status: String,
    val cursor: Long? = null,
    val applied: Int = 0,
    val conflicts: Int = 0,
    val deleted: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
)

internal class SyncPullProtocolException(message: String) : Exception(message)

private data class AvailabilityIdentity(
    val localObjectId: String,
    val date: String?,
)

/**
 * First pull block: one page per call, no worker/loop, no automatic push.
 * A page, its cursor and lastPullAt are committed in one Room transaction.
 */
class SyncPullEngine(
    private val db: DeskDatabase,
    private val session: AccountSession,
    private val api: SyncPullApi,
    private val limit: Int = DEFAULT_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.syncDao()
    private val content = db.dao()
    private val ownerLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun pullOnce(ownerId: String, deviceId: String): PullOutcome =
        ownerLocks.getOrPut(ownerId) { Mutex() }.withLock {
            pullOnceLocked(ownerId, deviceId)
        }

    private suspend fun pullOnceLocked(ownerId: String, deviceId: String): PullOutcome {
        val sessionId = currentSessionFor(ownerId)
            ?: return PullOutcome("session_wait", error = "SESSION_CHANGED")
        val state = dao.state(ownerId)
        val cursor = state?.cursor ?: 0L

        val page = try {
            withContext(Dispatchers.IO) {
                api.pull(sessionId, cursor, limit.coerceIn(1, MAX_LIMIT))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return when (e.status) {
                401 -> PullOutcome("session_wait", error = "AUTH_401")
                403 -> PullOutcome("forbidden", cursor, error = "FORBIDDEN_403")
                200 -> PullOutcome("invalid", cursor, error = "MALFORMED_RESPONSE")
                else -> PullOutcome("error", cursor, error = "HTTP_${e.status}")
            }
        } catch (_: IOException) {
            return PullOutcome("error", cursor, error = "NETWORK_ERROR")
        } catch (_: Exception) {
            return PullOutcome("error", cursor, error = "UNKNOWN_ERROR")
        }

        if (currentSessionFor(ownerId) != sessionId) {
            return PullOutcome("session_wait", cursor, error = "SESSION_CHANGED")
        }
        if (page.nextCursor < cursor) {
            return PullOutcome("invalid", cursor, error = "CURSOR_REGRESSION")
        }
        if (page.changes.isNotEmpty() && page.nextCursor == cursor) {
            return PullOutcome("invalid", cursor, error = "CURSOR_NOT_ADVANCING")
        }
        if (page.hasMore && page.nextCursor == cursor) {
            return PullOutcome("invalid", cursor, error = "CURSOR_NOT_ADVANCING")
        }

        return try {
            db.withTransaction {
                if (currentSessionFor(ownerId) != sessionId) {
                    return@withTransaction PullOutcome("session_wait", cursor, error = "SESSION_CHANGED")
                }
                var applied = 0
                var conflicts = 0
                var deleted = 0
                val now = clock()
                for (change in page.changes) {
                    when (applyChange(ownerId, change, now)) {
                        ApplyResult.APPLIED -> applied++
                        ApplyResult.DELETED -> deleted++
                        ApplyResult.CONFLICT -> conflicts++
                        ApplyResult.SKIPPED -> Unit
                    }
                }
                dao.upsertState(
                    SyncStateRow(
                        ownerId = ownerId,
                        cursor = page.nextCursor,
                        lastPushAt = state?.lastPushAt,
                        lastPullAt = now,
                        deviceId = state?.deviceId ?: deviceId,
                    ),
                )
                PullOutcome(
                    status = "ok",
                    cursor = page.nextCursor,
                    applied = applied,
                    conflicts = conflicts,
                    deleted = deleted,
                    hasMore = page.hasMore,
                )
            }
        } catch (e: SyncPullProtocolException) {
            PullOutcome("invalid", cursor, error = e.message ?: "MALFORMED_CHANGE")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PullOutcome("error", cursor, error = "APPLY_FAILED")
        }
    }

    private suspend fun applyChange(ownerId: String, change: SyncPullChange, now: Long): ApplyResult {
        val type = change.objectType
        if (type !in OBJECT_TYPES) throw SyncPullProtocolException("UNKNOWN_OBJECT_TYPE")
        val payload = if (change.deleted) null else parsePayload(change)
        val identity = if (type == "availability") {
            resolveAvailabilityIdentity(ownerId, change.objectId, payload)
        } else {
            null
        }
        val localObjectId = identity?.localObjectId ?: change.objectId
        // Persist the remote→local translation for conflicts, skipped versions and deletes.
        if (identity != null && identity.date != null && change.objectId != localObjectId) {
            dao.upsertMapping(
                SyncIdMappingRow(
                    ownerId = ownerId,
                    objectType = "availability",
                    guestObjectId = change.objectId,
                    accountObjectId = localObjectId,
                    naturalKey = identity.date,
                    createdAt = now,
                ),
            )
        }
        val known = localKnownVersion(ownerId, change, payload, localObjectId, identity)
        if (change.version <= known) return ApplyResult.SKIPPED

        // A remote availability object_id may differ from the canonical local identity.
        // Keep the translation even when this version is applied or conflicts.
        val head = dao.head(ownerId, type, localObjectId)?.takeIf { it.state != "done" }
        val localConflict = dao.conflict(ownerId, type, localObjectId)
        val remoteConflict = change.objectId.takeIf { it != localObjectId }
            ?.let { dao.conflict(ownerId, type, it) }
        val existing = localConflict ?: remoteConflict
        if (head != null || existing != null) {
            val conflictId = localObjectId
            dao.upsertConflict(
                SyncConflictRow(
                    ownerId = ownerId,
                    objectType = type,
                    objectId = conflictId,
                    localPayloadJson = head?.payloadJson ?: existing?.localPayloadJson,
                    localBaseVersion = head?.baseVersion ?: existing?.localBaseVersion,
                    remoteKnownVersion = change.version,
                    remoteDetailFetched = true,
                    remotePayloadJson = if (change.deleted) null else change.payloadJson,
                    remoteDeleted = if (change.deleted) 1 else 0,
                    reason = if (change.deleted) "pull_remote_deleted" else "pull_remote_changed",
                    createdAt = existing?.createdAt ?: now,
                    state = existing?.state ?: "open",
                ),
            )
            // Never delete the local identity row we just wrote; only migrate an old
            // remote-keyed conflict to the local identity.
            if (remoteConflict != null) dao.deleteConflict(remoteConflict)
            return ApplyResult.CONFLICT
        }

        if (change.deleted) {
            deleteContent(ownerId, type, localObjectId, payload)
            dao.upsertTombstone(
                SyncTombstoneRow(
                    ownerId = ownerId,
                    objectType = type,
                    objectId = localObjectId,
                    deletedAt = now,
                    version = change.version,
                ),
            )
            return ApplyResult.DELETED
        }
        applyContent(ownerId, type, localObjectId, payload!!, change.version, now)
        return ApplyResult.APPLIED
    }

    private suspend fun existingConflict(
        ownerId: String,
        type: String,
        localObjectId: String,
        remoteObjectId: String,
    ): SyncConflictRow? =
        dao.conflict(ownerId, type, localObjectId)
            ?: remoteObjectId.takeIf { it != localObjectId }
                ?.let { dao.conflict(ownerId, type, it) }

    private suspend fun localKnownVersion(
        ownerId: String,
        change: SyncPullChange,
        payload: JSONObject?,
        localObjectId: String,
        identity: AvailabilityIdentity?,
    ): Int {
        val contentVersion = when (change.objectType) {
            "task" -> content.task(ownerId, localObjectId)?.serverVersion
            "block" -> content.block(ownerId, localObjectId)?.serverVersion
            "checkin" -> content.checkIn(ownerId, localObjectId)?.serverVersion
            "settings" -> content.settings(ownerId)?.serverVersion
            "availability" -> {
                val local = identity?.date?.let { content.workTime(ownerId, it) }
                    ?: content.workTimeByClient(ownerId, localObjectId)
                    ?: findWorkTime(ownerId, localObjectId, payload)
                local?.serverVersion
            }
            else -> throw SyncPullProtocolException("UNKNOWN_OBJECT_TYPE")
        } ?: 0
        val tombstoneVersion = maxOf(
            dao.tombstone(ownerId, change.objectType, localObjectId)?.version ?: 0,
            change.objectId.takeIf { it != localObjectId }
                ?.let { dao.tombstone(ownerId, change.objectType, it)?.version } ?: 0,
        )
        val conflictVersion = existingConflict(
            ownerId,
            change.objectType,
            localObjectId,
            change.objectId,
        )?.remoteKnownVersion ?: 0
        val head = dao.head(ownerId, change.objectType, localObjectId)
            ?.takeIf { it.state != "done" }
        val headVersion = head?.baseVersion ?: 0
        return maxOf(contentVersion, tombstoneVersion, conflictVersion, headVersion)
    }

    private suspend fun resolveAvailabilityIdentity(
        ownerId: String,
        remoteObjectId: String,
        payload: JSONObject?,
    ): AvailabilityIdentity {
        val payloadDate = payload?.string("date")
        val mapping = dao.mapping(ownerId, "availability", remoteObjectId)
        val byClient = content.workTimeByClient(ownerId, remoteObjectId)
        val mappedDate = mapping?.naturalKey
        val date = payloadDate
            ?: byClient?.date
            ?: mappedDate
            ?: content.workTimes(ownerId).firstOrNull {
                canonicalId("availability:$ownerId:${it.date}") == remoteObjectId
            }?.date
        val localObjectId = byClient?.let { remoteObjectId }
            ?: mapping?.accountObjectId?.takeIf { it.isNotBlank() }
            ?: date?.let { canonicalId("availability:$ownerId:$it") }
            ?: remoteObjectId
        return AvailabilityIdentity(localObjectId, date)
    }

    private suspend fun applyContent(
        ownerId: String,
        type: String,
        objectId: String,
        payload: JSONObject,
        version: Int,
        now: Long,
    ) {
        when (type) {
            "task" -> content.save(
                TaskRow(
                    id = objectId,
                    title = payload.requiredString("title").trim().also {
                        if (it.isEmpty()) throw SyncPullProtocolException("TASK_TITLE_EMPTY")
                    },
                    minutes = payload.requiredInt("minutes", 0, 525_600),
                    deadline = payload.requiredLong("deadline", 0, Long.MAX_VALUE),
                    importance = payload.requiredInt("importance", 1, 5),
                    done = payload.requiredBoolean("done"),
                    created = payload.requiredLong("created", 0, Long.MAX_VALUE),
                    note = payload.optionalString("note") ?: "",
                    chunkMinutes = payload.requiredInt("chunk_minutes", 1, 525_600),
                    splittable = payload.requiredBoolean("splittable"),
                    category = payload.requiredString("category").also {
                        if (it !in TASK_CATEGORIES) throw SyncPullProtocolException("TASK_CATEGORY_INVALID")
                    },
                    ownerId = ownerId,
                    clientId = objectId,
                    serverVersion = version,
                    updatedAt = now,
                ),
            )
            "block" -> content.save(
                BlockRow(
                    id = objectId,
                    taskId = payload.requiredString("task_id"),
                    start = payload.requiredLong("start", 0, Long.MAX_VALUE),
                    end = payload.requiredLong("end", 0, Long.MAX_VALUE).also {
                        if (it <= payload.requiredLong("start", 0, Long.MAX_VALUE)) {
                            throw SyncPullProtocolException("BLOCK_RANGE_INVALID")
                        }
                    },
                    locked = payload.requiredBoolean("locked"),
                    done = payload.requiredBoolean("done"),
                    ownerId = ownerId,
                    clientId = objectId,
                    serverVersion = version,
                    updatedAt = now,
                ),
            )
            "checkin" -> content.save(
                CheckInRow(
                    id = objectId,
                    taskId = payload.requiredString("task_id"),
                    completedAt = payload.requiredLong("completed_at", 0, Long.MAX_VALUE),
                    rating = payload.requiredInt("rating", 1, 5),
                    ownerId = ownerId,
                    clientId = objectId,
                    serverVersion = version,
                    updatedAt = now,
                ),
            )
            "settings" -> {
                val startHour = payload.requiredInt("start_hour", 0, 23)
                val endHour = payload.requiredInt("end_hour", 1, 24)
                if (startHour >= endHour) throw SyncPullProtocolException("SETTINGS_RANGE_INVALID")
                content.save(
                    SettingRow(
                        id = 1,
                        startHour = startHour,
                        endHour = endHour,
                        ownerId = ownerId,
                        clientId = canonicalId("settings:$ownerId"),
                        serverVersion = version,
                        updatedAt = now,
                        timeZone = strictZone(payload.requiredString("time_zone")),
                    ),
                )
            }
            "availability" -> {
                val date = strictDate(payload.requiredString("date"))
                val startMinute = payload.requiredInt("start_minute", 0, 1439)
                val endMinute = payload.requiredInt("end_minute", 1, 1440)
                if (startMinute >= endMinute) throw SyncPullProtocolException("AVAILABILITY_RANGE_INVALID")
                content.save(
                    DailyWorkTimeRow(
                        date = date,
                        startMinute = startMinute,
                        endMinute = endMinute,
                        ownerId = ownerId,
                        clientId = objectId,
                        serverVersion = version,
                        updatedAt = now,
                        timeZone = strictZone(payload.requiredString("time_zone")),
                    ),
                )
            }
            else -> throw SyncPullProtocolException("UNKNOWN_OBJECT_TYPE")
        }
    }

    private suspend fun findWorkTime(
        ownerId: String,
        objectId: String,
        payload: JSONObject?,
    ): DailyWorkTimeRow? =
        content.workTimeByClient(ownerId, objectId)
            ?: payload?.string("date")?.let { content.workTime(ownerId, it) }
            ?: dao.mapping(ownerId, "availability", objectId)?.naturalKey
                ?.let { content.workTime(ownerId, it) }
            ?: content.workTimes(ownerId).firstOrNull {
                canonicalId("availability:$ownerId:${it.date}") == objectId
            }

    private suspend fun deleteContent(
        ownerId: String,
        type: String,
        objectId: String,
        payload: JSONObject?,
    ) {
        when (type) {
            "task" -> content.deleteTask(ownerId, objectId)
            "block" -> content.deleteBlock(ownerId, objectId)
            "checkin" -> content.deleteCheckIn(ownerId, objectId)
            "settings" -> content.deleteSettings(ownerId)
            "availability" -> {
                findWorkTime(ownerId, objectId, payload)?.let { content.deleteWorkTime(ownerId, it.date) }
            }
            else -> throw SyncPullProtocolException("UNKNOWN_OBJECT_TYPE")
        }
    }

    private fun parsePayload(change: SyncPullChange): JSONObject {
        val raw = change.payloadJson ?: throw SyncPullProtocolException("PAYLOAD_MISSING")
        val payload = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw SyncPullProtocolException("PAYLOAD_MALFORMED")
        }
        if (payload.optInt("payload_version", -1) != 1) {
            throw SyncPullProtocolException("PAYLOAD_VERSION_UNSUPPORTED")
        }
        return payload
    }

    private fun currentSessionFor(ownerId: String): String? {
        if (session.access.value.userId != ownerId) return null
        return session.currentSessionId()
    }

    private fun strictDate(value: String): String {
        try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            throw SyncPullProtocolException("AVAILABILITY_DATE_INVALID")
        }
        return value
    }

    private fun strictZone(value: String): String {
        try {
            ZoneId.of(value)
        } catch (_: Exception) {
            throw SyncPullProtocolException("TIME_ZONE_INVALID")
        }
        return value
    }

    private fun canonicalId(name: String): String {
        val namespace = ByteBuffer.allocate(16)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(namespace + name.toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest.copyOf(16))
        return UUID(bytes.long, bytes.long).toString()
    }

    private enum class ApplyResult { APPLIED, DELETED, CONFLICT, SKIPPED }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        val OBJECT_TYPES = setOf("task", "block", "checkin", "availability", "settings")
        val TASK_CATEGORIES = setOf(
            "none", "study", "exercise", "entertainment", "work", "social", "travel", "rest",
        )
        val NAMESPACE = UUID.fromString("b420822a-ec70-4d54-976d-586dde231c6d")
    }
}

private fun JSONObject.requiredString(key: String): String {
    val value = opt(key)
    if (value !is String || value.isBlank()) throw SyncPullProtocolException("MISSING_$key")
    return value
}

private fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }

private fun JSONObject.optionalString(key: String): String? =
    when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> throw SyncPullProtocolException("INVALID_$key")
    }

private fun JSONObject.requiredInt(key: String, min: Int, max: Int): Int {
    val value = opt(key)
    if (value !is Number || value.toDouble() != value.toLong().toDouble()) {
        throw SyncPullProtocolException("MISSING_$key")
    }
    val number = value.toLong()
    if (number < min || number > max) throw SyncPullProtocolException("INVALID_$key")
    return number.toInt()
}

private fun JSONObject.requiredLong(key: String, min: Long, max: Long): Long {
    val value = opt(key)
    if (value !is Number || value.toDouble() != value.toLong().toDouble()) {
        throw SyncPullProtocolException("MISSING_$key")
    }
    val number = value.toLong()
    if (number < min || number > max) throw SyncPullProtocolException("INVALID_$key")
    return number
}

private fun JSONObject.requiredBoolean(key: String): Boolean =
    when (val value = opt(key)) {
        is Boolean -> value
        else -> throw SyncPullProtocolException("MISSING_$key")
    }
