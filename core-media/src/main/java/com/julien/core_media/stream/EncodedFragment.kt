package com.julien.core_media.stream

import com.julien.core_media.model.Micros

/**
 * 预览流的流类型。与 SDK 的 `PreviewStreamType` 一一对应，
 * 但 core-media 刻意不依赖 Insta360 SDK —— 这样这套分片合并/解码逻辑
 * 可以脱离真机用单元测试覆盖，也便于以后复用到别的相机。
 */
enum class MediaStreamType {
    /** 已拼接的整幅视频流，分析链路默认用这个。 */
    VIDEO,

    /** 左目鱼眼（未拼接）。 */
    VIDEO_L,

    /** 右目鱼眼（未拼接）。 */
    VIDEO_R,

    AUDIO,
    GYRO,
    OTHER,
    UNKNOWN,
    ;

    val isVideo: Boolean
        get() = this == VIDEO || this == VIDEO_L || this == VIDEO_R
}

/**
 * 一个编码帧**分片**。
 *
 * 关键事实（方案文档「05 影石SDK接入路径」）：
 * 「一个编码帧可能分多次回调，应按同一时间戳合并分片」。
 * 因此 SDK 每次 [com.arashivision.sdk.camera.api.preview.CameraStreamListener.onStreamDataNotify]
 * 给到的 `data` 并不保证是完整的一帧 —— 必须按 [timestampUs] 归组后才能送解码器。
 *
 * @param timestampUs 该分片所属编码帧的时间戳。**同一帧的所有分片共享同一个值**，
 *        这是合并的唯一依据。
 * @param receivedAtUs 本地收到该分片的单调时刻，仅用于统计抖动与超时判定，
 *        不参与时间映射（文档明确指出接收时间 ≠ 按下时间）。
 */
data class EncodedFragment(
    val data: ByteArray,
    val timestampUs: Micros,
    val type: MediaStreamType,
    val receivedAtUs: Micros,
) {
    val size: Int get() = data.size

    // ByteArray 的 equals/hashCode 是引用语义，data class 自动生成的版本会误导使用方，
    // 因此显式按内容实现，便于测试断言。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFragment) return false
        return timestampUs == other.timestampUs &&
            type == other.type &&
            receivedAtUs == other.receivedAtUs &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + receivedAtUs.hashCode()
        return result
    }
}

/**
 * 合并后的完整编码帧（一个 access unit），是可以安全送进 MediaCodec 的最小单位。
 *
 * @param fragmentCount 组成它的分片数。为 1 是正常情况（小帧或相机未分片）。
 * @param forcedIncomplete 是否因为超过分片上限或超时而被强制发出。
 *        这种帧有坏帧风险，解码侧应把它当作「可疑帧」对待。
 */
data class AssembledAccessUnit(
    val data: ByteArray,
    val timestampUs: Micros,
    val type: MediaStreamType,
    val fragmentCount: Int,
    val firstReceivedAtUs: Micros,
    val lastReceivedAtUs: Micros,
    val forcedIncomplete: Boolean = false,
) {
    val size: Int get() = data.size

    /** 分片在回调之间累积的耗时，用于观测回调是否被阻塞。 */
    val assemblyLatencyUs: Micros get() = lastReceivedAtUs - firstReceivedAtUs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssembledAccessUnit) return false
        return timestampUs == other.timestampUs &&
            type == other.type &&
            fragmentCount == other.fragmentCount &&
            firstReceivedAtUs == other.firstReceivedAtUs &&
            lastReceivedAtUs == other.lastReceivedAtUs &&
            forcedIncomplete == other.forcedIncomplete &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + fragmentCount
        result = 31 * result + firstReceivedAtUs.hashCode()
        result = 31 * result + lastReceivedAtUs.hashCode()
        result = 31 * result + forcedIncomplete.hashCode()
        return result
    }
}

/** 预览流健康度指标。方案文档把「预览稳定获取」列为八大难点之首，这些计数是其验收依据。 */
data class StreamStats(
    val fragmentsIn: Long = 0,
    val accessUnitsOut: Long = 0,
    val bytesIn: Long = 0,
    val framesForcedIncomplete: Long = 0,
    val duplicateTimestampFragments: Long = 0,
    val outOfOrderFragments: Long = 0,
    val maxFragmentsPerFrame: Int = 0,
    val maxAssemblyLatencyUs: Micros = 0,
) {
    /** 平均每帧分片数。明显 >1 说明相机确实在分片，合并逻辑是必需的。 */
    val avgFragmentsPerFrame: Double
        get() = if (accessUnitsOut == 0L) 0.0 else fragmentsIn.toDouble() / accessUnitsOut
}
