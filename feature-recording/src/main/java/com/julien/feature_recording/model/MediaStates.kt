package com.julien.feature_recording.model

import com.julien.core_media.codec.VideoCodecKind
import com.julien.core_media.stream.StreamStats
import com.julien.core_media.model.Micros

/**
 * 预览流（分析链路）状态。
 *
 * 方案文档把「预览稳定获取」列为八大难点之首（分片、回调阻塞、坏帧），
 * 因此这里把「探测编码格式」「等待关键帧」都做成用户可见的状态 ——
 * 出问题时能立刻看出卡在哪一步。
 */
sealed interface PreviewStreamState {

    data object Idle : PreviewStreamState

    /** 已请求开流，等待相机打开。 */
    data object Opening : PreviewStreamState

    /**
     * 正在收流。
     *
     * @param probingCodec 还在探测编码格式（H.264 / H.265 由机型固件决定）
     * @param awaitingKeyframe 已建解码器但还没拿到关键帧，此时画面无法正确解出
     */
    data class Streaming(
        val width: Int,
        val height: Int,
        val fps: Int,
        val codecKind: VideoCodecKind,
        val probingCodec: Boolean,
        val awaitingKeyframe: Boolean,
        val stats: StreamStats,
        val decodedFrames: Long,
    ) : PreviewStreamState

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : PreviewStreamState
}

/** 相机媒体文件类型（与 SDK 的 MediaFileType 对应，但不依赖它）。 */
enum class MediaFileKind {
    VIDEO,
    PHOTO,
    VIDEO_AND_PHOTO,
    ;

    /** SDK 侧对应的枚举名。 */
    val sdkName: String
        get() = when (this) {
            VIDEO -> "VIDEO"
            PHOTO -> "PHOTO"
            VIDEO_AND_PHOTO -> "VIDEO_AND_PHOTO"
        }
}

/** 相机上的一个媒体文件。 */
data class MediaFileItem(
    val url: String,
    val name: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val kind: MediaFileKind = MediaFileKind.VIDEO,
)

/** 下载状态。 */
sealed interface DownloadState {

    data object Idle : DownloadState

    data object Listing : DownloadState

    data class Downloading(
        val fileUrl: String,
        val bytesReceived: Long,
        val totalBytes: Long,
    ) : DownloadState {
        /** 0f..1f；总长度未知时返回 null，避免显示假的进度条。 */
        val fraction: Float?
            get() = if (totalBytes > 0) {
                (bytesReceived.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class Done(
        val fileUrl: String,
        val localPath: String,
    ) : DownloadState

    data class Failed(
        val fileUrl: String,
        val message: String,
        val cause: Throwable? = null,
    ) : DownloadState
}

/** 导出状态。 */
sealed interface ExportState {

    data object Idle : ExportState

    data object Preparing : ExportState

    data class Exporting(
        val progress: Float,
        val targetPath: String,
    ) : ExportState

    data class Done(val outputPath: String) : ExportState

    data object Cancelled : ExportState

    data class Failed(val message: String, val cause: Throwable? = null) : ExportState
}

/** 播放器状态。 */
sealed interface PlayerState {

    data object Idle : PlayerState

    data object Preparing : PlayerState

    data class Ready(val durationMs: Long) : PlayerState

    data class Playing(val positionMs: Long, val durationMs: Long) : PlayerState

    data class Paused(val positionMs: Long) : PlayerState

    data object Ended : PlayerState

    data class Failed(val message: String) : PlayerState
}

/** 全景观看模式。对应 SDK 的 switchNormalMode / switchFisheyeMode / switchPerspectiveMode。 */
enum class PanoramaViewMode {
    /** 全景（小行星/等距柱状） */
    NORMAL,

    /** 鱼眼原始视图 */
    FISHEYE,

    /** 平面透视，导出普通视频用 */
    PERSPECTIVE,
}

/**
 * 导出时的取景参数。
 *
 * 方案文档「08」第 7 条提醒：「取景与证据冲突 —— 突出狗却裁掉主人手部」，
 * 因此这里强制要求显式给出 fov/朝向，而不是用 SDK 默认值悄悄裁掉画面。
 */
data class PanoramaFraming(
    val fov: Float = 90f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val distance: Float = 0f,
)

/** 一个待导出/待跳转的原片区间（已换算到原片时间轴）。 */
data class OriginalClip(
    val workId: String,
    val rangeUs: Micros,
    val framing: PanoramaFraming = PanoramaFraming(),
    /** 该区间的映射置信度，用于界面上如实提示「位置可能不准」。 */
    val confidence: com.julien.core_media.model.MappingConfidence =
        com.julien.core_media.model.MappingConfidence.UNKNOWN,
)
