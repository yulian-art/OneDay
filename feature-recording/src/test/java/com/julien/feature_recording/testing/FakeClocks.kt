package com.julien.feature_recording.testing

import com.julien.core_media.model.Micros
import com.julien.core_media.util.EpochClock
import com.julien.core_media.util.MonotonicClock

/**
 * 测试用的可控时钟。
 *
 * 放在 feature-recording 自己的 test 源集里，**不是** core-media 的 main：
 * 测试替身不应进入生产代码（此前它们被放在 core-media 的 main 里，会被打进 AAR）。
 *
 * 之所以不放 core-media 的 `testFixtures`：AGP 9 + Kotlin Android 插件目前
 * 没有为 testFixtures 源集生成 Kotlin 编译任务（只有 Java 的，且 NO-SOURCE），
 * 放在那里会「编译通过但类不存在」。而实际需要它们的只有本模块的单测，
 * 因此直接放在这里最简单也最可靠。
 */
class FakeMonotonicClock(private var valueUs: Micros = 0L) : MonotonicClock {
    override fun nowUs(): Micros = valueUs

    fun advanceUs(deltaUs: Micros) {
        valueUs += deltaUs
    }

    fun set(value: Micros) {
        valueUs = value
    }
}

/** 测试用的可控墙上时钟。 */
class FakeEpochClock(private var valueMs: Long = 0L) : EpochClock {
    override fun nowMs(): Long = valueMs

    fun advanceMs(deltaMs: Long) {
        valueMs += deltaMs
    }

    fun set(value: Long) {
        valueMs = value
    }
}
