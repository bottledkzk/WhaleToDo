package io.whaletodo.core

/** Minimal owner/task pair used to filter reminders before scheduling. */
data class ReminderOwnerItem(val ownerId: String, val taskId: String)

object ReminderSelector {
    /** Keep only the active owner's reminders; blank/unknown active owner means guest. */
    fun select(items: List<ReminderOwnerItem>, activeOwner: String): List<ReminderOwnerItem> {
        val owner = activeOwner.trim().ifEmpty { "guest" }
        return items.filter { it.ownerId == owner }
    }
}
