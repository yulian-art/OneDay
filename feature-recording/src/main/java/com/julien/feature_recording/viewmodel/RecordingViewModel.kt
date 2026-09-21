package com.julien.feature_recording.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.Micros
import com.julien.core_media.util.SystemMonotonicClock
import com.julien.feature_recording.RecordingSessionDependencies
import com.julien.feature_recording.model.DownloadState
import com.julien.feature_recording.model.ExportState
import com.julien.feature_recording.model.MediaFileItem
import com.julien.feature_recording.model.MediaFileKind
import com.julien.feature_recording.model.PanoramaFraming
import com.julien.feature_recording.model.PlayerState
import com.julien.feature_recording.model.PreviewStreamState
import com.julien.feature_recording.model.RecordingState
import com.julien.feature_recording.player.PanoramaPlayerController
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import timber.log.Timber

/**
 * 录制与媒体页的 ViewModel。
 *
 * 只做编排与状态汇聚，不含任何业务判断 ——
 * 判断都在 `recording/`、`preview/`、`download/`、`export/` 里，
 * 这样那些逻辑可以脱离 Android 单元测试。
 */
class RecordingViewModel(
    private val deps: RecordingSessionDependencies,
    private val downloadDirProvider: () -> File,
) : ViewModel() {

    private val recordingController = deps.createRecordingController()
    private val downloader = deps.createDownloader(downloadDirProvider)
    private val exporter = deps.createExporter()

    val playerController: PanoramaPlayerController = deps.createPlayerController()

    /** 最近一帧预览流时间戳（预览域）。用于打点时刻的读数。 */
    private val latestPreviewTsUs = AtomicLong(-1L)

    private val previewController = deps.createPreviewController(
        scope = viewModelScope,
        frameSink = { presentationTimeUs -> latestPreviewTsUs.set(presentationTimeUs) },
    )

    val recordingState: StateFlow<RecordingState> = recordingController.state
    val previewState: StateFlow<PreviewStreamState> = previewController.state
    val downloadState: StateFlow<DownloadState> = downloader.state
    val exportState: StateFlow<ExportState> = exporter.state
    val files: StateFlow<List<MediaFileItem>> = downloader.files
    val playerState: StateFlow<PlayerState> = playerController.state

    private val _elapsedUs = MutableStateFlow(0L)

    /** 以本机单调时钟计算的已录时长。 */
    val elapsedUs: StateFlow<Long> = _elapsedUs.asStateFlow()

    private val _syncMarkerCount = MutableStateFlow(0)

    /** 本次会话已登记的同步事件数。 */
    val syncMarkerCount: StateFlow<Int> = _syncMarkerCount.asStateFlow()

    private val _pendingOriginalReadings = MutableStateFlow(0)

    /** 已打点但还没能确定原片位置的同步事件数（提醒用户「这些点还没生效」）。 */
    val pendingOriginalReadings: StateFlow<Int> = _pendingOriginalReadings.asStateFlow()

    private val _works = MutableStateFlow<List<WorkWrapper>>(emptyList())

    /** 可导出的媒体工程列表。 */
    val works: StateFlow<List<WorkWrapper>> = _works.asStateFlow()

    private var tickJob: Job? = null

    init {
        recordingController.attach()
        startTicking()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val state = recordingController.state.value
                _elapsedUs.value = if (state is RecordingState.Recording) {
                    state.elapsedUs(SystemMonotonicClock.nowUs())
                } else {
                    0L
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------
    // 录制
    // ------------------------------------------------------------------

    /**
     * 切换录制。
     *
     * 注意 `Arming` 也算「已经在下令」，再次点击应当发停录，
     * 否则用户会以为点了没反应而反复点。
     */
    fun toggleRecording() {
        viewModelScope.launch {
            val state = recordingController.state.value
            if (state.isBusy) {
                recordingController.stop()
            } else {
                recordingController.start()
            }
            refreshPendingMarkers()
        }
    }

    /** 与相机对账，修复状态错配。建议在页面恢复时调用。 */
    fun reconcile() {
        viewModelScope.launch {
            recordingController.reconcile()
            refreshPendingMarkers()
        }
    }

    fun clearRecordingError() = recordingController.clearError()

    // ------------------------------------------------------------------
    // 预览（分析链路）
    // ------------------------------------------------------------------

    fun startPreview() = previewController.start()

    fun stopPreview() = previewController.stop()

    // ------------------------------------------------------------------
    // 打点（可见同步事件）
    // ------------------------------------------------------------------

    /**
     * 打一个可见同步事件点。
     *
     * 用户拍手/闪灯的瞬间：
     * - 我们立刻能拿到**手机单调时刻**；
     * - 也能拿到**最近一帧预览流时间戳**；
     * - 但**原片位置此刻无法知道** —— 要等分析链路在原片中看到这次拍手。
     *
     * 所以这里只登记两个域，并把它计入 [pendingOriginalReadings] 如实提示用户
     * 「这个点还没生效」。分析链路事后调用 [completeSyncMarkerOriginal] 补齐。
     *
     * @return 生成的事件 id；没有在录时返回 null
     */
    fun markSyncEvent(label: String = "clap"): String? {
        val state = recordingController.state.value
        if (state !is RecordingState.Recording) {
            Timber.w("markSyncEvent ignored: not recording")
            return null
        }
        val readings = buildMap<ClockDomain, Micros> {
            put(ClockDomain.PHONE_MONOTONIC, SystemMonotonicClock.nowUs())
            val previewTs = latestPreviewTsUs.get()
            if (previewTs >= 0) {
                put(ClockDomain.PREVIEW_STREAM, previewTs)
            }
        }
        if (readings.size < 2) {
            Timber.w("markSyncEvent: preview timestamp not available yet")
            return null
        }
        val id = "sync-${System.currentTimeMillis()}-$label"
        val ok = recordingController.recordSyncMarker(label = label, readings = readings, id = id)
        if (ok) {
            _syncMarkerCount.value = _syncMarkerCount.value + 1
            refreshPendingMarkers()
        }
        return id.takeIf { ok }
    }

    /**
     * 补齐同步事件的原片读数（由分析链路调用）。
     * 补齐后 preview→original 的映射才真正成立。
     */
    fun completeSyncMarkerOriginal(markerId: String, originalUs: Micros): Boolean {
        val ok = recordingController.completeSyncMarker(
            markerId = markerId,
            domain = ClockDomain.ORIGINAL_FILE,
            rawUs = originalUs,
        )
        refreshPendingMarkers()
        return ok
    }

    private fun refreshPendingMarkers() {
        _pendingOriginalReadings.value = recordingController.pendingSyncMarkerIds().size
    }

    /** 供诊断/验收导出：完整的时间映射报告。 */
    fun timelineReport(): String = deps.timeline.describe()

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    fun refreshFiles(kind: MediaFileKind = MediaFileKind.VIDEO) {
        viewModelScope.launch { downloader.listFiles(kind) }
    }

    fun download(item: MediaFileItem) {
        viewModelScope.launch { downloader.download(item) }
    }

    fun resetDownload() = downloader.reset()

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /**
     * 刷新可导出的媒体工程列表（相机上的 + 本地的）。
     */
    fun refreshWorks() {
        viewModelScope.launch {
            val local = runCatching { exporter.listLocalWorks() }.getOrDefault(emptyList())
            val remote = exporter.listCameraWorks().getOrDefault(emptyList())
            _works.value = (remote + local).distinct()
        }
    }

    /**
     * 导出指定的媒体工程。
     *
     * 直接接收 [WorkWrapper] 本身，而不是「拿一个 id 去猜」。
     * 之前的实现是 `listLocalWorks().firstOrNull { it.toString().contains(workId) }`，
     * 而 UI 传的是空串 —— 空串匹配一切，于是**永远导出列表里的第一个工程**，
     * 与用户点选的对象无关。现在由调用方把选中的对象传进来。
     *
     * 取景由调用方显式给出（文档「08」第 7 条：不确定时保留较宽视角）。
     */
    fun exportVideo(
        work: WorkWrapper,
        targetPath: String,
        framing: PanoramaFraming = PanoramaFraming(),
    ) {
        exporter.exportVideo(work = work, targetPath = targetPath, framing = framing)
    }

    fun cancelExport() = exporter.cancel()

    fun resetExport() = exporter.reset()

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        previewController.stop()
        recordingController.detach()
        playerController.release()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 200L

        fun factory(
            deps: RecordingSessionDependencies,
            downloadDirProvider: () -> File,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RecordingViewModel(deps, downloadDirProvider) as T
        }
    }
}
