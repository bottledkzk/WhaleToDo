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
import io.whaletodo.core.ReminderOwnerItem
import io.whaletodo.core.ReminderPlanner
import io.whaletodo.core.ReminderSelector
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
    const val CHANNEL_ID_MESSAGE = "task_reminders_message"
    const val CHANNEL_ID_ALARM = "task_reminders_alarm"
    private const val LEGACY_CHANNEL_ID = "task_reminders"
    const val PREFS_NAME = "reminders"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_KIND = "kind"
    const val EXTRA_METHOD = "method"
    const val EXTRA_TRIGGER_AT = "trigger_at"
    const val EXTRA_REQUEST_CODE = "request_code"
    const val EXTRA_OWNER = "owner"

    private const val KEY_SCHEDULED = "scheduled"
    private const val KEY_NOTIFICATION_REQUESTED = "notification.requested"
    private const val KEY_ACTIVE_OWNER = "active_owner"
    private const val GUEST = "guest"
    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private val mutex = Mutex()
    private val ownerSwitch = OwnerSwitch()

    fun init(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MESSAGE,
                "待办提醒（消息）",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "以普通通知提示事务开始与结束"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALARM,
                "待办提醒（闹钟）",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "以高优先级通知提示事务开始与结束"
            },
        )
        // 旧的单一 HIGH channel 不再使用，删除后由新 channel 接管。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    /** message 使用普通 channel，alarm/both 使用高优先级 channel。 */
    fun channelFor(method: String?): String = when (method) {
        "alarm", "both" -> CHANNEL_ID_ALARM
        else -> CHANNEL_ID_MESSAGE
    }

    fun notificationPermissionRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_REQUESTED, false)

    fun markNotificationPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
    }

    /** 当前调度 owner；未登录/冷启动无值时默认 guest。 */
    fun activeOwner(context: Context): String {
        val stored = prefs(context).getString(KEY_ACTIVE_OWNER, GUEST).orEmpty()
        return stored.trim().ifEmpty { GUEST }
    }

    /**
     * 切换 owner 并重建 Alarm：旧 owner 的请求码会先被取消，再只排程新 owner。
     * 与当前 owner 相同时不重复 resync。
     */
    suspend fun setActiveOwner(context: Context, owner: String) {
        val app = context.applicationContext
        ownerSwitch.switch(
            owner = owner,
            current = { activeOwner(app) },
            write = { normalized ->
                prefs(app).edit().putString(KEY_ACTIVE_OWNER, normalized).commit()
            },
            resync = { resyncAll(app) },
        )
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
            val active = activeOwner(app)
            val alarmManager = app.getSystemService(AlarmManager::class.java)
            cancelScheduled(app, prefs, alarmManager)

            val tasks = dao.tasks().filterNot { it.done }.associateBy { it.id }
            val blocksByTask = dao.blocks().filterNot { it.done }.groupBy { it.taskId }
            val enabled = dao.enabledReminders()
            val selected = ReminderSelector.select(
                enabled.map { ReminderOwnerItem(it.ownerId, it.taskId) },
                active,
            ).toSet()
            val scheduled = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            var count = 0
            try {
                for (reminder in enabled) {
                    if (ReminderOwnerItem(reminder.ownerId, reminder.taskId) !in selected) continue
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
                            putExtra(EXTRA_OWNER, reminder.ownerId)
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
