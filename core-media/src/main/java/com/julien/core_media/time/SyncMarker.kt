package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.Micros

/**
 * 一次**可见同步事件**在多个时钟域上的读数。
 *
 * 方案文档要求「以可见同步事件建立映射」，而不是用模型去猜时间。
 * 现实中这类事件是拍手、闪光灯、遮挡镜头 —— 它在**每一路**信号里都留下一个
 * 可定位的时刻，把这些时刻凑齐就得到一组锚点。
 *
 * ### 为什么 readings 是 Map 而不是固定字段
 * 不同同步事件能提供的域不一样：
 * - 用户拍手那一刻，我们立刻能拿到 [ClockDomain.PHONE_MONOTONIC] 与
 *   [ClockDomain.PREVIEW_STREAM]；
 * - 但它在 [ClockDomain.ORIGINAL_FILE] 里的位置，要等分析链路在原片中
 *   看到这次拍手才知道。
 *
 * 所以同一个事件是**分两步**补齐的（见
 * [RecordingTimeline.completeSyncMarker]），用 Map 才能表达「暂时缺某一域」。
 *
 * @param createdAtEpochMs 登记时刻的墙上时间，仅用于展示与排序，
 *        **不参与映射计算**（墙上时钟可被调整，不是可靠的时间基准）。
 */
data class SyncMarker(
    val id: String,
    val label: String,
    val readings: Map<ClockDomain, Micros>,
    val createdAtEpochMs: Long = 0L,
) {
    /** 取某个域的读数；缺失返回 null（表示「还没定位到」）。 */
    operator fun get(domain: ClockDomain): Micros? = readings[domain]

    /** 已具备读数的域数量。建立映射至少需要 2 个。 */
    val domainCount: Int get() = readings.size
}
