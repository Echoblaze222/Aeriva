package com.aeriva.network.monitor.measurement

import java.time.Instant

/**
 * Extends the existing `() -> Instant` seam
 * ([com.aeriva.network.monitor.AndroidNetworkMonitor]'s `now` parameter)
 * into measurement tests specifically -- per
 * PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 3/6, "no dedicated
 * Clock interface... where the existing () -> Instant pattern already
 * works." This is that same plain function type, wrapped in the
 * smallest possible mutable holder so several tests in this package
 * don't each hand-roll `var current: Instant` plus a lambda closing over
 * it.
 *
 * Deliberately not a class hierarchy or interface -- callers that expect
 * `() -> Instant` can pass `clock::now` directly.
 */
class MutableClock(initial: Instant) {
    var current: Instant = initial

    fun now(): Instant = current

    fun advanceBy(millis: Long) {
        current = current.plusMillis(millis)
    }
}
