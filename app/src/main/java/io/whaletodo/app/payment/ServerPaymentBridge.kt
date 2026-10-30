package io.whaletodo.app.payment

import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.profile.ProfileException
import io.whaletodo.network.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerPaymentBridge(private val api:PaymentApi,private val auth:AuthApi,private val session:AccountSession,
 private val store:PaymentPendingStore,private val io:CoroutineDispatcher=Dispatchers.IO):PaymentBridge {
 private val lock=Mutex()
 override suspend fun inspect(owner:String)=call(owner){sid->
  val catalog=api.catalog(sid);val p=store.read(owner)
  val order=p?.orderId?.let{checked(api.order(sid,it),p)}
  if(order?.status=="PAID")settle(owner,sid,order)
  PaymentCheckout(catalog,p?.productId?.takeUnless{order?.status=="PAID"},order)
 }
 override suspend fun prepare(owner:String,productId:String)=call(owner){sid->
  if(!api.catalog(sid).mockEnabled)throw ProfileException("充值尚未开放")
  val p=store.read(owner)?:PendingPayment(productId,UUID.randomUUID().toString(),System.currentTimeMillis()).also{store.save(owner,it)}
  if(p.productId!=productId)throw ProfileException("请先处理原模拟订单")
  // Server idempotency receipts live for 24h. Never recreate an unknown order after that window.
  val age=System.currentTimeMillis()-p.createdAt
  if(p.orderId==null&&age !in 0..23*60*60*1000L)throw ProfileException("原订单待核对，请联系维护者处理")
  val order=checked(if(p.orderId!=null)api.order(sid,p.orderId)else api.create(sid,p.productId,p.key),p)
  store.save(owner,p.copy(orderId=order.id))
  if(order.status=="PAID")settle(owner,sid,order)
  order
 }
 override suspend fun confirm(owner:String)=call(owner){sid->
  if(!api.catalog(sid).mockEnabled)throw ProfileException("模拟支付未开放")
  val p=store.read(owner)?:throw ProfileException("请先创建模拟订单")
  val id=p.orderId?:throw ProfileException("请先恢复原订单")
  val order=checked(api.confirmMock(sid,id),p)
  if(order.status!="PAID")throw ProfileException("订单尚未确认，请查询订单状态")
  settle(owner,sid,order);order
 }
 private fun checked(order:PaymentOrder,p:PendingPayment):PaymentOrder {
  if(order.productId!=p.productId||order.environment!="mock"||(p.orderId!=null&&order.id!=p.orderId))throw ProfileException("订单信息不一致，请核对原订单")
  return order
 }
 private fun settle(owner:String,sid:String,order:PaymentOrder) {
  check(order.status=="PAID")
  val rights=auth.entitlements(sid);val wallet=auth.wallet(sid)
  if(!session.updateBalance(sid,owner,rights,wallet.available))throw ProfileException("账户已切换，请重新打开充值")
  store.clear(owner)
 }
 private suspend fun<T> call(owner:String,block:(String)->T):T {
  val sid=session.currentSessionId()?:throw ProfileException("请先登录")
  if(session.access.value.userId!=owner)throw ProfileException("账户已切换，请重新打开充值")
  return withContext(io){lock.withLock{
  if(session.currentSessionId()!=sid||session.access.value.userId!=owner)throw ProfileException("账户已切换，请重新打开充值")
  try {val result=block(sid)
   if(session.currentSessionId()!=sid||session.access.value.userId!=owner)throw ProfileException("账户已切换，请重新打开充值")
   result
  }catch(e:ApiException){throw ProfileException(when(e.code){"UNAUTHENTICATED"->"登录已失效，请重新登录";"FORBIDDEN"->"订单不可访问，请核对原账户";else->"暂时无法确认，请查询原订单后重试"})}
   catch(_:IOException){throw ProfileException("网络或本地保存失败，原订单已保留，请查询或恢复原订单")}
 }}
 }
}
