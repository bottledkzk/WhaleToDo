package io.whaletodo.app.share

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Local-only image IO for F07. User photos never leave the device except through the system share sheet. */
internal object ShareMedia {
    internal const val SHARE_PREFIX = "whaletodo-share-"
    /** Hard retention floor: files younger than this are never deleted, even over the soft caps. */
    internal const val SHARE_RETENTION_MS = 24L * 60 * 60 * 1000
    /** Soft cache targets; fresh files may exceed them until they age out. */
    internal const val SHARE_MAX_FILES = 20
    internal const val SHARE_MAX_BYTES = 50L * 1024 * 1024
    private const val CAMERA_PREFIX = "whaletodo-camera-"
    private const val MAX_DECODE_EDGE = 1600
    private const val MIME_PNG = "image/png"

    data class CameraTarget(val file: File, val uri: Uri)

    fun shareDir(context: Context): File =
        File(context.cacheDir, "shared").apply { if (!exists() && !mkdirs()) throw IOException("share cache unavailable") }

    internal fun shareFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith(SHARE_PREFIX) && it.name.endsWith(".png") } ?: emptyList()

    /**
     * Creates a fresh, never-reused share file. The timestamp is for operators; UUID guarantees
     * uniqueness even when two exports happen in the same millisecond.
     */
    internal fun newShareFile(dir: File, now: Long = System.currentTimeMillis()): File {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("share cache unavailable")
        val base = SharePlan.shareFileName(now).removeSuffix(".png")
        repeat(8) {
            val candidate = File(dir, "$base-${UUID.randomUUID()}.png")
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("share file unavailable")
    }

    /**
     * Writes a new unique file and only returns after [writer] succeeds. A failed/partial
     * write is deleted; no previously shared file is touched.
     */
    internal fun writeNewShareFile(dir: File, now: Long = System.currentTimeMillis(), writer: (File) -> Unit): File {
        val file = newShareFile(dir, now)
        try {
            writer(file)
            return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /**
     * Deletes only expired `cache/shared/whaletodo-share-*.png` files, oldest first.
     * Files inside the 24h retention window are never deleted, so the soft 20-file/50MB
     * targets may be temporarily exceeded; they are re-evaluated after the window.
     */
    internal fun cleanupShareFiles(dir: File, now: Long = System.currentTimeMillis()) {
        shareFiles(dir)
            .filter { now - it.lastModified() > SHARE_RETENTION_MS }
            .sortedBy { it.lastModified() }
            .forEach { it.delete() }
    }

    fun createCameraTarget(context: Context): CameraTarget {
        val dir = shareDir(context)
        dir.listFiles()?.filter { it.isFile && it.name.startsWith(CAMERA_PREFIX) }?.forEach { it.delete() }
        val file = File(dir, SharePlan.cameraFileName(System.currentTimeMillis()))
        if (!file.createNewFile()) throw IOException("camera target unavailable")
        return CameraTarget(file, fileUri(context, file))
    }

    fun deleteCamera(file: File?) {
        runCatching { file?.delete() }
    }

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeModern(context, uri)
        } else {
            decodeLegacy(context, uri)
        }
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val edge = max(info.size.width, info.size.height)
            if (edge > MAX_DECODE_EDGE && edge > 0) {
                val scale = MAX_DECODE_EDGE.toFloat() / edge
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            // Software allocation keeps PNG compression and previews deterministic.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return applyOrientation(decoded, readOrientation(context, uri))
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / sample > MAX_DECODE_EDGE) sample *= 2
        return sample
    }

    @Suppress("DEPRECATION")
    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    fun writeSharePng(context: Context, bitmap: Bitmap): File {
        val dir = shareDir(context)
        val file = writeNewShareFile(dir) { target ->
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("png encode failed")
                out.fd.sync()
            }
        }
        // Only sweep after the new share is complete; a failed write above returns before this line.
        runCatching { cleanupShareFiles(dir) }
        return file
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun launchSystemShare(context: Context, uri: Uri, caption: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WhaleToDo")
            if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri("WhaleToDo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享图片").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun needsLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val name = SharePlan.galleryFileName(System.currentTimeMillis())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/WhaleToDo",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gallery insert failed")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IOException("png encode failed")
                    }
                } ?: throw IOException("gallery stream unavailable")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw IOException("gallery publish failed")
                }
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            saveLegacy(context, bitmap, name)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pictures, "WhaleToDo")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("gallery directory unavailable")
        val file = File(dir, name)
        var uri: Uri? = null
        try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("png encode failed")
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gallery insert failed")
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_PNG), null)
            return uri
        } catch (t: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            file.delete()
            throw t
        }
    }
}
