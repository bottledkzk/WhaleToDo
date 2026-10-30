package io.whaletodo.app.payment

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.whaletodo.app.account.installAccount
import io.whaletodo.app.profile.ProfileBridgeHub

/** Only this app can open checkout. HTML is fetched with the current session; no token enters the WebView. */
class CashierActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAccount(this)
        val owner = intent.getStringExtra("owner") ?: return finish()
        val order = intent.getStringExtra("order") ?: return finish()
        if (!Regex("[a-fA-F0-9-]{36}").matches(order)) return finish()
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var html by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                var web by remember { mutableStateOf<WebView?>(null) }
                fun load() {
                    if (busy) return
                    busy = true; error = null
                    scope.launch { try {
                        withTimeout(15_000) { ProfileBridgeHub.bridge.details.first { it?.userId == owner } }
                        PaymentBridgeHub.bridge.inspect(owner)
                        html = PaymentBridgeHub.bridge.cashier(owner, order)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "暂时无法读取订单，请重试" }
                    finally { busy = false } }
                }
                fun confirm(uri: Uri) {
                    if (busy || uri.getQueryParameter("order") != order || uri.getQueryParameter("channel") !in setOf("alipay", "unionpay")) return
                    busy = true; error = null
                    scope.launch { try {
                        val paid = PaymentBridgeHub.bridge.confirmCashier(owner, order)
                        check(paid.id == order && paid.status == "PAID")
                        html = PaymentBridgeHub.bridge.cashier(owner, order)
                        setResult(RESULT_OK)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "暂未确认支付结果，请查询原订单" }
                    finally { busy = false } }
                }
                LaunchedEffect(Unit) { load() }
                DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick = { finish() }, enabled = !busy) { Text("返回") }
                            Text("在线收银台", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { message ->
                            Column(Modifier.padding(24.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = ::load, enabled = !busy) { Text("查询订单") }
                            }
                        }
                        if (html != null && error == null) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            WebView(context).apply {
                                web = this
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.setSupportMultipleWindows(false)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val uri = request.url
                                        if (request.isForMainFrame && uri.scheme == "whaletodo-pay") when (uri.host) {
                                            "confirm" -> confirm(uri)
                                            "return" -> finish()
                                        }
                                        return true
                                    }
                                }
                            }
                        }, update = { view ->
                            if (view.tag != html) {
                                view.tag = html
                                view.loadDataWithBaseURL("https://whaletodo-cashier.invalid/", html!!, "text/html", "UTF-8", null)
                            }
                            view.isEnabled = !busy
                        })
                    }
                }
            }
        }
    }
}
