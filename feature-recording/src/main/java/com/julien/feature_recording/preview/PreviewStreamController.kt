package com.julien.feature_recording.preview

import com.julien.core_media.codec.CodecDetection
import com.julien.core_media.codec.DecodeListener
import com.julien.core_media.codec.DecoderConfig
import com.julien.core_media.codec.FrameDecoder
import com.julien.core_media.codec.VideoCodecFormat
import com.julien.core_media.codec.VideoCodecKind
import com.julien.core_media.model.Micros
import com.julien.core_media.stream.AssembledAccessUnit
import com.julien.core_media.stream.EncodedFragment
import com.julien.core_media.stream.FrameAssembler
import com.julien.core_media.stream.FrameAssemblerConfig
import com.julien.core_media.stream.MediaStreamType
import com.julien.core_media.stream.StreamStats
import com.julien.core_media.util.MonotonicClock
import com.julien.core_media.util.SystemMonotonicClock
import com.julien.feature_recording.device.DevicePreviewEvents
import com.julien.feature_recording.device.PreviewFrameKind
import com.julien.feature_recording.device.PreviewStreamApi
import com.julien.feature_recording.model.PreviewStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** 状态发布的最小间隔。预览回调是逐帧的，不节流会让 Compose 以 30fps 重组。 */
private const val PUBLISH_MIN_INTERVAL_US: Micros = 500_000L

/** 管道参数。 */
data class PreviewPipelineConfig(
    val assembler: FrameAssemblerConfig = FrameAssemblerConfig(),
    /** 探测编码格式需要的最少视频帧数。太少会误判，太多会延迟出图。 */
    val codecProbeFrames: Int = 3,
    /** flushStale 心跳间隔。必须小于流光停止时的可容忍延迟。 */
    val flushIntervalMs: Long = 100,
    /** 解码队列容量。 */
    val decoderQueueCapacity: Int = 8,
)

/** 解码帧消费者（端侧轻量检测的入口）。 */
fun interface DecodedFrameSink {
    /** @param presentationTimeUs 预览域时间戳，**必须原样用于时间映射**。 */
    fun onFrame(presentationTimeUs: Long)
}

/**
 * 预览流管道：**分片 → 合并 → 识别编码 → 解码**。
 *
 * ### 这是分析链路，不是录制链路
 * 方案文档要求「录像和分析分开运行……分析失败不应影响原始记录」。
 * 因此本类：
 * - 不引用 [com.julien.feature_recording.recording.RecordingController] 的任何东西；
 * - 所有异常都被吞掉并反映到 [state] 的 Failed，绝不外抛。
 *
 * ### 为什么要先探测编码格式
 * 文档：「编码格式也需按实际机型、固件判断」。H.264 与 H.265 必须用不同 mime
 * 创建解码器。所以先收若干帧做投票（[VideoCodecFormat.decide]），
 * 确定后再建解码器，并立即 [PreviewStreamApi.requestKeyframe] ——
 * 没有关键帧，解码器只能吐花屏。
 *
 * ### 坏帧防线（对应文档「08」第 1 条）
 * 1. 分片合并：同一 timestamp 的分片必须拼成一帧才送解码（否则绿屏）；
 * 2. 有界队列：队列满时丢**整帧**而非分片；
 * 3. 丢帧后进入「等关键帧」状态，主动向相机请求 I 帧恢复。
 */
class PreviewStreamController(
    private val api: PreviewStreamApi,
    private val scope: CoroutineScope,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val config: PreviewPipelineConfig = PreviewPipelineConfig(),
    private val frameSink: DecodedFrameSink? = null,
) {

    private val _state = MutableStateFlow<PreviewStreamState>(PreviewStreamState.Idle)
    val state: StateFlow<PreviewStreamState> = _state.asStateFlow()

    /** 分片合并统计，供验收「预览稳定获取」。 */
    val assemblerStats: StreamStats get() = assembler.stats

    private var assembler = newAssembler()

    private var decoder: FrameDecoder? = null

    /** 编码格式投票累积。 */
    private val codecVotes = mutableListOf<CodecDetection>()
    private var codecKind: VideoCodecKind = VideoCodecKind.UNKNOWN

    private var width: Int = 0
    private var height: Int = 0
    private var fps: Int = 0

    private var decodedFrames: Long = 0L
    private var awaitingKeyframe: Boolean = true
    private var flushJob: Job? = null
    private var started = false

    /**
     * 当前生效的失败状态（null 表示正常）。
     *
     * 用一个显式字段而不是读 `_state.value`：因为 _state 同时承载 Streaming 快照，
     * 若以「当前是不是 Failed」来判断，就没法区分「持久失败」与「瞬时失败」，
     * 也无法在数据恢复后清除错误。
     */
    @Volatile
    private var failure: PreviewStreamState.Failed? = null

    /** 上次发布时间（微秒），用于节流。允许良性竞争，最坏只是多发一次。 */
    @Volatile
    private var lastPublishAtUs: Micros = 0L

    private fun newAssembler() = FrameAssembler(config.assembler) { unit ->
        onAccessUnit(unit)
    }

    private val events = object : DevicePreviewEvents {

        override fun onOpening() {
            Timber.d("preview: opening")
            failure = null
            publish(force = true)
        }

        override fun onOpened() {
            Timber.i("preview: opened")
            failure = null
            publish(force = true)
            // 刚开流时先要一个关键帧，避免开头几帧解不出来
            api.requestKeyframe()
        }

        override fun onIdle() {
            Timber.i("preview: idle")
            if (started) {
                // 流被相机侧停掉：如实反映，不假装还在收
                failure = PreviewStreamState.Failed("相机已停止预览流")
                publish(force = true)
            }
        }

        override fun onParamsChanged(width: Int, height: Int, fps: Int) {
            this@PreviewStreamController.width = width
            this@PreviewStreamController.height = height
            this@PreviewStreamController.fps = fps
            Timber.i("preview params: %dx%d @%dfps", width, height, fps)
            // 分辨率变了，旧解码器必须重建
            if (codecKind != VideoCodecKind.UNKNOWN) {
                rebuildDecoder()
            }
            publish(force = true)
        }

        override fun onStreamData(data: ByteArray, timestampUs: Long, kind: PreviewFrameKind) {
            if (!started) return
            if (!kind.isVideo) return // 音频/陀螺数据本管道不处理

            // 又有数据了 → 之前的失败状态作废，允许从 Failed 恢复。
            // 否则一次瞬时错误会让界面永久显示失败，即使流早已正常。
            if (failure != null) {
                Timber.i("preview: data resumed, clearing previous failure")
                failure = null
            }

            // 关键一步：这里拿到的是**分片**，交给 assembler 按时间戳合并
            assembler.add(
                EncodedFragment(
                    data = data,
                    timestampUs = timestampUs,
                    type = kind.toStreamType(),
                    receivedAtUs = monotonicClock.nowUs(),
                ),
            )
        }

        override fun onStreamError(error: Throwable) {
            Timber.e(error, "preview stream error")
            failure = PreviewStreamState.Failed(
                message = error.message ?: error.javaClass.simpleName,
                cause = error,
            )
            publish(force = true)
        }
    }

    /** 开始预览与分析。 */
    fun start() {
        if (started) return
        started = true
        codecKind = VideoCodecKind.UNKNOWN
        codecVotes.clear()
        decodedFrames = 0
        awaitingKeyframe = true
        failure = null
        lastPublishAtUs = 0L
        assembler = newAssembler()
        _state.value = PreviewStreamState.Opening

        api.registerEvents(events)
        api.startStream()

        // flushStale 心跳：没有它，每段的最后一帧会永远留在合并缓冲里
        flushJob = scope.launch {
            while (isActive) {
                delay(config.flushIntervalMs)
                if (!started) break
                runCatching { assembler.flushStale(monotonicClock.nowUs()) }
                    .onFailure { Timber.w(it, "flushStale failed") }
                publish()
            }
        }
    }

    /** 停止预览，释放解码器。 */
    fun stop() {
        if (!started) return
        started = false
        flushJob?.cancel()
        flushJob = null
        runCatching { assembler.flushAll() }
        api.stopStream()
        api.unregisterEvents(events)
        decoder?.stop()
        decoder = null
        codecVotes.clear()
        _state.value = PreviewStreamState.Idle
    }

    /**
     * 一个完整编码帧合并完成。
     *
     * 若还在探测编码格式，则先投票；确定后建解码器再开始喂数据。
     * 这段「探测期」的帧会被丢弃 —— 但会如实反映在
     * [PreviewStreamState.Streaming.probingCodec] 上，不做静默。
     */
    private fun onAccessUnit(unit: AssembledAccessUnit) {
        if (codecKind == VideoCodecKind.UNKNOWN) {
            probeCodec(unit)
            return
        }
        val d = decoder ?: return
        if (awaitingKeyframe) {
            // 解码器在等关键帧：只放行关键帧，避免把花屏喂上屏
            if (!VideoCodecFormat.isKeyframe(unit.data, codecKind)) {
                publish()
                return
            }
            awaitingKeyframe = false
        }
        d.submit(unit)
    }

    private fun probeCodec(unit: AssembledAccessUnit) {
        val detection = VideoCodecFormat.detect(unit.data)
        if (detection.kind != VideoCodecKind.UNKNOWN) {
            codecVotes += detection
        }
        val decided = VideoCodecFormat.decide(codecVotes)
        val enough = codecVotes.size >= config.codecProbeFrames

        if (decided != VideoCodecKind.UNKNOWN && enough) {
            codecKind = decided
            Timber.i(
                "preview: codec decided = %s (votes=%d, packing=%s)",
                decided,
                codecVotes.size,
                detection.packing,
            )
            rebuildDecoder()
        }
        publish()
    }

    private fun rebuildDecoder() {
        decoder?.stop()
        decoder = null

        if (width <= 0 || height <= 0) {
            Timber.w("preview: cannot build decoder before params are known")
            return
        }

        val d = FrameDecoder(
            config = DecoderConfig(
                kind = codecKind,
                width = width,
                height = height,
                frameRate = if (fps > 0) fps else 30,
            ),
            surface = null, // 分析只需要时间戳与解码成功/失败；上屏由 SDK 的预览播放器负责
            listener = object : DecodeListener {
                override fun onFrameDecoded(presentationTimeUs: Long, renderTimeUs: Long) {
                    decodedFrames++
                    frameSink?.onFrame(presentationTimeUs)
                }

                override fun onDecodeError(throwable: Throwable) {
                    // 分析链路失败不影响录制：只记录，并把状态如实标出来
                    Timber.w(throwable, "preview decoder error")
                }
            },
        )

        d.start()
            .onSuccess {
                decoder = d
                awaitingKeyframe = true
                // 新建解码器必须从关键帧开始，主动向相机索取
                api.requestKeyframe()
            }
            .onFailure {
                failure = PreviewStreamState.Failed(
                    message = "解码器启动失败: ${it.message ?: it.javaClass.simpleName}",
                    cause = it,
                )
            }
        publish(force = true)
    }

    /**
     * 发布当前状态。
     *
     * 两处修正：
     * 1. **允许从 Failed 恢复**：不再因为「当前是 Failed」就直接 return。
     *    错误保存在 [failure] 里，只有真正收到新数据（或重新开流）才清除；
     *    这样既不会把持久错误刷掉，也不会让一次瞬时错误永久卡住界面。
     * 2. **节流**：本方法在预览回调线程上被逐帧调用。若每帧都发 StateFlow，
     *    会让 Compose 以约 30fps 持续重组。这里限制到
     *    [PUBLISH_MIN_INTERVAL_US]，重要状态变化用 `force = true` 绕过节流。
     */
    private fun publish(force: Boolean = false) {
        if (!started) return
        val now = monotonicClock.nowUs()
        if (!force && now - lastPublishAtUs < PUBLISH_MIN_INTERVAL_US) return
        lastPublishAtUs = now

        val failed = failure
        _state.value = failed ?: PreviewStreamState.Streaming(
            width = width,
            height = height,
            fps = fps,
            codecKind = codecKind,
            probingCodec = codecKind == VideoCodecKind.UNKNOWN,
            awaitingKeyframe = awaitingKeyframe,
            stats = assembler.stats,
            decodedFrames = decodedFrames,
        )
    }

    private fun PreviewFrameKind.toStreamType(): MediaStreamType = when (this) {
        PreviewFrameKind.VIDEO -> MediaStreamType.VIDEO
        PreviewFrameKind.VIDEO_L -> MediaStreamType.VIDEO_L
        PreviewFrameKind.VIDEO_R -> MediaStreamType.VIDEO_R
        PreviewFrameKind.AUDIO -> MediaStreamType.AUDIO
        PreviewFrameKind.GYRO -> MediaStreamType.GYRO
        PreviewFrameKind.OTHER -> MediaStreamType.OTHER
        PreviewFrameKind.UNKNOWN -> MediaStreamType.UNKNOWN
    }
}
