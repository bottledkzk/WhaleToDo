package io.whaletodo.app.profile

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.whaletodo.app.share.ShareMedia
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The account bridge never retains an Activity or an external media permission. */
internal object AvatarPickerHub {
    var picker: AvatarPicker? = null
    suspend fun pick(source: AvatarSource): String? = withContext(Dispatchers.Main.immediate) {
        (picker ?: throw ProfileException("请返回应用后重新选择照片")).pick(source)
    }
}

internal object AvatarFiles {
    fun camera(context: Context): File {
        val dir = File(context.cacheDir, "avatar-camera").apply { mkdirs() }
        return File.createTempFile("capture-", ".jpg", dir)
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** Copy a bounded, oriented bitmap into durable app storage before saving the preference. */
    fun import(context: Context, source: Uri): String {
        val bitmap = ShareMedia.loadBitmap(context, source)
            ?: throw ProfileException("照片无法读取，请选择其他图片")
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".jpg")
        try {
            file.outputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output))
            }
            check(file.length() > 0)
            return uri(context, file).toString()
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
    }
}

internal class AvatarPicker(private val context: Context, private val scope: CoroutineScope) {
    var gallery: () -> Unit = {}
    var camera: (Uri) -> Unit = {}
    var permission: () -> Unit = {}
    private var pending: CompletableDeferred<String?>? = null
    private var target: File? = null

    suspend fun pick(source: AvatarSource): String? {
        if (pending != null) throw ProfileException("正在选择照片，请稍候")
        val result = CompletableDeferred<String?>()
        pending = result
        try {
            when (source) {
                AvatarSource.GALLERY -> gallery()
                AvatarSource.CAMERA -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else permission()
            }
            return result.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProfileException) {
            throw e
        } catch (_: Exception) {
            throw ProfileException("无法打开照片，请检查是否有可用的相册或相机")
        } finally {
            result.cancel()
            if (pending === result) {
                pending = null
                target?.delete()
                target = null
            }
        }
    }

    fun permissionResult(granted: Boolean) {
        if (pending == null) return
        if (granted) launchCamera() else fail("未授予相机权限，可从相册选择照片")
    }

    private fun launchCamera() {
        try {
            val file = AvatarFiles.camera(context)
            target = file
            camera(AvatarFiles.uri(context, file))
        } catch (_: Exception) {
            fail("无法打开相机，请检查是否有可用的相机应用")
        }
    }

    fun galleryResult(uri: Uri?) = finish(uri)
    fun cameraResult(ok: Boolean) = finish(if (ok) target?.let { AvatarFiles.uri(context, it) } else null)

    private fun fail(message: String) {
        pending?.completeExceptionally(ProfileException(message))
    }

    private fun finish(uri: Uri?) {
        val result = pending ?: return
        if (result.isCompleted) return
        if (uri == null) { result.complete(null); return }
        scope.launch {
            var saved: String? = null
            try {
                // Keep the write inside the IO block so cancellation can delete its own orphan.
                withContext(Dispatchers.IO) { saved = AvatarFiles.import(context, uri) }
                if (!result.complete(saved)) saved?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
            } catch (e: CancellationException) {
                saved?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
                result.cancel(e)
                throw e
            } catch (_: Exception) {
                result.completeExceptionally(ProfileException("照片无法读取，请选择其他图片"))
            }
        }
    }

    fun dispose() { pending?.cancel(); target?.delete(); target = null }
}

internal class AvatarCameraContract : ActivityResultContract<Uri, Boolean>() {
    private val delegate = ActivityResultContracts.TakePicture()
    override fun createIntent(context: Context, input: Uri): Intent = delegate.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("avatar", input)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = delegate.parseResult(resultCode, intent)
}

@Composable
internal fun AvatarPickerHost() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picker = remember(context, scope) { AvatarPicker(context, scope) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picker::galleryResult)
    val camera = rememberLauncherForActivityResult(AvatarCameraContract(), picker::cameraResult)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), picker::permissionResult)
    DisposableEffect(picker, gallery, camera, permission) {
        picker.gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        picker.camera = { camera.launch(it) }
        picker.permission = { permission.launch(Manifest.permission.CAMERA) }
        AvatarPickerHub.picker = picker
        onDispose {
            if (AvatarPickerHub.picker === picker) AvatarPickerHub.picker = null
            picker.dispose()
        }
    }
}
