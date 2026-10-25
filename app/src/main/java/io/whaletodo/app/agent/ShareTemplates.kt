package io.whaletodo.app.agent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.whaletodo.core.*
import java.time.LocalDate
import kotlin.math.*

/** Visual component contract only. Weiding owns bitmap export, camera/gallery and platform sharing. */
enum class ShareStyle(val code:String,val label:String,val bg:Long,val ink:Long,val accent:Long,val premium:Boolean=true){
    MINIMAL("minimal","极简",0xFFF6F5F0,0xFF263F42,0xFF408C85,false),
    CODE("github","程序员",0xFF101923,0xFFE4F2EA,0xFF58D68D),
    HEALING("healing","治愈",0xFFF0F3E6,0xFF426758,0xFFA9CBAA),
    LITERARY("literary","文学",0xFFF1E5D5,0xFF574F46,0xFFAC8B65),
    ORNATE("ornate","华丽",0xFF281D34,0xFFF5E6BF,0xFFD2AF65),
    STARRY("starry","星空",0xFF101C3D,0xFFE8EBFF,0xFFA8B5F2),
    RED("red","红色",0xFF972E35,0xFFFFF0DF,0xFFFFCBA0),
    FITNESS("fitness","锻炼",0xFF192B30,0xFFECF6EC,0xFFCCEE67)
}
@Composable fun ShareCardPreview(style:ShareStyle,title:String,lines:List<String>,date:String=LocalDate.now().toString(),modifier:Modifier=Modifier){
    val ink=Color(style.ink);val accent=Color(style.accent)
    Box(modifier.aspectRatio(0.78f).background(Color(style.bg),RoundedCornerShape(22.dp))){
        Canvas(Modifier.fillMaxSize()){
            when(style){
                ShareStyle.STARRY->repeat(64){i->val x=((i*73+19)%997)/997f*size.width;val y=((i*127+37)%991)/991f*size.height;drawCircle(accent.copy(alpha=0.15f+(i%5)*0.13f),if(i%9==0)2.4f else 1f,Offset(x,y))}
                ShareStyle.CODE->repeat(70){i->val column=i%14;val row=i/14;drawRect(accent.copy(alpha=0.12f+(i*13%7)*0.10f),Offset(size.width*(0.08f+column*0.06f),size.height*(0.71f+row*0.038f)),androidx.compose.ui.geometry.Size(size.width*0.043f,size.height*0.026f))}
                ShareStyle.HEALING-> {drawCircle(accent.copy(alpha=.22f),size.width*.55f,Offset(size.width*1.05f,size.height*.85f));drawCircle(Color.White.copy(alpha=.5f),size.width*.30f,Offset(size.width*.1f,size.height*.35f))}
                ShareStyle.ORNATE-> {drawRect(accent.copy(alpha=.6f),Offset(size.width*.04f,size.height*.035f),androidx.compose.ui.geometry.Size(size.width*.92f,size.height*.93f),style=androidx.compose.ui.graphics.drawscope.Stroke(2f));repeat(4){i->drawCircle(accent,3f,Offset(if(i%2==0)size.width*.06f else size.width*.94f,if(i<2)size.height*.05f else size.height*.95f))}}
                ShareStyle.FITNESS->repeat(5){i->drawLine(accent.copy(alpha=.10f),Offset(size.width*(.5f+i*.1f),0f),Offset(size.width*(i*.1f),size.height),18f)}
                ShareStyle.RED->drawCircle(accent.copy(alpha=.12f),size.width*.55f,Offset(size.width*.9f,size.height*.15f))
                ShareStyle.LITERARY->repeat(15){i->drawLine(ink.copy(alpha=.035f),Offset(size.width*.08f,size.height*(.20f+i*.045f)),Offset(size.width*.92f,size.height*(.20f+i*.045f)),1f)}
                else->drawLine(accent,Offset(size.width*.09f,size.height*.20f),Offset(size.width*.3f,size.height*.20f),3f)
            }
        }
        Column(Modifier.fillMaxSize().padding(26.dp),verticalArrangement=Arrangement.SpaceBetween){
            Column{
                Text(if(style==ShareStyle.CODE)"~/whaletodo/commit" else "WHALETODO · $date",fontSize=10.sp,color=accent,fontFamily=if(style==ShareStyle.CODE)FontFamily.Monospace else FontFamily.Default)
                Spacer(Modifier.height(28.dp));Text(title,color=ink,fontSize=27.sp,fontWeight=FontWeight.Bold,fontFamily=if(style==ShareStyle.LITERARY)FontFamily.Serif else FontFamily.Default,maxLines=3)
                Spacer(Modifier.height(20.dp));lines.take(5).forEachIndexed{i,line->Text("${if(style==ShareStyle.CODE)"+" else "${i+1}."} $line",color=ink.copy(alpha=.88f),fontSize=14.sp,modifier=Modifier.padding(vertical=6.dp),maxLines=2)}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){WhaleAvatar(Modifier.size(24.dp));Text("  每一步，都算数。",fontSize=11.sp,color=ink.copy(alpha=.72f))}
        }
    }
}
@Composable internal fun ShareStyleSheet(text:String,access:AgentAccess,dismiss:()->Unit,onExport:()->Unit,
    onGate:(GateReason,ShareStyle)->Unit){
    var selected by remember {mutableStateOf(ShareStyle.MINIMAL)}
    AlertDialog(onDismissRequest=dismiss,title={Text("分享样式")},text={
        Column(Modifier.verticalScroll(rememberScrollState())){
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ShareStyle.entries){s->FilterChip(selected==s,onClick={selected=s},label={Text(s.label+if(s.premium&&!access.vip)" · VIP" else "")})}}
            ShareCardPreview(selected,"今天的进度",text.lines().filter{it.isNotBlank()}.take(5),modifier=Modifier.fillMaxWidth().padding(top=12.dp))
        }
    },confirmButton={TextButton(onClick={val gate=AgentPolicy.shareGate(access,selected.premium);if(gate!=null)onGate(gate,selected)else onExport()}){Text("分享文字")}},
        dismissButton={TextButton(onClick=dismiss){Text("关闭")}})
}
