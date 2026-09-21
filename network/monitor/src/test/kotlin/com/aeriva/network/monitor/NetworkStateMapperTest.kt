package com.aeriva.network.monitor

import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NetworkStateMapperTest {

    private val now = Instant.parse("2026-09-11T00:00:00Z")

    @Test
    fun notAvailable_producesCanonicalOfflineState() {
        val state = NetworkStateMapper.buildNetworkState(
            available = false,
            snapshot = null,
            changedAt = now
        )

        assertEquals(TransportType.NONE, state.transport)
        assertFalse(state.available)
        assertFalse(state.validated)
        assertFalse(state.metered)
        assertEquals(emptySet<String>(), state.capabilities)
        assertEquals(NetworkQuality.Unavailable, state.estimatedQuality)
        assertEquals(DiagnosticsStatus.NotAvailable, state.diagnosticsStatus)
        assertEquals(now, state.lastChangedAt)
        assertFalse(state.captivePortalReported)
        assertFalse(state.vpnPresent)
        assertFalse(state.blockedByDevicePolicy)
    }

    @Test
    fun availableWithNullSnapshot_alsoProducesOfflineState() {
        // Defensive case: available flagged true but no capabilities
        // arrived yet. Must not fabricate a connected state from nothing.
        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = null,
            changedAt = now
        )

        assertFalse(state.available)
        assertEquals(TransportType.NONE, state.transport)
    }

    @Test
    fun vpnOverWifi_reportsVpnTransport() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.VPN, TransportType.WIFI),
            rawCapabilityNames = setOf("INTERNET", "VALIDATED")
        )

        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = snapshot,
            changedAt = now
        )

        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
    }

    @Test
    fun ethernetOverWifi_reportsEthernetTransport() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.ETHERNET, TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = snapshot,
            changedAt = now
        )

        assertEquals(TransportType.ETHERNET, state.transport)
    }

    @Test
    fun meteredFlag_isInverseOfNotMetered() {
        val meteredSnapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = false,
            transports = setOf(TransportType.CELLULAR),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = meteredSnapshot,
            changedAt = now
        )

        assertEquals(true, state.metered)
    }

    @Test
    fun unvalidatedWifi_reportsValidatedFalse() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = false,
            isValidated = false,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = snapshot,
            changedAt = now
        )

        assertFalse(state.validated)
        assertEquals(TransportType.WIFI, state.transport)
    }

    @Test
    fun quality_isAlwaysUnavailableInPhase2() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertEquals(NetworkQuality.Unavailable, state.estimatedQuality)
        assertEquals(DiagnosticsStatus.NotAvailable, state.diagnosticsStatus)
    }

    @Test
    fun noRecognizedTransport_fallsBackToNone() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = emptySet(),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertEquals(TransportType.NONE, state.transport)
    }

    // -- Phase 4: captivePortalReported, vpnPresent, blockedByDevicePolicy
    // (PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-8) --

    @Test
    fun captivePortalCapabilityPresent_reportsCaptivePortalReportedTrue() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = false,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = setOf("CAPTIVE_PORTAL")
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertTrue(state.captivePortalReported)
    }

    @Test
    fun noCaptivePortalCapability_reportsCaptivePortalReportedFalse() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = setOf("VALIDATED")
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertFalse(state.captivePortalReported)
    }

    @Test
    fun wifiOnly_noVpnTransport_reportsVpnPresentFalse() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertFalse(state.vpnPresent)
    }

    @Test
    fun vpnPresent_isTrue_evenWhenAnotherTransportIsReportedForDisplay() {
        // The whole point of Decision D4-8's vpnPresent field: VPN over
        // Ethernet still shows Ethernet-priority-adjacent... actually VPN
        // wins display priority (resolveTransport), but vpnPresent must
        // still independently read true from the raw transport set, not
        // from the resolved display transport.
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.VPN, TransportType.ETHERNET),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
    }

    @Test
    fun blocked_defaultsFalse_whenCallerDoesNotPassIt() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(true, snapshot, now)

        assertFalse(state.blockedByDevicePolicy)
    }

    @Test
    fun blocked_passesThroughWhenCallerSuppliesIt() {
        val snapshot = RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = true,
            isNotMetered = true,
            transports = setOf(TransportType.WIFI),
            rawCapabilityNames = emptySet()
        )

        val state = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = snapshot,
            changedAt = now,
            blocked = true
        )

        assertTrue(state.blockedByDevicePolicy)
    }
}
