package io.whaletodo.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProfileIdentityTest {
    @Test fun nicknameBudgetHandlesHanAndSupplementaryCharactersWithoutCuttingSurrogates() {
        assertEquals(16,ProfileIdentity.nicknameLength("鲸".repeat(8)))
        assertEquals("鲸".repeat(8),ProfileIdentity.clipNickname("鲸".repeat(9)))
        assertEquals(2,ProfileIdentity.nicknameLength(String(Character.toChars(0x20000))))
        assertEquals(16,ProfileIdentity.nicknameLength(ProfileIdentity.clipNickname("abc🐳"+"鲸".repeat(9))))
        assertThrows(IllegalArgumentException::class.java){ProfileIdentity.nickname("鲸".repeat(9))}
        assertThrows(IllegalArgumentException::class.java){ProfileIdentity.nickname("a\nb")}
    }
    @Test fun publicNamesHaveStableDefaultAndMottoUsesCodePoints() {
        assertEquals("Whale20261025",ProfileIdentity.defaultNickname("20261025"))
        assertEquals(ProfileIdentity.defaultNickname("same-owner"),ProfileIdentity.defaultNickname("same-owner"))
        val text="🐳".repeat(30);assertEquals(30,ProfileIdentity.mottoLength(text))
        assertEquals(text,ProfileIdentity.clipMotto(text+"鲸"));assertEquals(text,ProfileIdentity.motto(text))
        assertThrows(IllegalArgumentException::class.java){ProfileIdentity.motto(text+"a")}
        assertEquals("",ProfileIdentity.motto("  "))
    }
    @Test fun vipDaysAreCeilingOfRemainingTimeAndNeverNegative() {
        val now=Instant.parse("2026-10-25T00:00:00Z")
        assertEquals(2L,ProfileIdentity.vipDays(now.plusSeconds(86401),now));assertEquals(0L,ProfileIdentity.vipDays(now.minusSeconds(1),now));assertNull(ProfileIdentity.vipDays(null,now))
    }
    @Test fun losAngelesDaylightCanBeDisabledWithoutDependingOnCurrentSeason() {
        val summer=Instant.parse("2026-07-01T00:00:00Z");val winter=Instant.parse("2026-01-01T00:00:00Z")
        val auto=DeskZoneChoice("America/Los_Angeles",true);val standard=auto.copy(daylight=false)
        assertEquals("UTC-07:00",DeskZones.utcLabel(auto.effective(summer),summer))
        assertEquals(ZoneOffset.ofHours(-8),standard.effective(summer));assertEquals(ZoneOffset.ofHours(-8),standard.effective(winter))
        assertTrue(DeskZones.hasDaylight(ZoneId.of(auto.id),winter));assertFalse(DeskZones.hasDaylight(ZoneId.of("Asia/Shanghai"),summer))
        assertEquals(ZoneId.of("Asia/Tokyo"),DeskZoneChoice("system",false).effective(summer,ZoneId.of("Asia/Tokyo")))
        assertEquals("America/Los_Angeles",DeskZones.parse("PDT"));assertTrue(DeskZones.offsets.contains(ZoneOffset.of("+05:45")))
    }
    @Test fun localClockConversionPreservesWallTimeAndRejectsDstGaps() {
        val epoch=Instant.parse("2030-01-02T09:00:00Z").toEpochMilli()
        val moved=DeskZones.moveKeepingLocal(epoch,ZoneOffset.UTC,ZoneId.of("Asia/Shanghai"))
        assertEquals("2030-01-02T01:00:00Z",Instant.ofEpochMilli(moved).toString())
        assertThrows(IllegalArgumentException::class.java){DeskZones.moveKeepingLocal(Instant.parse("2030-03-10T02:30:00Z").toEpochMilli(),ZoneOffset.UTC,ZoneId.of("America/Los_Angeles"))}
    }
}
