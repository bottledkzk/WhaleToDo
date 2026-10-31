package io.whaletodo.app

import io.whaletodo.app.data.*
import io.whaletodo.core.Window
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PreviewPlannerTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val start = now.plusSeconds(3600)
    private val available = listOf(Window(start, start.plusSeconds(4 * 3600)))

    @Test fun agentArrangementWithoutDeadlineSurvivesReplanningAndStillBlocksItsTime() {
        val flexible = TaskRow("walk", "散步", minutes = 20)
        val due = TaskRow("due", "报告", minutes = 60, deadline = start.plusSeconds(7200).toEpochMilli())
        val existing = BlockRow("accepted", flexible.id, start.toEpochMilli(), start.plusSeconds(1200).toEpochMilli())
        val plan = previewPlan(listOf(flexible, due), listOf(existing), available, now)
        assertEquals(start, plan.blocks.first { it.taskId == flexible.id }.window.start)
        assertFalse(plan.blocks.first { it.taskId == flexible.id }.locked)
        assertTrue(plan.blocks.filter { it.taskId == due.id }.all { it.window.start >= start.plusSeconds(1200) })
        assertTrue(plan.unmet.isEmpty())
    }

    @Test fun incompleteInboxItemsExplainWhyButFullyArrangedManualItemsDoNotReportMissingFields() {
        val inbox = TaskRow("inbox", "散步", minutes = 20)
        val missing = TaskRow("missing", "想法")
        val meeting = TaskRow("meeting", "例会", minutes = 30)
        val fixed = BlockRow("fixed", meeting.id, start.toEpochMilli(), start.plusSeconds(1800).toEpochMilli(), locked = true)
        val plan = previewPlan(listOf(inbox, missing, meeting), listOf(fixed), available, now)
        assertEquals(setOf(inbox.id, missing.id), plan.unmet.map { it.taskId }.toSet())
        assertEquals("缺少截止时间", plan.unmet.first { it.taskId == inbox.id }.reason)
        assertEquals(20, plan.unmet.first { it.taskId == inbox.id }.remainingMinutes)
        assertEquals("缺少预计工时与截止时间", plan.unmet.first { it.taskId == missing.id }.reason)
        assertEquals(1, plan.blocks.size)
        assertTrue(plan.blocks.single().locked)
    }

    @Test fun calendarTimesUseSelectedZoneAndShowDatesAcrossMidnight() {
        val row = BlockRow("b", "t", Instant.parse("2030-01-01T15:30:00Z").toEpochMilli(),
            Instant.parse("2030-01-01T16:30:00Z").toEpochMilli(), locked = true)
        val state = DeskState(blocks = listOf(row), zoneId = "Asia/Shanghai")
        assertEquals(listOf("1/1 23:30–1/2 00:30 · 手动"), taskScheduleLabels(state, "t", LocalDate.parse("2030-01-02")))
        assertTrue(taskScheduleLabels(state, "t", LocalDate.parse("2030-01-03")).isEmpty())
    }
}
