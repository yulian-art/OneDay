package com.julien.core_media.util

import com.julien.core_media.model.Micros
import android.os.SystemClock

/**
 * 单调时钟。**测时长、算漂移只能用单调时钟** —— 墙上时钟会被 NTP 校正、
 * 用户改时间、时区切换打断，用它算出来的会话时长可能为负。
 */
fun interface MonotonicClock {
    fun nowUs(): Micros
}

/** 墙上时钟。只用于与外部日志/后端对齐，不用于测时长。 */
fun interface EpochClock {
    fun nowMs(): Long
}

/** 生产实现：基于 `SystemClock.elapsedRealtimeNanos`（含休眠，不受改时间影响）。 */
object SystemMonotonicClock : MonotonicClock {
    override fun nowUs(): Micros = SystemClock.elapsedRealtimeNanos() / 1_000L
}

/** 生产实现：`System.currentTimeMillis`。 */
object SystemEpochClock : EpochClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
