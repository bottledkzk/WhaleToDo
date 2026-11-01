package io.whaletodo.app.reminder

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ReminderSoundControls() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pending by rememberSaveable {mutableStateOf<String?>(null)}
    var menu by remember {mutableStateOf<SoundKind?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    fun selected(uri:Uri?) {
        val kind=SoundKind.entries.find{it.name==pending}?:return
        busy=true;error=""
        scope.launch {
            try {withContext(Dispatchers.IO){if(uri==null)ReminderSounds.chooseDefault(context,kind,true)else ReminderSounds.import(context,kind,uri)};ReminderCenter.init(context);revision++}
            catch(_:Exception){error="无法读取此音频，请重新选择"}
            finally{busy=false;pending=null}
        }
    }
    val local=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)selected(uri)else pending=null}
    val system=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==Activity.RESULT_OK){
            @Suppress("DEPRECATION")
            val uri=result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            selected(uri)
        }else pending=null
    }
    SoundKind.entries.forEach {kind->
        key(revision,kind){OutlinedButton(onClick={menu=kind},enabled=!busy,modifier=Modifier.fillMaxWidth().semantics{contentDescription="选择${kind.title}"}){
            Column(Modifier.fillMaxWidth()){Text(kind.title);Text(ReminderSounds.label(context,kind),style=MaterialTheme.typography.bodySmall)}
        }}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    menu?.let {kind->AlertDialog(onDismissRequest={menu=null},title={Text(kind.title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick={menu=null;pending=kind.name
            val intent=Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,kind.ringtoneType)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,kind.title).putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,true).putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,ReminderSounds.uri(context,kind))
            try{system.launch(intent)}catch(_:Exception){local.launch(arrayOf("audio/*"))}
        }){Text("系统铃声")}
        TextButton(onClick={menu=null;pending=kind.name;local.launch(arrayOf("audio/*"))}){Text("本地音频")}
        TextButton(onClick={ReminderSounds.chooseDefault(context,kind);ReminderCenter.init(context);revision++;menu=null}){Text("跟随系统")}
        TextButton(onClick={ReminderSounds.chooseDefault(context,kind,true);ReminderCenter.init(context);revision++;menu=null}){Text("静音")}
    }},confirmButton={TextButton(onClick={menu=null}){Text("取消")}})}
}
