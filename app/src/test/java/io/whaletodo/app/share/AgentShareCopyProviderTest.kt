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
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShareCopyProviderTest {
    private val model = AgentModel("deepseek-default", "鲸鱼AI")
    private val normalAccess = AgentAccess(userId = "owner-1", availablePoints = 10, connected = true)

    private class FakeStore : SharePendingStore {
        val values = mutableMapOf<String, SharePending>()
        var afterLoad: (() -> Unit)? = null
        override fun load(ownerId: String): SharePending? {
            val value = values[ownerId]
            afterLoad?.invoke()
            return value
        }
        override fun save(pending: SharePending) {
            values[pending.ownerId] = pending
        }
        override fun clear(ownerId: String) {
            values.remove(ownerId)
        }
    }

    private class FakeBridge(
        initial: AgentAccess,
        override val models: List<AgentModel>,
        private val replyWith: suspend (AssistantRequest) -> AssistantReply,
        private val queryWith: suspend (String) -> AssistantReply,
    ) : AgentBridge {
        override val access = MutableStateFlow(initial)
        var replyCalls = 0
        var queryCalls = 0
        var lastRequest: AssistantRequest? = null
        var lastQueryId: String? = null

        override suspend fun reply(request: AssistantRequest): AssistantReply {
            replyCalls++
            lastRequest = request
            return replyWith(request)
        }

        override suspend fun query(requestId: String): AssistantReply {
            queryCalls++
            lastQueryId = requestId
            return queryWith(requestId)
        }
    }

    private data class Harness(
        val provider: AgentShareCopyProvider,
        val bridge: FakeBridge,
        val store: FakeStore,
    )

    private fun successReply(): AssistantReply = AssistantReply("第一句\n第二句\n第三句", 3)

    private fun harness(
        access: AgentAccess = normalAccess,
        store: FakeStore = FakeStore(),
        models: List<AgentModel> = listOf(model),
        reply: suspend (AssistantRequest) -> AssistantReply = { successReply() },
        query: suspend (String) -> AssistantReply = { successReply() },
    ): Harness {
        val bridge = FakeBridge(access, models, reply, query)
        return Harness(AgentShareCopyProvider(store, bridge), bridge, store)
    }

    private fun pendingHarness(
        query: suspend (String) -> AssistantReply = { successReply() },
    ): Harness {
        val harness = harness(
            reply = { request -> throw AgentPendingException(request.id) },
            query = query,
        )
        assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        return harness
    }

    @Test
    fun `unauthenticated access is gated without calling reply`() {
        val harness = harness(access = AgentAccess())
        val error = assertThrows(AgentGateException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "分享文字") }
        }
        assertEquals(GateReason.LOGIN, error.reason)
        assertEquals(0, harness.bridge.replyCalls)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `zero points and disconnected access are gated without reply`() {
        val points = harness(access = normalAccess.copy(availablePoints = 0))
        assertEquals(
            GateReason.POINTS,
            assertThrows(AgentGateException::class.java) {
                runBlocking { points.provider.suggest(ShareStyle.MINIMAL, "分享文字") }
            }.reason,
        )
        assertEquals(0, points.bridge.replyCalls)

        val offline = harness(access = normalAccess.copy(connected = false))
        assertEquals(
            GateReason.CONNECTION,
            assertThrows(AgentGateException::class.java) {
                runBlocking { offline.provider.suggest(ShareStyle.MINIMAL, "分享文字") }
            }.reason,
        )
        assertEquals(0, offline.bridge.replyCalls)
    }

    @Test
    fun `normal reply builds a share_copy request and returns three cleaned suggestions`() {
        val harness = harness(reply = {
            AssistantReply("1. 第一句\n2. “第二句”\n- 第三句\n\n", 20)
        })
        val result = runBlocking { harness.provider.suggest(ShareStyle.LITERARY, "源文本") }

        assertEquals(listOf("第一句", "第二句", "第三句"), result)
        assertEquals(1, harness.bridge.replyCalls)
        assertEquals(0, harness.bridge.queryCalls)
        assertNull(harness.store.load("owner-1"))
        val request = harness.bridge.lastRequest!!
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
    fun `empty reply text is a service failure and clears pending`() {
        val harness = harness(reply = { AssistantReply(" \n\n", 1) })
        assertThrows(AgentServiceException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `pending saves before reply, second suggest reuses the same ID without reply`() {
        val store = FakeStore()
        var savedBeforeReply = false
        val harness = harness(
            store = store,
            reply = { request ->
                savedBeforeReply = store.load("owner-1")?.requestId == request.id
                throw AgentPendingException(request.id)
            },
        )
        val first = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        val saved = store.load("owner-1")
        assertNotNull(saved)
        assertEquals(saved!!.requestId, first.requestId)
        assertTrue(savedBeforeReply)
        assertEquals(1, harness.bridge.replyCalls)

        val second = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "再次生成") }
        }
        assertEquals(saved.requestId, second.requestId)
        assertEquals(1, harness.bridge.replyCalls)
        assertEquals(saved.requestId, runBlocking { harness.provider.pending("owner-1") })
    }

    @Test
    fun `recover queries once, returns the original result and clears pending`() {
        val harness = pendingHarness(query = { successReply() })
        val id = harness.store.load("owner-1")!!.requestId

        val result = runBlocking { harness.provider.recover("owner-1") }

        assertEquals(listOf("第一句", "第二句", "第三句"), result)
        assertEquals(1, harness.bridge.replyCalls)
        assertEquals(1, harness.bridge.queryCalls)
        assertEquals(id, harness.bridge.lastQueryId)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `recover 429, 5xx and IO retain pending and never POST`() {
        val cases = listOf<suspend (String) -> AssistantReply>(
            { id -> throw AgentPendingException(id) },
            { _ -> throw AgentServiceException(false) },
            { _ -> throw IOException("synthetic network failure") },
        )
        cases.forEach { query ->
            val harness = pendingHarness(query = query)
            val id = harness.store.load("owner-1")!!.requestId
            val error = assertThrows(AgentPendingException::class.java) {
                runBlocking { harness.provider.recover("owner-1") }
            }
            assertEquals(id, error.requestId)
            assertEquals(id, harness.store.load("owner-1")?.requestId)
            assertEquals(1, harness.bridge.replyCalls)
            assertEquals(1, harness.bridge.queryCalls)
        }
    }

    @Test
    fun `definite failed clears pending and allows a new request`() {
        val store = FakeStore()
        var first = true
        val harness = harness(
            store = store,
            reply = { request ->
                if (first) {
                    first = false
                    throw AgentPendingException(request.id)
                }
                successReply()
            },
            query = { throw AgentServiceException(true) },
        )
        val pending = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        val oldId = pending.requestId

        assertNull(runBlocking { harness.provider.recover("owner-1") })
        assertNull(store.load("owner-1"))

        val result = runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "新请求") }
        assertEquals(3, result.size)
        assertEquals(2, harness.bridge.replyCalls)
        assertNull(store.load("owner-1"))
        assertNotNull(harness.bridge.lastRequest)
        assertFalse(oldId == harness.bridge.lastRequest!!.id)
    }

    @Test
    fun `owner isolation keeps A pending and lets B create independently`() {
        val store = FakeStore()
        val ownerA = harness(
            access = normalAccess.copy(userId = "owner-a"),
            store = store,
            reply = { request -> throw AgentPendingException(request.id) },
        )
        val pendingA = assertThrows(AgentPendingException::class.java) {
            runBlocking { ownerA.provider.suggest(ShareStyle.MINIMAL, "A 源文本") }
        }

        val ownerB = harness(
            access = normalAccess.copy(userId = "owner-b"),
            store = store,
            reply = { successReply() },
        )
        assertNull(runBlocking { ownerB.provider.pending("owner-b") })
        val bResult = runBlocking { ownerB.provider.suggest(ShareStyle.MINIMAL, "B 源文本") }
        assertEquals(3, bResult.size)
        assertNotNull(store.load("owner-a"))
        assertEquals(pendingA.requestId, store.load("owner-a")?.requestId)
        assertNull(store.load("owner-b"))
    }

    @Test
    fun `new provider with the same store recovers the original ID`() {
        val first = pendingHarness()
        val id = first.store.load("owner-1")!!.requestId

        val restarted = harness(store = first.store)
        assertEquals(id, runBlocking { restarted.provider.pending("owner-1") })
        val result = runBlocking { restarted.provider.recover("owner-1") }
        assertEquals(3, result?.size)
        assertEquals(1, restarted.bridge.queryCalls)
        assertEquals(0, restarted.bridge.replyCalls)
        assertNull(first.store.load("owner-1"))
    }

    @Test
    fun `pre-send connection failure with definitelyNotSent clears pending`() {
        val harness = harness(
            reply = { throw AgentGateException(GateReason.CONNECTION, definitelyNotSent = true) },
        )
        assertThrows(AgentGateException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        assertEquals(1, harness.bridge.replyCalls)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `explicit login points and rate-limit rejections clear pending`() {
        val failures = listOf<Exception>(
            AgentGateException(GateReason.LOGIN, definitelyNotSent = true),
            AgentGateException(GateReason.POINTS, definitelyNotSent = true),
            AgentServiceException(true, definitelyNotSent = true),
        )
        failures.forEach { failure ->
            val harness = harness(reply = { throw failure })
            assertThrows(Exception::class.java) {
                runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
            }
            assertEquals(1, harness.bridge.replyCalls)
            assertNull("failure=$failure", harness.store.load("owner-1"))
        }
    }

    @Test
    fun `post-submit disconnect keeps original id and recover does not POST`() {
        val harness = pendingHarness()
        val id = harness.store.load("owner-1")!!.requestId

        val result = runBlocking { harness.provider.recover("owner-1") }

        assertEquals(3, result?.size)
        assertEquals(id, harness.bridge.lastQueryId)
        assertEquals(1, harness.bridge.replyCalls)
        assertEquals(1, harness.bridge.queryCalls)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `accepted LOGIN keeps original id and recover queries it after A returns`() {
        val store = FakeStore()
        lateinit var bridgeRef: FakeBridge
        val harness = harness(
            store = store,
            reply = {
                // reply may reach the server and then find the session/owner changed.
                bridgeRef.access.value = normalAccess.copy(userId = "owner-b")
                throw AgentGateException(GateReason.LOGIN, definitelyNotSent = false)
            },
        )
        bridgeRef = harness.bridge
        assertThrows(AgentGateException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        val id = store.load("owner-1")!!.requestId
        assertEquals(1, harness.bridge.replyCalls)

        harness.bridge.access.value = normalAccess
        val result = runBlocking { harness.provider.recover("owner-1") }

        assertEquals(3, result?.size)
        assertEquals(id, harness.bridge.lastQueryId)
        assertEquals(1, harness.bridge.replyCalls)
        assertEquals(1, harness.bridge.queryCalls)
        assertNull(store.load("owner-1"))
    }

    @Test
    fun `accepted service definitelyRejected keeps pending for recover`() {
        val harness = harness(
            reply = { throw AgentServiceException(true, definitelyNotSent = false) },
        )
        assertThrows(AgentServiceException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        val id = harness.store.load("owner-1")?.requestId
        assertNotNull(id)
        assertEquals(1, harness.bridge.replyCalls)

        val result = runBlocking { harness.provider.recover("owner-1") }
        assertEquals(3, result?.size)
        assertEquals(id, harness.bridge.lastQueryId)
        assertEquals(1, harness.bridge.queryCalls)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `pre-send gate writes no pending`() {
        val harness = harness(access = normalAccess.copy(availablePoints = 0))
        assertThrows(AgentGateException::class.java) {
            runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "源文本") }
        }
        assertEquals(0, harness.bridge.replyCalls)
        assertNull(harness.store.load("owner-1"))
    }

    @Test
    fun `recover with mismatched owner does not query or clear A`() {
        val harness = pendingHarness()
        val id = harness.store.load("owner-1")!!.requestId
        harness.bridge.access.value = normalAccess.copy(userId = "owner-b")

        val error = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.recover("owner-1") }
        }

        assertEquals(id, error.requestId)
        assertEquals(0, harness.bridge.queryCalls)
        assertEquals(id, harness.store.load("owner-1")?.requestId)
    }

    @Test
    fun `owner change after load prevents query and clear`() {
        val harness = pendingHarness()
        val id = harness.store.load("owner-1")!!.requestId
        harness.store.afterLoad = {
            harness.bridge.access.value = normalAccess.copy(userId = "owner-b")
        }

        val error = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.recover("owner-1") }
        }

        assertEquals(id, error.requestId)
        assertEquals(0, harness.bridge.queryCalls)
        assertEquals(id, harness.store.load("owner-1")?.requestId)
    }

    @Test
    fun `403 after owner change does not clear A pending`() {
        var bridgeRef: FakeBridge? = null
        val harness = pendingHarness(
            query = {
                bridgeRef?.access?.value = normalAccess.copy(userId = "owner-b")
                throw AgentServiceException(true)
            },
        )
        bridgeRef = harness.bridge
        val id = harness.store.load("owner-1")!!.requestId

        val error = assertThrows(AgentPendingException::class.java) {
            runBlocking { harness.provider.recover("owner-1") }
        }

        assertEquals(id, error.requestId)
        assertEquals(1, harness.bridge.queryCalls)
        assertEquals(id, harness.store.load("owner-1")?.requestId)
    }

    @Test
    fun `definite failed clears only when owner still matches`() {
        val harness = pendingHarness(query = { throw AgentServiceException(true) })
        runBlocking { harness.provider.recover("owner-1") }
        assertNull(harness.store.load("owner-1"))
        assertEquals(1, harness.bridge.queryCalls)
        assertEquals(1, harness.bridge.replyCalls)
    }

    @Test
    fun `long source text is bounded to the server prompt limit`() {
        val harness = harness()
        runBlocking { harness.provider.suggest(ShareStyle.MINIMAL, "字".repeat(16_001)) }
        assertEquals(16_000, harness.bridge.lastRequest!!.prompt.length)
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
