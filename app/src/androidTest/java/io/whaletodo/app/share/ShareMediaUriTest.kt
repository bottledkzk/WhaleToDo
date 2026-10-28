package io.whaletodo.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side regression for the P2: the old content URI must survive a later export. */
@RunWith(AndroidJUnit4::class)
class ShareMediaUriTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val written = mutableListOf<File>()

    @After
    fun cleanup() {
        written.forEach { it.delete() }
    }

    @Test
    fun twoExportsKeepBothContentUrisReadable() {
        val firstBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val secondBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val first = ShareMedia.writeSharePng(context, firstBitmap).also(written::add)
        val second = ShareMedia.writeSharePng(context, secondBitmap).also(written::add)

        val firstUri = ShareMedia.fileUri(context, first)
        val secondUri = ShareMedia.fileUri(context, second)
        assertNotEquals(firstUri, secondUri)

        val firstBytes = context.contentResolver.openInputStream(firstUri)!!.use { it.readBytes() }
        val secondBytes = context.contentResolver.openInputStream(secondUri)!!.use { it.readBytes() }
        assertTrue(firstBytes.size > 8)
        assertTrue(secondBytes.size > 8)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals(firstBytes.size, first.length().toInt())
        firstBitmap.recycle()
        secondBitmap.recycle()
    }

    @Test
    fun failedEncodeKeepsOldContentUriReadable() {
        val oldBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val old = ShareMedia.writeSharePng(context, oldBitmap).also(written::add)
        val oldUri = ShareMedia.fileUri(context, old)
        oldBitmap.recycle()

        val recycled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { recycle() }
        runCatching { ShareMedia.writeSharePng(context, recycled) }

        assertTrue(old.exists())
        val bytes = context.contentResolver.openInputStream(oldUri)!!.use { it.readBytes() }
        assertTrue(bytes.size > 8)
        assertEquals(1, written.count { it.exists() })
    }
}
