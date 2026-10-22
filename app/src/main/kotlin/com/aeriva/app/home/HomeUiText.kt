package com.aeriva.app.home

import com.aeriva.app.R
import com.aeriva.core.model.TransportType

/** One label/value line in the details card. */
data class DetailRow(
    val label: Int,
    val value: Int
)

fun HomeUiState.headlineRes(): Int = when (this) {
    HomeUiState.Initializing -> R.string.status_initializing_headline
    HomeUiState.NoNetworkReported -> R.string.status_no_network_reported_headline
    HomeUiState.Offline -> R.string.status_offline_headline
    is HomeUiState.Blocked -> R.string.status_blocked_headline
    is HomeUiState.Connected -> when (internet) {
        InternetStatus.VALIDATED -> R.string.status_connected_headline
        InternetStatus.NOT_VALIDATED -> R.string.status_limited_headline
        InternetStatus.CAPTIVE_PORTAL -> R.string.status_signin_headline
    }
}

fun HomeUiState.messageRes(): Int = when (this) {
    HomeUiState.Initializing -> R.string.status_initializing_message
    HomeUiState.NoNetworkReported -> R.string.status_no_network_reported_message
    HomeUiState.Offline -> R.string.status_offline_message
    is HomeUiState.Blocked -> R.string.status_blocked_message
    is HomeUiState.Connected -> when (internet) {
        InternetStatus.VALIDATED -> R.string.status_connected_message
        InternetStatus.NOT_VALIDATED -> R.string.status_limited_message
        InternetStatus.CAPTIVE_PORTAL -> R.string.status_signin_message
    }
}

fun HomeUiState.indicatorColorRes(): Int = when (this) {
    HomeUiState.Initializing -> R.color.aeriva_unknown
    HomeUiState.NoNetworkReported -> R.color.aeriva_offline
    HomeUiState.Offline -> R.color.aeriva_offline
    is HomeUiState.Blocked -> R.color.aeriva_warning
    is HomeUiState.Connected -> when (internet) {
        InternetStatus.VALIDATED -> R.color.aeriva_ice_blue
        InternetStatus.NOT_VALIDATED, InternetStatus.CAPTIVE_PORTAL -> R.color.aeriva_warning
    }
}

/**
 * Detail rows are produced only for states that carry real connection data.
 * Offline / initializing states return an empty list, so no previous
 * connection's details can linger on screen.
 */
fun HomeUiState.detailRows(): List<DetailRow> = when (this) {
    HomeUiState.Initializing,
    HomeUiState.NoNetworkReported,
    HomeUiState.Offline -> emptyList()

    is HomeUiState.Blocked -> buildList {
        add(DetailRow(R.string.detail_network_type, transport.labelRes()))
        add(DetailRow(R.string.detail_device_policy, R.string.value_policy_blocking))
        if (vpnPresent) add(DetailRow(R.string.detail_vpn, R.string.value_vpn_active))
    }

    is HomeUiState.Connected -> buildList {
        add(DetailRow(R.string.detail_network_type, transport.labelRes()))
        add(
            DetailRow(
                R.string.detail_internet_check,
                when (internet) {
                    InternetStatus.VALIDATED -> R.string.value_internet_validated
                    InternetStatus.NOT_VALIDATED -> R.string.value_internet_not_validated
                    InternetStatus.CAPTIVE_PORTAL -> R.string.value_internet_captive_portal
                }
            )
        )
        add(
            DetailRow(
                R.string.detail_data_cost,
                if (metered) R.string.value_metered else R.string.value_not_metered
            )
        )
        if (vpnPresent) add(DetailRow(R.string.detail_vpn, R.string.value_vpn_active))
    }
}

private fun TransportType.labelRes(): Int = when (this) {
    TransportType.WIFI -> R.string.transport_wifi
    TransportType.CELLULAR -> R.string.transport_cellular
    TransportType.ETHERNET -> R.string.transport_ethernet
    TransportType.VPN -> R.string.transport_vpn
    TransportType.OTHER -> R.string.transport_other
    TransportType.NONE -> R.string.transport_not_reported
}
