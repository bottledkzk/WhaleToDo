package io.whaletodo.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.whaletodo.app.data.DeskDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Rebuilds alarms after boot, a time zone/time change, or an app update. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = DeskDatabase.open(context)
                try {
                    ReminderCenter.resyncAll(context, db.dao())
                } finally {
                    db.close()
                }
            } catch (_: Exception) {
                // The next app start or system event will retry; never crash a manifest receiver.
            } finally {
                pending.finish()
            }
        }
    }
}
