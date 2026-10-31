package io.whaletodo.app

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.whaletodo.app.account.*
import io.whaletodo.app.payment.*
import io.whaletodo.app.profile.ProfileBridgeHub
import io.whaletodo.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic receipts and local API seam only; never connects to a payment or model service. */
class CashierRecoveryIsolationTest {
    @Test fun pendingReceiptSurvivesNewStoreAndColdStart() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        val phase=InstrumentationRegistry.getArguments().getString("payment_phase")?:"roundtrip"
        val backend="https://pending-process-test.invalid/api/v1"
        val first=DevicePaymentPendingStore(ctx,backend)
        val receipt=PendingPayment("points_100","synthetic-cold-start-key",1234,"11111111-1111-4111-8111-111111111111")
        if(phase!="restore")first.save("synthetic-A",receipt)
        if(phase=="save")return
        try {
            val reopened=DevicePaymentPendingStore(ctx,backend)
            assertEquals(receipt,reopened.read("synthetic-A"))
            assertNull(reopened.read("synthetic-B"))
            assertNull(DevicePaymentPendingStore(ctx,backend+"/other").read("synthetic-A"))
        } finally {first.clear("synthetic-A");first.clear("synthetic-B")}
    }

    @Test fun cashierRecreationDoesNotPayAndSwitchingAccountClosesTheOldPage():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        installAccount(ctx)
        val previousPayments=PaymentBridgeHub.bridge;val previousProfile=ProfileBridgeHub.bridge
        val tokens=InMemoryTokenStore(TokenPair("synthetic-a","synthetic-refresh","A-session"))
        val credentials=object:AccountCredentials,TokenStore by tokens {
            override fun readEmail()="a@example.invalid"
            override fun saveEmail(email:String){}
        }
        val session=AccountSession{tokens.read()?.sessionId}
        session.begin("A-session");session.update("A-session","a@example.invalid",AccountProfile("synthetic-A","A",null),Entitlements(false,null),0)
        val order=PaymentOrder("11111111-1111-4111-8111-111111111111","points_100","合成积分",100,"CNY","PENDING","mock")
        val reads=AtomicInteger();val pages=AtomicInteger();val confirms=AtomicInteger()
        val api=object:PaymentApi {
            override fun catalog(sessionId:String)=PaymentCatalog(true,emptyList())
            override fun create(sessionId:String,productId:String,key:String)=error("No creation in this test")
            override fun order(sessionId:String,id:String):PaymentOrder{reads.incrementAndGet();return order}
            override fun cashier(sessionId:String,id:String):String{pages.incrementAndGet();return "<html><body><h1>合成收银台</h1><form action='whaletodo-pay://confirm'><input name='order' type='hidden' value='$id'><input name='channel' type='hidden' value='alipay'><button>确认模拟支付</button></form></body></html>"}
            override fun confirmMock(sessionId:String,id:String):PaymentOrder{confirms.incrementAndGet();return order.copy(status="PAID")}
        }
        val auth=AuthApi(ApiClient(ApiConfig("https://cashier-test.invalid/api/v1"),tokens),tokens)
        val store=DevicePaymentPendingStore(ctx,"https://cashier-isolation-test.invalid/api/v1")
        store.save("synthetic-A",PendingPayment(order.productId,"synthetic-key",System.currentTimeMillis(),order.id))
        PaymentBridgeHub.bridge=ServerPaymentBridge(api,auth,session,store)
        ProfileBridgeHub.bridge=ServerProfileBridge(auth,session,credentials)
        val intent=Intent(ctx,CashierActivity::class.java).putExtra("owner","synthetic-A").putExtra("order",order.id)
        val device=UiDevice.getInstance(ins)
        try {
            ActivityScenario.launch<CashierActivity>(intent).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("确认模拟支付")),8000))
                scenario.recreate()
                withTimeout(8000){while(pages.get()<2)delay(25)}
                assertEquals(0,confirms.get())
                val before=reads.get()
                tokens.save(TokenPair("synthetic-b","synthetic-refresh-b","B-session"));session.begin("B-session")
                session.update("B-session","b@example.invalid",AccountProfile("synthetic-B","B",null),Entitlements(false,null),7)
                withTimeout(8000){while(scenario.state!=Lifecycle.State.DESTROYED)delay(25)}
                assertEquals(before,reads.get());assertEquals(0,confirms.get());assertEquals(7,session.access.value.availablePoints)
                assertNotNull(store.read("synthetic-A"));assertNull(store.read("synthetic-B"))
            }
        } finally {store.clear("synthetic-A");PaymentBridgeHub.bridge=previousPayments;ProfileBridgeHub.bridge=previousProfile}
    }
}
