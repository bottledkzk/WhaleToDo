package io.whaletodo.app.reminder

import java.util.concurrent.atomic.AtomicReference
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

class OwnerSwitchTest {
    @Test
    fun `sequential switches end with the last owner`() = runBlocking {
        val current = AtomicReference("guest")
        val resynced = mutableListOf<String>()
        val switch = OwnerSwitch()

        assertTrue(
            switch.switch(
                owner = "A",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { resynced += current.get() },
            ),
        )
        assertTrue(
            switch.switch(
                owner = "B",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { resynced += current.get() },
            ),
        )
        assertTrue(
            switch.switch(
                owner = "guest",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { resynced += current.get() },
            ),
        )
        assertFalse(
            switch.switch(
                owner = "guest",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { resynced += current.get() },
            ),
        )

        assertEquals("guest", current.get())
        assertEquals(listOf("A", "B", "guest"), resynced)
    }

    @Test
    fun `concurrent switch waits for resync and last owner wins`() = runBlocking {
        val current = AtomicReference("guest")
        val switch = OwnerSwitch()

        // 第一个切换持有 owner 锁并在 resync 挂起；第二个必须等它完成后才能写入。
        val first = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            switch.switch(
                owner = "B",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { delay(100) },
            )
        }
        val second = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            switch.switch(
                owner = "guest",
                currentOwner = { current.get() },
                write = { current.set(it) },
                resync = { },
            )
        }
        joinAll(first, second)

        assertEquals("guest", current.get())
    }

    @Test
    fun `stale generation callback is rejected without write or resync`() = runBlocking {
        val currentOwner = AtomicReference("A")
        var writes = 0
        var resyncs = 0
        val switch = OwnerSwitch()

        val changed = switch.switch(
            owner = "A",
            expectedSession = "A",
            currentSession = { "B" },
            currentOwner = { currentOwner.get() },
            write = {
                currentOwner.set(it)
                writes++
            },
            resync = { resyncs++ },
        )

        assertFalse(changed)
        assertEquals("A", currentOwner.get())
        assertEquals(0, writes)
        assertEquals(0, resyncs)
    }

    @Test
    fun `current generation callback switches owner and resyncs`() = runBlocking {
        val currentOwner = AtomicReference("A")
        var resyncs = 0
        val switch = OwnerSwitch()

        val changed = switch.switch(
            owner = "B",
            expectedSession = "B",
            currentSession = { "B" },
            currentOwner = { currentOwner.get() },
            write = { currentOwner.set(it) },
            resync = { resyncs++ },
        )

        assertTrue(changed)
        assertEquals("B", currentOwner.get())
        assertEquals(1, resyncs)
    }

    @Test
    fun `guest callback cancels old owner in current generation`() = runBlocking {
        val currentOwner = AtomicReference("A")
        var resyncs = 0
        val switch = OwnerSwitch()

        val changed = switch.switch(
            owner = "guest",
            expectedSession = "B",
            currentSession = { "B" },
            currentOwner = { currentOwner.get() },
            write = { currentOwner.set(it) },
            resync = { resyncs++ },
        )

        assertTrue(changed)
        assertEquals("guest", currentOwner.get())
        assertEquals(1, resyncs)
    }

    @Test
    fun `guest callback still applies after its session was cleared`() = runBlocking {
        val currentOwner = AtomicReference("A")
        var resyncs = 0
        val switch = OwnerSwitch()

        // 登出/401：session.clear 后 currentSession 已是 null，guest 清理必须落地。
        val changed = switch.switch(
            owner = "guest",
            expectedSession = "B",
            currentSession = { null },
            currentOwner = { currentOwner.get() },
            write = { currentOwner.set(it) },
            resync = { resyncs++ },
        )

        assertTrue(changed)
        assertEquals("guest", currentOwner.get())
        assertEquals(1, resyncs)
    }

    @Test
    fun `cleared session cannot reactivate old owner`() = runBlocking {
        val currentOwner = AtomicReference("guest")
        var writes = 0
        val switch = OwnerSwitch()

        val changed = switch.switch(
            owner = "A",
            expectedSession = "B",
            currentSession = { null },
            currentOwner = { currentOwner.get() },
            write = {
                currentOwner.set(it)
                writes++
            },
            resync = { },
        )

        assertFalse(changed)
        assertEquals("guest", currentOwner.get())
        assertEquals(0, writes)
    }
}
