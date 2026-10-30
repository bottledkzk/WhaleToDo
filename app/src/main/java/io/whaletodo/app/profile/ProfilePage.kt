package io.whaletodo.app.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import io.whaletodo.app.DeskState
import io.whaletodo.app.agent.WhaleAvatar
import io.whaletodo.app.data.DeskOptions
import io.whaletodo.app.ui.minuteLabel
import java.time.*
import kotlinx.coroutines.*

internal const val REPOSITORY_URL="https://github.com/bottledkzk/WhaleToDo"
internal fun openProjectLink(context:Context,issues:Boolean=false){
    runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(REPOSITORY_URL+if(issues)"/issues" else "")))}
}
@Composable internal fun ProfileAvatar(s:ProfileState,modifier:Modifier=Modifier){
    val style=s.options.find{it.key=="avatar.style"}?.value?:if(s.details?.avatarUri!=null)"custom" else "ocean"
    val uri=if(style=="custom")s.options.find{it.key=="avatar.uri"}?.value?:s.details?.avatarUri else null
    val context=LocalContext.current
    var bitmap by remember(s.owner,uri){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(s.owner,uri){bitmap=withContext(Dispatchers.IO){runCatching{
        if(uri?.startsWith("content://")!=true)return@runCatching null
        val u=Uri.parse(uri);val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(u)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth in 1..12000&&bounds.outHeight in 1..12000)
        val options=BitmapFactory.Options().apply{inSampleSize=1};while(bounds.outWidth/options.inSampleSize>256||bounds.outHeight/options.inSampleSize>256)options.inSampleSize*=2
        context.contentResolver.openInputStream(u)?.use{BitmapFactory.decodeStream(it,null,options)}
    }.getOrNull()}}
    val color=when(style){"dusk"->Color(0xFFFFDAC8);"mint"->Color(0xFFD1EADB);else->Color(0xFFD5E9F5)}
    Box(modifier.size(70.dp).clip(CircleShape).background(color),contentAlignment=Alignment.Center){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),"用户头像",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else WhaleAvatar(Modifier.size(58.dp))
    }
}
@Composable internal fun ProfileRow(icon:String,title:String,subtitle:String="",onClick:()->Unit){
    OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Text(icon,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("›",style=MaterialTheme.typography.titleLarge)
        }
    }
}
@Composable internal fun ProfilePage(controller:ProfileController,desk:DeskState,cloud:io.whaletodo.app.sync.AccountCloudController?=null){
    val s by controller.state.collectAsState()
    BackHandler(s.dialog!=MyDialog.NONE||s.page!=MyPageRoute.HOME){controller.back()}
    val context=LocalContext.current
    if(s.page==MyPageRoute.HOME)LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=18.dp,bottom=30.dp)){
        item{Text("我的",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}
        item{ProfileHero(s,desk,controller)}
        item{ProfileRow("✓","每日签到","7日打卡 · 坚持有回响"){controller.open(MyDialog.CHECKIN)}}
        item{ProfileRow("＋","充值","积分购买 · 会员充值"){controller.open(MyDialog.RECHARGE_POINTS)}}
        item{ProfileRow("▤","日记","回看每天的小片段"){controller.openDiary(desk.today)}}
        item{ProfileRow("◷","自动提醒","方式与覆盖范围"){controller.open(MyDialog.REMINDERS)}}
        item{ProfileRow("⚙","设置","账户、外观与时间"){controller.navigate(MyPageRoute.SETTINGS)}}
    }else Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=controller::back,Modifier.semantics{contentDescription=if(s.page==MyPageRoute.SETTINGS)"返回我的" else "返回设置"}){Text("‹",fontSize=30.sp)}
            Text(s.page.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        }
        if(s.error.isNotBlank()&&s.dialog==MyDialog.NONE)Text(s.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        when(s.page){
            MyPageRoute.SETTINGS->LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
                item{Text("账户",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
                item{ProfileRow("○","账户信息"){controller.navigate(MyPageRoute.ACCOUNT)}}
                item{ProfileRow("☁","账户同步","备份与同步"){controller.navigate(MyPageRoute.CLOUD)}}
                item{ProfileRow("⌘","账户密码","重置密码"){controller.open(MyDialog.RESET_PASSWORD)}}
                item{Text("偏好",Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
                item{ProfileRow("◐","外观设定"){controller.navigate(MyPageRoute.APPEARANCE)}}
                item{ProfileRow("◷","Working时间"){controller.navigate(MyPageRoute.WORKING)}}
                item{ProfileRow("◎","时间和地区",desk.zoneId){controller.navigate(MyPageRoute.REGION)}}
                item{ProfileRow("▣","设备与兼容性","系统、通知与后台状态"){controller.navigate(MyPageRoute.DEVICE)}}
                item{ProfileRow("ⓘ","关于"){controller.navigate(MyPageRoute.ABOUT)}}
            }
            MyPageRoute.ACCOUNT->AccountPanel(s,controller)
            MyPageRoute.CLOUD->cloud?.let{io.whaletodo.app.sync.AccountCloudPanel(it)}
            MyPageRoute.APPEARANCE->{
                val prefs=remember{context.getSharedPreferences("appearance",Context.MODE_PRIVATE)}
                var theme by remember{mutableStateOf(prefs.getString("theme","system"))}
                Column{listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(key,title)->
                    Row(Modifier.fillMaxWidth().clickable{theme=key;prefs.edit().putString("theme",key).apply()}.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                        RadioButton(theme==key,{theme=key;prefs.edit().putString("theme",key).apply()});Text(title)
                    }
                }}
            }
            MyPageRoute.WORKING->LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){
                val default=DeskOptions.defaults(s.deviceOptions,desk.settings)
                item{ProfileRow("◷","每日默认","${minuteLabel(default.start)}–${minuteLabel(default.end)}"){controller.openWorking(null)}}
                item{Text("按星期设置",Modifier.padding(top=12.dp),fontWeight=FontWeight.Bold)}
                items((1..7).toList()){n->val override=DeskOptions.weekdays(s.deviceOptions)[DayOfWeek.of(n)]
                    Column{ProfileRow("${listOf("一","二","三","四","五","六","日")[n-1]}","星期${listOf("一","二","三","四","五","六","日")[n-1]}",override?.let{"${minuteLabel(it.start)}–${minuteLabel(it.end)}"}?:"使用每日默认"){controller.openWorking(n)}
                        if(override!=null)TextButton(onClick={controller.clearWeekday(n)},enabled=!s.busy){Text("恢复每日默认")}}
                }
            }
            MyPageRoute.REGION->RegionPanel(s,desk,controller)
            MyPageRoute.DEVICE->io.whaletodo.app.device.DeviceCompatibilityPanel()
            MyPageRoute.ABOUT->Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
                WhaleAvatar(Modifier.size(80.dp));Text("WhaleToDo",style=MaterialTheme.typography.headlineSmall)
                Text("版本 ${remember{context.packageManager.getPackageInfo(context.packageName,0).versionName}}")
                Text("记录、安排与复盘每一天")
                TextButton(onClick={openProjectLink(context)}){Text("GitHub · WhaleToDo")}
            }
            else->Unit
        }
    }
}
