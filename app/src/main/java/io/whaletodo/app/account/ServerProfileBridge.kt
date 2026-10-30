package io.whaletodo.app.account

import io.whaletodo.app.profile.CodePurpose
import io.whaletodo.app.profile.ProfileBridge
import io.whaletodo.app.profile.ProfileException
import io.whaletodo.app.profile.VerificationDispatch
import io.whaletodo.network.ApiException
import io.whaletodo.network.AuthApi
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把现有账号弹窗接到真实邮箱账户 API；不改 UI，只提供 ProfileBridge 实现。 */
class ServerProfileBridge(
    private val auth: AuthApi,
    private val session: AccountSession,
    private val credentials: AccountCredentials,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 提醒 owner 跟随会话代次：callback 必须带上触发它的 sessionId。
     * 用 suspend 让 login 内的“先取消旧 owner、再加载、再启用新 owner”严格按顺序执行，
     * 避免异步 launch 的 guest 回调迟到后把刚加载好的新 owner 又取消掉。
     */
    private val onOwnerChanged: suspend (owner: String?, sessionId: String?) -> Unit = { _, _ -> },
) : ProfileBridge {
    override val details = session.details

    override suspend fun login(email: String, password: String) = call {
        val clean = email.trim()
        try { auth.loginWithEmail(clean, password) }
        catch(e: ApiException) {
            if(e.status==401 && !e.message.contains("账户已切换")) throw ProfileException("邮箱或密码错误，请重新输入")
            throw e
        }
        credentials.saveEmail(clean)
        val expected = credentials.read()?.sessionId ?: return@call
        if (!session.begin(expected)) throw ProfileException("账户已切换，请重试")
        // 新代次尚未就绪：立即取消旧 owner，避免 B 的 /me 失败时提醒仍按 A 排程。
        onOwnerChanged(null, expected)
        try {
            if (!loadAccount(clean, expected)) throw ProfileException("账户已切换，请重试")
            onOwnerChanged(session.access.value.userId, expected)
        } catch (e: ApiException) {
            // 新会话加载遇到 401：清的是 B，不是外层 call 启动时的旧 A。
            if (e.status == 401 || e.code == "UNAUTHENTICATED") clearIfSession(expected)
            throw e
        }
    }

    override suspend fun register(
        email: String,
        code: String,
        password: String,
        nickname: String?,
    ): Unit = call {
        val clean = email.trim()
        val ticket = auth.verifyEmailCode(clean, code, PURPOSE_REGISTER).ticket
        auth.registerWithEmail(clean, ticket, password, nickname)
        Unit
    }

    override suspend fun requestCode(
        email: String,
        purpose: CodePurpose,
    ): VerificationDispatch = call {
        val result = auth.sendEmailCode(email.trim(), purpose.wire())
        val seconds = resendSeconds(result.retryAfterMs)
        if (!result.sent) throw ProfileException("验证码发送过于频繁，请${seconds}秒后再试")
        VerificationDispatch(resendAfterSeconds = seconds)
    }

    override suspend fun resetPassword(
        email: String,
        code: String,
        password: String,
    ) = call {
        val clean = email.trim()
        val expected = credentials.read()?.sessionId
        val ticket = auth.verifyEmailCode(clean, code, PURPOSE_RESET).ticket
        auth.resetPassword(clean, ticket, password)
        clearIfSession(expected)
    }

    override suspend fun signOut() {
        withContext(io) {
            val expected = credentials.read()?.sessionId
            try {
                if (expected != null) auth.logout(expected)
            } catch (_: Exception) {
                // 服务端不可达也要在本机退出；只清理本会话，避免误清 B。
            } finally {
                clearIfSession(expected)
            }
        }
    }

    override suspend fun signIn(): Unit = call {
        val expectedSession = session.currentSessionId() ?: throw ProfileException("请先登录")
        val expectedUser = session.access.value.userId ?: throw ProfileException("请先登录")
        fun current() = credentials.read()?.sessionId == expectedSession &&
            session.currentSessionId() == expectedSession &&
            session.access.value.userId == expectedUser

        val businessDate = try {
            auth.claimDailyReward(UUID.randomUUID().toString()).businessDate
        } catch (e: ApiException) {
            if (e.code != "CONFLICT") throw e
            auth.dailyReward().businessDate
        }
        if (!current()) throw ProfileException("账户已切换，请重试")
        val entitlements = auth.entitlements()
        if (!current()) throw ProfileException("账户已切换，请重试")
        val wallet = auth.wallet()
        if (!current()) throw ProfileException("账户已切换，请重试")
        if (!session.updateBalance(expectedSession, expectedUser, entitlements, wallet.available)) {
            throw ProfileException("账户已切换，请重试")
        }
        parseDate(businessDate)?.let { date ->
            if (!session.markSignedIn(expectedSession, expectedUser, date)) {
                throw ProfileException("账户已切换，请重试")
            }
        }
        Unit
    }

    /** App 启动时若本地仍有令牌，恢复登录态；401 只在会话代次未变时清空。 */
    suspend fun restore() {
        val stored = credentials.read() ?: return
        val expected = stored.sessionId
        val email = credentials.readEmail().orEmpty()
        if (!session.begin(expected)) return
        // 冷启动/离线时故意不在 begin 阶段回调：保留持久化 owner，离线仍按上次账户排程。
        withContext(io) {
            try {
                if (loadAccount(email, expected)) {
                    onOwnerChanged(session.access.value.userId, expected)
                }
            } catch (e: ApiException) {
                if (e.status == 401 || e.code == "UNAUTHENTICATED") {
                    clearIfSession(expected)
                }
            } catch (_: IOException) {
                // 离线启动保留令牌与持久化 owner，下次启动再试。
            }
        }
    }

    private suspend fun loadAccount(email: String, expectedSession: String): Boolean {
        val profile = auth.me()
        if (credentials.read()?.sessionId != expectedSession) return false
        val entitlements = auth.entitlements()
        if (credentials.read()?.sessionId != expectedSession) return false
        val wallet = auth.wallet()
        if (credentials.read()?.sessionId != expectedSession) return false
        return session.update(expectedSession, email, profile, entitlements, wallet.available)
    }

    private suspend fun <T> call(block: suspend () -> T): T = withContext(io) {
        val expected = credentials.read()?.sessionId
        try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401 || e.code == "UNAUTHENTICATED") {
                clearIfSession(expected)
            }
            throw ProfileException(friendly(e))
        } catch (_: IOException) {
            throw ProfileException("网络连接失败，请检查网络后重试")
        }
    }

    /**
     * 只有当前会话仍是发起操作时的会话（或令牌已被同一会话的刷新失败清空）才清。
     * 期间切到 B 时不动 B 的 TokenStore/AccountSession。
     */
    private suspend fun clearIfSession(expected: String?) {
        val cleared = expected != null && credentials.clearIfCurrent(expected)
        if (cleared || credentials.read() == null) {
            val current = session.currentSessionId()
            val uiCleared = current == null || session.clear(expected ?: current)
            if (uiCleared) {
                // 这里可能已经 session.clear 成 null；callback 仍带上被清的代次，
                // 由 OwnerSwitch 在锁内判断“代次已清空时只允许 guest 落地”。
                onOwnerChanged(null, expected ?: session.currentSessionId())
            }
        }
    }

    /** retry_after_ms 向上取整到秒并至少 1 秒：避免服务端返回 0 时按钮立刻可再点。 */
    private fun resendSeconds(retryAfterMs: Long): Int =
        ((retryAfterMs + 999) / 1000).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private fun friendly(e: ApiException): String = when (e.code) {
        "UNAUTHENTICATED" -> if (e.message.contains("账户已切换")) {
            "账户已切换，请重试"
        } else {
            "登录已过期，请重新登录"
        }
        "RATE_LIMITED" -> "操作过于频繁，请稍后再试"
        "CONFLICT" -> "该邮箱已被注册"
        "VALIDATION_ERROR" -> e.message.takeIf { it.isNotBlank() } ?: "请检查输入信息"
        "INTERNAL" -> "服务暂时不可用，请稍后重试"
        else -> "暂时无法完成，请稍后重试"
    }

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value) }.getOrNull()

    private fun CodePurpose.wire(): String = when (this) {
        CodePurpose.REGISTER -> PURPOSE_REGISTER
        CodePurpose.RESET_PASSWORD -> PURPOSE_RESET
    }

    private companion object {
        const val PURPOSE_REGISTER = "register"
        const val PURPOSE_RESET = "reset"
    }
}
