package com.aeriva.app.home

import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType

/**
 * What the home screen is allowed to say about connectivity. Pure Kotlin
 * (no Android types) so the mapping from [NetworkState] is unit testable on
 * the JVM.
 *
 * Deliberately has no "stale details while offline" shape: [Offline],
 * [Initializing] and [NoNetworkReported] carry no transport or capability
 * data, so the UI cannot render old connection information as current.
 */
sealed interface HomeUiState {

    /** Nothing has been reported by the platform yet. Not "connected", not "offline". */
    data object Initializing : HomeUiState

    /**
     * No platform report arrived within the grace period. The monitor is
     * callback-driven and produces no event when there is no default
     * network at all, so silence is the expected signal for "no network",
     * but it is still an inference and is labelled as such in the UI.
     */
    data object NoNetworkReported : HomeUiState

    /** The monitor reported that no usable default network is available. */
    data object Offline : HomeUiState

    /** A network exists but device policy blocks it. */
    data class Blocked(
        val transport: TransportType,
        val vpnPresent: Boolean,
        val vpnUnderlyingTransport: TransportType = TransportType.NONE
    ) : HomeUiState

    /**
     * A default network is available and not policy-blocked. [internet]
     * reflects Android's own validation signal; it is not proof that any
     * particular service is reachable.
     */
    data class Connected(
        val transport: TransportType,
        val internet: InternetStatus,
        val metered: Boolean,
        val vpnPresent: Boolean,
        val vpnUnderlyingTransport: TransportType = TransportType.NONE
    ) : HomeUiState
}

enum class InternetStatus {
    /** Android's connectivity check passed (NET_CAPABILITY_VALIDATED). */
    VALIDATED,

    /** Network is up but Android has not validated internet access. */
    NOT_VALIDATED,

    /** The platform reported a captive portal (sign-in page). */
    CAPTIVE_PORTAL
}

/** Maps a real, monitor-produced [NetworkState] to UI state. */
fun NetworkState.toHomeUiState(): HomeUiState = when {
    !available -> HomeUiState.Offline

    blockedByDevicePolicy -> HomeUiState.Blocked(
        transport = transport,
        vpnPresent = vpnPresent,
        vpnUnderlyingTransport = vpnUnderlyingTransport
    )

    else -> HomeUiState.Connected(
        transport = transport,
        internet = when {
            captivePortalReported -> InternetStatus.CAPTIVE_PORTAL
            validated -> InternetStatus.VALIDATED
            else -> InternetStatus.NOT_VALIDATED
        },
        metered = metered,
        vpnPresent = vpnPresent,
        vpnUnderlyingTransport = vpnUnderlyingTransport
    )
}
