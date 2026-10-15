package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression coverage for audit finding CF-1
 * (PHASE_4_NETWORK_STATE_INTEGRATION_AUDIT.md, phase-4-network-state-audit
 * @ b4f0e6c) and its fix. Unlike [NetworkEventReducerTest], which calls
 * [NetworkEventReducer.reduce] directly and therefore cannot see anything
 * that happens in the surrounding Flow pipeline, every test here drives
 * [foldNetworkStateFlow] -- the exact function [AndroidNetworkMonitor.observe]
 * calls -- including its real `debounce` behavior, under
 * [kotlinx.coroutines.test]'s virtual time. `N = String` throughout, for
 * the same reason [NetworkEventReducerTest] uses it: see
 * [RawNetworkEvent]'s own KDoc.
 *
 * Each test builds its event flow with explicit [delay] calls that model
 * real platform timing (a same-instant callback burst is modeled as zero
 * delay between `emit`s; a settled, unrelated later event is modeled with
 * a real gap), then inspects what has actually been emitted at specific
 * virtual-time checkpoints via [advanceTimeBy]/[runCurrent] -- never
 * [kotlinx.coroutines.test.TestScope.advanceUntilIdle], since letting the
 * upstream flow run to completion would trigger `debounce`'s own
 * documented flush-on-close behavior and could hide a real timing defect.
 * Every event flow therefore stays open (a long trailing `delay`) for the
 * duration of its test and the collecting job is cancelled explicitly at
 * the end.
 */
class NetworkStatePipelineTest {

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED", "NOT_METERED")
    )

    private val cellularSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = false,
        transports = setOf(TransportType.CELLULAR),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED")
    )

    // ---- A. First connection ----------------------------------------

    @Test
    fun firstConnection_burstOfAvailableThenCapabilitiesThenBlocked_producesRealConnectedState() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            // Zero delay between these three: models the platform's own
            // guaranteed onAvailable -> onCapabilitiesChanged ->
            // onBlockedStatusChanged burst (VERIFIED FACT, see
            // AndroidNetworkMonitor's own KDoc), the exact condition
            // CF-1 was found under.
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        job.cancel()

        // Under CF-1 this would have been the canonical offline state
        // (network = null, available = false), because Available and
        // CapabilitiesChanged were silently dropped by a pre-fold
        // debounce and only the lone BlockedStatusChanged reached a
        // still-empty reducer state.
        assertEquals(1, collected.size)
        val state = collected.single()
        assertTrue("expected the real connected state, not canonical offline", state.available)
        assertTrue(state.validated)
        assertEquals(TransportType.WIFI, state.transport)
        assertFalse(state.blockedByDevicePolicy)
    }

    // ---- B. Wi-Fi -> cellular -----------------------------------------

    @Test
    fun wifiToCellularTransition_newNetworksBurstMakesItActive_oldNetworkNotStuck() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("wifi-net", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("wifi-net", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("wifi-net", false))
            delay(500)
            // The switch: per the platform's own documented contract for
            // registerDefaultNetworkCallback, this is a fresh burst for
            // the new network, not an onLost for the old one first.
            emit(RawNetworkEvent.Available("cellular-net", cellularSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("cellular-net", cellularSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("cellular-net", false))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)
        assertEquals(TransportType.WIFI, collected[0].transport)

        // Past the switch burst (t=500) and comfortably past its own
        // debounce window (500 + 300 = 800).
        advanceTimeBy(600) // now at t = 901
        runCurrent()
        job.cancel()

        // Under CF-1 this would have stayed stuck on the Wi-Fi state
        // forever: the new network's Available/CapabilitiesChanged would
        // have been dropped by the pre-fold debounce, and the lone
        // surviving BlockedStatusChanged("cellular-net", ...) would have
        // been rejected by NetworkEventReducer's own foreign-network
        // guard because state.network was still "wifi-net".
        assertEquals(2, collected.size)
        val last = collected.last()
        assertTrue(last.available)
        assertEquals(
            "the monitor must not still be reporting the old Wi-Fi network",
            TransportType.CELLULAR,
            last.transport
        )
        assertTrue("cellular snapshot in this test is metered", last.metered)
    }

    // ---- C. Foreign-network events -------------------------------------

    @Test
    fun foreignNetworkEvents_duringAndAfterTheEstablishingBurst_areIgnored() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            // A foreign event interleaved inside the same zero-delay
            // burst that establishes "A" -- proves debounce is not what
            // is protecting against it; NetworkEventReducer's own guard
            // is, and it still receives this event to reject.
            emit(RawNetworkEvent.BlockedStatusChanged("foreign-mid-burst", true))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            // Foreign events well after "A" has settled.
            emit(RawNetworkEvent.CapabilitiesChanged("foreign-later", cellularSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("foreign-later", true))
            emit(RawNetworkEvent.Lost("foreign-later"))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(1200)
        runCurrent()
        job.cancel()

        // Exactly one real state throughout: "A", never corrupted by any
        // foreign network's events, in the burst or afterward.
        assertEquals(1, collected.size)
        val state = collected.single()
        assertTrue(state.available)
        assertEquals(TransportType.WIFI, state.transport)
        assertFalse(state.blockedByDevicePolicy)
    }

    // ---- D. Loss ---------------------------------------------------------

    @Test
    fun lossOfActiveNetwork_resetsStateWithoutWaitingTheFullDebounceWindow() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            emit(RawNetworkEvent.Lost("A"))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)
        assertTrue(collected[0].available)

        // Lost arrives at virtual t = 500. Advance only 201ms further
        // (to t = 502) -- nowhere near another +300ms debounce wait --
        // to prove the offline transition was not delayed.
        advanceTimeBy(201)
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        val last = collected.last()
        assertFalse(last.available)
        assertFalse(last.blockedByDevicePolicy)
    }

    // ---- E. Rapid callback burst loses no lifecycle information -------

    @Test
    fun rapidBurstInsideThePreviousDebounceWindow_losesNoLifecycleInformation() = runTest {
        val initiallyUnvalidated = wifiSnapshot.copy(isValidated = false)
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            // Three distinct pieces of information, one per event, all
            // arriving with zero delay between them -- deliberately
            // inside the old 300ms debounce window this defect used to
            // collapse to a single surviving event.
            emit(RawNetworkEvent.Available("A", initiallyUnvalidated))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot)) // now validated
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        val state = collected.single()
        assertTrue("must be available, not the CF-1 canonical-offline failure", state.available)
        assertTrue("the CapabilitiesChanged validation update must not be lost", state.validated)
        assertFalse("the BlockedStatusChanged update must not be lost", state.blockedByDevicePolicy)
    }

    // ---- F. What debounce debounces, and why it cannot corrupt reducer input ----

    @Test
    fun debounce_delaysOnlyStillUsableChatter_neverOfflineOrNewlyBlockedTransitions() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            // Becoming blocked must be exempt from debounce, exactly
            // like a real network loss (see test D).
            emit(RawNetworkEvent.BlockedStatusChanged("A", true))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)
        assertFalse(collected[0].blockedByDevicePolicy)

        advanceTimeBy(201) // t = 502: just past the block at t = 500
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected[1].blockedByDevicePolicy)
    }

    @Test
    fun debounce_coalescesRapidStillUsableChanges_ratherThanEmittingEachOne() = runTest {
        val collected = mutableListOf<NetworkState>()
        val meteredWifi = wifiSnapshot.copy(isNotMetered = false)
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            // Still available, still unblocked -- ordinary chatter
            // (e.g. metered status settling), the case debounceMillis
            // exists for.
            emit(RawNetworkEvent.CapabilitiesChanged("A", meteredWifi))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)

        // t = 701: 200ms after the change at t = 500, still short of its
        // own +300ms debounce window -- must NOT have emitted yet, proving
        // this path really is delayed, unlike the offline/blocked paths
        // above.
        advanceTimeBy(400)
        runCurrent()
        assertEquals("a still-usable change must wait out the debounce window", 1, collected.size)

        advanceTimeBy(250) // t = 951: > 300ms after t = 500
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected[1].metered)
    }

    @Test
    fun debounceOperatesOnlyOnFoldedOutput_reducerAlwaysSeesEveryRawEvent() = runTest {
        // Structural proof that debounce cannot corrupt reducer input:
        // foldNetworkStateFlow applies debounce only after scan/map
        // (see that function's own KDoc). This test drives events fast
        // enough that, under the pre-fix ordering, several of them would
        // have been dropped before ever reaching NetworkEventReducer;
        // here every one of them still visibly influences the final
        // folded result, proving none was silently discarded upstream
        // of the reducer.
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot.copy(isValidated = false)))
            // Mid-burst, momentarily reflects cellular's data on the same
            // network identity -- unrealistic on its own, but exercises
            // that every field of every intermediate event really does
            // flow through the fold, not just the very first and last.
            emit(RawNetworkEvent.CapabilitiesChanged("A", cellularSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        val state = collected.single()
        // Reflects the *last* value of each field across the whole
        // burst (validated = true, blocked = false) -- only possible if
        // every intermediate event was actually folded by the reducer in
        // order, not filtered out before it arrived.
        assertTrue(state.available)
        assertTrue(state.validated)
        assertFalse(state.blockedByDevicePolicy)
    }

    // ---- G. Clock-driven coverage: folding order and changedAt -------------
    //
    // The tests above pass a constant clock, so they can see WHICH events
    // reached the reducer (through the final state) but not HOW MANY times, or
    // WHEN, the fold ran. foldNetworkStateFlow calls `now` once for the scan
    // seed and exactly once per raw event, at fold time -- before debounce. A
    // counting clock and a virtual-time clock make both facts observable.

    @Test
    fun countingClock_everyRawEventIsFoldedBeforeDebounce_andChangedAtIsTheLastEventsTick() = runTest {
        var ticks = 0
        val countingClock = { Instant.EPOCH.plusSeconds((ticks++).toLong()) }
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L, now = countingClock).toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        job.cancel()

        // 1 seed read + 3 raw events = 4 reads. Had debounce run before the fold,
        // only the last raw event would have reached the reducer: 2 reads.
        assertEquals("clock must be read once for the seed and once per raw event", 4, ticks)
        assertEquals(1, collected.size)
        val state = collected.single()
        assertTrue("every raw event was folded, so the real connected state is visible", state.available)
        assertEquals(TransportType.WIFI, state.transport)
        // Seed = tick 0; Available = 1; CapabilitiesChanged = 2; BlockedStatusChanged = 3.
        assertEquals(Instant.EPOCH.plusSeconds(3), state.lastChangedAt)
    }

    @Test
    fun changedAt_isWhenTheEventWasFolded_notWhenDebounceReleasedTheState() = runTest {
        val virtualClock = { Instant.EPOCH.plusMillis(currentTime) }
        val collected = mutableListOf<NetworkState>()
        val meteredWifi = wifiSnapshot.copy(isNotMetered = false)
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            emit(RawNetworkEvent.CapabilitiesChanged("A", meteredWifi))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L, now = virtualClock).toList(collected)
        }

        advanceTimeBy(301) // first burst folded at t=0, released by debounce at t=300
        runCurrent()
        assertEquals(1, collected.size)
        assertEquals(Instant.EPOCH, collected[0].lastChangedAt)

        advanceTimeBy(650) // t=951: change folded at t=500, released by debounce at t=800
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected[1].metered)
        assertEquals(
            "changedAt must be the t=500 fold time, not the t=800 emission time",
            Instant.EPOCH.plusMillis(500),
            collected[1].lastChangedAt
        )
    }

    @Test
    fun foreignNetworkEvents_doNotAdvanceChangedAtAndEmitNothing() = runTest {
        val virtualClock = { Instant.EPOCH.plusMillis(currentTime) }
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            emit(RawNetworkEvent.CapabilitiesChanged("foreign", cellularSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("foreign", true))
            emit(RawNetworkEvent.Lost("foreign"))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L, now = virtualClock).toList(collected)
        }

        advanceTimeBy(1200)
        runCurrent()
        job.cancel()

        // Dropped events leave the reducer state untouched, including changedAt,
        // so distinctUntilChanged collapses them: still exactly one emission.
        assertEquals(1, collected.size)
        assertEquals(Instant.EPOCH, collected.single().lastChangedAt)
        assertEquals(TransportType.WIFI, collected.single().transport)
    }

    // ---- H. Unavailable --------------------------------------------------
    //
    // Contract (NetworkEventReducer): Unavailable "always resets, regardless of
    // whatever network was previously tracked". Contract (foldNetworkStateFlow):
    // a state with available = false is never delayed by debounce.

    @Test
    fun unavailable_afterAnEstablishedNetwork_emitsCanonicalOfflineWithoutWaitingTheDebounceWindow() = runTest {
        val virtualClock = { Instant.EPOCH.plusMillis(currentTime) }
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            emit(RawNetworkEvent.Unavailable)
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L, now = virtualClock).toList(collected)
        }

        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, collected.size)
        assertTrue(collected[0].available)

        // Unavailable arrives at t=500. Checking at t=502, far short of another
        // 300 ms window, proves the offline transition was not debounced.
        advanceTimeBy(201)
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        val offline = collected[1]
        assertFalse(offline.available)
        assertEquals(TransportType.NONE, offline.transport)
        assertFalse(offline.validated)
        assertFalse(offline.metered)
        assertTrue(offline.capabilities.isEmpty())
        assertFalse(offline.blockedByDevicePolicy)
        assertEquals(Instant.EPOCH.plusMillis(500), offline.lastChangedAt)
    }

    @Test
    fun unavailable_afterABlockedNetwork_doesNotCarryTheBlockedFlagIntoOffline() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", true))
            delay(500)
            emit(RawNetworkEvent.Unavailable)
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        advanceTimeBy(1)
        runCurrent()
        // A blocked state is never debounced, so it is visible almost immediately.
        assertEquals(1, collected.size)
        assertTrue(collected[0].blockedByDevicePolicy)

        advanceTimeBy(502)
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertFalse(collected[1].available)
        assertFalse("blocked=true must not survive Unavailable", collected[1].blockedByDevicePolicy)
    }

    @Test
    fun unavailable_asTheVeryFirstEvent_emitsCanonicalOfflineImmediately() = runTest {
        val virtualClock = { Instant.EPOCH.plusMillis(currentTime) }
        val collected = mutableListOf<NetworkState>()
        val events = flow<RawNetworkEvent<String>> {
            emit(RawNetworkEvent.Unavailable)
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L, now = virtualClock).toList(collected)
        }

        advanceTimeBy(1)
        runCurrent()
        job.cancel()

        // A real platform event always yields an observable state, even when it
        // says "nothing is available"; only the pre-event seed is dropped.
        assertEquals(1, collected.size)
        val offline = collected.single()
        assertFalse(offline.available)
        assertEquals(TransportType.NONE, offline.transport)
        assertFalse(offline.blockedByDevicePolicy)
    }

    private companion object {
        /** Keeps each test's event flow open for its duration; never actually awaited. */
        const val UPSTREAM_IDLE_MS = 1_000_000L
    }
}
