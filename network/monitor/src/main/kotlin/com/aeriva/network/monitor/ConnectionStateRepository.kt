package com.aeriva.network.monitor

import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * Roadmap Phase 2 ("Network Detection") calls for a "connection state
 * service" as its own deliverable, separate from the raw monitor --
 * see 08_DEVELOPMENT_ROADMAP.md Phase 2. [NetworkMonitor] only exposes a
 * cold [kotlinx.coroutines.flow.Flow]; this repository is what turns that
 * into a single shared, always-current state that multiple features
 * (dashboard, diagnostics, permission gating, etc.) can read without each
 * one standing up its own ConnectivityManager registration -- per
 * architecture doc Section 5, features must depend on a repository, not
 * on the platform layer directly.
 *
 * "Offline" is exposed as its own named property rather than left for
 * callers to derive from [NetworkState.available], per architecture doc
 * Section 5.1 (UI state must be able to represent an explicit Offline
 * state, not just infer it) and Phase 0's finding that a network can be
 * [NetworkState.available] = true while still not
 * [NetworkState.validated] (e.g. a captive portal) -- that case is not
 * "offline" in the everyday sense but also is not fully connected, so it
 * is deliberately NOT folded into [isOffline]. [isOffline] means "no
 * usable default network at all."
 */
class ConnectionStateRepository(
    monitor: NetworkMonitor,
    scope: CoroutineScope,
    private val logger: AerivaLogger
) {

    val state: StateFlow<NetworkState> = monitor.observe()
        .stateIn(
            scope = scope,
            // Phase 2 exit criteria requires correct detection of real
            // transitions -- WhileSubscribed(0) would drop the
            // ConnectivityManager registration between the last
            // unsubscribe and next subscribe, which risks missing a
            // transition that happens while nothing is observing.
            // Eagerly keeps AERIVA's one shared registration alive for
            // the process lifetime instead.
            started = SharingStarted.Eagerly,
            initialValue = NetworkState.unknown(at = Instant.now())
        )

    /**
     * True only when there is no usable default network. See class doc
     * for why this is distinct from "not validated."
     */
    val isOffline: Boolean
        get() = !state.value.available

    init {
        logger.i(TAG, "ConnectionStateRepository initialized")
    }

    private companion object {
        const val TAG = "ConnectionStateRepository"
    }
}
