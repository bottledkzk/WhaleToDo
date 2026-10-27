package io.whaletodo.app.account

import android.content.Context
import io.whaletodo.app.BuildConfig
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.profile.ProfileBridgeHub
import io.whaletodo.app.reminder.ReminderCenter
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

/**
 * 在 Compose 启动前安装账户 bridge。
 * MainActivity 只调用这一处；API 基址来自 BuildConfig，release 不允许明文 HTTP。
 */
fun installAccount(context: Context) {
    val bridge = synchronized(installLock) {
        if (installed) return
        installed = true
        val credentials = KeystoreTokenStore(context.applicationContext)
        val config = ApiConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            allowInsecureHttp = BuildConfig.DEBUG,
        )
        val auth = AuthApi(ApiClient(config, credentials), credentials)
        val session = AccountSession { credentials.read()?.sessionId }
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
        val originalAgent = AgentBridgeHub.bridge
        ProfileBridgeHub.bridge = profile
        AgentBridgeHub.bridge = AccountAgentBridge(originalAgent, session)
        profile
    }
    accountScope.launch {
        runCatching { bridge.restore() }
    }
}
