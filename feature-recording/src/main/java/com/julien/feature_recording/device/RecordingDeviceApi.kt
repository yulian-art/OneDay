package com.julien.feature_recording.device

import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.CaptureStatusListener
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.capture.CameraCaptureStatus
import timber.log.Timber

/**
 * 相机上报的录制事件。
 *
 * 刻意不用 SDK 的 [CaptureStatusListener] 类型，而是折成自己的窄接口：
 * 这样 [com.julien.feature_recording.recording.RecordingController] 的
 * 状态机可以在 JVM 单元测试里用假实现穷举验证，不需要真机。
 *
 * 所有回调都可能来自 SDK 的后台线程。
 */
interface DeviceCaptureEvents {
    fun onCaptureStarting() = Unit
    fun onCaptureWorking() = Unit
    fun onCaptureStopping() = Unit

    /** 录制结束。`fileUrls` 是相机给出的原片路径，可能为空。 */
    fun onCaptureFinished(fileUrls: List<String>) = Unit

    fun onCaptureError(error: Throwable) = Unit

    /** 相机自报已录时长。单位随机型/固件可能不同，原样透传。 */
    fun onElapsedChanged(raw: Long) = Unit

    fun onCountChanged(count: Int) = Unit

    fun onSubStatusChanged(subStatus: String) = Unit
}

/**
 * 录制链路所需的相机能力（窄接口）。
 *
 * 只包含「开始/停止录制 + 订阅状态」这三件事。
 * 预览、下载、导出各有自己的接口 —— 方案文档要求
 * 「录制链路独立保证原片保存与状态正确」，
 * 因此录制不应依赖预览或分析的任何东西。
 */
interface RecordingDeviceApi {

    fun isConnected(): Boolean

    /** 相机当前是否真的在录制/工作中。 */
    suspend fun isWorking(): Result<Boolean>

    suspend fun startCapture(): Result<Unit>

    suspend fun stopCapture(): Result<Unit>

    fun registerCaptureEvents(listener: DeviceCaptureEvents)

    fun unregisterCaptureEvents(listener: DeviceCaptureEvents)
}

/**
 * [RecordingDeviceApi] 的 Insta360 实现。
 *
 * 这一层是唯一接触 Insta360 SDK 的地方，故意做得很薄：
 * 所有判断逻辑都在 [com.julien.feature_recording.recording.RecordingController] 里，
 * 这样业务逻辑可以脱离真机测试。
 */
class InstaRecordingDeviceApi(
    private val camera: CameraDevice = CameraDevice.get(ConnectType.BLE),
) : RecordingDeviceApi {

    private val listeners = mutableListOf<DeviceCaptureEvents>()
    private var sdkListenerRegistered = false

    /**
     * SDK 的 [CaptureStatusListener] 适配器。
     * 8 个回调全部实现，**一个都不能漏** ——
     * 漏掉 onCaptureError 会让失败静默（方案文档特别反对这种做法）。
     */
    private val sdkListener = object : CaptureStatusListener {
        override fun onCaptureStarting(functionMode: FunctionMode) {
            notify { it.onCaptureStarting() }
        }

        override fun onCaptureWorking(functionMode: FunctionMode) {
            notify { it.onCaptureWorking() }
        }

        override fun onCaptureStopping(functionMode: FunctionMode) {
            notify { it.onCaptureStopping() }
        }

        override fun onCaptureFinish(functionMode: FunctionMode, filePaths: List<String>) {
            notify { it.onCaptureFinished(filePaths) }
        }

        override fun onCaptureError(functionMode: FunctionMode, throwable: Throwable) {
            Timber.e(throwable, "camera reported capture error, mode=%s", functionMode)
            notify { it.onCaptureError(throwable) }
        }

        override fun onCaptureTimeChanged(functionMode: FunctionMode, captureTime: Long) {
            notify { it.onElapsedChanged(captureTime) }
        }

        override fun onCaptureCountChanged(functionMode: FunctionMode, captureCount: Int) {
            notify { it.onCountChanged(captureCount) }
        }

        override fun onCaptureSubStatusChanged(
            functionMode: FunctionMode,
            subStatus: CameraCaptureStatus.SubStatus,
        ) {
            notify { it.onSubStatusChanged(subStatus.name) }
        }
    }

    private inline fun notify(action: (DeviceCaptureEvents) -> Unit) {
        // 拷贝一份再遍历：回调里可能会注销自己
        listeners.toList().forEach { listener ->
            runCatching { action(listener) }
                .onFailure { Timber.w(it, "capture event listener threw") }
        }
    }

    override fun isConnected(): Boolean = runCatching { camera.isConnected() }.getOrDefault(false)

    override suspend fun isWorking(): Result<Boolean> = runCatching { camera.capture.isWorking() }

    override suspend fun startCapture(): Result<Unit> = runCatching {
        camera.capture.startCapture()
    }.onFailure { Timber.e(it, "startCapture failed") }

    override suspend fun stopCapture(): Result<Unit> = runCatching {
        camera.capture.stopCapture()
    }.onFailure { Timber.e(it, "stopCapture failed") }

    override fun registerCaptureEvents(listener: DeviceCaptureEvents) {
        listeners += listener
        if (!sdkListenerRegistered) {
            runCatching { camera.capture.registerCaptureStatusListener(sdkListener) }
                .onSuccess { sdkListenerRegistered = true }
                .onFailure { Timber.e(it, "registerCaptureStatusListener failed") }
        }
    }

    override fun unregisterCaptureEvents(listener: DeviceCaptureEvents) {
        listeners -= listener
        if (listeners.isEmpty() && sdkListenerRegistered) {
            runCatching { camera.capture.unregisterCaptureStatusListener(sdkListener) }
                .onFailure { Timber.w(it, "unregisterCaptureStatusListener failed") }
            sdkListenerRegistered = false
        }
    }
}
