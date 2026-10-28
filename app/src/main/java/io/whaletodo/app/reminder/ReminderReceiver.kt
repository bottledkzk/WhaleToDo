package io.whaletodo.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.whaletodo.app.MainActivity
import io.whaletodo.app.data.DeskDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shows a reminder only if its owner is still active and the reminder/task are still valid. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra(ReminderCenter.EXTRA_OWNER) ?: return
        if (owner != ReminderCenter.activeOwner(context)) return
        val taskId = intent.getStringExtra(ReminderCenter.EXTRA_TASK_ID) ?: return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var db: DeskDatabase? = null
            try {
                db = DeskDatabase.open(context)
                val reminder = db.dao().reminder(owner, taskId)
                if (reminder?.enabled != true) return@launch
                val task = db.dao().tasks(owner).firstOrNull { it.id == taskId }
                if (task == null || task.done) return@launch
                if (owner != ReminderCenter.activeOwner(context)) return@launch
                notify(context, intent)
            } catch (_: Exception) {
                // DB 打不开/数据竞争时放弃本次通知；下一次 resync 会纠正状态。
            } finally {
                runCatching { db?.close() }
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val title = intent.getStringExtra(ReminderCenter.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: return
        val kind = intent.getStringExtra(ReminderCenter.EXTRA_KIND)
        val method = intent.getStringExtra(ReminderCenter.EXTRA_METHOD)
        val requestCode = intent.getIntExtra(
            ReminderCenter.EXTRA_REQUEST_CODE,
            (title + kind).hashCode(),
        )
        val openApp = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderCenter.channelFor(method))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(if (kind == "start") "开始时间到了" else "已到结束时间")
            // Android O+ 的实际重要性由 channel 决定；保留 priority 供兼容路径使用。
            .setPriority(
                if (method == "message") NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH,
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        NotificationManagerCompat.from(context).let { manager ->
            runCatching { manager.notify(requestCode, notification) }
        }
    }
}
