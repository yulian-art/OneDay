package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.MappingConfidence
import com.julien.core_media.model.Micros

/**
 * 锚点来源，决定其可信上限。
 *
 * 方案文档反复强调：「时间同步不能用模型猜：接收时间不等于按下时间，
 * 命令发出不等于第一帧时间」。因此我们把「证据强度」显式建模，
 * 而不是把所有观测值一视同仁地喂给最小二乘。
 */
enum class AnchorSource(val baseConfidence: MappingConfidence) {
    /**
     * 可见同步事件（LED 闪、拍手、遮挡一次镜头）—— 在预览与原片里都能看到同一瞬间。
     * 文档指定的首选做法：「以可见同步事件建立映射」。
     */
    VISIBLE_SYNC_EVENT(MappingConfidence.CONFIRMED),

    /** 相机自报的录制时间/文件时间戳，来自设备状态而非我们的本地时钟。 */
    DEVICE_REPORTED(MappingConfidence.PROBABLE),

    /**
     * 「我们发出了开始录制命令」的时刻。
     * 这只是上界证据：命令发出 ≠ 第一帧写入时间，故置信度低。
     */
    COMMAND_ISSUE(MappingConfidence.UNCERTAIN),

    /**
     * 「我们收到了这一帧」的时刻（预览回调的接收时间）。
     * 文档点名不可当作按下时间，仅作粗略估计。
     */
    FRAME_RECEIVE(MappingConfidence.UNCERTAIN),

    /** 假设两域同源零偏移。仅用于完全没有证据时的占位，必须显式标记。 */
    ASSUMED_ALIGNED(MappingConfidence.UNKNOWN),
}

/**
 * 一个跨域锚点：已知「[domain] 域的 [rawUs] 时刻」对应
 * 「基准域（原片）的 [referenceUs] 时刻」。
 *
 * @param weight 参与拟合的权重。可见同步事件应给更高权重。
 * @param note   人类可读的来源说明，导出诊断报告时原样保留，
 *               以便方案文档要求的「可核查的事件记录」。
 */
data class TimeAnchor(
    val domain: ClockDomain,
    val rawUs: Micros,
    val referenceUs: Micros,
    val source: AnchorSource,
    val weight: Double = source.baseConfidence.defaultWeight,
    val note: String = "",
) {
    init {
        require(weight > 0.0) { "anchor weight must be > 0, was $weight" }
    }
}

private val MappingConfidence.defaultWeight: Double
    get() = when (this) {
        MappingConfidence.CONFIRMED -> 4.0
        MappingConfidence.PROBABLE -> 2.0
        MappingConfidence.UNCERTAIN -> 1.0
        MappingConfidence.UNKNOWN -> 0.25
    }
