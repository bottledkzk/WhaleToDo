package io.whaletodo.app.share

import android.content.Context
import org.json.JSONObject

/** Only the immutable request identity/state; never source text or generated copy. */
internal data class SharePending(
    val requestId: String,
    val ownerId: String,
    val styleCode: String,
    val state: String,
    val createdAt: Long,
    val sourceFingerprint: String = "",
)

internal interface SharePendingStore {
    fun load(ownerId: String): SharePending?
    fun save(pending: SharePending)
    /** Removes only the matching request when requestId is supplied. */
    fun clear(ownerId: String, requestId: String? = null)
}

/**
 * Per-owner SharedPreferences store. commit() is intentional: the pending ID must be
 * durable before the network request is sent, even if the process is killed immediately.
 */
internal class SharedPreferencesSharePendingStore(context: Context) : SharePendingStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(ownerId: String): SharePending? {
        val raw = prefs.getString(key(ownerId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SharePending(
                requestId = json.getString("requestId"),
                ownerId = json.getString("ownerId"),
                styleCode = json.getString("styleCode"),
                state = json.getString("state"),
                createdAt = json.getLong("createdAt"),
                sourceFingerprint = json.optString("sourceFingerprint", ""),
            ).takeIf { it.ownerId == ownerId && it.requestId.isNotBlank() }
        }.getOrNull()
    }

    override fun save(pending: SharePending) {
        val json = JSONObject()
            .put("requestId", pending.requestId)
            .put("ownerId", pending.ownerId)
            .put("styleCode", pending.styleCode)
            .put("state", pending.state)
            .put("createdAt", pending.createdAt)
            .put("sourceFingerprint", pending.sourceFingerprint)
        val ok = prefs.edit().putString(key(pending.ownerId), json.toString()).commit()
        if (!ok) throw IllegalStateException("SHARE_PENDING_NOT_PERSISTED")
    }

    override fun clear(ownerId: String, requestId: String?) {
        val current = load(ownerId)
        if (requestId == null || current?.requestId == requestId) {
            prefs.edit().remove(key(ownerId)).commit()
        }
    }

    private fun key(ownerId: String) = "owner:$ownerId"

    private companion object {
        const val PREFS_NAME = "share_ai_pending"
    }
}
