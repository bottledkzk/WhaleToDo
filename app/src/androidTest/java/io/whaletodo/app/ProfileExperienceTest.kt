package io.whaletodo.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.whaletodo.app.agent.*
import io.whaletodo.app.profile.*
import io.whaletodo.app.data.*
import io.whaletodo.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Explicit synthetic account/media transport in the verification APK only. No email, billing or vendor traffic. */
class ProfileExperienceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation();private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument);private val ui=UiSteps(device)
    @get:Rule val witness=object:TestWatcher(){override fun failed(e:Throwable?,description:Description){
        shot("failure-"+description.methodName)
        device.executeShellCommand("uiautomator dump /sdcard/Download/whaletodo-profile-failure-"+description.methodName+".xml")
    }}
    private val originalAgent=AgentBridgeHub.bridge;private val originalProfile=ProfileBridgeHub.bridge
    private class Fixture(val guest:Boolean=false,val vip:Boolean=false,val diaryText:String="今天完成了阅读，给自己留了一点休息时间。",sixDays:Boolean=false,val replyDelay:Long=500):ProfileBridge {
        val owner=if(guest)null else UUID.randomUUID().toString()
        val access=MutableStateFlow(AgentAccess(owner,vip,if(guest)0 else 100,true))
        override val details=MutableStateFlow(owner?.let{ProfileDetails(it,"小鲸同学","WHALE-DEMO","learner@example.test",signInDates=if(sixDays)(1L..6L).map{n->LocalDate.now().minusDays(n)}.toSet()else emptySet())})
        var mediaCalls=0;var codeCalls=0;var signInCalls=0
        val agent=object:AgentBridge {
            override val access=this@Fixture.access
            override val models=listOf(AgentModel("deepseek-default","鲸鱼AI"))
            override suspend fun reply(request:AssistantRequest):AssistantReply {delay(replyDelay);return AssistantReply(diaryText,350,250,100,1)}
        }
        override suspend fun signIn(){signInCalls++;val d=details.value!!
            if(d.businessDate !in d.signInDates){val p=SignInProgress.from(d.signInDates,d.businessDate);details.value=d.copy(signInDates=d.signInDates+d.businessDate);access.value=access.value.copy(availablePoints=access.value.availablePoints+SignInProgress.reward(p.cycleDay))}}
        override suspend fun requestCode(email:String,purpose:CodePurpose):VerificationDispatch {codeCalls++;throw ProfileUnavailable()}
        override suspend fun pickAvatar(source:AvatarSource):String?{mediaCalls++;throw ProfileUnavailable()}
    }
    private fun <T> db(action:suspend(DeskDatabase)->T):T=runBlocking{val d=DeskDatabase.open(context);try{action(d)}finally{d.close()}}
    @Before fun prepare(){db{d->d.dao().deviceOptions().forEach{d.dao().removeOption("device",it.key)}};context.getSharedPreferences("appearance",0).edit().putString("theme","system").commit()}
    @After fun restore(){AgentBridgeHub.bridge=originalAgent;ProfileBridgeHub.bridge=originalProfile;db{d->d.dao().deviceOptions().forEach{d.dao().removeOption("device",it.key)}};context.getSharedPreferences("appearance",0).edit().putString("theme","system").commit()}
    private fun start(f:Fixture){
        AgentBridgeHub.bridge=f.agent;ProfileBridgeHub.bridge=f
        val monitor=instrument.addMonitor(MainActivity::class.java.name,null,false)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity=instrument.waitForMonitorWithTimeout(monitor,10000)?:error("New activity did not start")
        instrument.removeMonitor(monitor)
        repeat(100){if(activity.hasWindowFocus()){device.waitForIdle();ui.text("WorkTime");return};android.os.SystemClock.sleep(100)}
        error("New activity never received focus")
    }
    private fun field(i:Int,value:String){
        repeat(50){
            val fields=device.findObjects(By.clazz("android.widget.EditText"))
            if(fields.size>i){fields[i].text=value;device.waitForIdle();return}
            android.os.SystemClock.sleep(100)
        }
        shot("input-missing");error("Editable field $i did not appear")
    }
    private fun shot(name:String){device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-profile-$name.png")}
    private fun visible(label:String):UiObject2 {
        repeat(12){
            device.findObject(By.text(label))?.takeIf{it.visibleBounds.height()>18}?.let{return it}
            val scroll=device.findObject(By.scrollable(true))
            if(scroll!=null)scroll.scroll(Direction.DOWN,0.35f)
            else device.swipe(device.displayWidth/2,device.displayHeight*65/100,device.displayWidth/2,device.displayHeight*48/100,20)
            device.waitForIdle()
        };return ui.text(label)
    }
    @Test fun guestPointsRoutesReplaceDialogsAndRegisterDoesNotFakeEmail(){
        val f=Fixture(guest=true);start(f);ui.click("我的");ui.text("本机访客");ui.text("0 积分");ui.desc("积分获取途径").click()
        ui.text("积分获取途径");ui.click("去签到");ui.text("连续打卡 0 天");assertFalse(device.hasObject(By.text("积分获取途径")))
        ui.click("登录后签到");ui.text("注册账户");assertFalse(device.hasObject(By.text("连续打卡 0 天")))
        ui.click("注册账户");ui.text("创建账户");field(0,"learner@example.test");ui.click("发送验证码");ui.text("暂时无法发送验证码，请稍后重试")
        assertEquals(1,f.codeCalls);assertFalse(device.hasObject(By.text("验证码已发送")));shot("register")
        ui.click("取消");ui.desc("积分获取途径").click();ui.click("去充值");ui.text("积分购买");ui.text("会员充值");assertFalse(device.hasObject(By.text("积分获取途径")));ui.click("关闭")
        ui.desc("积分获取途径").click();ui.click("开通VIP");ui.text("月度会员");shot("recharge")
    }
    @Test fun vipBadgeSeventhDayCelebrationAndServerSuppliedBalance(){
        val f=Fixture(vip=true,sixDays=true);start(f);ui.click("我的");ui.text("♛ VIP");ui.text("100 积分");ui.click("每日签到")
        ui.text("连续打卡 6 天");ui.click("签到领取");ui.text("连续打卡 7 天");ui.text("🎉 坚持一周，做得真棒！");shot("seventh-day")
        ui.text("今日已签到").click();assertEquals(1,f.signInCalls);ui.click("关闭");ui.text("105 积分");shot("vip-home")
        ui.click("充值");ui.click("查看方案");ui.text("充值尚未开放");assertEquals(105,f.access.value.availablePoints)
    }
    @Test fun avatarVariantsPersistButGalleryIsOnlyDelegated(){
        val f=Fixture();start(f);ui.click("我的");ui.desc("编辑头像").click();ui.click("从相册选择");ui.text("暂时无法打开照片，请稍后重试");assertEquals(1,f.mediaCalls)
        ui.click("落日");ui.text("小鲸同学");assertEquals("dusk",db{it.dao().options(f.owner!!).first{x->x.key=="avatar.style"}.value})
        val permissions=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(permissions.contains("android.permission.CAMERA"));assertFalse(permissions.any{it.startsWith("android.permission.READ_MEDIA")})
        start(f);ui.click("我的");ui.desc("编辑头像").click();shot("avatar")
    }
    @Test fun settingsBackThemeWeeklyWorkingAndCustomTimezone(){
        val f=Fixture();start(f);ui.click("我的");ui.click("设置");ui.text("账户信息");ui.click("外观设定");ui.click("深色");shot("settings-dark");ui.click("跟随系统");ui.desc("返回设置").click()
        ui.click("Working时间");ui.click("每日默认");ui.text("默认 Working · 开始");ui.time(9,15);ui.click("下一步");ui.time(17,45);ui.click("保存")
        ui.text("09:15–17:45");assertEquals("555,1065",db{it.dao().deviceOptions().first{x->x.key=="working.default"}.value})
        visible("星期一").click();ui.text("星期一 Working · 开始");ui.time(10,10);ui.click("下一步");ui.time(16,20);ui.click("保存")
        assertEquals("610,980",db{it.dao().deviceOptions().first{x->x.key=="working.1"}.value});shot("working")
        ui.desc("返回设置").click();ui.click("时间和地区");field(0,"+08:00");ui.click("应用自定义时区");ui.text("+08:00");shot("region")
        assertEquals("+08:00",db{DeskRepository(it).snapshot().zoneId})
        ui.desc("返回设置").click();ui.desc("返回我的").click();ui.text("每日签到")
    }
    @Test fun reminderPreferencesAndTaskOverridePersistWithoutSchedulingHardware(){
        val f=Fixture();val task=TaskRow("profile-reminder-${UUID.randomUUID()}","合成提醒事务",importance=4);db{it.dao().save(task)}
        start(f);ui.click("我的");ui.click("自动提醒");ui.desc("自动提醒开关").click();ui.click("闹钟");ui.click("≥4★");ui.click("保存偏好")
        ui.click("待办");ui.desc("搜索事务").click();field(0,task.title);ui.click(task.title);ui.desc("事务通知开关")
        visible("消息和闹钟").click();visible("开始和结束").click();shot("task-reminder");ui.click("保存")
        val row=db{it.dao().reminder(f.owner!!,task.id)}!!;assertTrue(row.enabled);assertEquals("both",row.method);assertEquals("both",row.timing)
    }
    @Test fun manualDiaryLimitPersistenceAndSecondDeleteConfirmation(){
        val f=Fixture();start(f);ui.click("我的");ui.click("日记");ui.click("写日记");field(0,"字".repeat(801));ui.text("801 / 800字")
        ui.text("保存日记").click();assertNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())})
        field(0,"今天完成了合成日记验收，天气和心情都平静。");shot("diary-editor");ui.click("保存日记")
        assertNotNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())});start(f);ui.click("我的");ui.click("日记");ui.text("今天完成了合成日记验收，天气和心情都平静。")
        ui.click("删除日记");ui.text("删除这篇日记？");assertNotNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())});ui.click("保留日记")
        ui.click("删除日记");ui.click("确认删除");ui.text("日记已删除");assertNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())})
    }
    @Test fun assistantDiaryRequiresEditablePreviewAndConfirmsBeforeReplacingExisting(){
        val f=Fixture();val day=LocalDate.now().toString();db{DeskRepository(it).saveDiary(f.owner!!,day,"旧的合成日记",null)}
        start(f);ui.click("助手");ui.text("把想法，变成下一步");ui.click("聊天 ▾");ui.click("日记");field(0,"帮我写一篇日记");ui.click("发送");ui.text("保存这篇日记？")
        assertEquals("旧的合成日记",db{it.dao().diary(f.owner!!,day)!!.content});field(0,"今天完成了阅读，也给自己留出了休息时间。")
        shot("ai-diary");ui.click("保存日记");ui.text("替换当日日记？");assertEquals("旧的合成日记",db{it.dao().diary(f.owner!!,day)!!.content})
        ui.click("替换并保存");ui.text("日记已保存");assertEquals("今天完成了阅读，也给自己留出了休息时间。",db{it.dao().diary(f.owner!!,day)!!.content})
    }
    @Test fun assistantReportsAreNotSaveableDiaryAndAccountSwitchHidesOldProfile(){
        val f=Fixture(diaryText="已为你生成今天的日记，任务汇报完成。");start(f);ui.click("助手");ui.text("把想法，变成下一步");ui.click("聊天 ▾");ui.click("日记");field(0,"写一篇日记");ui.click("发送")
        ui.text("日记草稿暂不可用，请缩小范围后重新生成");assertFalse(device.hasObject(By.text("保存这篇日记？")));assertNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())})
        f.access.value=f.access.value.copy(userId=UUID.randomUUID().toString(),vip=false,availablePoints=7)
        ui.click("我的");ui.text("7 积分");assertFalse(device.hasObject(By.text("小鲸同学")));assertFalse(device.hasObject(By.textContains("WHALE-DEMO")))
    }    @Test fun accountResetAboutAndDiaryDatePickerHaveConcreteUi(){
        val f=Fixture();start(f);ui.click("我的");ui.click("设置");ui.click("账户信息");ui.text("邮箱 · learner@example.test");ui.click("重置密码");ui.text("发送验证码");ui.click("发送验证码");ui.text("暂时无法发送验证码，请稍后重试");assertEquals(1,f.codeCalls);ui.click("取消")
        ui.desc("返回设置").click();visible("关于").click();ui.text("版本 0.5.0");ui.text("GitHub · WhaleToDo");shot("about");ui.desc("返回设置").click();ui.desc("返回我的").click()
        ui.click("日记");ui.click(LocalDate.now().toString());ui.text("查看日记");shot("diary-calendar")
        val day=LocalDate.now().withDayOfMonth(2);ui.click(day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu",java.util.Locale.US)));ui.click("查看日记");ui.text(day.toString())
    }
    @Test fun generatedDiaryWaitsForRechargeDialogToClose(){
        val f=Fixture(replyDelay=3500);start(f);ui.click("助手");ui.text("把想法，变成下一步");ui.click("聊天 ▾");ui.click("日记");field(0,"写一篇今天的日记");ui.click("发送")
        ui.click("我的");ui.click("充值");android.os.SystemClock.sleep(4000);ui.text("100积分");assertFalse(device.hasObject(By.text("保存这篇日记？")))
        ui.click("关闭");ui.text("保存这篇日记？");ui.click("取消");assertNull(db{it.dao().diary(f.owner!!,LocalDate.now().toString())})
    }

}
