package io.whaletodo.app.reminder

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化 owner 切换：prefs 写入与 resync 必须在同一把独立锁内完成，
 * 避免“登录 B → 立刻退出”两个协程交错后把旧 owner 的 alarm 留在最后。
 */
internal class OwnerSwitch {
    private val mutex = Mutex()

    /**
     * expectedSession 非空时，回调必须仍属于该会话代次：
     * - 旧代次（例如登录 B 后迟到）的 restore/login 回调直接丢弃；
     * - 会话已被同代次清空（登出/401）时 currentSession 为 null，
     *   只允许 guest 清理落地，避免旧 owner 重新激活，也避免误清刚登录的 B。
     */
    suspend fun switch(
        owner: String,
        expectedSession: String? = null,
        currentSession: () -> String? = { null },
        currentOwner: () -> String,
        write: (String) -> Unit,
        resync: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val normalized = owner.trim().ifEmpty { "guest" }
        if (expectedSession != null) {
            val current = currentSession()
            if (current != expectedSession && (current != null || normalized != "guest")) {
                return@withLock false
            }
        }
        if (currentOwner() == normalized) return@withLock false
        write(normalized)
        resync()
        true
    }
}
