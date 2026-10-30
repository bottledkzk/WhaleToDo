package io.whaletodo.app.payment

import android.content.Context
import io.whaletodo.app.profile.ProfileUnavailable
import io.whaletodo.network.*
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json as KJson

data class PendingPayment(val productId:String,val key:String,val createdAt:Long,val orderId:String?=null)
data class PaymentCheckout(val catalog:PaymentCatalog,val pendingProductId:String?=null,val order:PaymentOrder?=null)
interface PaymentPendingStore {
 fun read(owner:String):PendingPayment?
 fun save(owner:String,pending:PendingPayment)
 fun clear(owner:String)
}
/** Private per-backend, per-owner recovery receipt; never part of account/cloud payloads. */
class DevicePaymentPendingStore(context:Context,backend:String):PaymentPendingStore {
 private val prefs=context.getSharedPreferences("payment_pending_"+hash(backend),Context.MODE_PRIVATE)
 override fun read(owner:String):PendingPayment? {
  val raw=prefs.getString(hash(owner),null)?:return null
  return try {val o=KJson.parseToJsonElement(raw).jsonObject
   PendingPayment(o.getValue("product").jsonPrimitive.content,o.getValue("key").jsonPrimitive.content,o.getValue("created").jsonPrimitive.long,
    (o["order"] as? JsonPrimitive)?.contentOrNull)
  }catch(_:Exception){throw IOException("Pending payment receipt unreadable")}
 }
 override fun save(owner:String,pending:PendingPayment) {
  val raw=buildJsonObject{put("product",pending.productId);put("key",pending.key);put("created",pending.createdAt);pending.orderId?.let{put("order",it)}}.toString()
  if(!prefs.edit().putString(hash(owner),raw).commit())throw IOException("Pending payment receipt not saved")
 }
 override fun clear(owner:String){if(!prefs.edit().remove(hash(owner)).commit())throw IOException("Pending payment receipt not cleared")}
 private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
}
interface PaymentBridge {
 suspend fun inspect(owner:String):PaymentCheckout
 suspend fun prepare(owner:String,productId:String):PaymentOrder
 suspend fun confirm(owner:String):PaymentOrder
}
object PaymentBridgeHub {
 var bridge:PaymentBridge=object:PaymentBridge {
  override suspend fun inspect(owner:String):PaymentCheckout=throw ProfileUnavailable()
  override suspend fun prepare(owner:String,productId:String):PaymentOrder=throw ProfileUnavailable()
  override suspend fun confirm(owner:String):PaymentOrder=throw ProfileUnavailable()
 }
}
