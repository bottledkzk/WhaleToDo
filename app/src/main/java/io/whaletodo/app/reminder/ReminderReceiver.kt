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

/** Shows the task reminder once the system fires its alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
