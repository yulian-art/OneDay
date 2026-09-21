package com.julien.feature_recording.recording

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.Micros
import com.julien.core_media.time.RecordingTimeline
import com.julien.core_media.time.SyncMarker
import com.julien.core_media.util.EpochClock
import com.julien.core_media.util.MonotonicClock
import com.julien.core_media.util.SystemEpochClock
import com.julien.core_media.util.SystemMonotonicClock
import com.julien.feature_recording.device.DeviceCaptureEvents
import com.julien.feature_recording.device.RecordingDeviceApi
import com.julien.feature_recording.model.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 录制控制器 —— 录制链路的唯一入口。
 *
 * ### 三条不可退让的约束（均出自方案文档）
 * 1. **命令 ≠ 事实**：`startCapture()` 返回成功只说明命令被接受，
 *    必须等 [DeviceCaptureEvents.onCaptureWorking] 才算「设备已确认录制状态」。
 *    这是 [RecordingState.Arming] 与 [RecordingState.Recording] 分开的原因。
 * 2. **录制与分析隔离**：本类不认识预览、解码、AI 的任何东西。
 *    文档要求「分析失败不应影响原始记录」，隔离是唯一可靠的实现方式。
 * 3. **异常必须显式**：[DeviceCaptureEvents.onCaptureError] 会转成
 *    [RecordingState.Error] 并保留「出错前是否已在录」，因为那决定要不要
 *    提示用户「素材可能不完整」。
 *
 * ### 状态机
 * ```
 * Idle ──start()──▶ Arming ──onCaptureWorking──▶ Recording
 *   ▲                  │                            │
 *   │                  │ onCaptureError             │ stop()
 *   │                  ▼                            ▼
 *   └──────────────── Error ◀─────── onCaptureError  Stopping
 *   ▲                                                 │
 *   └────────────── onCaptureFinished ◀───────────────┘
 * ```
 *
 * 另外处理一个真实场景：**相机自己按了录制键**。此时我们没发过命令，
 * 却会收到 `onCaptureWorking`，状态机把它当作外部发起的录制并建段，
 * 而不是忽略 —— 否则那段时间轴就没有归属，事件会错配。
 */
class RecordingController(
    private val device: RecordingDeviceApi,
    private val timeline: RecordingTimeline,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val epochClock: EpochClock = SystemEpochClock,
) {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** 已完成的片段 id，按完成顺序。 */
    private val _finishedSegments = MutableStateFlow<List<String>>(emptyList())
    val finishedSegments: StateFlow<List<String>> = _finishedSegments.asStateFlow()

    /** 最近一次录制结束时相机给出的原片路径。 */
    private val _lastFinishedFiles = MutableStateFlow<List<String>>(emptyList())
    val lastFinishedFiles: StateFlow<List<String>> = _lastFinishedFiles.asStateFlow()

    /** 本次会话内的段计数，用于生成稳定且可读的 segmentId。 */
    private var segmentCounter = 0

    /** 当前正在等待设备确认的段 id。 */
    private var pendingSegmentId: String? = null

    private var attached = false

    private val events = object : DeviceCaptureEvents {

        override fun onCaptureStarting() {
            Timber.d("device: capture starting")
        }

        override fun onCaptureWorking() {
            onDeviceConfirmedRecording()
        }

        override fun onCaptureStopping() {
            val current = _state.value
            if (current is RecordingState.Recording) {
                _state.value = RecordingState.Stopping(
                    segmentId = current.segmentId,
                    commandedAtUs = monotonicClock.nowUs(),
                )
            }
        }

        override fun onCaptureFinished(fileUrls: List<String>) {
            onDeviceFinished(fileUrls)
        }

        override fun onCaptureError(error: Throwable) {
            val wasConfirmed = _state.value.isDeviceConfirmed
            Timber.e(error, "device reported capture error (wasConfirmed=%s)", wasConfirmed)
            // 若出错前确实在录，时间轴要收尾，否则会留一个永不关闭的段
            val segmentId = currentSegmentId()
            if (segmentId != null) {
                timeline.closeSegment(segmentId, closedAtUs = monotonicClock.nowUs())
                pendingSegmentId = null
            }
            _state.value = RecordingState.Error(
                message = error.message ?: error.javaClass.simpleName,
                cause = error,
                wasDeviceConfirmed = wasConfirmed,
            )
        }

        override fun onElapsedChanged(raw: Long) {
            val current = _state.value
            if (current is RecordingState.Recording) {
                _state.value = current.copy(deviceReportedElapsedRaw = raw)
            }
        }

        override fun onCountChanged(count: Int) {
            val current = _state.value
            if (current is RecordingState.Recording) {
                _state.value = current.copy(deviceReportedCount = count)
            }
        }

        override fun onSubStatusChanged(subStatus: String) {
            val current = _state.value
            if (current is RecordingState.Recording) {
                _state.value = current.copy(deviceSubStatus = subStatus)
            }
        }
    }

    /** 注册相机状态监听。应在进入录制页时调用。 */
    fun attach() {
        if (attached) return
        device.registerCaptureEvents(events)
        attached = true
    }

    /** 注销监听。应在离开录制页时调用，避免后台泄漏。 */
    fun detach() {
        if (!attached) return
        device.unregisterCaptureEvents(events)
        attached = false
    }

    /**
     * 开始录制。
     *
     * `Result.success` 只表示**命令被接受**；是否真的在录要看 [state]。
     */
    suspend fun start(): Result<Unit> {
        val current = _state.value
        if (current is RecordingState.Recording) {
            return Result.success(Unit) // 已在录，幂等
        }
        if (current is RecordingState.Arming) {
            return Result.success(Unit) // 命令在途，不重复发
        }
        if (!device.isConnected()) {
            val error = RecordingState.Error("相机未连接，无法开始录制")
            _state.value = error
            return Result.failure(IllegalStateException(error.message))
        }

        val commandedAtUs = monotonicClock.nowUs()
        val segmentId = nextSegmentId()
        pendingSegmentId = segmentId
        _state.value = RecordingState.Arming(
            commandedAtUs = commandedAtUs,
            commandedAtEpochMs = epochClock.nowMs(),
        )

        return device.startCapture().onFailure { error ->
            Timber.e(error, "startCapture command failed")
            pendingSegmentId = null
            _state.value = RecordingState.Error(
                message = "开始录制失败: ${error.message ?: error.javaClass.simpleName}",
                cause = error,
                wasDeviceConfirmed = false,
            )
        }
    }

    /**
     * 停止录制。
     *
     * 同样地，返回成功只代表命令被接受；真正的收尾由
     * [DeviceCaptureEvents.onCaptureFinished] 决定。
     */
    suspend fun stop(): Result<Unit> {
        val current = _state.value
        if (current !is RecordingState.Recording && current !is RecordingState.Arming) {
            return Result.success(Unit) // 没在录，幂等
        }
        val segmentId = currentSegmentId()
        if (segmentId != null) {
            _state.value = RecordingState.Stopping(
                segmentId = segmentId,
                commandedAtUs = monotonicClock.nowUs(),
            )
        }
        return device.stopCapture().onFailure { error ->
            Timber.e(error, "stopCapture command failed")
            // 停录命令失败是最危险的情况：相机可能还在录，用户却以为停了。
            // 因此这里给出明确的错误，并保留 wasDeviceConfirmed 让 UI 提示核实。
            _state.value = RecordingState.Error(
                message = "停止录制失败: ${error.message ?: error.javaClass.simpleName}",
                cause = error,
                wasDeviceConfirmed = true,
            )
        }
    }

    /**
     * 与设备对账，修复「本地状态与相机实际状态不一致」。
     *
     * 方案文档的验收项「可靠性：断连、停录、重启后是否丢事件或错配」。
     * 触发时机建议：进入页面、连接恢复、以及每次 start/stop 之后。
     */
    suspend fun reconcile(): RecordingState {
        val workingResult = device.isWorking()
        val working = workingResult.getOrNull()
            ?: return _state.value.also {
                Timber.w(workingResult.exceptionOrNull(), "reconcile: isWorking failed")
            }

        val current = _state.value
        val believedRecording = current.isDeviceConfirmed

        return when {
            // 相机在录但我们不知道（例如用户按了相机上的键，或我们漏了事件）
            working && !believedRecording -> {
                Timber.i("reconcile: device is recording but state=%s, adopting", current)
                adoptExternalRecording()
                _state.value
            }

            // 相机停了但我们以为还在录（例如漏了 onCaptureFinish）
            !working && believedRecording -> {
                Timber.i("reconcile: device stopped but state=%s, closing segment", current)
                onDeviceFinished(emptyList())
                _state.value
            }

            else -> current
        }
    }

    /**
     * 登记一个可见同步事件，用于建立跨时钟映射。
     *
     * 刻意只接受「多域读数」而不是自己去猜测各域时间 ——
     * 方案文档要求「保存原始时间，以可见同步事件建立映射」，
     * 而各域的当前值只有对应链路才知道（预览域在预览链路）。
     * 因此由调用方组装 readings，本类只负责挂到正确的时间轴上。
     *
     * @return 是否成功登记（没有打开中的片段时会失败）
     */
    fun recordSyncMarker(
        label: String,
        readings: Map<ClockDomain, Micros>,
        id: String = "sync-${epochClock.nowMs()}-$label",
    ): Boolean {
        if (readings.size < 2) {
            Timber.w("sync marker '%s' needs readings from >=2 domains, got %d", label, readings.size)
            return false
        }
        val recorded = timeline.recordSyncMarker(
            SyncMarker(id = id, label = label, readings = readings),
        )
        if (!recorded) {
            Timber.w("sync marker '%s' dropped: no open segment", label)
        }
        return recorded
    }

    /**
     * 给已登记的同步事件补一个域的读数（典型场景：分析链路在原片里找到了那次拍手）。
     *
     * 这是必须的能力而不是可选优化：打点瞬间我们只可能知道手机时刻与预览时间戳，
     * 原片位置要事后才能确定。补齐之后 preview→original 的映射才真正成立。
     */
    fun completeSyncMarker(
        markerId: String,
        domain: ClockDomain,
        rawUs: Micros,
    ): Boolean {
        val ok = timeline.completeSyncMarker(markerId, domain, rawUs)
        if (ok) {
            Timber.i("sync marker %s completed with %s=%d", markerId, domain, rawUs)
        } else {
            Timber.w("sync marker %s not found, cannot complete", markerId)
        }
        return ok
    }

    /** 当前片段上已登记、但还没有原片读数的同步事件 id。 */
    fun pendingSyncMarkerIds(): List<String> =
        timeline.currentSyncMarkers()
            .filter { it[ClockDomain.ORIGINAL_FILE] == null }
            .map { it.id }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun onDeviceConfirmedRecording() {
        val confirmedAtUs = monotonicClock.nowUs()
        val current = _state.value

        if (current is RecordingState.Recording) {
            // 重复确认，忽略
            return
        }

        val segmentId = (current as? RecordingState.Arming)
            ?.let { pendingSegmentId }
            ?: pendingSegmentId
            // 没有任何在途命令却收到「正在录制」→ 外部（相机本体）发起的录制
            ?: nextSegmentId()

        val commandedAtUs = (current as? RecordingState.Arming)?.commandedAtUs ?: confirmedAtUs

        // 只有走到这一步才建段：原片时间轴从这一刻起才有意义
        timeline.openSegment(
            segmentId = segmentId,
            phoneOpenedAtUs = commandedAtUs,
            captureConfirmedAtUs = confirmedAtUs,
        )
        pendingSegmentId = null

        _state.value = RecordingState.Recording(
            segmentId = segmentId,
            commandedAtUs = commandedAtUs,
            deviceConfirmedAtUs = confirmedAtUs,
        )
        Timber.i(
            "device confirmed recording: segment=%s commandLatency=%dus",
            segmentId,
            confirmedAtUs - commandedAtUs,
        )
    }

    private fun adoptExternalRecording() {
        val confirmedAtUs = monotonicClock.nowUs()
        val segmentId = nextSegmentId()
        timeline.openSegment(
            segmentId = segmentId,
            phoneOpenedAtUs = null, // 我们没发过命令，不知道确切的开始时刻
            captureConfirmedAtUs = confirmedAtUs,
        )
        _state.value = RecordingState.Recording(
            segmentId = segmentId,
            commandedAtUs = confirmedAtUs,
            deviceConfirmedAtUs = confirmedAtUs,
        )
    }

    private fun onDeviceFinished(fileUrls: List<String>) {
        val segmentId = currentSegmentId()
        // 只有在时间轴上**确实建立过**这一段才登记「完成」。
        // 若在 Arming 阶段（命令已发、设备尚未确认）就停录，段从未被 open，
        // 此时若照样记入 finishedSegments，就会出现一个查不到映射的「幽灵片段」，
        // 界面显示的「已完成 N 段」也会比实际多。
        if (segmentId != null && timeline.allSegments.any { it.id == segmentId }) {
            timeline.closeSegment(segmentId, closedAtUs = monotonicClock.nowUs())
            _finishedSegments.value = _finishedSegments.value + segmentId
        } else if (segmentId != null) {
            Timber.i("capture finished for segment %s which was never opened (stopped while arming)", segmentId)
        }
        pendingSegmentId = null
        if (fileUrls.isNotEmpty()) {
            _lastFinishedFiles.value = fileUrls
        }
        _state.value = RecordingState.Idle
        Timber.i("capture finished: files=%d", fileUrls.size)
    }

    /** 当前状态对应的段 id（Arming 阶段取在途 id）。 */
    private fun currentSegmentId(): String? = when (val s = _state.value) {
        is RecordingState.Recording -> s.segmentId
        is RecordingState.Stopping -> s.segmentId
        is RecordingState.Arming -> pendingSegmentId
        else -> null
    }

    private fun nextSegmentId(): String = "seg-${segmentCounter++}"

    /** 清空错误状态，回到 Idle 以便重试。 */
    fun clearError() {
        if (_state.value is RecordingState.Error) {
            _state.value = RecordingState.Idle
        }
    }
}
