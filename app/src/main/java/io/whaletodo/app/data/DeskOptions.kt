package io.whaletodo.app.data

import io.whaletodo.core.*
import java.time.*

object DeskOptions {
    fun zoneChoice(rows:List<UiPreferenceRow>)=DeskZoneChoice(rows.find{it.key=="time.zone"}?.value?:"system",rows.find{it.key=="time.dst"}?.value!="false")
    fun zone(rows:List<UiPreferenceRow>):ZoneId = runCatching{zoneChoice(rows).effective()}.getOrElse{ZoneId.systemDefault()}
    private fun range(value:String?):MinuteRange? = value?.split(',')?.takeIf{it.size==2}?.let{runCatching{MinuteRange(it[0].toInt(),it[1].toInt())}.getOrNull()}
    fun defaults(rows:List<UiPreferenceRow>,legacy:SettingRow):MinuteRange = range(rows.find{it.key=="working.default"}?.value) ?: MinuteRange(legacy.startHour*60,legacy.endHour*60)
    fun weekdays(rows:List<UiPreferenceRow>):Map<DayOfWeek,MinuteRange> = (1..7).mapNotNull{n->range(rows.find{it.key=="working.$n"}?.value)?.let{DayOfWeek.of(n) to it}}.toMap()
    fun forDay(date:LocalDate,rows:List<UiPreferenceRow>,legacy:SettingRow):MinuteRange = weekdays(rows)[date.dayOfWeek] ?: defaults(rows,legacy)
}
