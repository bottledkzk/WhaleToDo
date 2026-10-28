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
import io.whaletodo.app.device.compatibilitySettingsIntents
import io.whaletodo.app.device.DeviceSettingsTarget
import io.whaletodo.app.device.DeviceCompatibility
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

/** Verification package only; no private identifiers, database writes or external requests. */
class DeviceCompatibilityTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext

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

    @Test fun allFourSystemPagesOpenWithoutChangingPermissionOrBatteryState() {
        check(context.packageName == "io.whaletodo.app.verification")
        val device = UiDevice.getInstance(instrument)
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(launch)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
        val before = DeviceCompatibility.read(context)
        DeviceSettingsTarget.entries.forEach { target ->
            assertTrue(openCompatibilitySettings(context, target))
            assertTrue("Missing standard settings page for $target", device.wait(Until.hasObject(By.pkg("com.android.settings")), 10000))
            // Open the app again without touching any system toggle.
            context.startActivity(launch)
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
        }
        val after = DeviceCompatibility.read(context)
        assertEquals(before.notificationsAllowed, after.notificationsAllowed)
        assertEquals(before.exactAlarmsAllowed, after.exactAlarmsAllowed)
        assertEquals(before.batteryExempt, after.batteryExempt)
        assertEquals(before.cameraAllowed, after.cameraAllowed)
    }
}
