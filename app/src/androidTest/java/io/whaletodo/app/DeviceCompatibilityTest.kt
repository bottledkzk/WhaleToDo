package io.whaletodo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import io.whaletodo.app.device.openCompatibilitySettings
import io.whaletodo.app.device.compatibilitySettingsIntents
import io.whaletodo.app.device.DeviceSettingsTarget
import io.whaletodo.app.device.DeviceCompatibility
import androidx.test.uiautomator.Until
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test

/** Verification package only; no private identifiers, database writes or external requests. */
class DeviceCompatibilityTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

    /** Some OEMs block a background test process's startActivity; shell launch is test-only. */
    private fun showVerificationActivity(clearTask: Boolean = false) {
        check(context.packageName == "io.whaletodo.app.verification")
        val flags = if (clearTask) "0x10008000" else "0x30000000"
        val command = "am start -W -f $flags -n ${context.packageName}/${MainActivity::class.java.name}"
        val output = instrument.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        check(!output.contains("Error:") && !output.contains("Exception")) { "Verification activity launch failed" }
        instrument.waitForIdleSync()
        repeat(100) {
            var focused = false
            instrument.runOnMainSync {
                focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any { it.hasWindowFocus() }
            }
            if (focused) return
            android.os.SystemClock.sleep(50)
        }
        error("Verification activity did not receive window focus; keep the device unlocked")
    }

    /** Click only our verified semantics; avoids requiring OEM global input-injection settings. */
    private fun clickOwnLabel(label: String) {
        check(label in setOf("我的", "设置", "设备与兼容性"))
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.packageName?.toString() != context.packageName) return null
            if (node.text?.toString() == label) {
                var action: AccessibilityNodeInfo? = node
                while (action != null && action.packageName?.toString() == context.packageName) {
                    if (action.isClickable && action.isEnabled) return action
                    action = action.parent
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::find)?.let { return it }
            }
            return null
        }
        repeat(100) {
            val action = instrument.uiAutomation.rootInActiveWindow?.let(::find)
            if (action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrument.waitForIdleSync()
                return
            }
            android.os.SystemClock.sleep(50)
        }
        error("Own-app navigation action unavailable: $label")
    }

    private fun ownBack() {
        instrument.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            activity.onBackPressedDispatcher.onBackPressed()
        }
        instrument.waitForIdleSync()
    }

    @Test fun absentVendorSettingsFallbackAndFailureAreHandled() {
        DeviceSettingsTarget.entries.forEach { target ->
            val actions = mutableListOf<String?>()
            val fallback = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    actions.add(intent.action)
                    if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
                    assertEquals("package:${context.packageName}", intent.data.toString())
                }
            }
            assertTrue(openCompatibilitySettings(fallback, target))
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, actions.last())
            val onlyAppInfo = target == DeviceSettingsTarget.CAMERA ||
                target == DeviceSettingsTarget.EXACT_ALARMS && Build.VERSION.SDK_INT < 31
            assertEquals(if (onlyAppInfo) 1 else 2, actions.size)
            val absent = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { throw SecurityException("synthetic settings refusal") }
            }
            assertFalse(openCompatibilitySettings(absent, target))
        }
    }

    @Test fun settingsPolicyGuardsApi26AndProvidesApi31And33Targets() {
        for (sdk in listOf(26, 31, 33)) {
            val exact = compatibilitySettingsIntents(DeviceSettingsTarget.EXACT_ALARMS, context.packageName, sdk)
            assertEquals(if (sdk < 31) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, exact.first().action)
            assertEquals("package:${context.packageName}", exact.first().data.toString())
            val notifications = compatibilitySettingsIntents(DeviceSettingsTarget.NOTIFICATIONS, context.packageName, sdk)
            assertEquals(context.packageName, notifications.first().getStringExtra(Settings.EXTRA_APP_PACKAGE))
            val battery = compatibilitySettingsIntents(DeviceSettingsTarget.BATTERY, context.packageName, sdk)
            assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.first().action)
            assertFalse(battery.any { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS })
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery.last().action)
        }
    }

    @Test fun settingsPageShowsEnvironmentAndRemainsAccessibleAfterResume() {
        check(context.packageName == "io.whaletodo.app.verification")
        val device = UiDevice.getInstance(instrument)
        val ui = UiSteps(device)
        showVerificationActivity(clearTask = true)
        clickOwnLabel("我的")
        ui.text("每日签到")
        clickOwnLabel("设置")
        // Scroll rather than assuming a screen height, font size or OEM navigation layout.
        repeat(6) {
            if (!device.hasObject(By.text("设备与兼容性"))) {
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.5f)
                device.waitForIdle()
            }
        }
        clickOwnLabel("设备与兼容性")
        ui.text("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        ui.text("通知设置")
        instrument.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        showVerificationActivity()
        ui.text("设备与兼容性")
        ui.text("通知设置")
        ownBack()
        ui.text("外观设定")
    }

    @Test fun allFourSystemPagesOpenWithoutChangingPermissionOrBatteryState() {
        check(context.packageName == "io.whaletodo.app.verification")
        val device = UiDevice.getInstance(instrument)
        showVerificationActivity()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
        val before = DeviceCompatibility.read(context)
        DeviceSettingsTarget.entries.forEach { target ->
            assertTrue(openCompatibilitySettings(context, target))
            // HyperOS redirects standard app-details to this system app; no private intent is used.
            val settings = By.pkg("com.android.settings")
            val redirected = By.pkg("com.miui.securitycenter")
            val opened = device.wait(Until.hasObject(settings), 5000) ||
                device.wait(Until.hasObject(redirected), 5000)
            assertTrue("Missing standard/OEM settings page for $target", opened)
            // Open the app again without touching any system toggle.
            showVerificationActivity()
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
        }
        val after = DeviceCompatibility.read(context)
        assertEquals(before.notificationsAllowed, after.notificationsAllowed)
        assertEquals(before.exactAlarmsAllowed, after.exactAlarmsAllowed)
        assertEquals(before.batteryExempt, after.batteryExempt)
        assertEquals(before.cameraAllowed, after.cameraAllowed)
    }
}
