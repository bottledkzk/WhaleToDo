package io.whaletodo.app

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.whaletodo.app.agent.ShareStyle
import io.whaletodo.app.share.ShareCopyProvider
import io.whaletodo.app.share.ShareCopyResult
import io.whaletodo.app.share.ShareDraft
import io.whaletodo.app.share.ShareDraftStore
import io.whaletodo.app.share.ShareStyleSheet
import io.whaletodo.core.AgentAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareDraftUiTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val device = UiDevice.getInstance(instrument)

    private class FakeDraftStore(private val draft: ShareDraft?) : ShareDraftStore {
        var cleared = false
        override fun load(ownerId: String, sourceFingerprint: String): ShareDraft? =
            draft?.takeIf { it.ownerId == ownerId && it.sourceFingerprint == sourceFingerprint }

        override fun save(draft: ShareDraft) = Unit
        override fun clear(ownerId: String, sourceFingerprint: String) {
            cleared = true
        }
    }

    private class FakeProvider : ShareCopyProvider {
        override suspend fun suggest(style: ShareStyle, sourceText: String): ShareCopyResult =
            throw AssertionError("draft restore must not POST")

        override suspend fun pending(ownerId: String, sourceFingerprint: String): String? = null
        override suspend fun recover(ownerId: String, sourceFingerprint: String): ShareCopyResult? = null
    }

    @Test
    fun restoredDraftCanBePreviewedEditedAndClearedWithoutNetwork() {
        val owner = "draft-ui-owner"
        val source = "草稿预览来源"
        val fingerprint = ShareDraft.fingerprint(source)
        val store = FakeDraftStore(
            ShareDraft(
                ownerId = owner,
                sourceFingerprint = fingerprint,
                suggestions = listOf("草稿候选一", "草稿候选二"),
                selectedText = "草稿候选二",
                editedText = null,
                styleCode = "literary",
                requestId = "req-draft-ui",
                usage = 7,
                chargedPoints = 0,
                updatedAt = 1,
            ),
        )
        val monitor = instrument.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val activity = instrument.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
        instrument.removeMonitor(monitor)
        instrument.runOnMainSync {
            activity.setContent {
                MaterialTheme {
                    ShareStyleSheet(
                        text = source,
                        access = AgentAccess(userId = owner, availablePoints = 10, connected = true),
                        trialStyle = null,
                        dismiss = {},
                        onGate = { _, _ -> },
                        onShareText = {},
                        onShared = {},
                        onTrialConsumed = {},
                        copyProvider = FakeProvider(),
                        draftStore = store,
                    )
                }
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("分享样式")), 5000))
        repeat(6) {
            device.swipe(540, 1600, 540, 400, 10)
            device.waitForIdle()
        }
        assertTrue(device.wait(Until.hasObject(By.text("已恢复上次配文草稿（未重新请求）")), 5000))
        assertTrue(device.hasObject(By.desc("分享卡片预览")))
        assertTrue(device.hasObject(By.text("草稿候选二")))
        assertTrue(device.hasObject(By.text("本次用量：7 tokens · 结算积分：0 · 请求 ID：req-draft-ui")))

        val edit = device.findObject(By.clazz("android.widget.EditText"))
        assertTrue(edit != null)
        edit.click()
        edit.text = "编辑后的分享文案"
        device.waitForIdle()
        assertEquals("编辑后的分享文案", edit.text)

        device.pressBack()
        device.waitForIdle()
        repeat(3) {
            device.swipe(540, 1600, 540, 400, 10)
            device.waitForIdle()
        }
        val clear = device.findObject(By.text("清除分享草稿"))
        assertTrue(clear != null)
        clear.click()
        assertTrue(store.cleared)
        assertTrue(device.wait(Until.gone(By.text("草稿候选二")), 5000) || !device.hasObject(By.text("草稿候选二")))
        assertFalse(device.hasObject(By.text("草稿候选二")))
    }
}
