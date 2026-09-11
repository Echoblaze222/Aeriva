package com.aeriva.network.monitor

import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import java.time.Instant

/**
 * Pure mapping from a raw capabilities snapshot to [NetworkState]. No
 * Android import in this file -- see the module build.gradle.kts comment
 * for why that matters. This is where every "what does this actually
 * mean" decision lives, so it is the thing to unit test, not the
 * ConnectivityManager glue.
 */
internal object NetworkStateMapper {

    /**
     * @param available whether the platform currently has a default
     *   network at all. When false, [snapshot] is expected to be null and
     *   the result is the canonical "no network" state.
     * @param snapshot capability data for the current default network,
     *   or null if [available] is false or capabilities have not arrived
     *   yet.
     */
    fun buildNetworkState(
        available: Boolean,
        snapshot: RawCapabilitiesSnapshot?,
        changedAt: Instant
    ): NetworkState {
        if (!available || snapshot == null) {
            return NetworkState(
                transport = TransportType.NONE,
                available = false,
                validated = false,
                metered = false,
                capabilities = emptySet(),
                estimatedQuality = NetworkQuality.Unavailable,
                diagnosticsStatus = DiagnosticsStatus.NotAvailable,
                lastChangedAt = changedAt
            )
        }

        return NetworkState(
            transport = resolveTransport(snapshot.transports),
            available = true,
            validated = snapshot.isValidated,
            metered = !snapshot.isNotMetered,
            capabilities = snapshot.rawCapabilityNames,
            // Phase 2 (Network Detection) does not measure quality or
            // diagnostics -- that is Phase 3 (Measurement Engine). Do not
            // invent a value here; see architecture doc Section 6.
            estimatedQuality = NetworkQuality.Unavailable,
            diagnosticsStatus = DiagnosticsStatus.NotAvailable,
            lastChangedAt = changedAt
        )
    }

    /**
     * A network can technically report more than one transport bit
     * (e.g. a VPN riding over Wi-Fi). Priority order below is a
     * deliberate choice, not a platform guarantee: VPN is reported first
     * because it is the transport the user's traffic actually observes,
     * which matters most for a product whose job is explaining the
     * user's effective connection. Ethernet before Wi-Fi before Cellular
     * follows general specificity/reliability ordering. Documented here
     * because NetworkCapabilities does not itself define a priority.
     */
    private fun resolveTransport(transports: Set<TransportType>): TransportType = when {
        TransportType.VPN in transports -> TransportType.VPN
        TransportType.ETHERNET in transports -> TransportType.ETHERNET
        TransportType.WIFI in transports -> TransportType.WIFI
        TransportType.CELLULAR in transports -> TransportType.CELLULAR
        TransportType.OTHER in transports -> TransportType.OTHER
        else -> TransportType.NONE
    }
}
