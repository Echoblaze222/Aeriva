package com.aeriva.core.model.measurement

import java.time.Instant

/**
 * Which formal jitter definition [DerivedJitterStats] was computed
 * under. `v1` only offers [MEAN_ABS_CONSECUTIVE_DIFFERENCE] (mean
 * absolute IPDV, RFC 3393/RFC 5481) -- see Decision D4-3. A percentile
 * (PDV, RFC 5481) definition is deferred until validation pilots supply
 * a sample count that makes a percentile meaningful
 * (PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md D-11); [DerivedJitterStats]
 * already carries [DerivedJitterStats.pdvRangeMillis] as a simple range
 * so that data exists to compute a real percentile from later, without
 * this type needing to change shape when that decision is made.
 */
enum class JitterDefinition {
    MEAN_ABS_CONSECUTIVE_DIFFERENCE
}

/**
 * Jitter as a derived value over an ordered series of latency samples
 * -- not a new probe type or a new engine (Decision D4-3). Resolves
 * PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md D-10 for v1; revisable after
 * pilots per that same decision.
 *
 * The signed mean IPDV is deliberately not stored: RFC 5481 notes the
 * mean signed IPDV over a real series is typically close to zero, which
 * is why [meanAbsIpdvMillis] (mean of the *absolute* difference between
 * consecutive delays) is the reported statistic, alongside
 * [pdvRangeMillis] (max minus min delay over the same samples) as a
 * second, differently-shaped view per RFC 5481's own PDV formulation.
 *
 * "The same samples" means every [LatencyMeasurement.Succeeded] that is
 * a member of at least one counted pair -- not only the samples that
 * happen to start a run. A sample that ends one run of adjacency and a
 * sample that starts the next run after a break are both members of a
 * counted pair and are both part of [sourceMeasurementIds],
 * [sampleCount] and the range that feeds [pdvRangeMillis]. Fixed
 * 2026-09-22 (PHASE_4_JITTER_TEST_GATE_REPORT.md, finding F-2): an
 * earlier version of this function only recorded the first member of
 * the very first counted pair in the whole series, silently dropping
 * the first member of every pair that starts a run after a break
 * (a failed sample, a cold sample, a method change or a handle
 * change). [method] is the method shared by the first counted pair
 * (finding F-3; an earlier version reported the method of the first
 * *Succeeded* sample in the series, which is not necessarily part of
 * any counted pair at all). A series whose counted pairs span more
 * than one method -- which [isAdjacentPair] cannot produce for a
 * single pair, but can arise across two different runs separated by a
 * break -- has no defined single-method label yet; that is an open
 * decision (DP-1 in the same report), not resolved by this function.
 */
data class DerivedJitterStats(
    val definition: JitterDefinition,
    val method: String,
    val meanAbsIpdvMillis: Double,
    val pdvRangeMillis: Double,
    val sampleCount: Int,
    val pairCount: Int,
    val sourceMeasurementIds: List<Long>,
    val calculatedAt: Instant,
    val confidence: Confidence
) {
    companion object {

        // Provisional starting points only, explicitly carried over from
        // Confidence.of's own illustrative thresholds per Decision D4-4 --
        // jitter's own confidence function, not a reuse of the latency
        // function unchanged, and both numbers below must be replaced by
        // pilot-derived values before release (Decision D4-4).
        private const val MINIMUM_PAIRS = 3
        private const val MEDIUM_PAIRS = 10

        /**
         * @param series the full ordered series in send order, including
         *   failures -- a failed sample, a network change, or a method
         *   change breaks adjacency and is never bridged over (Decision
         *   D4-3). Only pairs of consecutive [LatencyMeasurement.Succeeded]
         *   samples that share [LatencyMeasurement.method], are both
         *   warm-connection samples (per their attached [ProbeEvidence]),
         *   and have equal network handles (both null counts as equal)
         *   are counted. A sample whose [LatencyMeasurement.Succeeded.valueMillis]
         *   is not finite or is negative is never treated as a real
         *   reading and can never be part of a counted pair -- the same
         *   numerical-edge-case rule [DerivedLatencyStats.from] already
         *   applies (PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md
         *   Section 2, "a probe that somehow reports negative latency
         *   should be rejected, not averaged in"; that requirement is
         *   general to derived scoring/aggregation, not specific to
         *   latency's own type).
         *
         * Rejects a series not in non-decreasing [LatencyMeasurement.measuredAt]
         * order as unordered evidence (returns null). Returns null when
         * there are zero valid pairs.
         */
        fun from(series: List<LatencyMeasurement>, calculatedAt: Instant): DerivedJitterStats? {
            if (series.isEmpty()) return null

            for (i in 1 until series.size) {
                if (series[i].measuredAt.isBefore(series[i - 1].measuredAt)) {
                    return null
                }
            }

            val differences = mutableListOf<Double>()
            // Keyed by measurement id so every member of every counted pair is
            // recorded exactly once, in first-seen (send) order, regardless of
            // whether it is the first or second member of its pair and
            // regardless of how many runs of adjacency the series contains.
            val members = linkedMapOf<Long, Double>()
            var pairMethod: String? = null

            var previous: LatencyMeasurement.Succeeded? = null
            for (measurement in series) {
                val succeeded = (measurement as? LatencyMeasurement.Succeeded)?.takeIf { isRealReading(it) }
                if (succeeded == null) {
                    previous = null
                    continue
                }

                val prev = previous
                if (prev != null && isAdjacentPair(prev, succeeded)) {
                    differences += kotlin.math.abs(succeeded.valueMillis - prev.valueMillis)
                    members[prev.id] = prev.valueMillis
                    members[succeeded.id] = succeeded.valueMillis
                    if (pairMethod == null) pairMethod = prev.method
                }
                previous = succeeded
            }

            if (differences.isEmpty()) return null

            val pairCount = differences.size

            return DerivedJitterStats(
                definition = JitterDefinition.MEAN_ABS_CONSECUTIVE_DIFFERENCE,
                method = pairMethod!!,
                meanAbsIpdvMillis = differences.sum() / pairCount,
                pdvRangeMillis = members.values.max() - members.values.min(),
                sampleCount = members.size,
                pairCount = pairCount,
                sourceMeasurementIds = members.keys.toList(),
                calculatedAt = calculatedAt,
                confidence = confidenceFor(pairCount)
            )
        }

        /** Not a real latency reading if non-finite or negative -- see [from]'s own KDoc. */
        private fun isRealReading(m: LatencyMeasurement.Succeeded): Boolean =
            m.valueMillis.isFinite() && m.valueMillis >= 0.0

        private fun isAdjacentPair(
            a: LatencyMeasurement.Succeeded,
            b: LatencyMeasurement.Succeeded
        ): Boolean {
            if (a.method != b.method) return false
            val aWarm = a.evidence?.connectionState == ConnectionState.Warm
            val bWarm = b.evidence?.connectionState == ConnectionState.Warm
            if (!aWarm || !bWarm) return false
            return a.evidence?.networkHandle == b.evidence?.networkHandle
        }

        private fun confidenceFor(pairCount: Int): Confidence = when {
            pairCount < MINIMUM_PAIRS -> Confidence.Insufficient
            pairCount < MEDIUM_PAIRS -> Confidence.Low
            else -> Confidence.High
        }
    }
}
