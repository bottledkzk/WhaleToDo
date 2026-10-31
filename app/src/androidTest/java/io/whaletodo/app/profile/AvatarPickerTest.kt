package io.whaletodo.app.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses only owned synthetic files; never opens the user's media library. */
class AvatarPickerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importedAvatarSurvivesRemovalOfSourceAndCanBeDecodedAgain() {
        val source = AvatarFiles.camera(context)
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        var saved: Uri? = null
        try {
            saved = Uri.parse(AvatarFiles.import(context, AvatarFiles.uri(context, source)))
            assertEquals("content", saved.scheme)
            assertTrue(saved.path.orEmpty().startsWith("/avatars/"))
            source.delete()
            context.contentResolver.openInputStream(saved).use { input ->
                val decoded = BitmapFactory.decodeStream(input)
                assertNotNull(decoded); assertEquals(80, decoded.width); assertEquals(60, decoded.height)
                decoded.recycle()
            }
        } finally { source.delete(); saved?.let { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun invalidPhotoDoesNotReplaceAnExistingAvatar() {
        val sentinel = File(context.filesDir, "avatars/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs(); writeText("owned sentinel") }
        val source = AvatarFiles.camera(context).apply { writeText("not an image") }
        try {
            assertTrue(runCatching { AvatarFiles.import(context, AvatarFiles.uri(context, source)) }.isFailure)
            assertEquals("owned sentinel", sentinel.readText())
        } finally { sentinel.delete(); source.delete() }
    }

    @Test fun cancellationAndPermissionDenialLeavePickerReusable() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val picker = AvatarPicker(context, scope)
            try {
                picker.gallery = { picker.galleryResult(null) }
                assertNull(picker.pick(AvatarSource.GALLERY))
                picker.gallery = { picker.permissionResult(false) }
                val denied = runCatching { picker.pick(AvatarSource.GALLERY) }.exceptionOrNull()
                assertTrue(denied is ProfileException)
                picker.gallery = {}
                val pending = async { picker.pick(AvatarSource.GALLERY) }
                yield(); pending.cancelAndJoin()
                picker.gallery = { picker.galleryResult(null) }
                assertNull(picker.pick(AvatarSource.GALLERY))
            } finally { picker.dispose(); scope.cancel() }
        }
    }

    @Test fun cameraReceivesOnlyItsOwnTemporaryUriWithReadWriteGrants() {
        val file = AvatarFiles.camera(context)
        try {
            val uri = AvatarFiles.uri(context, file)
            val intent = AvatarCameraContract().createIntent(context, uri)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        } finally { file.delete() }
    }
}
