package io.whaletodo.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProfileRulesTest {
    private val today=LocalDate.of(2030,1,7)
    @Test fun serverHistoryDrivesSeventhDayAndRepeatingCycles(){
        val six=(1L..6L).map{today.minusDays(it)}.toSet()
        val before=SignInProgress.from(six,today);assertEquals(6,before.streak);assertEquals(7,before.cycleDay);assertFalse(before.celebrating)
        val seven=SignInProgress.from(six+today,today);assertEquals(7,seven.streak);assertTrue(seven.celebrating);assertTrue(seven.completed(7))
        val fourteen=SignInProgress.from((0L..13L).map{today.minusDays(it)}.toSet(),today);assertEquals(14,fourteen.streak);assertTrue(fourteen.celebrating)
        assertEquals(1,SignInProgress.from(six+today,today.plusDays(1)).cycleDay)
    }
    @Test fun missedDayResetsStreakAndFutureHistoryCannotGrantTodayReward(){
        val p=SignInProgress.from(setOf(today.minusDays(2),today.plusDays(1)),today)
        assertEquals(0,p.streak);assertFalse(p.signedToday);assertEquals(1,p.cycleDay)
    }
    @Test fun displayedWeeklyRewardScheduleIsOneThroughSixAndFiveOnSeven(){
        assertEquals(listOf(1,1,1,1,1,1,5),(1..7).map(SignInProgress::reward))
    }
    @Test fun diaryLimitCountsUnicodeCharactersWithoutCuttingEmoji(){
        val text="🐳".repeat(800);assertEquals(800,DiaryContent.count(text));assertEquals(text,DiaryContent.validate(text))
        assertThrows(IllegalArgumentException::class.java){DiaryContent.validate(text+"🐳")}
    }
    @Test fun assistantReportsAreRejectedButUserCanEditTheirOwnDiaryProse(){
        for(text in listOf("已为你生成今天的日记", "以下是你的日记", "任务汇报：已完成", "我是WhaleToDo助手", "```日记```"))assertNull(DiaryContent.fromAssistant(text))
        assertEquals("今天读完一章书。",DiaryContent.fromAssistant("今天读完一章书。"))
        assertEquals("以下是我的日记",DiaryContent.validate("以下是我的日记"))
    }
    @Test fun dateOverridesBeatWeekdayPreferencesWhichBeatDefault(){
        val first=LocalDate.of(2030,1,7);assertEquals(DayOfWeek.MONDAY,first.dayOfWeek)
        val out=DailyWindows.build(first,3,ZoneOffset.UTC,MinuteRange(540,1080),mapOf(first.plusDays(1) to MinuteRange(600,660)),mapOf(DayOfWeek.MONDAY to MinuteRange(720,780),DayOfWeek.TUESDAY to MinuteRange(900,960)))
        assertEquals(listOf(12,10,9),out.map{it.start.atZone(ZoneOffset.UTC).hour})
    }
}
