package io.whaletodo.app.reminder

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import io.whaletodo.app.MainActivity
import io.whaletodo.app.data.DeskDatabase
import kotlinx.coroutines.*

/** User-requested exact alarms ring with alarm audio until stopped, for at most two minutes. */
class AlarmRingingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private var player:MediaPlayer?=null
    private var focus:AudioFocusRequest?=null
    private var key:String?=null
    private val audio by lazy{getSystemService(AudioManager::class.java)}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent==null){stopSelf();return START_NOT_STICKY}
        if(intent.action==STOP){if(intent.getStringExtra(KEY)==key)stopSelf();return START_NOT_STICKY}
        val owner=intent.getStringExtra(ReminderCenter.EXTRA_OWNER)
        val task=intent.getStringExtra(ReminderCenter.EXTRA_TASK_ID)
        if(owner==null||task==null||owner!=ReminderCenter.activeOwner(this)){stopSelf();return START_NOT_STICKY}
        val next="$owner:$task:${intent.getStringExtra(ReminderCenter.EXTRA_KIND)}:${intent.getLongExtra(ReminderCenter.EXTRA_TRIGGER_AT,0)}"
        if(key==next)return START_NOT_STICKY
        releaseAudio();key=next;active=owner to task
        val notification=notification(intent,next)
        try{startForeground(ID,notification)}catch(_:RuntimeException){stopSelf();return START_NOT_STICKY}
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({if(key==next)stopSelf()},120_000)
        scope.launch {
            val valid=withContext(Dispatchers.IO){
                val db=DeskDatabase.open(this@AlarmRingingService)
                try{db.dao().reminder(owner,task)?.enabled==true&&db.dao().tasks(owner).any{it.id==task&&!it.done}}
                finally{db.close()}
            }
            if(key!=next)return@launch
            if(!valid||owner!=ReminderCenter.activeOwner(this@AlarmRingingService)){stopSelf();return@launch}
            val sound=ReminderSounds.uri(this@AlarmRingingService,SoundKind.ALARM)?:return@launch
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener {change->
                    if(key==next)when(change){AudioManager.AUDIOFOCUS_LOSS->stopSelf();AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->runCatching{player?.pause()};AudioManager.AUDIOFOCUS_GAIN->runCatching{player?.start()}}
                }.build()
            if(audio.requestAudioFocus(focus!!)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return@launch
            try {
                player=MediaPlayer().apply {
                    setAudioAttributes(attributes);setWakeMode(this@AlarmRingingService,PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(this@AlarmRingingService,sound);isLooping=true
                    setOnPreparedListener{if(key==next)it.start()}
                    setOnErrorListener{_,_,_->stopSelf();true}
                    prepareAsync()
                }
            }catch(_:Exception){stopSelf()}
        }.invokeOnCompletion{error->if(error!=null&&error !is CancellationException&&key==next)stopSelf()}
        return START_NOT_STICKY
    }
    private fun notification(source:Intent,ringKey:String):Notification {
        val channel="active_alarm_ringing"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"正在响铃",NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null)})
        val stop=PendingIntent.getService(this,ringKey.hashCode(),Intent(this,AlarmRingingService::class.java).setAction(STOP).setData(Uri.parse("whaletodo://stop-alarm/${ringKey.hashCode()}")).putExtra(KEY,ringKey),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(source.getStringExtra(ReminderCenter.EXTRA_TITLE)?:"日程提醒")
            .setContentText(if(source.getStringExtra(ReminderCenter.EXTRA_KIND)=="start")"开始时间到了" else "已到结束时间")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true)
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"停止响铃",stop).build()
    }
    private fun releaseAudio(){runCatching{player?.release()};player=null;focus?.let{audio.abandonAudioFocusRequest(it)};focus=null}
    override fun onDestroy(){key=null;handler.removeCallbacksAndMessages(null);scope.cancel();releaseAudio();active=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    companion object {
        private const val ID=20261101
        private const val STOP="io.whaletodo.STOP_ALARM"
        private const val KEY="ring_key"
        @Volatile private var active:Pair<String,String>?=null
        internal fun reconcile(context:Context,owner:String,validTasks:Set<String>){
            val ring=active?:return
            if(ring.first!=owner||ring.second !in validTasks)context.stopService(Intent(context,AlarmRingingService::class.java))
        }
    }
}
