package io.whaletodo.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.whaletodo.app.agent.*
import io.whaletodo.app.profile.*
import io.whaletodo.core.*
import java.time.LocalDate
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Synthetic transport only: no mailbox, password, provider or payment traffic and no preference clearing. */
class ProfileCleanUiTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument)
    private val ui=UiSteps(device)
    private val oldAgent=AgentBridgeHub.bridge
    private val oldProfile=ProfileBridgeHub.bridge
    @get:Rule val witness=object:TestWatcher(){override fun failed(e:Throwable?,description:Description){
        shot("failure-"+description.methodName)
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"clean-ui-failure-${description.methodName}.xml"))
    }}
    private class Fixture(guest:Boolean=false,sixDays:Boolean=false):ProfileBridge {
        val owner=if(guest)null else UUID.randomUUID().toString()
        val access=MutableStateFlow(AgentAccess(owner,false,if(guest)0 else 100,true))
        override val details=MutableStateFlow(owner?.let{ProfileDetails(it,"小鲸同学","UI-DEMO","learner@example.test",signInDates=if(sixDays)(1L..6L).map{LocalDate.now().minusDays(it)}.toSet()else emptySet())})
        var codeCalls=0;var signInCalls=0;var failCode=false;var resendSeconds=1
        val purposes=mutableListOf<CodePurpose>()
        override suspend fun requestCode(email:String,purpose:CodePurpose):VerificationDispatch {
            codeCalls++;purposes+=purpose;delay(100)
            if(failCode)throw ProfileUnavailable()
            return VerificationDispatch(resendSeconds)
        }
        override suspend fun signIn(){
            signInCalls++;delay(100);val d=details.value!!
            if(d.businessDate !in d.signInDates){
                val p=SignInProgress.from(d.signInDates,d.businessDate)
                details.value=d.copy(signInDates=d.signInDates+d.businessDate)
                access.value=access.value.copy(availablePoints=access.value.availablePoints+SignInProgress.reward(p.cycleDay))
            }
        }
        val agent=object:AgentBridge {
            override val access=this@Fixture.access
            override val models=emptyList<AgentModel>()
            override suspend fun reply(request:AssistantRequest):AssistantReply=error("No model calls in clean UI checks")
        }
    }
    @After fun restore(){AgentBridgeHub.bridge=oldAgent;ProfileBridgeHub.bridge=oldProfile}
    private fun start(f:Fixture){
        // Install the real runtime once before overriding its hubs; otherwise the first Activity replaces the fixture.
        instrument.runOnMainSync{io.whaletodo.app.account.installAccount(context)}
        AgentBridgeHub.bridge=f.agent;ProfileBridgeHub.bridge=f
        val monitor=instrument.addMonitor(MainActivity::class.java.name,null,false)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity=instrument.waitForMonitorWithTimeout(monitor,10000)?:error("Activity did not start")
        instrument.removeMonitor(monitor)
        repeat(100){if(activity.hasWindowFocus()){device.waitForIdle();ui.text("WorkTime");return};SystemClock.sleep(100)}
        error("Activity never focused")
    }
    private fun email(){ui.desc("账户邮箱");device.findObjects(By.clazz("android.widget.EditText"))[0].text="learner@example.test";device.waitForIdle()}
    private fun countdown():UiObject2=device.wait(Until.findObject(By.text(Pattern.compile("\\d+s 后重发"))),5000)?:error("No resend countdown")
    private fun seconds()=countdown().text.substringBefore('s').toInt()
    private fun sendButton()=ui.desc("发送邮箱验证码").parent
    private fun shot(name:String){device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-clean-ui-$name.png")}

    @Test fun milestonesKeepSeventhDayRewardAndCustomMotto(){
        val f=Fixture(sixDays=true);start(f);ui.click("我的");ui.text(ProfileIdentity.DEFAULT_MOTTO);shot("motto")
        ui.click("每日签到");ui.text("连续打卡 6 天")
        val nodes=(1..7).map{day->ui.desc("第${day}天${if(day==7)"礼物" else ""}，${if(day<=6)"已签到" else "待签到"}").visibleBounds}
        nodes.zipWithNext().forEach{(a,b)->assertTrue(a.right<=b.left);assertEquals(a.top,b.top)}
        ui.text("🎁");assertFalse(device.hasObject(By.textContains("+5积分")));shot("six-days")
        ui.click("签到领取");ui.text("连续打卡 7 天");ui.text("今日已签到").click();assertEquals(1,f.signInCalls)
        ui.desc("第7天礼物，已签到");shot("seven-days");ui.click("关闭");ui.text("105 积分")
        f.details.value=f.details.value!!.copy(motto="今天也认真。🐳")
        ui.text("今天也认真。🐳")
    }
    @Test fun registerWaitsSixtySecondsSurvivesReopenAndReenables(){
        val f=Fixture(guest=true);start(f);ui.click("我的");ui.text(ProfileIdentity.DEFAULT_MOTTO)
        ui.click("登录 / 注册");ui.click("注册账户");email();val before=SystemClock.elapsedRealtime();ui.click("发送验证码")
        assertTrue(seconds() in 55..60);shot("register-wait");assertFalse(sendButton().isEnabled);ui.desc("验证码重发倒计时")
        val initial=seconds();SystemClock.sleep(2200);assertTrue(seconds()<initial)
        countdown().click();assertEquals(1,f.codeCalls)
        ui.click("取消");ui.click("登录 / 注册");ui.click("注册账户");email()
        countdown();assertFalse(sendButton().isEnabled);assertEquals(1,f.codeCalls)
        assertTrue(device.wait(Until.hasObject(By.text("发送验证码")),65000))
        assertTrue(SystemClock.elapsedRealtime()-before>=59500);assertTrue(sendButton().isEnabled)
        assertFalse(device.hasObject(By.desc("验证码重发倒计时")))
        ui.click("发送验证码");countdown();assertEquals(2,f.codeCalls)
        assertEquals(listOf(CodePurpose.REGISTER,CodePurpose.REGISTER),f.purposes)
    }
    @Test fun resetFailureCanRetryAndLongerServerWaitIsPreserved(){
        val f=Fixture();f.failCode=true;f.resendSeconds=120;start(f);ui.click("我的");ui.click("设置");ui.click("账户密码")
        ui.click("发送验证码");ui.text("暂时无法发送验证码，请稍后重试");assertTrue(sendButton().isEnabled)
        assertFalse(device.hasObject(By.desc("验证码重发倒计时")))
        f.failCode=false;ui.click("发送验证码");assertTrue(seconds() in 115..120);assertFalse(sendButton().isEnabled)
        ui.desc("验证码重发倒计时");assertFalse(device.hasObject(By.text("暂时无法发送验证码，请稍后重试")));shot("reset-wait")
        assertEquals(2,f.codeCalls);assertEquals(listOf(CodePurpose.RESET_PASSWORD,CodePurpose.RESET_PASSWORD),f.purposes)
    }
}
