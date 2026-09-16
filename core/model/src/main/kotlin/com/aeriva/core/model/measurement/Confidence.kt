package com.aeriva.core.model.measurement

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 10. A closed, named set of confidence tiers, each defined in
 * terms of sample count and consistency -- never a bare unexplained
 * number. [Insufficient] is not "low confidence"; it is the distinct,
 * first-class "we do not have enough basis for a result yet" outcome
 * (see [MeasurementFailure] and the design doc's Section 12 for why
 * this is not the same thing as a failure).
 *
 * The thresholds in [of] are illustrative, per the design doc's own
 * Section 10 -- not a claim that these exact numbers are correct for
 * production use. Whoever implements real scoring should replace this
 * function's body, not the shape of [Confidence] itself.
 */
sealed interface Confidence {
    data object Insufficient : Confidence
    data object Low : Confidence
    data object Medium : Confidence
    data object High : Confidence

    companion object {
        /**
         * Illustrative sample-count-based tiering only. [consistent]
         * represents whether the samples were low-variance (a real
         * implementation would pass this in already computed, not
         * compute variance here -- this type has no opinion on how
         * consistency is measured).
         */
        fun of(sampleCount: Int, consistent: Boolean): Confidence = when {
            sampleCount < MINIMUM_SAMPLES -> Insufficient
            sampleCount < MEDIUM_SAMPLES -> Low
            !consistent -> Medium
            else -> High
        }

        private const val MINIMUM_SAMPLES = 3
        private const val MEDIUM_SAMPLES = 10
    }
}
