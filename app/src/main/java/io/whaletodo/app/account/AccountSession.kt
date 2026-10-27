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
class AccountSession {
    private val lock = Any()
    private val mutableAccess = MutableStateFlow(AgentAccess())
    val access: StateFlow<AgentAccess> = mutableAccess.asStateFlow()

    private val mutableDetails = MutableStateFlow<ProfileDetails?>(null)
    val details: StateFlow<ProfileDetails?> = mutableDetails.asStateFlow()

    private val mutableEmail = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = mutableEmail.asStateFlow()

    fun update(
        email: String,
        profile: AccountProfile,
        entitlements: Entitlements,
        availablePoints: Int,
    ) = synchronized(lock) {
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
    }

    /** 只有当前仍是 expectedUserId 时写余额/VIP；返回是否写入。 */
    fun updateBalance(
        expectedUserId: String,
        entitlements: Entitlements,
        availablePoints: Int,
    ): Boolean = synchronized(lock) {
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

    /** 只有当前仍是 expectedUserId 时标记签到；返回是否写入。 */
    fun markSignedIn(expectedUserId: String, date: LocalDate): Boolean = synchronized(lock) {
        val current = mutableDetails.value
        if (current?.userId != expectedUserId) return@synchronized false
        mutableDetails.value = current.copy(
            signInDates = current.signInDates + date,
            businessDate = date,
        )
        true
    }

    fun clear() = synchronized(lock) {
        mutableEmail.value = null
        mutableDetails.value = null
        mutableAccess.value = AgentAccess()
    }
}
