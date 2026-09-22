package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
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

/**
 * Folds a stream of raw platform events into the [NetworkState] flow
 * [AndroidNetworkMonitor.observe] exposes: the fold (via
 * [NetworkEventReducer.reduce]) plus the debounce that coalesces rapid
 * *output* changes for slow consumers (architecture doc Section 6.2).
 *
 * Debounce here is applied to the **already-folded, already-mapped**
 * [NetworkState] output -- never to [events] itself. This is the direct
 * fix for audit finding CF-1
 * (PHASE_4_NETWORK_STATE_INTEGRATION_AUDIT.md, branch
 * phase-4-network-state-audit @ b4f0e6c): debounce previously ran on the
 * raw, pre-fold platform-event stream, upstream of this fold. The
 * platform's own guaranteed onAvailable -> onCapabilitiesChanged ->
 * onBlockedStatusChanged burst (VERIFIED FACT, see
 * [AndroidNetworkMonitor]'s own KDoc) fires well inside any realistic
 * debounce window, and kotlinx.coroutines' `debounce` -- which, per its
 * own current documentation, "filters out values that are followed by
 * the newer values within the given timeout... the latest value is
 * always emitted" -- silently discarded the `Available`/
 * `CapabilitiesChanged` events [NetworkEventReducer] needs to establish
 * `state.network` before a same-network `BlockedStatusChanged` can do
 * anything useful. Moving debounce here means [events] always reaches
 * [NetworkEventReducer.reduce] in full and in order: a lifecycle burst
 * can never lose the events that establish which network is active.
 * Only the fully-folded *result* of a burst is ever coalesced, and only
 * after it already reflects every event the burst contained -- so
 * debounce here cannot corrupt what the reducer sees, by construction:
 * it sits entirely after the reducer in the operator chain, with nothing
 * feeding back.
 *
 * Generic over the network identity type ([N]) for the same reason
 * [RawNetworkEvent] is: [AndroidNetworkMonitor] is the only caller that
 * supplies `N = android.net.Network`. A plain JVM test can drive this
 * with a synthetic `Flow<RawNetworkEvent<String>>` -- including one that
 * emits an entire onAvailable/onCapabilitiesChanged/onBlockedStatusChanged
 * burst with no delay between events, the exact scenario CF-1 was found
 * in -- and observe real (virtual-time) debounce behavior, with no
 * Android and no Robolectric involved. See `NetworkStatePipelineTest`.
 *
 * @param debounceMillis how long a [NetworkState] that still represents
 *   a usable, unblocked network waits for further changes before being
 *   emitted. A [NetworkState] that just became unusable -- `available =
 *   false` (offline/lost) or `blockedByDevicePolicy = true` (a device
 *   policy just blocked this app on this network) -- is never delayed:
 *   this preserves the architecture doc's own requirement ("debouncing
 *   must not delay user-visible network failures excessively") and is
 *   the output-level equivalent of what the pre-fix, event-level
 *   debounce selector did for `Lost`/`Unavailable`/
 *   `BlockedStatusChanged(blocked = true)`.
 */
internal fun <N> foldNetworkStateFlow(
    events: Flow<RawNetworkEvent<N>>,
    debounceMillis: Long,
    now: () -> Instant
): Flow<NetworkState> = events
    .scan(MonitorState.initial<N>(now())) { state, event -> NetworkEventReducer.reduce(state, event, now()) }
    // Drop the seed value: callers must only ever observe a state that
    // reflects a real platform event, never a pre-event placeholder.
    .drop(1)
    .map { state ->
        NetworkStateMapper.buildNetworkState(
            available = state.available,
            snapshot = state.snapshot,
            changedAt = state.changedAt,
            blocked = state.blocked
        )
    }
    .debounce { networkState ->
        if (!networkState.available || networkState.blockedByDevicePolicy) 0L else debounceMillis
    }
    // A dropped/ignored event (e.g. a foreign-network Lost, per
    // NetworkEventReducer's guards) still produces a scan emission whose
    // content is unchanged from the previous one -- collapse those
    // rather than surface a no-op update to callers.
    .distinctUntilChanged()
