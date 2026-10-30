package io.whaletodo.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import io.whaletodo.app.account.*
import io.whaletodo.app.agent.*
import io.whaletodo.app.data.*
import io.whaletodo.app.payment.*
import io.whaletodo.app.profile.*
import io.whaletodo.core.*
import io.whaletodo.network.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP to a loopback-only demo service. No production credentials or model calls. */
class MockPaymentFlowTest {
 @Test fun explicitPointsAndVipCheckoutRefreshAccountAndRestorePendingOrder():Unit=runBlocking {
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext;val args=InstrumentationRegistry.getArguments()
  require(ctx.packageName=="io.whaletodo.app.paymentdemo")
  require(BuildConfig.API_BASE_URL=="http://10.0.2.2:18080/api/v1")
  val email=requireNotNull(args.getString("payment_email"));val password=requireNotNull(args.getString("payment_password"))
  val credentials=object:AccountCredentials,TokenStore by InMemoryTokenStore(){
   override fun readEmail()=email
   override fun saveEmail(email:String){}
  }
  val session=AccountSession{credentials.read()?.sessionId};val client=ApiClient(ApiConfig(BuildConfig.API_BASE_URL,true),credentials);val auth=AuthApi(client,credentials)
  val profile=ServerProfileBridge(auth,session,credentials);profile.login(email,password)
  val owner=requireNotNull(session.access.value.userId);val beforePoints=session.access.value.availablePoints;val beforeVip=session.details.value?.vipExpiresAt
  val pending=DevicePaymentPendingStore(ctx,BuildConfig.API_BASE_URL+"/test/"+UUID.randomUUID())
  val payments=ServerPaymentBridge(ServerPaymentApi(client),auth,session,pending)
  var originalPayments=PaymentBridgeHub.bridge;var originalProfile=ProfileBridgeHub.bridge
  val agent=object:AgentBridge {override val access=session.access;override val models=emptyList<AgentModel>();override suspend fun reply(request:AssistantRequest):AssistantReply=error("No model calls")}
  val db=Room.inMemoryDatabaseBuilder(ctx,DeskDatabase::class.java).build();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val controller=ProfileController(DeskRepository(db),scope,profile,agent,payments=payments);val device=UiDevice.getInstance(ins)
  try {
   withTimeout(5000){while(controller.state.value.owner!=owner)delay(25)}
   device.executeShellCommand("am start -W -n ${ctx.packageName}/io.whaletodo.app.MainActivity")
   var activity:MainActivity?=null;repeat(100){if(activity==null){ins.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};delay(50)}}
   assertNotNull(activity);ins.runOnMainSync{
    originalPayments=PaymentBridgeHub.bridge;originalProfile=ProfileBridgeHub.bridge
    PaymentBridgeHub.bridge=payments;ProfileBridgeHub.bridge=profile
    activity!!.setContent{MaterialTheme{Surface{ProfileDialogHost(controller,DeskState())}}}
   }
   controller.open(MyDialog.RECHARGE_POINTS)
   assertTrue(device.wait(Until.hasObject(By.text("模拟支付")),8000));assertNull(pending.read(owner))
   assertTrue(device.wait(Until.hasObject(By.text("100 积分")),5000));device.findObject(By.text("100 积分")).click()
   device.findObject(By.text("去支付")).click()
   assertTrue(device.wait(Until.hasObject(By.textStartsWith("确认支付")),8000));assertNotNull(pending.read(owner)?.orderId)
   val firstId=pending.read(owner)!!.orderId
   device.findObject(By.text("返回")).click()
   withTimeout(8000){while(controller.state.value.busy||controller.state.value.checkout?.order?.id!=firstId)delay(30)}
   assertEquals(firstId,pending.read(owner)?.orderId);assertEquals(beforePoints,session.access.value.availablePoints)
   assertTrue(device.wait(Until.hasObject(By.text("继续支付")),8000));device.findObject(By.text("继续支付")).click()
   assertTrue(device.wait(Until.hasObject(By.textStartsWith("确认支付")),8000));device.findObject(By.textStartsWith("确认支付")).click()
   assertTrue(device.wait(Until.hasObject(By.text("支付成功")),8000));assertEquals(beforePoints+100,session.access.value.availablePoints);assertNull(pending.read(owner))
   device.takeScreenshot(File(ctx.cacheDir,"mock-payment-points-ui.png"))
   device.findObject(By.text("完成")).click();assertTrue(device.wait(Until.hasObject(By.text("关闭")),5000));device.findObject(By.text("关闭")).click();controller.open(MyDialog.RECHARGE_VIP)
   withTimeout(8000){while(controller.state.value.busy||controller.state.value.checkout?.order!=null)delay(30)}
   assertTrue(device.wait(Until.hasObject(By.text("去支付")),5000));device.findObject(By.text("去支付")).click()
   assertTrue(device.wait(Until.hasObject(By.textStartsWith("确认支付")),8000));device.findObject(By.textStartsWith("确认支付")).click()
   assertTrue(device.wait(Until.hasObject(By.text("支付成功")),8000));assertTrue(session.access.value.vip)
   assertEquals(beforePoints+100,session.access.value.availablePoints);assertNull(pending.read(owner))
   assertNotNull(session.details.value?.vipExpiresAt);beforeVip?.let{assertTrue(session.details.value!!.vipExpiresAt!!.isAfter(it))}
   device.takeScreenshot(File(ctx.cacheDir,"mock-payment-vip-ui.png"))
  }finally{PaymentBridgeHub.bridge=originalPayments;ProfileBridgeHub.bridge=originalProfile;scope.cancel();db.close()}
 }
}
