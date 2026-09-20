package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.MappingConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖方案文档「08」第 2 条：
 * 「若用线性映射，应测偏移与漂移；停录、重启、分段分别处理」。
 */
class LinearTimeMapTest {

    private fun anchor(
        raw: Long,
        ref: Long,
        source: AnchorSource = AnchorSource.VISIBLE_SYNC_EVENT,
    ) = TimeAnchor(
        domain = ClockDomain.PREVIEW_STREAM,
        rawUs = raw,
        referenceUs = ref,
        source = source,
    )

    @Test
    fun `no anchors means no map`() {
        assertNull(LinearTimeMap.fit(ClockDomain.PREVIEW_STREAM, emptyList()))
    }

    @Test
    fun `single anchor degrades to pure offset and is not actionable as confirmed`() {
        val map = LinearTimeMap.fit(ClockDomain.PREVIEW_STREAM, listOf(anchor(1_000, 5_000)))!!
        // 纯偏移：reference = raw + 4000
        assertEquals(9_000L, map.toReference(5_000).referenceUs)
        assertEquals(1.0, map.scale, 1e-9)
        assertEquals(MappingConfidence.UNCERTAIN, map.toReference(5_000).confidence)
    }

    @Test
    fun `two anchors recover exact offset and zero drift`() {
        // 纯偏移 +1_000_000us，两域速率一致 → 漂移应为 0
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 1_000_000), anchor(1_000_000, 2_000_000)),
        )!!
        assertEquals(1_000_000L, map.offsetAtUs(0))
        assertEquals(0.0, map.driftPpm, 1.0)
        assertEquals(1_500_000L, map.toReference(500_000).referenceUs)
        assertEquals(0L, map.maxResidualUs)
        assertTrue(map.rejectedAnchors.isEmpty())
    }

    @Test
    fun `drift is measured in ppm`() {
        // raw 走 1_000_000us 时，reference 走 1_000_100us → +100ppm
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(1_000_000, 1_000_100)),
        )!!
        assertEquals(100.0, map.driftPpm, 0.5)
        assertTrue(map.scale > 1.0)
    }

    @Test
    fun `three consistent anchors reach CONFIRMED`() {
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 1_000), anchor(500_000, 501_000), anchor(1_000_000, 1_001_000)),
        )!!
        assertEquals(3, map.anchors.size)
        assertEquals(MappingConfidence.CONFIRMED, map.toReference(250_000).confidence)
    }

    @Test
    fun `an inconsistent anchor is rejected instead of skewing the fit`() {
        // 第三个锚点偏了 300ms —— 稳健拟合应当把它识别为离群并排除
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 1_000), anchor(500_000, 501_000), anchor(1_000_000, 1_301_000)),
        )!!
        assertEquals("the bad anchor must be set aside", 1, map.rejectedAnchors.size)
        assertEquals(1_000_000L, map.rejectedAnchors.first().rawUs)
        // 剩下两个好锚点拟合应当是干净的
        assertEquals(1_000L, map.offsetAtUs(0))
        assertEquals(0L, map.maxResidualUs)
        // 但「有锚点对不上」本身是可疑信号，不应再给 CONFIRMED
        assertTrue(map.toReference(500_000).confidence != MappingConfidence.CONFIRMED)
    }

    @Test
    fun `when no subset is consistent confidence degrades honestly`() {
        // 三个锚点两两斜率都不同，没有任何一致的子集
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(100_000, 500_000), anchor(200_000, 1_000_000)),
        )!!
        // 不应给出可执行的高置信度
        assertTrue(
            "mutually inconsistent evidence must not be CONFIRMED",
            map.toReference(100_000).confidence != MappingConfidence.CONFIRMED,
        )
    }

    @Test
    fun `absurd drift is rejected rather than trusted`() {
        // 斜率约 2.0 → 1_000_000ppm，远超阈值
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(1_000_000, 2_000_000)),
        )!!
        assertTrue("drift must be rejected", map.driftRejected)
        assertEquals(1.0, map.scale, 1e-9)
        assertTrue(map.toReference(1_000_000).confidence != MappingConfidence.CONFIRMED)
    }

    @Test
    fun `weak evidence is outvoted by strong evidence`() {
        // 3 个强锚点定义 +1000 偏移；1 个弱锚点故意给错值
        val anchors = listOf(
            anchor(0, 1_000),
            anchor(100_000, 101_000),
            anchor(200_000, 201_000),
            TimeAnchor(
                domain = ClockDomain.PREVIEW_STREAM,
                rawUs = 300_000,
                referenceUs = 999_999, // 完全离谱
                source = AnchorSource.FRAME_RECEIVE, // 权重最低
            ),
        )
        val map = LinearTimeMap.fit(ClockDomain.PREVIEW_STREAM, anchors)!!
        // 加权后不应被那一个弱证据带偏太多
        assertTrue(
            "weak single anchor must not dominate: offset=${map.offsetAtUs(0)}",
            kotlin.math.abs(map.offsetAtUs(0) - 1_000L) <= 20_000L,
        )
    }

    @Test
    fun `extrapolation beyond anchor span is flagged and downgraded`() {
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(1_000_000, 1_000_000)),
        )!!
        val inside = map.toReference(500_000)
        assertEquals(0L, inside.extrapolatedByUs)
        assertTrue(!inside.isExtrapolated)

        val outside = map.toReference(3_000_000)
        assertEquals(2_000_000L, outside.extrapolatedByUs)
        assertTrue(outside.isExtrapolated)
        assertTrue("extrapolated results must not be CONFIRMED", outside.confidence != MappingConfidence.CONFIRMED)
    }

    @Test
    fun `error bound grows with assumed input jitter`() {
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(1_000_000, 1_000_000)),
        )!!
        val clean = map.toReference(500_000, assumedErrorUs = 0)
        val jittery = map.toReference(500_000, assumedErrorUs = 30_000)
        assertEquals(30_000L, jittery.errorBoundUs - clean.errorBoundUs)
    }

    @Test
    fun `anchors from other domains are ignored`() {
        val mixed = listOf(
            anchor(0, 1_000),
            TimeAnchor(
                domain = ClockDomain.PHONE_MONOTONIC,
                rawUs = 5,
                referenceUs = 5,
                source = AnchorSource.VISIBLE_SYNC_EVENT,
            ),
        )
        val map = LinearTimeMap.fit(ClockDomain.PREVIEW_STREAM, mixed)!!
        assertEquals("only same-domain anchors may be used", 1, map.anchors.size)
    }

    @Test
    fun `describe exposes offset drift and residual for acceptance records`() {
        val map = LinearTimeMap.fit(
            ClockDomain.PREVIEW_STREAM,
            listOf(anchor(0, 0), anchor(1_000_000, 1_000_100)),
        )!!
        val text = map.describe()
        assertNotNull(text)
        assertTrue(text.contains("drift="))
        assertTrue(text.contains("maxResidual="))
    }
}
