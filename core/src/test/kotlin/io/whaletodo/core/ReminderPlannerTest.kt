package io.whaletodo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {
    private val now = 1_000L

    private fun window(start: Long, end: Long) = Window(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))

    @Test
    fun `start uses the earliest future block`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 5_000,
            blocks = listOf(window(3_000, 4_000), window(2_000, 3_000)),
            timing = "start",
            nowMillis = now,
        )
        assertEquals(listOf(ReminderTrigger(2_000, "start")), result)
    }

    @Test
    fun `start falls back to a future deadline when no block is future`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 5_000,
            blocks = listOf(window(200, 500)),
            timing = "start",
            nowMillis = now,
        )
        assertEquals(listOf(ReminderTrigger(5_000, "start")), result)
    }

    @Test
    fun `start returns empty once the deadline has passed and no block is future`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 900,
            blocks = listOf(window(200, 500)),
            timing = "start",
            nowMillis = now,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `end uses the latest future block end`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 9_000,
            blocks = listOf(window(2_000, 3_000), window(4_000, 6_000)),
            timing = "end",
            nowMillis = now,
        )
        assertEquals(listOf(ReminderTrigger(6_000, "end")), result)
    }

    @Test
    fun `end falls back to a future deadline when no block is future`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 7_000,
            blocks = listOf(window(200, 500)),
            timing = "end",
            nowMillis = now,
        )
        assertEquals(listOf(ReminderTrigger(7_000, "end")), result)
    }

    @Test
    fun `both returns start and end triggers in ascending order`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 9_000,
            blocks = listOf(window(3_000, 4_000), window(5_000, 8_000)),
            timing = "both",
            nowMillis = now,
        )
        assertEquals(
            listOf(ReminderTrigger(3_000, "start"), ReminderTrigger(8_000, "end")),
            result,
        )
    }

    @Test
    fun `both de-duplicates a shared deadline into one start trigger`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = 5_000,
            blocks = emptyList(),
            timing = "both",
            nowMillis = now,
        )
        assertEquals(listOf(ReminderTrigger(5_000, "start")), result)
    }

    @Test
    fun `deadline zero without blocks returns nothing`() {
        assertTrue(ReminderPlanner.triggers(0, emptyList(), "start", now).isEmpty())
        assertTrue(ReminderPlanner.triggers(0, emptyList(), "end", now).isEmpty())
        assertTrue(ReminderPlanner.triggers(0, emptyList(), "both", now).isEmpty())
    }

    @Test
    fun `only strictly future triggers are returned`() {
        val result = ReminderPlanner.triggers(
            deadlineMillis = now,
            blocks = listOf(window(now, now + 1_000), window(now + 1_000, now + 2_000)),
            timing = "both",
            nowMillis = now,
        )
        assertEquals(
            listOf(ReminderTrigger(now + 1_000, "start"), ReminderTrigger(now + 2_000, "end")),
            result,
        )
    }
}
