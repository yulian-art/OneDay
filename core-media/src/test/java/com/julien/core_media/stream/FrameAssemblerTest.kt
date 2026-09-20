package com.julien.core_media.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖方案文档「05」点名的失败模式：
 * 「一个编码帧可能分多次回调，应按同一时间戳合并分片；
 *   错误地把单独分片送入解码器可能造成绿屏或坏帧」。
 */
class FrameAssemblerTest {

    private val config = FrameAssemblerConfig(
        maxFragmentsPerFrame = 8,
        staleFlushUs = 100_000,
    )

    private fun frag(ts: Long, bytes: ByteArray, at: Long = ts, type: MediaStreamType = MediaStreamType.VIDEO) =
        EncodedFragment(data = bytes, timestampUs = ts, type = type, receivedAtUs = at)

    @Test
    fun `fragments sharing a timestamp are merged into one access unit`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        // 同一帧被拆成 3 个分片回调
        asm.add(frag(1000, byteArrayOf(1, 2)))
        asm.add(frag(1000, byteArrayOf(3, 4)))
        asm.add(frag(1000, byteArrayOf(5, 6)))
        assertEquals("must not emit before the frame is known complete", 0, out.size)

        // 下一个时间戳出现，才说明 1000 收完了
        asm.add(frag(2000, byteArrayOf(9)))

        assertEquals(1, out.size)
        val frame = out.first()
        assertEquals(1000L, frame.timestampUs)
        assertEquals("all three fragments must be in ONE unit", 3, frame.fragmentCount)
        assertTrue(frame.data.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(frame.forcedIncomplete)
    }

    @Test
    fun `feeding a fragment never produces a partial frame`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        // 只来一个分片就停住：flushAll 之前不应有任何输出
        asm.add(frag(500, byteArrayOf(7, 7)))
        assertEquals(0, out.size)

        asm.flushAll()
        assertEquals(1, out.size)
        // 没等到下一帧时间戳，所以完整性未经验证，必须如实标记
        assertTrue("forced flush must be flagged incomplete", out.first().forcedIncomplete)
    }

    @Test
    fun `flushStale only emits frames older than the timeout`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        // 时间单位是微秒；config.staleFlushUs = 100_000 (100ms)
        asm.add(frag(100, byteArrayOf(1), at = 1_000_000))
        asm.add(frag(200, byteArrayOf(2), at = 1_050_000)) // 使帧 100 完成

        // 距 1_050_000 只过了 50ms，未到 100ms 阈值
        assertEquals(0, asm.flushStale(nowUs = 1_100_000))
        // 过了 150ms，超时
        assertEquals(1, asm.flushStale(nowUs = 1_200_000))
        assertEquals(200L, out.last().timestampUs)
        assertTrue(out.last().forcedIncomplete)
    }

    @Test
    fun `out of order fragments are counted and not silently merged`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        asm.add(frag(200, byteArrayOf(2)))
        asm.add(frag(300, byteArrayOf(3))) // 200 完成
        asm.add(frag(250, byteArrayOf(9))) // 迟到，属于已发出的 200

        assertEquals(1L, asm.stats.outOfOrderFragments)
        // 迟到分片绝不能污染已发出的帧
        assertEquals(1, out.size)
        assertTrue(out.first().data.contentEquals(byteArrayOf(2)))
    }

    @Test
    fun `max fragments guard force emits a stuck frame`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val small = config.copy(maxFragmentsPerFrame = 3)
        val asm = FrameAssembler(small) { out += it }

        asm.add(frag(10, byteArrayOf(1)))
        asm.add(frag(10, byteArrayOf(2)))
        asm.add(frag(10, byteArrayOf(3)))
        // 正好等于上限仍视为合法帧，不应发出
        assertEquals(0, out.size)

        // 第 4 个分片才「超过」上限 → 强制发出，避免内存无界增长
        asm.add(frag(10, byteArrayOf(4)))
        assertEquals(1, out.size)
        assertTrue(out.first().forcedIncomplete)
        assertEquals(1L, asm.stats.framesForcedIncomplete)
        assertEquals(4, asm.stats.maxFragmentsPerFrame)
    }

    @Test
    fun `different stream types are assembled independently`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        asm.add(frag(100, byteArrayOf(1), type = MediaStreamType.VIDEO))
        asm.add(frag(100, byteArrayOf(2), type = MediaStreamType.VIDEO_L))
        asm.add(frag(200, byteArrayOf(3), type = MediaStreamType.VIDEO))
        asm.add(frag(200, byteArrayOf(4), type = MediaStreamType.VIDEO_L))

        assertEquals(2, out.size)
        assertEquals(1, out.count { it.type == MediaStreamType.VIDEO })
        assertEquals(1, out.count { it.type == MediaStreamType.VIDEO_L })
    }

    @Test
    fun `stats reflect the real fragmentation ratio`() {
        val asm = FrameAssembler(config) { }
        asm.add(frag(1, byteArrayOf(1)))
        asm.add(frag(1, byteArrayOf(1)))
        asm.add(frag(2, byteArrayOf(1)))
        asm.add(frag(3, byteArrayOf(1))) // 使 ts=2 完成

        val s = asm.stats
        assertEquals(4L, s.fragmentsIn)
        assertEquals(2L, s.accessUnitsOut)
        assertEquals(2.0, s.avgFragmentsPerFrame, 0.001)
        assertEquals(2, s.maxFragmentsPerFrame)
    }

    @Test
    fun `assembly latency is measured across fragment callbacks`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        asm.add(frag(100, byteArrayOf(1), at = 10_000))
        asm.add(frag(100, byteArrayOf(2), at = 13_000))
        asm.add(frag(200, byteArrayOf(3), at = 13_100))

        assertEquals(3_000L, out.first().assemblyLatencyUs)
        assertEquals(3_000L, asm.stats.maxAssemblyLatencyUs)
    }

    /**
     * 固定一个**不变式**：每个流类型的待定帧恒 ≤1，且本层从不丢帧。
     *
     * 入队只有两条路径 —— 「队列为空时入队」与「先 removeLast 再入队」——
     * 所以队列长度不可能是 2 或更多。这意味着合并层不需要背压，
     * 有界队列与丢帧策略在 `DecoderAdmissionPolicy` 那一层。
     *
     * （此前的 `maxPendingFrames` 配置与「队列溢出丢整帧」分支声称提供背压，
     * 但对任何 ≥1 的取值都不可达，取 0 时还会丢掉刚入队的最新帧 —— 已删除。）
     */
    @Test
    fun `pending queue never exceeds one frame and nothing is dropped`() {
        val out = mutableListOf<AssembledAccessUnit>()
        val asm = FrameAssembler(config) { out += it }

        repeat(200) { i ->
            asm.add(frag(i.toLong(), byteArrayOf(i.toByte())))
            assertTrue(
                "pending queue length must stay <= 1, was ${asm.pendingFrameCount}",
                asm.pendingFrameCount <= 1,
            )
        }

        // 没有任何一帧因为「溢出」被丢
        assertEquals(0L, asm.stats.framesForcedIncomplete)
        // 前 199 帧因下一帧到来而完整发出，最后 1 帧仍在待定
        assertEquals(199, out.size)
        // 待定的那一帧可以正常收尾
        assertEquals(1, asm.flushAll())
        assertEquals(200, out.size)
    }
}
