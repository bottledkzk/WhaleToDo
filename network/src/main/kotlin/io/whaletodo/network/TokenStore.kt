package io.whaletodo.network

/** 令牌对：access/refresh + 会话代次；同会话 refresh 保留 sessionId，换号/退出更换或清空。 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String = "",
)

/**
 * 令牌存储抽象。
 * 约定：
 *  - 只存内存时随进程结束清空（[InMemoryTokenStore] 用于测试与临时联调）
 *  - Android 侧实现必须用 Android Keystore 加密后再落盘，不得明文写入 SharedPreferences
 *  - 刷新失败或退出登录时必须调用 [clear]，避免残留令牌被后续请求带出
 */
interface TokenStore {
    fun read(): TokenPair?
    fun save(tokens: TokenPair)
    fun clear()
}

/** 内存实现：测试与临时联调用；不做持久化 */
class InMemoryTokenStore(initial: TokenPair? = null) : TokenStore {
    @Volatile
    private var tokens: TokenPair? = initial

    override fun read(): TokenPair? = tokens
    override fun save(tokens: TokenPair) { this.tokens = tokens }
    override fun clear() { tokens = null }
}
