package io.whaletodo.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.whaletodo.app.account.AccountCloudBridge
import io.whaletodo.app.agent.*
import io.whaletodo.app.data.*
import io.whaletodo.app.profile.*
import io.whaletodo.app.sync.*
import io.whaletodo.core.*
import io.whaletodo.network.CloudDocument
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AccountCloudUiTest {
 @Test fun settingsShowExplicitActionsAndCancelNeverUploads()=runBlocking {
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext;val device=UiDevice.getInstance(ins)
  val db=Room.inMemoryDatabaseBuilder(ctx,DeskDatabase::class.java).build();val repo=DeskRepository(db);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val agent=object:AgentBridge {override val access=MutableStateFlow(AgentAccess("cloud-ui",true,20,true));override val models=emptyList<AgentModel>();override suspend fun reply(request:AssistantRequest):AssistantReply=error("No model calls")}
  var scheduleGets=0;var schedulePosts=0
  val bridge=object:AccountCloudBridge {
   override suspend fun read(owner:String,kind:String):CloudDocument {if(kind=="schedule")scheduleGets++;return CloudDocument(0,"{}",null)}
   override suspend fun write(owner:String,kind:String,revision:Int,payload:String):CloudDocument {if(kind=="schedule")schedulePosts++;return CloudDocument(revision+1,payload,1)}
  }
  val cloud=AccountCloudController(repo,scope,bridge,agent);val profile=ProfileController(repo,scope,agent=agent)
  try{
   withTimeout(5000){while(profile.state.value.owner!="cloud-ui")delay(25)};profile.navigate(MyPageRoute.CLOUD)
   device.executeShellCommand("am start -W -n ${ctx.packageName}/io.whaletodo.app.MainActivity")
   var activity:MainActivity?=null;repeat(100){if(activity==null){ins.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};delay(50)}}
   assertNotNull(activity);ins.runOnMainSync{activity!!.setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){Box(Modifier.systemBarsPadding().padding(horizontal=16.dp)){ProfilePage(profile,DeskState(),cloud)}}}}}
   device.waitForIdle();delay(600)
   device.takeScreenshot(File(ctx.cacheDir,"account-cloud-ui-20261030.png"))
   device.dumpWindowHierarchy(File(ctx.cacheDir,"account-cloud-ui-20261030.xml"))
   assertEquals("Profile should stay on requested cloud settings page",MyPageRoute.CLOUD,profile.state.value.page)
   assertTrue("Account sync title",device.wait(Until.hasObject(By.text("账户同步")),5000))
   assertTrue("Backup action",device.hasObject(By.text("云端备份")))
   assertTrue("Replace action",device.hasObject(By.text("覆盖同步")))
   assertTrue("Compatible action",device.hasObject(By.text("兼容同步")))
   assertEquals(0,scheduleGets);assertEquals(0,schedulePosts)
   device.takeScreenshot(File(ctx.cacheDir,"account-cloud-ui-20261030.png"))
   device.findObject(By.text("云端备份")).click();assertTrue(device.wait(Until.hasObject(By.text("确认云端备份")),5000));assertEquals(0,schedulePosts)
   device.findObject(By.text("取消")).click();assertTrue(device.wait(Until.gone(By.text("确认云端备份")),5000));assertEquals(0,schedulePosts)
  }finally{scope.cancel();db.close()}
 }
}
