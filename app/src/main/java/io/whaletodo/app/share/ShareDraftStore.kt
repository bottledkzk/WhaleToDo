package io.whaletodo.app.share

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable share-copy draft; contains no source text, token, email or verification code. */
internal data class ShareDraft(
    val ownerId: String,
    val sourceFingerprint: String,
    val suggestions: List<String>,
    val selectedText: String?,
    val editedText: String?,
    val styleCode: String,
    val requestId: String,
    val usage: Int?,
    val chargedPoints: Int?,
    val updatedAt: Long,
) {
    fun isValid(): Boolean =
        ownerId.isNotBlank() &&
            sourceFingerprint.isNotBlank() &&
            requestId.isNotBlank() &&
            suggestions.isNotEmpty()

    companion object {
        /** Hash only; the source text itself is never persisted. */
        fun fingerprint(sourceText: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(sourceText.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal interface ShareDraftStore {
    fun load(ownerId: String, sourceFingerprint: String): ShareDraft?
    fun save(draft: ShareDraft)
    fun clear(ownerId: String, sourceFingerprint: String)
}

internal object NoopShareDraftStore : ShareDraftStore {
    override fun load(ownerId: String, sourceFingerprint: String): ShareDraft? = null
    override fun save(draft: ShareDraft) = Unit
    override fun clear(ownerId: String, sourceFingerprint: String) = Unit
}

/**
 * Per-owner + source-fingerprint SharedPreferences store. commit() is intentional: a success
 * receipt must be durable before its pending row is removed.
 */
internal class SharedPreferencesShareDraftStore(
    context: Context,
    private val commit: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) : ShareDraftStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(ownerId: String, sourceFingerprint: String): ShareDraft? {
        val raw = prefs.getString(key(ownerId, sourceFingerprint), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val suggestions = json.getJSONArray("suggestions").let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            ShareDraft(
                ownerId = json.getString("ownerId"),
                sourceFingerprint = json.getString("sourceFingerprint"),
                suggestions = suggestions,
                selectedText = json.optStringOrNull("selectedText"),
                editedText = json.optStringOrNull("editedText"),
                styleCode = json.getString("styleCode"),
                requestId = json.getString("requestId"),
                usage = json.optIntOrNull("usage"),
                chargedPoints = json.optIntOrNull("chargedPoints"),
                updatedAt = json.getLong("updatedAt"),
            ).takeIf {
                it.ownerId == ownerId &&
                    it.sourceFingerprint == sourceFingerprint &&
                    it.isValid()
            }
        }.getOrNull()
    }

    override fun save(draft: ShareDraft) {
        require(draft.isValid())
        val json = JSONObject()
            .put("ownerId", draft.ownerId)
            .put("sourceFingerprint", draft.sourceFingerprint)
            .put("suggestions", JSONArray(draft.suggestions))
            .put("selectedText", draft.selectedText ?: JSONObject.NULL)
            .put("editedText", draft.editedText ?: JSONObject.NULL)
            .put("styleCode", draft.styleCode)
            .put("requestId", draft.requestId)
            .put("usage", draft.usage ?: JSONObject.NULL)
            .put("chargedPoints", draft.chargedPoints ?: JSONObject.NULL)
            .put("updatedAt", draft.updatedAt)
        val ok = commit(
            prefs.edit()
                .putString(key(draft.ownerId, draft.sourceFingerprint), json.toString()),
        )
        if (!ok) throw IllegalStateException("SHARE_DRAFT_NOT_PERSISTED")
    }

    override fun clear(ownerId: String, sourceFingerprint: String) {
        val ok = commit(prefs.edit().remove(key(ownerId, sourceFingerprint)))
        if (!ok) throw IllegalStateException("SHARE_DRAFT_NOT_CLEARED")
    }

    private fun key(ownerId: String, sourceFingerprint: String) =
        "owner:$ownerId:source:$sourceFingerprint"

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private companion object {
        const val PREFS_NAME = "share_ai_drafts"
    }
}
