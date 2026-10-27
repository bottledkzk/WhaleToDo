package io.whaletodo.app.reminder

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class OwnerSwitchTest {
    @Test
    fun `sequential switches end with the last owner`() = runBlocking {
        val current = AtomicReference("guest")
        val resynced = mutableListOf<String>()
        val switch = OwnerSwitch()

        assertTrue(switch.switch("A", { current.get() }, { current.set(it) }, { resynced += current.get() }))
        assertTrue(switch.switch("B", { current.get() }, { current.set(it) }, { resynced += current.get() }))
        assertTrue(switch.switch("guest", { current.get() }, { current.set(it) }, { resynced += current.get() }))
        assertFalse(switch.switch("guest", { current.get() }, { current.set(it) }, { resynced += current.get() }))

        assertEquals("guest", current.get())
        assertEquals(listOf("A", "B", "guest"), resynced)
    }

    @Test
    fun `concurrent switch waits for resync and last owner wins`() = runBlocking {
        val current = AtomicReference("guest")
        val switch = OwnerSwitch()

        // 第一个切换持有 owner 锁并在 resync 挂起；第二个必须等它完成后才能写入。
        val first = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            switch.switch("B", { current.get() }, { current.set(it) }, { delay(100) })
        }
        val second = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            switch.switch("guest", { current.get() }, { current.set(it) }, { })
        }
        joinAll(first, second)

        assertEquals("guest", current.get())
    }
}
