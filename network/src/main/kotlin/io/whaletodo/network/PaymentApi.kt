package io.whaletodo.network

import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json as KJson

data class PaymentProduct(val id:String,val title:String,val priceMinor:Int,val currency:String,val vipDays:Int,val points:Int)
data class PaymentCatalog(val mockEnabled:Boolean,val products:List<PaymentProduct>)
data class PaymentOrder(val id:String,val productId:String,val title:String,val amountMinor:Int,val currency:String,val status:String,val environment:String)
interface PaymentApi {
 fun catalog(sessionId:String):PaymentCatalog
 fun create(sessionId:String,productId:String,key:String):PaymentOrder
 fun order(sessionId:String,id:String):PaymentOrder
 fun confirmMock(sessionId:String,id:String):PaymentOrder
}
class ServerPaymentApi(private val client:ApiClient):PaymentApi {
 override fun catalog(sessionId:String)=parse(client.getAsSession("/products",sessionId)){d->
  PaymentCatalog(d["payment_provider"]?.jsonPrimitive?.content=="mock" && d["mock_checkout"]?.jsonPrimitive?.booleanOrNull==true,
   d.getValue("products").jsonArray.map {e->val p=e.jsonObject
    PaymentProduct(p.text("product_id"),p.text("title"),p.number("price_minor"),p.text("currency"),p.number("vip_days"),p.number("points"))
   })
 }
 override fun create(sessionId:String,productId:String,key:String):PaymentOrder {
  require(Regex("[a-z0-9_]{1,32}").matches(productId));require(key.isNotBlank())
  return readOrder(client.postAsSession("/orders",buildJsonObject{put("product_id",productId)}.toString(),sessionId,key))
 }
 override fun order(sessionId:String,id:String)=readOrder(client.getAsSession(path(id),sessionId))
 override fun confirmMock(sessionId:String,id:String)=readOrder(client.postAsSession(path(id)+"/mock-pay","{}",sessionId))
 private fun path(id:String):String {require(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}").matches(id));return "/orders/$id"}
 private fun readOrder(r:ApiResponse)=parse(r){d->val o=d.getValue("order").jsonObject
  PaymentOrder(o.text("id"),o.text("product_id"),o.getValue("product_snapshot").jsonObject.text("title"),o.number("amount_minor"),o.text("currency"),o.text("status"),o.text("environment")).also{
   require(it.status in setOf("PENDING","PAID","CLOSED","REFUNDED"));require(it.environment in setOf("mock","production"));path(it.id)
  }
 }
 private fun JsonObject.text(key:String)=getValue(key).jsonPrimitive.content.also{require(it.isNotBlank()&&it.length<=300)}
 private fun JsonObject.number(key:String)=getValue(key).jsonPrimitive.int.also{require(it>=0)}
 private fun<T> parse(r:ApiResponse,block:(JsonObject)->T):T=try{block(KJson.parseToJsonElement(r.body).jsonObject.getValue("data").jsonObject)}
  catch(_:Exception){throw ApiException("UPSTREAM_UNAVAILABLE","订单响应无效，请查询原订单",r.status,r.requestId)}
}
