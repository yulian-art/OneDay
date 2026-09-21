package com.julien.core_media.stream

import com.julien.core_media.model.Micros
import timber.log.Timber

/**
 * [FrameAssembler] 的行为参数。
 *
 * @param maxFragmentsPerFrame 单帧分片数上限，防止时间戳长时间不变
 *        （流卡住 / 相机异常）时内存无界增长。超限时强制发出并标记
 *        `forcedIncomplete`，因为这几乎必然是异常流。
 * @param staleFlushUs 一个待定帧超过这么久没有新分片，就认为本轮已经收完，
 *        主动发出。**没有这个机制会丢掉每一段的最后一帧** ——
 *        因为「帧完整」是靠「下一帧时间戳出现」推断的，流一旦停下就永远等不到。
 *        代价：若真有迟到分片，它会被记为 outOfOrder。
 */
data class FrameAssemblerConfig(
    val maxFragmentsPerFrame: Int = 64,
    val staleFlushUs: Micros = 250_000,
)

/**
 * 把预览流的编码**分片**合并成完整编码帧。
 *
 * ### 为什么必须有这一层
 * 方案文档「05 影石SDK接入路径与待验条件」明确警告：
 * > 一个编码帧可能分多次回调，应按同一时间戳合并分片；
 * > 错误地把单独分片送入解码器可能造成绿屏或坏帧。
 *
 * 也就是说 SDK 的 `onStreamDataNotify` 给到的 byte[] **不是**一帧，
 * 而是帧的一个片段。唯一可靠的归组依据是**时间戳**：
 * 同一帧的所有分片共享同一个 [EncodedFragment.timestampUs]。
 *
 * ### 合并规则
 * 对同一 [MediaStreamType]：
 * - 时间戳相同 → 继续累积到当前待定帧；
 * - 时间戳更大 → 当前待定帧已完整，发出，并为新时间戳开新帧；
 * - 时间戳更小 → 迟到分片，无法再并入（那一帧已发出），计入 [StreamStats.outOfOrderFragments]。
 *
 * ### 不丢分片
 * 本类**不会**为了省内存而丢弃当前帧的分片。唯一会「放弃」的情况是
 * 迟到分片（结构上不可能再使用）与超过 `maxFragmentsPerFrame` 的异常流，
 * 两者都会计入统计，便于验收时核查。
 *
 * ### 一个需要说清楚的不变式：待定帧恒 ≤1
 * 入队只发生在两处 —— 「队列为空时入队」与「先 removeLast 再入队」——
 * 所以每个流类型的待定队列长度**恒为 0 或 1**（见 `pendingFrameCount`）。
 *
 * 这意味着两件事：
 * - **本层不需要背压**，因为它从不积压。有界队列与丢帧策略在
 *   [com.julien.core_media.codec.DecoderAdmissionPolicy] 那一层。
 * - 这里**不会**因为「队列溢出」丢帧。曾经有一个 `maxPendingFrames`
 *   配置项与对应分支声称做这件事，但它对任何 ≥1 的取值都不可达，
 *   取 0 时又会丢掉刚入队的最新帧（与注释写的「丢最旧的」相反），
 *   属于假保证，已删除。
 *
 * ### 线程模型
 * 预期只在预览回调线程上调用 [add] / [flushStale]。
 * 本类不做内部加锁；若换线程使用，请在外部串行化。
 */
class FrameAssembler(
    private val config: FrameAssemblerConfig = FrameAssemblerConfig(),
    private val sink: (AssembledAccessUnit) -> Unit,
) {

    private class Pending(
        val timestampUs: Micros,
        val type: MediaStreamType,
        val firstReceivedAtUs: Micros,
    ) {
        val chunks = mutableListOf<ByteArray>()
        var lastReceivedAtUs: Micros = firstReceivedAtUs
        var byteCount: Int = 0
    }

    private val pending = mutableMapOf<MediaStreamType, ArrayDeque<Pending>>()

    private var fragmentsIn = 0L
    private var accessUnitsOut = 0L
    private var bytesIn = 0L
    private var framesForcedIncomplete = 0L
    private var duplicateTimestampFragments = 0L
    private var outOfOrderFragments = 0L

    /** 观测到的单帧最大分片数（统计用，区别于 config 里的上限）。 */
    private var observedMaxFragmentsPerFrame = 0
    private var maxAssemblyLatencyUs = 0L

    val stats: StreamStats
        get() = StreamStats(
            fragmentsIn = fragmentsIn,
            accessUnitsOut = accessUnitsOut,
            bytesIn = bytesIn,
            framesForcedIncomplete = framesForcedIncomplete,
            duplicateTimestampFragments = duplicateTimestampFragments,
            outOfOrderFragments = outOfOrderFragments,
            maxFragmentsPerFrame = observedMaxFragmentsPerFrame,
            maxAssemblyLatencyUs = maxAssemblyLatencyUs,
        )

    /** 当前尚未发出的帧数（所有流类型合计）。 */
    val pendingFrameCount: Int get() = pending.values.sumOf { it.size }

    /**
     * 送入一个分片。可能触发 0 次或多次 [sink] 回调。
     */
    fun add(fragment: EncodedFragment) {
        fragmentsIn++
        bytesIn += fragment.size

        val queue = pending.getOrPut(fragment.type) { ArrayDeque() }
        val head = queue.lastOrNull()

        when {
            head == null -> {
                queue.addLast(newPending(fragment))
            }

            fragment.timestampUs == head.timestampUs -> {
                // 同一帧的后续分片 —— 正常路径
                duplicateTimestampFragments++
                head.chunks += fragment.data
                head.byteCount += fragment.size
                head.lastReceivedAtUs = fragment.receivedAtUs
                val latency = head.lastReceivedAtUs - head.firstReceivedAtUs
                if (latency > maxAssemblyLatencyUs) maxAssemblyLatencyUs = latency
                if (head.chunks.size > observedMaxFragmentsPerFrame) {
                    observedMaxFragmentsPerFrame = head.chunks.size
                }

                // 严格「超过」上限才强制发出：正好等于上限仍可能是合法帧
                if (head.chunks.size > config.maxFragmentsPerFrame) {
                    // 异常流保护：时间戳迟迟不变，强制发出，避免内存无界增长
                    Timber.w(
                        "FrameAssembler: frame %d exceeded maxFragmentsPerFrame=%d, force-emitting",
                        head.timestampUs,
                        config.maxFragmentsPerFrame,
                    )
                    queue.removeLast()
                    emit(head, forcedIncomplete = true)
                }
            }

            fragment.timestampUs > head.timestampUs -> {
                // 新时间戳 → 上一帧确认收完
                queue.removeLast()
                emit(head, forcedIncomplete = false)
                queue.addLast(newPending(fragment))
            }

            else -> {
                // 迟到分片：对应的帧已经发出，结构上无法再并入
                outOfOrderFragments++
            }
        }
    }

    private fun newPending(fragment: EncodedFragment): Pending {
        val p = Pending(
            timestampUs = fragment.timestampUs,
            type = fragment.type,
            firstReceivedAtUs = fragment.receivedAtUs,
        )
        p.chunks += fragment.data
        p.byteCount += fragment.size
        return p
    }

    /**
     * 把停留超过 `staleFlushUs` 的待定帧发出。
     *
     * 应在预览停止、或周期性心跳（建议 100ms 一次）时调用，
     * 否则每段的最后一帧会永远留在缓冲里。
     *
     * @param nowUs 当前单调时刻（微秒）。
     * @return 本次实际发出的帧数。
     */
    fun flushStale(nowUs: Micros): Int {
        var flushed = 0
        for ((_, queue) in pending) {
            while (true) {
                val head = queue.firstOrNull() ?: break
                if (nowUs - head.lastReceivedAtUs < config.staleFlushUs) break
                queue.removeFirst()
                emit(head, forcedIncomplete = true)
                flushed++
            }
        }
        return flushed
    }

    /** 无视超时，立即发出所有待定帧（用于停止预览时收尾）。 */
    fun flushAll(): Int {
        var flushed = 0
        for ((_, queue) in pending) {
            while (true) {
                val head = queue.removeFirstOrNull() ?: break
                emit(head, forcedIncomplete = true)
                flushed++
            }
        }
        return flushed
    }

    private fun emit(frame: Pending, forcedIncomplete: Boolean) {
        if (frame.chunks.isEmpty()) return
        val merged = ByteArray(frame.byteCount)
        var offset = 0
        for (chunk in frame.chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        accessUnitsOut++
        if (forcedIncomplete) framesForcedIncomplete++
        sink(
            AssembledAccessUnit(
                data = merged,
                timestampUs = frame.timestampUs,
                type = frame.type,
                fragmentCount = frame.chunks.size,
                firstReceivedAtUs = frame.firstReceivedAtUs,
                lastReceivedAtUs = frame.lastReceivedAtUs,
                forcedIncomplete = forcedIncomplete,
            ),
        )
    }

    fun reset() {
        pending.clear()
        fragmentsIn = 0
        accessUnitsOut = 0
        bytesIn = 0
        framesForcedIncomplete = 0
        duplicateTimestampFragments = 0
        outOfOrderFragments = 0
        observedMaxFragmentsPerFrame = 0
        maxAssemblyLatencyUs = 0
    }
}
