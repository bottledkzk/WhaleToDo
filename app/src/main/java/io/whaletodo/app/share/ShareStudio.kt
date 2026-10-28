package io.whaletodo.app.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.app.agent.ShareCardPreview
import io.whaletodo.app.agent.ShareStyle
import io.whaletodo.core.AgentAccess
import io.whaletodo.core.AgentPolicy
import io.whaletodo.core.GateReason
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配文契约：由 AgentShareCopyProvider 通过 AgentBridge 的 share_copy 模式实现；
 * 不直接走 HTTP，也不在客户端扣积分。
 */
internal interface ShareCopyProvider {
    suspend fun suggest(style: ShareStyle, sourceText: String): List<String>
    suspend fun pending(ownerId: String): String? = null
    suspend fun recover(ownerId: String): List<String>? = null
}

private enum class ShareImageAction { TEXT, SAVE, SHARE }

/** Adds the URI grants that the stock camera contract omits, while keeping its result parsing. */
private class TakePictureWithGrant : ActivityResultContract<Uri, Boolean>() {
    private val delegate = ActivityResultContracts.TakePicture()

    override fun createIntent(context: Context, input: Uri): Intent =
        delegate.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        delegate.parseResult(resultCode, intent)
}

@Composable
internal fun ShareStyleSheet(
    text: String,
    access: AgentAccess,
    trialStyle: ShareStyle?,
    dismiss: () -> Unit,
    onGate: (GateReason, ShareStyle) -> Unit,
    onShareText: (String) -> Unit,
    onShared: () -> Unit,
    onTrialConsumed: () -> Unit,
    copyProvider: ShareCopyProvider? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val currentOwner by rememberUpdatedState(access.userId)
    var caption by remember(text) { mutableStateOf(text) }
    val lines = remember(caption) { caption.lines().map { it.trim() }.filter { it.isNotBlank() }.take(5) }
    var selected by remember { mutableStateOf(ShareStyle.MINIMAL) }
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var suggestions by remember(text) { mutableStateOf(emptyList<String>()) }
    var pendingId by remember(access.userId) { mutableStateOf<String?>(null) }
    var pendingLoading by remember(access.userId, copyProvider) { mutableStateOf(copyProvider != null) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var retryAction by remember { mutableStateOf<ShareImageAction?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAction by remember { mutableStateOf<ShareImageAction?>(null) }

    fun showStatus(message: String, error: Boolean = false, retry: ShareImageAction? = null) {
        status = message
        statusError = error
        retryAction = retry
    }

    fun stillSameOwner(owner: String?): Boolean = currentOwner == owner

    fun requestSuggestions() {
        val provider = copyProvider ?: return
        if (aiBusy || busy || pendingLoading || pendingId != null) return
        aiBusy = true
        suggestions = emptyList()
        showStatus("正在生成配文…")
        val styleAtRequest = selected
        val ownerAtRequest = access.userId
        scope.launch {
            try {
                val result = provider.suggest(styleAtRequest, text)
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = null
                suggestions = result.take(3)
                showStatus(if (result.isEmpty()) "配文生成失败，请重试" else "已生成配文，点击选择", error = result.isEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentGateException) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = null
                showStatus("")
                onGate(e.reason, styleAtRequest)
            } catch (e: AgentPendingException) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = e.requestId
                showStatus("配文生成中，请稍后重试")
            } catch (_: Exception) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = null
                showStatus("配文生成失败，请重试", error = true)
            } finally {
                aiBusy = false
            }
        }
    }

    fun recoverPending() {
        val provider = copyProvider ?: return
        val ownerId = access.userId?.takeIf { it.isNotBlank() } ?: return
        if (aiBusy || busy) return
        aiBusy = true
        showStatus("正在查询原配文…")
        scope.launch {
            try {
                val result = provider.recover(ownerId)
                if (!stillSameOwner(ownerId)) return@launch
                pendingId = null
                if (result == null) {
                    suggestions = emptyList()
                    showStatus("原配文请求已失败，可重新生成", error = true)
                } else {
                    suggestions = result.take(3)
                    showStatus("已恢复原配文结果，点击选择")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentPendingException) {
                if (!stillSameOwner(ownerId)) return@launch
                pendingId = e.requestId
                showStatus("仍未完成，请稍后再查")
            } catch (e: AgentGateException) {
                if (!stillSameOwner(ownerId)) return@launch
                // Keep the pending record; the user can retry after resolving the gate.
                showStatus("")
                onGate(e.reason, selected)
            } catch (e: AgentServiceException) {
                if (!stillSameOwner(ownerId)) return@launch
                if (e.definitelyRejected) {
                    pendingId = null
                    suggestions = emptyList()
                    showStatus("原配文请求已失败，可重新生成", error = true)
                } else {
                    showStatus("查询暂时不可用，原请求保留", error = true)
                }
            } catch (_: Exception) {
                if (!stillSameOwner(ownerId)) return@launch
                showStatus("查询失败，原请求保留", error = true)
            } finally {
                aiBusy = false
            }
        }
    }

    LaunchedEffect(access.userId, copyProvider) {
        val provider = copyProvider
        val ownerId = access.userId?.takeIf { it.isNotBlank() }
        pendingId = null
        caption = text
        suggestions = emptyList()
        if (provider == null || ownerId == null) {
            pendingLoading = false
            return@LaunchedEffect
        }
        pendingLoading = true
        pendingId = try {
            provider.pending(ownerId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        pendingLoading = false
        if (pendingId != null) showStatus("上次配文未完成，可查询原结果")
    }

    fun loadBackground(uri: Uri, cameraFile: File?) {
        if (busy) return
        busy = true
        showStatus("正在读取图片…")
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { ShareMedia.loadBitmap(context, uri) }.getOrNull()
                }
                if (bitmap != null) {
                    background = bitmap.asImageBitmap()
                    showStatus("")
                } else {
                    showStatus("无法读取图片，请重试", error = true)
                }
            } finally {
                ShareMedia.deleteCamera(cameraFile)
                busy = false
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) showStatus("已取消选图") else loadBackground(uri, null)
    }
    val takePicture = rememberLauncherForActivityResult(TakePictureWithGrant()) { ok ->
        val file = pendingCameraFile
        val uri = pendingCameraUri
        pendingCameraFile = null
        pendingCameraUri = null
        when {
            !ok -> {
                ShareMedia.deleteCamera(file)
                showStatus("已取消拍照")
            }
            uri == null -> {
                ShareMedia.deleteCamera(file)
                showStatus("无法读取拍照结果，请重试", error = true)
            }
            else -> loadBackground(uri, file)
        }
    }

    fun launchCamera() {
        if (busy) return
        val target = runCatching { ShareMedia.createCameraTarget(context) }.getOrNull()
        if (target == null) {
            showStatus("无法创建拍照文件，请重试", error = true)
            return
        }
        pendingCameraFile = target.file
        pendingCameraUri = target.uri
        runCatching { takePicture.launch(target.uri) }.onFailure {
            pendingCameraFile = null
            pendingCameraUri = null
            ShareMedia.deleteCamera(target.file)
            showStatus("未找到可用的相机应用", error = true)
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else showStatus("未获得相机权限，仍可从相册选图", error = true)
    }

    fun requestCamera() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun chooseImage() {
        if (busy) return
        runCatching {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            showStatus("无法打开图片选择器，请重试", error = true)
        }
    }

    fun performExport(save: Boolean) {
        if (busy) return
        busy = true
        showStatus("正在生成图片…")
        scope.launch {
            try {
                val bitmap = renderCardBitmap(layer)
                if (bitmap == null) error("empty card bitmap")
                if (save) {
                    withContext(Dispatchers.IO) { ShareMedia.saveToGallery(context, bitmap) }
                    showStatus("已保存到相册 · Pictures/WhaleToDo")
                    onTrialConsumed()
                } else {
                    val file = withContext(Dispatchers.IO) { ShareMedia.writeSharePng(context, bitmap) }
                    val uri = ShareMedia.fileUri(context, file)
                    ShareMedia.launchSystemShare(context, uri, caption)
                    onTrialConsumed()
                    onShared()
                }
            } catch (_: Throwable) {
                showStatus(
                    if (save) "保存失败，请重试；也可改用系统分享" else "分享失败，请重试",
                    error = true,
                    retry = if (save) ShareImageAction.SAVE else ShareImageAction.SHARE,
                )
            } finally {
                busy = false
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingAction = ShareImageAction.SAVE
        } else {
            showStatus("未获得存储权限，可改用系统分享", error = true, retry = ShareImageAction.SAVE)
        }
    }

    fun requestAction(action: ShareImageAction) {
        if (busy) return
        val gate = AgentPolicy.shareGate(access, selected.premium, trialStyle == selected)
        if (gate != null) {
            onGate(gate, selected)
            return
        }
        when (action) {
            ShareImageAction.TEXT -> onShareText(caption)
            ShareImageAction.SAVE -> {
                if (ShareMedia.needsLegacyStoragePermission(context)) {
                    showStatus("需要存储权限才能保存到相册，可改用系统分享", retry = ShareImageAction.SAVE)
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    performExport(save = true)
                }
            }
            ShareImageAction.SHARE -> performExport(save = false)
        }
    }

    LaunchedEffect(pendingAction) {
        when (pendingAction) {
            ShareImageAction.SAVE -> {
                pendingAction = null
                performExport(save = true)
            }
            ShareImageAction.SHARE -> {
                pendingAction = null
                performExport(save = false)
            }
            else -> Unit
        }
    }
    LaunchedEffect(trialStyle) {
        if (trialStyle != null && trialStyle == selected) {
            showStatus("模板试用已获准，请点击保存或分享")
        }
    }

    Dialog(onDismissRequest = { if (!busy) dismiss() }) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "分享样式",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { if (!busy) dismiss() },
                        modifier = Modifier.semantics { contentDescription = "关闭分享" },
                    ) { Text("×") }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "分享卡片预览" }
                        .drawWithContent {
                            if (size.width <= 0f || size.height <= 0f) {
                                drawContent()
                            } else {
                                val outputWidth = max(SharePlan.TARGET_WIDTH, size.width.roundToInt())
                                val factor = outputWidth / size.width
                                val outputHeight = (size.height * factor).roundToInt().coerceAtLeast(1)
                                layer.record(
                                    density = this,
                                    layoutDirection = layoutDirection,
                                    size = IntSize(outputWidth, outputHeight),
                                ) {
                                    scale(factor, factor, pivot = Offset.Zero) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                                withTransform({
                                    scale(1f / factor, 1f / factor, pivot = Offset.Zero)
                                }) {
                                    this@drawWithContent.drawLayer(layer)
                                }
                            }
                        },
                ) {
                    ShareCardPreview(
                        selected,
                        "今天的进度",
                        lines,
                        background = background,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ShareStyle.entries) { style ->
                        FilterChip(
                            selected == style,
                            onClick = {
                                selected = style
                                showStatus("")
                            },
                            label = {
                                Text(
                                    style.label + if (style.premium && !access.vip && trialStyle != style) " · VIP" else "",
                                )
                            },
                        )
                    }
                }
                if (copyProvider != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = ::requestSuggestions,
                            enabled = !aiBusy && !busy && !pendingLoading && pendingId == null,
                        ) { Text(if (aiBusy) "配文生成中…" else "AI 配文") }
                        if (pendingId != null) {
                            OutlinedButton(
                                onClick = ::recoverPending,
                                enabled = !aiBusy && !busy,
                            ) { Text(if (aiBusy) "查询中…" else "查询原结果") }
                        }
                        if (aiBusy || pendingLoading) CircularProgressIndicator(Modifier.size(18.dp))
                    }
                }
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestions) { suggestion ->
                            FilterChip(
                                selected = caption == suggestion,
                                onClick = {
                                    caption = suggestion
                                    showStatus("已选择 AI 配文")
                                },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = ::chooseImage, enabled = !busy) { Text("选图") }
                    OutlinedButton(onClick = ::requestCamera, enabled = !busy) { Text("拍照") }
                    if (background != null) {
                        TextButton(onClick = { background = null; showStatus("") }, enabled = !busy) {
                            Text("移除背景")
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            status,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (retryAction != null && !busy) {
                            TextButton(onClick = { retryAction?.let(::requestAction) }) { Text("重试") }
                        }
                    }
                }
                Text(
                    "照片仅在本机合成，不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { requestAction(ShareImageAction.SAVE) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存相册") }
                    Button(
                        onClick = { requestAction(ShareImageAction.SHARE) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("系统分享") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { requestAction(ShareImageAction.TEXT) }, enabled = !busy) {
                        Text("分享文字")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss, enabled = !busy) { Text("关闭") }
                }
            }
        }
    }
}

private suspend fun renderCardBitmap(layer: GraphicsLayer): Bitmap? {
    val image = layer.toImageBitmap()
    var bitmap = image.asAndroidBitmap()
    if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    }
    val size = SharePlan.outputSize(bitmap.width, bitmap.height)
    if (bitmap.width != size.width || bitmap.height != size.height) {
        bitmap = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
    }
    return bitmap
}
