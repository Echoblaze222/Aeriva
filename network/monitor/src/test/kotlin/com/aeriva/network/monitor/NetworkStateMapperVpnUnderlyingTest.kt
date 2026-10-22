package com.aeriva.network.monitor

import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [NetworkStateMapper] reporting of the transport a VPN runs over.
 *
 * Android merges the underlying network's transports into the VPN network's
 * capabilities, so a VPN over Wi-Fi arrives as {VPN, WIFI}. The mapper must
 * report the underlying transport only when exactly one is present, and
 * "not reported" ([TransportType.NONE]) otherwise. It must never guess, and
 * must leave the existing display transport (VPN-first), validation,
 * captive-portal, offline and blocked behavior unchanged.
 */
class NetworkStateMapperVpnUnderlyingTest {

    private val changedAt = Instant.parse("2026-10-22T08:00:00Z")

    private fun snapshot(
        transports: Set<TransportType>,
        names: Set<String> = emptySet(),
        validated: Boolean = true
    ) = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = validated,
        isNotMetered = true,
        transports = transports,
        rawCapabilityNames = names
    )

    private fun map(
        transports: Set<TransportType>,
        names: Set<String> = emptySet(),
        validated: Boolean = true,
        blocked: Boolean = false
    ) = NetworkStateMapper.buildNetworkState(
        available = true,
        snapshot = snapshot(transports, names, validated),
        changedAt = changedAt,
        blocked = blocked
    )

    @Test
    fun vpnOverWifi_reportsWifiAsUnderlying_andKeepsVpnAsDisplayTransport() {
        val state = map(setOf(TransportType.VPN, TransportType.WIFI))

        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
        assertEquals(TransportType.WIFI, state.vpnUnderlyingTransport)
    }

    @Test
    fun vpnOverCellular_reportsCellularAsUnderlying() {
        val state = map(setOf(TransportType.VPN, TransportType.CELLULAR))

        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
        assertEquals(TransportType.CELLULAR, state.vpnUnderlyingTransport)
    }

    @Test
    fun vpnOverEthernet_reportsEthernetAsUnderlying() {
        val state = map(setOf(TransportType.VPN, TransportType.ETHERNET))

        assertEquals(TransportType.ETHERNET, state.vpnUnderlyingTransport)
    }

    @Test
    fun vpnWithNoUnderlyingTransportReported_isNotReported_notGuessed() {
        val state = map(setOf(TransportType.VPN))

        assertEquals(TransportType.VPN, state.transport)
        assertTrue(state.vpnPresent)
        assertEquals(TransportType.NONE, state.vpnUnderlyingTransport)
    }

    @Test
    fun vpnWithSeveralUnderlyingTransports_isNotReported_insteadOfPickingByDisplayPriority() {
        val state = map(setOf(TransportType.VPN, TransportType.WIFI, TransportType.CELLULAR))

        assertTrue(state.vpnPresent)
        assertEquals(TransportType.NONE, state.vpnUnderlyingTransport)
    }

    @Test
    fun vpnStatus_isReportedAlongsideInternetValidation_forBothOutcomes() {
        val validated = map(setOf(TransportType.VPN, TransportType.WIFI), validated = true)
        val unvalidated = map(setOf(TransportType.VPN, TransportType.WIFI), validated = false)

        assertTrue(validated.vpnPresent)
        assertTrue(validated.validated)
        assertEquals(TransportType.WIFI, validated.vpnUnderlyingTransport)
        assertTrue(unvalidated.vpnPresent)
        assertFalse(unvalidated.validated)
        assertEquals(TransportType.WIFI, unvalidated.vpnUnderlyingTransport)
    }

    @Test
    fun vpnWithCaptivePortalCapability_stillReportsCaptivePortalAndUnderlying() {
        val state = map(
            setOf(TransportType.VPN, TransportType.WIFI),
            names = setOf("CAPTIVE_PORTAL"),
            validated = false
        )

        assertTrue(state.captivePortalReported)
        assertTrue(state.vpnPresent)
        assertEquals(TransportType.WIFI, state.vpnUnderlyingTransport)
    }

    @Test
    fun blockedVpn_keepsBlockedFlag_andUnderlying() {
        val state = map(setOf(TransportType.VPN, TransportType.WIFI), blocked = true)

        assertTrue(state.blockedByDevicePolicy)
        assertEquals(TransportType.WIFI, state.vpnUnderlyingTransport)
    }

    @Test
    fun nonVpnConnections_haveNoUnderlyingTransport() {
        listOf(
            setOf(TransportType.WIFI),
            setOf(TransportType.CELLULAR),
            setOf(TransportType.ETHERNET),
            setOf(TransportType.WIFI, TransportType.CELLULAR)
        ).forEach { transports ->
            val state = map(transports)

            assertFalse("vpnPresent for $transports", state.vpnPresent)
            assertEquals("underlying for $transports", TransportType.NONE, state.vpnUnderlyingTransport)
        }
    }

    @Test
    fun nonVpnDisplayTransport_isUnchanged() {
        assertEquals(TransportType.WIFI, map(setOf(TransportType.WIFI)).transport)
        assertEquals(
            TransportType.ETHERNET,
            map(setOf(TransportType.ETHERNET, TransportType.WIFI)).transport
        )
        assertEquals(TransportType.CELLULAR, map(setOf(TransportType.CELLULAR)).transport)
    }

    @Test
    fun unavailableNetwork_carriesNoVpnOrUnderlyingInformation_evenWithASnapshotInHand() {
        val state = NetworkStateMapper.buildNetworkState(
            available = false,
            snapshot = snapshot(setOf(TransportType.VPN, TransportType.WIFI)),
            changedAt = changedAt
        )

        assertFalse(state.available)
        assertFalse(state.vpnPresent)
        assertEquals(TransportType.NONE, state.transport)
        assertEquals(TransportType.NONE, state.vpnUnderlyingTransport)
    }
}
