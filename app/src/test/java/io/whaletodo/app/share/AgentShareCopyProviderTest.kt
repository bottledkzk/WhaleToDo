package io.whaletodo.app.share

import io.whaletodo.app.agent.AgentBridge
import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.app.agent.AssistantReply
import io.whaletodo.app.agent.AssistantRequest
import io.whaletodo.app.agent.ShareStyle
import io.whaletodo.core.AgentAccess
import io.whaletodo.core.AgentMode
import io.whaletodo.core.AgentModel
import io.whaletodo.core.GateReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShareCopyProviderTest {
    private val model = AgentModel("deepseek-default", "鲸鱼AI")
    private val normalAccess = AgentAccess(userId = "owner-1", availablePoints = 10, connected = true)

    private class FakeBridge(
        initial: AgentAccess,
        override val models: List<AgentModel>,
        private val replyWith: suspend (AssistantRequest) -> AssistantReply,
    ) : AgentBridge {
        override val access = MutableStateFlow(initial)
        var calls = 0
        var lastRequest: AssistantRequest? = null

        override suspend fun reply(request: AssistantRequest): AssistantReply {
            calls++
            lastRequest = request
            return replyWith(request)
        }
    }

    private fun provider(
        access: AgentAccess = normalAccess,
        models: List<AgentModel> = listOf(model),
        reply: (AssistantRequest) -> AssistantReply = { AssistantReply("第一句\n第二句\n第三句", 3) },
    ): Pair<AgentShareCopyProvider, FakeBridge> {
        val bridge = FakeBridge(access, models) { reply(it) }
        return AgentShareCopyProvider(bridge) to bridge
    }

    @Test
    fun `unauthenticated access is gated without calling reply`() = runBlocking {
        val (provider, bridge) = provider(access = AgentAccess())
        val error = assertThrows(AgentGateException::class.java) {
            runBlocking { provider.suggest(ShareStyle.MINIMAL, "分享文字") }
        }
        assertEquals(GateReason.LOGIN, error.reason)
        assertEquals(0, bridge.calls)
    }

    @Test
    fun `zero points and disconnected access are gated without reply`() = runBlocking {
        val (pointsProvider, pointsBridge) = provider(
            access = normalAccess.copy(availablePoints = 0),
        )
        assertEquals(
            GateReason.POINTS,
            assertThrows(AgentGateException::class.java) {
                runBlocking { pointsProvider.suggest(ShareStyle.MINIMAL, "分享文字") }
            }.reason,
        )
        assertEquals(0, pointsBridge.calls)

        val (offlineProvider, offlineBridge) = provider(
            access = normalAccess.copy(connected = false),
        )
        assertEquals(
            GateReason.CONNECTION,
            assertThrows(AgentGateException::class.java) {
                runBlocking { offlineProvider.suggest(ShareStyle.MINIMAL, "分享文字") }
            }.reason,
        )
        assertEquals(0, offlineBridge.calls)
    }

    @Test
    fun `normal reply builds a share_copy request and returns three cleaned suggestions`() = runBlocking {
        val (provider, bridge) = provider(reply = {
            AssistantReply("1. 第一句\n2. “第二句”\n- 第三句\n\n", 20)
        })

        val result = provider.suggest(ShareStyle.LITERARY, "源文本")

        assertEquals(listOf("第一句", "第二句", "第三句"), result)
        assertEquals(1, bridge.calls)
        val request = bridge.lastRequest!!
        assertEquals(AgentMode.SHARE_COPY.code, request.mode)
        assertEquals(model.code, request.model)
        assertEquals(ShareStyle.LITERARY.code, request.shareStyle)
        assertEquals("源文本", request.prompt)
        assertEquals("", request.context)
        assertFalse(request.editConsent)
        assertTrue(request.history.isEmpty())
        assertEquals("owner-1", request.ownerId)
    }

    @Test
    fun `empty reply text is a service failure`() = runBlocking {
        val (provider, _) = provider(reply = { AssistantReply(" \n\n", 1) })
        assertThrows(AgentServiceException::class.java) {
            runBlocking { provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        Unit
    }

    @Test
    fun `pending is propagated unchanged and is not converted to a normal list`() = runBlocking {
        val (provider, bridge) = provider(reply = { throw AgentPendingException("req-42") })
        val error = assertThrows(AgentPendingException::class.java) {
            runBlocking { provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        assertEquals("req-42", error.requestId)
        assertEquals(1, bridge.calls)
    }

    @Test
    fun `long source text is bounded to the server prompt limit`() = runBlocking {
        val (provider, bridge) = provider()
        provider.suggest(ShareStyle.MINIMAL, "字".repeat(16_001))
        assertEquals(16_000, bridge.lastRequest!!.prompt.length)
    }

    @Test
    fun `parser strips numbering bullets and quotes`() {
        assertEquals(
            listOf("第一句", "第二句", "第三句"),
            parseShareCopySuggestions("1. 第一句\n2、“第二句”\n- 「第三句」\n\n"),
        )
    }

    @Test
    fun `parser drops empty overlong duplicate and keeps at most three`() {
        val long = "长".repeat(51)
        assertEquals(
            listOf("短句"),
            parseShareCopySuggestions("1. 短句\n2. 短句\n3. $long\n\n"),
        )
        assertEquals(
            listOf("一", "二", "三"),
            parseShareCopySuggestions("1. 一\n2. 二\n3. 三\n4. 四"),
        )
    }
}
