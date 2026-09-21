package com.julien.feature_recording

import android.app.Application
import androidx.compose.runtime.Composable
import com.julien.core_media.time.RecordingTimeline
import com.julien.feature_recording.device.InstaMediaFileApi
import com.julien.feature_recording.device.InstaPreviewStreamApi
import com.julien.feature_recording.device.InstaRecordingDeviceApi
import com.julien.feature_recording.device.MediaFileApi
import com.julien.feature_recording.device.PreviewStreamApi
import com.julien.feature_recording.device.RecordingDeviceApi
import com.julien.feature_recording.download.MediaDownloader
import com.julien.feature_recording.export.InstaMediaWorkApi
import com.julien.feature_recording.export.MediaExporter
import com.julien.feature_recording.export.MediaWorkApi
import com.julien.feature_recording.player.PanoramaPlayerController
import com.julien.feature_recording.preview.DecodedFrameSink
import com.julien.feature_recording.preview.PreviewPipelineConfig
import com.julien.feature_recording.preview.PreviewStreamController
import com.julien.feature_recording.recording.RecordingController
import com.julien.feature_recording.ui.RecordingScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * 会话级依赖集合。
 *
 * 「会话」= 一次进入录制页到离开。时间轴必须是会话级的 ——
 * 因为每个原片文件的时间轴都从 0 开始（见 [RecordingTimeline] 的分段设计）。
 * 而设备/文件/工程 API 是无状态的门面，可以全局复用。
 */
class RecordingSessionDependencies(
    val timeline: RecordingTimeline,
    val deviceApi: RecordingDeviceApi,
    val previewApi: PreviewStreamApi,
    val fileApi: MediaFileApi,
    val workApi: MediaWorkApi,
) {
    fun createRecordingController(): RecordingController =
        RecordingController(device = deviceApi, timeline = timeline)

    fun createPreviewController(
        scope: CoroutineScope,
        config: PreviewPipelineConfig = PreviewPipelineConfig(),
        frameSink: DecodedFrameSink? = null,
    ): PreviewStreamController = PreviewStreamController(
        api = previewApi,
        scope = scope,
        config = config,
        frameSink = frameSink,
    )

    fun createDownloader(targetDirProvider: () -> File): MediaDownloader =
        MediaDownloader(api = fileApi, targetDirProvider = targetDirProvider)

    fun createExporter(): MediaExporter = MediaExporter(workApi)

    fun createPlayerController(): PanoramaPlayerController = PanoramaPlayerController()
}

/**
 * 录制与媒体模块的对外入口。
 *
 * 与 `feature-device` 的 `DeviceModule` 保持同样的形状：
 * 一个 `initialize()` + 一个 `getXxxScreen()`，避免宿主 App 直接依赖内部实现。
 *
 * 与 feature-device 的分工：
 * - feature-device 负责**连接**（扫描/配对/连接状态）；
 * - feature-recording 负责**连接之后的录制与媒体**。
 * 两者都通过 `CameraDevice.get(ConnectType.BLE)` 这个单例拿到同一个相机句柄，
 * 但互不引用 —— 这样录制链路不会被连接页的重构影响。
 */
object RecordingModule {

    private var application: Application? = null

    private val deviceApi: RecordingDeviceApi by lazy { InstaRecordingDeviceApi() }
    private val previewApi: InstaPreviewStreamApi by lazy { InstaPreviewStreamApi() }
    private val fileApi: MediaFileApi by lazy { InstaMediaFileApi() }
    private val workApi: MediaWorkApi by lazy { InstaMediaWorkApi() }

    val isInitialized: Boolean get() = application != null

    /**
     * 初始化。必须在 [newSession] 之前调用。
     *
     * 失败不抛异常（与 `DeviceModule.initialize` 一致的约定），
     * 由宿主决定是否提示。
     */
    fun initialize(application: Application, debug: Boolean): Result<Unit> = runCatching {
        this.application = application
        // 预览流需要 Application 才能初始化 SDK 侧解码/渲染
        previewApi.initialize(application)
    }
    /**
     * 开启一个新的录制会话。
     * 每次进入录制页都应调用，以获得干净的时间轴。
     *
     * **未初始化时返回 null 而不是抛异常**：调用方（通常是 Compose 页面）
     * 应当展示一条可读的提示，而不是让 App 直接崩溃。
     * 用 [isInitialized] 可以提前判断。
     */
    fun newSession(): RecordingSessionDependencies? {
        if (application == null) return null
        return RecordingSessionDependencies(
            timeline = RecordingTimeline(),
            deviceApi = deviceApi,
            previewApi = previewApi,
            fileApi = fileApi,
            workApi = workApi,
        )
    }

    /** 录制与媒体页面。 */
    @Composable
    fun getRecordingScreen(
        onNavigateBack: (() -> Unit)? = null,
        downloadDirProvider: (() -> File)? = null,
    ) {
        RecordingScreen(
            onNavigateBack = onNavigateBack,
            downloadDirProvider = downloadDirProvider,
        )
    }
}
