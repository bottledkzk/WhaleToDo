package io.whaletodo.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/** User-selected photos become bounded private copies, never gallery paths or cloud profile fields. */
internal object AssistantPictures {
    const val MAX_BYTES=128*1024
    fun load(context:Context,uri:Uri):String {
        var bitmap=if(Build.VERSION.SDK_INT>=28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
                val ratio=(1920.0/max(info.size.width,info.size.height)).coerceAtMost(1.0)
                decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth>0&&bounds.outHeight>0)
            val options=BitmapFactory.Options().apply{inSampleSize=1;while(max(bounds.outWidth,bounds.outHeight)/inSampleSize>1920)inSampleSize*=2}
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) }?:error("Unreadable picture")
        }
        try {
            var quality=88
            while(true) {
                val bytes=ByteArrayOutputStream().use{output->check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,output));output.toByteArray()}
                if(bytes.size<=MAX_BYTES) {
                    val folder=File(context.filesDir,"assistant-pictures").apply{mkdirs()}
                    return File(folder,UUID.randomUUID().toString()+".jpg").apply{writeBytes(bytes)}.absolutePath
                }
                if(quality>58)quality-=10
                else {
                    require(max(bitmap.width,bitmap.height)>512)
                    val smaller=Bitmap.createScaledBitmap(bitmap,(bitmap.width*.8).toInt().coerceAtLeast(32),(bitmap.height*.8).toInt().coerceAtLeast(32),true)
                    bitmap.recycle();bitmap=smaller;quality=85
                }
            }
        }finally{bitmap.recycle()}
    }
}
