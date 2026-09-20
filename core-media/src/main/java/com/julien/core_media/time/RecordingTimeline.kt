package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.MappingConfidence
import com.julien.core_media.model.Micros
import com.julien.core_media.model.TimeRangeUs

/**
 * 一个录制片段的元数据与映射。
 *
 * 方案文档要求「停录、重启、分段分别处理」，理由是：
 * 每次停录再开录都会新建一个原片文件，原片时间轴**重新从 0 开始**，
 * 相机的编码器也会重置。若把整场会话当成一条时间轴，第二段之后的事件
 * 全会跳错位置。因此每段独立持有自己的 [LinearTimeMap]，段与段之间不做插值。
 *
 * @param index        片段序号，从 0 开始。
 * @param phoneOpenedAtUs  本段「开录命令发出」的手机单调时刻（弱证据，仅作兜底锚点）。
 * @param captureConfirmedAtUs 相机确认进入录制状态的手机单调时刻。
 * @param cameraReportedStartUs 相机自报的本段起点（若有）。
 * @param syncMarkers  落在本段内的可见同步事件。
 */
data class TimeSegment(
    val id: String,
    val index: Int,
    val phoneOpenedAtUs: Micros?,
    val captureConfirmedAtUs: Micros?,
    val closedAtUs: Micros?,
    val cameraReportedStartUs: Micros?,
    val syncMarkers: List<SyncMarker>,
) {
    /** 本段是否已经结束（用于判断能否安全建立最终映射）。 */
    val isClosed: Boolean get() = closedAtUs != null

    /**
     * 本段的映射表，按域惰性构建。
     *
     * 锚点来源分两层：
     * 1. 可见同步事件（强证据）
     * 2. 命令发出 / 相机自报（弱证据，只在没有强证据时兜底）
     */
    fun mapFor(
        domain: ClockDomain,
        referenceDomain: ClockDomain = ClockDomain.ORIGINAL_FILE,
    ): LinearTimeMap? = LinearTimeMap.fit(domain, anchorsFor(domain, referenceDomain))

    /**
     * 收集本段在某域上的锚点，供 [LinearTimeMap.fit]。
     *
     * 分两级证据：
     * 1. **强证据**：可见同步事件（同一物理瞬间在两个域都有读数），可真正解出漂移；
     * 2. **弱兜底**：没有任何同步事件时，退化为「手机开录时刻 ≈ 原片 0 点」的假设。
     *
     * 第 2 级是刻意的妥协：方案文档要求「允许不确定」，但若完全不给映射，
     * 事件跳转就彻底不可用。所以我们给出一个**显式标记为 UNCERTAIN** 的猜测，
     * 而不是假装没有这个问题。预览域没有这一级兜底 ——
     * 因为预览时间戳与原片之间没有任何可假设的共同起点。
     */
    fun anchorsFor(
        domain: ClockDomain,
        referenceDomain: ClockDomain = ClockDomain.ORIGINAL_FILE,
    ): List<TimeAnchor> {
        if (domain == referenceDomain) return emptyList()

        val strong = syncMarkers.mapNotNull { marker ->
            val raw = marker[domain] ?: return@mapNotNull null
            val reference = marker[referenceDomain] ?: return@mapNotNull null
            TimeAnchor(
                domain = domain,
                rawUs = raw,
                referenceUs = reference,
                source = AnchorSource.VISIBLE_SYNC_EVENT,
                note = "segment[$id]/${marker.label}",
            )
        }
        if (strong.isNotEmpty()) return strong

        // ---- 弱兜底：只用手机侧时钟域，且必须有「开录时刻」可用 ----
        if (domain != ClockDomain.PHONE_MONOTONIC && domain != ClockDomain.PHONE_EPOCH) {
            return emptyList()
        }
        val phoneAnchor = captureConfirmedAtUs ?: phoneOpenedAtUs ?: return emptyList()
        val source = if (captureConfirmedAtUs != null) {
            AnchorSource.DEVICE_REPORTED
        } else {
            AnchorSource.COMMAND_ISSUE
        }
        return listOf(
            TimeAnchor(
                domain = domain,
                rawUs = phoneAnchor,
                // 假设：相机确认录制的那一刻 ≈ 原片时间轴 0
                referenceUs = 0L,
                source = source,
                note = "segment[$id]/assumed-zero-alignment",
            ),
        )
    }
}

/**
 * 整场会话的时间轴：管理多个 [TimeSegment]，并把任意域的时间换算到原片域。
 *
 * 这是录制与媒体侧对「时间映射」的唯一入口。上层的候选事件、
 * 片段边界、导出区间都必须经它换算，禁止自行相减 ——
 * 因为按钮、手机、预览、原片分属四个不同时钟（方案文档「08」第 2 条）。
 */
class RecordingTimeline {

    private val segments = mutableListOf<TimeSegment>()
    private val openMarkers = mutableMapOf<String, MutableList<SyncMarker>>()

    /** 当前处于打开状态的片段 id。null 表示尚未开录。 */
    var openSegmentId: String? = null
        private set

    /**
     * 全部片段。
     *
     * 打开中的片段会把**当前已登记**的同步事件一并带上 ——
     * 否则「边录边分析」就拿不到映射：候选事件需要在录制过程中就换算到原片位置，
     * 而不是等停录之后。这是文档「能否边录边分析」那一条的前置条件。
     */
    val allSegments: List<TimeSegment> get() = segments.map { effective(it) }

    /**
     * 把打开中的片段的实时同步事件注入视图。
     * 已关闭的片段其事件早已固化在自己的 `syncMarkers` 里，无需处理。
     */
    private fun effective(segment: TimeSegment): TimeSegment {
        val live = openMarkers[segment.id] ?: return segment
        if (live.isEmpty()) return segment
        return segment.copy(syncMarkers = live.toList())
    }

    val isRecording: Boolean get() = openSegmentId != null

    /**
     * 开一个新片段。**每次相机真正开始录制时都必须调用**，
     * 以便原片时间轴从 0 重新计数。
     */
    fun openSegment(
        segmentId: String,
        phoneOpenedAtUs: Micros?,
        captureConfirmedAtUs: Micros? = null,
        cameraReportedStartUs: Micros? = null,
    ): String {
        // 若上一段未正常关闭（例如断连、崩溃），先补一个关闭，避免时间轴出现无主空洞
        openSegmentId?.let { previous ->
            if (segments.none { it.id == previous && it.isClosed }) {
                closeSegment(previous, closedAtUs = phoneOpenedAtUs)
            }
        }
        openMarkers[segmentId] = mutableListOf()
        segments += TimeSegment(
            id = segmentId,
            index = segments.size,
            phoneOpenedAtUs = phoneOpenedAtUs,
            captureConfirmedAtUs = captureConfirmedAtUs,
            closedAtUs = null,
            cameraReportedStartUs = cameraReportedStartUs,
            syncMarkers = emptyList(),
        )
        openSegmentId = segmentId
        return segmentId
    }

    fun closeSegment(segmentId: String, closedAtUs: Micros?) {
        val idx = segments.indexOfFirst { it.id == segmentId }
        if (idx < 0) return
        val markers = openMarkers.remove(segmentId).orEmpty()
        segments[idx] = segments[idx].copy(
            closedAtUs = closedAtUs,
            syncMarkers = markers.toList(),
        )
        if (openSegmentId == segmentId) openSegmentId = null
    }

    /**
     * 把一次可见同步事件登记到当前打开的片段。
     * 没有打开片段时丢弃并返回 false（避免把锚点挂到错误的原片时间轴上）。
     */
    fun recordSyncMarker(marker: SyncMarker): Boolean {
        val segmentId = openSegmentId ?: return false
        openMarkers.getOrPut(segmentId) { mutableListOf() }.add(marker)
        return true
    }

    /**
     * 给已登记的同步事件**补一个域的读数**。
     *
     * 这是「可见同步事件」在真实流程里的必然形态：
     * 用户拍手的那一刻，我们只可能立刻拿到手机时刻与预览流时间戳；
     * 这个拍手在**原片**里的位置，要等分析链路在原片中看到它才知道。
     * 所以映射是分两步成立的，不能强迫调用方在打点瞬间凑齐所有域。
     *
     * @return 是否找到并更新了该事件（含已关闭的片段）
     */
    fun completeSyncMarker(
        markerId: String,
        domain: ClockDomain,
        rawUs: Micros,
    ): Boolean {
        // 先找还开着的片段
        for ((segmentId, markers) in openMarkers) {
            val idx = markers.indexOfFirst { it.id == markerId }
            if (idx >= 0) {
                val old = markers[idx]
                markers[idx] = old.copy(readings = old.readings + (domain to rawUs))
                return true
            }
        }
        // 再找已关闭的片段：补录往往发生在停录之后
        for (i in segments.indices) {
            val seg = segments[i]
            val idx = seg.syncMarkers.indexOfFirst { it.id == markerId }
            if (idx >= 0) {
                val old = seg.syncMarkers[idx]
                val updated = seg.syncMarkers.toMutableList().also {
                    it[idx] = old.copy(readings = old.readings + (domain to rawUs))
                }
                segments[i] = seg.copy(syncMarkers = updated)
                return true
            }
        }
        return false
    }

    /** 按 id 查找一个已登记的同步事件。 */
    fun findSyncMarker(markerId: String): SyncMarker? {
        openMarkers.values.forEach { markers ->
            markers.firstOrNull { it.id == markerId }?.let { return it }
        }
        segments.forEach { seg ->
            seg.syncMarkers.firstOrNull { it.id == markerId }?.let { return it }
        }
        return null
    }

    /** 当前打开片段已登记的同步事件。 */
    fun currentSyncMarkers(): List<SyncMarker> =
        openSegmentId?.let { openMarkers[it]?.toList() }.orEmpty()

    /**
     * 把 [domain] 域的 [rawUs] 换算到原片域。
     *
     * 片段选择策略：优先选「该域上确实有锚点覆盖 rawUs」的片段；
     * 若有多个（理论上不该发生），取映射残差最小的。
     * 若没有任何片段能覆盖，则退化为「用最近的片段外推」，并如实降级置信度。
     */
    fun toOriginal(
        domain: ClockDomain,
        rawUs: Micros,
        assumedErrorUs: Micros = 0L,
    ): TimeMappingResult? {
        if (domain == ClockDomain.ORIGINAL_FILE) {
            // 已经在基准域，直接返回；这是唯一不需要映射的情况
            return TimeMappingResult(
                referenceUs = rawUs,
                confidence = MappingConfidence.CONFIRMED,
                errorBoundUs = assumedErrorUs,
                extrapolatedByUs = 0L,
            )
        }
        if (segments.isEmpty()) return null

        // 必须用 effective()：打开中的片段靠它才带上实时同步事件
        val candidates = segments.mapNotNull { segment ->
            effective(segment).mapFor(domain)?.let { segment to it }
        }
        if (candidates.isEmpty()) return null

        // 先找真正落在锚点跨度内的
        val covering = candidates.filter { (_, map) ->
            map.anchors.any { it.rawUs == rawUs } ||
                (rawUs >= map.anchors.minOf { it.rawUs } && rawUs <= map.anchors.maxOf { it.rawUs })
        }
        val chosen = (covering.ifEmpty { candidates })
            .minByOrNull { (_, map) -> map.maxResidualUs }

        return chosen?.second?.toReference(rawUs, assumedErrorUs)
    }

    /** 把原片域的一个区间换算回 [domain]（用于「按时间戳抽帧」时反查）。 */
    fun toDomain(
        domain: ClockDomain,
        originalRange: TimeRangeUs,
    ): TimeRangeUs? {
        if (domain == ClockDomain.ORIGINAL_FILE) return originalRange
        val segment = segments.lastOrNull() ?: return null
        val map = effective(segment).mapFor(domain) ?: return null
        if (map.scale == 0.0) return null
        // reference = intercept + raw*scale  ⇒  raw = (reference - intercept)/scale
        // 这里用两端点分别反算，避免自行推导截距
        val startRaw = invert(map, originalRange.startUs) ?: return null
        val endRaw = invert(map, originalRange.endExclusiveUs) ?: return null
        val lo = minOf(startRaw, endRaw)
        val hi = maxOf(startRaw, endRaw)
        return TimeRangeUs(lo, hi)
    }

    /**
     * 反算：原片时间 → 本域时间。
     * [LinearTimeMap] 只暴露正向换算，这里通过两次正向采样做线性反解。
     */
    private fun invert(map: LinearTimeMap, referenceUs: Micros): Micros? {
        if (map.anchors.isEmpty()) return null
        val a = map.anchors.first()
        val b = map.anchors.last()
        if (a.rawUs == b.rawUs) {
            // 单点映射：偏移即常量
            val forward = map.toReference(a.rawUs)
            return a.rawUs + (referenceUs - forward.referenceUs)
        }
        val fa = map.toReference(a.rawUs).referenceUs
        val fb = map.toReference(b.rawUs).referenceUs
        val span = (fb - fa).toDouble()
        if (span == 0.0) return null
        val ratio = (referenceUs - fa) / span
        return a.rawUs + (ratio * (b.rawUs - a.rawUs)).toLong()
    }

    /**
     * 生成可核查的时间映射报告。
     * 方案文档要求「保存原始时间」「可核查的事件记录」，导出与验收都读这个。
     */
    fun describe(): String = buildString {
        appendLine("RecordingTimeline(segments=${segments.size}, recording=$isRecording)")
        allSegments.forEach { s ->
            append("  #").append(s.index).append(" id=").append(s.id)
            append(" phoneOpen=").append(s.phoneOpenedAtUs)
            append(" confirmed=").append(s.captureConfirmedAtUs)
            append(" closed=").append(s.closedAtUs)
            append(" markers=").append(s.syncMarkers.size)
            appendLine()
            for (domain in ClockDomain.entries) {
                s.mapFor(domain)?.let { appendLine("      " + it.describe()) }
            }
        }
    }

    fun reset() {
        segments.clear()
        openMarkers.clear()
        openSegmentId = null
    }
}
