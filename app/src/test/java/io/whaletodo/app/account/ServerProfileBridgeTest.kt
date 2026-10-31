package io.whaletodo.app.account

import io.whaletodo.app.profile.CodePurpose
import io.whaletodo.app.profile.ProfileException
import io.whaletodo.network.ApiClient
import io.whaletodo.network.ApiConfig
import io.whaletodo.network.AuthApi
import io.whaletodo.network.InMemoryTokenStore
import io.whaletodo.network.TokenPair
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 最小本地 HTTP 服务：只回固定 JSON，用于验证桥接的 sent/限流/网络/401 分支。 */
private class BridgeHttpServer(private val respond: (String) -> Pair<Int, String>) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()

    val baseUrl: String = "http://127.0.0.1:${socket.localPort}/api/v1"

    init {
        Thread {
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
        }.apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val (status, body) = respond(path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 ").append(status).append(" Synthetic\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("x-request-id: synthetic-profile\r\n")
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

/** 只实现桥接需要的凭据面；不落盘、不含真实令牌。 */
private class FakeCredentials(initial: TokenPair? = null) : AccountCredentials {
    private val tokens = InMemoryTokenStore(initial)
    private var email: String? = null

    override fun read(): TokenPair? = tokens.read()
    override fun save(tokens: TokenPair) = this.tokens.save(tokens)
    override fun clear() {
        tokens.clear()
        email = null
    }

    override fun saveIfCurrent(
        expectedSessionId: String,
        expectedRefreshToken: String,
        tokens: TokenPair,
    ): Boolean = this.tokens.saveIfCurrent(expectedSessionId, expectedRefreshToken, tokens)

    override fun clearIfCurrent(expectedSessionId: String): Boolean {
        val cleared = tokens.clearIfCurrent(expectedSessionId)
        if (cleared) email = null
        return cleared
    }

    override fun readEmail(): String? = email
    override fun saveEmail(email: String) {
        this.email = email.trim()
    }
}

/**
 * requestCode 的客户端契约：sent=true 返回 dispatch，sent=false 保留限流提示，
 * 网络/401 仍是原来的 ProfileException 文案。全部走本地合成服务，不连真实服务器。
 */
class ServerProfileBridgeTest {
    private lateinit var server: BridgeHttpServer
    private lateinit var credentials: FakeCredentials
    private lateinit var bridge: ServerProfileBridge

    private var status = 202
    private var body = """{"request_id":"r","data":{"sent":true,"retry_after_ms":45000}}"""

    @Before
    fun setUp() {
        server = BridgeHttpServer { _ -> status to body }
        credentials = FakeCredentials(TokenPair("access-a", "refresh-a", "S-A"))
        val session = AccountSession { credentials.read()?.sessionId }
        assertTrue(session.begin("S-A"))
        bridge = ServerProfileBridge(
            auth = AuthApi(ApiClient(ApiConfig(server.baseUrl, allowInsecureHttp = true), credentials), credentials),
            session = session,
            credentials = credentials,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `sent true returns dispatch instead of throwing`() = runBlocking {
        status = 202
        body = """{"request_id":"r","data":{"sent":true,"retry_after_ms":45000}}"""

        val dispatch = bridge.requestCode("user@example.invalid", CodePurpose.REGISTER)

        assertEquals(45, dispatch.resendAfterSeconds)
        assertNotNull("发送成功不应清掉登录态", credentials.read())
    }

    @Test
    fun `zero retry after still waits at least one second`() = runBlocking {
        status = 202
        body = """{"request_id":"r","data":{"sent":true,"retry_after_ms":0}}"""

        val dispatch = bridge.requestCode("user@example.invalid", CodePurpose.RESET_PASSWORD)

        assertEquals(1, dispatch.resendAfterSeconds)
    }

    @Test
    fun `sent false keeps throttling message and session`() {
        status = 202
        body = """{"request_id":"r","data":{"sent":false,"retry_after_ms":30000}}"""

        val error = assertThrows(ProfileException::class.java) {
            runBlocking { bridge.requestCode("user@example.invalid", CodePurpose.REGISTER) }
        }

        assertEquals("验证码发送过于频繁，请30秒后再试", error.message)
        assertNotNull("限流不是登录失败，不应清掉会话", credentials.read())
    }

    @Test
    fun `network failure keeps original profile message`() {
        server.close()

        val error = assertThrows(ProfileException::class.java) {
            runBlocking { bridge.requestCode("user@example.invalid", CodePurpose.REGISTER) }
        }

        assertEquals("网络连接失败，请检查网络后重试", error.message)
    }

    @Test
    fun `invalid register and reset codes keep the server feedback and session`() {
        status = 400
        body = """{"request_id":"r","error":{"code":"VALIDATION_ERROR","message":"验证码无效或已过期"}}"""
        for (reset in listOf(false, true)) {
            val error = assertThrows(ProfileException::class.java) {
                runBlocking {
                    if (reset) bridge.resetPassword("user@example.invalid", "123456", "Synthetic-password-9")
                    else bridge.register("user@example.invalid", "123456", "Synthetic-password-9")
                }
            }
            assertEquals("验证码无效或已过期", error.message)
            assertNotNull(credentials.read())
        }
    }

    @Test
    fun `offline login register and reset keep credentials and report network failure`() {
        server.close()
        for (operation in 0..2) {
            val error = assertThrows(ProfileException::class.java) {
                runBlocking {
                    when (operation) {
                        0 -> bridge.login("user@example.invalid", "Synthetic-password-9")
                        1 -> bridge.register("user@example.invalid", "123456", "Synthetic-password-9")
                        else -> bridge.resetPassword("user@example.invalid", "123456", "Synthetic-password-9")
                    }
                }
            }
            assertEquals("网络连接失败，请检查网络后重试", error.message)
            assertNotNull(credentials.read())
        }
    }

    @Test
    fun `401 keeps unauthenticated message and clears session`() {
        status = 401
        body = """{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"synthetic"}}"""

        val error = assertThrows(ProfileException::class.java) {
            runBlocking { bridge.requestCode("user@example.invalid", CodePurpose.REGISTER) }
        }

        assertEquals("登录已过期，请重新登录", error.message)
        assertNull(credentials.read())
    }

    @Test fun `wrong login password has specific feedback without clearing another active session`() {
        status=401
        body="""{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"Invalid credentials"}}"""
        val error=assertThrows(ProfileException::class.java){runBlocking{bridge.login("user@example.invalid","incorrect-password")}}
        assertEquals("邮箱或密码错误，请重新输入",error.message)
        assertEquals("S-A",credentials.read()?.sessionId)
    }
}
