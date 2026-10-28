package io.whaletodo.app.share

import io.whaletodo.app.agent.AgentBridge
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.app.agent.AssistantRequest
import io.whaletodo.app.agent.ShareStyle
import io.whaletodo.core.AgentMode
import io.whaletodo.core.AgentPolicy
import io.whaletodo.core.GateReason
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * share_copy mode through the already-authenticated AgentBridge.
 * No direct HTTP, no local points deduction; access/models/gate are checked before reply.
 * The request ID is persisted before the first POST so reopen/restart can query the same ID.
 */
internal class AgentShareCopyProvider(
    private val store: SharePendingStore,
    private val bridge: AgentBridge = AgentBridgeHub.bridge,
) : ShareCopyProvider {
    override suspend fun suggest(style: ShareStyle, sourceText: String): List<String> {
        val access = bridge.access.value
        val ownerId = access.userId?.takeIf { it.isNotBlank() }
            ?: throw AgentGateException(GateReason.LOGIN)

        val existing = loadPending(ownerId)
        if (existing != null) {
            if (existing.state == "pending") throw AgentPendingException(existing.requestId)
            clearPending(ownerId)
        }

        val model = AgentPolicy.route(bridge.models, access, AgentMode.SHARE_COPY)
        AgentPolicy.gate(access, AgentMode.SHARE_COPY, model, consent = false)?.let {
            // This is a local pre-send gate: nothing was saved/never sent; do not touch an
            // existing owner's pending record.
            throw AgentGateException(it)
        }
        val prompt = sourceText.trim()
        if (prompt.isEmpty()) throw AgentServiceException(false)

        val request = AssistantRequest(
            id = UUID.randomUUID().toString(),
            mode = AgentMode.SHARE_COPY.code,
            model = model.code,
            // Server prompt limit is 16000; keep the request valid without inventing local billing.
            prompt = prompt.take(MAX_PROMPT_CHARS),
            context = "",
            editConsent = false,
            shareStyle = style.code,
            history = emptyList(),
            ownerId = ownerId,
        )
        // commit() must complete before bridge.reply; otherwise a kill could lose the ID.
        savePending(
            SharePending(
                requestId = request.id,
                ownerId = ownerId,
                styleCode = style.code,
                state = "pending",
                createdAt = System.currentTimeMillis(),
            ),
        )

        val reply = try {
            bridge.reply(request)
        } catch (e: AgentPendingException) {
            // Keep the durable record; use the ID we persisted, not a replacement.
            throw AgentPendingException(request.id)
        } catch (e: AgentGateException) {
            // A gate can be raised after reply reached the server (session changed), or
            // before any POST (connection/login/points). Only the latter may clear.
            if (e.definitelyNotSent) clearPending(ownerId)
            throw e
        } catch (e: AgentServiceException) {
            // definitelyNotSent is set only for an explicit pre-acceptance rejection.
            // A post-submit service failure (including definitelyRejected) keeps the ID.
            if (e.definitelyNotSent) clearPending(ownerId)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AgentPendingException(request.id)
        }

        // The request is settled once reply returns; parsing failure must not leave a phantom pending.
        clearPending(ownerId)
        val suggestions = parseShareCopySuggestions(reply.text)
        if (suggestions.isEmpty()) throw AgentServiceException(false)
        return suggestions
    }

    override suspend fun pending(ownerId: String): String? {
        val entry = loadPending(ownerId) ?: return null
        if (entry.state != "pending") {
            clearPending(ownerId)
            return null
        }
        return entry.requestId
    }

    override suspend fun recover(ownerId: String): List<String>? {
        val entry = loadPending(ownerId) ?: return null
        if (entry.state != "pending") {
            // Do not let another active owner clear a non-current owner's record.
            if (bridge.access.value.userId == ownerId) clearPending(ownerId)
            return null
        }
        // Query only while A is current; the bridge also binds expectedOwner for the IO race.
        if (bridge.access.value.userId != ownerId) {
            throw AgentPendingException(entry.requestId)
        }
        val reply = try {
            // Query only; never POST a second model request.
            bridge.query(entry.requestId, ownerId)
        } catch (e: AgentPendingException) {
            throw AgentPendingException(entry.requestId)
        } catch (e: AgentGateException) {
            // Authentication/ability gates do not prove the request failed; keep it for retry.
            throw e
        } catch (e: AgentServiceException) {
            if (e.definitelyRejected) {
                // A 403/failed is a terminal signal only for the owner that issued the query.
                if (bridge.access.value.userId != ownerId) {
                    throw AgentPendingException(entry.requestId)
                }
                clearPending(ownerId)
                return null
            }
            throw AgentPendingException(entry.requestId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AgentPendingException(entry.requestId)
        }

        // Owner can change between query return and persistence; do not clear A's record.
        if (bridge.access.value.userId != ownerId) {
            throw AgentPendingException(entry.requestId)
        }
        clearPending(ownerId)
        val suggestions = parseShareCopySuggestions(reply.text)
        if (suggestions.isEmpty()) throw AgentServiceException(false)
        return suggestions
    }

    private suspend fun loadPending(ownerId: String): SharePending? =
        withContext(Dispatchers.IO) { store.load(ownerId) }

    private suspend fun savePending(pending: SharePending) =
        withContext(Dispatchers.IO) { store.save(pending) }

    private suspend fun clearPending(ownerId: String) =
        withContext(Dispatchers.IO) { store.clear(ownerId) }

    private companion object {
        const val MAX_PROMPT_CHARS = 16_000
    }
}

private val BULLET_PREFIX = Regex("""^\s*(?:[-*•·]|\d{1,2}[.、)）]|[（(]\d{1,2}[)）])\s*""")
private val QUOTE_CHARS = charArrayOf('"', '\'', '“', '”', '‘', '’', '「', '」')

/**
 * Split the model's short lines into at most three suggestions.
 * Strip common numbering/bullets/quotes; drop blank, duplicate and >50-codepoint lines.
 * Overlong lines are dropped (not silently truncated), so the UI never shows a half sentence.
 */
internal fun parseShareCopySuggestions(raw: String): List<String> =
    raw.lineSequence()
        .map { it.trim() }
        .map { BULLET_PREFIX.replace(it, "").trim().trim(*QUOTE_CHARS).trim() }
        .filter { it.isNotEmpty() && it.codePointCount(0, it.length) <= 50 }
        .distinct()
        .take(3)
        .toList()
