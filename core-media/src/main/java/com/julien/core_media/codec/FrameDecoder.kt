package com.julien.core_media.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.julien.core_media.stream.AssembledAccessUnit
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * 解码器创建参数。
 *
 * @param csd0 SPS（H.264）或 VPS+SPS（HEVC）字节，用于带内参数集缺失时手动注入。
 * @param csd1 PPS。
 */
data class DecoderConfig(
    val kind: VideoCodecKind,
    val width: Int,
    val height: Int,
    val frameRate: Int = 30,
    val csd0: ByteArray? = null,
    val csd1: ByteArray? = null,
) {
    val mime: String get() = kind.mime
}

/** 解码器运行统计。用于验收「预览稳定获取」。 */
data class DecodeStats(
    val framesSubmitted: Long = 0,
    val framesDecoded: Long = 0,
    val framesDroppedAwaitingKeyframe: Long = 0,
    val framesEvicted: Long = 0,
    val inputQueueDepth: Int = 0,
    val lastPresentationTimeUs: Long = 0,
    /** 被识别为编码器配置（SPS/PPS）而非图像帧、因而未上报的输出缓冲数。 */
    val configBuffersSkipped: Long = 0,
)

/** 解码输出回调。 */
interface DecodeListener {
    /**
     * 解码出一帧。
     * @param presentationTimeUs 原样透传预览流时间戳，供时间映射使用。
     */
    fun onFrameDecoded(presentationTimeUs: Long, renderTimeUs: Long) {}

    /** 解码器输出格式发生变化（分辨率/色彩）。 */
    fun onOutputFormatChanged(format: MediaFormat) {}

    /** 解码出错。**分析链路失败不允许影响录制链路**，调用方应只做降级处理。 */
    fun onDecodeError(throwable: Throwable) {}
}

/**
 * 预览流解码器。
 *
 * ### 职责边界
 * 只做三件事：
 * 1. 用 [DecoderAdmissionPolicy] 做有界准入（丢整帧 + 关键帧重同步）；
 * 2. 把完整编码帧喂给 MediaCodec；
 * 3. 把输出的 `presentationTimeUs` **原样**回调出去。
 *
 * 第 3 点很重要：方案文档要求「解码、保留时间信息」，
 * 且事件跳转依赖预览时间→原片时间的映射。若在这里改写时间戳，映射就断了。
 *
 * ### 与录制链路的关系
 * 本类属于**分析链路**。方案文档明确：
 * 「分析失败不应影响原始记录」。因此这里任何异常都被吞掉并上报
 * [DecodeListener.onDecodeError]，绝不向上抛到录制流程。
 *
 * @param surface 渲染目标。传 null 表示只解码不渲染（例如仅做统计），
 *        但那样拿不到像素数据；端侧检测需要传 Surface（来自 ImageReader 或 TextureView）。
 */
class FrameDecoder(
    private val config: DecoderConfig,
    private val surface: Surface?,
    private val listener: DecodeListener,
    private val policy: DecoderAdmissionPolicy = DecoderAdmissionPolicy(),
) {

    private var codec: MediaCodec? = null
    private val submitted = AtomicLong()
    private val decoded = AtomicLong()
    private val configBuffersSkipped = AtomicLong()
    private var lastPresentationTimeUs = 0L

    /**
     * 已由 MediaCodec 交出、但还没有数据可填的输入缓冲索引。
     *
     * 关键设计：**没有数据时就把索引存在这里，而不是向 codec 灌 0 字节空缓冲。**
     * 灌空缓冲会产生 PTS=0 的伪输出，并且掩盖真正的丢帧问题。
     */
    private val availableInputBuffers = ArrayDeque<Int>()

    /**
     * 保护 [availableInputBuffers] 与 [policy] 的配对顺序。
     * `submit()` 来自预览回调线程，`onInputBufferAvailable` 来自 codec 的 handler 线程，
     * 两者都会驱动配对，必须串行化。
     */
    private val feedLock = Any()

    /** 由 [VideoCodecFormat] 探测得到的编码格式；构造 [DecoderConfig] 时可能还是 UNKNOWN。 */
    private var codecKind: VideoCodecKind = config.kind

    @Volatile
    var isRunning: Boolean = false
        private set

    val stats: DecodeStats
        get() = DecodeStats(
            framesSubmitted = submitted.get(),
            framesDecoded = decoded.get(),
            framesDroppedAwaitingKeyframe = policy.droppedAwaitingKeyframe,
            framesEvicted = policy.evictedCount,
            inputQueueDepth = policy.size,
            lastPresentationTimeUs = lastPresentationTimeUs,
            configBuffersSkipped = configBuffersSkipped.get(),
        )

    /** 启动解码器。失败返回 Result，不抛异常（分析链路必须可失败）。 */
    fun start(): Result<Unit> = runCatching {
        if (isRunning) return@runCatching
        val mime = if (codecKind == VideoCodecKind.UNKNOWN) {
            throw IllegalStateException("codec kind unknown; detect from bitstream first")
        } else {
            codecKind.mime
        }

        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            // 低延迟：预览分析不需要 B 帧重排序缓冲
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            config.csd0?.let { setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
            config.csd1?.let { setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }
        }

        val c = MediaCodec.createDecoderByType(mime)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                synchronized(feedLock) {
                    availableInputBuffers.addLast(index)
                    drainLocked(mc)
                }
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                try {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    // 编码器配置（SPS/PPS）与空缓冲都**不是图像帧**。
                    // 之前对每个输出缓冲都计数并回调，会让分析链路收到 PTS=0 的伪帧；
                    // 经时间映射后它指向原片开头，可能凭空生成一个不存在的候选事件。
                    if (isConfig || isEos || info.size == 0) {
                        if (isConfig) configBuffersSkipped.incrementAndGet()
                        mc.releaseOutputBuffer(index, false)
                        return
                    }

                    decoded.incrementAndGet()
                    lastPresentationTimeUs = info.presentationTimeUs
                    // render = true 才真正上屏；surface 为 null 时也必须 release
                    mc.releaseOutputBuffer(index, surface != null)
                    listener.onFrameDecoded(info.presentationTimeUs, System.nanoTime() / 1000)
                } catch (e: Exception) {
                    listener.onDecodeError(e)
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Timber.e(e, "FrameDecoder: codec error")
                listener.onDecodeError(e)
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                listener.onOutputFormatChanged(format)
            }
        })

        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        isRunning = true
        policy.resetForNewStream()
        Timber.d("FrameDecoder started: mime=%s %dx%d", mime, config.width, config.height)
    }.onFailure {
        Timber.e(it, "FrameDecoder.start failed")
        listener.onDecodeError(it)
    }

    /**
     * 送入一个**已合并完整**的编码帧。
     *
     * 注意：调用方必须传 [AssembledAccessUnit]，而不是分片。
     * 直接把分片喂进来正是文档警告的绿屏成因。
     */
    fun submit(unit: AssembledAccessUnit) {
        if (!isRunning) return
        val key = VideoCodecFormat.isKeyframe(unit.data, codecKind)
        val decision = policy.admit(unit, key)
        submitted.incrementAndGet()
        if (!decision.accepted && decision.evicted == null) {
            // 恢复期丢帧属于预期行为，用 debug 级别，避免刷屏
            Timber.v("FrameDecoder: drop ts=%d (%s)", unit.timestampUs, decision.reason)
        }
        // 帧已进入 policy 队列；若此刻恰好有空闲输入缓冲就立刻配对送出
        val mc = codec ?: return
        synchronized(feedLock) { drainLocked(mc) }
    }

    /**
     * 把「可用的输入缓冲」与「待解码的帧」两两配对。调用方必须持有 [feedLock]。
     *
     * 两侧任一方缺货就停下等待，**不灌空缓冲**。
     * 这是对原先 `feedNext` 的修正：原来在没有数据时
     * `queueInputBuffer(index, 0, 0, 0L, 0)` 会持续向解码器灌 PTS=0 的空帧，
     * 并且 `policy.poll()` 之后若 `getInputBuffer` 失败，那一帧就被永久丢弃。
     */
    private fun drainLocked(mc: MediaCodec) {
        while (availableInputBuffers.isNotEmpty()) {
            val unit = policy.poll() ?: break
            val index = availableInputBuffers.removeFirst()
            if (!feed(mc, index, unit)) {
                // 喂不进去：缓冲与帧原样放回，等下一次机会。绝不静默丢帧。
                policy.pushFront(unit)
                availableInputBuffers.addFirst(index)
                break
            }
        }
    }

    /** 把一帧写入指定输入缓冲。返回 false 表示本次没写成功（调用方需回滚）。 */
    private fun feed(mc: MediaCodec, index: Int, unit: AssembledAccessUnit): Boolean {
        return try {
            val buffer = mc.getInputBuffer(index)
            if (buffer == null) {
                Timber.w("FrameDecoder: input buffer %d unavailable, frame ts=%d held", index, unit.timestampUs)
                return false
            }
            buffer.clear()
            val data = unit.data
            val n = minOf(buffer.capacity(), data.size)
            buffer.put(data, 0, n)
            // 时间戳原样透传：这是跨时钟映射的锚
            mc.queueInputBuffer(index, 0, n, unit.timestampUs, 0)
            if (n < data.size) {
                Timber.w(
                    "FrameDecoder: frame ts=%d truncated %d->%d bytes (input buffer too small)",
                    unit.timestampUs,
                    data.size,
                    n,
                )
            }
            true
        } catch (e: Exception) {
            listener.onDecodeError(e)
            false
        }
    }

    /**
     * 当上层从码流中探测出真实编码格式后调用。
     * 若与当前不一致，会重启解码器 —— 这就是「按实际机型、固件判断」的落地方式。
     */
    fun rebindCodecKind(kind: VideoCodecKind): Boolean {
        if (kind == codecKind) return false
        Timber.i("FrameDecoder: codec kind %s -> %s, restarting", codecKind, kind)
        codecKind = kind
        stop()
        start()
        return true
    }

    fun stop() {
        isRunning = false
        try {
            codec?.stop()
        } catch (e: Exception) {
            Timber.w(e, "FrameDecoder: codec.stop threw")
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Timber.w(e, "FrameDecoder: codec.release threw")
        }
        codec = null
        synchronized(feedLock) { availableInputBuffers.clear() }
        policy.clear()
    }

    /**
     * 请求一个关键帧。
     * 相机侧对应 [com.arashivision.sdk.camera.api.CameraPreview.requestStreamIframe]，
     * 由 feature-recording 层串联。
     */
    fun markNeedsKeyframe() {
        policy.resetForNewStream()
    }
}
