package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression coverage for the stale per-network event defect:
 * [NetworkEventReducer]'s CapabilitiesChanged and BlockedStatusChanged
 * branches used `state.network != null && event.network != state.network`
 * as their foreign-network guard, which is false whenever no network is
 * tracked (initial state, after Lost, after Unavailable) -- so a late
 * per-network event for an already-lost network was accepted instead of
 * dropped. Only `Available` may establish a tracked network.
 *
 * Kept in its own file, separate from [NetworkEventReducerTest], so it does
 * not collide with concurrent edits to that file. `N = String` throughout,
 * for the reason given in [RawNetworkEvent]'s KDoc.
 */
class NetworkEventReducerStaleEventTest {

    private val t0 = Instant.parse("2026-09-11T00:00:00Z")
    private val t1 = Instant.parse("2026-09-11T00:00:01Z")
    private val t2 = Instant.parse("2026-09-11T00:00:02Z")
    private val t3 = Instant.parse("2026-09-11T00:00:03Z")

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED", "NOT_METERED")
    )

    private val cellularSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = false,
        transports = setOf(TransportType.CELLULAR),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED")
    )

    private fun established(): MonitorState<String> = NetworkEventReducer.reduce(
        MonitorState.initial(t0),
        RawNetworkEvent.Available("A", wifiSnapshot),
        t1
    )

    /** "A" established, then lost at t2: canonical offline, changedAt = t2. */
    private fun afterLostA(): MonitorState<String> =
        NetworkEventReducer.reduce(established(), RawNetworkEvent.Lost("A"), t2)

    /** "A" established, then Unavailable at t2: canonical offline, changedAt = t2. */
    private fun afterUnavailable(): MonitorState<String> =
        NetworkEventReducer.reduce(established(), RawNetworkEvent.Unavailable, t2)

    private fun assertCanonicalOffline(state: MonitorState<String>, expectedChangedAt: Instant) {
        assertNull("no network may be tracked", state.network)
        assertNull("no snapshot may be held", state.snapshot)
        assertFalse("must not be available", state.available)
        assertFalse("must not be blocked", state.blocked)
        assertEquals("changedAt must be unchanged by the ignored event", expectedChangedAt, state.changedAt)
    }

    // ---- After Lost ---------------------------------------------------

    @Test
    fun capabilitiesChanged_forLostNetwork_afterLost_doesNotResurrectAvailability() {
        val lost = afterLostA()
        assertCanonicalOffline(lost, t2)

        val after = NetworkEventReducer.reduce(lost, RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot), t3)

        assertCanonicalOffline(after, t2)
        assertEquals(lost, after)
    }

    @Test
    fun blockedStatusChanged_forLostNetwork_afterLost_doesNotCorruptOfflineState() {
        val lost = afterLostA()

        val after = NetworkEventReducer.reduce(lost, RawNetworkEvent.BlockedStatusChanged("A", true), t3)

        assertCanonicalOffline(after, t2)
        assertEquals(lost, after)
    }

    @Test
    fun capabilitiesChanged_forUnrelatedNetwork_afterLost_isDropped() {
        val lost = afterLostA()

        val after = NetworkEventReducer.reduce(lost, RawNetworkEvent.CapabilitiesChanged("B", cellularSnapshot), t3)

        assertEquals(lost, after)
    }

    @Test
    fun blockedStatusChanged_forUnrelatedNetwork_afterLost_isDropped() {
        val lost = afterLostA()

        val after = NetworkEventReducer.reduce(lost, RawNetworkEvent.BlockedStatusChanged("B", true), t3)

        assertEquals(lost, after)
    }

    @Test
    fun repeatedLost_forAlreadyLostNetwork_staysCanonicalOffline() {
        val lost = afterLostA()

        val after = NetworkEventReducer.reduce(lost, RawNetworkEvent.Lost("A"), t3)

        assertCanonicalOffline(after, t2)
    }

    // ---- Reverse-order stale events after Lost ------------------------

    /**
     * Available(A) -> Lost(A) -> stale BlockedStatusChanged(A, true) ->
     * stale CapabilitiesChanged(A, ...), in that (blocked-first) order,
     * chained so each step feeds the next. Asserts the complete state after
     * every step -- not only the fields the defect touched -- so a stale
     * blocked=true leaking into the offline state, a resurrected network or
     * snapshot, or any changedAt bump fails here. The final Available(A)
     * shows recovery is unaffected and that the stale blocked=true did not
     * survive into it. Also serves as the reducer-level combined test of
     * both stale events after Lost(A): the pipeline test below covers the
     * opposite (capabilities-first) order at flow level, and each ignored
     * event returns the state unchanged, so no further chained permutation
     * adds regression value.
     */
    @Test
    fun staleBlockedThenStaleCapabilities_afterLost_leaveCompleteOfflineStateUnchanged() {
        val lost = afterLostA()
        val expectedOffline = MonitorState<String>(
            network = null,
            snapshot = null,
            available = false,
            blocked = false,
            changedAt = t2
        )
        assertEquals("precondition: Lost(A) yields canonical offline at t2", expectedOffline, lost)

        val afterStaleBlocked = NetworkEventReducer.reduce(lost, RawNetworkEvent.BlockedStatusChanged("A", true), t3)
        assertEquals("stale blocked-status must not corrupt the offline state", expectedOffline, afterStaleBlocked)

        val afterStaleCapabilities = NetworkEventReducer.reduce(
            afterStaleBlocked,
            RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot),
            t3.plusSeconds(1)
        )
        assertEquals("stale capabilities must not resurrect A", expectedOffline, afterStaleCapabilities)
        assertEquals("neither stale event may move changedAt", t2, afterStaleCapabilities.changedAt)

        val recovered = NetworkEventReducer.reduce(
            afterStaleCapabilities,
            RawNetworkEvent.Available("A", wifiSnapshot),
            t3.plusSeconds(2)
        )
        assertEquals(
            "recovery via Available(A) still works and starts unblocked",
            MonitorState("A", wifiSnapshot, available = true, blocked = false, changedAt = t3.plusSeconds(2)),
            recovered
        )
    }

    // ---- After Unavailable --------------------------------------------

    @Test
    fun capabilitiesChanged_afterUnavailable_isDropped() {
        val offline = afterUnavailable()
        assertCanonicalOffline(offline, t2)

        val after = NetworkEventReducer.reduce(offline, RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot), t3)

        assertCanonicalOffline(after, t2)
        assertEquals(offline, after)
    }

    @Test
    fun blockedStatusChanged_afterUnavailable_isDropped() {
        val offline = afterUnavailable()

        val after = NetworkEventReducer.reduce(offline, RawNetworkEvent.BlockedStatusChanged("A", true), t3)

        assertCanonicalOffline(after, t2)
        assertEquals(offline, after)
    }

    // ---- Initial state (no network ever tracked) ----------------------

    @Test
    fun capabilitiesChanged_beforeAnyAvailable_isDropped() {
        val initial = MonitorState.initial<String>(t0)

        val after = NetworkEventReducer.reduce(initial, RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot), t1)

        assertCanonicalOffline(after, t0)
        assertEquals(initial, after)
    }

    @Test
    fun blockedStatusChanged_beforeAnyAvailable_isDropped() {
        val initial = MonitorState.initial<String>(t0)

        val after = NetworkEventReducer.reduce(initial, RawNetworkEvent.BlockedStatusChanged("A", true), t1)

        assertCanonicalOffline(after, t0)
        assertEquals(initial, after)
    }

    // ---- Recovery and replacement still work --------------------------

    @Test
    fun recovery_availableForSameNetworkAfterLost_resumesNormalPerNetworkUpdates() {
        var state = afterLostA()

        state = NetworkEventReducer.reduce(state, RawNetworkEvent.Available("A", wifiSnapshot), t3)
        assertEquals("A", state.network)
        assertTrue(state.available)
        assertFalse(state.blocked)

        state = NetworkEventReducer.reduce(state, RawNetworkEvent.BlockedStatusChanged("A", true), t3)
        assertTrue("same-network blocked update must apply after recovery", state.blocked)

        state = NetworkEventReducer.reduce(state, RawNetworkEvent.CapabilitiesChanged("A", cellularSnapshot), t3)
        assertEquals(cellularSnapshot, state.snapshot)
        assertTrue("capabilities update must preserve blocked=true", state.blocked)
        assertTrue(state.available)
    }

    @Test
    fun replacement_staleEventsForLostNetwork_doNotAffectNewlyAvailableNetwork() {
        var state = afterLostA()
        state = NetworkEventReducer.reduce(state, RawNetworkEvent.Available("B", cellularSnapshot), t3)
        val tracked = state
        assertEquals("B", tracked.network)

        val afterStaleCaps = NetworkEventReducer.reduce(
            tracked,
            RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot),
            t3.plusSeconds(1)
        )
        val afterStaleBlocked = NetworkEventReducer.reduce(
            afterStaleCaps,
            RawNetworkEvent.BlockedStatusChanged("A", true),
            t3.plusSeconds(2)
        )

        assertEquals(tracked, afterStaleBlocked)
        assertEquals("B", afterStaleBlocked.network)
        assertEquals(cellularSnapshot, afterStaleBlocked.snapshot)
        assertFalse(afterStaleBlocked.blocked)
        assertEquals(t3, afterStaleBlocked.changedAt)
    }

    // ---- changedAt for ignored events on a tracked network ------------

    @Test
    fun foreignEvents_whileNetworkTracked_preserveChangedAt() {
        val tracked = established()

        val afterForeignCaps = NetworkEventReducer.reduce(
            tracked,
            RawNetworkEvent.CapabilitiesChanged("B", cellularSnapshot),
            t2
        )
        val afterForeignBlocked = NetworkEventReducer.reduce(
            afterForeignCaps,
            RawNetworkEvent.BlockedStatusChanged("B", true),
            t3
        )

        assertEquals(tracked, afterForeignBlocked)
        assertEquals(t1, afterForeignBlocked.changedAt)
    }

    // ---- Pipeline: stale events after Lost emit nothing ---------------

    @Test
    fun pipeline_staleCapabilitiesAndBlockedAfterLost_doNotResurrectOrReBlockOfflineState() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            emit(RawNetworkEvent.Lost("A"))
            delay(10)
            // Late callbacks for the already-lost network.
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", true))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)
        assertTrue(collected[0].available)

        // t = 511: Lost folded at t = 500, stale events folded at t = 510.
        advanceTimeBy(210)
        runCurrent()
        // Past any debounce window a resurrected state would have needed.
        advanceTimeBy(1000)
        runCurrent()
        job.cancel()

        assertEquals("stale events after Lost must not produce any further emission", 2, collected.size)
        val last = collected.last()
        assertFalse("offline state must not be resurrected", last.available)
        assertFalse("offline state must not be re-blocked", last.blockedByDevicePolicy)
    }

    private companion object {
        /** Keeps each test's event flow open for its duration; never actually awaited. */
        const val UPSTREAM_IDLE_MS = 1_000_000L
    }
}
