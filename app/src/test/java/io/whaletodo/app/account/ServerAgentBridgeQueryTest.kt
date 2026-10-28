package io.whaletodo.app.account

import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.network.AccountProfile
import io.whaletodo.network.AiApi
import io.whaletodo.network.ApiClient
import io.whaletodo.network.ApiConfig
import io.whaletodo.network.AuthApi
import io.whaletodo.network.Entitlements
import io.whaletodo.network.InMemoryTokenStore
import io.whaletodo.network.TokenPair
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private data class Recorded(val method: String, val path: String, val auth: String?)

/**
 * Minimal HTTP/1.1 server for app JVM tests. The Android unit-test classpath does not expose
 * com.sun.net.httpserver, so this keeps the probe local and dependency-free.
 */
private class TestHttpServer(private val route: (Recorded) -> Pair<Int, String>) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val records = mutableListOf<Recorded>()

    val port: Int get() = socket.localPort
    val calls: List<Recorded> get() = synchronized(records) { records.toList() }

    private val acceptThread = Thread {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { handle(client) }
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                break
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            var auth: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    auth = line.substringAfter(':').trim()
                }
            }
            val record = Recorded(parts[0], parts[1], auth)
            synchronized(records) { records += record }
            val (status, body) = route(record)
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 ").append(status).append(" Synthetic\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("x-request-id: synthetic-query\r\n")
                append("Connection: close\r\n\r\n")
            }
            connection.getOutputStream().use { output ->
                output.write(headers.toByteArray(Charsets.ISO_8859_1))
                output.write(bytes)
                output.flush()
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
        executor.shutdownNow()
    }
}

/** Holds the IO block so a test can switch accounts after owner/session freeze but before HTTP. */
private class GatedDispatcher : CoroutineDispatcher() {
    private val queued = LinkedBlockingQueue<Runnable>()
    private val entered = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.put(block)
        entered.countDown()
    }

    fun awaitDispatch(): Boolean = entered.await(2, TimeUnit.SECONDS)

    fun runNext() {
        queued.take().run()
    }
}

/**
 * Query owner binding against a synthetic local HTTP server.
 * No production server or real credentials are contacted; sessions/tokens are in-memory.
 */
class ServerAgentBridgeQueryTest {
    private lateinit var server: TestHttpServer
    private lateinit var baseUrl: String
    private lateinit var tokens: InMemoryTokenStore
    private lateinit var session: AccountSession
    private lateinit var scope: CoroutineScope
    private lateinit var bridge: ServerAgentBridge
    private var storedSession = "S-A"

    private var queryStatus = 200
    private var queryBody =
        """{"request_id":"r","data":{"id":"$REQUEST_ID","status":"succeeded","text":"done","usage_tokens":3}}"""

    @Before
    fun setUp() {
        server = TestHttpServer { record ->
            when {
                record.path.endsWith("/ai/models") ->
                    200 to """{"request_id":"r","data":{"prompt_version":"test","items":[]}}"""
                record.path.endsWith("/ai/usage") ->
                    200 to """{"request_id":"r","data":{"requests":0}}"""
                record.path.endsWith("/me/entitlements") ->
                    200 to """{"request_id":"r","data":{"active":false,"expires_at":null}}"""
                record.path.endsWith("/wallet") ->
                    200 to """{"request_id":"r","data":{"available":10,"reserved":0}}"""
                record.path.contains("/ai/requests/") -> queryStatus to queryBody
                else -> 404 to """{"request_id":"r","error":{"code":"NOT_FOUND","message":"synthetic"}}"""
            }
        }
        baseUrl = "http://127.0.0.1:${server.port}/api/v1"

        tokens = InMemoryTokenStore(TokenPair("token-a", "refresh-a", "S-A"))
        session = AccountSession { storedSession }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = ApiClient(ApiConfig(baseUrl, allowInsecureHttp = true), tokens)
        bridge = ServerAgentBridge(
            ai = AiApi(client),
            auth = AuthApi(client, tokens),
            session = session,
            scope = scope,
            io = Dispatchers.Unconfined,
        )
        switchTo(owner = "owner-a", sessionId = "S-A", accessToken = "token-a")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun switchTo(owner: String, sessionId: String, accessToken: String) {
        tokens.save(TokenPair(accessToken, "refresh-$sessionId", sessionId))
        storedSession = sessionId
        assertTrue(session.begin(sessionId))
        assertTrue(
            session.update(
                expectedSession = sessionId,
                email = "$owner@example.invalid",
                profile = AccountProfile(owner, "user-$owner", null),
                entitlements = Entitlements(false, null),
                availablePoints = 10,
            ),
        )
    }

    private fun aiQueryCalls(): List<Recorded> =
        server.calls.filter { it.path.contains("/ai/requests/") }

    @Test
    fun `B cannot query A request and A to B to A sends no HTTP`() {
        switchTo(owner = "owner-b", sessionId = "S-B", accessToken = "token-b")

        val fromB = assertThrows(AgentPendingException::class.java) {
            runBlocking { bridge.query(REQUEST_ID, expectedOwner = "owner-a") }
        }
        assertEquals(REQUEST_ID, fromB.requestId)
        assertEquals(0, aiQueryCalls().size)

        // A returns before the provider handles the result: still no request under B.
        switchTo(owner = "owner-a", sessionId = "S-A3", accessToken = "token-a3")
        assertEquals(0, aiQueryCalls().size)
    }

    @Test
    fun `owner switch after pre-IO freeze aborts before HTTP`() {
        val gated = GatedDispatcher()
        val isolatedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        isolatedScope.cancel()
        val client = ApiClient(ApiConfig(baseUrl, allowInsecureHttp = true), tokens)
        val gatedBridge = ServerAgentBridge(
            ai = AiApi(client),
            auth = AuthApi(client, tokens),
            session = session,
            scope = isolatedScope,
            io = gated,
        )

        val deferred = CoroutineScope(Dispatchers.Unconfined).async {
            gatedBridge.query(REQUEST_ID, expectedOwner = "owner-a")
        }
        assertTrue("query did not reach IO", gated.awaitDispatch())

        // The provider already selected A; B becomes active only after the IO block is queued.
        switchTo(owner = "owner-b", sessionId = "S-B", accessToken = "token-b")
        gated.runNext()

        val error = assertThrows(AgentPendingException::class.java) {
            runBlocking { deferred.await() }
        }
        assertEquals(REQUEST_ID, error.requestId)
        assertEquals(0, aiQueryCalls().size)
    }

    @Test
    fun `same owner new session queries with the current session`() {
        switchTo(owner = "owner-a", sessionId = "S-A2", accessToken = "token-a2")

        val reply = runBlocking { bridge.query(REQUEST_ID, expectedOwner = "owner-a") }

        assertEquals("done", reply.text)
        val call = aiQueryCalls().single()
        assertEquals("Bearer token-a2", call.auth)
    }

    @Test
    fun `403 for the same owner is a terminal service rejection`() {
        queryStatus = 403
        queryBody = """{"request_id":"r","error":{"code":"FORBIDDEN","message":"denied"}}"""

        val error = assertThrows(AgentServiceException::class.java) {
            runBlocking { bridge.query(REQUEST_ID, expectedOwner = "owner-a") }
        }

        assertTrue(error.definitelyRejected)
        assertNotNull(aiQueryCalls().single())
    }

    @Test
    fun `429 and 5xx keep the original request pending`() {
        listOf(
            429 to """{"request_id":"r","error":{"code":"RATE_LIMITED","message":"slow"}}""",
            503 to """{"request_id":"r","error":{"code":"UPSTREAM_UNAVAILABLE","message":"down"}}""",
        ).forEach { (status, body) ->
            queryStatus = status
            queryBody = body
            val error = assertThrows(AgentPendingException::class.java) {
                runBlocking { bridge.query(REQUEST_ID, expectedOwner = "owner-a") }
            }
            assertEquals("status=$status", REQUEST_ID, error.requestId)
        }
        assertEquals(2, aiQueryCalls().size)
    }

    private companion object {
        const val REQUEST_ID = "00000000-0000-4000-a000-000000000061"
    }
}
