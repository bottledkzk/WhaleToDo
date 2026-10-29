package io.whaletodo.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Wire DTO for POST /sync/push. Payload is kept as the exact JSON fragment from the
 * outbox snapshot; the batch body is serialized once and must be reused on retry.
 */
data class SyncPushMutation(
    val objectType: String,
    val objectId: String,
    val op: String,
    val expectedVersion: Int?,
    val payloadJson: String?,
)

data class SyncPushBody(
    val batchId: String,
    val deviceId: String,
    val mutations: List<SyncPushMutation>,
) {
    fun toJson(): String = buildJsonObject {
        put("batch_id", batchId)
        put("device_id", deviceId)
        put("mutations", buildJsonArray {
            mutations.forEach { mutation ->
                addJsonObject {
                    put("object_type", mutation.objectType)
                    put("object_id", mutation.objectId)
                    put("op", mutation.op)
                    put("expected_version", mutation.expectedVersion?.let(::JsonPrimitive) ?: JsonNull)
                    if (mutation.payloadJson != null) {
                        put("payload", Json.parseToJsonElement(mutation.payloadJson))
                    }
                }
            }
        })
    }.toString()
}

data class SyncPushItem(
    val objectType: String,
    val objectId: String,
    val op: String,
    val result: String,
    val serverVersion: Int?,
    val reason: String?,
)

data class SyncPushResponse(
    val batchId: String,
    val status: String,
    val replayed: Boolean,
    val items: List<SyncPushItem>,
)

/** Narrow seam so the app push engine can be tested without opening a socket. */
interface SyncPushApi {
    /** Sends the exact stored body bytes; retries must pass the same string. */
    suspend fun push(sessionId: String, bodyJson: String): SyncPushResponse
}

class SyncApi(private val client: ApiClient) : SyncPushApi {
    override suspend fun push(sessionId: String, bodyJson: String): SyncPushResponse =
        parsePushResponse(client.postAsSession("/sync/push", bodyJson, sessionId))

    suspend fun push(sessionId: String, body: SyncPushBody): SyncPushResponse =
        push(sessionId, body.toJson())
}

/** Parse the stable `{data:{...}}` envelope; malformed 2xx is a hard protocol error. */
internal fun parsePushResponse(response: ApiResponse): SyncPushResponse {
    val root = runCatching { Json.parseToJsonElement(response.body).jsonObject }
        .getOrElse { throw malformed(response) }
    val data = root["data"] as? JsonObject ?: throw malformed(response)
    val batchId = data.string("batch_id")?.takeIf { it.isNotBlank() } ?: throw malformed(response)
    val status = data.string("status")?.takeIf { it in PUSH_STATUSES } ?: throw malformed(response)
    val rawItems = data["items"] as? JsonArray ?: throw malformed(response)
    if (rawItems.isEmpty()) throw malformed(response)
    val seen = mutableSetOf<String>()
    val items = rawItems.map { raw ->
        val item = raw as? JsonObject ?: throw malformed(response)
        val objectType = item.string("object_type") ?: throw malformed(response)
        val objectId = item.string("object_id") ?: throw malformed(response)
        val op = item.string("op")?.takeIf { it in PUSH_OPS } ?: throw malformed(response)
        val result = item.string("result")?.takeIf { it in PUSH_RESULTS } ?: throw malformed(response)
        val version = (item["server_version"] as? JsonPrimitive)?.intOrNull
        if (result == "applied" && op != "delete" && (version == null || version <= 0)) {
            throw malformed(response)
        }
        val key = "$objectType\u0000$objectId\u0000$op"
        if (!seen.add(key)) throw malformed(response)
        SyncPushItem(
            objectType = objectType,
            objectId = objectId,
            op = op,
            result = result,
            serverVersion = version,
            reason = item.string("reason"),
        )
    }
    return SyncPushResponse(
        batchId = batchId,
        status = status,
        replayed = (data["replayed"] as? JsonPrimitive)?.booleanOrNull ?: false,
        items = items,
    )
}

private val PUSH_STATUSES = setOf("applied", "partial", "rejected")
private val PUSH_RESULTS = setOf("applied", "rejected", "conflict")
private val PUSH_OPS = setOf("create", "update", "delete")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun malformed(response: ApiResponse): ApiException =
    ApiException("INTERNAL", "同步回执格式异常", response.status, response.requestId, response.body)
