package io.whaletodo.app.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DraftWriteResult(
    val success: Boolean,
    /** False when a newer generation superseded this write before it reached storage. */
    val applied: Boolean,
    val error: Throwable? = null,
)

/**
 * Serializes draft writes for one owner + source fingerprint.
 *
 * Every save/clear takes a generation. A write checks the generation while holding the
 * mutex, so an older save cannot land after a clear (or after a newer edit). The scope is
 * application-lifetime, not the composition scope, so closing the sheet cannot cancel the
 * final flush.
 */
internal class ShareDraftPersister(
    private val store: ShareDraftStore,
    val ownerId: String,
    private val sourceFingerprint: String,
    private val scope: CoroutineScope = ShareDraftIo.scope,
) {
    private val lock = Any()
    private val mutex = Mutex()
    private var generation = 0L
    private var latest: Deferred<DraftWriteResult>? = null
    private var latestDraft: ShareDraft? = null
    private var latestWasClear = false

    fun save(draft: ShareDraft): Deferred<DraftWriteResult> {
        require(draft.ownerId == ownerId && draft.sourceFingerprint == sourceFingerprint) {
            "draft owner/source does not match persister"
        }
        val generationAtWrite = nextGeneration()
        synchronized(lock) {
            latestDraft = draft
            latestWasClear = false
        }
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            mutex.withLock {
                if (!isCurrent(generationAtWrite)) {
                    DraftWriteResult(success = true, applied = false)
                } else {
                    runCatching { store.save(draft) }.fold(
                        onSuccess = { DraftWriteResult(success = true, applied = true) },
                        onFailure = { DraftWriteResult(success = false, applied = false, error = it) },
                    )
                }
            }
        }
        synchronized(lock) { latest = deferred }
        deferred.start()
        return deferred
    }

    fun clear(): Deferred<DraftWriteResult> {
        val generationAtClear = nextGeneration()
        synchronized(lock) { latestWasClear = true }
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            mutex.withLock {
                if (!isCurrent(generationAtClear)) {
                    DraftWriteResult(success = true, applied = false)
                } else {
                    runCatching { store.clear(ownerId, sourceFingerprint) }.fold(
                        onSuccess = { DraftWriteResult(success = true, applied = true) },
                        onFailure = { DraftWriteResult(success = false, applied = false, error = it) },
                    )
                }
            }
        }
        synchronized(lock) { latest = deferred }
        deferred.start()
        return deferred
    }

    /**
     * Wait for the latest write. If that write was a save that failed, retry once from the
     * latest immutable draft instead of awaiting the same failed Deferred forever.
     */
    suspend fun flushForClose(): DraftWriteResult? = flushLatest()

    /** Explicitly wait for the latest write; retries a failed save from the latest draft. */
    suspend fun flush(): DraftWriteResult? = flushLatest()

    private suspend fun flushLatest(): DraftWriteResult? {
        val first = synchronized(lock) { latest } ?: return null
        val result = first.await()
        if (result.success) return result
        val draft = synchronized(lock) {
            if (latestWasClear) null else latestDraft
        } ?: return result
        // A failed Deferred is completed; replace it with a fresh save from the immutable draft.
        return save(draft).await()
    }

    private fun nextGeneration(): Long = synchronized(lock) {
        generation += 1
        generation
    }

    private fun isCurrent(value: Long): Boolean = synchronized(lock) {
        generation == value
    }
}

/** Survives composition disposal so a closing sheet can await its last save. */
internal object ShareDraftIo {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
