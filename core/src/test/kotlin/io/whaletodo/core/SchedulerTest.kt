package io.whaletodo.core

import java.time.Instant
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SchedulerTest {
    private val base = Instant.parse("2026-10-22T08:00:00Z")
    private fun at(m: Long) = base.plusSeconds(m * 60)
    private fun w(a: Long, b: Long) = Window(at(a), at(b))
    private fun task(id: String = "a", minutes: Int = 60, due: Long = 240, split: Boolean = true) = Task(id, id, minutes, at(due), splittable = split)
    @Test fun touchingWindowsMerge() { assertEquals(listOf(w(0,120)), Scheduler.union(listOf(w(60,120),w(0,60)))) }
    @Test fun genuineGapRemains() { assertEquals(2, Scheduler.union(listOf(w(0,60),w(61,120))).size) }
    @Test fun overlappingAndNestedMerge() { assertEquals(listOf(w(0,120)), Scheduler.union(listOf(w(0,90),w(30,60),w(80,120)))) }
    @Test fun busySplitsAndClips() { assertEquals(listOf(w(0,30),w(60,90)), Scheduler.subtract(listOf(w(0,120)),listOf(w(30,60),w(90,180)))) }
    @Test fun capacityReported() { val r=Scheduler.plan(listOf(task(minutes=90)),listOf(w(0,60)),emptyList(),emptyList(),base); assertEquals(30,r.unmet.single().remainingMinutes); assertEquals(60,r.blocks.sumOf { it.window.minutes }.toInt()) }
    @Test fun unsplittableCannotCrossGap() { val r=Scheduler.plan(listOf(task(minutes=60,split=false)),listOf(w(0,30),w(45,75)),emptyList(),emptyList(),base); assertTrue(r.blocks.isEmpty()); assertEquals(60,r.unmet.single().remainingMinutes) }
    @Test fun preservesProtectedAndOnlySchedulesRemainingBudget() { val lock=Block("a",w(0,30),locked=true); val r=Scheduler.plan(listOf(task()),listOf(w(0,120)),emptyList(),listOf(lock),at(10)); assertTrue(r.blocks.contains(lock)); assertEquals(60,r.blocks.sumOf { it.window.minutes }.toInt()); assertTrue(r.unmet.isEmpty()) }
    @Test fun startedBlocksAndCompletedBlocksSurvive() { val old=listOf(Block("a",w(-30,30)),Block("old",w(40,60),done=true)); val r=Scheduler.plan(listOf(task()),listOf(w(0,120)),emptyList(),old,base); assertTrue(r.blocks.containsAll(old)); assertEquals(2,r.blocks.size) }
    @Test fun expiredTaskDoesNotSchedule() { val r=Scheduler.plan(listOf(task(due=-1)),listOf(w(0,120)),emptyList(),emptyList(),base); assertTrue(r.blocks.isEmpty()); assertEquals(60,r.unmet.single().remainingMinutes) }
    @Test fun earliestAndDeadlineClip() { val t=task(minutes=60,due=90).copy(earliest=at(60)); val r=Scheduler.plan(listOf(t),listOf(w(0,120)),emptyList(),emptyList(),base); assertEquals(w(60,90),r.blocks.single().window); assertEquals(30,r.unmet.single().remainingMinutes) }
    @Test fun priorityBreaksEqualDeadlineAndResultsDeterministic() { val t=listOf(task("low").copy(importance=1),task("high").copy(importance=5)); val r=Scheduler.plan(t,listOf(w(0,60)),emptyList(),emptyList(),base); assertEquals("high",r.blocks.single().taskId); assertEquals(r,Scheduler.plan(t.reversed(),listOf(w(0,60)),emptyList(),emptyList(),base)) }
    @Test(expected=IllegalArgumentException::class) fun rejectsDuplicateIds() { Scheduler.plan(listOf(task(),task()),emptyList(),emptyList(),emptyList(),base) }
    @Test fun generatedPlansConserveBudgetAndNeverOverlap() {
        val rng=Random(214)
        repeat(250) {
            val tasks=(0 until 8).map { task("t$it",rng.nextInt(15,181),rng.nextLong(90,481)).copy(importance=rng.nextInt(1,6)) }
            val busy=listOf(w(100,130),w(240,270))
            val r=Scheduler.plan(tasks,listOf(w(0,480)),busy,emptyList(),base)
            val sorted=r.blocks.sortedBy { it.window.start }
            sorted.zipWithNext().forEach { (a,b) -> assertTrue(a.window.end<=b.window.start) }
            for (b in sorted) { assertTrue(b.window.start>=base); assertTrue(b.window.end<=tasks.single { it.id==b.taskId }.deadline); assertTrue(busy.none { it.start < b.window.end && b.window.start < it.end }) }
            for (t in tasks) { val allocated=r.blocks.filter { it.taskId==t.id }.sumOf { it.window.minutes }; val remaining=r.unmet.find { it.taskId==t.id }?.remainingMinutes?:0; assertEquals(t.minutes.toLong(),allocated+remaining) }
        }
    }
}
