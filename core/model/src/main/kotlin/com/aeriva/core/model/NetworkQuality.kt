package com.aeriva.core.model

/**
 * Estimated connection quality. Populating [Measured] requires the
 * measurement engine, which is Phase 3 (Measurement Engine), not Phase 2.
 * Phase 2 code must only ever produce [Unavailable] here -- see
 * architecture doc Section 6: "if the platform does not expose a
 * trustworthy value for a particular metric, AERIVA should show
 * Unavailable rather than inventing a number."
 */
sealed interface NetworkQuality {
    data object Unavailable : NetworkQuality

    data class Measured(
        val score: Int,
        val label: String
    ) : NetworkQuality
}
