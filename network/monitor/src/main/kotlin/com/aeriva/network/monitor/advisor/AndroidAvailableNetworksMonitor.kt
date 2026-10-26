package com.aeriva.network.monitor.advisor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.network.monitor.PlatformCallbackRegistrar
import com.aeriva.network.monitor.networkCallbackFor
import com.aeriva.network.monitor.toRawSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * [AvailableNetworksMonitor] backed by `registerNetworkCallback` (every
 * network) and `registerDefaultNetworkCallback` (which one is the default).
 * All logic lives in [AdvisorCandidateSource]; this class only binds it to the
 * Android framework.
 *
 * The all networks request removes `NET_CAPABILITY_NOT_VPN`, which a request
 * carries by default (AOSP `NetworkCapabilities` default capabilities), so VPN
 * networks are visible and the selector can apply its VPN rules. It requires
 * `NET_CAPABILITY_INTERNET` so networks that cannot carry internet traffic are
 * not listed. Both registrations need only `ACCESS_NETWORK_STATE`, which the
 * existing monitor already uses. No new permission.
 *
 * A network is identified by `Network.getNetworkHandle()` (API 23), which is a
 * stable `Long` for as long as the network exists.
 */
class AndroidAvailableNetworksMonitor(
    context: Context,
    private val logger: AerivaLogger,
    now: () -> Instant = Instant::now
) : AvailableNetworksMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val source = AdvisorCandidateSource<Network, NetworkCapabilities>(
        allNetworks = registrar(allNetworksRequest()),
        defaultNetwork = registrar(null),
        idOf = { CandidateNetworkId(it.networkHandle) },
        snapshotOf = { it.toRawSnapshot() },
        logger = logger,
        now = now
    )

    /** True while the candidate list may be out of date (events may have been lost). */
    val stale: StateFlow<Boolean>
        get() = source.stale

    /** Events rejected by a full callback buffer since this monitor was created. */
    val droppedEventCount: Long
        get() = source.droppedEventCount

    override fun observe(): Flow<List<CandidateNetwork>> = source.observe()

    private fun allNetworksRequest(): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    /** A null [request] registers the default network callback. */
    private fun registrar(request: NetworkRequest?) =
        PlatformCallbackRegistrar<Network, NetworkCapabilities> { sink ->
            val manager = connectivityManager
            if (manager == null) {
                logger.w(TAG, "ConnectivityManager unavailable, no network updates will be delivered")
                AutoCloseable { }
            } else {
                val callback = networkCallbackFor(sink)
                if (request == null) {
                    manager.registerDefaultNetworkCallback(callback)
                } else {
                    manager.registerNetworkCallback(request, callback)
                }
                AutoCloseable { manager.unregisterNetworkCallback(callback) }
            }
        }

    private companion object {
        const val TAG = "AndroidAvailableNetworksMonitor"
    }
}
