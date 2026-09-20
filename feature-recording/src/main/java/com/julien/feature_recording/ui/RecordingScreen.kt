package com.julien.feature_recording.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arashivision.sdk.media.api.params.PreviewParams
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.arashivision.sdk.media.player.preview.InstaCapturePlayerView
import com.julien.core_media.codec.VideoCodecKind
import com.julien.feature_recording.RecordingModule
import com.julien.feature_recording.model.DownloadState
import com.julien.feature_recording.model.ExportState
import com.julien.feature_recording.model.MediaFileItem
import com.julien.feature_recording.model.PreviewStreamState
import com.julien.feature_recording.model.RecordingState
import com.julien.feature_recording.viewmodel.RecordingViewModel
import java.io.File
import timber.log.Timber

/**
 * 录制与媒体页。
 *
 * 界面刻意把方案文档要求的「渐进式确定性」摆到台面上：
 * 录制状态会区分「已下令 / 设备已确认」，预览会显示「探测编码中 / 等待关键帧」，
 * 打点会显示「尚未定位到原片」。这些都不是调试信息，而是用户应该知道的事实。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateBack: (() -> Unit)? = null,
    downloadDirProvider: (() -> File)? = null,
) {
    val context = LocalContext.current

    // 不再硬编码 "/sdcard/Android/data/<包名>/..."：
    // 写死绝对路径既会在包名变更时失效，也不符合 scoped storage 的推荐做法。
    // 用应用自己的外部私有目录，并回退到内部目录。
    val dirProvider = downloadDirProvider ?: remember(context) {
        {
            context.getExternalFilesDir("oneday")
                ?: File(context.filesDir, "oneday")
        }
    }

    val deps = remember { RecordingModule.newSession() }

    if (deps == null) {
        // 未调用 RecordingModule.initialize()：给出可读提示，而不是抛异常崩溃。
        NotInitializedScreen(onNavigateBack)
        return
    }

    val vm: RecordingViewModel = viewModel(
        factory = RecordingViewModel.factory(deps, dirProvider),
    )

    val recordingState by vm.recordingState.collectAsState()
    val previewState by vm.previewState.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val exportState by vm.exportState.collectAsState()
    val files by vm.files.collectAsState()
    val elapsedUs by vm.elapsedUs.collectAsState()
    val syncCount by vm.syncMarkerCount.collectAsState()
    val pendingMarkers by vm.pendingOriginalReadings.collectAsState()
    val works by vm.works.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录制与媒体") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            LivePreviewSurface(
                // 只有相机真正在用（用户已开启预览流）时才去 prepare。
                // 未连接时 InstaCapturePlayerView.prepare() 会抛
                // CameraNotConnectedException，而且**不会自动重试** ——
                // 所以必须在状态变化时重新尝试，否则连上相机后画面仍是黑的。
                previewActive = previewState !is PreviewStreamState.Idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            )

            Spacer(Modifier.height(12.dp))
            RecordingStatusCard(
                state = recordingState,
                elapsedUs = elapsedUs,
            )

            Spacer(Modifier.height(12.dp))
            RecordingControls(
                state = recordingState,
                onToggle = { vm.toggleRecording() },
                onReconcile = { vm.reconcile() },
                onClearError = { vm.clearRecordingError() },
            )

            Spacer(Modifier.height(12.dp))
            PreviewPipelineCard(
                state = previewState,
                onStart = { vm.startPreview() },
                onStop = { vm.stopPreview() },
            )

            Spacer(Modifier.height(12.dp))
            SyncMarkerCard(
                canMark = recordingState.isDeviceConfirmed,
                markerCount = syncCount,
                pendingCount = pendingMarkers,
                onMark = { vm.markSyncEvent("clap") },
                onReport = { vm.timelineReport() },
            )

            Spacer(Modifier.height(12.dp))
            FilesCard(
                files = files,
                downloadState = downloadState,
                onRefresh = { vm.refreshFiles() },
                onDownload = { vm.download(it) },
            )

            Spacer(Modifier.height(12.dp))
            ExportCard(
                state = exportState,
                works = works,
                onRefreshWorks = { vm.refreshWorks() },
                onExport = { work ->
                    // 导出的是用户点选的那个工程对象本身，
                    // 不再靠字符串猜（原来传空串会永远导出列表第一项）
                    vm.exportVideo(
                        work = work,
                        targetPath = dirProvider().resolve("export-${System.currentTimeMillis()}.mp4").absolutePath,
                    )
                },
                onCancel = { vm.cancelExport() },
                onReset = { vm.resetExport() },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 预览画面
// ---------------------------------------------------------------------------

/**
 * 实时预览画面。
 *
 * 用 SDK 的 [InstaCapturePlayerView] 承载：全景拼接是私有实现，
 * 自己拿 Surface 渲染是拿不到拼接结果的。
 *
 * ### 为什么不是「建好就 prepare」
 * `prepare()` 在相机未连接时会抛 `CameraNotConnectedException`，
 * 而且 SDK **不会自动重试**。早期实现只在 `AndroidView` 的 factory 里
 * prepare 一次，于是「先进录制页、后连相机」这条最常见的路径上，
 * 画面会一直是黑的（实测确认）。
 * 现在改为：factory 只建视图，`update` 里按需 prepare 并在失败后持续重试。
 *
 * 刻意没有调用 `setLifecycle` —— 那需要 `lifecycle-runtime-compose` 依赖，
 * 本模块没有引入。宿主要严格随页面生命周期暂停预览可自行补上。
 */
@Composable
private fun LivePreviewSurface(
    previewActive: Boolean,
    modifier: Modifier = Modifier,
) {
    // 持有 view 引用：离开页面时必须销毁，否则 SDK 播放器会继续占用
    // 相机预览资源（泄漏，且会让下次进入页面的预览异常）。
    val playerHolder = remember { mutableStateOf<InstaCapturePlayerView?>(null) }
    val prepared = remember { mutableStateOf(false) }
    val failure = remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { playerHolder.value?.destroy() }
                .onFailure { Timber.w(it, "InstaCapturePlayerView.destroy failed") }
            playerHolder.value = null
            prepared.value = false
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF1B1B1F), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 构造本身也可能抛（例如 SDK 尚未初始化）。之前只有 prepare 被
                // runCatching 包住，构造函数在外面 —— 一旦抛异常整个页面就崩。
                // 现在整体兜住，失败时退化为一个空容器，界面其余部分仍可用。
                runCatching { InstaCapturePlayerView(ctx) }
                    .onSuccess { playerHolder.value = it }
                    .getOrElse { error ->
                        Timber.w(error, "InstaCapturePlayerView construction failed; degraded")
                        failure.value = error.message ?: error.javaClass.simpleName
                        android.widget.FrameLayout(ctx)
                    }
            },
            update = { view ->
                if (view is InstaCapturePlayerView && previewActive && !prepared.value) {
                    runCatching { view.prepare(buildPreviewParams()) }
                        .onSuccess {
                            prepared.value = true
                            failure.value = null
                        }
                        .onFailure { error ->
                            // 相机还没连上属于正常情况，保持可读提示并等待下次重试
                            failure.value = error.message ?: error.javaClass.simpleName
                        }
                }
            },
        )

        if (!prepared.value) {
            Text(
                text = when {
                    !previewActive -> "相机未连接\n开启预览后可显示实时画面"
                    failure.value != null -> "预览不可用\n${failure.value}"
                    else -> "预览准备中…"
                },
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun buildPreviewParams(): PreviewParams = PreviewParams().apply {
    width = PREVIEW_WIDTH
    height = PREVIEW_HEIGHT
    fps = PREVIEW_FPS
}

/**
 * 模块未初始化时的降级页面。
 *
 * 之前这里会抛 IllegalStateException 直接崩溃；忘记调用
 * `RecordingModule.initialize()` 是很容易犯的错，不应该以崩溃收场。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotInitializedScreen(onNavigateBack: (() -> Unit)?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录制与媒体") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("录制模块尚未初始化", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "请在 Application 中先调用：\n" +
                    "RecordingModule.initialize(application, BuildConfig.DEBUG)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val PREVIEW_WIDTH = 1920
private const val PREVIEW_HEIGHT = 960
private const val PREVIEW_FPS = 30

// ---------------------------------------------------------------------------
// 录制状态
// ---------------------------------------------------------------------------

@Composable
private fun RecordingStatusCard(
    state: RecordingState,
    elapsedUs: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state is RecordingState.Error -> MaterialTheme.colorScheme.errorContainer
                state.isDeviceConfirmed -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            color = when {
                                state is RecordingState.Error -> MaterialTheme.colorScheme.error
                                state.isDeviceConfirmed -> Color(0xFFD32F2F)
                                state is RecordingState.Arming -> Color(0xFFFFA000)
                                else -> Color(0xFF9E9E9E)
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.userFacingLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                // 文档要求「设备已确认录制状态」才能说正在录制，这里如实区分
                text = state.detailLine(),
                style = MaterialTheme.typography.bodySmall,
            )

            if (state is RecordingState.Recording) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatDuration(elapsedUs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "命令→确认延迟 ${state.commandLatencyUs / 1000} ms" +
                        "  段 ${state.segmentId}" +
                        (state.deviceSubStatus?.let { "  子状态 $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (state is RecordingState.Error) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (state.wasDeviceConfirmed) {
                        "⚠ 出错前已在录制，原片可能不完整，请在相机上核实"
                    } else {
                        "出错前尚未开始录制，未产生素材"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 面向用户的措辞。Arming 绝不能说成「正在录制」。 */
private fun RecordingState.userFacingLabel(): String = when (this) {
    is RecordingState.Idle -> "未录制"
    is RecordingState.Arming -> "已下令，等待相机确认…"
    is RecordingState.Recording -> "正在录制（设备已确认）"
    is RecordingState.Stopping -> "正在停止，等待相机写文件…"
    is RecordingState.Error -> "录制异常"
}

private fun RecordingState.detailLine(): String = when (this) {
    is RecordingState.Idle -> "相机就绪后可开始录制"
    is RecordingState.Arming -> "命令已发出，但设备尚未确认。此时不能说“正在录制”。"
    is RecordingState.Recording -> "相机已确认进入录制状态，原片正在写入 SD 卡。"
    is RecordingState.Stopping -> "停录命令已发出，原片正在收尾。"
    is RecordingState.Error -> message
}

@Composable
private fun RecordingControls(
    state: RecordingState,
    onToggle: () -> Unit,
    onReconcile: () -> Unit,
    onClearError: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onToggle,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = if (state.isBusy) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (state.isBusy) "停止录制" else "开始录制")
        }
        OutlinedButton(onClick = onReconcile) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("对账")
        }
        if (state is RecordingState.Error) {
            OutlinedButton(onClick = onClearError) { Text("清除") }
        }
    }
}

// ---------------------------------------------------------------------------
// 预览管道诊断
// ---------------------------------------------------------------------------

@Composable
private fun PreviewPipelineCard(
    state: PreviewStreamState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("预览流（分析链路）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            when (state) {
                is PreviewStreamState.Idle ->
                    Text("未开启", style = MaterialTheme.typography.bodySmall)

                is PreviewStreamState.Opening ->
                    Text("正在打开流…", style = MaterialTheme.typography.bodySmall)

                is PreviewStreamState.Streaming -> {
                    val codec = when (state.codecKind) {
                        VideoCodecKind.H264 -> "H.264"
                        VideoCodecKind.HEVC -> "H.265"
                        VideoCodecKind.UNKNOWN -> "未识别"
                    }
                    Text(
                        "${state.width}×${state.height} @${state.fps}fps  编码 $codec",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.probingCodec) {
                        Text("· 正在探测编码格式（按机型/固件判断，不写死）", style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.awaitingKeyframe) {
                        Text("· 等待关键帧，已向相机请求 I 帧", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    val s = state.stats
                    Text(
                        "分片 ${s.fragmentsIn} → 帧 ${s.accessUnitsOut}" +
                            "（平均 ${"%.2f".format(s.avgFragmentsPerFrame)} 片/帧，" +
                            "最多 ${s.maxFragmentsPerFrame}）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "乱序分片 ${s.outOfOrderFragments} · 强制不完整 ${s.framesForcedIncomplete}" +
                            " · 已解码 ${state.decodedFrames}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (s.maxFragmentsPerFrame > 1) {
                        Text(
                            "· 确认相机确实在分片，合并是必需的（否则会绿屏）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                is PreviewStreamState.Failed ->
                    Text("失败：${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = state is PreviewStreamState.Idle) { Text("开启预览") }
                OutlinedButton(onClick = onStop, enabled = state !is PreviewStreamState.Idle) { Text("停止") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 打点
// ---------------------------------------------------------------------------

@Composable
private fun SyncMarkerCard(
    canMark: Boolean,
    markerCount: Int,
    pendingCount: Int,
    onMark: () -> Unit,
    onReport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("时间同步打点", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "拍手或闪灯后立刻点「打点」。系统记录手机时刻与预览流时间戳；" +
                    "这次动作在原片中的位置需要事后由分析链路补齐。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text("已打点 $markerCount 次，其中 $pendingCount 次尚未定位到原片", style = MaterialTheme.typography.labelSmall)
            if (pendingCount > 0) {
                Text(
                    "· 未定位的打点暂时无法用于事件跳转，这是如实状态而非故障",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMark, enabled = canMark) { Text("打点") }
                OutlinedButton(onClick = onReport) { Text("查看映射报告") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文件与下载
// ---------------------------------------------------------------------------

@Composable
private fun FilesCard(
    files: List<MediaFileItem>,
    downloadState: DownloadState,
    onRefresh: () -> Unit,
    onDownload: (MediaFileItem) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("相机素材", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))

            when (downloadState) {
                is DownloadState.Listing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("正在获取列表…", style = MaterialTheme.typography.bodySmall)
                }

                is DownloadState.Downloading -> {
                    val f = downloadState.fraction
                    Text("下载中 ${downloadState.fileUrl.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                        Text("${(f * 100).toInt()}%  (${downloadState.bytesReceived}/${downloadState.totalBytes} 字节)", style = MaterialTheme.typography.labelSmall)
                    } else {
                        // 总长度未知时不显示假进度
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("已接收 ${downloadState.bytesReceived} 字节（总长度未知）", style = MaterialTheme.typography.labelSmall)
                    }
                }

                is DownloadState.Done ->
                    Text("已下载：${downloadState.localPath}", style = MaterialTheme.typography.bodySmall)

                is DownloadState.Failed ->
                    Text("下载失败：${downloadState.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                is DownloadState.Idle ->
                    Text(
                        if (files.isEmpty()) "尚未获取素材列表" else "共 ${files.size} 个素材",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onRefresh) { Text("刷新列表") }

            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(150.dp)) {
                    items(files) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(onClick = { onDownload(item) }) {
                                Icon(Icons.Filled.Download, contentDescription = "下载")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

@Composable
private fun ExportCard(
    state: ExportState,
    works: List<WorkWrapper>,
    onRefreshWorks: () -> Unit,
    onExport: (WorkWrapper) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("导出", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            when (state) {
                is ExportState.Idle -> Text("未开始", style = MaterialTheme.typography.bodySmall)
                is ExportState.Preparing -> Text("准备中…", style = MaterialTheme.typography.bodySmall)
                is ExportState.Exporting -> {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("${(state.progress * 100).toInt()}% → ${state.targetPath}", style = MaterialTheme.typography.labelSmall)
                }
                is ExportState.Done ->
                    Text("导出完成：${state.outputPath}", style = MaterialTheme.typography.bodySmall)
                is ExportState.Cancelled -> Text("已取消", style = MaterialTheme.typography.bodySmall)
                is ExportState.Failed ->
                    Text("导出失败：${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefreshWorks,
                    enabled = state !is ExportState.Exporting,
                ) { Text("刷新工程") }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state is ExportState.Exporting,
                ) { Text("取消") }
                if (state is ExportState.Failed || state is ExportState.Cancelled) {
                    OutlinedButton(onClick = onReset) { Text("重置") }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (works.isEmpty()) {
                Text("暂无工程，点「刷新工程」拉取素材", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("选择要导出的工程：", style = MaterialTheme.typography.labelSmall)
                LazyColumn(modifier = Modifier.height(140.dp)) {
                    items(works) { work ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                work.toString(),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Button(
                                onClick = { onExport(work) },
                                enabled = state !is ExportState.Exporting && state !is ExportState.Preparing,
                            ) { Text("导出") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "只有 SDK 回调 onSuccess 才算导出完成 —— 命令发出不等于文件已生成。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

private fun formatDuration(us: Long): String {
    val totalSeconds = us / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (us % 1_000_000) / 100_000
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}
