package io.whaletodo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import io.whaletodo.app.device.openCompatibilitySettings
import org.junit.Assert.*
import org.junit.Test

/** Verification package only; no private identifiers, database writes or external requests. */
class DeviceCompatibilityTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

    @Test fun absentVendorSettingsFallbackAndFailureAreHandled() {
        val actions = mutableListOf<String?>()
        val fallback = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                actions.add(intent.action)
                if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) throw ActivityNotFoundException()
                assertEquals("package:${context.packageName}", intent.data.toString())
            }
        }
        assertTrue(openCompatibilitySettings(fallback, true))
        assertEquals(listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS), actions)
        val absent = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("synthetic settings refusal") }
        }
        assertFalse(openCompatibilitySettings(absent, true))
    }

    @Test fun settingsPageShowsEnvironmentAndRemainsAccessibleAfterResume() {
        check(context.packageName == "io.whaletodo.app.verification")
        val device = UiDevice.getInstance(instrument)
        val ui = UiSteps(device)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.click("我的")
        ui.click("设置")
        // Scroll rather than assuming a screen height, font size or OEM navigation layout.
        repeat(6) {
            if (!device.hasObject(By.text("设备与兼容性"))) {
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.5f)
                device.waitForIdle()
            }
        }
        ui.click("设备与兼容性")
        ui.text("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        ui.text("通知设置")
        device.pressHome()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        ui.text("设备与兼容性")
        ui.text("通知设置")
        device.pressBack()
        ui.text("外观设定")
    }
}
