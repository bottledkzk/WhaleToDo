package io.whaletodo.core

import java.text.Normalizer
import java.time.*

/** Public display text is separate from account identifiers and credentials. */
object ProfileIdentity {
    const val DEFAULT_MOTTO = "Whale to do， will to do"
    private fun points(text:String)=text.codePoints().toArray()
    fun nicknameLength(text:String)=points(text).sumOf { if(Character.UnicodeScript.of(it)==Character.UnicodeScript.HAN)2 else 1 }
    fun mottoLength(text:String)=text.codePointCount(0,text.length)
    fun clipNickname(text:String):String {
        val out=StringBuilder();var size=0
        for(point in points(text)){val weight=if(Character.UnicodeScript.of(point)==Character.UnicodeScript.HAN)2 else 1;if(size+weight>16)break;out.appendCodePoint(point);size+=weight}
        return out.toString()
    }
    fun clipMotto(text:String):String=text.substring(0,text.offsetByCodePoints(0,mottoLength(text).coerceAtMost(30)))
    private fun singleLine(text:String):String {
        val clean=Normalizer.normalize(text.trim(),Normalizer.Form.NFC)
        require(points(clean).none { Character.isISOControl(it)||it==0x2028||it==0x2029 }) { "请使用单行文字" }
        return clean
    }
    fun nickname(text:String):String=singleLine(text).also { require(it.isNotBlank()&&nicknameLength(it)<=16) { "请检查昵称" } }
    fun motto(text:String):String=singleLine(text).also { require(mottoLength(it)<=30) { "请检查Motto" } }
    fun defaultNickname(publicId:String):String {
        val number=publicId.filter(Char::isDigit).takeLast(8).ifBlank { kotlin.math.abs(publicId.hashCode().toLong()).toString().takeLast(8) }
        return "Whale$number"
    }
    fun vipDays(expires:Instant?,now:Instant=Instant.now()):Long?=expires?.let {
        val seconds=Duration.between(now,it).seconds
        if(seconds<=0)0L else (seconds+86399)/86400
    }
}

/** A disabled DST option uses the region's standard offset, rather than a summer abbreviation. */
data class DeskZoneChoice(val id:String,val daylight:Boolean=true) {
    fun effective(now:Instant=Instant.now(),system:ZoneId=ZoneId.systemDefault()):ZoneId {
        if(id=="system")return system
        val zone=ZoneId.of(id)
        return if(daylight)zone else zone.rules.getStandardOffset(now)
    }
}
object DeskZones {
    fun parse(value:String):String = when(value.trim().uppercase()) {
        "PDT","PST"->"America/Los_Angeles"
        else->ZoneId.of(value.trim()).id
    }
    fun hasDaylight(zone:ZoneId,now:Instant=Instant.now()):Boolean {
        val year=now.atZone(zone).year
        return (1..12).any { zone.rules.getDaylightSavings(LocalDate.of(year,it,15).atStartOfDay(zone).toInstant())!=Duration.ZERO }
    }
    fun utcLabel(zone:ZoneId,now:Instant=Instant.now()):String {
        val offset=zone.rules.getOffset(now)
        return "UTC${if(offset==ZoneOffset.UTC)"+00:00" else offset.id}"
    }
    val offsets:List<ZoneOffset> = listOf(-720,-660,-600,-570,-540,-480,-420,-360,-300,-240,-210,-180,-120,-60,0,60,120,180,210,240,270,300,330,345,360,390,420,480,525,540,570,600,630,660,720,765,780,840).map { ZoneOffset.ofTotalSeconds(it*60) }
    fun moveKeepingLocal(epoch:Long,from:ZoneId,to:ZoneId):Long {
        val old=Instant.ofEpochMilli(epoch).atZone(from);val local=old.toLocalDateTime()
        require(to.rules.getValidOffsets(local).isNotEmpty()) { "该时区存在无效日程时刻" }
        return ZonedDateTime.ofLocal(local,to,old.offset).toInstant().toEpochMilli()
    }
}
