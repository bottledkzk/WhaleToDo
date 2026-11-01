package io.whaletodo.app.reminder

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.DeskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReminderSoundsDeviceTest {
    private fun context():Context=InstrumentationRegistry.getInstrumentation().targetContext.also{check(it.packageName.endsWith(".verification"))}
    @Test fun selectedAudioSurvivesOriginalRemovalAndUsesANewImmutableChannel() {
        val c=context();val p=c.getSharedPreferences("reminder-sounds-device",Context.MODE_PRIVATE)
        val before=p.all;val manager=c.getSystemService(NotificationManager::class.java)
        val channelsBefore=manager.notificationChannels.map{it.id}.toSet()
        val original=File(c.cacheDir,"sound-device-check.wav")
        try {
            val size=16000;val wav=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36+size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
            repeat(size/2){wav.putShort((Math.sin(it*2*Math.PI*440/16000)*2000).toInt().toShort())};original.writeBytes(wav.array())
            ReminderSounds.chooseDefault(c,SoundKind.MESSAGE);ReminderCenter.init(c)
            val old=ReminderSounds.channelId(c,SoundKind.MESSAGE)
            ReminderSounds.import(c,SoundKind.MESSAGE,Uri.fromFile(original));ReminderCenter.init(c)
            val selected=ReminderSounds.uri(c,SoundKind.MESSAGE)!!
            val id=ReminderSounds.channelId(c,SoundKind.MESSAGE);assertNotEquals(old,id)
            assertEquals(selected,manager.getNotificationChannel(id).sound)
            val bytes=original.readBytes();assertTrue(original.delete())
            assertArrayEquals(bytes,c.contentResolver.openInputStream(selected)!!.use{it.readBytes()})
            ReminderSounds.chooseDefault(c,SoundKind.MESSAGE,true);ReminderCenter.init(c)
            assertNull(manager.getNotificationChannel(ReminderSounds.channelId(c,SoundKind.MESSAGE)).sound)
        }finally{
            original.delete();p.edit().clear().apply{before.forEach{(k,v)->if(v is String)putString(k,v)}}.commit()
            manager.notificationChannels.filter{it.id !in channelsBefore}.forEach{manager.deleteNotificationChannel(it.id)}
        }
    }
    @Test fun relativeAndAbsoluteRemindersAreAtomicAndOwned()=runBlocking {
        val c=context();val db=Room.inMemoryDatabaseBuilder(c,DeskDatabase::class.java).build()
        try {
            val repo=DeskRepository(db);val now=System.currentTimeMillis()
            assertEquals(now+60_000,repo.timedReminder("reminder-check","合成相对提醒","alarm",afterMinutes=1,now=now))
            val task=db.dao().tasks("reminder-check").single();assertEquals(now+60_000,task.deadline)
            assertTrue(db.dao().tasks("guest").isEmpty())
            val reminder=db.dao().reminder("reminder-check",task.id)!!
            assertTrue(reminder.enabled);assertEquals("alarm",reminder.method);assertEquals("end",reminder.timing)
            assertTrue(runCatching{repo.timedReminder("reminder-check","错误方式","invalid",afterMinutes=1,now=now)}.isFailure)
            assertTrue(runCatching{repo.timedReminder("reminder-check","过期时刻","alarm",atMillis=now-1,now=now)}.isFailure)
            assertEquals(1,db.dao().tasks("reminder-check").size)
            assertEquals(now+360_000,repo.timedReminder("reminder-check","合成定时提醒","message",atMillis=now+360_000,now=now))
        }finally{db.close()}
    }
}
