package com.aeriva.network.monitor

import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
