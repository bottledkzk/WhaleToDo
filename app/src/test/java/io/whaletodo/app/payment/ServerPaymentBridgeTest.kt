package io.whaletodo.app.payment

import java.net.ServerSocket
import java.net.InetAddress
import java.io.IOException
import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.profile.ProfileException
import io.whaletodo.network.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

class ServerPaymentBridgeTest {
 private class LocalHttp(private val respond:(String)->String):AutoCloseable {
  private val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
  val port=socket.localPort
  init{Thread{while(!socket.isClosed){try{socket.accept().use{c->
   val reader=c.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1]
   while(!reader.readLine().isNullOrEmpty()){}
   val data=respond(path).toByteArray();c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray());write(data);flush()}
  }}catch(_:IOException){}}}.apply{isDaemon=true;start()}}
  override fun close(){socket.close()}
 }
 private class Store:PaymentPendingStore {
  val rows=mutableMapOf<String,PendingPayment>();var failSave=false
  override fun read(owner:String)=rows[owner]
  override fun save(owner:String,pending:PendingPayment){if(failSave)throw IOException();rows[owner]=pending}
  override fun clear(owner:String){rows.remove(owner)}
 }
 private class Api:PaymentApi {
  var created=0;var confirmed=0;var fetched=0;var cashierLoads=0;val keys=mutableListOf<String>();var lostCreate=false;var lostPay=false;var switchDuringPay:()->Unit={};var switchDuringOrder:()->Unit={}
  var value=PaymentOrder("11111111-1111-4111-8111-111111111111","points_100","100 积分",100,"CNY","PENDING","mock")
  override fun catalog(sessionId:String)=PaymentCatalog(true,emptyList())
  override fun create(sessionId:String,productId:String,key:String):PaymentOrder{created++;keys+=key;if(lostCreate){lostCreate=false;throw IOException()};return value}
  override fun order(sessionId:String,id:String):PaymentOrder{fetched++;switchDuringOrder();return value}
  override fun cashier(sessionId:String,id:String):String{cashierLoads++;return "<!doctype html><title>Cashier</title>"}
  override fun confirmMock(sessionId:String,id:String):PaymentOrder{confirmed++;value=value.copy(status="PAID");switchDuringPay();if(lostPay){lostPay=false;throw IOException()};return value}
 }
 private class Fixture:AutoCloseable {
  val tokens=InMemoryTokenStore(TokenPair("access-A","refresh-A","A-session"));val session=AccountSession{tokens.read()?.sessionId};val store=Store();val api=Api();var walletCalls=0
  val server=LocalHttp{path->walletCalls++
   if(path.endsWith("/wallet"))"""{"data":{"available":100,"reserved":0}}""" else """{"data":{"vip":{"active":true,"expires_at":"2030-01-01T00:00:00Z"}}}"""
  }
  val auth=AuthApi(ApiClient(ApiConfig("http://127.0.0.1:${server.port}/api/v1",true),tokens),tokens)
  init{session.begin("A-session");session.update("A-session","a@example.invalid",AccountProfile("A","user_a",null),Entitlements(false,null),0)}
  fun bridge()=ServerPaymentBridge(api,auth,session,store,Dispatchers.Unconfined)
  override fun close(){server.close()}
 }
 @Test fun lostCreationReusesDurableKeyAndInspectDoesNotRepeatPost()=runBlocking {Fixture().use{f->
  f.api.lostCreate=true;assertThrows(ProfileException::class.java){runBlocking{f.bridge().prepare("A","points_100")}}
  assertNotNull(f.store.read("A"));f.bridge().inspect("A");assertEquals(1,f.api.created);assertEquals(0,f.api.confirmed)
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().prepare("A","vip_30d")}}
  f.bridge().prepare("A","points_100");assertEquals(2,f.api.created);assertEquals(1,f.api.keys.distinct().size)
 }}
 @Test fun cashierOnlyLoadsOwnedOrderAndStalePageCannotPayAnotherPendingOrder()=runBlocking {Fixture().use{f->
  val order=f.bridge().prepare("A","points_100")
  assertTrue(f.bridge().cashier("A",order.id).contains("Cashier"));assertEquals(0,f.api.confirmed)
  val wrong="22222222-2222-4222-8222-222222222222"
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().cashier("A",wrong)}}
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirmCashier("A",wrong)}}
  assertEquals(0,f.api.confirmed)
  assertEquals("PAID",f.bridge().confirmCashier("A",order.id).status)
  assertTrue(f.bridge().cashier("A",order.id).contains("Cashier"));assertEquals(1,f.api.confirmed)
 }}
 @Test fun storageFailureAndExpiredUnknownReceiptNeverCreateAnotherOrder()=runBlocking {Fixture().use{f->
  f.store.failSave=true;assertThrows(ProfileException::class.java){runBlocking{f.bridge().prepare("A","points_100")}};assertEquals(0,f.api.created)
  f.store.failSave=false;f.store.save("A",PendingPayment("points_100","old",0));assertThrows(ProfileException::class.java){runBlocking{f.bridge().prepare("A","points_100")}};assertEquals(0,f.api.created)
 }}
 @Test fun lostPaymentResponseRecoversByQueryAndRefreshesServerBalance()=runBlocking {Fixture().use{f->
  f.bridge().prepare("A","points_100");f.api.lostPay=true
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirm("A")}};assertNotNull(f.store.read("A"));assertEquals(0,f.session.access.value.availablePoints)
  assertEquals("PAID",f.bridge().inspect("A").order?.status);assertEquals(1,f.api.confirmed);assertEquals(100,f.session.access.value.availablePoints);assertTrue(f.session.access.value.vip);assertNull(f.store.read("A"))
 }}
 @Test fun switchingOwnerDuringConfirmationCannotReadOrCreditNewAccount()=runBlocking {Fixture().use{f->
  f.bridge().prepare("A","points_100");f.api.switchDuringPay={f.tokens.save(TokenPair("access-B","refresh-B","B-session"));f.session.begin("B-session");f.session.update("B-session","b@example.invalid",AccountProfile("B","user_b",null),Entitlements(false,null),7)}
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirm("A")}};assertEquals(0,f.walletCalls);assertEquals(7,f.session.access.value.availablePoints);assertNotNull(f.store.read("A"));assertNull(f.store.read("B"))
 }}
 @Test fun ownerSwitchWhileWaitingForIoDoesNotSendOrderUnderNewSession()=runBlocking {Fixture().use{f->supervisorScope {
  var queued:Runnable?=null
  val gate=object:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){queued=block}}
  val bridge=ServerPaymentBridge(f.api,f.auth,f.session,f.store,gate)
  val attempt=async(start=CoroutineStart.UNDISPATCHED){bridge.prepare("A","points_100")}
  assertNotNull(queued);f.tokens.save(TokenPair("b","b-refresh","B-session"));f.session.begin("B-session");f.session.update("B-session","b@example.invalid",AccountProfile("B","user_b",null),Entitlements(false,null),7)
  queued!!.run();assertTrue(runCatching{attempt.await()}.exceptionOrNull() is ProfileException)
  assertEquals(0,f.api.created);assertEquals(0,f.walletCalls);assertNull(f.store.read("A"));assertEquals(7,f.session.access.value.availablePoints)
 }}}
 @Test fun productMismatchIsRejectedBeforeAnyPaymentMutation()=runBlocking {Fixture().use{f->
  val order=f.bridge().prepare("A","points_100")
  f.store.save("A",f.store.read("A")!!.copy(productId="vip_30d"))
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirmCashier("A",order.id)}}
  assertEquals(0,f.api.confirmed);assertEquals("PENDING",f.api.value.status);assertEquals(0,f.walletCalls)
 }}
 @Test fun reopenedBridgeRecoversSamePendingOrderWithoutCreatingOrConfirming()=runBlocking {Fixture().use{f->
  val order=f.bridge().prepare("A","points_100")
  val recovered=f.bridge().inspect("A")
  assertEquals(order.id,recovered.order!!.id);assertEquals(1,f.api.created);assertEquals(0,f.api.confirmed)
 }}
 @Test fun ownerSwitchBlocksCashierReadAndConfirmationOfPreviousOwnersPendingOrder()=runBlocking {Fixture().use{f->
  val order=f.bridge().prepare("A","points_100");val reads=f.api.fetched
  f.tokens.save(TokenPair("b","b-refresh","B-session"));f.session.begin("B-session");f.session.update("B-session","b@example.invalid",AccountProfile("B","user_b",null),Entitlements(false,null),7)
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().cashier("A",order.id)}}
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirmCashier("A",order.id)}}
  assertEquals(reads,f.api.fetched);assertEquals(0,f.api.cashierLoads);assertEquals(0,f.api.confirmed);assertEquals(7,f.session.access.value.availablePoints)
  assertNotNull(f.store.read("A"));assertNull(f.store.read("B"))
 }}
 @Test fun sessionChangeDuringPreflightDoesNotPostPayment()=runBlocking {Fixture().use{f->
  val order=f.bridge().prepare("A","points_100")
  f.api.switchDuringOrder={f.tokens.save(TokenPair("b","b-refresh","B-session"));f.session.begin("B-session");f.session.update("B-session","b@example.invalid",AccountProfile("B","user_b",null),Entitlements(false,null),7)}
  assertThrows(ProfileException::class.java){runBlocking{f.bridge().confirmCashier("A",order.id)}}
  assertEquals(0,f.api.confirmed);assertEquals(0,f.walletCalls)
 }}
}
