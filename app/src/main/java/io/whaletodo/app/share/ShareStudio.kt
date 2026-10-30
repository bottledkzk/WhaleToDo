package io.whaletodo.app.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
internal data class ShareCopyResult(
    val suggestions: List<String>,
    val requestId: String,
    /** null means the receipt did not expose a usable usage value (unknown, not fabricated). */
    val usage: Int?,
    /** 0 is a real settled value; null means unknown. */
    val chargedPoints: Int?,
)

internal interface ShareCopyProvider {
    suspend fun suggest(style: ShareStyle, sourceText: String): ShareCopyResult
    suspend fun pending(ownerId: String, sourceFingerprint: String = ""): String? = null
    suspend fun recover(ownerId: String, sourceFingerprint: String = ""): ShareCopyResult? = null
}

/** Immutable snapshot passed to the offscreen export composition. */
private data class ShareCardSnapshot(
    val style: ShareStyle,
    val lines: List<String>,
    val background: ImageBitmap?,
)

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
    draftStore: ShareDraftStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOwner by rememberUpdatedState(access.userId)
    var caption by remember(text) { mutableStateOf(text) }
    var captureView by remember { mutableStateOf<ComposeView?>(null) }
    val density = LocalDensity.current
    val captureWidth = with(density) { SharePlan.TARGET_WIDTH.toDp() }
    val captureHeight = with(density) { (SharePlan.TARGET_WIDTH / 0.78f).roundToInt().toDp() }
    // Export and preview must always use the selected AI caption, never the original text.
    val lines = remember(caption) { caption.lines().map { it.trim() }.filter { it.isNotBlank() }.take(5) }
    var selected by remember { mutableStateOf(ShareStyle.MINIMAL) }
    var background by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var suggestions by remember(text) { mutableStateOf(emptyList<String>()) }
    var pendingId by remember(access.userId) { mutableStateOf<String?>(null) }
    var pendingLoading by remember(access.userId, copyProvider) { mutableStateOf(copyProvider != null) }
    val sourceFingerprint = remember(text) { ShareDraft.fingerprint(text) }
    var draftRequestId by remember(text) { mutableStateOf<String?>(null) }
    var draftUsage by remember(text) { mutableStateOf<Int?>(null) }
    var draftChargedPoints by remember(text) { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var retryAction by remember { mutableStateOf<ShareImageAction?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAction by remember { mutableStateOf<ShareImageAction?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val exportSnapshot = rememberUpdatedState(ShareCardSnapshot(selected, lines, background))
    val persistScope = remember { ShareDraftIo.scope }
    val persister = remember(draftStore, access.userId, sourceFingerprint) {
        val store = draftStore
        val owner = access.userId?.takeIf { it.isNotBlank() }
        if (store != null && owner != null) {
            ShareDraftPersister(store, owner, sourceFingerprint)
        } else {
            null
        }
    }
    // Backstop for account/source changes that remove the sheet without pressing close.
    // The explicit close path awaits flush; this keeps the last write durable too.
    DisposableEffect(persister) {
        onDispose {
            persister?.let { writer -> ShareDraftIo.scope.launch { writer.flush() } }
        }
    }

    fun showStatus(message: String, error: Boolean = false, retry: ShareImageAction? = null) {
        status = message
        statusError = error
        retryAction = retry
    }

    fun stillSameOwner(owner: String?): Boolean = currentOwner == owner

    fun persistDraft() {
        val writer = persister ?: return
        if (closing || clearing) return
        val requestId = draftRequestId ?: return
        if (suggestions.isEmpty()) return
        val selectedText = caption.takeIf { it in suggestions }
        val editedText = caption.takeIf { it.isNotBlank() && it != text && it !in suggestions }
        val draft = ShareDraft(
            ownerId = writer.ownerId,
            sourceFingerprint = sourceFingerprint,
            suggestions = suggestions.take(3),
            selectedText = selectedText,
            editedText = editedText,
            styleCode = selected.code,
            requestId = requestId,
            usage = draftUsage,
            chargedPoints = draftChargedPoints,
            updatedAt = System.currentTimeMillis(),
        )
        val job = writer.save(draft)
        persistScope.launch {
            val result = job.await()
            if (!result.success) {
                withContext(Dispatchers.Main.immediate) {
                    showStatus("草稿保存失败，请重试", error = true)
                }
            }
        }
    }

    fun clearDraft() {
        val writer = persister ?: return
        if (clearing || closing) return
        clearing = true
        val job = writer.clear()
        persistScope.launch {
            val result = job.await()
            withContext(Dispatchers.Main.immediate) {
                clearing = false
                if (result.success && result.applied) {
                    suggestions = emptyList()
                    draftRequestId = null
                    draftUsage = null
                    draftChargedPoints = null
                    caption = text
                    showStatus("已清除分享草稿")
                } else {
                    showStatus("清除草稿失败，请重试", error = true)
                }
            }
        }
    }

    /**
     * Explicitly flush the latest save before leaving the sheet. This scope is independent
     * of composition, so disposal cannot cancel the flush.
     */
    fun closeWithFlush(block: () -> Unit) {
        if (closing) return
        closing = true
        val writer = persister
        if (writer == null) {
            closing = false
            block()
            return
        }
        persistScope.launch {
            val result = writer.flush()
            withContext(Dispatchers.Main.immediate) {
                closing = false
                if (result != null && !result.success) {
                    showStatus("草稿保存失败，请重试", error = true)
                } else {
                    block()
                }
            }
        }
    }

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
                suggestions = result.suggestions.take(3)
                draftRequestId = result.requestId
                draftUsage = result.usage
                draftChargedPoints = result.chargedPoints
                showStatus(
                    if (result.suggestions.isEmpty()) "配文生成失败，请重试"
                    else "已生成配文，点击选择",
                    error = result.suggestions.isEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentGateException) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = ownerAtRequest?.let { owner ->
                    runCatching { provider.pending(owner, sourceFingerprint) }.getOrNull()
                }
                showStatus(
                    if (pendingId != null) "请求可能已受理，请重新登录后查询原结果" else "",
                    error = pendingId != null,
                )
                onGate(e.reason, styleAtRequest)
            } catch (e: AgentPendingException) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = e.requestId
                showStatus("配文生成中，请稍后重试")
            } catch (e: AgentServiceException) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = ownerAtRequest?.let { owner ->
                    runCatching { provider.pending(owner, sourceFingerprint) }.getOrNull()
                }
                if (pendingId != null) {
                    showStatus("请求可能已受理，可查询原结果", error = true)
                } else {
                    showStatus("配文生成失败，请重试", error = true)
                }
            } catch (_: Exception) {
                if (!stillSameOwner(ownerAtRequest)) return@launch
                pendingId = ownerAtRequest?.let { owner ->
                    runCatching { provider.pending(owner, sourceFingerprint) }.getOrNull()
                }
                if (pendingId != null) {
                    showStatus("请求可能已受理，可查询原结果", error = true)
                } else {
                    showStatus("配文生成失败，请重试", error = true)
                }
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
                val result = provider.recover(ownerId, sourceFingerprint)
                if (!stillSameOwner(ownerId)) return@launch
                pendingId = null
                if (result == null) {
                    suggestions = emptyList()
                    showStatus("来源已变化或原配文请求已失败，可重新生成", error = true)
                } else {
                    suggestions = result.suggestions.take(3)
                    draftRequestId = result.requestId
                    draftUsage = result.usage
                    draftChargedPoints = result.chargedPoints
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

    LaunchedEffect(access.userId, sourceFingerprint, copyProvider, draftStore) {
        val provider = copyProvider
        val store = draftStore
        val ownerId = access.userId?.takeIf { it.isNotBlank() }
        pendingId = null
        caption = text
        suggestions = emptyList()
        draftRequestId = null
        draftUsage = null
        draftChargedPoints = null
        if (ownerId == null) {
            pendingLoading = false
            return@LaunchedEffect
        }
        pendingLoading = provider != null
        val draft = store?.let {
            withContext(Dispatchers.IO) { it.load(ownerId, sourceFingerprint) }
        }
        if (draft != null) {
            suggestions = draft.suggestions.take(3)
            selected = ShareStyle.entries.find { it.code == draft.styleCode } ?: selected
            caption = draft.selectedText ?: draft.editedText ?: text
            draftRequestId = draft.requestId
            draftUsage = draft.usage
            draftChargedPoints = draft.chargedPoints
        }
        if (provider != null) {
            pendingId = try {
                provider.pending(ownerId, sourceFingerprint)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        pendingLoading = false
        when {
            draft != null -> showStatus("已恢复上次配文草稿（未重新请求）")
            pendingId != null -> showStatus("上次配文未完成，可查询原结果")
        }
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
                val bitmap = renderCardBitmap(captureView)
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
                    closeWithFlush { onShared() }
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
            ShareImageAction.TEXT -> closeWithFlush { onShareText(caption) }
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

    Dialog(onDismissRequest = { if (!busy && !closing) closeWithFlush(dismiss) }) {
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
                        onClick = { if (!busy && !closing) closeWithFlush(dismiss) },
                        modifier = Modifier.semantics { contentDescription = "关闭分享" },
                    ) { Text("×") }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "分享卡片预览" },
                ) {
                    ShareCardPreview(
                        selected,
                        "今天的进度",
                        lines,
                        background = background,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Offscreen composition used only for export. It is measured at the exact output
                // pixel size but clipped to 1dp, so the dialog layout is unchanged. Drawing this
                // view is reliable on API 30, unlike the GraphicsLayer snapshot path.
                Box(Modifier.size(1.dp).clipToBounds()) {
                    AndroidView(
                        factory = { ctx ->
                            ComposeView(ctx).also { view ->
                                captureView = view
                                view.setContent {
                                    val snapshot = exportSnapshot.value
                                    ShareCardPreview(
                                        snapshot.style,
                                        "今天的进度",
                                        snapshot.lines,
                                        background = snapshot.background,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.requiredSize(captureWidth, captureHeight),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ShareStyle.entries) { style ->
                        FilterChip(
                            selected == style,
                            onClick = {
                                selected = style
                                persistDraft()
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
                                    persistDraft()
                                    showStatus("已选择 AI 配文")
                                },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
                if (draftRequestId != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = caption,
                        onValueChange = {
                            caption = it
                            persistDraft()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分享文案（可编辑）") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            "本次用量：${draftUsage?.toString() ?: "未知"} tokens · " +
                                "结算积分：${draftChargedPoints?.toString() ?: "未知"} · " +
                                "请求 ID：${draftRequestId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = ::clearDraft,
                        enabled = !aiBusy && !busy && !clearing && !closing,
                    ) { Text(if (clearing) "正在清除…" else "清除分享草稿") }
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
                    "照片仅在本机合成，不会上传",
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

/**
 * Captures the offscreen export composition at [SharePlan.TARGET_WIDTH].
 *
 * The export view is a real Android View measured at the target size, so drawing it into a
 * software ARGB_8888 bitmap does not depend on API 30 GraphicsLayer/RenderNode readback. A
 * fully blank capture is rejected and never saved/shared.
 */
private fun renderCardBitmap(view: View?): Bitmap? {
    if (view == null || view.width <= 0 || view.height <= 0) return null
    val outputWidth = max(SharePlan.TARGET_WIDTH, view.width)
    val factor = outputWidth.toFloat() / view.width
    val outputHeight = (view.height * factor).roundToInt().coerceAtLeast(1)
    val captured = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(captured)
        canvas.drawColor(Color.WHITE)
        canvas.scale(factor, factor)
        view.draw(canvas)

        val size = SharePlan.outputSize(captured.width, captured.height)
        val result = if (captured.width != size.width || captured.height != size.height) {
            Bitmap.createScaledBitmap(captured, size.width, size.height, true)
        } else {
            captured
        }
        if (!hasVisibleContent(result)) {
            if (result !== captured) result.recycle()
            captured.recycle()
            return null
        }
        return result
    } catch (t: Throwable) {
        captured.recycle()
        throw t
    }
}

/** Rejects an all-white/transparent capture; a valid share card always contains text or an image. */
private fun hasVisibleContent(bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return false
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (pixel in row) {
            if ((pixel ushr 24) != 0 && pixel != Color.WHITE) return true
        }
    }
    return false
}
