package com.julien.core_media.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖方案文档「05」：「编码格式也需按实际机型、固件判断」。
 * 也就是说不能写死 H.264，必须能从码流认出来。
 */
class VideoCodecFormatTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** H.264：SPS=0x67(type 7)、PPS=0x68(type 8)、IDR=0x65(type 5) */
    private fun h264Sample(): ByteArray =
        startCode + byteArrayOf(0x67, 0x42, 0x00, 0x1E) +
            startCode + byteArrayOf(0x68, (0xCE).toByte(), 0x3C, (0x80).toByte()) +
            startCode + byteArrayOf(0x65, (0x88).toByte(), (0x84).toByte())

    /** H.265：VPS=0x40(type 32)、SPS=0x42(type 33)、PPS=0x44(type 34)、IDR=0x26(type 19) */
    private fun hevcSample(): ByteArray =
        startCode + byteArrayOf(0x40, 0x01, 0x0C) +
            startCode + byteArrayOf(0x42, 0x01, 0x01) +
            startCode + byteArrayOf(0x44, 0x01, (0xC0).toByte()) +
            startCode + byteArrayOf(0x26, 0x01, (0xAF).toByte())

    @Test
    fun `detects H264 from parameter sets`() {
        val d = VideoCodecFormat.detect(h264Sample())
        assertEquals(VideoCodecKind.H264, d.kind)
        assertEquals(BitstreamPacking.ANNEX_B, d.packing)
        assertTrue(d.hasSps)
        assertTrue(d.hasPps)
        assertFalse(d.hasVps)
        assertTrue(d.isKeyframe)
        assertTrue(d.confidence > 0.9)
    }

    @Test
    fun `detects HEVC via VPS which only HEVC has`() {
        val d = VideoCodecFormat.detect(hevcSample())
        assertEquals(VideoCodecKind.HEVC, d.kind)
        assertTrue("VPS is the decisive evidence", d.hasVps)
        assertTrue(d.isKeyframe)
        assertTrue(d.confidence > 0.9)
    }

    @Test
    fun `hevc mime is correct`() {
        assertEquals("video/avc", VideoCodecKind.H264.mime)
        assertEquals("video/hevc", VideoCodecKind.HEVC.mime)
    }

    @Test
    fun `detects length prefixed packing`() {
        // 4 字节大端长度前缀，长度取 4 以免被误判成起始码
        val payload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val data = byteArrayOf(0, 0, 0, 4) + payload

        val d = VideoCodecFormat.detect(data)
        assertEquals(BitstreamPacking.LENGTH_PREFIXED, d.packing)
        assertEquals(VideoCodecKind.H264, d.kind)
    }

    @Test
    fun `empty or garbage input yields UNKNOWN without crashing`() {
        assertEquals(VideoCodecKind.UNKNOWN, VideoCodecFormat.detect(ByteArray(0)).kind)
        assertEquals(VideoCodecKind.UNKNOWN, VideoCodecFormat.detect(byteArrayOf(1, 2, 3)).kind)
    }

    @Test
    fun `majority vote across frames decides the codec`() {
        val votes = listOf(
            VideoCodecFormat.detect(hevcSample()),
            VideoCodecFormat.detect(hevcSample()),
            VideoCodecFormat.detect(ByteArray(0)), // 一帧没探出来
        )
        assertEquals(VideoCodecKind.HEVC, VideoCodecFormat.decide(votes))
        assertEquals(VideoCodecKind.UNKNOWN, VideoCodecFormat.decide(emptyList()))
    }

    @Test
    fun `keyframe detection distinguishes IDR from non-IDR`() {
        assertTrue(VideoCodecFormat.isKeyframe(h264Sample(), VideoCodecKind.H264))
        assertTrue(VideoCodecFormat.isKeyframe(hevcSample(), VideoCodecKind.HEVC))

        // 只有一个非 IDR 切片
        val pFrame = startCode + byteArrayOf(0x41, (0x9A).toByte(), 0x02)
        assertFalse(VideoCodecFormat.isKeyframe(pFrame, VideoCodecKind.H264))
    }

    @Test
    fun `unknown codec never claims a keyframe`() {
        assertFalse(VideoCodecFormat.isKeyframe(h264Sample(), VideoCodecKind.UNKNOWN))
    }
}
