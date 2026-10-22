package com.aeriva.app.home

import com.aeriva.app.R
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Behavior of [toHomeUiState]: what the home screen is allowed to claim for
 * each shape of real [NetworkState] the monitor can produce. Inputs are
 * built the way NetworkStateMapper builds them (offline = NONE transport,
 * nothing else populated), not hand-picked UI-friendly values.
 */
class HomeUiStateMappingTest {

    private val at = Instant.EPOCH.plusSeconds(10)

    private fun online(
        transport: TransportType = TransportType.WIFI,
        validated: Boolean = true,
        metered: Boolean = false,
        captivePortal: Boolean = false,
        vpn: Boolean = false,
        blocked: Boolean = false,
        vpnUnderlying: TransportType = TransportType.NONE
    ) = NetworkState(
        transport = transport,
        available = true,
        validated = validated,
        metered = metered,
        capabilities = emptySet(),
        estimatedQuality = NetworkQuality.Unavailable,
        diagnosticsStatus = DiagnosticsStatus.NotAvailable,
        lastChangedAt = at,
        captivePortalReported = captivePortal,
        vpnPresent = vpn,
        blockedByDevicePolicy = blocked,
        vpnUnderlyingTransport = vpnUnderlying
    )

    private val offline = NetworkState(
        transport = TransportType.NONE,
        available = false,
        validated = false,
        metered = false,
        capabilities = emptySet(),
        estimatedQuality = NetworkQuality.Unavailable,
        diagnosticsStatus = DiagnosticsStatus.NotAvailable,
        lastChangedAt = at,
        captivePortalReported = false,
        vpnPresent = false,
        blockedByDevicePolicy = false
    )

    @Test
    fun unavailableNetwork_isOffline_andCarriesNoConnectionDetails() {
        val ui = offline.toHomeUiState()

        assertEquals(HomeUiState.Offline, ui)
        assertEquals(emptyList<DetailRow>(), ui.detailRows())
    }

    @Test
    fun availableAndValidated_isConnectedWithValidatedInternet() {
        val ui = online(transport = TransportType.CELLULAR, metered = true).toHomeUiState()

        assertEquals(
            HomeUiState.Connected(
                transport = TransportType.CELLULAR,
                internet = InternetStatus.VALIDATED,
                metered = true,
                vpnPresent = false
            ),
            ui
        )
    }

    @Test
    fun availableButNotValidated_isNotPresentedAsConfirmedInternet() {
        val ui = online(validated = false).toHomeUiState() as HomeUiState.Connected

        assertEquals(InternetStatus.NOT_VALIDATED, ui.internet)
    }

    @Test
    fun captivePortalReported_takesPrecedenceOverValidated() {
        val ui = online(validated = true, captivePortal = true).toHomeUiState() as HomeUiState.Connected

        assertEquals(InternetStatus.CAPTIVE_PORTAL, ui.internet)
    }

    @Test
    fun blockedByDevicePolicy_isBlockedEvenWhenValidated() {
        val ui = online(blocked = true, validated = true, vpn = true).toHomeUiState()

        assertEquals(HomeUiState.Blocked(TransportType.WIFI, vpnPresent = true), ui)
    }

    @Test
    fun vpnPresent_isCarriedThroughForConnectedState() {
        val ui = online(transport = TransportType.VPN, vpn = true).toHomeUiState() as HomeUiState.Connected

        assertEquals(true, ui.vpnPresent)
        assertEquals(TransportType.VPN, ui.transport)
    }

    @Test
    fun availableWithNoRecognizedTransport_keepsNoneInsteadOfInventingOne() {
        val ui = online(transport = TransportType.NONE).toHomeUiState() as HomeUiState.Connected

        assertEquals(TransportType.NONE, ui.transport)
    }

    @Test
    fun vpnOverWifi_showsUnderlyingNetworkRow_andVpnRow() {
        val ui = online(transport = TransportType.VPN, vpn = true, vpnUnderlying = TransportType.WIFI)
            .toHomeUiState()

        val rows = ui.detailRows()
        assertTrue(DetailRow(R.string.detail_underlying_network, R.string.transport_wifi) in rows)
        assertTrue(DetailRow(R.string.detail_vpn, R.string.value_vpn_active) in rows)
        assertTrue(rows.none { it.value == R.string.transport_vpn })
    }

    @Test
    fun vpnOverCellular_showsMobileDataAsUnderlying() {
        val rows = online(transport = TransportType.VPN, vpn = true, vpnUnderlying = TransportType.CELLULAR)
            .toHomeUiState().detailRows()

        assertTrue(DetailRow(R.string.detail_underlying_network, R.string.transport_cellular) in rows)
    }

    @Test
    fun vpnWithUnknownUnderlying_saysNotReported_insteadOfGuessing() {
        val rows = online(transport = TransportType.VPN, vpn = true).toHomeUiState().detailRows()

        assertTrue(DetailRow(R.string.detail_underlying_network, R.string.transport_not_reported) in rows)
        assertTrue(DetailRow(R.string.detail_vpn, R.string.value_vpn_active) in rows)
    }

    @Test
    fun vpnStatus_isShownAlongsideInternetCheck_forNotValidated() {
        val rows = online(
            transport = TransportType.VPN, vpn = true, validated = false,
            vpnUnderlying = TransportType.WIFI
        ).toHomeUiState().detailRows()

        assertTrue(DetailRow(R.string.detail_internet_check, R.string.value_internet_not_validated) in rows)
        assertTrue(DetailRow(R.string.detail_vpn, R.string.value_vpn_active) in rows)
    }

    @Test
    fun blockedVpn_showsUnderlyingAndPolicyRows() {
        val rows = online(
            transport = TransportType.VPN, vpn = true, blocked = true,
            vpnUnderlying = TransportType.WIFI
        ).toHomeUiState().detailRows()

        assertTrue(DetailRow(R.string.detail_underlying_network, R.string.transport_wifi) in rows)
        assertTrue(DetailRow(R.string.detail_device_policy, R.string.value_policy_blocking) in rows)
    }

    @Test
    fun nonVpnConnection_keepsPlainNetworkTypeRow_andNoVpnRow() {
        val rows = online(transport = TransportType.WIFI).toHomeUiState().detailRows()

        assertTrue(DetailRow(R.string.detail_network_type, R.string.transport_wifi) in rows)
        assertTrue(rows.none { it.label == R.string.detail_underlying_network })
        assertTrue(rows.none { it.label == R.string.detail_vpn })
    }
}
