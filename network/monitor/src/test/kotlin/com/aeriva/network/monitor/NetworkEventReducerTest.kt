package com.aeriva.network.monitor

import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [N] is a plain [String] throughout -- see [RawNetworkEvent]'s own KDoc
 * for why a plain JVM test can stand in any comparable type for the
 * network identity [AndroidNetworkMonitor] supplies as real
 * `android.net.Network` instances in production.
 */
class NetworkEventReducerTest {

    private val t0 = Instant.parse("2026-09-11T00:00:00Z")
    private val t1 = Instant.parse("2026-09-11T00:00:01Z")
    private val t2 = Instant.parse("2026-09-11T00:00:02Z")

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED")
    )

    private val cellularSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = false,
        transports = setOf(TransportType.CELLULAR),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED")
    )

    @Test
    fun initial_isCanonicalOffline() {
        val state = MonitorState.initial<String>(t0)

        assertNull(state.network)
        assertNull(state.snapshot)
        assertFalse(state.available)
        assertFalse(state.blocked)
    }

    @Test
    fun available_tracksNetworkAndStartsUnblocked() {
        val state = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        assertEquals("A", state.network)
        assertEquals(wifiSnapshot, state.snapshot)
        assertTrue(state.available)
        assertFalse(state.blocked)
        assertEquals(t1, state.changedAt)
    }

    @Test
    fun available_withNullSnapshot_representsUnknownRatherThanInventingCapabilities() {
        // "Android cannot provide reliable information" case: onAvailable
        // fired but the follow-up getNetworkCapabilities lookup returned
        // null. Must not fabricate a snapshot -- NetworkStateMapper's own
        // null-snapshot handling then produces the canonical offline
        // NetworkState downstream.
        val state = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", snapshot = null),
            t1
        )

        assertEquals("A", state.network)
        assertNull(state.snapshot)
        assertTrue(state.available)
    }

    @Test
    fun capabilitiesChanged_forTrackedNetwork_updatesSnapshot() {
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterCapabilities = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.CapabilitiesChanged("A", cellularSnapshot),
            t2
        )

        assertEquals(cellularSnapshot, afterCapabilities.snapshot)
        assertEquals("A", afterCapabilities.network)
    }

    @Test
    fun capabilitiesChanged_forForeignNetwork_isDroppedNotAppliedToTrackedState() {
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterForeignCapabilities = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.CapabilitiesChanged("B", cellularSnapshot),
            t2
        )

        // Must still reflect A's data, not B's -- a capabilities event for
        // a network we are not tracking as the current default must never
        // silently overwrite what we do hold.
        assertEquals("A", afterForeignCapabilities.network)
        assertEquals(wifiSnapshot, afterForeignCapabilities.snapshot)
    }

    @Test
    fun blockedStatusChanged_forTrackedNetwork_setsBlockedWithoutTouchingSnapshot() {
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterBlocked = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.BlockedStatusChanged("A", blocked = true),
            t2
        )

        assertTrue(afterBlocked.blocked)
        assertEquals(wifiSnapshot, afterBlocked.snapshot)
        assertEquals("A", afterBlocked.network)
    }

    @Test
    fun blockedStatusChanged_forForeignNetwork_isDropped() {
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterForeignBlocked = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.BlockedStatusChanged("B", blocked = true),
            t2
        )

        assertFalse(afterForeignBlocked.blocked)
        assertEquals("A", afterForeignBlocked.network)
    }

    @Test
    fun blockedThenUnblocked_reflectsLatestValue() {
        var state: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )
        state = NetworkEventReducer.reduce(state, RawNetworkEvent.BlockedStatusChanged("A", true), t2)
        assertTrue(state.blocked)

        state = NetworkEventReducer.reduce(state, RawNetworkEvent.BlockedStatusChanged("A", false), t2)
        assertFalse(state.blocked)
    }

    @Test
    fun lost_forTrackedNetwork_resetsToCanonicalOffline_includingBlockedFlag() {
        var state: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )
        state = NetworkEventReducer.reduce(state, RawNetworkEvent.BlockedStatusChanged("A", true), t1)

        val afterLost = NetworkEventReducer.reduce(state, RawNetworkEvent.Lost("A"), t2)

        assertNull(afterLost.network)
        assertNull(afterLost.snapshot)
        assertFalse(afterLost.available)
        // The stale blocked=true from the lost network must not survive --
        // a fresh Lost never carries forward a previous network's flags.
        assertFalse(afterLost.blocked)
    }

    @Test
    fun lost_forForeignNetwork_isDroppedNotAppliedToTrackedState() {
        // Per ConnectivityManager.NetworkCallback#onLost's own documented
        // contract this should not happen for a default-network callback,
        // but a stray/out-of-order Lost for a network we are not tracking
        // must never wipe real, current state -- this is the "stale state"
        // requirement.
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterForeignLost = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.Lost("B"),
            t2
        )

        assertEquals("A", afterForeignLost.network)
        assertEquals(wifiSnapshot, afterForeignLost.snapshot)
        assertTrue(afterForeignLost.available)
    }

    @Test
    fun unavailable_resetsRegardlessOfPreviouslyTrackedNetwork() {
        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterUnavailable = NetworkEventReducer.reduce(afterAvailable, RawNetworkEvent.Unavailable, t2)

        assertNull(afterUnavailable.network)
        assertNull(afterUnavailable.snapshot)
        assertFalse(afterUnavailable.available)
        assertFalse(afterUnavailable.blocked)
    }

    @Test
    fun wifiToCellularTransition_freshAvailable_replacesTrackedNetworkCleanly() {
        // Per the platform's own documented contract for
        // registerDefaultNetworkCallback, a default-network switch is a
        // fresh onAvailable for the new network, not onLost(old) followed
        // by onAvailable(new). This is the "Wi-Fi -> cellular" transition
        // case this change's tests are required to cover.
        var state: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("wifi-net", wifiSnapshot),
            t1
        )
        state = NetworkEventReducer.reduce(state, RawNetworkEvent.BlockedStatusChanged("wifi-net", false), t1)

        val afterSwitch = NetworkEventReducer.reduce(
            state,
            RawNetworkEvent.Available("cellular-net", cellularSnapshot),
            t2
        )

        assertEquals("cellular-net", afterSwitch.network)
        assertEquals(cellularSnapshot, afterSwitch.snapshot)
        // Must not retain Wi-Fi's data in any field.
        assertFalse(afterSwitch.snapshot == wifiSnapshot)
        // Must not retain a blocked flag from the old network by accident
        // (Available always resets blocked=false; the real value for the
        // new network arrives as its own guaranteed follow-up event).
        assertFalse(afterSwitch.blocked)
    }

    @Test
    fun cellularToWifiTransition_freshAvailable_replacesTrackedNetworkCleanly() {
        var state: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("cellular-net", cellularSnapshot),
            t1
        )

        val afterSwitch = NetworkEventReducer.reduce(
            state,
            RawNetworkEvent.Available("wifi-net", wifiSnapshot),
            t2
        )

        assertEquals("wifi-net", afterSwitch.network)
        assertEquals(wifiSnapshot, afterSwitch.snapshot)
    }

    @Test
    fun disconnectedThenConnected_reflectsNewNetworkNotStaleOfflineFlags() {
        // Explicit type argument required here: with no expected-type
        // context, inferring N purely from (MonitorState.initial(t0),
        // RawNetworkEvent.Unavailable) resolves N = Nothing (Unavailable's
        // static type is RawNetworkEvent<Nothing>, and covariance makes
        // that a valid-looking match for any N) -- which then rejects the
        // Available("A", ...) call below, whose argument type is
        // RawNetworkEvent<String>, not assignable to the poisoned
        // RawNetworkEvent<Nothing>. Every other test in this file avoids
        // the trap because its first event already carries concrete type
        // evidence (e.g. Available("A", ...)) instead of Unavailable.
        val afterUnavailable: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Unavailable,
            t1
        )
        assertFalse(afterUnavailable.available)

        val afterConnected = NetworkEventReducer.reduce(
            afterUnavailable,
            RawNetworkEvent.Available("A", wifiSnapshot),
            t2
        )

        assertTrue(afterConnected.available)
        assertEquals("A", afterConnected.network)
        assertEquals(wifiSnapshot, afterConnected.snapshot)
    }

    @Test
    fun vpnAppearingOverExistingTransport_isJustACapabilitiesChangeOnTheSameNetwork() {
        // A VPN riding over an existing default network typically arrives
        // as a capabilities update on that same Network object (the
        // platform does not necessarily allocate a new Network for it in
        // every case AERIVA can rely on), so this must flow through the
        // ordinary CapabilitiesChanged path with no special-casing that
        // could drop it.
        val vpnOverWifiSnapshot = wifiSnapshot.copy(
            transports = setOf(TransportType.VPN, TransportType.WIFI)
        )

        val afterAvailable = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", wifiSnapshot),
            t1
        )

        val afterVpn = NetworkEventReducer.reduce(
            afterAvailable,
            RawNetworkEvent.CapabilitiesChanged("A", vpnOverWifiSnapshot),
            t2
        )

        assertEquals(vpnOverWifiSnapshot, afterVpn.snapshot)
        assertTrue(TransportType.VPN in afterVpn.snapshot!!.transports)
    }

    @Test
    fun vpnDisconnecting_capabilitiesChangeDropsVpnTransportOnSameNetwork() {
        val vpnOverWifiSnapshot = wifiSnapshot.copy(
            transports = setOf(TransportType.VPN, TransportType.WIFI)
        )

        var state: MonitorState<String> = NetworkEventReducer.reduce(
            MonitorState.initial(t0),
            RawNetworkEvent.Available("A", vpnOverWifiSnapshot),
            t1
        )

        state = NetworkEventReducer.reduce(
            state,
            RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot),
            t2
        )

        assertFalse(TransportType.VPN in state.snapshot!!.transports)
    }
}
