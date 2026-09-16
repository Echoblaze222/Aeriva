package com.aeriva.core.model.measurement

import java.time.Duration
import java.time.Instant

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 11. Wall-clock [Instant], matching the existing
 * NetworkState.lastChangedAt convention -- appropriate for persisted,
 * cross-session freshness (see the design doc's Section 11 for why
 * monotonic time is a separate, unresolved question for in-flight
 * duration timing, not for this type).
 *
 * [isStaleAt] takes "now" as an explicit parameter rather than calling
 * Instant.now() itself, matching AndroidNetworkMonitor's existing
 * `now: () -> Instant` seam and PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md's
 * requirement that every freshness calculation be testable with a
 * fake/fixed clock.
 */
data class Freshness(
    val producedAt: Instant,
    val validUntil: Instant?
) {
    fun isStaleAt(now: Instant): Boolean {
        val until = validUntil ?: return false
        return now.isAfter(until)
    }

    fun ageAt(now: Instant): Duration = Duration.between(producedAt, now)

    companion object {
        /** No defined expiry -- freshness is judged by [ageAt] alone. */
        fun withoutExpiry(producedAt: Instant): Freshness = Freshness(producedAt, validUntil = null)
    }
}
