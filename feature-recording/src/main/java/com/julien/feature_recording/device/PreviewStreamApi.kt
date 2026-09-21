package com.julien.feature_recording.device

import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import com.arashivision.sdk.camera.core.model.ConnectType
import timber.log.Timber

/** 预览帧的流类型（与 SDK 解耦）。 */
enum class PreviewFrameKind {
    VIDEO,
    VIDEO_L,
    VIDEO_R,
    AUDIO,
    GYRO,
    OTHER,
    UNKNOWN,
    ;

    val isVideo: Boolean
        get() = this == VIDEO || this == VIDEO_L || this == VIDEO_R
}

/** 预览流事件。所有回调来自 SDK 的后台线程。 */
interface DevicePreviewEvents {
    fun onOpening() = Unit
    fun onOpened() = Unit
    fun onIdle() = Unit

    fun onParamsChanged(width: Int, height: Int, fps: Int) = Unit

    /**
     * 收到一个编码**分片**（不是完整帧！）。
     * 合并由 core-media 的 FrameAssembler 按 timestampUs 完成。
     */
    fun onStreamData(data: ByteArray, timestampUs: Long, kind: PreviewFrameKind) = Unit

    fun onStreamError(error: Throwable) = Unit
}

/**
 * 预览流能力（窄接口），便于脱离真机测试。
 */
interface PreviewStreamApi {
    fun isConnected(): Boolean
    fun registerEvents(listener: DevicePreviewEvents)
    fun unregisterEvents(listener: DevicePreviewEvents)
    fun startStream()
    fun stopStream()

    /**
     * 请求一个 I 帧。
     *
     * 这是解码侧的救命接口：一旦我们因为队列溢出丢了帧、
     * 或者刚创建解码器，都必须请求关键帧才能恢复出正确画面。
     */
    fun requestKeyframe()
}

/** [PreviewStreamApi] 的 Insta360 实现。 */
class InstaPreviewStreamApi(
    private val camera: CameraDevice = CameraDevice.get(ConnectType.BLE),
) : PreviewStreamApi {

    private val listeners = mutableListOf<DevicePreviewEvents>()
    private var registered = false

    private val sdkListener = object : CameraStreamListener {

        override fun onOpening() {
            notify { it.onOpening() }
        }

        override fun onOpened() {
            notify { it.onOpened() }
        }

        override fun onIdle() {
            notify { it.onIdle() }
        }

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            notify {
                it.onParamsChanged(
                    width = paramsUpdate.previewWidth,
                    height = paramsUpdate.previewHeight,
                    fps = paramsUpdate.previewFps,
                )
            }
        }

        override fun onStreamDataNotify(streamData: PreviewStreamFrame) {
            val kind = streamData.type.toKind()
            val data = streamData.data
            val ts = streamData.timestamp
            // 逐个 try：一个监听者抛异常不应影响其他监听者（分析链路必须可失败）
            listeners.toList().forEach { listener ->
                runCatching { listener.onStreamData(data, ts, kind) }
                    .onFailure { Timber.w(it, "preview listener threw on stream data") }
            }
        }
    }

    private inline fun notify(action: (DevicePreviewEvents) -> Unit) {
        listeners.toList().forEach { listener ->
            runCatching { action(listener) }
                .onFailure { Timber.w(it, "preview listener threw") }
        }
    }

    override fun isConnected(): Boolean = runCatching { camera.isConnected() }.getOrDefault(false)

    override fun registerEvents(listener: DevicePreviewEvents) {
        listeners += listener
        if (!registered) {
            runCatching { camera.preview.registerCameraStreamListener(sdkListener) }
                .onSuccess { registered = true }
                .onFailure { Timber.e(it, "registerCameraStreamListener failed") }
        }
    }

    override fun unregisterEvents(listener: DevicePreviewEvents) {
        listeners -= listener
        if (listeners.isEmpty() && registered) {
            runCatching { camera.preview.unregisterCameraStreamListener(sdkListener) }
                .onFailure { Timber.w(it, "unregisterCameraStreamListener failed") }
            registered = false
        }
    }

    override fun startStream() {
        runCatching { camera.preview.startStream() }
            .onFailure { error ->
                Timber.e(error, "startStream failed")
                notify { it.onStreamError(error) }
            }
    }

    override fun stopStream() {
        runCatching { camera.preview.stopStream() }
            .onFailure { Timber.w(it, "stopStream failed") }
    }

    override fun requestKeyframe() {
        runCatching { camera.preview.requestStreamIframe() }
            .onFailure { Timber.w(it, "requestStreamIframe failed") }
    }

    /** 预览初始化需要 Application 实例，由 [com.julien.feature_recording.RecordingModule] 注入。 */
    fun initialize(application: android.app.Application) {
        runCatching { camera.preview.init(application) }
            .onFailure { Timber.w(it, "preview.init failed") }
    }

    /**
     * SDK 的流类型 → 本模块的流类型。
     *
     * 这里刻意**不写 else 分支**：`when` 对 SDK 枚举是穷尽的，一旦哪天 SDK
     * 新增一个流类型，编译器会直接报错提醒我们处理，而不是被 else 悄悄
     * 归到 UNKNOWN 里、让新数据在不知不觉中被丢掉。
     */
    private fun PreviewStreamType.toKind(): PreviewFrameKind = when (this) {
        PreviewStreamType.VIDEO -> PreviewFrameKind.VIDEO
        PreviewStreamType.VIDEO_L -> PreviewFrameKind.VIDEO_L
        PreviewStreamType.VIDEO_R -> PreviewFrameKind.VIDEO_R
        PreviewStreamType.AUDIO -> PreviewFrameKind.AUDIO
        PreviewStreamType.GYRO -> PreviewFrameKind.GYRO
        PreviewStreamType.OTHER -> PreviewFrameKind.OTHER
        PreviewStreamType.UNKNOWN -> PreviewFrameKind.UNKNOWN
    }
}
