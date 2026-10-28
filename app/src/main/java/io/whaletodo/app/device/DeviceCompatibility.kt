package io.whaletodo.app.device

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Local capability snapshot. No hardware identifiers, reflection, persistence or upload. */
internal data class DeviceCompatibility(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidVersion: String,
    val api: Int,
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryExempt: Boolean,
    val cameraAllowed: Boolean,
) {
    // Used only for guidance. Scheduling/media decisions must use actual platform capabilities.
    val xiaomiFamily: Boolean get() =
        listOf(manufacturer, brand).any { it.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

    companion object {
        fun read(context: Context): DeviceCompatibility {
            val notificationPermission = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val alarms = context.getSystemService(AlarmManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            return DeviceCompatibility(
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                api = Build.VERSION.SDK_INT,
                notificationsAllowed = notificationPermission && NotificationManagerCompat.from(context).areNotificationsEnabled(),
                exactAlarmsAllowed = Build.VERSION.SDK_INT >= 31 && runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false),
                batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
                cameraAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }
}

internal enum class DeviceSettingsTarget { NOTIFICATIONS, EXACT_ALARMS, BATTERY, CAMERA }

/** Explicit SDK parameter allows branch checks without claiming an older OS was run. */
internal fun compatibilitySettingsIntents(
    target: DeviceSettingsTarget,
    packageName: String,
    sdk: Int = Build.VERSION.SDK_INT,
): List<Intent> {
    val packageUri = Uri.parse("package:$packageName")
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    val primary = when (target) {
        DeviceSettingsTarget.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        DeviceSettingsTarget.EXACT_ALARMS -> if (sdk >= 31)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) else appDetails
        // Opens the normal list; never requests an exemption or changes battery policy.
        DeviceSettingsTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        DeviceSettingsTarget.CAMERA -> appDetails
    }
    return listOf(primary, appDetails).distinctBy { it.action }
}

/** OEMs can omit a settings activity. Each target falls back to the standard app page. */
internal fun openCompatibilitySettings(context: Context, target: DeviceSettingsTarget): Boolean {
    for (intent in compatibilitySettingsIntents(target, context.packageName)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // A standard activity may be absent on an OEM ROM.
        } catch (_: SecurityException) {
            // Do not try private vendor activities or privileged settings.
        }
    }
    return false
}
