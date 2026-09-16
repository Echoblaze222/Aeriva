package com.aeriva.core.model.measurement

import java.time.Instant

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 8. About the future (or an unmeasured time/place), based on
 * historical evidence -- no machine-learning system is implied or
 * required by this type; [methodId] can name something as simple as
 * "N-day historical average."
 *
 * [predictedAt] is when the prediction was generated -- distinct from
 * whatever future time/place [targetDescription] describes.
 */
sealed interface LatencyPrediction {

    data object InsufficientEvidence : LatencyPrediction

    data class Predicted(
        val targetDescription: String,
        val predictedAt: Instant,
        val valueMillis: Double,
        val sourceHistoryIds: List<Long>,
        val methodId: String,
        val confidence: Confidence,
        val freshness: Freshness
    ) : LatencyPrediction
}
