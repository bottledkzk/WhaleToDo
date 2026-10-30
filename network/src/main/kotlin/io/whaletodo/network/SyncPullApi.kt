package io.whaletodo.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A single opaque change from GET /sync/pull. Payload stays as JSON text for the app mapper. */
data class SyncPullChange(
    val objectType: String,
    val objectId: String,
    val version: Int,
    val deleted: Boolean,
    val payloadJson: String?,
)

data class SyncPullResponse(
    val changes: List<SyncPullChange>,
    val nextCursor: Long,
    val hasMore: Boolean,
)

/** Narrow seam so the pull engine can be tested without opening a socket. */
interface SyncPullApi {
    suspend fun pull(sessionId: String, cursor: Long, limit: Int = 100): SyncPullResponse
}

/** Parse the stable `{data:{changes,next_cursor,has_more}}` envelope. */
internal fun parsePullResponse(response: ApiResponse): SyncPullResponse {
    val root = runCatching { Json.parseToJsonElement(response.body).jsonObject }
        .getOrElse { throw pullMalformed(response) }
    val data = root["data"] as? JsonObject ?: throw pullMalformed(response)
    val rawChanges = data["changes"] as? JsonArray ?: throw pullMalformed(response)
    val nextCursor = (data["next_cursor"] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull || it.isString }
        ?.longOrNull
        ?.takeIf { it >= 0 }
        ?: throw pullMalformed(response)
    val hasMore = (data["has_more"] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull || it.isString }
        ?.booleanOrNull
        ?: throw pullMalformed(response)

    val changes = rawChanges.map { raw ->
        val change = raw as? JsonObject ?: throw pullMalformed(response)
        val objectType = change.string("object_type")?.takeIf { it in PULL_OBJECT_TYPES }
            ?: throw pullMalformed(response)
        val objectId = change.string("object_id")
            ?.takeIf { UUID_RE.matches(it) }
            ?: throw pullMalformed(response)
        val version = (change["version"] as? JsonPrimitive)
            ?.takeUnless { it is JsonNull || it.isString }
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?: throw pullMalformed(response)
        val deleted = (change["deleted"] as? JsonPrimitive)
            ?.takeUnless { it is JsonNull || it.isString }
            ?.booleanOrNull
            ?: throw pullMalformed(response)
        val payload = change["payload"]
        val payloadJson = when {
            payload == null || payload is JsonNull -> null
            payload is JsonObject -> {
                if (!deleted) {
                    val payloadVersion = (payload["payload_version"] as? JsonPrimitive)
                        ?.takeUnless { it is JsonNull || it.isString }
                        ?.intOrNull
                    if (payloadVersion != PULL_PAYLOAD_VERSION) throw pullMalformed(response)
                }
                payload.toString()
            }
            else -> throw pullMalformed(response)
        }
        if (!deleted && payloadJson == null) throw pullMalformed(response)
        SyncPullChange(
            objectType = objectType,
            objectId = objectId,
            version = version,
            deleted = deleted,
            payloadJson = payloadJson,
        )
    }

    return SyncPullResponse(changes, nextCursor, hasMore)
}

private val PULL_OBJECT_TYPES = setOf("task", "block", "checkin", "availability", "settings")
private const val PULL_PAYLOAD_VERSION = 1
private val UUID_RE = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

private fun pullMalformed(response: ApiResponse): ApiException =
    ApiException("INTERNAL", "同步回拉格式异常", response.status, response.requestId, response.body)
