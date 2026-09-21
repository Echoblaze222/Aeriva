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
         *   are counted.
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
            val delaysInValidPairs = mutableListOf<Double>()
            val sourceIds = mutableListOf<Long>()

            var previous: LatencyMeasurement.Succeeded? = null
            for (measurement in series) {
                val succeeded = measurement as? LatencyMeasurement.Succeeded
                if (succeeded == null) {
                    previous = null
                    continue
                }

                val prev = previous
                if (prev != null && isAdjacentPair(prev, succeeded)) {
                    differences += kotlin.math.abs(succeeded.valueMillis - prev.valueMillis)
                    if (delaysInValidPairs.isEmpty()) {
                        delaysInValidPairs += prev.valueMillis
                        sourceIds += prev.id
                    }
                    delaysInValidPairs += succeeded.valueMillis
                    sourceIds += succeeded.id
                }
                previous = succeeded
            }

            if (differences.isEmpty()) return null

            val meanAbsIpdv = differences.sum() / differences.size
            val pdvRange = (delaysInValidPairs.maxOrNull() ?: 0.0) - (delaysInValidPairs.minOrNull() ?: 0.0)
            val pairCount = differences.size

            return DerivedJitterStats(
                definition = JitterDefinition.MEAN_ABS_CONSECUTIVE_DIFFERENCE,
                method = series.first { it is LatencyMeasurement.Succeeded }.method,
                meanAbsIpdvMillis = meanAbsIpdv,
                pdvRangeMillis = pdvRange,
                sampleCount = delaysInValidPairs.size,
                pairCount = pairCount,
                sourceMeasurementIds = sourceIds.distinct(),
                calculatedAt = calculatedAt,
                confidence = confidenceFor(pairCount)
            )
        }

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
