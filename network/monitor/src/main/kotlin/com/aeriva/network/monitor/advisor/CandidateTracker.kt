package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.network.monitor.NetworkStateMapper
import com.aeriva.network.monitor.RawCapabilitiesSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import java.time.Instant

/**
 * What the reducer remembers about one tracked network. [snapshot] is null
 * when the platform announced the network but no capabilities have arrived
 * yet; such a network is tracked but not exposed as a candidate, so no
 * value is ever invented for it.
 */
internal data class TrackedCandidate(
    val snapshot: RawCapabilitiesSnapshot?,
    val blocked: Boolean,
    val changedAt: Instant
)

/**
 * Folded state of the candidate tracker: every tracked network by opaque id,
 * plus which id the platform last reported as the default network.
 *
 * [defaultId] is kept separately from [tracked] on purpose. The default
 * network callback and the all networks callback are separate platform
 * registrations with no ordering guarantee between them, so a default
 * report may arrive before the matching Available.
 */
internal data class CandidateTrackerState(
    val tracked: Map<CandidateNetworkId, TrackedCandidate>,
    val defaultId: CandidateNetworkId?
) {
    /**
     * Candidates in ascending id order, so equal states always produce equal
     * lists. Interpretation of the capability facts is delegated to the
     * existing [NetworkStateMapper] so transport priority, captive portal
     * and VPN detection are not duplicated.
     */
    fun candidates(): List<CandidateNetwork> =
        tracked.entries
            .sortedBy { it.key.value }
            .mapNotNull { entry ->
                val snapshot = entry.value.snapshot ?: return@mapNotNull null
                val facts = NetworkStateMapper.buildNetworkState(
                    available = true,
                    snapshot = snapshot,
                    changedAt = entry.value.changedAt,
                    blocked = entry.value.blocked
                )
                CandidateNetwork(
                    id = entry.key,
                    transport = facts.transport,
                    validated = facts.validated,
                    metered = facts.metered,
                    captivePortalReported = facts.captivePortalReported,
                    blockedByDevicePolicy = facts.blockedByDevicePolicy,
                    vpnPresent = facts.vpnPresent,
                    isDefault = entry.key == defaultId,
                    observedAt = entry.value.changedAt
                )
            }

    companion object {
        val EMPTY = CandidateTrackerState(tracked = emptyMap(), defaultId = null)
    }
}

/**
 * Platform callback events for the all networks registration, plus the
 * default network report, translated into plain data. Identity is the
 * opaque [CandidateNetworkId], supplied by the Android adapter, so this
 * file needs no generic network type and no Android import.
 *
 * As in the existing monitor, snapshots arrive already resolved, so no
 * synchronous ConnectivityManager call can happen inside the reducer.
 */
internal sealed interface RawCandidateEvent {
    data class Available(val id: CandidateNetworkId, val snapshot: RawCapabilitiesSnapshot?) : RawCandidateEvent
    data class CapabilitiesChanged(val id: CandidateNetworkId, val snapshot: RawCapabilitiesSnapshot) : RawCandidateEvent
    data class BlockedStatusChanged(val id: CandidateNetworkId, val blocked: Boolean) : RawCandidateEvent
    data class Lost(val id: CandidateNetworkId) : RawCandidateEvent

    /** The platform's current default network, or null when there is none. */
    data class DefaultChanged(val id: CandidateNetworkId?) : RawCandidateEvent
}

/**
 * Pure fold from (state, event) to the next state, tracking many networks.
 * No Android import.
 *
 * Ordering guarantee reused from the existing reducer, applied per network:
 * only [RawCandidateEvent.Available] may establish a tracked network. A
 * [RawCandidateEvent.CapabilitiesChanged] or
 * [RawCandidateEvent.BlockedStatusChanged] for an id that is not tracked
 * (never announced, or already lost) is dropped, so after `Lost(n)` a late
 * event for `n` cannot resurrect it. An ignored event returns the state
 * unchanged, including every observedAt.
 *
 * Known limit: a network id the platform reuses after Lost would be
 * accepted again on its next Available. That is correct behavior for a
 * genuinely new network and is not distinguishable here.
 */
internal object CandidateEventReducer {

    fun reduce(state: CandidateTrackerState, event: RawCandidateEvent, changedAt: Instant): CandidateTrackerState =
        when (event) {
            is RawCandidateEvent.Available -> onAvailable(state, event, changedAt)
            is RawCandidateEvent.CapabilitiesChanged -> onCapabilitiesChanged(state, event, changedAt)
            is RawCandidateEvent.BlockedStatusChanged -> onBlockedStatusChanged(state, event, changedAt)
            is RawCandidateEvent.Lost -> onLost(state, event)
            is RawCandidateEvent.DefaultChanged -> onDefaultChanged(state, event)
        }

    private fun onAvailable(
        state: CandidateTrackerState,
        event: RawCandidateEvent.Available,
        changedAt: Instant
    ): CandidateTrackerState = state.copy(
        // A newly available network starts unblocked; the real value arrives
        // as its own BlockedStatusChanged, as in the existing reducer.
        tracked = state.tracked + (event.id to TrackedCandidate(event.snapshot, blocked = false, changedAt = changedAt))
    )

    private fun onCapabilitiesChanged(
        state: CandidateTrackerState,
        event: RawCandidateEvent.CapabilitiesChanged,
        changedAt: Instant
    ): CandidateTrackerState {
        val existing = state.tracked[event.id] ?: return state
        return state.copy(
            tracked = state.tracked + (event.id to existing.copy(snapshot = event.snapshot, changedAt = changedAt))
        )
    }

    private fun onBlockedStatusChanged(
        state: CandidateTrackerState,
        event: RawCandidateEvent.BlockedStatusChanged,
        changedAt: Instant
    ): CandidateTrackerState {
        val existing = state.tracked[event.id] ?: return state
        return state.copy(
            tracked = state.tracked + (event.id to existing.copy(blocked = event.blocked, changedAt = changedAt))
        )
    }

    private fun onLost(state: CandidateTrackerState, event: RawCandidateEvent.Lost): CandidateTrackerState {
        val wasTracked = event.id in state.tracked
        val wasDefault = state.defaultId == event.id
        if (!wasTracked && !wasDefault) return state
        return state.copy(
            tracked = state.tracked - event.id,
            // A lost network cannot remain the default.
            defaultId = if (wasDefault) null else state.defaultId
        )
    }

    private fun onDefaultChanged(
        state: CandidateTrackerState,
        event: RawCandidateEvent.DefaultChanged
    ): CandidateTrackerState =
        if (state.defaultId == event.id) state else state.copy(defaultId = event.id)
}

/**
 * Folds raw candidate events into the candidate list flow the future
 * [AvailableNetworksMonitor] implementation will expose. No debounce is
 * applied here: dwell is the job of [RecommendationGate], so every folded
 * state, including intermediate burst states, stays visible to a collector.
 * Consecutive equal lists are collapsed.
 */
internal fun foldCandidateListFlow(
    events: Flow<RawCandidateEvent>,
    now: () -> Instant
): Flow<List<CandidateNetwork>> = events
    .scan(CandidateTrackerState.EMPTY) { state, event -> CandidateEventReducer.reduce(state, event, now()) }
    // Drop the seed so a collector only sees state that reflects a real event.
    .drop(1)
    .map { it.candidates() }
    .distinctUntilChanged()
