package io.whaletodo.app

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.whaletodo.app.agent.*
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.UUID

/** Explicit fixture transport in the test APK; no API credentials or supplier traffic. */
class AgentExperienceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val ui=UiSteps(device)
    private val original=AgentBridgeHub.bridge
    private class FixtureBridge(vip:Boolean=false,guest:Boolean=false,val target:String="", val pending:Boolean=false) : AgentBridge {
        override val access=MutableStateFlow(if(guest)AgentAccess() else AgentAccess(UUID.randomUUID().toString(),vip,100,true,setOf("deepseek-planner")))
        override val models=listOf(AgentModel("deepseek-default","DeepSeek"),AgentModel("deepseek-planner","DeepSeek · 安排",true))
        @Volatile var last:AssistantRequest?=null
        @Volatile var replyCount=0
        @Volatile var queryCount=0
        override suspend fun reply(request:AssistantRequest):AssistantReply {
            last=request;replyCount++;delay(2400)
            if(pending)throw AgentPendingException(request.id)
            return if(request.mode=="edit")AssistantReply("建议将这件事的标题更新。",1250,1000,250,2,changes=listOf(AgentChange.RenameTask(target,"已确认的新标题")))
            else AssistantReply("已根据只读日程生成建议。",1250,1000,250,2)
        }
        override suspend fun query(requestId:String):AssistantReply {queryCount++;return AssistantReply("原请求已确认，无需重复发送。",500,400,100,1)}
    }
        
    @After fun restore(){AgentBridgeHub.bridge=original}
    private fun start(bridge:AgentBridge){AgentBridgeHub.bridge=bridge;context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));device.waitForIdle();ui.text("WorkTime")}
    private fun <T> db(action:suspend(DeskDatabase)->T):T=runBlocking{val d=DeskDatabase.open(context);try{action(d)}finally{d.close()}}
    @Test fun guestGatesAndWhaleAvatar() {
        start(FixtureBridge(guest=true));ui.click("助手");device.findObject(By.clazz("android.widget.EditText")).text="合成学习问题";ui.click("发送"); device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-guest-after-send.png");ui.text("登录后继续");ui.click("前往账户");ui.click("知道了");ui.desc("默认鲸鱼头像")
    }
    @Test fun chatWorkingUsageAndHistoryWithoutModifyingAgenda() {
        val t=TaskRow("agent-${UUID.randomUUID()}","只读日程验收",note="PRIVATE-NOTE-DO-NOT-SEND");db{it.dao().save(t)}
        val bridge=FixtureBridge();start(bridge);ui.click("助手");device.findObject(By.clazz("android.widget.EditText")).text="给出学习建议";ui.click("发送")
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-chat-after-send.png");assertTrue(device.wait(Until.hasObject(By.textStartsWith("Working")),5000))
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-working.png")
        ui.text("已根据只读日程生成建议。");ui.text("1.25k tokens")
        assertTrue(bridge.last!!.context.contains(t.id));assertFalse(bridge.last!!.context.contains("PRIVATE-NOTE"));assertEquals(t,db{it.dao().tasks().single{x->x.id==t.id}})
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-chat.png")
        ui.click("＋ 新对话");ui.text("把想法，变成下一步");ui.click("历史");ui.click("给出学习建议");ui.text("已根据只读日程生成建议。")
    }
    @Test fun vipSpecificModelAndExplicitConsentLeadToPreviewBeforeAtomicApply() {
        val t=TaskRow("agent-edit-${UUID.randomUUID()}","编辑提案验收");db{it.dao().save(t)}
        start(FixtureBridge(vip=true,target=t.id));ui.click("助手");ui.click("聊天 ▾");ui.click("调整日程")
        ui.click("DeepSeek ▾");ui.click("DeepSeek · 安排 · 可提案");ui.desc("本次编辑授权").click()
        device.findObject(By.clazz("android.widget.EditText")).text="把标题更新为已确认的新标题";ui.click("发送");ui.text("检查变更提案")
        assertEquals(t.title,db{it.dao().tasks().single{x->x.id==t.id}.title})
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-proposal.png")
        ui.click("确认采用");ui.text("已应用提案");assertEquals("已确认的新标题",db{it.dao().tasks().single{x->x.id==t.id}.title})
    }
    @Test fun taskCategoryAndShareTemplatePaywall() {
        start(FixtureBridge());ui.click("＋");ui.text("记下一件事");device.findObject(By.clazz("android.widget.EditText")).text="分类分享验收";ui.click("详细")
        ui.desc("事务类型").click();ui.click("♫ 娱乐");ui.click("记下");ui.click("待办")
        assertEquals("entertainment",db{it.dao().tasks().first{t->t.title=="分类分享验收"}.category})
        ui.desc("分享 分类分享验收").click();ui.text("分享样式");ui.click("程序员 · VIP");ui.click("分享文字");ui.text("开通 VIP")
        device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-agent-vip-gate.png")
    }
    @Test fun pendingRequestSurvivesNewActivityAndQueriesOriginalIdWithoutResending() {
        val bridge=FixtureBridge(pending=true);start(bridge);ui.click("助手")
        device.findObject(By.clazz("android.widget.EditText")).text="等待网络确认";ui.click("发送")
        ui.text("查询结果");assertEquals(1,bridge.replyCount)
        start(bridge);ui.click("助手");ui.text("把想法，变成下一步");ui.click("历史");ui.click("等待网络确认")
        ui.click("查询结果");ui.text("原请求已确认，无需重复发送。")
        assertEquals(1,bridge.replyCount);assertEquals(1,bridge.queryCount)
    }

    @Test fun switchingAccountDuringWorkDiscardsLateResults() {
        val bridge=FixtureBridge();start(bridge);ui.click("助手")
        device.findObject(By.clazz("android.widget.EditText")).text="账户隔离验证";ui.click("发送")
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Working")),5000))
        bridge.access.value=bridge.access.value.copy(userId=UUID.randomUUID().toString())
        ui.text("把想法，变成下一步");android.os.SystemClock.sleep(3000)
        assertFalse(device.hasObject(By.text("已根据只读日程生成建议。")))
    }
    @Test fun pointsGateAndEightReadableTemplateComponents() {
        val bridge=FixtureBridge();bridge.access.value=bridge.access.value.copy(availablePoints=0)
        start(bridge);ui.click("助手");device.findObject(By.clazz("android.widget.EditText")).text="余额验证";ui.click("发送");ui.text("积分不足");ui.click("返回")
        val monitor=instrument.addMonitor(MainActivity::class.java.name,null,false)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity=instrument.waitForMonitorWithTimeout(monitor,5000) as MainActivity
        instrument.removeMonitor(monitor)
        val selected=androidx.compose.runtime.mutableStateOf(ShareStyle.MINIMAL)
        instrument.runOnMainSync {activity.setContentForTemplateTest(selected)}
        ShareStyle.entries.forEach {style->
            instrument.runOnMainSync {selected.value=style};device.waitForIdle();ui.text("样式 · ${style.label}")
            device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-template-${style.code}.png")
        }
    }

}

private fun MainActivity.setContentForTemplateTest(selected: androidx.compose.runtime.MutableState<ShareStyle>) {
    setContent {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(22.dp)) {
                    Text("样式 · ${selected.value.label}",style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(18.dp))
                    ShareCardPreview(selected.value,"每一小步，都有回响",listOf("完成图论复习 45 分钟","跑步 3 公里","留一点时间，给自己"),date="2026-10-24",modifier=Modifier.fillMaxWidth())
                }
            }
        }
    }
}
