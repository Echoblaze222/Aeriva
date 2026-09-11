package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.flow.Flow

/**
 * Produces normalized [NetworkState] for the device's current default
 * network, per architecture doc Section 6. This is the only interface
 * anything above the platform layer should depend on -- never
 * android.net.ConnectivityManager directly (architecture doc Section 5).
 */
interface NetworkMonitor {
    fun observe(): Flow<NetworkState>
}
