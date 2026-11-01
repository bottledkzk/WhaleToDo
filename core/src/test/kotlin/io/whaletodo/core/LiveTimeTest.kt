package io.whaletodo.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LiveTimeTest {
    @Test fun reflectionUsesTheRequestedDayAcrossMidnightAndYear() {
        assertEquals(LocalDate.parse("2026-11-01"),LiveTime.reflectionDate("针对昨天的日程给我生成一份昨天的日记并记录",LocalDate.parse("2026-11-02")))
        assertEquals(LocalDate.parse("2025-12-31"),LiveTime.reflectionDate("昨天的日记",LocalDate.parse("2026-01-01")))
        assertEquals(LocalDate.parse("2026-11-01"),LiveTime.reflectionDate("11月1日的日记",LocalDate.parse("2026-11-02")))
    }
    @Test fun livePlanningRoundsForwardWithoutSchedulingElapsedSeconds() {
        assertEquals(Instant.parse("2026-11-01T11:12:00Z"),LiveTime.ceilMinute(Instant.parse("2026-11-01T11:11:00.001Z")))
        assertEquals(Instant.parse("2026-11-01T11:11:00Z"),LiveTime.ceilMinute(Instant.parse("2026-11-01T11:11:00Z")))
    }
    @Test fun fixedNewCourseCanBeOutsideWorkingHoursButNeverOverlapExistingBlocks() {
        val now=Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
        val start=Instant.parse("2026-11-03T02:00:00Z").toEpochMilli()
        val course=AgentChange.CreateTask("new:1","计算机网络课程","study",start,start+120*60000L,true)
        AgentChanges.validate(listOf(course),emptyList(),emptyList(),now,emptyList())
        assertThrows(IllegalArgumentException::class.java) { AgentChanges.validate(listOf(course),listOf(AgentTaskFact("existing",false,0)),listOf(AgentBlockFact("fixed","existing",start,start+60000,true,false)),now,emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AgentChanges.validate(listOf(course.copy(start=now-60000,end=now)),emptyList(),emptyList(),now,emptyList()) }
    }
}
