package io.whaletodo.app.share

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareDraftPersisterTest {
    private class BlockingStore : ShareDraftStore {
        @Volatile var value: ShareDraft? = null
        val firstSaveStarted = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        @Volatile var failSave = false
        @Volatile var failClear = false

        override fun load(ownerId: String, sourceFingerprint: String): ShareDraft? = value
        override fun save(draft: ShareDraft) {
            if (draft.updatedAt == 1L) {
                firstSaveStarted.countDown()
                releaseFirstSave.await(5, TimeUnit.SECONDS)
            }
            if (failSave) throw IllegalStateException("synthetic save failure")
            value = draft
        }
        override fun clear(ownerId: String, sourceFingerprint: String) {
            if (failClear) throw IllegalStateException("synthetic clear failure")
            value = null
        }
    }

    private val owner = "owner"
    private val fingerprint = "source"
    private fun draft(updatedAt: Long, text: String) = ShareDraft(
        ownerId = owner,
        sourceFingerprint = fingerprint,
        suggestions = listOf("候选", "候选二"),
        selectedText = text,
        editedText = null,
        styleCode = "minimal",
        requestId = "request-$updatedAt",
        usage = 7,
        chargedPoints = 0,
        updatedAt = updatedAt,
    )

    @Test
    fun newerSaveWinsWhenAnOlderSaveIsStillWriting() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        val old = persister.save(draft(1, "旧"))
        assertTrue(store.firstSaveStarted.await(2, TimeUnit.SECONDS))
        val latest = persister.save(draft(2, "新"))
        store.releaseFirstSave.countDown()

        withTimeout(5_000) {
            old.await()
            latest.await()
        }
        assertEquals("新", store.value?.selectedText)
        assertEquals(2L, store.value?.updatedAt)
    }

    @Test
    fun clearDuringSaveWinsAndOldSaveCannotResurrectDraft() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        val old = persister.save(draft(1, "旧"))
        assertTrue(store.firstSaveStarted.await(2, TimeUnit.SECONDS))
        val clear = persister.clear()
        store.releaseFirstSave.countDown()

        withTimeout(5_000) {
            old.await()
            clear.await()
        }
        assertNull(store.value)
    }

    @Test
    fun failedClearKeepsDraftAndReportsFailureThenRetrySucceeds() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        withTimeout(5_000) { persister.save(draft(2, "保留")).await() }
        store.failClear = true

        val failed = withTimeout(5_000) { persister.clear().await() }
        assertFalse(failed.success)
        assertFalse(failed.applied)
        assertEquals("保留", store.value?.selectedText)

        store.failClear = false
        val retried = withTimeout(5_000) { persister.clear().await() }
        assertTrue(retried.success)
        assertNull(store.value)
    }

    @Test
    fun failedSaveIsReturnedByFlushAndDoesNotOverwrite() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        store.failSave = true

        val job = persister.save(draft(2, "失败"))
        val result = withTimeout(5_000) { job.await() }
        assertFalse(result.success)
        assertFalse(result.applied)
        assertNull(store.value)
        assertEquals(false, withTimeout(5_000) { persister.flush()?.success })
    }

    @Test
    fun flushRetriesFailedSaveWithLatestDraft() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        store.failSave = true
        withTimeout(5_000) { persister.save(draft(4, "待重试")).await() }

        store.failSave = false
        val retried = withTimeout(5_000) { persister.flush() }
        assertTrue(retried?.success == true)
        assertEquals("待重试", store.value?.selectedText)
    }

    @Test
    fun flushForCloseRetriesFailedSaveWithLatestDraftThenCloses() = runBlocking {
        val store = BlockingStore()
        val persister = ShareDraftPersister(store, owner, fingerprint)
        store.failSave = true
        withTimeout(5_000) { persister.save(draft(3, "最新未保存")).await() }

        store.failSave = false
        val retried = withTimeout(5_000) { persister.flushForClose() }
        assertTrue(retried?.success == true)
        assertEquals("最新未保存", store.value?.selectedText)

        val reopened = ShareDraftPersister(store, owner, fingerprint)
        withTimeout(5_000) { reopened.flushForClose() }
        assertEquals("最新未保存", store.load(owner, fingerprint)?.selectedText)
    }

    @Test
    fun latestDraftCanBeRestoredFromNewPersister() = runBlocking {
        val store = BlockingStore()
        val first = ShareDraftPersister(store, owner, fingerprint)
        withTimeout(5_000) { first.save(draft(2, "最新编辑")).await() }

        val reopened = ShareDraftPersister(store, owner, fingerprint)
        withTimeout(5_000) { reopened.flush() }
        assertEquals("最新编辑", store.load(owner, fingerprint)?.selectedText)
    }
}
