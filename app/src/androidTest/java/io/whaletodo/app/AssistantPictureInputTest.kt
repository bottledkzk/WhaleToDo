package io.whaletodo.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.agent.AssistantPictures
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AssistantPictureInputTest {
    @Test fun selectedPictureBecomesBoundedPrivateJpegWithoutChangingTheOriginal() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=File(context.cacheDir,"picture-test-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888)
        var copied:File?=null
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            original.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val before=original.readBytes()
            copied=File(AssistantPictures.load(context,Uri.fromFile(original)))
            assertTrue(copied.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
            assertTrue(copied.length()<=AssistantPictures.MAX_BYTES)
            assertArrayEquals(before,original.readBytes())
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(copied.path,bounds)
            assertEquals("image/jpeg",bounds.outMimeType);assertTrue(bounds.outWidth<=1920&&bounds.outHeight<=1920)
        }finally{bitmap.recycle();original.delete();copied?.delete()}
    }
}
