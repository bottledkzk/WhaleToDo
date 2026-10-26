package io.whaletodo.app.agent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.whaletodo.app.R
import io.whaletodo.core.GateReason

@Composable fun WhaleAvatar(modifier:Modifier=Modifier) {
    Image(painterResource(R.drawable.ic_whale),"默认鲸鱼头像",modifier.size(48.dp).clip(CircleShape))
}
@Composable fun AccessDialog(reason:GateReason,dismiss:()->Unit,onAccount:()->Unit,
    onVip:()->Unit=onAccount,onPoints:()->Unit=onAccount,onConsent:()->Unit={},onTrial:(()->Unit)?=null) {
    val title=when(reason){GateReason.LOGIN->"登录后继续";GateReason.VIP->"开通 VIP";GateReason.POINTS->"积分不足";
        GateReason.CONSENT->"开启日常编辑？";GateReason.MODEL->"暂不支持日程编辑";GateReason.CONNECTION->"连接尚未就绪"}
    val text=when(reason){GateReason.LOGIN->"登录后可使用助手和分享。";GateReason.VIP->if(onTrial!=null)"此模板需要会员权益，也可使用积分试用。" else "此功能需要会员权益。";
        GateReason.POINTS->"请先签到领取积分，或查看账户余额。";GateReason.CONSENT->"助手只会生成变更提案，采用前仍需你确认。";
        GateReason.MODEL->"日程编辑暂不可用，请先使用计划建议。";GateReason.CONNECTION->"暂时无法连接，请稍后再试。"}
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(text)},confirmButton={
        TextButton(onClick={dismiss();when(reason){GateReason.LOGIN->onAccount();GateReason.VIP->onVip();GateReason.POINTS->onPoints();GateReason.CONSENT->onConsent();else->Unit}}){
            Text(when(reason){GateReason.LOGIN->"前往账户";GateReason.VIP->"查看会员";GateReason.POINTS->"查看积分";GateReason.CONSENT->"允许";else->"知道了"})}
    },dismissButton={Row {if(reason==GateReason.VIP&&onTrial!=null)TextButton(onClick={dismiss();onTrial()}){Text("积分试用")};TextButton(onClick=dismiss){Text("返回")}}})
}
