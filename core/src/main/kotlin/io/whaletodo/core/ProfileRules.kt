package io.whaletodo.core

import java.time.LocalDate

/** Presentation derived from server-provided sign-in dates, never a client-side wallet. */
data class SignInProgress(val streak:Int,val signedToday:Boolean,val cycleDay:Int) {
    fun completed(day:Int):Boolean = day < cycleDay || signedToday && day == cycleDay
    val celebrating:Boolean get()=signedToday && cycleDay==7
    companion object {
        fun from(dates:Set<LocalDate>,today:LocalDate):SignInProgress {
            val signed=today in dates;var day=if(signed)today else today.minusDays(1);var streak=0
            while(day in dates){streak++;day=day.minusDays(1)}
            return SignInProgress(streak,signed,if(signed)((streak-1).coerceAtLeast(0)%7)+1 else streak%7+1)
        }
        fun reward(day:Int):Int {require(day in 1..7);return if(day==7)5 else 1}
    }
}

enum class ReminderMethod(val code:String,val label:String) { MESSAGE("message","消息"),ALARM("alarm","闹钟"),BOTH("both","消息和闹钟") }
enum class ReminderTiming(val code:String,val label:String) { START("start","开始时"),END("end","结束时"),BOTH("both","开始和结束") }

object DiaryContent {
    const val LIMIT=800
    fun count(text:String)=text.codePointCount(0,text.length)
    fun validate(text:String):String {
        val clean=text.trim();require(clean.isNotBlank()){ "请写一点内容" }
        require(count(clean)<=LIMIT){ "单日日记最多800字" };return clean
    }
    /** Model scaffolding is rejected, never silently saved or treated as diary prose. */
    fun fromAssistant(text:String):String? {
        val clean=text.trim()
        if(clean.startsWith("```")||clean.startsWith("{")||Regex("我是.*(?:助手|模型)|作为.*(?:助手|模型)|以下是.*日记|已为你.*(?:生成|完成|保存)|日记草稿暂不可用|任务汇报|任务报告|WhaleToDo|tokens",RegexOption.IGNORE_CASE).containsMatchIn(clean))return null
        return runCatching{validate(clean)}.getOrNull()
    }
}
