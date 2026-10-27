package io.whaletodo.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用 JDK 自带 HttpServer 起一个假后端，验证客户端的真实 HTTP 行为：
 * 鉴权头、401 自动刷新并重试一次、刷新失败清令牌、错误码映射。
 */
class ApiClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val serverExecutor = Executors.newCachedThreadPool()

    /** 记录收到的请求，便于断言 */
    private val calls = mutableListOf<Recorded>()

    data class Recorded(val method: String, val path: String, val auth: String?, val body: String, val idem: String?)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}/api/v1"
        server.executor = serverExecutor
    }

    @After
    fun tearDown() {
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    private fun handler(routes: (Recorded) -> Pair<Int, String>) {
        server.createContext("/") { exchange: HttpExchange ->
            val rec = Recorded(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                auth = exchange.requestHeaders.getFirst("Authorization"),
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                idem = exchange.requestHeaders.getFirst("Idempotency-Key"),
            )
            synchronized(calls) { calls += rec }
            val (status, body) = routes(rec)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("x-request-id", "req-test-1")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun client(store: TokenStore = InMemoryTokenStore()) =
        ApiClient(ApiConfig(baseUrl, allowInsecureHttp = true), store)

    /* ---------------- 配置 ---------------- */

    @Test
    fun `release 构建拒绝明文 http 基址`() {
        try {
            ApiConfig("http://api.example.com/api/v1")
            fail("应当拒绝 http 基址")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("明文 HTTP"))
        }
    }

    @Test
    fun `调试构建允许 http，且路径拼接正确`() {
        val cfg = ApiConfig("http://10.0.2.2:8080/api/v1/", allowInsecureHttp = true)
        assertEquals("http://10.0.2.2:8080/api/v1/me", cfg.resolve("/me"))
        assertEquals("http://10.0.2.2:8080/api/v1/me", ApiConfig("http://10.0.2.2:8080/api/v1", true).resolve("/me"))
    }

    @Test
    fun `https 公网基址默认可用`() {
        val cfg = ApiConfig(ApiConfig.PUBLIC_BASE)
        assertEquals("https://api.whaletodo.top:8443/api/v1/health/live", cfg.resolve("/health/live"))
    }

    /* ---------------- 基本请求 ---------------- */

    @Test
    fun `请求自动带 Bearer 令牌`() {
        handler { Pair(200, """{"request_id":"r1","data":{"ok":true}}""") }
        val store = InMemoryTokenStore(TokenPair("access-1", "refresh-1"))
        val res = client(store).get("/me")

        assertEquals(200, res.status)
        assertEquals("req-test-1", res.requestId)
        assertEquals("Bearer access-1", calls.single().auth)
    }

    @Test
    fun `未登录时不带鉴权头`() {
        handler { Pair(200, """{"request_id":"r1","data":{}}""") }
        client().post("/auth/login", """{"username":"a","password":"b"}""", auth = false)
        assertNull(calls.single().auth)
    }

    @Test
    fun `写请求透传 Idempotency-Key`() {
        handler { Pair(201, """{"request_id":"r1","data":{}}""") }
        val store = InMemoryTokenStore(TokenPair("a", "r"))
        client(store).post("/rewards/daily/claim", auth = true, idempotencyKey = "key-1")
        assertEquals("key-1", calls.single().idem)
        assertEquals("POST", calls.single().method)
    }

    /* ---------------- 401 刷新 ---------------- */

    @Test
    fun `401 时用 refresh 换新令牌并重试原请求一次`() {
        var meCalls = 0
        handler { rec ->
            when (rec.path) {
                "/api/v1/me" -> {
                    meCalls++
                    if (meCalls == 1) Pair(401, """{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"过期"}}""")
                    else Pair(200, """{"request_id":"r2","data":{"user":{"id":"u1"}}}""")
                }
                "/api/v1/auth/refresh" -> Pair(200, """{"request_id":"r3","data":{"access_token":"new-a","refresh_token":"new-r"}}""")
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("old-a", "old-r"))
        val res = client(store).get("/me")

        assertEquals(200, res.status)
        assertEquals(2, meCalls)
        assertEquals(TokenPair("new-a", "new-r"), store.read())
        assertEquals("Bearer new-a", calls.last().auth)
    }

    @Test
    fun `刷新失败时清空令牌并抛出 401`() {
        handler { rec ->
            if (rec.path == "/api/v1/auth/refresh") {
                Pair(401, """{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"刷新令牌已失效"}}""")
            } else {
                Pair(401, """{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"访问令牌无效"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("old-a", "old-r"))
        try {
            client(store).get("/me")
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals("UNAUTHENTICATED", e.code)
            assertEquals(401, e.status)
        }
        assertNull(store.read())
    }

    @Test
    fun `refresh 503 保留令牌，恢复后重试成功`() {
        var meCalls = 0
        var refreshCalls = 0
        handler { rec ->
            when (rec.path) {
                "/api/v1/me" -> {
                    meCalls++
                    if (meCalls <= 2) Pair(401, """{"request_id":"r401","error":{"code":"UNAUTHENTICATED","message":"过期"}}""")
                    else Pair(200, """{"request_id":"r200","data":{"user":{"id":"u1"}}}""")
                }
                "/api/v1/auth/refresh" -> {
                    refreshCalls++
                    if (refreshCalls == 1) {
                        Pair(503, """{"request_id":"r503","error":{"code":"UPSTREAM_UNAVAILABLE","message":"down"}}""")
                    } else {
                        Pair(200, """{"request_id":"r-refresh","data":{"access_token":"new-a","refresh_token":"new-r"}}""")
                    }
                }
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("old-a", "old-r"))
        val api = client(store)

        try {
            api.get("/me")
            fail("应当抛出可重试错误")
        } catch (e: ApiException) {
            assertEquals("UPSTREAM_UNAVAILABLE", e.code)
            assertEquals(503, e.status)
            assertEquals("登录状态校验暂时不可用，请稍后重试", e.message)
        }
        assertEquals(TokenPair("old-a", "old-r"), store.read())

        assertEquals(200, api.get("/me").status)
        assertEquals(2, refreshCalls)
        assertEquals(TokenPair("new-a", "new-r"), store.read())
    }

    @Test
    fun `并发 401 只提交一次 refresh`() {
        val oldTokenArrivals = CountDownLatch(2)
        val refreshCalls = AtomicInteger(0)
        handler { rec ->
            when {
                rec.path == "/api/v1/me" && rec.auth == "Bearer old-a" -> {
                    oldTokenArrivals.countDown()
                    oldTokenArrivals.await(3, TimeUnit.SECONDS)
                    Pair(401, """{"request_id":"r401","error":{"code":"UNAUTHENTICATED","message":"过期"}}""")
                }
                rec.path == "/api/v1/me" ->
                    Pair(200, """{"request_id":"r200","data":{"user":{"id":"u1"}}}""")
                rec.path == "/api/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    Pair(200, """{"request_id":"r-refresh","data":{"access_token":"new-a","refresh_token":"new-r"}}""")
                }
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("old-a", "old-r"))
        val api = client(store)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<ApiResponse> {
                    gate.await(3, TimeUnit.SECONDS)
                    api.get("/me")
                }
            }
            gate.countDown()
            futures.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS).status) }
            assertEquals(1, refreshCalls.get())
            assertEquals(TokenPair("new-a", "new-r"), store.read())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `退出期间刷新结果不写回`() {
        val refreshStarted = CountDownLatch(1)
        val allowRefresh = CountDownLatch(1)
        handler { rec ->
            when (rec.path) {
                "/api/v1/me" ->
                    Pair(401, """{"request_id":"r401","error":{"code":"UNAUTHENTICATED","message":"过期"}}""")
                "/api/v1/auth/refresh" -> {
                    refreshStarted.countDown()
                    allowRefresh.await(3, TimeUnit.SECONDS)
                    Pair(200, """{"request_id":"r-refresh","data":{"access_token":"new-a","refresh_token":"new-r"}}""")
                }
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("old-a", "old-r"))
        val api = client(store)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<ApiResponse> { api.get("/me") }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            store.clear()
            allowRefresh.countDown()
            try {
                future.get(5, TimeUnit.SECONDS)
                fail("刷新结果被丢弃后应抛原 401")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is ApiException)
                assertEquals(401, (e.cause as ApiException).status)
            }
            assertNull(store.read())
        } finally {
            allowRefresh.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `登录成功保存令牌`() {
        handler { Pair(200, """{"request_id":"r","data":{"access_token":"acc","refresh_token":"ref"}}""") }
        val store = InMemoryTokenStore()
        val tokens = AuthApi(client(store), store).login("alice", "S3cret-passw0rd")
        assertEquals(TokenPair("acc", "ref"), tokens)
        assertEquals(TokenPair("acc", "ref"), store.read())
    }

    @Test
    fun `退出登录即使服务端失败也清本地令牌`() {
        handler { Pair(500, """{"request_id":"r","error":{"code":"INTERNAL","message":"boom"}}""") }
        val store = InMemoryTokenStore(TokenPair("a", "r"))
        try {
            AuthApi(client(store), store).logout()
        } catch (_: ApiException) {
            // 预期
        }
        assertNull(store.read())
    }

    /* ---------------- 错误映射 ---------------- */

    @Test
    fun `非 2xx 映射为 ApiException 并带错误码与 request_id`() {
        handler { Pair(409, """{"request_id":"req-x","error":{"code":"CONFLICT","message":"今日已领取签到奖励"}}""") }
        val store = InMemoryTokenStore(TokenPair("a", "r"))
        try {
            client(store).post("/rewards/daily/claim", idempotencyKey = "k")
            fail("应当抛出")
        } catch (e: ApiException) {
            assertEquals("CONFLICT", e.code)
            assertEquals("今日已领取签到奖励", e.message)
            assertEquals(409, e.status)
            assertNotNull(e.requestId)
        }
    }

    @Test
    fun `响应缺字段时抛 INTERNAL`() {
        handler { Pair(200, """{"request_id":"r","data":{}}""") }
        val store = InMemoryTokenStore()
        try {
            AuthApi(client(store), store).login("alice", "pw")
            fail("应当抛出")
        } catch (e: ApiException) {
            assertEquals("INTERNAL", e.code)
            assertTrue(e.message.contains("access_token"))
        }
    }

    @Test
    fun `成功响应不带错误解析`() {
        handler { Pair(200, """{"request_id":"r","data":{"available":30,"reserved":0}}""") }
        val store = InMemoryTokenStore(TokenPair("a", "r"))
        val res = client(store).get("/wallet")
        assertTrue(res.isSuccess)
        assertFalse(res.body.isEmpty())
    }

    /* ---------------- 邮箱账户 ---------------- */

    @Test
    fun `邮箱登录发送 email 字段并保存令牌`() {
        handler { Pair(200, """{"request_id":"r","data":{"access_token":"acc","refresh_token":"ref"}}""") }
        val store = InMemoryTokenStore()
        AuthApi(client(store), store).loginWithEmail("a@example.invalid", "S3cret-passw0rd")
        val call = calls.single()
        assertEquals("/api/v1/auth/login", call.path)
        assertTrue(call.body.contains("\"email\":\"a@example.invalid\""))
        assertFalse(call.body.contains("\"username\""))
        assertEquals(TokenPair("acc", "ref"), store.read())
    }

    @Test
    fun `发码与验证返回 ticket`() {
        handler { call ->
            when (call.path) {
                "/api/v1/auth/email/send-code" ->
                    Pair(202, """{"request_id":"r","data":{"sent":true,"retry_after_ms":45000}}""")
                "/api/v1/auth/email/verify" ->
                    Pair(200, """{"request_id":"r","data":{"ticket":"ticket-1","expires_in":60}}""")
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val api = AuthApi(client(), InMemoryTokenStore())
        val dispatch = api.sendEmailCode("a@example.invalid", "register")
        assertTrue(dispatch.sent)
        assertEquals(45_000L, dispatch.retryAfterMs)
        assertTrue(calls.first().body.contains("\"purpose\":\"register\""))

        val ticket = api.verifyEmailCode("a@example.invalid", "123456", "register")
        assertEquals("ticket-1", ticket.ticket)
        assertEquals(60, ticket.expiresInSeconds)
        assertTrue(calls.last().body.contains("\"code\":\"123456\""))
    }

    @Test
    fun `邮箱注册与重置使用 ticket`() {
        handler { call ->
            when (call.path) {
                "/api/v1/auth/register/email" -> Pair(
                    201,
                    """{"request_id":"r","data":{"user":{"id":"u1","username":"whale_0123456789ab","display_name":"阿鲸"}}}""",
                )
                "/api/v1/auth/password/reset" -> Pair(204, "")
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val api = AuthApi(client(), InMemoryTokenStore())
        val user = api.registerWithEmail("a@example.invalid", "ticket-1", "S3cret-passw0rd", "阿鲸")
        assertEquals("u1", user.id)
        assertEquals("whale_0123456789ab", user.username)
        assertEquals("阿鲸", user.displayName)
        assertTrue(calls.first().body.contains("\"ticket\":\"ticket-1\""))
        assertTrue(calls.first().body.contains("\"display_name\":\"阿鲸\""))

        api.resetPassword("a@example.invalid", "ticket-2", "N3w-secret-passw0rd")
        assertTrue(calls.last().body.contains("\"ticket\":\"ticket-2\""))
    }

    @Test
    fun `资料权益钱包与签到响应解析`() {
        handler { call ->
            when (call.path) {
                "/api/v1/me" -> Pair(
                    200,
                    """{"request_id":"r","data":{"user":{"id":"u1","username":"whale_0123456789ab","display_name":"阿鲸","created_at":"2026-10-26T00:00:00Z"}}}""",
                )
                "/api/v1/me/entitlements" -> Pair(
                    200,
                    """{"request_id":"r","data":{"vip":{"active":true,"expires_at":"2026-11-26T00:00:00Z"},"balance":{"available":7,"reserved":0}}}""",
                )
                "/api/v1/wallet" -> Pair(200, """{"request_id":"r","data":{"available":7,"reserved":0,"total":7}}""")
                "/api/v1/rewards/daily/claim" -> Pair(
                    201,
                    """{"request_id":"r","data":{"business_date":"2026-10-26","points_awarded":1,"wallet":{"available":8,"reserved":0}}}""",
                )
                "/api/v1/rewards/daily" -> Pair(
                    200,
                    """{"request_id":"r","data":{"business_date":"2026-10-26","claimed":true}}""",
                )
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("a", "r"))
        val api = AuthApi(client(store), store)
        assertEquals("u1", api.me().id)
        assertEquals("阿鲸", api.me().displayName)
        val ent = api.entitlements()
        assertTrue(ent.vipActive)
        assertEquals(Instant.parse("2026-11-26T00:00:00Z"), ent.vipExpiresAt)
        assertEquals(7, api.wallet().available)
        val claim = api.claimDailyReward("idem-1")
        assertEquals("2026-10-26", claim.businessDate)
        assertEquals(1, claim.pointsAwarded)
        assertEquals(8, claim.availablePoints)
        assertEquals("2026-10-26", api.dailyReward().businessDate)
        assertEquals("Bearer a", calls.first().auth)
        assertEquals("idem-1", calls.first { it.path.endsWith("/claim") }.idem)
    }
}
