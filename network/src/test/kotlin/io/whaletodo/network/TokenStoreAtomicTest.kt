package io.whaletodo.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStoreAtomicTest {
    @Test
    fun `A to B save and clear with old generation both fail`() {
        val a = TokenPair("a", "ra", "A")
        val b = TokenPair("b", "rb", "B")
        val store = InMemoryTokenStore(a)

        store.save(b)
        assertFalse(store.saveIfCurrent("A", "ra", TokenPair("a2", "ra2", "A")))
        assertEquals(b, store.read())

        assertFalse(store.clearIfCurrent("A"))
        assertEquals(b, store.read())

        assertTrue(store.clearIfCurrent("B"))
        assertNull(store.read())
    }

    @Test
    fun `matching saveIfCurrent writes and matching clearIfCurrent clears`() {
        val a = TokenPair("a", "ra", "A")
        val store = InMemoryTokenStore(a)
        val next = TokenPair("a2", "ra2", "A")
        assertTrue(store.saveIfCurrent("A", "ra", next))
        assertEquals(next, store.read())
        assertTrue(store.clearIfCurrent("A"))
        assertNull(store.read())
    }
}
