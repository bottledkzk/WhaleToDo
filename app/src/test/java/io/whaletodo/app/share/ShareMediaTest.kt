package io.whaletodo.app.share

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ShareMediaTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("whaletodo-share-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun twoExportsKeepTwoUniqueReadableFiles() {
        val now = System.currentTimeMillis()
        val first = ShareMedia.writeNewShareFile(dir, now) { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val second = ShareMedia.writeNewShareFile(dir, now) { it.writeBytes(byteArrayOf(4, 5, 6)) }

        ShareMedia.cleanupShareFiles(dir, now)

        assertNotEquals(first.name, second.name)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), second.readBytes())
    }

    @Test
    fun failedWriteKeepsOldShareAndLeavesNoPartialFile() {
        val now = System.currentTimeMillis()
        val old = ShareMedia.writeNewShareFile(dir, now) { it.writeBytes(byteArrayOf(9, 8, 7)) }
        val oldBytes = old.readBytes()

        assertThrows(IOException::class.java) {
            ShareMedia.writeNewShareFile(dir, now) {
                it.writeBytes(byteArrayOf(0, 0, 0))
                throw IOException("injected encode failure")
            }
        }

        assertTrue(old.exists())
        assertArrayEquals(oldBytes, old.readBytes())
        assertEquals(1, ShareMedia.shareFiles(dir).size)
    }

    @Test
    fun cleanupDeletesExpiredShareOnlyButKeepsFreshAndForeignFiles() {
        val now = System.currentTimeMillis()
        val expired = shareFile("expired", now - ShareMedia.SHARE_RETENTION_MS - 60_000)
        val fresh = shareFile("fresh", now - 60_000)
        val userPhoto = File(dir, "user-photo.png").apply { writeBytes(byteArrayOf(42)) }
        val camera = File(dir, "whaletodo-camera-123.jpg").apply { writeBytes(byteArrayOf(43)) }

        ShareMedia.cleanupShareFiles(dir, now)

        assertFalse(expired.exists())
        assertTrue(fresh.exists())
        assertTrue(userPhoto.exists())
        assertTrue(camera.exists())
    }

    @Test
    fun freshFilesOverTheSoftCountCapAreNeverDeleted() {
        val now = System.currentTimeMillis()
        val fresh = (1..(ShareMedia.SHARE_MAX_FILES + 5)).map { n ->
            shareFile("fresh-$n", now - 60_000)
        }

        ShareMedia.cleanupShareFiles(dir, now)

        assertEquals(fresh.size, ShareMedia.shareFiles(dir).size)
        fresh.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun freshFileOverTheSoftByteCapIsNeverDeleted() {
        val now = System.currentTimeMillis()
        val big = shareFile("big-fresh", now - 60_000)
        RandomAccessFile(big, "rw").use { it.setLength(ShareMedia.SHARE_MAX_BYTES + 1) }

        ShareMedia.cleanupShareFiles(dir, now)

        assertTrue(big.exists())
    }

    @Test
    fun oldFilesAreRemovedOldestFirstWhileFreshFilesRemain() {
        val now = System.currentTimeMillis()
        val older = shareFile("older", now - ShareMedia.SHARE_RETENTION_MS - 120_000)
        val expired = shareFile("expired", now - ShareMedia.SHARE_RETENTION_MS - 60_000)
        val fresh = shareFile("fresh", now - 60_000)

        ShareMedia.cleanupShareFiles(dir, now)

        assertFalse(older.exists())
        assertFalse(expired.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun newShareFileIsUniqueEvenWithTheSameTimestamp() {
        val now = 1_700_000_000_000L
        val first = ShareMedia.newShareFile(dir, now)
        val second = ShareMedia.newShareFile(dir, now)

        assertNotEquals(first.name, second.name)
        assertTrue(first.name.startsWith(ShareMedia.SHARE_PREFIX))
        assertTrue(first.name.endsWith(".png"))
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    private fun shareFile(label: String, lastModified: Long): File {
        val file = File(dir, "${ShareMedia.SHARE_PREFIX}$label-${UUID.randomUUID()}.png")
        file.writeBytes(byteArrayOf(1))
        if (!file.setLastModified(lastModified)) error("cannot set mtime")
        return file
    }
}
