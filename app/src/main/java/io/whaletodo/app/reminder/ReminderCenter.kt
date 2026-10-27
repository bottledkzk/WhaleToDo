package io.whaletodo.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.whaletodo.app.data.DeskDao
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.core.ReminderPlanner
import io.whaletodo.core.Window
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rebuilds every task alarm from the persisted reminder rows.
 * Only future triggers are scheduled; previous alarms are cancelled by their stored request codes.
 */
object ReminderCenter {
    const val CHANNEL_ID = "task_reminders"
    const val PREFS_NAME = "reminders"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_KIND = "kind"
    const val EXTRA_METHOD = "method"
    const val EXTRA_TRIGGER_AT = "trigger_at"
    const val EXTRA_REQUEST_CODE = "request_code"

    private const val KEY_SCHEDULED = "scheduled"
    private const val KEY_NOTIFICATION_REQUESTED = "notification.requested"
    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private val mutex = Mutex()

    fun init(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "待办提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "事务开始与结束时的提醒"
            },
        )
    }

    fun notificationPermissionRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
    }

    /** Opens the database for callers that do not already own one (activity/view model startup). */
    suspend fun resyncAll(context: Context): Int = withContext(Dispatchers.IO) {
        val db = DeskDatabase.open(context)
        try {
            resyncAll(context, db.dao())
        } finally {
            db.close()
        }
    }

    /** Rebuilds alarms against an already opened database (boot/time-change receiver). */
    suspend fun resyncAll(context: Context, dao: DeskDao): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            init(context)
            val app = context.applicationContext
            val prefs = prefs(app)
            val alarmManager = app.getSystemService(AlarmManager::class.java)
            cancelScheduled(app, prefs, alarmManager)

            val tasks = dao.tasks().filterNot { it.done }.associateBy { it.id }
            val blocksByTask = dao.blocks().filterNot { it.done }.groupBy { it.taskId }
            val scheduled = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            var count = 0
            try {
                for (reminder in dao.enabledReminders()) {
                    val task = tasks[reminder.taskId] ?: continue
                    val windows = blocksByTask[task.id].orEmpty().mapNotNull { block ->
                        runCatching {
                            Window(Instant.ofEpochMilli(block.start), Instant.ofEpochMilli(block.end))
                        }.getOrNull()
                    }
                    for (trigger in ReminderPlanner.triggers(
                        deadlineMillis = task.deadline,
                        blocks = windows,
                        timing = reminder.timing,
                        nowMillis = now,
                    )) {
                        val requestCode = (reminder.ownerId + task.id + trigger.kind).hashCode()
                        val intent = Intent(app, ReminderReceiver::class.java).apply {
                            putExtra(EXTRA_TASK_ID, task.id)
                            putExtra(EXTRA_TITLE, task.title)
                            putExtra(EXTRA_KIND, trigger.kind)
                            putExtra(EXTRA_METHOD, reminder.method)
                            putExtra(EXTRA_TRIGGER_AT, trigger.atMillis)
                            putExtra(EXTRA_REQUEST_CODE, requestCode)
                        }
                        val pending = PendingIntent.getBroadcast(app, requestCode, intent, PI_FLAGS)
                        schedule(alarmManager, trigger.atMillis, pending)
                        scheduled += requestCode.toString()
                        count++
                    }
                }
            } finally {
                // Persist even a partial pass so a later resync can cancel alarms scheduled before a failure.
                prefs.edit().putStringSet(KEY_SCHEDULED, scheduled).apply()
            }
            count
        }
    }

    private fun cancelScheduled(
        context: Context,
        prefs: android.content.SharedPreferences,
        alarmManager: AlarmManager,
    ) {
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().toSet().forEach { stored ->
            val requestCode = stored.toIntOrNull() ?: return@forEach
            val intent = Intent(context, ReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, requestCode, intent, flags) ?: return@forEach
            alarmManager.cancel(pending)
            pending.cancel()
        }
        prefs.edit().remove(KEY_SCHEDULED).apply()
    }

    private fun schedule(alarmManager: AlarmManager, atMillis: Long, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                return
            } catch (_: SecurityException) {
                // Capability changed between the check and the call: fall back to an inexact alarm.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
