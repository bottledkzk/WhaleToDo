package io.whaletodo.app.account

import io.whaletodo.app.profile.ProfileDetails
import io.whaletodo.core.AgentAccess
import io.whaletodo.network.AccountProfile
import io.whaletodo.network.Entitlements
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 登录态的唯一真源：AgentAccess 与 ProfileBridge.details 都由这里同步更新。 */
class AccountSession(
    private val credentialSessionId: () -> String? = { null },
) {
    private val lock = Any()
    private var sessionId: String? = null
    private val mutableAccess = MutableStateFlow(AgentAccess())
    val access: StateFlow<AgentAccess> = mutableAccess.asStateFlow()

    private val mutableDetails = MutableStateFlow<ProfileDetails?>(null)
    val details: StateFlow<ProfileDetails?> = mutableDetails.asStateFlow()

    private val mutableEmail = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = mutableEmail.asStateFlow()

    fun currentSessionId(): String? = synchronized(lock) { sessionId }

    /** 把 UI 会话切到 expected；TokenStore 已换号则拒绝。 */
    fun begin(expected: String): Boolean = synchronized(lock) {
        if (credentialSessionId() != expected) return@synchronized false
        sessionId = expected
        true
    }

    fun update(
        expectedSession: String,
        email: String,
        profile: AccountProfile,
        entitlements: Entitlements,
        availablePoints: Int,
    ): Boolean = synchronized(lock) {
        if (!sameGeneration(expectedSession)) return@synchronized false
        val previous = mutableDetails.value?.takeIf { it.userId == profile.id }
        mutableEmail.value = email
        mutableDetails.value = ProfileDetails(
            userId = profile.id,
            displayName = profile.displayName.orEmpty(),
            publicId = profile.username,
            email = email,
            signInDates = previous?.signInDates.orEmpty(),
            businessDate = previous?.businessDate ?: LocalDate.now(),
            vipExpiresAt = entitlements.vipExpiresAt,
        )
        mutableAccess.value = AgentAccess(
            userId = profile.id,
            vip = entitlements.vipActive,
            availablePoints = availablePoints,
            connected = true,
        )
        true
    }

    /** 只有当前仍是 expectedSession/expectedUserId 时写余额/VIP；返回是否写入。 */
    fun updateBalance(
        expectedSession: String,
        expectedUserId: String,
        entitlements: Entitlements,
        availablePoints: Int,
    ): Boolean = synchronized(lock) {
        if (!sameGeneration(expectedSession)) return@synchronized false
        val current = mutableAccess.value
        if (current.userId != expectedUserId) return@synchronized false
        mutableAccess.value = current.copy(
            vip = entitlements.vipActive,
            availablePoints = availablePoints,
        )
        mutableDetails.value = mutableDetails.value?.takeIf { it.userId == expectedUserId }?.let {
            it.copy(vipExpiresAt = entitlements.vipExpiresAt)
        } ?: mutableDetails.value
        true
    }

    /** 只有当前仍是 expectedSession/expectedUserId 时标记签到；返回是否写入。 */
    fun markSignedIn(
        expectedSession: String,
        expectedUserId: String,
        date: LocalDate,
    ): Boolean = synchronized(lock) {
        if (!sameGeneration(expectedSession)) return@synchronized false
        val current = mutableDetails.value
        if (current?.userId != expectedUserId) return@synchronized false
        mutableDetails.value = current.copy(
            signInDates = current.signInDates + date,
            businessDate = date,
        )
        true
    }

    /** 只有 UI 会话仍匹配时才清空；返回是否清空。 */
    fun clear(expectedSession: String): Boolean = synchronized(lock) {
        if (sessionId != expectedSession) return@synchronized false
        mutableEmail.value = null
        mutableDetails.value = null
        mutableAccess.value = AgentAccess()
        sessionId = null
        true
    }

    /** UI 代次与 TokenStore 都必须仍是 expected，避免 B 已保存令牌但尚未 begin 时写 A。 */
    private fun sameGeneration(expected: String): Boolean =
        sessionId == expected && credentialSessionId() == expected
}
