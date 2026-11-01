package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.network.monitor.RawCapabilitiesSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM tests for [CandidateEventReducer] and [foldCandidateListFlow]
 * (spec section 7, candidate reducer list). Plain Kotlin only.
 */
class CandidateEventReducerTest {

    private val t0 = Instant.parse("2026-10-23T10:00:00Z")
    private val t1 = t0.plusSeconds(1)
    private val t2 = t0.plusSeconds(2)
    private val t3 = t0.plusSeconds(3)
    private val t4 = t0.plusSeconds(4)

    private val a = CandidateNetworkId(1)
    private val b = CandidateNetworkId(2)
    private val c = CandidateNetworkId(3)

    private fun snap(
        vararg transports: TransportType,
        validated: Boolean = true,
        notMetered: Boolean = true,
        captive: Boolean = false
    ) = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = validated,
        isNotMetered = notMetered,
        transports = transports.toSet(),
        rawCapabilityNames = buildSet {
            add("INTERNET")
            if (validated) add("VALIDATED")
            if (notMetered) add("NOT_METERED")
            if (captive) add("CAPTIVE_PORTAL")
        }
    )

    private val wifi = snap(TransportType.WIFI)
    private val cellular = snap(TransportType.CELLULAR, notMetered = false)

    private fun reduce(state: CandidateTrackerState, event: RawCandidateEvent, at: Instant) =
        CandidateEventReducer.reduce(state, event, at)

    private fun withAvailable(id: CandidateNetworkId, snapshot: RawCapabilitiesSnapshot, at: Instant) =
        reduce(CandidateTrackerState.EMPTY, RawCandidateEvent.Available(id, snapshot), at)

    private fun only(state: CandidateTrackerState): CandidateNetwork {
        val list = state.candidates()
        assertEquals("expected exactly one candidate", 1, list.size)
        return list.single()
    }

    // ---- Mapping ------------------------------------------------------

    @Test
    fun available_withSnapshot_exposesCandidateWithMappedFacts() {
        val candidate = only(withAvailable(a, wifi, t1))

        assertEquals(a, candidate.id)
        assertEquals(TransportType.WIFI, candidate.transport)
        assertTrue(candidate.validated)
        assertFalse(candidate.metered)
        assertFalse(candidate.captivePortalReported)
        assertFalse(candidate.blockedByDevicePolicy)
        assertFalse(candidate.vpnPresent)
        assertFalse(candidate.isDefault)
        assertEquals(t1, candidate.observedAt)
    }

    @Test
    fun meteredAndUnvalidatedFacts_areMapped() {
        val candidate = only(withAvailable(a, snap(TransportType.CELLULAR, validated = false, notMetered = false), t1))

        assertEquals(TransportType.CELLULAR, candidate.transport)
        assertFalse(candidate.validated)
        assertTrue(candidate.metered)
    }

    @Test
    fun captivePortalCapability_mapsToCaptivePortalReported() {
        val candidate = only(withAvailable(a, snap(TransportType.WIFI, captive = true), t1))

        assertTrue(candidate.captivePortalReported)
    }

    @Test
    fun vpnTransport_mapsToVpnTransportAndVpnPresent() {
        val candidate = only(withAvailable(a, snap(TransportType.VPN, TransportType.WIFI), t1))

        assertEquals(TransportType.VPN, candidate.transport)
        assertTrue(candidate.vpnPresent)
    }

    @Test
    fun available_withoutSnapshot_isTrackedButNotExposed_untilCapabilitiesArrive() {
        var state = reduce(CandidateTrackerState.EMPTY, RawCandidateEvent.Available(a, null), t1)
        assertTrue("no facts yet, nothing may be invented", state.candidates().isEmpty())

        state = reduce(state, RawCandidateEvent.CapabilitiesChanged(a, wifi), t2)

        val candidate = only(state)
        assertEquals(a, candidate.id)
        assertEquals(t2, candidate.observedAt)
    }

    // ---- Per network updates ------------------------------------------

    @Test
    fun capabilitiesChanged_updatesFacts_andPreservesBlocked() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.BlockedStatusChanged(a, true), t2)

        state = reduce(state, RawCandidateEvent.CapabilitiesChanged(a, snap(TransportType.WIFI, validated = false)), t3)

        val candidate = only(state)
        assertFalse(candidate.validated)
        assertTrue("blocked must survive a capabilities only update", candidate.blockedByDevicePolicy)
        assertEquals(t3, candidate.observedAt)
    }

    @Test
    fun blockedStatusChanged_setsBlocked_andPreservesCapabilities() {
        var state = withAvailable(a, wifi, t1)

        state = reduce(state, RawCandidateEvent.BlockedStatusChanged(a, true), t2)

        val candidate = only(state)
        assertTrue(candidate.blockedByDevicePolicy)
        assertTrue(candidate.validated)
        assertEquals(TransportType.WIFI, candidate.transport)
    }

    @Test
    fun availableForTrackedNetwork_resetsBlockedToFalse() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.BlockedStatusChanged(a, true), t2)

        state = reduce(state, RawCandidateEvent.Available(a, wifi), t3)

        assertFalse(only(state).blockedByDevicePolicy)
    }

    // ---- Lost and stale events ----------------------------------------

    @Test
    fun lost_removesOnlyThatNetwork() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.Available(b, cellular), t2)

        state = reduce(state, RawCandidateEvent.Lost(a), t3)

        assertEquals(listOf(b), state.candidates().map { it.id })
    }

    @Test
    fun lostThenLateCapabilities_doesNotResurrect() {
        val lost = reduce(withAvailable(a, wifi, t1), RawCandidateEvent.Lost(a), t2)
        assertTrue(lost.candidates().isEmpty())

        val after = reduce(lost, RawCandidateEvent.CapabilitiesChanged(a, wifi), t3)

        assertTrue(after.candidates().isEmpty())
        assertEquals(lost, after)
    }

    @Test
    fun lostThenLateBlocked_doesNotResurrect() {
        val lost = reduce(withAvailable(a, wifi, t1), RawCandidateEvent.Lost(a), t2)

        val after = reduce(lost, RawCandidateEvent.BlockedStatusChanged(a, true), t3)

        assertTrue(after.candidates().isEmpty())
        assertEquals(lost, after)
    }

    @Test
    fun staleBlockedThenStaleCapabilitiesAfterLost_leaveStateUnchanged_andRecoveryStillWorks() {
        val lost = reduce(withAvailable(a, wifi, t1), RawCandidateEvent.Lost(a), t2)

        val afterBlocked = reduce(lost, RawCandidateEvent.BlockedStatusChanged(a, true), t3)
        val afterCapabilities = reduce(afterBlocked, RawCandidateEvent.CapabilitiesChanged(a, wifi), t4)
        assertEquals(lost, afterCapabilities)

        val recovered = reduce(afterCapabilities, RawCandidateEvent.Available(a, wifi), t4.plusSeconds(1))
        val candidate = only(recovered)
        assertFalse("stale blocked=true must not survive into recovery", candidate.blockedByDevicePolicy)
        assertEquals(t4.plusSeconds(1), candidate.observedAt)
    }

    @Test
    fun capabilitiesAndBlockedBeforeAnyAvailable_areDropped() {
        val initial = CandidateTrackerState.EMPTY

        val afterCapabilities = reduce(initial, RawCandidateEvent.CapabilitiesChanged(a, wifi), t1)
        val afterBlocked = reduce(afterCapabilities, RawCandidateEvent.BlockedStatusChanged(a, true), t2)

        assertEquals(initial, afterBlocked)
    }

    @Test
    fun eventsForUntrackedNetwork_whileOthersTracked_changeNothing() {
        val tracked = withAvailable(a, wifi, t1)

        val afterCapabilities = reduce(tracked, RawCandidateEvent.CapabilitiesChanged(b, cellular), t2)
        val afterBlocked = reduce(afterCapabilities, RawCandidateEvent.BlockedStatusChanged(b, true), t3)
        val afterLost = reduce(afterBlocked, RawCandidateEvent.Lost(b), t4)

        assertEquals(tracked, afterLost)
        assertEquals(t1, only(afterLost).observedAt)
    }

    @Test
    fun repeatedLost_isNoOp() {
        val lost = reduce(withAvailable(a, wifi, t1), RawCandidateEvent.Lost(a), t2)

        val after = reduce(lost, RawCandidateEvent.Lost(a), t3)

        assertEquals(lost, after)
    }

    @Test
    fun availableAfterLost_resumesNormalUpdates() {
        var state = reduce(withAvailable(a, wifi, t1), RawCandidateEvent.Lost(a), t2)

        state = reduce(state, RawCandidateEvent.Available(a, wifi), t3)
        state = reduce(state, RawCandidateEvent.BlockedStatusChanged(a, true), t4)

        assertTrue(only(state).blockedByDevicePolicy)
    }

    // ---- Several candidates -------------------------------------------

    @Test
    fun twoCandidatesOfTheSameTransport_stayDistinct_andIndependent() {
        var state = withAvailable(a, cellular, t1)
        state = reduce(state, RawCandidateEvent.Available(b, cellular), t2)
        state = reduce(state, RawCandidateEvent.BlockedStatusChanged(b, true), t3)
        state = reduce(state, RawCandidateEvent.CapabilitiesChanged(a, snap(TransportType.CELLULAR, validated = false, notMetered = false)), t4)

        val list = state.candidates()

        assertEquals(listOf(a, b), list.map { it.id })
        assertTrue(list.all { it.transport == TransportType.CELLULAR })
        assertFalse(list[0].validated)
        assertFalse(list[0].blockedByDevicePolicy)
        assertTrue(list[1].validated)
        assertTrue(list[1].blockedByDevicePolicy)
        assertEquals(t4, list[0].observedAt)
        assertEquals(t3, list[1].observedAt)
    }

    @Test
    fun twoWifiCandidates_stayDistinct_whenOneIsLost() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.Available(b, wifi), t2)

        state = reduce(state, RawCandidateEvent.Lost(a), t3)

        assertEquals(listOf(b), state.candidates().map { it.id })
    }

    @Test
    fun candidates_areOrderedById_regardlessOfArrivalOrder() {
        var state = withAvailable(c, wifi, t1)
        state = reduce(state, RawCandidateEvent.Available(a, cellular), t2)
        state = reduce(state, RawCandidateEvent.Available(b, wifi), t3)

        assertEquals(listOf(a, b, c), state.candidates().map { it.id })
    }

    // ---- Default flag -------------------------------------------------

    @Test
    fun defaultChanged_movesTheDefaultFlag() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.Available(b, cellular), t2)

        state = reduce(state, RawCandidateEvent.DefaultChanged(a), t3)
        assertEquals(listOf(true, false), state.candidates().map { it.isDefault })

        state = reduce(state, RawCandidateEvent.DefaultChanged(b), t4)
        assertEquals(listOf(false, true), state.candidates().map { it.isDefault })
    }

    @Test
    fun defaultChanged_toNull_clearsTheFlag() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.DefaultChanged(a), t2)

        state = reduce(state, RawCandidateEvent.DefaultChanged(null), t3)

        assertFalse(only(state).isDefault)
    }

    @Test
    fun defaultChanged_beforeAvailable_appliesOnceTheNetworkIsAvailable() {
        var state = reduce(CandidateTrackerState.EMPTY, RawCandidateEvent.DefaultChanged(a), t1)
        assertTrue(state.candidates().isEmpty())

        state = reduce(state, RawCandidateEvent.Available(a, wifi), t2)

        assertTrue(only(state).isDefault)
    }

    @Test
    fun defaultChanged_doesNotChangeObservedAt() {
        var state = withAvailable(a, wifi, t1)

        state = reduce(state, RawCandidateEvent.DefaultChanged(a), t3)

        assertEquals(t1, only(state).observedAt)
    }

    @Test
    fun lostOfTheDefault_clearsDefault_andLostOfAnotherKeepsIt() {
        var state = withAvailable(a, wifi, t1)
        state = reduce(state, RawCandidateEvent.Available(b, cellular), t2)
        state = reduce(state, RawCandidateEvent.DefaultChanged(a), t3)

        val afterOtherLost = reduce(state, RawCandidateEvent.Lost(b), t4)
        assertEquals(a, afterOtherLost.defaultId)
        assertTrue(only(afterOtherLost).isDefault)

        val afterDefaultLost = reduce(state, RawCandidateEvent.Lost(a), t4)
        assertNull(afterDefaultLost.defaultId)
        assertEquals(listOf(false), afterDefaultLost.candidates().map { it.isDefault })
    }

    @Test
    fun lostForDefaultNotYetTracked_clearsDefault() {
        var state = reduce(CandidateTrackerState.EMPTY, RawCandidateEvent.DefaultChanged(a), t1)

        state = reduce(state, RawCandidateEvent.Lost(a), t2)

        assertNull(state.defaultId)
    }

    // ---- Flow ---------------------------------------------------------

    @Test
    fun flow_burstThenLostThenStaleEvents_neverShowsTheLostNetworkAgain() = runTest {
        var tick = 0L
        val now: () -> Instant = { t0.plusSeconds(tick++) }
        val events = flowOf<RawCandidateEvent>(
            RawCandidateEvent.Available(a, wifi),
            RawCandidateEvent.CapabilitiesChanged(a, wifi),
            RawCandidateEvent.BlockedStatusChanged(a, false),
            RawCandidateEvent.Lost(a),
            RawCandidateEvent.CapabilitiesChanged(a, wifi),
            RawCandidateEvent.BlockedStatusChanged(a, true)
        )

        val lists = foldCandidateListFlow(events, now).toList()

        assertTrue(lists.last().isEmpty())
        assertTrue("nothing may follow the empty list", lists.dropWhile { it.isNotEmpty() }.all { it.isEmpty() })
        assertEquals("stale events must not add emissions", 1, lists.count { it.isEmpty() })
    }

    @Test
    fun flow_twoNetworksAndDefault_endsWithBothDistinctAndDefaultMarked() = runTest {
        var tick = 0L
        val now: () -> Instant = { t0.plusSeconds(tick++) }
        val events = flowOf<RawCandidateEvent>(
            RawCandidateEvent.Available(a, wifi),
            RawCandidateEvent.Available(b, cellular),
            RawCandidateEvent.DefaultChanged(a)
        )

        val last = foldCandidateListFlow(events, now).toList().last()

        assertEquals(listOf(a, b), last.map { it.id })
        assertEquals(listOf(true, false), last.map { it.isDefault })
    }

    @Test
    fun flow_identicalConsecutiveLists_areCollapsed() = runTest {
        val now: () -> Instant = { t0 }
        val events = flowOf<RawCandidateEvent>(
            RawCandidateEvent.Available(a, wifi),
            RawCandidateEvent.CapabilitiesChanged(a, wifi),
            RawCandidateEvent.DefaultChanged(null)
        )

        val lists = foldCandidateListFlow(events, now).toList()

        assertEquals(1, lists.size)
    }
}
