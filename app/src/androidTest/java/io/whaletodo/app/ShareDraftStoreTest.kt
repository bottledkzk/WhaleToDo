package io.whaletodo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.share.ShareDraft
import io.whaletodo.app.share.SharedPreferencesShareDraftStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
