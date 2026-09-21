package com.julien.feature_recording.export

import com.arashivision.sdk.media.api.export.ExporterManager
import com.arashivision.sdk.media.api.export.IExportCallback
import com.arashivision.sdk.media.api.params.ImageExportParams
import com.arashivision.sdk.media.api.params.VideoExportParams
import com.arashivision.sdk.media.api.work.WorkManager
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.julien.feature_recording.model.ExportState
import com.julien.feature_recording.model.PanoramaFraming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 媒体工程（Work）能力。
 *
 * 导出与播放都以 [WorkWrapper] 为单位。
 */
interface MediaWorkApi {
    /** 相机上的媒体工程（需要连接）。 */
    suspend fun listCameraWorks(): Result<List<WorkWrapper>>

    /** 本地已有的媒体工程。 */
    fun listLocalWorks(): List<WorkWrapper>
}

/** [MediaWorkApi] 的 Insta360 实现。 */
class InstaMediaWorkApi : MediaWorkApi {

    override suspend fun listCameraWorks(): Result<List<WorkWrapper>> =
        runCatching { WorkManager.getAllCameraWorks().getOrThrow() }
            .onFailure { Timber.e(it, "listCameraWorks failed") }

    override fun listLocalWorks(): List<WorkWrapper> =
        runCatching { WorkManager.getAllLocalWorks() }
            .onFailure { Timber.e(it, "listLocalWorks failed") }
            .getOrDefault(emptyList())
}

/**
 * 全景导出与按时间戳抽帧。
 *
 * ### 两条导出路径
 * 1. **视频导出** —— `ExporterManager.exportVideo(VideoExportParams)`。
 *    取景（fov/yaw/pitch/distance）在这里显式给定：文档「08」第 7 条要求
 *    「按阶段构图，不确定时保留较宽视角」，所以取景参数由调用方决定，
 *    不用 SDK 默认值偷偷裁掉画面。
 *
 * 2. **按时间戳抽帧** —— `ExporterManager.exportVideoToImage(ImageExportParams)`，
 *    配合 `timestampList`（文档「05」点名的能力）。
 *    这是「关键阶段证据」的来源：候选事件需要一张可核查的画面来支撑判断，
 *    而抽帧比导一整段视频快得多。
 *
 * ### 状态语义
 * 与录制一致：只有 [IExportCallback.onSuccess] 才算「导出完成」。
 * 文档明确：「模型返回成功，也不等于视频导出成功」，
 * 所以绝不把「命令已发出」当作完成。
 */
class MediaExporter(
    private val workApi: MediaWorkApi,
) {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /** 当前导出任务 id（由 SDK 在 onStart 中给出），用于取消。 */
    private var activeExportId: Int? = null

    suspend fun listCameraWorks(): Result<List<WorkWrapper>> = workApi.listCameraWorks()

    fun listLocalWorks(): List<WorkWrapper> = workApi.listLocalWorks()

    /**
     * 导出一段全景视频。
     *
     * @param framing 取景。文档要求不确定时保留较宽视角，故默认 fov 较宽。
     * @param width 输出宽度，0 表示交给 SDK 决定。
     */
    fun exportVideo(
        work: WorkWrapper,
        targetPath: String,
        framing: PanoramaFraming = PanoramaFraming(),
        width: Int = 0,
        height: Int = 0,
        bitrate: Int = 0,
        fps: Int = 0,
    ) {
        if (_state.value is ExportState.Exporting) {
            Timber.w("export already in progress, ignoring new request")
            return
        }
        _state.value = ExportState.Preparing

        val params = VideoExportParams(work).apply {
            this.targetPath = targetPath
            if (width > 0) this.width = width
            if (height > 0) this.height = height
            if (bitrate > 0) this.bitrate = bitrate
            if (fps > 0) this.fps = fps
            // 显式取景，避免默认裁切把关键部位切掉
            this.fov = framing.fov
            this.yaw = framing.yaw
            this.pitch = framing.pitch
            this.distance = framing.distance
        }
        lastTargetPath = targetPath

        runCatching {
            ExporterManager.exportVideo(params, callback)
        }.onFailure { error ->
            Timber.e(error, "exportVideo threw synchronously")
            _state.value = ExportState.Failed(
                message = "导出启动失败: ${error.message ?: error.javaClass.simpleName}",
                cause = error,
            )
        }
    }

    /**
     * 按时间戳抽帧。
     *
     * @param timestampsSec 时间戳列表，单位**秒**（SDK 的 timestampList 是 List<Double>）。
     * @param index 要抽取的序号，对应 timestampsSec 的下标。
     */
    fun extractFrame(
        work: WorkWrapper,
        timestampsSec: List<Double>,
        index: Int,
        targetPath: String,
        framing: PanoramaFraming = PanoramaFraming(),
        width: Int = 0,
        height: Int = 0,
    ) {
        if (timestampsSec.isEmpty()) {
            _state.value = ExportState.Failed("时间戳列表为空，无法抽帧")
            return
        }
        if (index !in timestampsSec.indices) {
            _state.value = ExportState.Failed("抽帧序号 $index 超出范围 0..${timestampsSec.lastIndex}")
            return
        }

        _state.value = ExportState.Preparing
        val params = ImageExportParams(work).apply {
            this.targetPath = targetPath
            this.timestampList = timestampsSec
            this.index = index
            if (width > 0) this.width = width
            if (height > 0) this.height = height
            this.fov = framing.fov
            this.yaw = framing.yaw
            this.pitch = framing.pitch
            this.distance = framing.distance
        }
        lastTargetPath = targetPath

        runCatching {
            ExporterManager.exportVideoToImage(params, callback)
        }.onFailure { error ->
            Timber.e(error, "exportVideoToImage threw synchronously")
            _state.value = ExportState.Failed(
                message = "抽帧启动失败: ${error.message ?: error.javaClass.simpleName}",
                cause = error,
            )
        }
    }

    /** 取消当前导出。 */
    fun cancel() {
        val id = activeExportId
        if (id == null) {
            Timber.w("cancel requested but no active export id")
            return
        }
        runCatching { ExporterManager.stopExport(id) }
            .onFailure { Timber.w(it, "stopExport failed") }
    }

    private val callback = object : IExportCallback {

        override fun onStart(id: Int) {
            activeExportId = id
            Timber.i("export started, id=%d", id)
            _state.value = ExportState.Exporting(
                progress = 0f,
                targetPath = currentTargetPath(),
            )
        }

        override fun onProgress(progress: Float) {
            _state.value = ExportState.Exporting(
                progress = progress.coerceIn(0f, 1f),
                targetPath = currentTargetPath(),
            )
        }

        override fun onSuccess() {
            // 只有这里才算「导出完成」—— 文档：模型返回成功 ≠ 视频导出成功
            val path = currentTargetPath()
            activeExportId = null
            _state.value = ExportState.Done(path)
            Timber.i("export succeeded: %s", path)
        }

        override fun onFail(throwable: Throwable) {
            activeExportId = null
            _state.value = ExportState.Failed(
                message = "导出失败: ${throwable.message ?: throwable.javaClass.simpleName}",
                cause = throwable,
            )
            Timber.e(throwable, "export failed")
        }

        override fun onCancel() {
            activeExportId = null
            _state.value = ExportState.Cancelled
            Timber.i("export cancelled")
        }
    }

    private var lastTargetPath: String = ""

    private fun currentTargetPath(): String = lastTargetPath

    fun reset() {
        _state.value = ExportState.Idle
        activeExportId = null
    }
}
