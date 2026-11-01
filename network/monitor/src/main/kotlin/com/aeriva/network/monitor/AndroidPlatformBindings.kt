package com.aeriva.network.monitor

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Android bindings shared by every monitor in this module, so the platform
 * callback and the capability curation exist exactly once.
 */

/**
 * A real platform callback that forwards every method to [sink]. Used by both
 * [AndroidNetworkMonitor] (default network) and the Network Advisor candidate
 * monitor (all networks).
 */
internal fun networkCallbackFor(
    sink: PlatformCallbackSink<Network, NetworkCapabilities>
): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = sink.onAvailable(network)

    override fun onCapabilitiesChanged(
        network: Network,
        capabilities: NetworkCapabilities
    ) = sink.onCapabilitiesChanged(network, capabilities)

    // Added in API 29 (VERIFIED FACT, current official reference,
    // ConnectivityManager.NetworkCallback#onBlockedStatusChanged).
    // minSdk is 26: overriding a callback method added in a later
    // SDK level than the device's own is safe on Android -- the
    // framework simply never invokes it on a device whose OS
    // predates it. No @RequiresApi is needed because nothing here
    // calls the superclass's platform implementation.
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
        sink.onBlockedStatusChanged(network, blocked)

    override fun onLost(network: Network) = sink.onLost(network)

    override fun onUnavailable() = sink.onUnavailable()
}

internal fun NetworkCapabilities.toRawSnapshot(): RawCapabilitiesSnapshot {
    val transports = ALL_TRANSPORT_CONSTANTS
        .filter { hasTransport(it) }
        .mapNotNull(::mapTransportConstant)
        .toSet()

    val presentNamedCapabilities = NAMED_CAPABILITIES_OF_INTEREST
        .filter { (constant, _) -> hasCapability(constant) }
        .map { (_, name) -> name }
        .toSet()

    return RawCapabilitiesSnapshot(
        hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        isNotMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        transports = transports,
        rawCapabilityNames = presentNamedCapabilities
    )
}

private val ALL_TRANSPORT_CONSTANTS = listOf(
    NetworkCapabilities.TRANSPORT_VPN,
    NetworkCapabilities.TRANSPORT_ETHERNET,
    NetworkCapabilities.TRANSPORT_WIFI,
    NetworkCapabilities.TRANSPORT_CELLULAR,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_LOWPAN,
    NetworkCapabilities.TRANSPORT_USB,
    NetworkCapabilities.TRANSPORT_WIFI_AWARE
)

// Curated, not exhaustive. Listed here, by name, only because we
// can vouch for what each one means -- do not add a constant to
// this list without also being able to explain it in the brand's
// plain-language voice, per engineering standards Section 17.
private val NAMED_CAPABILITIES_OF_INTEREST = listOf(
    NetworkCapabilities.NET_CAPABILITY_INTERNET to "INTERNET",
    NetworkCapabilities.NET_CAPABILITY_VALIDATED to "VALIDATED",
    NetworkCapabilities.NET_CAPABILITY_NOT_METERED to "NOT_METERED",
    NetworkCapabilities.NET_CAPABILITY_NOT_VPN to "NOT_VPN",
    NetworkCapabilities.NET_CAPABILITY_TRUSTED to "TRUSTED",
    NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED to "NOT_RESTRICTED",
    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL to "CAPTIVE_PORTAL"
)
