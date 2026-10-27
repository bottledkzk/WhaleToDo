package io.whaletodo.app.account

import io.whaletodo.network.AccountProfile
import io.whaletodo.network.Entitlements
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionTest {
    @Test
    fun `begin requires matching credential session`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertFalse(session.begin("B"))
        assertNull(session.currentSessionId())
        assertTrue(session.begin("A"))
        assertEquals("A", session.currentSessionId())
    }

    @Test
    fun `stale generation cannot update balance mark or clear new session`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertTrue(session.begin("A"))
        val profile = AccountProfile("u1", "user", null)
        val entitlements = Entitlements(false, null)
        assertTrue(session.update("A", "a@example.invalid", profile, entitlements, 5))

        stored = "B"
        assertTrue(session.begin("B"))
        assertFalse(session.update("A", "a@example.invalid", profile, entitlements, 9))
        assertFalse(session.updateBalance("A", "u1", entitlements, 9))
        assertFalse(session.markSignedIn("A", "u1", LocalDate.of(2026, 10, 27)))
        assertFalse(session.clear("A"))

        assertEquals("B", session.currentSessionId())
        assertNull(session.email.value)
        assertNull(session.details.value)
        assertNull(session.access.value.userId)
        assertEquals(0, session.access.value.availablePoints)
        assertFalse(session.access.value.vip)
        assertFalse(session.access.value.connected)
    }

    @Test
    fun `begin new session clears old profile before new load`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertTrue(session.begin("A"))
        val profile = AccountProfile("u1", "user", "A 用户")
        assertTrue(session.update("A", "a@example.invalid", profile, Entitlements(true, null), 5))
        assertNotNull(session.details.value)

        stored = "B"
        assertTrue(session.begin("B"))
        assertEquals("B", session.currentSessionId())
        assertNull(session.email.value)
        assertNull(session.details.value)
        assertEquals(0, session.access.value.availablePoints)
        assertNull(session.access.value.userId)
    }

    @Test
    fun `begin same session preserves loaded profile`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertTrue(session.begin("A"))
        val profile = AccountProfile("u1", "user", "A 用户")
        assertTrue(session.update("A", "a@example.invalid", profile, Entitlements(true, null), 5))

        assertTrue(session.begin("A"))
        assertEquals("A", session.currentSessionId())
        assertEquals("a@example.invalid", session.email.value)
        assertEquals("u1", session.details.value?.userId)
        assertEquals(5, session.access.value.availablePoints)
    }

    @Test
    fun `new session can update after begin`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertTrue(session.begin("A"))
        stored = "B"
        assertTrue(session.begin("B"))
        val profile = AccountProfile("u2", "user-b", "B 用户")
        assertTrue(session.update("B", "b@example.invalid", profile, Entitlements(false, null), 3))
        assertEquals("B", session.currentSessionId())
        assertEquals("u2", session.access.value.userId)
        assertEquals(3, session.access.value.availablePoints)
    }

    @Test
    fun `matching generation writes and clears`() {
        var stored = "A"
        val session = AccountSession { stored }
        assertTrue(session.begin("A"))
        val profile = AccountProfile("u1", "user", null)
        assertTrue(session.update("A", "a@example.invalid", profile, Entitlements(false, null), 5))
        assertTrue(session.updateBalance("A", "u1", Entitlements(true, null), 9))
        assertTrue(session.markSignedIn("A", "u1", LocalDate.of(2026, 10, 27)))
        assertEquals(9, session.access.value.availablePoints)
        assertTrue(session.access.value.vip)
        assertTrue(session.clear("A"))
        assertNull(session.currentSessionId())
        assertNull(session.details.value)
        assertNull(session.access.value.userId)
    }
}
