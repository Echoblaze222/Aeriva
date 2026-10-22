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
) {
    companion object {

        /**
         * Phase 3B test-foundation addition -- the one deterministic
         * aggregation function PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md's
         * Section on latency/stability coverage needs to exist against,
         * following PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 2's
         * pure-function-over-hand-constructed-samples convention exactly
         * ([NetworkStateMapper] is the precedent, not a new pattern).
         *
         * This is deliberately the *only* new arithmetic this phase adds:
         * jitter/packet-loss/throughput aggregation are not implemented here
         * (their domain types don't exist yet -- Phase 3A modeled latency
         * only, as the illustrative metric). This function proves the same
         * pattern transfers to them once those types exist; it does not
         * pre-build for them speculatively.
         *
         * @param measurements only [LatencyMeasurement.Succeeded] -- per the
         *   design doc's Section 5 "partial results" framing, a caller
         *   deciding what to do with [LatencyMeasurement.Failed] entries is
         *   a separate, already-modeled concern ([MeasurementFailure]); this
         *   function only aggregates values that actually exist.
         * @param calculatedAt explicit "now", from the existing `() ->
         *   Instant` seam -- never computed internally, per
         *   [Freshness]'s and [AndroidNetworkMonitor]'s established
         *   convention.
         * @return null when there is nothing valid to aggregate (empty
         *   input, or every sample rejected as invalid) -- an explicit,
         *   defined "no result" per Section 2's requirement, never a
         *   division-by-zero or NaN silently standing in for one.
         */
        fun from(
            measurements: List<LatencyMeasurement.Succeeded>,
            calculatedAt: Instant
        ): DerivedLatencyStats? {
            // A probe that somehow reports negative latency is rejected,
            // not averaged in -- Section 2's explicit numerical-edge-case
            // requirement. NaN/infinite values are equally not a real
            // latency reading and are rejected the same way.
            val valid = measurements.filter { it.valueMillis >= 0.0 && it.valueMillis.isFinite() }
            if (valid.isEmpty()) return null

            val values = valid.map { it.valueMillis }
            val average = values.sum() / values.size
            val min = values.min()
            val max = values.max()

            // Illustrative consistency heuristic only -- same status as
            // Confidence.of's own thresholds (that type's KDoc already
            // states this explicitly): low spread relative to the average
            // counts as "consistent." A single sample has no spread to
            // measure and is trivially consistent under this definition.
            val consistent = values.size < 2 ||
                (max - min) <= average * CONSISTENCY_SPREAD_RATIO

            return DerivedLatencyStats(
                averageMillis = average,
                minMillis = min,
                maxMillis = max,
                sourceMeasurementIds = valid.map { it.id },
                calculatedAt = calculatedAt,
                confidence = Confidence.of(sampleCount = valid.size, consistent = consistent)
            )
        }

        private const val CONSISTENCY_SPREAD_RATIO = 0.5
    }
}
