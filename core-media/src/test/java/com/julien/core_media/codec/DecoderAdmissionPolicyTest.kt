package com.julien.core_media.codec

import com.julien.core_media.stream.AssembledAccessUnit
import com.julien.core_media.stream.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖方案文档「08」第 1 条的硬要求：
 * 「独立解码、有界队列；不能随意丢编码分片」。
 *
 * 这里验证「丢整帧 + 关键帧重同步」这条策略是对的，
 * 并且证明我们**从不**产出半帧。
 */
class DecoderAdmissionPolicyTest {

    private fun unit(ts: Long, bytes: Int = 8) = AssembledAccessUnit(
        data = ByteArray(bytes) { ts.toByte() },
        timestampUs = ts,
        type = MediaStreamType.VIDEO,
        fragmentCount = 1,
        firstReceivedAtUs = ts,
        lastReceivedAtUs = ts,
    )

    @Test
    fun `first frame must be a keyframe`() {
        val policy = DecoderAdmissionPolicy(capacity = 4)
        assertTrue("must wait for keyframe at startup", policy.needsKeyframe)

        val rejected = policy.admit(unit(1), isKeyframe = false)
        assertFalse(rejected.accepted)
        assertEquals(1L, policy.droppedAwaitingKeyframe)
        assertEquals(0, policy.size)

        val accepted = policy.admit(unit(2), isKeyframe = true)
        assertTrue(accepted.accepted)
        assertFalse(policy.needsKeyframe)
    }

    @Test
    fun `queue overflow drops a WHOLE frame never a fragment`() {
        val policy = DecoderAdmissionPolicy(capacity = 2)
        policy.admit(unit(1, bytes = 100), isKeyframe = true)
        policy.admit(unit(2, bytes = 100), isKeyframe = false)
        assertEquals(2, policy.size)

        // 第 3 帧进来时队满：必须丢掉队首那一整个 unit
        val decision = policy.admit(unit(3, bytes = 100), isKeyframe = false)
        assertNotNull("an entire frame must be evicted", decision.evicted)
        assertEquals(1L, decision.evicted!!.timestampUs)
        // 丢帧后必须重新等关键帧，且当前非关键帧不能被收下
        assertTrue(policy.needsKeyframe)
        assertFalse(decision.accepted)
        assertEquals(1L, policy.evictedCount)
    }

    @Test
    fun `after eviction a keyframe restores decoding`() {
        val policy = DecoderAdmissionPolicy(capacity = 2)
        policy.admit(unit(1), isKeyframe = true)
        policy.admit(unit(2), isKeyframe = false)
        policy.admit(unit(3), isKeyframe = false) // 触发驱逐，进入恢复期

        assertTrue(policy.needsKeyframe)
        // 恢复期的普通帧被丢弃
        assertFalse(policy.admit(unit(4), isKeyframe = false).accepted)
        // 关键帧到来 → 恢复
        val recovered = policy.admit(unit(5), isKeyframe = true)
        assertTrue(recovered.accepted)
        assertFalse(policy.needsKeyframe)
    }

    @Test
    fun `evicted frame is always complete and intact`() {
        val policy = DecoderAdmissionPolicy(capacity = 1)
        val original = unit(42, bytes = 64)
        policy.admit(original, isKeyframe = true)

        val decision = policy.admit(unit(43), isKeyframe = true)
        assertEquals(original, decision.evicted)
        assertEquals("evicted unit must keep all its bytes", 64, decision.evicted!!.data.size)
    }

    @Test
    fun `poll returns frames in FIFO order`() {
        val policy = DecoderAdmissionPolicy(capacity = 4)
        policy.admit(unit(1), isKeyframe = true)
        policy.admit(unit(2), isKeyframe = false)
        policy.admit(unit(3), isKeyframe = false)

        assertEquals(1L, policy.poll()!!.timestampUs)
        assertEquals(2L, policy.poll()!!.timestampUs)
        assertEquals(3L, policy.poll()!!.timestampUs)
        assertNull(policy.poll())
        assertTrue(policy.isEmpty)
    }

    @Test
    fun `reset for new stream waits for a fresh keyframe`() {
        val policy = DecoderAdmissionPolicy(capacity = 4)
        policy.admit(unit(1), isKeyframe = true)
        assertFalse(policy.needsKeyframe)

        policy.resetForNewStream()
        assertTrue(policy.needsKeyframe)
        assertEquals(0, policy.size)
        assertFalse(policy.admit(unit(9), isKeyframe = false).accepted)
    }

    @Test
    fun `capacity of one still works`() {
        val policy = DecoderAdmissionPolicy(capacity = 1)
        assertTrue(policy.admit(unit(1), isKeyframe = true).accepted)
        val d = policy.admit(unit(2), isKeyframe = true)
        assertTrue(d.accepted)
        assertEquals(1L, d.evicted!!.timestampUs)
    }
}
