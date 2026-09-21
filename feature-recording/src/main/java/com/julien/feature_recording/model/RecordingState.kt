package com.julien.feature_recording.model

import com.julien.core_media.model.Micros

/**
 * 录制状态机。
 *
 * ### 关键设计：命令与事实分离
 * 方案文档「04 系统结构与运行状态」强调，能告诉用户「正在录制」
 * 的前提是**设备已确认录制状态**，而不是我们发出过命令：
 *
 * | 状态 | 可以告诉用户什么 |
 * |---|---|
 * | 正在录制 | 设备已确认录制状态 |
 *
 * 同理文档也提醒：「预览里看清，不等于高清文件已经保存」。
 * 因此这里把 [Arming]（命令已发，等设备确认）与 [Recording]（设备确认真在录）
 * 显式分成两个状态，UI 在这两种状态下必须说不同的话。
 */
sealed interface RecordingState {

    /** 未录制。 */
    data object Idle : RecordingState

    /**
     * 已发出开录命令，但**尚未**收到设备的录制状态确认。
     * 此时绝不能说「正在录制」。
     */
    data class Arming(
        val commandedAtUs: Micros,
        val commandedAtEpochMs: Long,
    ) : RecordingState

    /** 设备已确认正在录制。这是唯一可以说「正在录制」的状态。 */
    data class Recording(
        val segmentId: String,
        val commandedAtUs: Micros,
        /** 相机上报进入录制状态的手机单调时刻。 */
        val deviceConfirmedAtUs: Micros,
        /**
         * 相机自报的已录时长**原始值**
         * （[com.arashivision.sdk.camera.api.param.listener.CaptureStatusListener.onCaptureTimeChanged]）。
         *
         * 刻意保留原始值而不做单位换算：该字段的单位随机型/固件可能是秒或毫秒，
         * 猜错就是 1000 倍误差。UI 显示时长应当使用我们自己单调时钟算出的
         * [elapsedUs]，这个值只用于与相机交叉核对。
         */
        val deviceReportedElapsedRaw: Long = 0L,
        /** 相机自报的已录数量。 */
        val deviceReportedCount: Int = 0,
        /** 相机自报的子状态名，原样保留用于诊断。 */
        val deviceSubStatus: String? = null,
    ) : RecordingState {
        /** 命令发出 → 设备确认的延迟。这个数字本身就是「响应性」的验收项。 */
        val commandLatencyUs: Micros get() = deviceConfirmedAtUs - commandedAtUs

        /**
         * 以**本机单调时钟**计算的已录时长。
         * 不用相机上报值 —— 见 [deviceReportedElapsedRaw] 的说明。
         */
        fun elapsedUs(nowUs: Micros): Micros = nowUs - deviceConfirmedAtUs
    }

    /** 已发出停录命令，等待设备收尾（写文件、生成原片）。 */
    data class Stopping(
        val segmentId: String,
        val commandedAtUs: Micros,
    ) : RecordingState

    /** 出错。录制链路的错误必须显式暴露，不能静默。 */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        /** 出错前是否已经确认在录 —— 决定要不要提示用户「素材可能不完整」。 */
        val wasDeviceConfirmed: Boolean = false,
    ) : RecordingState

    /**
     * 是否可以对外宣称「正在录制」。
     * UI 与日志都应以此为准，而不是自行判断。
     */
    val isDeviceConfirmed: Boolean get() = this is Recording

    /** 是否有录制命令在途（含等待确认与等待收尾）。 */
    val isBusy: Boolean
        get() = this is Arming || this is Recording || this is Stopping
}
