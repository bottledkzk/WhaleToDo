package io.whaletodo.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class PaymentApiTest {
 @Test fun orderRequestKeepsKeyDuringSessionRefreshAndNeverSendsPrice() {
  val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
  val tokens=InMemoryTokenStore(TokenPair("old","refresh","session"));val keys=mutableListOf<String?>();val bodies=mutableListOf<String>()
  val id="11111111-1111-4111-8111-111111111111"
  server.createContext("/"){x->
   val path=x.requestURI.path;val body=x.requestBody.bufferedReader().readText()
   val (code,response)=when{
    path.endsWith("/auth/refresh")->200 to """{"data":{"access_token":"new","refresh_token":"new-refresh"}}"""
    else->{keys+=x.requestHeaders.getFirst("Idempotency-Key");bodies+=body
     if(x.requestHeaders.getFirst("Authorization")=="Bearer old")401 to """{"error":{"code":"UNAUTHENTICATED","message":"expired"}}"""
     else 201 to """{"data":{"order":{"id":"$id","product_id":"points_100","amount_minor":100,"currency":"CNY","status":"PENDING","environment":"mock","product_snapshot":{"title":"100 积分"}}}}"""
    }
   };val bytes=response.toByteArray();x.sendResponseHeaders(code,bytes.size.toLong());x.responseBody.use{it.write(bytes)}
  };server.start()
  try{
   val api=ServerPaymentApi(ApiClient(ApiConfig("http://127.0.0.1:${server.address.port}/api/v1",true),tokens))
   assertEquals(id,api.create("session","points_100","durable-key").id)
   assertEquals(listOf("durable-key","durable-key"),keys);assertTrue(bodies.all{it=="""{"product_id":"points_100"}"""})
   val before=keys.size;assertThrows(ApiException::class.java){api.create("other-session","points_100","another-key")};assertEquals(before,keys.size)
  }finally{server.stop(0)}
 }
 @Test fun malformedOrderNeverBecomesPaid() {
  val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
  server.createContext("/"){x->val b="""{"data":{"order":{"status":"PAID","amount_minor":-1}}}""".toByteArray();x.sendResponseHeaders(200,b.size.toLong());x.responseBody.use{it.write(b)}};server.start()
  try{val api=ServerPaymentApi(ApiClient(ApiConfig("http://127.0.0.1:${server.address.port}/api/v1",true),InMemoryTokenStore(TokenPair("a","r","s"))))
   assertThrows(ApiException::class.java){api.order("s","11111111-1111-4111-8111-111111111111")}
  }finally{server.stop(0)}
 }
}
