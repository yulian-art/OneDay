package com.julien.core_media.codec

import com.julien.core_media.stream.AssembledAccessUnit

/**
 * 一次入队裁决的结果。
 *
 * @param accepted 该帧是否被接受进入解码队列。
 * @param evicted  为了腾空间而**整帧**丢弃的最旧帧（null 表示没丢）。
 * @param reason   可读原因，用于诊断与验收统计。
 */
data class AdmissionDecision(
    val accepted: Boolean,
    val evicted: AssembledAccessUnit?,
    val reason: String,
)

/**
 * 有界解码队列的准入策略。
 *
 * ### 为什么把「丢帧」单独建模
 * 方案文档「08」第 1 条要求：「独立解码、有界队列；**不能随意丢编码分片**」。
 *
 * 关键区别在于「丢什么」：
 * - 丢**分片**（fragment）→ 拼出一帧残废数据 → 绿屏/坏帧，绝对禁止；
 * - 丢**整帧**（access unit）→ 合法，但因为 H.264/H.265 有帧间预测，
 *   丢掉一帧会让后续 P/B 帧失去参考 → 必须**等到下一个关键帧**才能恢复解码。
 *
 * 所以策略是：队列满时丢队首整帧，并立即置 `needsKeyframe = true`，
 * 丢弃所有非关键帧直到关键帧到来。这样画面会「卡一下再恢复」，
 * 而不是「一路花屏到底」。
 *
 * 本类是纯逻辑、不依赖 Android，因此可以在 JVM 单元测试里穷举验证。
 */
class DecoderAdmissionPolicy(private val capacity: Int = 8) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val queue = ArrayDeque<AssembledAccessUnit>()

    /**
     * 是否正在等待关键帧。
     * 初始为 true：解码器刚创建时，在收到第一个关键帧之前不应该喂任何数据。
     */
    var needsKeyframe: Boolean = true
        private set

    /** 因队列溢出而被整帧丢弃的数量。 */
    var evictedCount: Long = 0L
        private set

    /** 因等待关键帧而被丢弃的数量（这是恢复期的必要代价，非异常）。 */
    var droppedAwaitingKeyframe: Long = 0L
        private set

    val size: Int get() = queue.size

    val isEmpty: Boolean get() = queue.isEmpty()

    /**
     * 尝试接收一帧。
     *
     * @param isKeyframe 该帧是否为关键帧。由 [VideoCodecFormat.isKeyframe] 判定，
     *        以参数传入以便本类保持可测试性。
     */
    fun admit(unit: AssembledAccessUnit, isKeyframe: Boolean): AdmissionDecision {
        // 1) 恢复期：非关键帧一律不能进（进了也解不出正确画面）
        if (needsKeyframe && !isKeyframe) {
            droppedAwaitingKeyframe++
            return AdmissionDecision(false, null, "awaiting keyframe")
        }

        // 2) 队满：丢队首**整帧**，然后重新进入恢复期
        var evicted: AssembledAccessUnit? = null
        if (queue.size >= capacity) {
            evicted = queue.removeFirst()
            evictedCount++
            needsKeyframe = true
            // 丢掉一帧后，当前这帧若不是关键帧也不能用了
            if (!isKeyframe) {
                droppedAwaitingKeyframe++
                return AdmissionDecision(false, evicted, "evicted oldest; resync needed")
            }
        }

        needsKeyframe = false
        queue.addLast(unit)
        return AdmissionDecision(true, evicted, "accepted")
    }

    fun poll(): AssembledAccessUnit? = queue.removeFirstOrNull()

    /**
     * 把一帧放回队首。
     *
     * 用于「已经 poll 出来、但下游暂时喂不进去」的场景（例如 MediaCodec 的
     * 输入缓冲拿不到）。没有这个方法就只能把帧丢掉 —— 而静默丢帧是文档明确反对的。
     */
    fun pushFront(unit: AssembledAccessUnit) {
        queue.addFirst(unit)
    }

    fun clear() {
        queue.clear()
        needsKeyframe = true
    }

    /** 预览流重启后必须调用：清空并重新等待关键帧。 */
    fun resetForNewStream() = clear()
}
