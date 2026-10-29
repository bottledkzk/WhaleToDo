package io.whaletodo.app.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.whaletodo.app.BuildConfig

@Composable
internal fun DeviceCompatibilityPanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember(context) { mutableStateOf(DeviceCompatibility.read(context)) }
    var settingError by remember { mutableStateOf(false) }
    // Permission/battery changes in Settings must appear immediately on return.
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) snapshot = DeviceCompatibility.read(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings(target: DeviceSettingsTarget) {
        settingError = !openCompatibilitySettings(context, target)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("${snapshot.manufacturer} · ${snapshot.model}", style = MaterialTheme.typography.titleMedium)
            Text("Android ${snapshot.androidVersion}（API ${snapshot.api}）")
            Text("WhaleToDo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
        item {
            CompatibilityStatus("通知", if (snapshot.notificationsAllowed) "应用通知已允许" else "应用通知未允许")
            Text("提醒还受各通知类别、勿扰和锁屏设置影响", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(DeviceSettingsTarget.NOTIFICATIONS) }) { Text("通知设置") }
        }
        item {
            CompatibilityStatus("提醒精度", if (snapshot.exactAlarmsAllowed) "系统允许精确提醒" else "普通提醒，可能延迟")
            Text("本版未申请精确闹钟权限；系统未提供对应设置时可查看应用信息", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(DeviceSettingsTarget.EXACT_ALARMS) }) { Text("提醒精度设置") }
            CompatibilityStatus("后台运行", if (snapshot.batteryExempt) "已豁免系统电池优化" else "受系统电池优化管理")
            OutlinedButton(onClick = { openSettings(DeviceSettingsTarget.BATTERY) }) { Text("电池优化设置") }
            Text("锁屏或省电模式可能影响到达时间，请在实际使用状态下检查提醒", style = MaterialTheme.typography.bodySmall)
            if (snapshot.xiaomiFamily) {
                Text("小米设备可在应用信息中查看通知、锁屏显示、自启动及省电设置；入口随系统版本变化", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            CompatibilityStatus("拍照", if (snapshot.cameraAllowed) "相机权限已允许" else "拍照时按需申请相机权限")
            Text("选图使用系统选择器，取消选择不会改动原内容", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(DeviceSettingsTarget.CAMERA) }) { Text("相机权限设置") }
        }
        if (settingError) item {
            Text("无法打开系统设置，请在手机设置中搜索 WhaleToDo", color = MaterialTheme.colorScheme.error)
        }
        item {
            Text("设备信息仅在本页显示，系统和权限状态用于排查问题，不能保证后台提醒一定准时", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { snapshot = DeviceCompatibility.read(context) }) { Text("刷新状态") }
        }
    }
}

@Composable
private fun CompatibilityStatus(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(value)
    }
}
