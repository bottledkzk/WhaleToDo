package io.whaletodo.network

import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json

data class CloudDocument(val revision:Int,val payloadJson:String,val updatedAt:Long?)
interface AccountCloudApi {
 fun read(sessionId:String,kind:String):CloudDocument
 fun write(sessionId:String,kind:String,revision:Int,payloadJson:String):CloudDocument
}
class ServerAccountCloudApi(private val client:ApiClient):AccountCloudApi {
 private fun path(kind:String):String {require(kind in setOf("schedule","profile"));return "/account/cloud/$kind"}
 override fun read(sessionId:String,kind:String)=parse(client.getAsSession(path(kind),sessionId))
 override fun write(sessionId:String,kind:String,revision:Int,payloadJson:String)=parse(client.postAsSession(path(kind),buildJsonObject{
  put("revision",revision);put("payload",Json.parseToJsonElement(payloadJson))
 }.toString(),sessionId))
 private fun parse(response:ApiResponse):CloudDocument {
  try{
   val data=Json.parseToJsonElement(response.body).jsonObject.getValue("data").jsonObject
   val revision=data.getValue("revision").jsonPrimitive.int
   require(revision>=0)
   val payload=data.getValue("payload").jsonObject
   val updated=(data["updated_at"] as? JsonPrimitive)?.longOrNull
   return CloudDocument(revision,payload.toString(),updated)
  }catch(_:Exception){throw ApiException("UPSTREAM_UNAVAILABLE","云端响应无效",response.status,response.requestId)}
 }
}
