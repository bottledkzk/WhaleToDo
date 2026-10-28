package io.whaletodo.app.share

import io.whaletodo.app.agent.AgentBridge
import io.whaletodo.app.agent.AgentBridgeHub
import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.app.agent.AssistantRequest
import io.whaletodo.app.agent.ShareStyle
import io.whaletodo.core.AgentMode
import io.whaletodo.core.AgentPolicy
import io.whaletodo.core.GateReason
import java.util.UUID

/**
 * share_copy mode through the already-authenticated AgentBridge.
 * No direct HTTP, no local points deduction; access/models/gate are checked before reply.
 */
internal class AgentShareCopyProvider(
    private val bridge: AgentBridge = AgentBridgeHub.bridge,
) : ShareCopyProvider {
    override suspend fun suggest(style: ShareStyle, sourceText: String): List<String> {
        val access = bridge.access.value
        val ownerId = access.userId?.takeIf { it.isNotBlank() }
            ?: throw AgentGateException(GateReason.LOGIN)
        val model = AgentPolicy.route(bridge.models, access, AgentMode.SHARE_COPY)
        AgentPolicy.gate(access, AgentMode.SHARE_COPY, model, consent = false)?.let {
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
        val reply = bridge.reply(request)
        val suggestions = parseShareCopySuggestions(reply.text)
        if (suggestions.isEmpty()) throw AgentServiceException(false)
        return suggestions
    }

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
