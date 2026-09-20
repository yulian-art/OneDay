package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.MappingConfidence
import com.julien.core_media.model.TimeRangeUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖方案文档「08」第 2 条的三个要点：
 * 「保存原始时间，以可见同步事件建立映射」「测偏移与漂移」「停录、重启、分段分别处理」。
 */
class RecordingTimelineTest {

    private fun marker(
        id: String,
        label: String,
        preview: Long,
        original: Long,
        phone: Long? = null,
    ): SyncMarker = SyncMarker(
        id = id,
        label = label,
        readings = buildMap {
            put(ClockDomain.PREVIEW_STREAM, preview)
            put(ClockDomain.ORIGINAL_FILE, original)
            phone?.let { put(ClockDomain.PHONE_MONOTONIC, it) }
        },
    )

    @Test
    fun `no segments means no mapping`() {
        val timeline = RecordingTimeline()
        assertNull(timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 1_000))
    }

    @Test
    fun `original domain passes through unchanged`() {
        val timeline = RecordingTimeline()
        val r = timeline.toOriginal(ClockDomain.ORIGINAL_FILE, 12_345)
        assertNotNull(r)
        assertEquals(12_345L, r!!.referenceUs)
        assertEquals(MappingConfidence.CONFIRMED, r.confidence)
    }

    @Test
    fun `sync markers establish the preview to original mapping`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 1_000_000, captureConfirmedAtUs = 1_100_000)

        // 两次可见同步事件：预览时间戳与原片时间戳的配对
        timeline.recordSyncMarker(marker("m1", "clap-1", preview = 100_000, original = 500_000))
        timeline.recordSyncMarker(marker("m2", "clap-2", preview = 2_100_000, original = 2_500_000))
        timeline.closeSegment("seg-0", closedAtUs = 5_000_000)

        // 两锚点完全一致 → 偏移 +400_000，漂移 0
        val r = timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 1_100_000)!!
        assertEquals(1_500_000L, r.referenceUs)
        assertTrue(r.confidence.isActionable)
    }

    @Test
    fun `mapping reports drift when clocks diverge`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        // 预览走 1s，原片走 1.0001s → +100ppm
        timeline.recordSyncMarker(marker("m1", "a", preview = 0, original = 0))
        timeline.recordSyncMarker(marker("m2", "b", preview = 1_000_000, original = 1_000_100))
        timeline.closeSegment("seg-0", closedAtUs = 2_000_000)

        val map = timeline.allSegments.first()
            .mapFor(ClockDomain.PREVIEW_STREAM)!!
        assertEquals(100.0, map.driftPpm, 1.0)
    }

    @Test
    fun `segments are mapped independently because the original timeline restarts`() {
        val timeline = RecordingTimeline()

        // 第一段：预览 0..1s 对应原片 0..1s
        timeline.openSegment("seg-0", phoneOpenedAtUs = 10_000_000, captureConfirmedAtUs = 10_100_000)
        timeline.recordSyncMarker(marker("a1", "s0-a", preview = 0, original = 0))
        timeline.recordSyncMarker(marker("a2", "s0-b", preview = 1_000_000, original = 1_000_000))
        timeline.closeSegment("seg-0", closedAtUs = 12_000_000)

        // 第二段：原片时间轴**重新从 0 开始**，但预览流时间戳继续增长
        timeline.openSegment("seg-1", phoneOpenedAtUs = 20_000_000, captureConfirmedAtUs = 20_100_000)
        timeline.recordSyncMarker(marker("b1", "s1-a", preview = 10_000_000, original = 0))
        timeline.recordSyncMarker(marker("b2", "s1-b", preview = 11_000_000, original = 1_000_000))
        timeline.closeSegment("seg-1", closedAtUs = 22_000_000)

        // 关键断言：第二段的预览时间不能沿用第一段的偏移
        val inSecond = timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 10_500_000)!!
        assertEquals("second segment must use its own origin", 500_000L, inSecond.referenceUs)

        val inFirst = timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 500_000)!!
        assertEquals(500_000L, inFirst.referenceUs)
    }

    @Test
    fun `weak fallback maps phone clock to original zero when no sync markers exist`() {
        val timeline = RecordingTimeline()
        timeline.openSegment(
            "seg-0",
            phoneOpenedAtUs = 1_000_000,
            captureConfirmedAtUs = 1_050_000,
        )

        val r = timeline.toOriginal(ClockDomain.PHONE_MONOTONIC, 1_050_000)
        assertNotNull("a weak fallback must exist so the UI is not dead", r)
        assertEquals(0L, r!!.referenceUs)
        // 但必须诚实标记为不确定
        assertEquals(MappingConfidence.UNCERTAIN, r.confidence)
        assertFalse(r.confidence.isActionable)
    }

    @Test
    fun `preview domain has no weak fallback because there is no shared origin`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 1_000_000, captureConfirmedAtUs = 1_050_000)
        assertNull(timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 500))
    }

    @Test
    fun `sync markers are dropped when no segment is open`() {
        val timeline = RecordingTimeline()
        assertFalse(
            "must not attach an anchor to the wrong original timeline",
            timeline.recordSyncMarker(marker("x", "orphan", 1, 1)),
        )
    }

    @Test
    fun `opening a new segment auto closes a dangling one`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 100)
        assertTrue(timeline.isRecording)

        timeline.openSegment("seg-1", phoneOpenedAtUs = 200)
        assertEquals(2, timeline.allSegments.size)
        assertTrue("previous segment must be closed", timeline.allSegments[0].isClosed)
        assertFalse(timeline.allSegments[1].isClosed)
        assertEquals("seg-1", timeline.openSegmentId)
    }

    @Test
    fun `segment indices are sequential`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("a", phoneOpenedAtUs = 1)
        timeline.closeSegment("a", closedAtUs = 2)
        timeline.openSegment("b", phoneOpenedAtUs = 3)
        assertEquals(listOf(0, 1), timeline.allSegments.map { it.index })
        assertEquals(listOf("a", "b"), timeline.allSegments.map { it.id })
    }

    @Test
    fun `description exposes segments and their maps for acceptance records`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        timeline.recordSyncMarker(marker("m", "clap", 0, 0))
        timeline.closeSegment("seg-0", closedAtUs = 1)
        val text = timeline.describe()
        assertTrue(text.contains("seg-0"))
        assertTrue(text.contains("anchors="))
    }

    @Test
    fun `toDomain inverts a forward mapping`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        timeline.recordSyncMarker(marker("m1", "a", preview = 0, original = 1_000))
        timeline.recordSyncMarker(marker("m2", "b", preview = 1_000_000, original = 1_001_000))
        timeline.closeSegment("seg-0", closedAtUs = 2_000_000)

        // 原片 501_000 应对应预览约 500_000
        val back = timeline.toDomain(ClockDomain.PREVIEW_STREAM, TimeRangeUs(501_000, 601_000))
        assertNotNull(back)
        assertTrue(
            "inverse mapping should land near 500_000, was ${back!!.startUs}",
            kotlin.math.abs(back.startUs - 500_000L) <= 2_000L,
        )
    }

    @Test
    fun `reset clears everything`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        timeline.recordSyncMarker(marker("m", "x", 0, 0))
        timeline.reset()
        assertTrue(timeline.allSegments.isEmpty())
        assertNull(timeline.openSegmentId)
        assertFalse(timeline.isRecording)
    }

    @Test
    fun `sync marker can be completed later with the original reading`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 1_000)

        // 打点瞬间只知道手机与预览两个域 —— 原片位置此时不可能知道
        timeline.recordSyncMarker(
            SyncMarker(
                id = "s1",
                label = "clap",
                readings = mapOf(
                    ClockDomain.PHONE_MONOTONIC to 2_000L,
                    ClockDomain.PREVIEW_STREAM to 50_000L,
                ),
            ),
        )

        // 此刻 preview→original 还不成立
        assertNull(timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 50_000))

        // 分析链路在原片里找到了这次拍手
        assertTrue(timeline.completeSyncMarker("s1", ClockDomain.ORIGINAL_FILE, 7_000L))

        val merged = timeline.findSyncMarker("s1")!!
        assertEquals(3, merged.readings.size)

        // 补齐后映射成立
        val mapped = timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 50_000)
        assertNotNull(mapped)
        assertEquals(7_000L, mapped!!.referenceUs)
    }

    @Test
    fun `completing an unknown marker reports failure`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        assertFalse(timeline.completeSyncMarker("nope", ClockDomain.ORIGINAL_FILE, 1L))
    }

    @Test
    fun `a marker can be completed after its segment was closed`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        timeline.recordSyncMarker(
            SyncMarker(
                id = "s2",
                label = "clap",
                readings = mapOf(ClockDomain.PREVIEW_STREAM to 10L),
            ),
        )
        timeline.closeSegment("seg-0", closedAtUs = 999)

        // 停录之后才在原片里定位到，是常态
        assertTrue(timeline.completeSyncMarker("s2", ClockDomain.ORIGINAL_FILE, 42L))
        assertEquals(42L, timeline.findSyncMarker("s2")!![ClockDomain.ORIGINAL_FILE])
    }

    @Test
    fun `two complete markers give an actionable mapping`() {
        val timeline = RecordingTimeline()
        timeline.openSegment("seg-0", phoneOpenedAtUs = 0)
        timeline.recordSyncMarker(
            SyncMarker(
                id = "a",
                label = "clap-1",
                readings = mapOf(ClockDomain.PREVIEW_STREAM to 0L, ClockDomain.ORIGINAL_FILE to 1_000L),
            ),
        )
        timeline.recordSyncMarker(
            SyncMarker(
                id = "b",
                label = "clap-2",
                readings = mapOf(ClockDomain.PREVIEW_STREAM to 1_000_000L, ClockDomain.ORIGINAL_FILE to 1_001_000L),
            ),
        )
        timeline.closeSegment("seg-0", closedAtUs = 2_000_000)

        val mapped = timeline.toOriginal(ClockDomain.PREVIEW_STREAM, 500_000)!!
        assertEquals(501_000L, mapped.referenceUs)
        assertTrue(mapped.confidence.isActionable)
    }
}
