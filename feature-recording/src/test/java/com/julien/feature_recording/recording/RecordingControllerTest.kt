package com.julien.feature_recording.recording

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.time.RecordingTimeline
import com.julien.feature_recording.testing.FakeEpochClock
import com.julien.feature_recording.testing.FakeMonotonicClock
import com.julien.feature_recording.device.DeviceCaptureEvents
import com.julien.feature_recording.device.RecordingDeviceApi
import com.julien.feature_recording.model.RecordingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可控的假相机，让状态机可以脱离真机穷举验证。 */
private class FakeRecordingDeviceApi : RecordingDeviceApi {
    var connected = true
    var startResult: Result<Unit> = Result.success(Unit)
    var stopResult: Result<Unit> = Result.success(Unit)
    var workingResult: Result<Boolean> = Result.success(false)

    var startCalls = 0
    var stopCalls = 0

    private val listeners = mutableListOf<DeviceCaptureEvents>()

    override fun isConnected(): Boolean = connected
    override suspend fun isWorking(): Result<Boolean> = workingResult
    override suspend fun startCapture(): Result<Unit> {
        startCalls++
        return startResult
    }

    override suspend fun stopCapture(): Result<Unit> {
        stopCalls++
        return stopResult
    }

    override fun registerCaptureEvents(listener: DeviceCaptureEvents) {
        listeners += listener
    }

    override fun unregisterCaptureEvents(listener: DeviceCaptureEvents) {
        listeners -= listener
    }

    fun emitStarting() = listeners.toList().forEach { it.onCaptureStarting() }
    fun emitWorking() = listeners.toList().forEach { it.onCaptureWorking() }
    fun emitStopping() = listeners.toList().forEach { it.onCaptureStopping() }
    fun emitFinished(files: List<String> = emptyList()) =
        listeners.toList().forEach { it.onCaptureFinished(files) }

    fun emitError(t: Throwable) = listeners.toList().forEach { it.onCaptureError(t) }
    fun emitElapsed(raw: Long) = listeners.toList().forEach { it.onElapsedChanged(raw) }
    fun emitCount(c: Int) = listeners.toList().forEach { it.onCountChanged(c) }
    fun emitSubStatus(s: String) = listeners.toList().forEach { it.onSubStatusChanged(s) }
}

/**
 * 覆盖方案文档「04」的状态定义：
 * 「正在录制 → 设备已确认录制状态」，
 * 以及验收项「可靠性：断连、停录、重启后是否丢事件或错配」。
 */
class RecordingControllerTest {

    private val device = FakeRecordingDeviceApi()
    private val clock = FakeMonotonicClock(1_000_000)
    private val epoch = FakeEpochClock(1_700_000_000_000)
    private val timeline = RecordingTimeline()

    private fun controller() = RecordingController(
        device = device,
        timeline = timeline,
        monotonicClock = clock,
        epochClock = epoch,
    ).also { it.attach() }

    @Test
    fun `issuing start does NOT claim we are recording`() = runBlocking {
        val c = controller()
        assertEquals(RecordingState.Idle, c.state.value)

        c.start()

        // 命令已发出，但设备还没确认 —— 此时绝不能说「正在录制」
        assertTrue(c.state.value is RecordingState.Arming)
        assertFalse("must not claim device-confirmed before the device says so", c.state.value.isDeviceConfirmed)
        assertEquals(1, device.startCalls)
        assertFalse(timeline.isRecording)
    }

    @Test
    fun `device confirmation promotes to Recording and opens a segment`() = runBlocking {
        val c = controller()
        c.start()
        clock.advanceUs(150_000) // 设备 150ms 后才确认

        device.emitWorking()

        val s = c.state.value
        assertTrue(s is RecordingState.Recording)
        s as RecordingState.Recording
        assertTrue(s.isDeviceConfirmed)
        assertEquals(150_000L, s.commandLatencyUs)
        assertTrue("segment must open only after device confirmation", timeline.isRecording)
        assertEquals("seg-0", s.segmentId)
    }

    @Test
    fun `stop goes through Stopping and finish returns to Idle closing the segment`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking()

        c.stop()
        assertTrue(c.state.value is RecordingState.Stopping)

        device.emitFinished(listOf("/sdcard/VID_001.insv"))

        assertEquals(RecordingState.Idle, c.state.value)
        assertFalse(timeline.isRecording)
        assertEquals(listOf("seg-0"), c.finishedSegments.value)
        assertEquals(listOf("/sdcard/VID_001.insv"), c.lastFinishedFiles.value)
        assertEquals(1, device.stopCalls)
    }

    @Test
    fun `capture error surfaces and keeps whether we were already recording`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking()
        assertTrue(c.state.value.isDeviceConfirmed)

        device.emitError(IllegalStateException("SD card full"))

        val s = c.state.value
        assertTrue(s is RecordingState.Error)
        s as RecordingState.Error
        assertTrue("UI must be able to warn that footage may be incomplete", s.wasDeviceConfirmed)
        assertTrue(s.message.contains("SD card full"))
        // 时间轴必须收尾，否则会留一个永不关闭的段
        assertFalse(timeline.isRecording)
    }

    @Test
    fun `start on a disconnected camera fails loudly`() = runBlocking {
        device.connected = false
        val c = controller()

        val result = c.start()

        assertTrue(result.isFailure)
        assertTrue(c.state.value is RecordingState.Error)
        assertEquals(0, device.startCalls)
    }

    @Test
    fun `start is idempotent while the command is in flight`() = runBlocking {
        val c = controller()
        c.start()
        c.start()
        c.start()
        assertEquals("must not spam the camera", 1, device.startCalls)
    }

    @Test
    fun `external recording started on the camera itself is adopted`() = runBlocking {
        val c = controller()
        // 我们没发过任何命令
        assertEquals(RecordingState.Idle, c.state.value)

        device.emitWorking()

        val s = c.state.value
        assertTrue("must adopt, otherwise the timeline has an orphan stretch", s is RecordingState.Recording)
        assertTrue(timeline.isRecording)
    }

    @Test
    fun `reconcile adopts a recording we did not know about`() = runBlocking {
        val c = controller()
        device.workingResult = Result.success(true)

        c.reconcile()

        assertTrue(c.state.value.isDeviceConfirmed)
        assertTrue(timeline.isRecording)
    }

    @Test
    fun `reconcile closes a segment when the device already stopped`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking()
        assertTrue(timeline.isRecording)

        // 模拟漏掉了 onCaptureFinish
        device.workingResult = Result.success(false)
        c.reconcile()

        assertEquals(RecordingState.Idle, c.state.value)
        assertFalse("must not leave a dangling open segment", timeline.isRecording)
        assertEquals(listOf("seg-0"), c.finishedSegments.value)
    }

    @Test
    fun `reconcile is a no-op when state already matches the device`() = runBlocking {
        val c = controller()
        device.workingResult = Result.success(false)
        c.reconcile()
        assertEquals(RecordingState.Idle, c.state.value)
        assertFalse(timeline.isRecording)
    }

    @Test
    fun `device reported elapsed and count are carried into state without unit guessing`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking()

        device.emitElapsed(7L)
        device.emitCount(2)
        device.emitSubStatus("RECORDING")

        val s = c.state.value as RecordingState.Recording
        assertEquals("raw value must be preserved untouched", 7L, s.deviceReportedElapsedRaw)
        assertEquals(2, s.deviceReportedCount)
        assertEquals("RECORDING", s.deviceSubStatus)
    }

    @Test
    fun `elapsed is computed from our own monotonic clock`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking()
        val confirmedAt = (c.state.value as RecordingState.Recording).deviceConfirmedAtUs

        clock.advanceUs(2_500_000)

        val s = c.state.value as RecordingState.Recording
        assertEquals(2_500_000L, s.elapsedUs(clock.nowUs()))
        assertEquals(confirmedAt + 2_500_000L, clock.nowUs())
    }

    @Test
    fun `two recordings produce two independently mapped segments`() = runBlocking {
        val c = controller()

        c.start(); device.emitWorking()
        c.recordSyncMarker(
            label = "clap-1",
            readings = mapOf(
                ClockDomain.PREVIEW_STREAM to 100L,
                ClockDomain.ORIGINAL_FILE to 0L,
            ),
        )
        c.stop(); device.emitFinished()

        clock.advanceUs(5_000_000)

        c.start(); device.emitWorking()
        c.recordSyncMarker(
            label = "clap-2",
            readings = mapOf(
                ClockDomain.PREVIEW_STREAM to 9_000_000L,
                ClockDomain.ORIGINAL_FILE to 0L,
            ),
        )
        c.stop(); device.emitFinished()

        assertEquals(listOf("seg-0", "seg-1"), c.finishedSegments.value)
        assertEquals(2, timeline.allSegments.size)
        assertTrue(timeline.allSegments.all { it.isClosed })
    }

    @Test
    fun `sync marker without an open segment is rejected`() = runBlocking {
        val c = controller()
        val ok = c.recordSyncMarker(
            label = "clap",
            readings = mapOf(
                ClockDomain.PREVIEW_STREAM to 1L,
                ClockDomain.ORIGINAL_FILE to 1L,
            ),
        )
        assertFalse(ok)
    }

    @Test
    fun `sync marker with only one domain is rejected`() = runBlocking {
        val c = controller()
        c.start(); device.emitWorking()
        val ok = c.recordSyncMarker(
            label = "clap",
            readings = mapOf(ClockDomain.PREVIEW_STREAM to 1L),
        )
        assertFalse("one domain cannot establish a mapping", ok)
    }

    @Test
    fun `sync marker is attached to the correct segment`() = runBlocking {
        val c = controller()
        c.start(); device.emitWorking()
        assertTrue(
            c.recordSyncMarker(
                label = "clap-1",
                readings = mapOf(
                    ClockDomain.PREVIEW_STREAM to 500L,
                    ClockDomain.ORIGINAL_FILE to 1_000L,
                ),
            ),
        )
        c.stop(); device.emitFinished()

        assertEquals(1, timeline.allSegments.first().syncMarkers.size)
        assertEquals("clap-1", timeline.allSegments.first().syncMarkers.first().label)
    }

    @Test
    fun `stop command failure keeps warning that the camera may still be recording`() = runBlocking {
        val c = controller()
        c.start(); device.emitWorking()

        device.stopResult = Result.failure(IllegalStateException("io error"))
        c.stop()

        val s = c.state.value
        assertTrue(s is RecordingState.Error)
        assertTrue(
            "stopping failure is dangerous: user may think it stopped",
            (s as RecordingState.Error).wasDeviceConfirmed,
        )
    }

    @Test
    fun `clearError returns to Idle so the user can retry`() = runBlocking {
        device.connected = false
        val c = controller()
        c.start()
        assertTrue(c.state.value is RecordingState.Error)

        c.clearError()
        assertEquals(RecordingState.Idle, c.state.value)
    }

    @Test
    fun `detach unregisters so no events leak after leaving the screen`() = runBlocking {
        val c = controller()
        c.detach()
        device.emitWorking()
        assertEquals("detached controller must not react", RecordingState.Idle, c.state.value)
    }

    @Test
    fun `timeline description is available for acceptance evidence`() = runBlocking {
        val c = controller()
        c.start(); device.emitWorking()
        c.recordSyncMarker(
            label = "clap",
            readings = mapOf(
                ClockDomain.PREVIEW_STREAM to 0L,
                ClockDomain.ORIGINAL_FILE to 0L,
            ),
        )
        val text = timeline.describe()
        assertNotNull(text)
        assertTrue(text.contains("seg-0"))
    }

    @Test
    fun `segment ids increment across recordings`() = runBlocking {
        val c = controller()

        c.start(); device.emitWorking()
        assertEquals("seg-0", (c.state.value as RecordingState.Recording).segmentId)
        c.stop(); device.emitFinished()

        clock.advanceUs(1_000_000)

        c.start(); device.emitWorking()
        assertEquals("seg-1", (c.state.value as RecordingState.Recording).segmentId)
        c.stop(); device.emitFinished()

        c.start(); device.emitWorking()
        assertEquals("seg-2", (c.state.value as RecordingState.Recording).segmentId)
    }

    /**
     * 在 `Arming`（命令已发、设备尚未确认）阶段就停录时，
     * 时间轴上从未建立过该段，因此**不应**把它登记为「已完成」。
     *
     * 修复前这里会产生一个「幽灵片段」：`finishedSegments` 里有一个
     * `allSegments` 中不存在的 id，界面显示的「已完成 N 段」比实际多一段，
     * 下游据此查时间映射也会一无所获。
     */
    @Test
    fun `stopping while still arming does not register a phantom finished segment`() = runBlocking {
        val c = controller()

        c.start()
        assertTrue(c.state.value is RecordingState.Arming)
        // 设备一直没确认，此时用户点了停止
        c.stop()
        device.emitFinished()

        assertEquals(RecordingState.Idle, c.state.value)
        // 时间轴上根本没有这一段
        assertEquals(0, timeline.allSegments.size)
        // 因此也不应被计为「完成」
        assertEquals(
            "a segment that was never opened must not be reported as finished",
            emptyList<String>(),
            c.finishedSegments.value,
        )
    }

    @Test
    fun `a confirmed segment is still registered as finished`() = runBlocking {
        val c = controller()
        c.start()
        device.emitWorking() // 设备确认 → 段真正建立
        assertTrue(timeline.isRecording)

        c.stop()
        device.emitFinished()

        assertEquals(listOf("seg-0"), c.finishedSegments.value)
        assertEquals(1, timeline.allSegments.size)
        assertTrue(timeline.allSegments.first().isClosed)
    }
}
