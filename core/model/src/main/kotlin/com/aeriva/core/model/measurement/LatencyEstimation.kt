package com.aeriva.core.model.measurement

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 7. Produced when direct measurement was unavailable or
 * incomplete. [InsufficientEvidence] is a legitimate, first-class
 * outcome (Section 10) -- not a failure, and never silently replaced
 * by a fabricated [Estimated] value.
 *
 * [Estimated.valueMillis] must never be read as if it were a directly
 * measured [LatencyMeasurement.Succeeded.valueMillis] -- keeping this
 * a structurally separate type (rather than, say, an optional
 * "estimated" flag on [LatencyMeasurement]) is what makes that
 * confusion a compile-time impossibility rather than a convention.
 */
sealed interface LatencyEstimation {

    data object InsufficientEvidence : LatencyEstimation

    data class Estimated(
        val valueMillis: Double,
        val method: String,
        val sourceEvidenceIds: List<Long>,
        val confidence: Confidence,
        val freshness: Freshness
    ) : LatencyEstimation
}
