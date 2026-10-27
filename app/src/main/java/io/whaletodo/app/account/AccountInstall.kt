package io.whaletodo.app.account

import android.content.Context
import io.whaletodo.app.BuildConfig
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.profile.ProfileBridgeHub
import io.whaletodo.app.reminder.ReminderCenter
import io.whaletodo.network.AiApi
import io.whaletodo.network.ApiClient
import io.whaletodo.network.ApiConfig
import io.whaletodo.network.AuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val installLock = Any()
private var installed = false

private data class AccountRuntime(
    val profile: ServerProfileBridge,
    val agent: ServerAgentBridge,
)

/**
 * 在 Compose 启动前安装账户与 AI bridge。
 * 账户和 AI 共用同一个 ApiClient/TokenStore；restore 后再刷新 models/usage/钱包。
 */
fun installAccount(context: Context) {
    val runtime = synchronized(installLock) {
        if (installed) return
        installed = true
        val credentials = KeystoreTokenStore(context.applicationContext)
        val config = ApiConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            allowInsecureHttp = BuildConfig.DEBUG,
        )
        val client = ApiClient(config, credentials)
        val auth = AuthApi(client, credentials)
        val session = AccountSession { credentials.read()?.sessionId }
        val agent = ServerAgentBridge(AiApi(client), auth, session, accountScope)
        val profile = ServerProfileBridge(auth, session, credentials) { owner, sessionId ->
            // begin 之后的立即取消（owner=null）时 currentSessionId 仍是新代次；
            // 登出/401 清空后 currentSessionId 已为 null，此时只允许 guest 清理落地。
            val current = session.currentSessionId()
            val applies = sessionId == null || current == sessionId ||
                (owner == null && current == null)
            if (applies) {
                // 提醒重建失败不应反过来让登录/退出失败。
                runCatching {
                    ReminderCenter.setActiveOwner(
                        context.applicationContext,
                        owner ?: "guest",
                        sessionId,
                    ) { session.currentSessionId() }
                }
            }
        }
        ProfileBridgeHub.bridge = profile
        AgentBridgeHub.bridge = agent
        AccountRuntime(profile, agent)
    }
    accountScope.launch {
        runCatching { runtime.profile.restore() }
        runCatching { runtime.agent.refresh() }
    }
}
