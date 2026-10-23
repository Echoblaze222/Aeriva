package com.aeriva.network.monitor

import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * Characterizes what happens when a non-suspending platform callback pushes
 * events into a `callbackFlow` faster than the collector drains them, using the
 * SAME shape as [AndroidNetworkMonitor.platformEvents]: a `callbackFlow` with
 * the default buffer, a callback that calls `trySend` and ignores the result,
 * and `awaitClose`.
 *
 * WHAT THIS IS: a REPLICA of that shape ([CallbackReplica]), not a test of
 * [AndroidNetworkMonitor] itself. The real class needs a
 * `ConnectivityManager.NetworkCallback`, which this module cannot construct on
 * the plain JVM, so no JVM test can drive the production callbacks. See
 * NETWORK_MONITOR_RELIABILITY_SEAM_REPORT.md for the smallest seam that would
 * close that gap.
 *
 * WHAT THIS PROVES: with the default buffer, once the buffer is full
 * `trySend` returns a failed result, the rejected element is the NEWEST one,
 * the accepted elements are the OLDEST ones in their original order, and the
 * pipeline downstream ([foldNetworkStateFlow]) then simply never sees the
 * rejected event.
 *
 * WHAT THIS DOES NOT PROVE: that more than the buffer's capacity of events can
 * ever be pending on a real device, or how likely that is. Whether the real
 * consumer can fall that far behind is UNCONFIRMED. The burst below is
 * synthetic: it runs on the single test thread so the consumer cannot run in
 * between.
 *
 * The exact capacity is deliberately not asserted to be a single number. The
 * documented default is 64 (kotlinx.coroutines Channel.BUFFERED), and a parked
 * receiver may take one element directly, so the test only requires that at
 * least the documented capacity was accepted and that not all sends were.
 */
class CallbackFlowTrySendOverflowCharacterizationTest {

    private class CallbackReplica<E> {
        /** Stands in for the NetworkCallback methods: true when trySend succeeded. */
        var deliver: (E) -> Boolean = { false }

        val events: Flow<E> = callbackFlow {
            deliver = { event -> trySend(event).isSuccess }
            awaitClose { deliver = { false } }
        }
    }

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED", "NOT_METERED")
    )

    @Test
    fun aBurstLargerThanTheDefaultBuffer_whileTheConsumerCannotRun_dropsTheNewestEvents_andKeepsTheOldestInOrder() = runTest {
        val replica = CallbackReplica<Int>()
        val received = mutableListOf<Int>()
        val job = launch { replica.events.toList(received) }
        runCurrent()

        val outcomes = (0 until BURST).map { replica.deliver(it) }
        runCurrent()
        job.cancel()

        val accepted = outcomes.count { it }
        assertTrue("the documented default capacity must have been accepted", accepted >= DOCUMENTED_DEFAULT_CAPACITY)
        assertTrue("a burst larger than the buffer must see at least one failed send", accepted < BURST)
        assertEquals(
            "accepted sends form a prefix, failed sends the suffix: the newest events are the ones dropped",
            List(accepted) { true } + List(BURST - accepted) { false },
            outcomes
        )
        assertEquals(
            "the consumer receives exactly the accepted events, oldest first, in order",
            (0 until accepted).toList(),
            received
        )
    }

    @Test
    fun aLostEventRejectedByAFullBuffer_leavesThePipelineReportingTheLostNetworkAsConnected() = runTest {
        val replica = CallbackReplica<RawNetworkEvent<String>>()
        val collected = mutableListOf<NetworkState>()
        val job = launch {
            foldNetworkStateFlow(replica.events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }
        runCurrent()

        val availableAccepted = replica.deliver(RawNetworkEvent.Available("A", wifiSnapshot))
        repeat(CAPABILITY_BURST) {
            replica.deliver(RawNetworkEvent.CapabilitiesChanged("A", wifiSnapshot))
        }
        val lostAccepted = replica.deliver(RawNetworkEvent.Lost("A"))

        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()

        assertTrue(availableAccepted)
        assertFalse("the Lost event was the newest, so the full buffer rejected it", lostAccepted)
        assertEquals(1, collected.size)
        assertTrue(
            "no later event arrives, so the monitor keeps reporting network A as connected",
            collected.last().available
        )
    }

    @Test
    fun theSameSequenceWithoutTheOverflow_reportsTheLoss() = runTest {
        val replica = CallbackReplica<RawNetworkEvent<String>>()
        val collected = mutableListOf<NetworkState>()
        val job = launch {
            foldNetworkStateFlow(replica.events, debounceMillis = 300L) { Instant.EPOCH }.toList(collected)
        }
        runCurrent()

        val availableAccepted = replica.deliver(RawNetworkEvent.Available("A", wifiSnapshot))
        val lostAccepted = replica.deliver(RawNetworkEvent.Lost("A"))

        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()

        assertTrue(availableAccepted)
        assertTrue(lostAccepted)
        assertFalse("control: with every event delivered the loss is reported", collected.last().available)
    }

    private companion object {
        const val BURST = 200
        const val CAPABILITY_BURST = 200
        const val DOCUMENTED_DEFAULT_CAPACITY = 64
    }
}
