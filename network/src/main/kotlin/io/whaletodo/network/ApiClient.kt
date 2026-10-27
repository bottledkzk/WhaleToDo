package io.whaletodo.network

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 服务端统一错误：对应契约里的 9 个错误码 */
class ApiException(
    val code: String,
    override val message: String,
    val status: Int,
    val requestId: String?,
) : Exception(message)

/** 原始响应：成功时 body 为 `{request_id, data:{...}}` */
data class ApiResponse(val status: Int, val body: String, val requestId: String?) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * HTTP 客户端。
 *
 * 行为约定（与契约一致）：
 *  - 自动带上 `Authorization: Bearer <access_token>`
 *  - 访问令牌过期（401）时用 refresh_token 换一次新令牌并**重试原请求一次**
 *  - 并发 401 按 ApiClient 实例 single-flight，只提交一次 refresh
 *  - refresh 明确无效（400/401/403）才清空令牌；临时/上游错误保留令牌并抛可重试错误
 *  - 非 2xx 一律抛 [ApiException]，携带错误码、状态码与 request_id
 *  - 写请求由调用方传 Idempotency-Key
 *
 * 不做的部分：
 *  - 不解析业务 DTO（由上层按需解析 body）
 *  - 不做重试风暴：只重试一次刷新
 */
class ApiClient(
    private val config: ApiConfig,
    private val tokenStore: TokenStore,
    private val timeoutMs: Int = 15_000,
) {
    private val refreshLock = Any()

    private enum class RefreshResult { RETRY, INVALID, SWITCHED, DISCARDED }

    fun get(path: String, auth: Boolean = true): ApiResponse = request("GET", path, null, auth, null)

    fun post(
        path: String,
        body: String? = null,
        auth: Boolean = true,
        idempotencyKey: String? = null,
    ): ApiResponse = request("POST", path, body, auth, idempotencyKey)

    private fun request(
        method: String,
        path: String,
        body: String?,
        auth: Boolean,
        idempotencyKey: String?,
    ): ApiResponse {
        val used = if (auth) tokenStore.read() else null
        val usedAccess = used?.accessToken
        val usedSessionId = used?.sessionId.orEmpty()
        var response = execute(method, path, body, auth, idempotencyKey, usedAccess)
        if (response.status == 401 && auth) {
            when (refreshAfter401(usedSessionId, usedAccess, response.requestId)) {
                RefreshResult.RETRY -> {
                    val retryTokens = tokenStore.read()
                    if (retryTokens == null || retryTokens.sessionId != usedSessionId) {
                        throw switched(response.requestId)
                    }
                    response = execute(
                        method,
                        path,
                        body,
                        auth,
                        idempotencyKey,
                        retryTokens.accessToken,
                    )
                }
                RefreshResult.SWITCHED, RefreshResult.DISCARDED -> throw switched(response.requestId)
                RefreshResult.INVALID -> Unit
            }
        }
        if (!response.isSuccess) throw toException(response)
        return response
    }

    /**
     * 401 后的单飞刷新：同实例并发 401 只提交一次 refresh。
     * 会话代次不同时绝不拿新账户令牌重试；返回 SWITCHED。
     */
    private fun refreshAfter401(
        usedSessionId: String,
        usedAccess: String?,
        originalRequestId: String?,
    ): RefreshResult = synchronized(refreshLock) {
        val current = tokenStore.read() ?: return@synchronized RefreshResult.INVALID
        if (current.sessionId != usedSessionId) return@synchronized RefreshResult.SWITCHED
        if (current.accessToken != usedAccess) return@synchronized RefreshResult.RETRY

        val response = try {
            submitRefresh(current.refreshToken)
        } catch (_: IOException) {
            throw upstreamUnavailable(originalRequestId)
        }

        if (response.isSuccess) {
            val tokens = readTokens(response, current.sessionId)
            if (tokens == null) {
                if (tokenStore.read()?.sessionId == current.sessionId) tokenStore.clear()
                return@synchronized RefreshResult.INVALID
            }
            // 保存前核对：期间退出或换号（session/refresh 已变）时丢弃结果。
            val latest = tokenStore.read()
            if (latest == null ||
                latest.sessionId != current.sessionId ||
                latest.refreshToken != current.refreshToken
            ) {
                return@synchronized RefreshResult.DISCARDED
            }
            tokenStore.save(tokens)
            return@synchronized RefreshResult.RETRY
        }

        if (response.status in INVALID_REFRESH_STATUSES) {
            if (tokenStore.read()?.sessionId == current.sessionId) tokenStore.clear()
            return@synchronized RefreshResult.INVALID
        }
        throw upstreamUnavailable(response.requestId ?: originalRequestId)
    }

    /**
     * 用 refresh_token 换新令牌；成功返回 true 并写回 TokenStore。
     * 仅作为兼容入口，401 自动刷新走 [refreshAfter401] 的单飞流程。
     */
    fun refreshTokens(): Boolean = synchronized(refreshLock) {
        val current = tokenStore.read() ?: return@synchronized false
        val response = try {
            submitRefresh(current.refreshToken)
        } catch (_: IOException) {
            return@synchronized false
        }
        if (!response.isSuccess) return@synchronized false
        val tokens = readTokens(response, current.sessionId) ?: return@synchronized false
        val latest = tokenStore.read()
        if (latest == null ||
            latest.sessionId != current.sessionId ||
            latest.refreshToken != current.refreshToken
        ) return@synchronized false
        tokenStore.save(tokens)
        true
    }

    private fun submitRefresh(refreshToken: String): ApiResponse {
        val payload = """{"refresh_token":"${escape(refreshToken)}"}"""
        return execute("POST", "/auth/refresh", payload, auth = false, idempotencyKey = null, accessToken = null)
    }

    private fun readTokens(response: ApiResponse, sessionId: String): TokenPair? {
        val access = Json.stringField(response.body, "access_token") ?: return null
        val refresh = Json.stringField(response.body, "refresh_token") ?: return null
        return TokenPair(access, refresh, sessionId)
    }

    private fun switched(requestId: String?): ApiException =
        ApiException("UNAUTHENTICATED", "账户已切换，请重试", 401, requestId)

    private fun upstreamUnavailable(requestId: String?): ApiException =
        ApiException(
            "UPSTREAM_UNAVAILABLE",
            "登录状态校验暂时不可用，请稍后重试",
            503,
            requestId,
        )

    private fun execute(
        method: String,
        path: String,
        body: String?,
        auth: Boolean,
        idempotencyKey: String?,
        accessToken: String?,
    ): ApiResponse {
        val url = java.net.URI.create(config.resolve(path)).toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (auth && accessToken != null) {
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            idempotencyKey?.let { conn.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s -> BufferedReader(s.reader(Charsets.UTF_8)).readText() } ?: ""
            return ApiResponse(status, text, conn.getHeaderField("x-request-id"))
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        val INVALID_REFRESH_STATUSES = setOf(400, 401, 403)
    }

    private fun toException(response: ApiResponse): ApiException {
        val code = Json.stringField(response.body, "code") ?: "INTERNAL"
        val message = Json.stringField(response.body, "message") ?: "请求失败（HTTP ${response.status}）"
        return ApiException(code, message, response.status, response.requestId)
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
