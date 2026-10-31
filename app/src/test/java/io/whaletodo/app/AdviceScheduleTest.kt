package io.whaletodo.app

import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class AdviceScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.parse("2030-01-01")
    private fun ms(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun source(tasks: List<TaskRow>, blocks: List<BlockRow> = emptyList()) = DeskSnapshot(tasks, blocks, SettingRow(), emptyList(), date, zone.id)

    @Test fun complexAdviceBecomesCompleteNonOverlappingPlanWithRealBreaksAndFixedMeetings() {
        val tasks = listOf(TaskRow("a", "演示·算法", 90, ms(13)), TaskRow("b", "演示·彩排", 45, ms(18)),
            TaskRow("c", "演示·报告", 120, ms(20)), TaskRow("lunch", "午餐", 60, category = "rest"),
            TaskRow("meeting", "例会", 30, category = "work"), TaskRow("walk", "散步", 20, category = "exercise"))
        val blocks = listOf(BlockRow("fixed-lunch", "lunch", ms(12), ms(13), locked = true),
            BlockRow("fixed-meeting", "meeting", ms(14), ms(14, 30), locked = true))
        val snapshot = source(tasks, blocks)
        val draft = arrangeAdvice(snapshot, "每连续工作60分钟休息10分钟", "算法、彩排、报告、午餐、例会和散步都要安排", ms(8))
        val result = draft.preview.result.blocks
        assertEquals(6, draft.taskIds.size)
        for (task in tasks) assertEquals(task.minutes.toLong(), result.filter { it.taskId == task.id }.sumOf { it.window.minutes })
        assertTrue(result.zipWithNext().all { (a,b) -> a.window.end <= b.window.start })
        for (fixed in blocks) assertTrue(result.any { it.taskId == fixed.taskId && it.window.start.toEpochMilli() == fixed.start && it.window.end.toEpochMilli() == fixed.end && it.locked })
        assertTrue(result.filter { b -> tasks.first { it.id == b.taskId }.deadline > 0 }.all { b -> b.window.end.toEpochMilli() <= tasks.first { it.id == b.taskId }.deadline })
        var previous: Instant? = null; var run = 0L
        for (b in result.filter { it.taskId !in setOf("lunch", "walk") }) {
            if (previous == null || Duration.between(previous, b.window.start).toMinutes() >= 10) run = 0
            run += b.window.minutes; assertTrue("Continuous work including the fixed meeting must stay within the limit", run <= 60)
            previous = b.window.end
        }
        assertEquals(draft.preview.result, executableAdvicePlan(snapshot, draft.taskIds, draft.maxWork, draft.rest, draft.generatedAt))
    }

    @Test fun unrelatedUnfixedArrangementIsRetainedAndAmbiguousShortNamesDoNotSelectTasks() {
        val keep = BlockRow("keep", "other", ms(9), ms(10))
        val snapshot = source(listOf(TaskRow("a", "报告", 30, ms(12)), TaskRow("other", "会议", 60)), listOf(keep))
        val plan = arrangeAdvice(snapshot, "安排报告", "先做报告", ms(8))
        assertEquals(setOf("a"), plan.taskIds)
        assertTrue(plan.preview.result.blocks.any { it.taskId == "other" && it.window.start.toEpochMilli() == keep.start && !it.locked })
        assertTrue(plan.preview.result.blocks.first { it.taskId == "a" }.window.start.toEpochMilli() >= keep.end)
        val ambiguous = source(listOf(TaskRow("a", "数学·复习", 30), TaskRow("b", "英语·复习", 30)))
        assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(ambiguous, "安排", "复习", ms(8)) }
    }

    @Test fun impossibleDeadlineOrUnsplitDurationRequiresClarificationWithoutPartialPlan() {
        assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(source(listOf(TaskRow("a", "报告", 90, ms(10)))), "每连续工作60分钟休息10分钟", "报告", ms(9)) }
        assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(source(listOf(TaskRow("a", "报告", 90, ms(15), splittable = false))), "连续工作60分钟休息10分钟", "报告", ms(8)) }
    }

    @Test fun tomorrowAndAbsoluteDatesRestrictEveryNewBlockToThatDay() {
        val snapshot=source(listOf(TaskRow("a","报告",30)))
        for(question in listOf("明天安排报告","2030年1月2日安排报告")) {
            val plan=arrangeAdvice(snapshot,question,"安排报告",ms(8))
            assertEquals(date.plusDays(1),plan.firstDate);assertEquals(1,plan.days)
            assertTrue(plan.preview.result.blocks.all{it.window.start.atZone(zone).toLocalDate()==date.plusDays(1)})
        }
        assertThrows(IllegalArgumentException::class.java){arrangeAdvice(snapshot,"今天或明天安排报告","报告",ms(8))}
    }

    @Test fun explicitScopeAndExclusionsCannotBeExpandedByTheModelMentioningOtherTasks() {
        val snapshot=source(listOf(TaskRow("a","报告",30),TaskRow("b","散步",20)))
        val one=arrangeAdvice(snapshot,"今天只安排报告，不要安排散步","报告和散步都可以做",ms(8))
        assertEquals(setOf("a"),one.taskIds)
        val pause=arrangeAdvice(snapshot,"今天安排报告，每连续工作60分钟后，休息10分钟","报告",ms(8))
        assertEquals(60,pause.maxWork);assertEquals(10,pause.rest)
        assertThrows(IllegalArgumentException::class.java){arrangeAdvice(snapshot,"连续工作60分钟休息0分钟，安排报告","报告",ms(8))}
    }

    @Test fun afternoonAndExplicitAvailabilityNarrowConfiguredWindowsWithoutInventingExtraWorkingTime() {
        val snapshot=source(listOf(TaskRow("a","报告",30)))
        val afternoon=arrangeAdvice(snapshot,"明天下午安排报告","报告",ms(8))
        assertTrue(afternoon.preview.result.blocks.all{it.window.start.atZone(zone).hour>=12&&it.window.end.atZone(zone).hour<=18})
        val limited=arrangeAdvice(snapshot,"明天可用时间14:30到16:00，安排报告","报告",ms(8))
        assertEquals(14,limited.preview.result.blocks.single().window.start.atZone(zone).hour)
        assertEquals(30,limited.preview.result.blocks.single().window.start.atZone(zone).minute)
        assertThrows(IllegalArgumentException::class.java){arrangeAdvice(snapshot,"2030年2月30日安排报告","报告",ms(8))}
        assertThrows(IllegalArgumentException::class.java){arrangeAdvice(snapshot,"周末安排报告","报告",ms(8))}
    }

    @Test fun negativeSchedulingVerbsExcludeTasksEvenWhenTheModelMentionsThem() {
        val snapshot=source(listOf(TaskRow("a","报告",30),TaskRow("b","会议",30)))
        for(negative in listOf("不安排报告","不排报告","不要排报告","不要把报告排进去","报告不安排")) {
            assertEquals(setOf("b"),arrangeAdvice(snapshot,"$negative，只安排会议","报告和会议",ms(8)).taskIds)
        }
    }

    @Test fun continuousWorkWithoutRestRequiresClarificationInsteadOfDroppingTheLimit() {
        val snapshot=source(listOf(TaskRow("a","报告",90)))
        val error=assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(snapshot,"连续工作60分钟，安排报告","报告",ms(8)) }
        assertTrue(error.message!!.contains("休息"))
        assertThrows(IllegalArgumentException::class.java) { executableAdvicePlan(snapshot,setOf("a"),60,0,ms(8)) }
    }

    @Test fun prefixlessClockRangeAndShortHyphenDateAreExplicitConstraints() {
        val snapshot=source(listOf(TaskRow("a","报告",30)))
        for(q in listOf("明天14:00到15:00安排报告","01-02 14:00到15:00安排报告")) {
            val plan=arrangeAdvice(snapshot,q,"报告",ms(8))
            assertEquals(date.plusDays(1),plan.firstDate)
            assertTrue(plan.preview.result.blocks.all{it.window.start.atZone(zone).hour>=14&&it.window.end.atZone(zone).hour<=15})
        }
        assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(snapshot,"明天14:99到15:00安排报告","报告",ms(8)) }
    }

    @Test fun thisWeekMeansTodayThroughSundayAndDateIntervalsAreExplicitlyRejected() {
        val snapshot=source(listOf(TaskRow("a","报告",30)))
        val week=arrangeAdvice(snapshot,"本周安排报告","报告",ms(8))
        assertEquals(date,week.firstDate);assertEquals(8-date.dayOfWeek.value,week.days)
        val next=arrangeAdvice(snapshot,"下周安排报告","报告",ms(8))
        assertEquals(DayOfWeek.MONDAY,next.firstDate.dayOfWeek);assertEquals(7,next.days)
        val error=assertThrows(IllegalArgumentException::class.java) { arrangeAdvice(snapshot,"1月2日到1月5日安排报告","报告",ms(8)) }
        assertTrue(error.message!!.contains("日期区间"))
    }

    @Test fun displayedBreaksCannotIncludeAnUnselectedWorkBlock() {
        val snapshot=source(listOf(TaskRow("a","报告",30),TaskRow("b","会议",30),TaskRow("c","其他工作",20)))
        fun block(id:String,start:Long,end:Long)=Block(id,Window(Instant.ofEpochMilli(start),Instant.ofEpochMilli(end)))
        val result=ScheduleResult(listOf(block("a",ms(9),ms(9,30)),block("c",ms(9,35),ms(9,55)),block("b",ms(10),ms(10,30))),emptyList())
        val text=AdviceArrangement(PlanPreview(snapshot,result),setOf("a","b"),60,10,ms(8)).display()
        assertFalse(text.contains("休息（"))
    }
}
