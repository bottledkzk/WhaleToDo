package io.whaletodo.app.account

import io.whaletodo.network.AccountCloudApi
import io.whaletodo.network.CloudDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AccountCloudBridge {
 suspend fun read(owner:String,kind:String):CloudDocument
 suspend fun write(owner:String,kind:String,revision:Int,payload:String):CloudDocument
}
object AccountCloudBridgeHub {
 var bridge:AccountCloudBridge=object:AccountCloudBridge {
  override suspend fun read(owner:String,kind:String):CloudDocument=error("云端服务未连接")
  override suspend fun write(owner:String,kind:String,revision:Int,payload:String):CloudDocument=error("云端服务未连接")
 }
}
class ServerAccountCloudBridge(private val api:AccountCloudApi,private val session:AccountSession):AccountCloudBridge {
 private suspend fun <T> bound(owner:String,action:(String)->T):T=withContext(Dispatchers.IO){
  val id=session.currentSessionId()?:error("请先登录")
  check(session.access.value.userId==owner){"账户已变化"}
  val result=action(id)
  check(session.currentSessionId()==id&&session.access.value.userId==owner){"账户已变化"}
  result
 }
 override suspend fun read(owner:String,kind:String)=bound(owner){api.read(it,kind)}
 override suspend fun write(owner:String,kind:String,revision:Int,payload:String)=bound(owner){api.write(it,kind,revision,payload)}
}
