package io.whaletodo.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** AiApi 的 HTTP 契约测试：models/usage/submit/query、错误体与 401 刷新。 */
class AiApiTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val serverExecutor = Executors.newCachedThreadPool()
    private val calls = mutableListOf<Recorded>()

    data class Recorded(val method: String, val path: String, val auth: String?, val body: String)

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

    private fun handler(route: (Recorded) -> Pair<Int, String>) {
        server.createContext("/") { exchange: HttpExchange ->
            val rec = Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            synchronized(calls) { calls += rec }
            val (status, body) = route(rec)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("x-request-id", "req-ai")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun api(store: TokenStore = InMemoryTokenStore(TokenPair("a", "r", "S"))) =
        AiApi(ApiClient(ApiConfig(baseUrl, allowInsecureHttp = true), store))

    private fun submit(id: String = "00000000-0000-4000-a000-000000000001") = AiSubmit(
        clientRequestId = id,
        mode = "chat",
        modelCode = "deepseek-default",
        prompt = "hello",
        context = "",
        history = listOf(AiHistory("user", "earlier")),
        editConsent = false,
        shareStyle = "minimal",
    )

    @Test
    fun `models 解析允许列表与 vip 标记`() {
        handler {
            Pair(200, """{"request_id":"r","data":{"prompt_version":"agent-v1","tokens_per_point":1000,"items":[{"code":"deepseek-default","title":"鲸鱼AI","can_edit":false},{"code":"deepseek-planner","title":"日程编辑","can_edit":true,"requires_vip":true}]}}""")
        }
        val models = api().models("S")
        assertEquals("agent-v1", models.promptVersion)
        assertEquals(2, models.items.size)
        assertTrue(models.items[1].canEdit)
        assertTrue(models.items[1].requiresVip)
    }

    @Test
    fun `usage 解析 token 与积分字段`() {
        handler {
            Pair(200, """{"request_id":"r","data":{"requests":3,"completed":2,"pending":1,"total_tokens":1234,"input_tokens":1000,"output_tokens":234,"charged_points":2,"available_points":8,"reserved_points":1,"usage_k":"1.2k","tokens_per_point":1000}}""")
        }
        val usage = api().usage("S")
        assertEquals(3, usage.requests)
        assertEquals(1234, usage.totalTokens)
        assertEquals(8, usage.availablePoints)
        assertEquals("1.2k", usage.usageK)
    }

    @Test
    fun `submit 200 succeeded 映射文本、用量与提案`() {
        handler {
            Pair(200, """{"request_id":"r","data":{"id":"00000000-0000-4000-a000-000000000001","status":"succeeded","mode":"edit","text":"建议先做一件小事。","usage_tokens":12,"usage_k":"0.1k","input_tokens":8,"output_tokens":4,"points_reserved":1,"points_charged":1,"continuation":false,"context_compacted":false,"proposal":{"changes":[{"type":"rename_task","target_id":"t1","title":"新标题"},{"type":"set_category","target_id":"t2","category":"work"},{"type":"move_block","target_id":"b1","start_ms":1000,"end_ms":2000}]},"error_code":null,"created_at":"2026-10-27T00:00:00.000Z"}}""")
        }
        val view = api().submit(submit(), "S")
        assertEquals("succeeded", view.status)
        assertEquals("建议先做一件小事。", view.text)
        assertEquals(12, view.usageTokens)
        assertEquals(1, view.pointsCharged)
        assertEquals(3, view.proposal?.changes?.size)
        assertEquals("rename_task", view.proposal?.changes?.get(0)?.type)
        assertEquals("新标题", view.proposal?.changes?.get(0)?.title)
        assertEquals(2000L, view.proposal?.changes?.get(2)?.endMs)
        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v1/ai/requests", call.path)
        assertTrue(call.body.contains("\"client_request_id\""))
        assertTrue(call.body.contains("\"history\""))
        assertTrue(call.body.contains("\"edit_consent\":false"))
    }

    @Test
    fun `submit 202 unknown 保留原请求状态`() {
        handler {
            Pair(202, """{"request_id":"r","data":{"id":"00000000-0000-4000-a000-000000000001","status":"unknown","mode":"chat","text":null,"error_code":"REQUEST_PENDING"}}""")
        }
        val view = api().submit(submit(), "S")
        assertEquals("unknown", view.status)
        assertEquals("REQUEST_PENDING", view.errorCode)
    }

    @Test
    fun `402 保留错误体与 INSUFFICIENT_POINTS`() {
        handler {
            Pair(402, """{"request_id":"r","error":{"code":"INSUFFICIENT_POINTS","message":"no points","details":{"required_points":1}}}""")
        }
        val e = runCatching { api().usage("S") }.exceptionOrNull() as ApiException
        assertEquals(402, e.status)
        assertEquals("INSUFFICIENT_POINTS", e.code)
        assertTrue(e.body.contains("required_points"))
    }

    @Test
    fun `403 三种 reason 都可从 body 读取`() {
        var reason = "VIP_REQUIRED"
        handler {
            Pair(403, """{"request_id":"r","error":{"code":"FORBIDDEN","message":"denied","details":{"reason":"$reason"}}}""")
        }
        val api = api()
        listOf("VIP_REQUIRED", "MODEL_NOT_ALLOWED", "CONSENT_REQUIRED").forEach { expected ->
            reason = expected
            val e = runCatching { api.usage("S") }.exceptionOrNull() as ApiException
            assertEquals(403, e.status)
            assertEquals(expected, api.errorReason(e.body))
        }
    }

    @Test
    fun `409 429 503 保留 HTTP 状态`() {
        var status = 409
        handler {
            Pair(status, """{"request_id":"r","error":{"code":"CONFLICT","message":"conflict"}}""")
        }
        val api = api()
        listOf(409, 429, 503).forEach { expected ->
            status = expected
            val e = runCatching { api.usage("S") }.exceptionOrNull() as ApiException
            assertEquals(expected, e.status)
        }
    }

    @Test
    fun `query 成功只 GET 原 ID`() {
        handler {
            Pair(200, """{"request_id":"r","data":{"id":"00000000-0000-4000-a000-000000000001","status":"succeeded","text":"done","usage_tokens":3}}""")
        }
        val view = api().query("00000000-0000-4000-a000-000000000001", "S")
        assertEquals("done", view.text)
        val call = calls.single()
        assertEquals("GET", call.method)
        assertEquals("/api/v1/ai/requests/00000000-0000-4000-a000-000000000001", call.path)
    }

    @Test
    fun `query 越权 403`() {
        handler {
            Pair(403, """{"request_id":"r","error":{"code":"FORBIDDEN","message":"无法访问此请求"}}""")
        }
        val e = runCatching { api().query("00000000-0000-4000-a000-000000000001", "S") }.exceptionOrNull() as ApiException
        assertEquals(403, e.status)
        assertEquals("FORBIDDEN", e.code)
    }

    @Test
    fun `401 刷新后重试 models`() {
        val modelCalls = AtomicInteger(0)
        val refreshCalls = AtomicInteger(0)
        handler { call ->
            when (call.path) {
                "/api/v1/ai/models" -> if (modelCalls.getAndIncrement() == 0) {
                    Pair(401, """{"request_id":"r","error":{"code":"UNAUTHENTICATED","message":"expired"}}""")
                } else {
                    Pair(200, """{"request_id":"r","data":{"tokens_per_point":1000,"items":[{"code":"deepseek-default","title":"鲸鱼AI","can_edit":false}]}}""")
                }
                "/api/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    Pair(200, """{"data":{"access_token":"b","refresh_token":"r2"}}""")
                }
                else -> Pair(404, """{"error":{"code":"VALIDATION_ERROR","message":"?"}}""")
            }
        }
        val store = InMemoryTokenStore(TokenPair("a", "r", "S"))
        val models = api(store).models("S")
        assertEquals(1, refreshCalls.get())
        assertEquals(2, modelCalls.get())
        assertEquals("鲸鱼AI", models.items.single().title)
        assertEquals("b", store.read()?.accessToken)
        assertNotNull(store.read()?.sessionId)
    }

    @Test
    fun `TokenStore 已是 B 时 A 的 submit 不发 HTTP`() {
        handler { Pair(200, """{"request_id":"r","data":{"id":"x","status":"succeeded","text":"ok"}}""") }
        val store = InMemoryTokenStore(TokenPair("b", "rb", "B"))
        val api = api(store)
        val e = runCatching { api.submit(submit(), "A") }.exceptionOrNull() as ApiException
        assertEquals(401, e.status)
        assertTrue(e.message.contains("账户已切换"))
        assertEquals(0, calls.size)
    }

    @Test
    fun `TokenStore 已是 B 时 A 的 query 不发 HTTP`() {
        handler { Pair(200, """{"request_id":"r","data":{"id":"00000000-0000-4000-a000-000000000001","status":"succeeded","text":"ok"}}""") }
        val store = InMemoryTokenStore(TokenPair("b", "rb", "B"))
        val api = api(store)
        val e = runCatching {
            api.query("00000000-0000-4000-a000-000000000001", "A")
        }.exceptionOrNull() as ApiException
        assertEquals(401, e.status)
        assertTrue(e.message.contains("账户已切换"))
        assertEquals(0, calls.size)
    }

    @Test
    fun `503 有 failed 回执与无 failed 可区分`() {
        var body = """{"request_id":"r","data":{"id":"x","status":"failed"}}"""
        handler { Pair(503, body) }
        val api = api()
        val failed = runCatching { api.submit(submit(), "S") }.exceptionOrNull() as ApiException
        assertEquals(503, failed.status)
        assertEquals("failed", api.statusOf(failed.body))

        body = """{"request_id":"r","error":{"code":"UPSTREAM_UNAVAILABLE","message":"down"}}"""
        val unknown = runCatching { api.submit(submit(), "S") }.exceptionOrNull() as ApiException
        assertEquals(503, unknown.status)
        assertNull(api.statusOf(unknown.body))
    }

    @Test
    fun `statusOf 只把明确 failed 的回执当作失败`() {
        handler { Pair(500, """{"request_id":"r","error":{"code":"INTERNAL","message":"boom"}}""") }
        val api = api()
        assertEquals("failed", api.statusOf("""{"request_id":"r","data":{"id":"x","status":"failed"}}"""))
        assertNull(api.statusOf("""{"request_id":"r","error":{"code":"UPSTREAM_UNAVAILABLE","message":"down"}}"""))
        assertNull(api.statusOf("not-json"))
    }
}
