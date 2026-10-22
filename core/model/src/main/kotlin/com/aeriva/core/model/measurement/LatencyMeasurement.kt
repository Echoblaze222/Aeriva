package com.aeriva.core.model.measurement

import java.time.Instant

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 5 and Section 19. Latency is the single illustrative metric
 * used to prove the OBSERVATION/MEASUREMENT/.../RECOMMENDATION boundary
 * compiles and is testable -- jitter/packet-loss/throughput are not
 * modeled here; per the design doc, this phase establishes the
 * boundary, not full metric coverage.
 *
 * [Failed] and [Succeeded] are structurally distinct cases specifically
 * so a failed probe can never be misread as "succeeded with a low
 * value" -- see the design doc's Section 12 on why this must be
 * enforced at the type level, not by convention.
 *
 * [evidence] was added in Phase 4 per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-1 --
 * a trailing, optional [ProbeEvidence] so existing positional
 * constructor call sites (the Phase 3B engine's own `toMeasurement`)
 * keep compiling unchanged. It is null wherever a client has not yet
 * been migrated to attach it, and is expected to always be present once
 * a production client does (Decision D3-1). [DerivedJitterStats.from]
 * reads [evidence]`.connectionState` to decide pair adjacency, so a
 * `null` evidence value means that sample can never be paired for
 * jitter, not that it is silently treated as cold or warm.
 */
sealed interface LatencyMeasurement {

    val id: Long
    val context: MeasurementNetworkContext
    val measuredAt: Instant
    val method: String

    data class Succeeded(
        override val id: Long,
        override val context: MeasurementNetworkContext,
        override val measuredAt: Instant,
        override val method: String,
        val valueMillis: Double,
        val sampleCount: Int,
        val evidence: ProbeEvidence? = null
    ) : LatencyMeasurement

    data class Failed(
        override val id: Long,
        override val context: MeasurementNetworkContext,
        override val measuredAt: Instant,
        override val method: String,
        val failure: MeasurementFailure,
        val evidence: ProbeEvidence? = null
    ) : LatencyMeasurement
}
