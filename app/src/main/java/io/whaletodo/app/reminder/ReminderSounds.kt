package io.whaletodo.app.reminder

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal enum class SoundKind(val key:String,val title:String,val ringtoneType:Int) {
    MESSAGE("message","通知铃声",RingtoneManager.TYPE_NOTIFICATION),
    ALARM("alarm","闹钟铃声",RingtoneManager.TYPE_ALARM)
}

/** Device-local selections and copies never enter Room account/schedule documents. */
internal object ReminderSounds {
    private fun prefs(c:Context)=c.getSharedPreferences("reminder-sounds-device",Context.MODE_PRIVATE)
    fun value(c:Context,k:SoundKind)=prefs(c).getString(k.key,"default")?:"default"
    fun uri(c:Context,k:SoundKind):Uri?=when(val v=value(c,k)) {
        "silent"->null
        "default"->RingtoneManager.getDefaultUri(k.ringtoneType)
        else->Uri.parse(v)
    }
    fun label(c:Context,k:SoundKind)=when(value(c,k)) {
        "default"->"跟随系统"
        "silent"->"静音"
        else->prefs(c).getString(k.key+".label","已选铃声")?:"已选铃声"
    }
    fun chooseDefault(c:Context,k:SoundKind,silent:Boolean=false) {
        check(prefs(c).edit().putString(k.key,if(silent)"silent" else "default").remove(k.key+".label").commit())
    }
    fun channelId(c:Context,k:SoundKind):String {
        val hash=MessageDigest.getInstance("SHA-256").digest(value(c,k).toByteArray()).take(8).joinToString(""){"%02x".format(it)}
        return "task_${k.key}_v2_$hash"
    }
    fun import(c:Context,k:SoundKind,source:Uri) {
        if(source==RingtoneManager.getDefaultUri(k.ringtoneType)){chooseDefault(c,k);return}
        val folder=File(c.filesDir,"reminder-sounds").apply{mkdirs()}
        val temporary=File.createTempFile("selected-",".audio",folder)
        try {
            c.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input)
                temporary.outputStream().use { output ->
                    val buffer=ByteArray(8192);var total=0
                    while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=20*1024*1024){"Audio too large"};output.write(buffer,0,n)}
                    require(total>0)
                }
            }
            // Verify a playable audio track before persisting the selection.
            val metadata=android.media.MediaMetadataRetriever()
            try {metadata.setDataSource(temporary.absolutePath);require((metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)>0)}
            finally {metadata.release()}
            val digest=MessageDigest.getInstance("SHA-256").digest(temporary.readBytes()).joinToString(""){"%02x".format(it)}
            val target=File(folder,"$digest.audio")
            if(target.exists())temporary.delete() else check(temporary.renameTo(target))
            val uri=FileProvider.getUriForFile(c,c.packageName+".fileprovider",target)
            listOf("android","com.android.systemui").forEach { packageName ->
                runCatching{c.grantUriPermission(packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            }
            val name=runCatching {
                c.contentResolver.query(source,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst())cursor.getString(0)else null
                }
            }.getOrNull()?.take(80)?:runCatching{RingtoneManager.getRingtone(c,source)?.getTitle(c)}.getOrNull()?:"已选铃声"
            check(prefs(c).edit().putString(k.key,uri.toString()).putString(k.key+".label",name).commit())
        }finally{temporary.delete()}
    }
}
