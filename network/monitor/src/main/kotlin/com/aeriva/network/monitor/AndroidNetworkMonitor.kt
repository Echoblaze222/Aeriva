package com.aeriva.network.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * [NetworkMonitor] backed by [ConnectivityManager.registerDefaultNetworkCallback],
 * per architecture doc Section 6 ("Android network monitoring should be
 * built around platform connectivity APIs and callbacks") and Phase 0's
 * platform validation.
 *
 * Debouncing (architecture doc Section 6.2): loss-type events
 * ([RawNetworkEvent.Lost], [RawNetworkEvent.Unavailable]) are never
 * debounced -- "debouncing must not delay user-visible network failures
 * excessively" is explicit in the architecture doc, and a delayed offline
 * indicator is worse than a slightly chattier one. Availability and
 * capability-change events are debounced by [debounceMillis] because
 * onCapabilitiesChanged is documented to fire repeatedly in bursts as a
 * connection comes up (e.g. validation arriving after initial connect).
 */
class AndroidNetworkMonitor(
    context: Context,
    private val logger: AerivaLogger,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val now: () -> Instant = Instant::now
) : NetworkMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<NetworkState> = rawEvents()
        .debounce { event ->
            when (event) {
                is RawNetworkEvent.Lost, RawNetworkEvent.Unavailable -> 0L
                else -> debounceMillis
            }
        }
        .map { event -> toNetworkState(event) }

    private fun rawEvents(): Flow<RawNetworkEvent> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(RawNetworkEvent.Available(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(RawNetworkEvent.CapabilitiesChanged(network, capabilities))
            }

            override fun onLost(network: Network) {
                trySend(RawNetworkEvent.Lost(network))
            }

            override fun onUnavailable() {
                trySend(RawNetworkEvent.Unavailable)
            }
        }

        logger.i(TAG, "Registering default network callback")
        connectivityManager?.registerDefaultNetworkCallback(callback)
            ?: logger.w(TAG, "ConnectivityManager unavailable, no network updates will be delivered")

        awaitClose {
            logger.i(TAG, "Unregistering default network callback")
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }

    private fun toNetworkState(event: RawNetworkEvent): NetworkState = when (event) {
        is RawNetworkEvent.Lost, RawNetworkEvent.Unavailable ->
            NetworkStateMapper.buildNetworkState(available = false, snapshot = null, changedAt = now())

        is RawNetworkEvent.Available -> {
            // onAvailable does not itself carry capabilities; ask for the
            // current snapshot directly rather than waiting for a
            // separate onCapabilitiesChanged callback, so callers never
            // observe a network as "available" with stale/absent data.
            val capabilities = connectivityManager?.getNetworkCapabilities(event.network)
            NetworkStateMapper.buildNetworkState(
                available = true,
                snapshot = capabilities?.let(::toSnapshot),
                changedAt = now()
            )
        }

        is RawNetworkEvent.CapabilitiesChanged ->
            NetworkStateMapper.buildNetworkState(
                available = true,
                snapshot = toSnapshot(event.capabilities),
                changedAt = now()
            )
    }

    private fun toSnapshot(capabilities: NetworkCapabilities): RawCapabilitiesSnapshot {
        val transports = ALL_TRANSPORT_CONSTANTS
            .filter { capabilities.hasTransport(it) }
            .mapNotNull(::mapTransportConstant)
            .toSet()

        val presentNamedCapabilities = NAMED_CAPABILITIES_OF_INTEREST
            .filter { (constant, _) -> capabilities.hasCapability(constant) }
            .map { (_, name) -> name }
            .toSet()

        return RawCapabilitiesSnapshot(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transports = transports,
            rawCapabilityNames = presentNamedCapabilities
        )
    }

    private sealed interface RawNetworkEvent {
        data class Available(val network: Network) : RawNetworkEvent
        data class CapabilitiesChanged(
            val network: Network,
            val capabilities: NetworkCapabilities
        ) : RawNetworkEvent
        data class Lost(val network: Network) : RawNetworkEvent
        data object Unavailable : RawNetworkEvent
    }

    private companion object {
        const val TAG = "AndroidNetworkMonitor"
        const val DEFAULT_DEBOUNCE_MILLIS = 300L

        val ALL_TRANSPORT_CONSTANTS = listOf(
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
        val NAMED_CAPABILITIES_OF_INTEREST = listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET to "INTERNET",
            NetworkCapabilities.NET_CAPABILITY_VALIDATED to "VALIDATED",
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED to "NOT_METERED",
            NetworkCapabilities.NET_CAPABILITY_NOT_VPN to "NOT_VPN",
            NetworkCapabilities.NET_CAPABILITY_TRUSTED to "TRUSTED",
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED to "NOT_RESTRICTED",
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL to "CAPTIVE_PORTAL"
        )
    }
}
