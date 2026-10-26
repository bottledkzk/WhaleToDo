package io.whaletodo.app

import android.content.Intent
import androidx.room.Room
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
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Synthetic identity and registration callbacks only in verification instrumentation. */
class ProfilePolishTest {
    private val instrument=InstrumentationRegistry.getInstrumentation();private val context=instrument.targetContext
    private val device=UiDevice.getInstance(instrument);private val ui=UiSteps(device)
    private val oldAgent=AgentBridgeHub.bridge;private val oldProfile=ProfileBridgeHub.bridge
    @get:Rule val witness=object:TestWatcher(){override fun failed(e:Throwable?,description:Description){shot("failure-"+description.methodName);device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"profile-polish-failure.xml"))}}
    private class Fixture(guest:Boolean=false,vip:Boolean=false):ProfileBridge {
        val owner=if(guest)null else UUID.randomUUID().toString()
        val access=MutableStateFlow(AgentAccess(owner,vip,if(guest)0 else 150,true))
        override val details=MutableStateFlow(owner?.let{ProfileDetails(it,"","20261025","learner@example.test",motto="把每一天，慢慢安排好。",vipExpiresAt=if(vip)Instant.now().plusSeconds(12*86400)else null)})
        var registrations=0;var nickname:String?="not-called"
        override suspend fun signIn(){delay(200)}
        override suspend fun register(email:String,code:String,password:String,nickname:String?){registrations++;this.nickname=nickname}
        val agent=object:AgentBridge {
            override val access=this@Fixture.access
            override val models=listOf(AgentModel("deepseek-default","鲸鱼AI"))
            override suspend fun reply(request:AssistantRequest):AssistantReply=error("No provider calls in profile polish tests")
        }
    }
    private fun <T> db(block:suspend(DeskDatabase)->T):T=runBlocking{val d=DeskDatabase.open(context);try{block(d)}finally{d.close()}}
    @Before fun prepare(){db{d->d.dao().deviceOptions().forEach{d.dao().removeOption("device",it.key)}}}
    @After fun restore(){AgentBridgeHub.bridge=oldAgent;ProfileBridgeHub.bridge=oldProfile;db{d->d.dao().deviceOptions().forEach{d.dao().removeOption("device",it.key)}}}
    private fun start(f:Fixture) {
        AgentBridgeHub.bridge=f.agent;ProfileBridgeHub.bridge=f
        val monitor=instrument.addMonitor(MainActivity::class.java.name,null,false)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity=instrument.waitForMonitorWithTimeout(monitor,10000)?:error("Activity did not start");instrument.removeMonitor(monitor)
        repeat(100){if(activity.hasWindowFocus()){device.waitForIdle();ui.text("WorkTime");return};android.os.SystemClock.sleep(100)};error("No window focus")
    }
    private fun field(index:Int,value:String){repeat(40){val rows=device.findObjects(By.clazz("android.widget.EditText"));if(rows.size>index){rows[index].text=value;device.waitForIdle();return};android.os.SystemClock.sleep(100)};error("Missing field $index")}
    private fun clickDesc(label:String){repeat(50){try{val node=ui.desc(label);if(node.isEnabled){val b=node.visibleBounds;device.click(b.centerX(),b.centerY());device.waitForIdle();return}}catch(_:StaleObjectException){device.waitForIdle()};android.os.SystemClock.sleep(100)};error("Unstable control $label")}
    private fun noEditor(){assertTrue(device.wait(Until.gone(By.clazz("android.widget.EditText")),5000))}
    private fun shot(name:String){device.executeShellCommand("screencap -p /sdcard/Download/whaletodo-profile-polish-$name.png")}
    private fun region(){ui.click("我的");ui.click("设置");ui.click("时间和地区")}
    private fun confirm(){ui.text("更改时区");ui.click("确认更改");device.waitForIdle()}
    private fun selectVisible(label:String){repeat(30){device.findObject(By.text(label))?.takeIf{it.visibleBounds.height()>15}?.let{it.click();device.waitForIdle();return};device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN,0.5f);device.waitForIdle()};error("Missing list option $label")}

    @Test fun heroPlacesPointsAndVipOnRightAndMottoBelowIdentity() {
        val f=Fixture(vip=true);start(f);ui.click("我的");val name=ui.text("Whale20261025");val points=ui.text("150 积分");val vip=ui.text("♛ VIP · 12天");val motto=ui.text("把每一天，慢慢安排好。")
        assertTrue(points.visibleBounds.centerX()>name.visibleBounds.centerX());assertTrue(vip.visibleBounds.centerY()>points.visibleBounds.centerY())
        assertTrue(motto.visibleBounds.top>=ui.desc("编辑头像").visibleBounds.bottom);shot("vip-home")
        clickDesc("VIP会员");ui.text("月度会员");ui.click("关闭")
        clickDesc("积分获取途径");ui.text("提出建议");assertFalse(device.hasObject(By.textContains("github.com/")));assertFalse(device.hasObject(By.textContains("贡献建议的奖励")));shot("points")
    }
    @Test fun guestBadgeOpensRechargeAndSettingsNeverSurviveTabNavigation() {
        start(Fixture(guest=true));ui.click("我的");ui.text("本机访客");ui.text("0 积分");clickDesc("开通VIP会员");ui.text("会员充值");ui.click("关闭");shot("guest-home")
        ui.click("设置");ui.text("账户信息");assertFalse(device.hasObject(By.text("每日默认与星期安排")));ui.click("今天");ui.click("我的");ui.text("每日签到");assertFalse(device.hasObject(By.text("账户信息")))
        ui.click("设置");ui.click("我的");ui.text("每日签到");ui.click("预览");assertFalse(device.hasObject(By.textContains("按日涉及")))
    }
    @Test fun nicknameAndMottoEditCancelLimitsPersistenceAndAccountPartition() {
        val f=Fixture();start(f);ui.click("我的");ui.click("设置");ui.click("账户信息");clickDesc("展开用户ID");ui.text("20261025")
        clickDesc("编辑用户昵称");field(0,"不会保存");clickDesc("取消编辑用户昵称");noEditor();ui.text("Whale20261025")
        clickDesc("编辑用户昵称");field(0,"鲸".repeat(9));clickDesc("保存用户昵称");noEditor();ui.text("鲸".repeat(8))
        assertEquals("鲸".repeat(8),db{it.dao().options(f.owner!!).first{x->x.key=="profile.nickname"}.value})
        clickDesc("编辑用户昵称");field(0,"读书小鲸鱼");clickDesc("保存用户昵称");noEditor();ui.text("读书小鲸鱼");assertEquals("读书小鲸鱼",db{it.dao().options(f.owner!!).first{x->x.key=="profile.nickname"}.value})
        clickDesc("编辑Motto");field(0,"鲸".repeat(31));clickDesc("保存Motto");noEditor();ui.text("鲸".repeat(30))
        assertEquals("鲸".repeat(30),db{it.dao().options(f.owner!!).first{x->x.key=="profile.motto"}.value})
        clickDesc("编辑Motto");field(0,"今天也认真留下一点进步。🐳");clickDesc("保存Motto");noEditor();ui.text("今天也认真留下一点进步。🐳");shot("account")
        assertFalse(device.hasObject(By.clazz("android.widget.EditText")))
        start(f);ui.click("我的");ui.text("读书小鲸鱼");ui.text("今天也认真留下一点进步。🐳");shot("edited-home")
        val other=UUID.randomUUID().toString();f.details.value=ProfileDetails(other,"","98765432","other@example.test");f.access.value=f.access.value.copy(userId=other)
        ui.text("Whale98765432");assertFalse(device.hasObject(By.text("读书小鲸鱼")));assertFalse(device.hasObject(By.text("今天也认真留下一点进步。🐳")))
    }
    @Test fun registerNicknameHasDefaultLimitAndIsPassedOnlyAsPublicDisplayText() {
        val f=Fixture(guest=true);start(f);ui.click("我的");ui.click("登录 / 注册");ui.click("注册账户")
        val fields=device.findObjects(By.clazz("android.widget.EditText"));assertTrue(fields[1].text.startsWith("Whale"))
        field(0,"learner@example.test");field(1,"注册小鲸");field(2,"123456");field(3,"Synthetic-password-9");field(4,"Synthetic-password-9")
        shot("register");ui.click("创建账户");ui.text("注册账户");assertEquals(1,f.registrations);assertEquals("注册小鲸",f.nickname);assertNull(f.access.value.userId)
        ui.click("注册账户");field(0,"second@example.test");field(2,"123456");field(3,"Synthetic-password-9");field(4,"Synthetic-password-9")
        ui.click("创建账户");ui.text("注册账户");assertEquals(2,f.registrations);assertNull(f.nickname)
    }
    @Test fun identitySaveRejectsBusyAndInvalidInputWithoutDiscardingAcceptedChanges()=runBlocking {
        val d=Room.inMemoryDatabaseBuilder(context,DeskDatabase::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try{val f=Fixture();val c=ProfileController(DeskRepository(d),scope,f,f.agent)
            withTimeout(5000){c.state.first{it.owner==f.owner}}
            c.signIn();assertTrue(c.state.value.busy);assertFalse(c.saveIdentity(true,"不能丢失的编辑"))
            withTimeout(5000){c.state.first{!it.busy}}
            assertFalse(c.saveIdentity(true,"鲸".repeat(9)));assertTrue(c.saveIdentity(true,"保存的小鲸"))
            withTimeout(5000){c.state.first{!it.busy}}
            assertEquals("保存的小鲸",d.dao().options(f.owner!!).first{it.key=="profile.nickname"}.value)
        }finally{scope.cancel();d.close()}
    }
    @Test fun systemControlsHiddenAndZoneChangesRequireConfirmationOrCanBeCancelled() {
        val f=Fixture();start(f);region();assertFalse(device.hasObject(By.desc("时区搜索")));assertFalse(device.hasObject(By.text("UTC时区")))
        clickDesc("跟随系统时区");ui.text("是否同步调整日程时间？");assertTrue(db{it.dao().deviceOptions().none{x->x.key=="time.zone"}});ui.click("取消")
        assertFalse(device.hasObject(By.desc("时区搜索")));clickDesc("跟随系统时区");confirm();ui.desc("时区搜索")
        field(0,"+08:00");ui.click("应用自定义时区");ui.text("更改时区");ui.click("取消");assertNotEquals("+08:00",db{DeskOptions.zoneChoice(it.dao().deviceOptions()).id})
        ui.click("应用自定义时区");confirm();assertEquals("+08:00",db{DeskRepository(it).snapshot().zoneId});shot("region-offset")
    }
    @Test fun daylightAndUtcPickerAreSelectableAndSystemModeHidesThemAgain() {
        start(Fixture());region();clickDesc("跟随系统时区");confirm();field(0,"PDT");ui.click("应用自定义时区");confirm();ui.desc("夏令时开关")
        assertEquals("America/Los_Angeles",db{DeskOptions.zoneChoice(it.dao().deviceOptions()).id})
        clickDesc("夏令时开关");ui.click("取消");assertTrue(db{DeskOptions.zoneChoice(it.dao().deviceOptions()).daylight})
        clickDesc("夏令时开关");confirm();assertFalse(db{DeskOptions.zoneChoice(it.dao().deviceOptions()).daylight});assertEquals("-08:00",db{DeskRepository(it).snapshot().zoneId});shot("region-daylight")
        ui.click("UTC时区");ui.text("选择UTC时区");shot("utc-picker");selectVisible("UTC+05:45");confirm();assertEquals("+05:45",db{DeskRepository(it).snapshot().zoneId})
        clickDesc("跟随系统时区");confirm();assertFalse(device.hasObject(By.desc("时区搜索")));assertFalse(device.hasObject(By.desc("夏令时开关")))
    }
}