package com.julien.core_media.model

/**
 * 跨时钟域的标识。
 *
 * 方案文档「08 整个流程最难的八个环节 · 跨时钟对应」指出：
 * 「按钮、手机、预览、原片时间不同 —— 保存原始时间，以可见同步事件建立映射」。
 *
 * 本枚举刻意把「原始时间」分域保存，任何跨域换算都必须经过
 * [com.julien.core_media.time.RecordingTimeline]，禁止直接相减。
 */
enum class ClockDomain {
    /**
     * 相机 SD 卡原片的时间轴（相对单个原片文件起点，微秒）。
     * 这是最终交付物所在的域 —— 事件跳转、导出区间都以它为基准。
     */
    ORIGINAL_FILE,

    /**
     * 预览流帧时间戳（[com.arashivision.sdk.camera.api.preview.PreviewStreamFrame.getTimestamp] 原样值）。
     * 起点不可假设为 0，也不保证与相机内部录制时间同源，必须靠锚点映射。
     */
    PREVIEW_STREAM,

    /**
     * 手机单调时钟（`SystemClock.elapsedRealtimeNanos` 折算为微秒）。
     * 不受系统时间调整影响，适合测量时长与漂移。
     */
    PHONE_MONOTONIC,

    /**
     * 手机墙上时钟（`System.currentTimeMillis` 折算为微秒）。
     * 仅用于与外部记录（日志、后端）对齐，不适合测时长。
     */
    PHONE_EPOCH,

    /**
     * 确认夹（XIAO nRF52840）上报的按键时刻。
     * 方案文档特别提醒：「不能把腰部运动峰值当作宠物互动判断依据」——
     * 因此该域只用于标记示例与反馈，绝不作为事件判定输入。
     */
    ACCESSORY_BUTTON,

    /**
     * 相机自报的录制时间（如 [com.arashivision.sdk.camera.api.param.listener.CaptureStatusListener.onCaptureTimeChanged]
     * 的秒数、`CameraCaptureStatus.captureTime`）。粒度粗，只能作粗对齐。
     */
    CAMERA_REPORTED,
}

/**
 * 映射置信度 —— 对应方案文档的「渐进式确定性」与「待确认」状态。
 *
 * 文档要求「明确告知用户每个阶段的确定性」，且
 * 「抽样的太稀就应降低确定性」「遮挡时进入待确认」。
 * 因此任何跨域换算结果都必须带置信度，不允许返回一个貌似精确的裸时间。
 */
enum class MappingConfidence {
    /** 有 ≥3 个锚点且残差很小，可直接用于事件跳转。 */
    CONFIRMED,

    /** 有 ≥2 个锚点、残差可接受，可用于跳转但应留出容差。 */
    PROBABLE,

    /** 只有 1 个锚点，或锚点来源是「命令发出」这类弱证据；仅供参考。 */
    UNCERTAIN,

    /** 无可用锚点，或目标时间落在所有片段之外。 */
    UNKNOWN,
    ;

    /** 该置信度是否足以支撑「跳到原片位置」这类用户可见操作。 */
    val isActionable: Boolean
        get() = this == CONFIRMED || this == PROBABLE
}
