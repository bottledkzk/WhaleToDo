package io.whaletodo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSelectorTest {
    private val items = listOf(
        ReminderOwnerItem("guest", "guest-1"),
        ReminderOwnerItem("A", "a-1"),
        ReminderOwnerItem("B", "b-1"),
    )

    @Test
    fun `guest only keeps guest reminders`() {
        assertEquals(
            listOf(ReminderOwnerItem("guest", "guest-1")),
            ReminderSelector.select(items, "guest"),
        )
    }

    @Test
    fun `A only keeps A reminders`() {
        assertEquals(
            listOf(ReminderOwnerItem("A", "a-1")),
            ReminderSelector.select(items, "A"),
        )
    }

    @Test
    fun `B only keeps B reminders`() {
        assertEquals(
            listOf(ReminderOwnerItem("B", "b-1")),
            ReminderSelector.select(items, "B"),
        )
    }

    @Test
    fun `blank item owner is guest`() {
        val withBlank = items + ReminderOwnerItem("   ", "blank-1")
        assertEquals(
            listOf(
                ReminderOwnerItem("guest", "guest-1"),
                ReminderOwnerItem("   ", "blank-1"),
            ),
            ReminderSelector.select(withBlank, "guest"),
        )
    }

    @Test
    fun `blank active owner is guest and does not include A`() {
        assertEquals(
            listOf(ReminderOwnerItem("guest", "guest-1")),
            ReminderSelector.select(items, "   "),
        )
    }
}
