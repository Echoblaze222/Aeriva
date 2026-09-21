package com.aeriva.network.monitor

import java.time.Instant

/**
 * Folded state [NetworkEventReducer] carries between platform callback
 * events, for exactly one tracked network at a time (matching
 * `registerDefaultNetworkCallback`'s own single-current-network
 * contract -- see [NetworkEventReducer]'s own KDoc).
 *
 * Generic over the network identity type ([N]) for the same reason
 * [RawNetworkEvent] is: see that type's KDoc.
 */
internal data class MonitorState<N>(
    val network: N?,
    val snapshot: RawCapabilitiesSnapshot?,
    val available: Boolean,
    val blocked: Boolean,
    val changedAt: Instant
) {
    companion object {
        /** State before any real platform callback has arrived. */
        fun <N> initial(at: Instant): MonitorState<N> = MonitorState(
            network = null,
            snapshot = null,
            available = false,
            blocked = false,
            changedAt = at
        )
    }
}

/**
 * Platform-callback events, translated into plain data. Generic over the
 * network identity type ([N]) purely so [NetworkEventReducer] is
 * unit-testable on the plain JVM: `android.net.Network` cannot be
 * constructed in a plain JVM unit test (the stub android.jar throws --
 * see [TransportConstantMapper]'s own file comment on the same
 * constraint), but every decision this reducer makes only ever needs
 * network *identity* (`==`), never any of `Network`'s methods. A plain
 * JVM test can therefore stand in any comparable type (a [String], an
 * [Int]) for [N] and exercise the exact same decision logic that
 * [AndroidNetworkMonitor] runs in production against real
 * `android.net.Network` instances -- [AndroidNetworkMonitor] is the only
 * file that supplies `N = android.net.Network`.
 *
 * [Available] and [CapabilitiesChanged] carry an already-resolved
 * [RawCapabilitiesSnapshot] (or null, for "Android had nothing to give
 * us yet" -- see [MonitorState] and the "unknown state" rule in this
 * change's implementation notes) rather than resolving it themselves,
 * so that no synchronous `ConnectivityManager` call ever happens from
 * inside this pure function -- that resolution is
 * [AndroidNetworkMonitor]'s job, done outside the actual
 * `NetworkCallback` method body (see that file's own comment on
 * `ConnectivityManager.NetworkCallback`'s documented race-condition
 * warning against calling `getNetworkCapabilities`/`getLinkProperties`
 * synchronously from inside a callback).
 */
internal sealed interface RawNetworkEvent<out N> {
    data class Available<N>(val network: N, val snapshot: RawCapabilitiesSnapshot?) : RawNetworkEvent<N>
    data class CapabilitiesChanged<N>(val network: N, val snapshot: RawCapabilitiesSnapshot) : RawNetworkEvent<N>
    data class BlockedStatusChanged<N>(val network: N, val blocked: Boolean) : RawNetworkEvent<N>
    data class Lost<N>(val network: N) : RawNetworkEvent<N>
    data object Unavailable : RawNetworkEvent<Nothing>
}

/**
 * Pure fold from (current [MonitorState], next [RawNetworkEvent]) to the
 * next [MonitorState]. No Android import in this file, matching
 * [NetworkStateMapper]'s existing convention.
 *
 * Why this exists as its own stateful fold, rather than the previous
 * per-event-independent mapping: `blockedByDevicePolicy`
 * (PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-8)
 * comes from `onBlockedStatusChanged`, which the platform fires
 * independently of `onCapabilitiesChanged` -- a capabilities update must
 * not silently forget the last-known blocked value, and a blocked-status
 * update must not silently forget the last-known capabilities. Both need
 * a place to persist across events for the *same* network. This was
 * deferred, not built, by the change that added the `blocked` field to
 * [NetworkStateMapper.buildNetworkState] -- see that function's own KDoc
 * and this change's implementation notes.
 *
 * The single-tracked-network model below matches
 * `ConnectivityManager.registerDefaultNetworkCallback`'s own documented
 * contract (VERIFIED FACT, current official reference,
 * `ConnectivityManager.NetworkCallback#onAvailable`): "this callback
 * will no longer receive method calls about other networks that may
 * have been passed to this method previously", and
 * `NetworkCallback#onLost`: "it will only be invoked against the last
 * network returned by onAvailable() when that network is lost and no
 * other network satisfies the criteria of the request." A default-network
 * switch (Wi-Fi to cellular or back) is therefore reported as a fresh
 * `onAvailable` for the new network -- not an `onLost` for the old one
 * followed by an `onAvailable` for the new one. This reducer's guards
 * (below) exist for the case the platform's own contract does not
 * strictly rule out: an out-of-order or foreign-network event arriving
 * for a network this reducer is not currently tracking. Per this
 * change's "no stale state" and "no invented values" requirements, such
 * an event is dropped rather than allowed to corrupt or overwrite state
 * for the network actually being tracked.
 */
internal object NetworkEventReducer {

    fun <N> reduce(state: MonitorState<N>, event: RawNetworkEvent<N>, changedAt: Instant): MonitorState<N> =
        when (event) {
            RawNetworkEvent.Unavailable ->
                // No default network satisfies the request at all. Always
                // resets, regardless of whatever network was previously
                // tracked -- this is the platform's own unambiguous "there
                // is nothing" signal (ConnectivityManager.NetworkCallback
                // #onUnavailable), not a per-network event.
                offline(changedAt)

            is RawNetworkEvent.Lost ->
                if (event.network == state.network) {
                    offline(changedAt)
                } else {
                    // Per this function's own KDoc: onLost is only ever
                    // supposed to name the last network from onAvailable.
                    // An event naming anything else is treated as stale/
                    // foreign and dropped rather than wiping real state --
                    // this is the "stale state after a transition" case
                    // this change's tests cover explicitly.
                    state
                }

            is RawNetworkEvent.Available ->
                MonitorState(
                    network = event.network,
                    snapshot = event.snapshot,
                    available = true,
                    // A newly-available network starts unblocked, not
                    // "unknown" -- per the platform's own documented
                    // guarantee (VERIFIED FACT, current official reference,
                    // ConnectivityManager.NetworkCallback#onAvailable:
                    // "Starting with Build.VERSION_CODES.O, this will
                    // always immediately be followed by ... a call to
                    // onBlockedStatusChanged"), the real value always
                    // arrives as its own event immediately after, on every
                    // SDK level this repository supports (minSdk 26 = O).
                    // This is therefore never the value callers actually
                    // observe for more than one event.
                    blocked = false,
                    changedAt = changedAt
                )

            is RawNetworkEvent.CapabilitiesChanged ->
                if (state.network != null && event.network != state.network) {
                    state
                } else {
                    state.copy(
                        network = event.network,
                        snapshot = event.snapshot,
                        available = true,
                        changedAt = changedAt
                    )
                }

            is RawNetworkEvent.BlockedStatusChanged ->
                if (state.network != null && event.network != state.network) {
                    state
                } else {
                    state.copy(blocked = event.blocked, changedAt = changedAt)
                }
        }

    private fun <N> offline(changedAt: Instant): MonitorState<N> = MonitorState(
        network = null,
        snapshot = null,
        available = false,
        blocked = false,
        changedAt = changedAt
    )
}
