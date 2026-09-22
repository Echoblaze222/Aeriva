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
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * [NetworkMonitor] backed by [ConnectivityManager.registerDefaultNetworkCallback],
 * per architecture doc Section 6 ("Android network monitoring should be
 * built around platform connectivity APIs and callbacks") and Phase 0's
 * platform validation.
 *
 * Raw platform events reach [NetworkEventReducer] undebounced (Phase 4,
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-8, and
 * the fix for audit finding CF-1, PHASE_4_NETWORK_STATE_INTEGRATION_AUDIT.md,
 * branch phase-4-network-state-audit @ b4f0e6c): `blockedByDevicePolicy`
 * comes from `NetworkCallback.onBlockedStatusChanged`, which the platform
 * fires independently of, but always immediately after,
 * `onCapabilitiesChanged` (VERIFIED FACT, current official reference,
 * `ConnectivityManager.NetworkCallback#onAvailable`: "Starting with
 * Build.VERSION_CODES.O, this will always immediately be followed by a
 * call to onCapabilitiesChanged(...) then ... and a call to
 * onBlockedStatusChanged(...)" -- true for every SDK level this
 * repository supports, since minSdk 26 = O). [NetworkEventReducer] needs
 * every event in that burst, in order, to establish which network is
 * active before a same-network `BlockedStatusChanged` means anything --
 * debouncing the *raw* event stream can silently discard the
 * `Available`/`CapabilitiesChanged` events it depends on, since the
 * platform's guaranteed burst fires well inside any realistic debounce
 * window (this is exactly what audit finding CF-1 identified: earlier
 * versions of this file debounced the raw stream, and a fast burst could
 * collapse to a lone `BlockedStatusChanged` that the reducer could not
 * use, leaving the monitor stuck offline or stuck on a stale network
 * after a transition). See [foldNetworkStateFlow]'s own KDoc for the full
 * mechanism and [NetworkEventReducer]'s KDoc for the platform contract
 * (`onAvailable`/`onLost`'s documented single-current-network behavior)
 * its guards rely on.
 *
 * Debouncing (architecture doc Section 6.2) is applied only to the
 * already-folded [NetworkState] output, by [foldNetworkStateFlow] --
 * never to the raw event stream. A [NetworkState] that just became
 * unusable (`available = false` or `blockedByDevicePolicy = true`) is
 * never delayed -- "debouncing must not delay user-visible network
 * failures excessively" is explicit in the architecture doc, and a
 * delayed offline/blocked indicator is worse than a slightly chattier
 * one. Every other change is debounced by [debounceMillis] because
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

    override fun observe(): Flow<NetworkState> = foldNetworkStateFlow(
        events = platformEvents().map(::toRawNetworkEvent),
        debounceMillis = debounceMillis,
        now = now
    )

    private fun platformEvents(): Flow<PlatformEvent> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(PlatformEvent.Available(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(PlatformEvent.CapabilitiesChanged(network, capabilities))
            }

            // Added in API 29 (VERIFIED FACT, current official reference,
            // ConnectivityManager.NetworkCallback#onBlockedStatusChanged).
            // minSdk is 26: overriding a callback method added in a later
            // SDK level than the device's own is safe on Android -- the
            // framework simply never invokes it on a device whose OS
            // predates it, and the class still loads and verifies (the
            // widely-used pattern for exactly this method; see e.g. public
            // examples that override it with no SDK_INT guard). No
            // @RequiresApi is needed because nothing here calls the
            // superclass's platform implementation.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                trySend(PlatformEvent.BlockedStatusChanged(network, blocked))
            }

            override fun onLost(network: Network) {
                trySend(PlatformEvent.Lost(network))
            }

            override fun onUnavailable() {
                trySend(PlatformEvent.Unavailable)
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

    /**
     * Resolves capability data outside the actual `NetworkCallback`
     * method body, per `ConnectivityManager.NetworkCallback`'s own
     * documented warning: "Do NOT call getNetworkCapabilities(Network) or
     * getLinkProperties(Network) or other synchronous ConnectivityManager
     * methods in this callback as this is prone to race conditions."
     * [platformEvents] only ever `trySend`s into the callback channel; the
     * actual synchronous lookup for [PlatformEvent.Available] happens here,
     * downstream, on the flow's own collection, never inside
     * `onAvailable` itself. This conversion runs undebounced (see this
     * file's own class KDoc and [foldNetworkStateFlow]) so it sees every
     * platform event, in order, with nothing dropped before it reaches
     * [NetworkEventReducer].
     */
    private fun toRawNetworkEvent(event: PlatformEvent): RawNetworkEvent<Network> = when (event) {
        is PlatformEvent.Available -> {
            // onAvailable does not itself carry capabilities; ask for the
            // current snapshot directly rather than waiting for a
            // separate onCapabilitiesChanged callback, so callers never
            // observe a network as "available" with stale/absent data.
            // A null result here (capabilities not yet obtainable) is
            // passed through as-is -- Android had nothing reliable to
            // give us, so nothing is invented; see RawNetworkEvent's own
            // KDoc.
            val capabilities = connectivityManager?.getNetworkCapabilities(event.network)
            RawNetworkEvent.Available(event.network, capabilities?.let(::toSnapshot))
        }

        is PlatformEvent.CapabilitiesChanged ->
            RawNetworkEvent.CapabilitiesChanged(event.network, toSnapshot(event.capabilities))

        is PlatformEvent.BlockedStatusChanged ->
            RawNetworkEvent.BlockedStatusChanged(event.network, event.blocked)

        is PlatformEvent.Lost -> RawNetworkEvent.Lost(event.network)

        PlatformEvent.Unavailable -> RawNetworkEvent.Unavailable
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

    /** Raw platform callback events, one-to-one with the NetworkCallback methods used. */
    private sealed interface PlatformEvent {
        data class Available(val network: Network) : PlatformEvent
        data class CapabilitiesChanged(
            val network: Network,
            val capabilities: NetworkCapabilities
        ) : PlatformEvent
        data class BlockedStatusChanged(val network: Network, val blocked: Boolean) : PlatformEvent
        data class Lost(val network: Network) : PlatformEvent
        data object Unavailable : PlatformEvent
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
