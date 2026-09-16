package com.aeriva.core.model.measurement

import java.time.Instant

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 9. An action suggested to the user -- never itself a
 * measurement, and always traceable to [supportingEvidenceIds]. No
 * recommendation algorithm is implemented; this type only establishes
 * the shape a future one would populate.
 *
 * [decisionCriteria] exists so a recommendation always states the rule
 * that produced it (e.g. "lowest predicted jitter among networks with
 * sufficient recent evidence"), even before any real rule is
 * implemented -- see the design doc's Section 9.
 */
sealed interface ConnectivityRecommendation {

    data class NoRecommendation(val reason: String) : ConnectivityRecommendation

    data class Recommended(
        val target: String,
        val activityProfile: String,
        val supportingEvidenceIds: List<Long>,
        val decisionCriteria: String,
        val confidence: Confidence,
        val freshness: Freshness,
        val generatedAt: Instant,
        val explanation: String
    ) : ConnectivityRecommendation
}
