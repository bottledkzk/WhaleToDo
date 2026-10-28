package io.whaletodo.app.agent

import io.whaletodo.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Weiding plugs in the authenticated network layer. No supplier URL/key or login emulator here. */
interface AgentBridge {
    val access: StateFlow<AgentAccess>
    val models: List<AgentModel>
    suspend fun reply(request: AssistantRequest): AssistantReply
    suspend fun query(requestId: String, expectedOwner: String? = null): AssistantReply =
        throw AgentPendingException(requestId)
    suspend fun authorizeTemplateTrial(template: String): Boolean = false
}
data class AssistantRequest(val id: String, val mode: String, val model: String, val prompt: String,
    val context: String, val editConsent: Boolean, val shareStyle: String = "minimal", val history: List<Pair<String,String>>, val ownerId: String)
data class AssistantReply(val text: String, val totalTokens: Int, val inputTokens: Int? = null,
    val outputTokens: Int? = null, val chargedPoints: Int? = null, val continuation: Boolean = false,
    val changes: List<AgentChange> = emptyList())
class AgentPendingException(val requestId: String) : Exception("请求状态待确认")
class AgentServiceException(
    val definitelyRejected: Boolean,
    /** True only when the request definitely was not accepted by the server. */
    val definitelyNotSent: Boolean = false,
) : Exception("助手暂时不可用")
class AgentGateException(
    val reason: GateReason,
    /** True only when the request definitely was not accepted by the server. */
    val definitelyNotSent: Boolean = false,
) : Exception(reason.name)
object AgentBridgeHub {
    /** Install once from trusted application setup; only test APKs replace this with fixtures. */
    var bridge: AgentBridge = object : AgentBridge {
        override val access = MutableStateFlow(AgentAccess())
        override val models = listOf(AgentModel("deepseek-default","鲸鱼AI"), AgentModel("deepseek-planner","日程编辑",true))
        override suspend fun reply(request: AssistantRequest): AssistantReply = throw AgentGateException(GateReason.CONNECTION)
    }
}
