package com.julien.core_media.model

/** 微秒。全模块统一用微秒，避免 ms/us 混用导致的 1000 倍错误。 */
typealias Micros = Long

/**
 * 半开区间 `[startUs, endExclusiveUs)`。
 *
 * 半开是刻意的：方案文档要求「片段边界」可核查，闭区间会让相邻片段
 * 在边界帧上重复计数，导出时出现一帧的重叠。
 */
data class TimeRangeUs(
    val startUs: Micros,
    val endExclusiveUs: Micros,
) {
    init {
        require(endExclusiveUs >= startUs) {
            "TimeRangeUs end ($endExclusiveUs) must be >= start ($startUs)"
        }
    }

    val durationUs: Micros get() = endExclusiveUs - startUs

    val isEmpty: Boolean get() = endExclusiveUs == startUs

    operator fun contains(t: Micros): Boolean = t >= startUs && t < endExclusiveUs

    /** 与另一区间是否真的重叠（相邻不算重叠）。 */
    fun overlaps(other: TimeRangeUs): Boolean =
        startUs < other.endExclusiveUs && other.startUs < endExclusiveUs

    fun intersectionOrNull(other: TimeRangeUs): TimeRangeUs? {
        val s = maxOf(startUs, other.startUs)
        val e = minOf(endExclusiveUs, other.endExclusiveUs)
        return if (e > s) TimeRangeUs(s, e) else null
    }

    fun shift(deltaUs: Micros): TimeRangeUs =
        TimeRangeUs(startUs + deltaUs, endExclusiveUs + deltaUs)

    /** 向两侧扩张，用于给不确定的边界留容差。 */
    fun expand(padUs: Micros): TimeRangeUs =
        TimeRangeUs(startUs - padUs, endExclusiveUs + padUs)

    fun clampTo(bounds: TimeRangeUs): TimeRangeUs {
        val s = startUs.coerceIn(bounds.startUs, bounds.endExclusiveUs)
        val e = endExclusiveUs.coerceIn(bounds.startUs, bounds.endExclusiveUs)
        return TimeRangeUs(s, e)
    }

    companion object {
        /** 以中心与时长构造，便于「±容差」表达。 */
        fun around(centerUs: Micros, halfWidthUs: Micros): TimeRangeUs =
            TimeRangeUs(centerUs - halfWidthUs, centerUs + halfWidthUs)
    }
}
