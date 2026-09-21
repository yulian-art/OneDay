package com.julien.core_media.codec

/**
 * 编码格式。
 *
 * 方案文档「05」：「编码格式也需按实际机型、固件判断」——
 * 也就是说不能把 H.264 写死，必须能从码流里认出来。
 */
enum class VideoCodecKind(val mime: String) {
    H264("video/avc"),
    HEVC("video/hevc"),
    UNKNOWN(""),
}

/** 码流的封装形态。Annex-B 带起始码；AVCC/HVCC 用 4 字节长度前缀。 */
enum class BitstreamPacking {
    /** `00 00 00 01` / `00 00 01` 起始码。相机预览流通常是这种。 */
    ANNEX_B,

    /** 4 字节大端长度前缀（mp4 / MediaCodec 输出常见）。 */
    LENGTH_PREFIXED,
}

/**
 * 一次码流探测的结果。
 *
 * @param confidence 0..1。仅凭单帧猜编码格式并不可靠，
 *        上层应把多帧的探测结果投票后再 [VideoCodecFormat.decide]。
 */
data class CodecDetection(
    val kind: VideoCodecKind,
    val packing: BitstreamPacking,
    val naluTypes: List<Int>,
    val hasSps: Boolean,
    val hasPps: Boolean,
    val hasVps: Boolean,
    val isKeyframe: Boolean,
    val confidence: Double,
)

/**
 * 从裸码流识别编码格式与关键帧。
 *
 * ### 为什么需要它
 * 相机在不同机型/固件下可能给 H.264 或 H.265，而 MediaCodec 必须用正确的
 * mime 创建解码器，否则会直接报错或输出花屏。文档要求「按实际机型、固件判断」，
 * 所以这里做**运行时探测**，而不是写死一个 mime。
 *
 * ### 判别思路
 * H.264 与 H.265 的 NAL 头都是前缀码，但位宽不同：
 * - H.264：1 字节头，`type = b0 and 0x1F`，SPS=7、PPS=8、IDR=5
 * - H.265：2 字节头，`type = (b0 ushr 1) and 0x3F`，VPS=32、SPS=33、PPS=34、IDR=19/20
 *
 * 对同一段字节按两种解释分别打分，取分高者。参数集（SPS/PPS/VPS）出现时权重最高，
 * 因为那是**确定性证据**而非猜测。
 */
object VideoCodecFormat {

    private const val H264_SPS = 7
    private const val H264_PPS = 8
    private const val H264_IDR = 5
    private const val H264_NON_IDR = 1

    private const val HEVC_VPS = 32
    private const val HEVC_SPS = 33
    private const val HEVC_PPS = 34
    private const val HEVC_IDR_W_RADL = 19
    private const val HEVC_IDR_N_LP = 20

    /** 探测单帧/单段码流。 */
    fun detect(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): CodecDetection {
        val packing = if (hasAnnexBStartCode(data, offset, length)) {
            BitstreamPacking.ANNEX_B
        } else {
            BitstreamPacking.LENGTH_PREFIXED
        }
        val headers = if (packing == BitstreamPacking.ANNEX_B) {
            annexBNalHeaders(data, offset, length)
        } else {
            lengthPrefixedNalHeaders(data, offset, length)
        }

        var h264Score = 0
        var hevcScore = 0
        var hasSps = false
        var hasPps = false
        var hasVps = false
        var isKeyframe = false
        val types = ArrayList<Int>(headers.size)

        for (h in headers) {
            val b0 = h.first
            val h264Type = b0.toInt() and 0x1F
            val hevcType = (b0.toInt() ushr 1) and 0x3F
            types += h264Type

            when (h264Type) {
                H264_SPS -> { h264Score += 4; hasSps = true }
                H264_PPS -> { h264Score += 4; hasPps = true }
                H264_IDR -> { h264Score += 3; isKeyframe = true }
                H264_NON_IDR -> h264Score += 1
                6 -> h264Score += 1 // SEI
            }
            when (hevcType) {
                HEVC_VPS -> { hevcScore += 5; hasVps = true }
                HEVC_SPS -> { hevcScore += 4; hasSps = true }
                HEVC_PPS -> { hevcScore += 4; hasPps = true }
                HEVC_IDR_W_RADL, HEVC_IDR_N_LP -> { hevcScore += 3; isKeyframe = true }
                1 -> hevcScore += 1 // TRAIL_R
            }
        }

        val kind: VideoCodecKind
        val confidence: Double
        when {
            hevcScore > h264Score && hasVps -> {
                // VPS 只有 HEVC 才有 —— 这是最硬的证据
                kind = VideoCodecKind.HEVC
                confidence = 0.99
            }
            hevcScore > h264Score && hevcScore >= 8 -> {
                kind = VideoCodecKind.HEVC
                confidence = 0.8
            }
            h264Score > hevcScore && hasSps && hasPps -> {
                kind = VideoCodecKind.H264
                confidence = 0.95
            }
            h264Score > hevcScore && h264Score >= 3 -> {
                kind = VideoCodecKind.H264
                confidence = 0.7
            }
            else -> {
                kind = VideoCodecKind.UNKNOWN
                confidence = 0.0
            }
        }

        return CodecDetection(
            kind = kind,
            packing = packing,
            naluTypes = types,
            hasSps = hasSps,
            hasPps = hasPps,
            hasVps = hasVps,
            isKeyframe = isKeyframe,
            confidence = confidence,
        )
    }

    /**
     * 多帧投票，显著提升可靠性。
     * 上层应把预览流前若干帧都喂进来，而不是只探测第一帧。
     */
    fun decide(detections: List<CodecDetection>): VideoCodecKind {
        if (detections.isEmpty()) return VideoCodecKind.UNKNOWN
        val votes = detections
            .filter { it.kind != VideoCodecKind.UNKNOWN }
            .groupBy { it.kind }
            .mapValues { (_, list) -> list.sumOf { it.confidence } }
        return votes.maxByOrNull { it.value }?.key ?: VideoCodecKind.UNKNOWN
    }

    /**
     * 判断一个访问单元是否含有关键帧（I 帧）。
     *
     * 这是解码侧的**重整旗鼓**依据：一旦因为队列溢出丢了帧，
     * 后续帧必须等到关键帧才能重新解码，否则必然花屏。
     */
    fun isKeyframe(data: ByteArray, kind: VideoCodecKind): Boolean = when (kind) {
        VideoCodecKind.H264 -> detect(data).let { it.isKeyframe || it.hasSps }
        VideoCodecKind.HEVC -> detect(data).let { it.isKeyframe || (it.hasVps && it.hasSps) }
        VideoCodecKind.UNKNOWN -> false
    }

    // ------------------------------------------------------------------
    // NAL 解析
    // ------------------------------------------------------------------

    private fun hasAnnexBStartCode(data: ByteArray, offset: Int, length: Int): Boolean {
        val end = offset + length
        var i = offset
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() ||
                    (data[i + 2] == 0.toByte() && i + 3 < end && data[i + 3] == 1.toByte()))
            ) {
                return true
            }
            // 只看开头一小段即可判定，避免在纯数据里误命中
            if (i - offset > 32) return false
            i++
        }
        return false
    }

    /** 返回每个 NAL 单元的头字节（Annex-B 形态）。 */
    private fun annexBNalHeaders(data: ByteArray, offset: Int, length: Int): List<Pair<Byte, Int>> {
        val end = offset + length
        val starts = ArrayList<Int>()
        var i = offset
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i + 3
                    i += 3
                    continue
                }
                if (i + 3 < end && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapNotNull { s ->
            if (s < end) data[s] to (end - s) else null
        }
    }

    /** 返回每个 NAL 单元的头字节（长度前缀形态）。 */
    private fun lengthPrefixedNalHeaders(data: ByteArray, offset: Int, length: Int): List<Pair<Byte, Int>> {
        val out = ArrayList<Pair<Byte, Int>>()
        var i = offset
        val end = offset + length
        var guard = 0
        while (i + 4 <= end && guard++ < 256) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            if (len <= 0 || i + 4 + len > end) break
            out += data[i + 4] to len
            i += 4 + len
        }
        return out
    }
}
