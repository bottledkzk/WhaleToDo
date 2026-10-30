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
import io.whaletodo.network.ApiTransportException
import io.whaletodo.network.AuthApi
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 真实 AI 网关桥：只发自有服务端，不直连供应商。
 * access 的 userId/vip/points 来自 AccountSession；connected/editModels 只由 models 成功更新。
 * 所有 HTTP 都在 [io] 上执行。reply 绑定调用开始时的 sessionId；
 * query 在进入 IO 前冻结 owner，并在 HTTP 前/回包前再次核对。同一 owner 重新登录
 * 可使用当前有效 session，owner 变了则返回 pending，避免 A 的请求以 B 的令牌发出或写回。
 */
class ServerAgentBridge(
    private val ai: AiApi,
    private val auth: AuthApi,
    private val session: AccountSession,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AgentBridge {
    private val mutableAccess = MutableStateFlow(
        session.access.value.copy(connected = false, editModels = emptySet()),
    )
    override val access: StateFlow<AgentAccess> = mutableAccess.asStateFlow()

    @Volatile
    private var modelList: List<AgentModel> = emptyList()
    override val models: List<AgentModel> get() = modelList

    @Volatile
    private var refreshJob: Job? = null

    init {
        scope.launch {
            var lastOwner: String? = null
            session.access.collect { base ->
                val owner = base.userId
                when {
                    owner == null -> {
                        lastOwner = null
                        refreshJob?.cancel()
                        refreshJob = null
                        modelList = emptyList()
                        mutableAccess.value = AgentAccess()
                    }
                    owner != lastOwner || base.vip != mutableAccess.value.vip -> {
                        lastOwner = owner
                        refreshJob?.cancel()
                        modelList = emptyList()
                        // 换号先立即断开旧 owner 的能力；网络刷新放到独立 Job，不阻塞 collect。
                        mutableAccess.value = base.copy(connected = false, editModels = emptySet())
                        val expectedSession = session.currentSessionId()
                        refreshJob = scope.launch {
                            if (expectedSession != null) refresh(base, expectedSession)
                        }
                    }
                    else -> {
                        // 签到/订单/VIP/余额变化：只同步 userId/vip/points，保留连接与编辑模型。
                        mutableAccess.value = base.copy(
                            connected = mutableAccess.value.connected,
                            editModels = mutableAccess.value.editModels,
                        )
                    }
                }
            }
        }
    }

    /** 拉 models + usage + 钱包；models 成功才 connected。 */
    suspend fun refresh() = withContext(io) {
        val expectedSession = session.currentSessionId()
        val base = session.access.value
        if (expectedSession == null || base.userId == null) {
            modelList = emptyList()
            mutableAccess.value = AgentAccess()
            return@withContext
        }
        refreshJob?.cancel()
        refresh(base, expectedSession)
    }

    override suspend fun refreshCapabilities() = refresh()

    private suspend fun refresh(base: AgentAccess, expectedSession: String) = withContext(io) {
        val owner = base.userId ?: return@withContext
        fun current() = session.currentSessionId() == expectedSession &&
            session.access.value.userId == owner

        val models = try {
            ai.models(expectedSession)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (current()) {
                mutableAccess.value = session.access.value.copy(connected = false)
            }
            return@withContext
        }
        if (!current()) return@withContext

        val mapped = models.items.map {
            AgentModel(
                code = it.code,
                title = it.title,
                canEdit = it.canEdit,
                requiresVip = it.requiresVip,
            )
        }
        modelList = mapped
        val editModels = mapped.filter { it.canEdit }.map { it.code }.toSet()
        mutableAccess.value = session.access.value.copy(connected = true, editModels = editModels)

        try {
            ai.usage(expectedSession)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // usage 只用于展示；失败不影响 connected 与编辑能力。
        }
        if (!current()) return@withContext

        val entitlements = try {
            auth.entitlements()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!current()) return@withContext
        val wallet = try {
            auth.wallet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!current()) return@withContext

        if (entitlements != null && wallet != null &&
            session.updateBalance(expectedSession, owner, entitlements, wallet.available)
        ) {
            if (current()) {
                mutableAccess.value = session.access.value.copy(
                    connected = true,
                    editModels = editModels,
                )
            }
        }
    }

    override suspend fun reply(request: AssistantRequest): AssistantReply = withContext(io) {
        // 冻结本次调用所属代次；后续 HTTP 必须用它做 bound，返回后也要用它核对。
        val expectedSession = session.currentSessionId()
            ?: throw AgentGateException(GateReason.LOGIN, definitelyNotSent = true)
        val owner = requireOwner(definitelyNotSent = true)
        if (request.ownerId != owner) {
            throw AgentGateException(GateReason.LOGIN, definitelyNotSent = true)
        }
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
                    expectedSession,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                throw mapSubmitApi(e, request.id)
            } catch (e: IOException) {
                throw mapIo(e, request.id)
            } catch (_: Exception) {
                throw AgentServiceException(false)
            }
            if (!sameSession(expectedSession, owner)) {
                // submit 已返回；即使请求未成功，也不能证明服务端没有受理/计费。
                throw AgentGateException(GateReason.LOGIN, definitelyNotSent = false)
            }
            when (view.status) {
                "succeeded" -> toReply(view)
                "failed" -> throw AgentServiceException(true, definitelyNotSent = false)
                else -> throw AgentPendingException(request.id)
            }
        } finally {
            // 超时/取消时 job 已不活跃，不再补一次最长 55s 的刷新，保证取消能及时传播。
            if (currentCoroutineContext().isActive) refreshAfterCall(expectedSession)
        }
    }

    override suspend fun query(requestId: String, expectedOwner: String?): AssistantReply {
        // Freeze the owner before dispatching to IO; a later account switch must not turn
        // this into a query with the other account's token.
        val owner = expectedOwner ?: requireOwner()
        val sessionAtCall = session.currentSessionId()
        if (sessionAtCall == null) throw AgentGateException(GateReason.LOGIN)

        return withContext(io) {
            // The provider may have observed A before the switch, then IO starts on B.
            requireQueryOwner(owner, requestId)
            // Same owner may have logged in again; use the current valid generation.
            val querySession = session.currentSessionId()
                ?: throw AgentPendingException(requestId)
            requireQueryOwner(owner, requestId)

            try {
                val view = try {
                    // ApiClient also binds querySession to TokenStore; if the token generation
                    // changes after this check, the request aborts before HTTP.
                    ai.query(requestId, querySession)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // A 403/401 from another account must never become A's terminal state.
                    requireQueryOwner(owner, requestId)
                    throw mapQueryApi(e, requestId)
                } catch (e: IOException) {
                    requireQueryOwner(owner, requestId)
                    throw mapIo(e, requestId)
                } catch (_: Exception) {
                    requireQueryOwner(owner, requestId)
                    throw AgentServiceException(false)
                }
                requireQueryOwner(owner, requestId)
                when (view.status) {
                    "succeeded" -> toReply(view)
                    "failed" -> throw AgentServiceException(true)
                    else -> throw AgentPendingException(requestId)
                }
            } finally {
                if (currentCoroutineContext().isActive) refreshAfterCall(querySession)
            }
        }
    }

    /** Owner changed between the provider check and the actual query/response. */
    private fun requireQueryOwner(owner: String, requestId: String) {
        if (session.access.value.userId != owner) throw AgentPendingException(requestId)
    }

    override suspend fun authorizeTemplateTrial(template: String): Boolean = false

    private fun sameSession(expectedSession: String, owner: String): Boolean =
        session.currentSessionId() == expectedSession && session.access.value.userId == owner

    private fun requireOwner(definitelyNotSent: Boolean = false): String =
        session.access.value.userId?.takeIf { it.isNotBlank() }
            ?: throw AgentGateException(GateReason.LOGIN, definitelyNotSent)

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
            changes = view.proposal?.changes?.map { toChange(it) ?: throw AgentServiceException(true) }.orEmpty(),
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
        "schedule_task" -> {
            val start=change.startMs;val end=change.endMs
            if(start==null||end==null)null else AgentChange.ScheduleTask(change.targetId,start,end)
        }
        else -> null
    }

    /**
     * 提交语义：只有明确未接纳（连接失败/门禁/业务拒绝）才 definitive；
     * 已发出后的 5xx/IO 保留原 ID，等下一次 query 确认。
     * 429 在提交阶段仍按明确拒绝处理（与查询不同）。
     */
    private fun mapSubmitApi(e: ApiException, requestId: String): Exception =
        AgentErrorMapping.submit(
            status = e.status,
            bodyStatus = ai.statusOf(e.body),
            reason = ai.errorReason(e.body),
            requestId = requestId,
        )

    /**
     * 查询语义：503/5xx 只说明暂时查不到，429 只限制本次查询，都不能终结 pending；
     * 只有明确 failed、403 归属拒绝、409/明确业务拒绝才结束。
     */
    private fun mapQueryApi(e: ApiException, requestId: String): Exception =
        AgentErrorMapping.query(
            status = e.status,
            code = e.code,
            bodyStatus = ai.statusOf(e.body),
            requestId = requestId,
        )

    /**
     * 连接未建立 → CONNECTION（确定未发出）；
     * 已写出后的读超时/EOF/reset → pending（服务端可能已接收）。
     */
    private fun mapIo(e: IOException, requestId: String): Exception {
        if (e is ApiTransportException) {
            return if (e.requestSent) {
                AgentPendingException(requestId)
            } else {
                AgentGateException(GateReason.CONNECTION, definitelyNotSent = true)
            }
        }
        return when {
            e is ConnectException || e is UnknownHostException ->
                AgentGateException(GateReason.CONNECTION, definitelyNotSent = true)
            e is SocketTimeoutException -> {
                val connectTimeout = e.message?.contains("connect", ignoreCase = true) == true
                if (connectTimeout) {
                    AgentGateException(GateReason.CONNECTION, definitelyNotSent = true)
                } else {
                    AgentPendingException(requestId)
                }
            }
            else -> AgentGateException(GateReason.CONNECTION)
        }
    }

    /** 成功/失败都刷新钱包与当日 usage；只在会话代次未变时写回。 */
    private suspend fun refreshAfterCall(expectedSession: String) = withContext(io) {
        val owner = session.access.value.userId ?: return@withContext
        fun current() = session.currentSessionId() == expectedSession &&
            session.access.value.userId == owner
        if (!current()) return@withContext

        try {
            val entitlements = auth.entitlements()
            if (!current()) return@withContext
            val wallet = auth.wallet()
            if (!current()) return@withContext
            if (session.updateBalance(expectedSession, owner, entitlements, wallet.available)) {
                if (current()) {
                    mutableAccess.value = session.access.value.copy(
                        connected = mutableAccess.value.connected,
                        editModels = modelList.filter { it.canEdit }.map { it.code }.toSet(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 钱包刷新失败不影响本次回复结果。
        }
        if (!current()) return@withContext
        try {
            ai.usage(expectedSession)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // usage 刷新失败不影响本次回复结果。
        }
    }
}
