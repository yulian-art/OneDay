package com.julien.core_media.time

import com.julien.core_media.model.ClockDomain
import com.julien.core_media.model.MappingConfidence
import com.julien.core_media.model.Micros

/**
 * 一次跨域换算的结果。
 *
 * 刻意不返回裸 `Long`：方案文档要求「明确告知用户每个阶段的确定性」，
 * 也要求「时间对应」误差可核查。所以每次换算都自带
 * 置信度、误差上界，以及是否属于外推。
 */
data class TimeMappingResult(
    /** 映射到基准域（原片）的时间。 */
    val referenceUs: Micros,
    val confidence: MappingConfidence,
    /** 真实误差的上界估计（微秒）。 */
    val errorBoundUs: Micros,
    /**
     * 目标时间落在锚点跨度之外的部分（微秒）。
     * 0 表示在锚点覆盖范围内（内插）；>0 表示外推，可信度已相应下调。
     */
    val extrapolatedByUs: Micros,
) {
    val isExtrapolated: Boolean get() = extrapolatedByUs > 0
}

/**
 * 单时钟域 → 原片域的仿射映射：`reference = intercept + raw * scale`。
 *
 * ### 为什么是「仿射」而不是「加一个偏移」
 * 方案文档明确要求：「若用线性映射，应测偏移与漂移；停录、重启、分段分别处理」。
 * 两个时钟的晶振不可能完全一致，长会话下纯偏移会产生秒级累积误差，
 * 因此这里同时拟合**偏移**与**漂移**（`scale`），并把漂移以 ppm 暴露出来供验收测量。
 *
 * ### 拟合方式：小规模 RANSAC + 带权最小二乘
 * 直接用最小二乘是**不安全**的：只要有一个锚点配错（例如同步事件被误检、
 * 用户拍手时机看错），它就会把整条斜率拽歪，导致后续所有跳转全错。
 * 锚点数量本来就少（几个），所以我们用 RANSAC 思路做稳健拟合：
 *
 * 1. 由每对锚点算出一个候选斜率；
 * 2. 对每个候选斜率，用「各锚点截距的中位数」作为截距，统计落在容差内的内点；
 * 3. 取内点最多（同分则权重和最大）的那个模型；
 * 4. 只在内点上做带权最小二乘。
 *
 * 被排除的锚点不会被静默丢弃 —— 它们记录在 [rejectedAnchors] 里，
 * 并且会拉低置信度，因为「有锚点对不上」本身就是需要人看一眼的信号。
 */
class LinearTimeMap private constructor(
    val domain: ClockDomain,
    /** 参与最终拟合的锚点（内点），按 rawUs 升序。 */
    val anchors: List<TimeAnchor>,
    /** 被稳健拟合判定为离群、未参与拟合的锚点。 */
    val rejectedAnchors: List<TimeAnchor>,
    /** `reference = interceptUs + rawUs * scale` 中的截距。 */
    private val interceptUs: Double,
    /** 原片微秒 / 该域微秒。1.0 表示两域速率一致。 */
    val scale: Double,
    /** 内点上的最大拟合残差（微秒）。 */
    val maxResidualUs: Micros,
    /** 内点上的残差均方根（微秒）。 */
    val rmsResidualUs: Micros,
    /** 斜率是否因超出物理合理范围而被判定不可信并强制为 1.0。 */
    val driftRejected: Boolean,
) {
    /** 锚点覆盖的原始时间跨度，用于判断内插/外推。 */
    private val rawMinUs: Micros = anchors.minOf { it.rawUs }
    private val rawMaxUs: Micros = anchors.maxOf { it.rawUs }

    /**
     * 漂移，单位 ppm（parts per million）。
     * 验收时应记录该值：文档要求「测偏移与漂移」。
     */
    val driftPpm: Double get() = (scale - 1.0) * 1_000_000.0

    /** 在 `rawUs` 处的偏移量：把该域时间直接当原片时间会有多大误差。 */
    fun offsetAtUs(rawUs: Micros): Micros = (interceptUs + rawUs * scale - rawUs).toLong()

    /**
     * 把本域时间换算到原片域。
     *
     * @param assumedErrorUs 调用方对「这个原始时间戳本身有多准」的先验估计。
     *        例如预览帧时间戳若怀疑有抖动，可传 30_000（30ms）。
     */
    fun toReference(rawUs: Micros, assumedErrorUs: Micros = 0L): TimeMappingResult {
        val reference = (interceptUs + rawUs * scale).toLong()

        val extrapolatedBy = when {
            rawUs < rawMinUs -> rawMinUs - rawUs
            rawUs > rawMaxUs -> rawUs - rawMaxUs
            else -> 0L
        }

        // 误差上界 = 拟合残差 + 原始时间戳先验误差 + 外推惩罚（按漂移线性放大）
        val driftPenalty = if (extrapolatedBy > 0) {
            (extrapolatedBy * kotlin.math.abs(scale - 1.0)).toLong()
        } else {
            0L
        }
        val errorBound = maxResidualUs + assumedErrorUs + driftPenalty

        return TimeMappingResult(
            referenceUs = reference,
            confidence = classify(extrapolatedBy),
            errorBoundUs = errorBound,
            extrapolatedByUs = extrapolatedBy,
        )
    }

    /**
     * 置信度分级。锚点数量、残差、外推距离、是否有离群锚点共同决定。
     * 分档阈值与方案文档的「待确认」理念一致：证据不足时宁可报低。
     */
    private fun classify(extrapolatedByUs: Micros): MappingConfidence {
        if (anchors.isEmpty()) return MappingConfidence.UNKNOWN

        // 有锚点对不上 → 说明证据本身可疑，最高只能给 PROBABLE
        val hasRejected = rejectedAnchors.isNotEmpty()

        if (driftRejected) {
            return if (anchors.size >= 2 && maxResidualUs <= PROBABLE_RESIDUAL_US) {
                MappingConfidence.PROBABLE
            } else {
                MappingConfidence.UNCERTAIN
            }
        }
        return when {
            extrapolatedByUs > EXTRAPOLATION_HARD_LIMIT_US -> MappingConfidence.UNCERTAIN
            anchors.size >= 3 && maxResidualUs <= CONFIRMED_RESIDUAL_US -> when {
                extrapolatedByUs > 0 -> MappingConfidence.PROBABLE
                hasRejected -> MappingConfidence.PROBABLE
                else -> MappingConfidence.CONFIRMED
            }
            anchors.size >= 2 && maxResidualUs <= PROBABLE_RESIDUAL_US -> MappingConfidence.PROBABLE
            else -> MappingConfidence.UNCERTAIN
        }
    }

    /** 供诊断/导出记录的摘要，满足「保存原始时间」与「可核查」的要求。 */
    fun describe(): String = buildString {
        append("LinearTimeMap(")
        append("domain=").append(domain)
        append(", anchors=").append(anchors.size)
        append(", offset@first=").append(offsetAtUs(rawMinUs)).append("us")
        // 必须固定 Locale：这个字符串是要被机器解析的验收记录，
        // 在德语/法语等区域用默认 Locale 会输出 "100,00" 而破坏可解析性。
        append(", drift=").append(String.format(java.util.Locale.ROOT, "%.2f", driftPpm)).append("ppm")
        append(", maxResidual=").append(maxResidualUs).append("us")
        append(", rmsResidual=").append(rmsResidualUs).append("us")
        if (rejectedAnchors.isNotEmpty()) append(", rejected=").append(rejectedAnchors.size)
        if (driftRejected) append(", driftRejected=true")
        append(")")
    }

    companion object {
        /** 残差 ≤20ms 且锚点 ≥3 才敢称 CONFIRMED。 */
        const val CONFIRMED_RESIDUAL_US = 20_000L

        /** 残差 ≤120ms 且锚点 ≥2 可算 PROBABLE。 */
        const val PROBABLE_RESIDUAL_US = 120_000L

        /** 外推超过 5 秒一律降级为 UNCERTAIN。 */
        const val EXTRAPOLATION_HARD_LIMIT_US = 5_000_000L

        /**
         * 允许的最大漂移。常见晶振 ±100ppm 以内；
         * 超过 5%（50000ppm）几乎必然是配对错误或时间戳回绕，必须拒绝。
         */
        const val MAX_ABS_DRIFT_PPM = 50_000.0

        /**
         * RANSAC 内点容差。同步事件的人工精度在几十毫秒量级，
         * 取 60ms 既能容纳正常抖动，又能识别出真正配错的锚点。
         */
        const val DEFAULT_INLIER_TOLERANCE_US = 60_000L

        /**
         * 由锚点集合稳健拟合映射。
         *
         * @return 锚点为空时返回 null —— 文档要求「不能用模型猜」，
         *         没有证据就不该伪造一个映射。
         */
        fun fit(
            domain: ClockDomain,
            anchors: List<TimeAnchor>,
            inlierToleranceUs: Micros = DEFAULT_INLIER_TOLERANCE_US,
        ): LinearTimeMap? {
            val usable = anchors
                .filter { it.domain == domain }
                .sortedBy { it.rawUs }
            if (usable.isEmpty()) return null

            // ---- 单锚点：斜率不可辨识，退化为纯偏移 ----
            if (usable.size == 1) {
                val a = usable.first()
                return LinearTimeMap(
                    domain = domain,
                    anchors = usable,
                    rejectedAnchors = emptyList(),
                    interceptUs = a.referenceUs.toDouble() - a.rawUs,
                    scale = 1.0,
                    maxResidualUs = 0L,
                    rmsResidualUs = 0L,
                    driftRejected = false,
                )
            }

            // ---- 第 1 步：由锚点对生成候选斜率 ----
            val candidateSlopes = LinkedHashSet<Double>()
            for (i in usable.indices) {
                for (j in i + 1 until usable.size) {
                    val dx = (usable[j].rawUs - usable[i].rawUs).toDouble()
                    if (dx != 0.0) {
                        val s = (usable[j].referenceUs - usable[i].referenceUs) / dx
                        if (s.isFinite()) candidateSlopes += s
                    }
                }
            }
            // 把「无漂移」作为一个显式候选，并在同分时优先选中它：
            // 证据不足时应当倾向最简单、最不容易错的模型。
            candidateSlopes += 1.0

            // ---- 第 2 步：为每个候选斜率找内点 ----
            var bestInliers: List<TimeAnchor> = emptyList()
            var bestScore = -1.0
            for (slope in candidateSlopes) {
                val intercepts = usable
                    .map { it.referenceUs - slope * it.rawUs }
                    .sorted()
                val medianIntercept = median(intercepts)
                val inliers = usable.filter { a ->
                    val predicted = medianIntercept + slope * a.rawUs
                    kotlin.math.abs(a.referenceUs - predicted) <= inlierToleranceUs
                }
                // 内点数量优先，其次权重和
                val score = inliers.size * 1_000_000.0 + inliers.sumOf { it.weight }
                if (score > bestScore) {
                    bestScore = score
                    bestInliers = inliers
                }
            }

            // 找不到任何一致的两点，只能退回全量（并让置信度如实反映）
            val model = if (bestInliers.size >= 2) bestInliers else usable
            val rejected = usable.filterNot { it in model }

            // ---- 第 3 步：在内点上做带权最小二乘 ----
            val totalWeight = model.sumOf { it.weight }
            val meanRaw = model.sumOf { it.rawUs * it.weight } / totalWeight
            val meanRef = model.sumOf { it.referenceUs * it.weight } / totalWeight

            var sxx = 0.0
            var sxy = 0.0
            for (a in model) {
                val dx = a.rawUs - meanRaw
                sxx += a.weight * dx * dx
                sxy += a.weight * dx * (a.referenceUs - meanRef)
            }

            var driftRejected = false
            var scale = if (sxx > 1e-6) sxy / sxx else 1.0

            if (kotlin.math.abs(scale - 1.0) * 1_000_000.0 > MAX_ABS_DRIFT_PPM) {
                // 斜率不可信：拒绝采信，退回纯偏移，但保留原始锚点供诊断
                scale = 1.0
                driftRejected = true
            }

            val intercept = meanRef - scale * meanRaw

            // ---- 第 4 步：残差（只统计内点，离群点已单独记录）----
            var maxResidual = 0.0
            var sumSq = 0.0
            for (a in model) {
                val predicted = intercept + scale * a.rawUs
                val residual = a.referenceUs - predicted
                if (kotlin.math.abs(residual) > maxResidual) maxResidual = kotlin.math.abs(residual)
                sumSq += residual * residual
            }
            val rms = kotlin.math.sqrt(sumSq / model.size)

            return LinearTimeMap(
                domain = domain,
                anchors = model,
                rejectedAnchors = rejected,
                interceptUs = intercept,
                scale = scale,
                maxResidualUs = maxResidual.toLong(),
                rmsResidualUs = rms.toLong(),
                driftRejected = driftRejected,
            )
        }

        private fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return 0.0
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2.0
            }
        }
    }
}
