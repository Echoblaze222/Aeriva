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
        val sampleCount: Int
    ) : LatencyMeasurement

    data class Failed(
        override val id: Long,
        override val context: MeasurementNetworkContext,
        override val measuredAt: Instant,
        override val method: String,
        val failure: MeasurementFailure
    ) : LatencyMeasurement
}
