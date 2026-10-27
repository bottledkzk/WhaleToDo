package io.whaletodo.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * AI 网关 API：models/usage/submit/query。
 * 使用 kotlinx-serialization-json 的 tree API，不引入编译器插件或 @Serializable。
 */
class AiApi(private val client: ApiClient) {

    fun models(): AiModels {
        val data = data(client.get("/ai/models"))
        return AiModels(
            promptVersion = data.string("prompt_version"),
            tokensPerPoint = data.int("tokens_per_point") ?: 1000,
            items = data.array("items").mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val code = obj.string("code") ?: return@mapNotNull null
                AiModel(
                    code = code,
                    title = obj.string("title") ?: code,
                    canEdit = obj.bool("can_edit") ?: false,
                    requiresVip = obj.bool("requires_vip") ?: false,
                )
            },
        )
    }

    fun usage(): AiUsage {
        val data = data(client.get("/ai/usage"))
        return AiUsage(
            requests = data.int("requests") ?: 0,
            completed = data.int("completed") ?: 0,
            pending = data.int("pending") ?: 0,
            totalTokens = data.int("total_tokens") ?: 0,
            inputTokens = data.int("input_tokens") ?: 0,
            outputTokens = data.int("output_tokens") ?: 0,
            chargedPoints = data.int("charged_points") ?: 0,
            availablePoints = data.int("available_points") ?: 0,
            reservedPoints = data.int("reserved_points") ?: 0,
            usageK = data.string("usage_k"),
            tokensPerPoint = data.int("tokens_per_point") ?: 1000,
        )
    }

    fun submit(request: AiSubmit): AiRequestView {
        val payload = buildJsonObject {
            put("client_request_id", request.clientRequestId)
            put("mode", request.mode)
            put("model_code", request.modelCode)
            put("prompt", request.prompt)
            put("context", request.context)
            put("edit_consent", request.editConsent)
            put("share_style", request.shareStyle)
            put("history", buildJsonArray {
                request.history.forEach { item ->
                    addJsonObject {
                        put("role", item.role)
                        put("content", item.content)
                    }
                }
            })
        }
        val response = client.post("/ai/requests", payload.toString())
        return parseRequest(response)
    }

    /** 只查询原请求 ID；调用方不得用此方法提交新请求。 */
    fun query(requestId: String): AiRequestView =
        parseRequest(client.get("/ai/requests/$requestId"))

    /** 403 details.reason；服务端不返回该字段时为空。 */
    fun errorReason(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject
            .obj("error")
            ?.obj("details")
            ?.string("reason")
    }.getOrNull()

    private fun parseRequest(response: ApiResponse): AiRequestView {
        val data = data(response)
        return AiRequestView(
            id = data.string("id") ?: throw malformed(response),
            status = data.string("status") ?: "unknown",
            mode = data.string("mode"),
            text = data.string("text"),
            usageTokens = data.int("usage_tokens"),
            usageK = data.string("usage_k"),
            inputTokens = data.int("input_tokens"),
            outputTokens = data.int("output_tokens"),
            pointsReserved = data.int("points_reserved"),
            pointsCharged = data.int("points_charged"),
            continuation = data.bool("continuation") ?: false,
            contextCompacted = data.bool("context_compacted") ?: false,
            proposal = data.obj("proposal")?.let { proposal ->
                AiProposal(
                    changes = proposal.array("changes").mapNotNull { change ->
                        val obj = change as? JsonObject ?: return@mapNotNull null
                        val type = obj.string("type") ?: return@mapNotNull null
                        val target = obj.string("target_id") ?: return@mapNotNull null
                        AiChange(
                            type = type,
                            targetId = target,
                            title = obj.string("title"),
                            category = obj.string("category"),
                            startMs = obj.long("start_ms"),
                            endMs = obj.long("end_ms"),
                        )
                    },
                )
            },
            errorCode = data.string("error_code"),
            createdAt = data.string("created_at"),
        )
    }

    private fun data(response: ApiResponse): JsonObject {
        val root = Json.parseToJsonElement(response.body).jsonObject
        return root.obj("data") ?: throw malformed(response)
    }

    private fun malformed(response: ApiResponse): ApiException =
        ApiException("INTERNAL", "AI 响应格式异常", response.status, response.requestId, response.body)

    private fun JsonObject.obj(key: String): JsonObject? =
        (this[key] as? JsonObject)

    private fun JsonObject.array(key: String): JsonArray =
        (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
