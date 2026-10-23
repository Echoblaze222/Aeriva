package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.advisor.CandidateNetwork
import kotlinx.coroutines.flow.Flow

/**
 * Source of every network the device can currently use (spec section 5.1).
 * No `android.net` type appears in this signature.
 *
 * Interface only. The Android implementation (a `registerNetworkCallback`
 * adapter feeding [CandidateEventReducer]) is a separate leader approved
 * task that waits for the network monitor reliability seam decision
 * (leader decision L4). It must not be written against the current
 * unhardened `trySend` pattern.
 *
 * Separate from, and does not replace, the existing default network
 * monitor.
 */
interface AvailableNetworksMonitor {
    fun observe(): Flow<List<CandidateNetwork>>
}
