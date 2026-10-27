package io.whaletodo.app.account

import io.whaletodo.app.agent.AgentBridge
import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.app.agent.AssistantReply
import io.whaletodo.app.agent.AssistantRequest
import io.whaletodo.core.AgentAccess
import io.whaletodo.core.AgentChange
import io.whaletodo.core.AgentModel
import io.whaletodo.core.GateReason
import io.whaletodo.network.AiApi
import io.whaletodo.network.AiChange
import io.whaletodo.network.AiHistory
import io.whaletodo.network.AiRequestView
import io.whaletodo.network.AiSubmit
import io.whaletodo.network.ApiException
import io.whaletodo.network.AuthApi
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * 真实 AI 网关桥：只发自有服务端，不直连供应商。
 * access 的 userId/vip/points 来自 AccountSession；connected/editModels 只由 models 成功更新。
 */
class ServerAgentBridge(
    private val ai: AiApi,
    private val auth: AuthApi,
    private val session: AccountSession,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AgentBridge {
    private val mutableAccess = MutableStateFlow(
        session.access.value.copy(connected = false, editModels = emptySet()),
    )
    override val access: StateFlow<AgentAccess> = mutableAccess.asStateFlow()

    @Volatile
    private var modelList: List<AgentModel> = emptyList()
    override val models: List<AgentModel> get() = modelList

    init {
        scope.launch {
            var lastOwner: String? = null
            session.access.collect { base ->
                val owner = base.userId
                if (owner == null) {
                    lastOwner = null
                    modelList = emptyList()
                    mutableAccess.value = AgentAccess()
                } else if (owner != lastOwner) {
                    lastOwner = owner
                    // 换号时先断开旧 owner 的模型/编辑能力，再刷新新 owner。
                    mutableAccess.value = base.copy(connected = false, editModels = emptySet())
                    refresh()
                } else {
                    // 签到/订单/VIP/余额变化：只同步 userId/vip/points，保留已建立的连接与编辑模型。
                    mutableAccess.value = base.copy(
                        connected = mutableAccess.value.connected,
                        editModels = mutableAccess.value.editModels,
                    )
                }
            }
        }
    }

    /** 拉 models + usage + 钱包；models 成功才 connected。 */
    suspend fun refresh() {
        val owner = session.access.value.userId ?: run {
            modelList = emptyList()
            mutableAccess.value = AgentAccess()
            return
        }
        val models = try {
            ai.models()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (session.access.value.userId == owner) {
                mutableAccess.value = session.access.value.copy(connected = false)
            }
            return
        }
        val mapped = models.items.map {
            AgentModel(
                code = it.code,
                title = it.title,
                canEdit = it.canEdit,
                requiresVip = it.requiresVip,
            )
        }
        if (session.access.value.userId != owner) return
        modelList = mapped
        val editModels = mapped.filter { it.canEdit }.map { it.code }.toSet()
        mutableAccess.value = session.access.value.copy(connected = true, editModels = editModels)

        runCatching { ai.usage() }
        val entitlements = runCatching { auth.entitlements() }.getOrNull()
        val wallet = runCatching { auth.wallet() }.getOrNull()
        if (session.access.value.userId == owner) {
            if (entitlements != null && wallet != null) {
                session.updateBalance(entitlements, wallet.available)
            }
            mutableAccess.value = session.access.value.copy(connected = true, editModels = editModels)
        }
    }

    override suspend fun reply(request: AssistantRequest): AssistantReply {
        val owner = requireOwner()
        if (request.ownerId != owner) throw AgentGateException(GateReason.LOGIN)
        try {
            val view = try {
                ai.submit(
                    AiSubmit(
                        clientRequestId = request.id,
                        mode = request.mode,
                        modelCode = request.model,
                        prompt = request.prompt,
                        context = request.context,
                        history = request.history
                            .filter { it.first == "user" || it.first == "assistant" }
                            .takeLast(16)
                            .map { AiHistory(it.first, it.second) },
                        editConsent = request.editConsent,
                        shareStyle = request.shareStyle,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                throw mapApi(e)
            } catch (e: IOException) {
                throw mapIo(e, request.id)
            } catch (e: Exception) {
                throw AgentServiceException(false)
            }
            return when (view.status) {
                "succeeded" -> toReply(view)
                "failed" -> throw AgentServiceException(true)
                else -> throw AgentPendingException(request.id)
            }
        } finally {
            refreshAfterCall(owner)
        }
    }

    override suspend fun query(requestId: String): AssistantReply {
        val owner = requireOwner()
        try {
            val view = try {
                ai.query(requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                throw mapApi(e)
            } catch (e: IOException) {
                throw mapIo(e, requestId)
            } catch (e: Exception) {
                throw AgentServiceException(false)
            }
            return when (view.status) {
                "succeeded" -> toReply(view)
                "failed" -> throw AgentServiceException(true)
                else -> throw AgentPendingException(requestId)
            }
        } finally {
            refreshAfterCall(owner)
        }
    }

    override suspend fun authorizeTemplateTrial(template: String): Boolean = false

    private fun requireOwner(): String =
        session.access.value.userId?.takeIf { it.isNotBlank() }
            ?: throw AgentGateException(GateReason.LOGIN)

    private fun toReply(view: AiRequestView): AssistantReply {
        val text = view.text?.takeIf { it.isNotBlank() }
            ?: throw AgentServiceException(true)
        return AssistantReply(
            text = text,
            totalTokens = view.usageTokens ?: 0,
            inputTokens = view.inputTokens,
            outputTokens = view.outputTokens,
            chargedPoints = view.pointsCharged,
            continuation = view.continuation,
            changes = view.proposal?.changes?.mapNotNull(::toChange).orEmpty(),
        )
    }

    private fun toChange(change: AiChange): AgentChange? = when (change.type) {
        "rename_task" -> change.title?.let { AgentChange.RenameTask(change.targetId, it) }
        "set_category" -> change.category?.let { AgentChange.CategorizeTask(change.targetId, it) }
        "move_block" -> {
            val start = change.startMs
            val end = change.endMs
            if (start == null || end == null) null else AgentChange.MoveBlock(change.targetId, start, end)
        }
        else -> null
    }

    private fun mapApi(e: ApiException): Exception = when {
        e.status == 401 -> AgentGateException(GateReason.LOGIN)
        e.status == 402 -> AgentGateException(GateReason.POINTS)
        e.status == 403 -> when (ai.errorReason(e.body)) {
            "VIP_REQUIRED" -> AgentGateException(GateReason.VIP)
            "MODEL_NOT_ALLOWED" -> AgentGateException(GateReason.MODEL)
            "CONSENT_REQUIRED" -> AgentGateException(GateReason.CONSENT)
            else -> AgentServiceException(true)
        }
        e.status == 409 || e.status == 429 || e.status == 503 ->
            AgentServiceException(true)
        else -> AgentServiceException(false)
    }

    /**
     * 连接失败 → CONNECTION；请求已发出后的读超时 → 原 ID pending。
     * 这里用异常类型/消息区分连接超时与读超时；其他 IOException 按未连接处理。
     */
    private fun mapIo(e: IOException, requestId: String): Exception {
        val connectTimeout = e is SocketTimeoutException &&
            e.message?.contains("connect", ignoreCase = true) == true
        return if (e is SocketTimeoutException && !connectTimeout) {
            AgentPendingException(requestId)
        } else {
            AgentGateException(GateReason.CONNECTION)
        }
    }

    /** 成功/失败都刷新钱包与当日 usage；不根据客户端字符串修改积分。 */
    private suspend fun refreshAfterCall(owner: String) {
        if (session.access.value.userId != owner) return
        runCatching {
            val entitlements = auth.entitlements()
            val wallet = auth.wallet()
            if (session.access.value.userId == owner) {
                session.updateBalance(entitlements, wallet.available)
                mutableAccess.value = session.access.value.copy(
                    connected = mutableAccess.value.connected,
                    editModels = modelList.filter { it.canEdit }.map { it.code }.toSet(),
                )
            }
        }
        runCatching { ai.usage() }
    }
}
