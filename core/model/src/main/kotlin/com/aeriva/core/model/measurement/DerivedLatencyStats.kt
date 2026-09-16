package com.aeriva.core.model.measurement

import java.time.Instant

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 6. Deterministic arithmetic over [sourceMeasurementIds] --
 * no inference, no history-based extrapolation (that is
 * [LatencyEstimation]/[LatencyPrediction], not this type). Remains a
 * distinct type from [LatencyMeasurement] even when computed over a
 * single sample, so "raw reading" vs. "computed statistic" is never
 * ambiguous from the type alone.
 *
 * [calculatedAt] is deliberately separate from any source measurement's
 * own [LatencyMeasurement.measuredAt] -- per the design doc's Section
 * 11, a derived value's calculation time can be later than what it was
 * calculated from.
 */
data class DerivedLatencyStats(
    val averageMillis: Double,
    val minMillis: Double,
    val maxMillis: Double,
    val sourceMeasurementIds: List<Long>,
    val calculatedAt: Instant,
    val confidence: Confidence
)
