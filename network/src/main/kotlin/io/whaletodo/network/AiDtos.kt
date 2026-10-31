package io.whaletodo.network

/** AI 网关的 wire DTO；只描述服务端契约，不含供应商 URL/Key 或本地权限判断。 */
data class AiModel(
    val code: String,
    val title: String,
    val canEdit: Boolean,
    val requiresVip: Boolean,
)

data class AiModels(
    val promptVersion: String?,
    val tokensPerPoint: Int,
    val items: List<AiModel>,
)

data class AiUsage(
    val requests: Int,
    val completed: Int,
    val pending: Int,
    val totalTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val chargedPoints: Int,
    val availablePoints: Int,
    val reservedPoints: Int,
    val usageK: String?,
    val tokensPerPoint: Int,
)

data class AiHistory(val role: String, val content: String)

data class AiSubmit(
    val clientRequestId: String,
    val mode: String,
    val modelCode: String,
    val prompt: String,
    val context: String,
    val history: List<AiHistory>,
    val editConsent: Boolean,
    val shareStyle: String,
    val diaryStyle: String? = null,
)

data class AiChange(
    val type: String,
    val targetId: String,
    val title: String?,
    val category: String?,
    val startMs: Long?,
    val endMs: Long?,
)

data class AiProposal(val changes: List<AiChange>)

data class AiRequestView(
    val id: String,
    val status: String,
    val mode: String?,
    val text: String?,
    val usageTokens: Int?,
    val usageK: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val pointsReserved: Int?,
    val pointsCharged: Int?,
    val continuation: Boolean,
    val contextCompacted: Boolean,
    val proposal: AiProposal?,
    val errorCode: String?,
    val createdAt: String?,
)
