package io.whaletodo.app.reminder

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化 owner 切换：prefs 写入与 resync 必须在同一把独立锁内完成，
 * 避免“登录 B → 立刻退出”两个协程交错后把旧 owner 的 alarm 留在最后。
 */
internal class OwnerSwitch {
    private val mutex = Mutex()

    suspend fun switch(
        owner: String,
        current: () -> String,
        write: (String) -> Unit,
        resync: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val normalized = owner.trim().ifEmpty { "guest" }
        if (current() == normalized) return@withLock false
        write(normalized)
        resync()
        true
    }
}
