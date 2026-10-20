package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What the pipeline does when a platform event is MISSING (never reaches the
 * reducer), as opposed to arriving late or out of order, which
 * [NetworkEventReducerStaleEventTest] and [NetworkStatePipelineTest] already
 * cover. A missing event is the observable consequence of a failed
 * `trySend` in [AndroidNetworkMonitor] (full callback channel). The failed
 * send itself cannot be induced here, because the `NetworkCallback` is not
 * constructible on the plain JVM; this test induces the condition one level
 * down, by simply not emitting the event into [foldNetworkStateFlow], which is
 * exactly what the reducer would see.
 *
 * WHAT THIS TEST MODELS: an OMITTED Available event, nothing more. It does
 * NOT prove that the real Android callback channel overflows, that `trySend`
 * ever fails in production, or how likely that is. It only shows what the
 * pipeline does downstream IF such an event were never delivered.
 *
 * This is a characterization test of the documented reducer contract ("only
 * Available may establish a tracked network"), not a claim that the behavior
 * is ideal. If a future change makes the pipeline self-heal after a missing
 * Available, this test is the one that must be deliberately revised.
 *
 * Conventions follow [NetworkStatePipelineTest]: virtual time, no sleeps, the
 * upstream flow stays open and the collector is cancelled explicitly.
 */
class NetworkStateMissingEventRecoveryTest {

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

    @Test
    fun availableForNewNetworkMissing_itsLaterEventsAreIgnored_untilTheNextAvailableArrives() = runTest {
        val collected = mutableListOf<NetworkState>()
        val events = flow {
            // t=0: Wi-Fi "A" is established normally.
            emit(RawNetworkEvent.Available("A", wifiSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("A", false))
            delay(500)
            // t=500: the platform switched to cellular "B", but Available("B")
            // never reached the reducer. Only the events that followed it did.
            emit(RawNetworkEvent.CapabilitiesChanged("B", cellularSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("B", true))
            delay(500)
            // t=1000: a later, complete burst for "B" (e.g. the next platform
            // change) re-establishes it.
            emit(RawNetworkEvent.Available("B", cellularSnapshot))
            emit(RawNetworkEvent.CapabilitiesChanged("B", cellularSnapshot))
            emit(RawNetworkEvent.BlockedStatusChanged("B", true))
            delay(UPSTREAM_IDLE_MS)
        }
        val job = launch {
            foldNetworkStateFlow(events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }

        // t=900: past A's debounce window (300) and past the two orphaned "B"
        // events (500), before the recovery burst (1000).
        advanceTimeBy(900)
        runCurrent()

        // The orphaned events were dropped: the monitor still reports Wi-Fi "A",
        // and B's blocked-by-policy report did NOT leak into the state.
        assertEquals(1, collected.size)
        assertTrue(collected.last().available)
        assertEquals(TransportType.WIFI, collected.last().transport)
        assertFalse(collected.last().blockedByDevicePolicy)

        // t=1100: the next Available("B") burst recovers the pipeline.
        advanceTimeBy(200)
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected.last().available)
        assertEquals(TransportType.CELLULAR, collected.last().transport)
        assertTrue(collected.last().blockedByDevicePolicy)
    }

    private companion object {
        const val UPSTREAM_IDLE_MS = 1_000_000L
    }
}
