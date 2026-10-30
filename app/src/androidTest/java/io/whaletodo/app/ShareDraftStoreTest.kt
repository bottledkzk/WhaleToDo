package io.whaletodo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.share.ShareDraft
import io.whaletodo.app.share.ShareDraftPersister
import io.whaletodo.app.share.SharedPreferencesShareDraftStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareDraftStoreTest {
    @Test
    fun savesReloadsIsolatesAndClears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedPreferencesShareDraftStore(context)
        val ownerA = "draft-test-a-${UUID.randomUUID()}"
        val ownerB = "draft-test-b-${UUID.randomUUID()}"
        val sourceA = "source-a-${UUID.randomUUID()}"
        val sourceB = "source-b-${UUID.randomUUID()}"
        val fpA = ShareDraft.fingerprint(sourceA)
        val fpB = ShareDraft.fingerprint(sourceB)
        val draft = ShareDraft(
            ownerId = ownerA,
            sourceFingerprint = fpA,
            suggestions = listOf("候选一", "候选二"),
            selectedText = "候选二",
            editedText = "用户编辑后的文案",
            styleCode = "minimal",
            requestId = "request-123",
            usage = null,
            chargedPoints = 0,
            updatedAt = 1234L,
        )
        try {
            store.save(draft)
            assertEquals(draft, store.load(ownerA, fpA))
            assertNull(store.load(ownerB, fpA))
            assertNull(store.load(ownerA, fpB))

            store.clear(ownerA, fpA)
            assertNull(store.load(ownerA, fpA))
        } finally {
            store.clear(ownerA, fpA)
            store.clear(ownerA, fpB)
            store.clear(ownerB, fpA)
            store.clear(ownerB, fpB)
        }
    }

    @Test
    fun clearCommitFailureKeepsDraftAndCanBeRetried() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = SharedPreferencesShareDraftStore(context)
        val failing = SharedPreferencesShareDraftStore(context) { false }
        val owner = "draft-test-clear-${UUID.randomUUID()}"
        val source = "source-clear-${UUID.randomUUID()}"
        val fp = ShareDraft.fingerprint(source)
        val draft = ShareDraft(
            ownerId = owner,
            sourceFingerprint = fp,
            suggestions = listOf("候选"),
            selectedText = "候选",
            editedText = null,
            styleCode = "minimal",
            requestId = "request-clear",
            usage = null,
            chargedPoints = 0,
            updatedAt = 1L,
        )
        try {
            real.save(draft)
            assertThrows(IllegalStateException::class.java) { failing.clear(owner, fp) }
            assertNotNull(real.load(owner, fp))

            real.clear(owner, fp)
            assertNull(real.load(owner, fp))
        } finally {
            real.clear(owner, fp)
        }
    }

    @Test
    fun saveCommitFailureThrowsWithoutWriting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failing = SharedPreferencesShareDraftStore(context) { false }
        val real = SharedPreferencesShareDraftStore(context)
        val owner = "draft-test-save-${UUID.randomUUID()}"
        val source = "source-save-${UUID.randomUUID()}"
        val fp = ShareDraft.fingerprint(source)
        val draft = ShareDraft(
            ownerId = owner,
            sourceFingerprint = fp,
            suggestions = listOf("候选"),
            selectedText = null,
            editedText = "编辑",
            styleCode = "minimal",
            requestId = "request-save",
            usage = 1,
            chargedPoints = 0,
            updatedAt = 1L,
        )
        try {
            assertThrows(IllegalStateException::class.java) { failing.save(draft) }
            assertNull(real.load(owner, fp))
        } finally {
            real.clear(owner, fp)
        }
    }

    @Test
    fun firstSaveCommitFailureThenFlushRetryPersistsAndReopens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var commits = 0
        val flaky = SharedPreferencesShareDraftStore(context) { editor ->
            commits += 1
            if (commits == 1) false else editor.commit()
        }
        val real = SharedPreferencesShareDraftStore(context)
        val owner = "draft-test-flush-${UUID.randomUUID()}"
        val source = "source-flush-${UUID.randomUUID()}"
        val fp = ShareDraft.fingerprint(source)
        val draft = ShareDraft(
            ownerId = owner,
            sourceFingerprint = fp,
            suggestions = listOf("候选一", "候选二"),
            selectedText = "候选二",
            editedText = null,
            styleCode = "minimal",
            requestId = "request-flush",
            usage = 3,
            chargedPoints = 0,
            updatedAt = 2L,
        )
        val persister = ShareDraftPersister(flaky, owner, fp)
        try {
            val failed = withTimeout(5_000) { persister.save(draft).await() }
            assertFalse(failed.success)
            assertFalse(failed.applied)
            assertNull(real.load(owner, fp))

            val retried = withTimeout(5_000) { persister.flushForClose() }
            assertTrue("flushForClose should retry the failed save", retried?.success == true)
            assertEquals(draft, real.load(owner, fp))

            val reopened = ShareDraftPersister(real, owner, fp)
            assertNull(withTimeout(5_000) { reopened.flushForClose() })
            assertEquals("reopened store should still have the draft", draft, real.load(owner, fp))
        } finally {
            real.clear(owner, fp)
        }
    }

    @Test
    fun clearCommitFailureIsNotAppliedAndCanBeRetried() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = SharedPreferencesShareDraftStore(context)
        val failing = SharedPreferencesShareDraftStore(context) { false }
        val owner = "draft-test-clear-persister-${UUID.randomUUID()}"
        val source = "source-clear-persister-${UUID.randomUUID()}"
        val fp = ShareDraft.fingerprint(source)
        val draft = ShareDraft(
            ownerId = owner,
            sourceFingerprint = fp,
            suggestions = listOf("候选"),
            selectedText = "候选",
            editedText = null,
            styleCode = "minimal",
            requestId = "request-clear-persister",
            usage = null,
            chargedPoints = 0,
            updatedAt = 3L,
        )
        try {
            real.save(draft)
            val failed = withTimeout(5_000) {
                ShareDraftPersister(failing, owner, fp).clear().await()
            }
            assertFalse(failed.success)
            assertFalse(failed.applied)
            assertEquals(draft, real.load(owner, fp))

            val retried = withTimeout(5_000) {
                ShareDraftPersister(real, owner, fp).clear().await()
            }
            assertTrue(retried.success)
            assertTrue(retried.applied)
            assertNull(real.load(owner, fp))
        } finally {
            real.clear(owner, fp)
        }
    }
}
