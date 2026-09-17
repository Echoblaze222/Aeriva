package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.TestAerivaDispatchers
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.measurement.Freshness
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementFailure
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md's central claim: the
 * seams this phase adds (NetworkClient/FakeNetworkClient,
 * AerivaDispatchers/TestAerivaDispatchers, the clock parameter) are
 * *sufficient* to deterministically test every objective this task's
 * instructions named, entirely on the JVM, with no real time, no real
 * network, and no mocking library. [ReferenceLatencyProbeExecutor]
 * itself is test-only scaffolding, not the production engine -- see its
 * own KDoc.
 *
 * All `TestAerivaDispatchers`/`StandardTestDispatcher` instances share
 * `runTest`'s own `testScheduler`, per the official coroutine-testing
 * guidance ("All TestDispatchers should share the same scheduler") --
 * not an invention of this file.
 */
class ReferenceLatencyProbeExecutorTest {

    private val availableContext = MeasurementNetworkContext(
        networkState = NetworkState(
            transport = TransportType.WIFI,
            available = true,
            validated = true,
            metered = false,
            capabilities = emptySet(),
            estimatedQuality = NetworkQuality.Unavailable,
            diagnosticsStatus = DiagnosticsStatus.NotAvailable,
            lastChangedAt = Instant.EPOCH
        )
    )

    private val unavailableContext = MeasurementNetworkContext(networkState = NetworkState.unknown(Instant.EPOCH))

    // -- Objective 7: unavailable network -----------------------------

    @Test
    fun measure_whenNetworkUnavailable_returnsNull_withoutCallingClient() = runTest {
        val client = FakeNetworkClient()
        val executor = executor(client)

        val result = executor.measure(id = 1, target = "host", context = unavailableContext)

        assertNull(result)
        assertEquals(0, client.callCount)
    }

    // -- Objective 8: timeout -------------------------------------------

    @Test
    fun measure_onTimeout_returnsFailedTimeout_andClientCallIsCancelledCleanly() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val executor = executor(client, timeoutMillis = 1_000L)

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val failed = result as? LatencyMeasurement.Failed
        requireNotNull(failed) { "expected Failed, got $result" }
        assertEquals(MeasurementFailure.Timeout, failed.failure)
        assertEquals(1, client.cancelledCallCount)
        assertEquals(0, client.completedCallCount)
    }

    // -- Objective 9: cancellation (caller-initiated, not timeout) ------

    @Test
    fun measure_onCallerCancellation_propagates_andEmitsNoResult() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        // Timeout far longer than this test will ever run to, so the
        // cancellation under test is unambiguously caller-initiated, not
        // the timeout path already covered above.
        val executor = executor(client, timeoutMillis = Long.MAX_VALUE / 2)

        var sawAResult = false
        val job = launch {
            executor.measure(id = 1, target = "host", context = availableContext)
            sawAResult = true
        }

        advanceUntilIdle()
        job.cancel()
        job.join()

        assertFalse("a cancelled measurement must never emit a result", sawAResult)
        assertEquals(1, client.cancelledCallCount)
    }

    // -- Objective 12/13/14: cleanup / resource leaks, repeated calls --

    @Test
    fun repeatedCalls_everyProbeIsAccountedFor_completedOrCancelled_noneVanish() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8))
            enqueueHang()
        }
        val executor = executor(client, timeoutMillis = 100L)

        executor.measure(id = 1, target = "a", context = availableContext)
        executor.measure(id = 2, target = "b", context = availableContext)

        assertEquals(2, client.callCount)
        assertEquals(1, client.completedCallCount)
        assertEquals(1, client.cancelledCallCount)
    }

    // -- Successful measurement + objective 16: malformed responses ----

    @Test
    fun measure_onValidPayload_returnsSucceeded_withStartTimeAndElapsedDuration() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        val startInstant = Instant.parse("2026-01-01T00:00:00Z")
        val completionInstant = startInstant.plusMillis(300)
        val scriptedNow = ArrayDeque(listOf(startInstant, completionInstant))
        val executor = executor(client, now = { scriptedNow.removeFirst() })

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val succeeded = result as? LatencyMeasurement.Succeeded
        requireNotNull(succeeded) { "expected Succeeded, got $result" }
        assertEquals(startInstant, succeeded.measuredAt)
        assertEquals(300.0, succeeded.valueMillis, 0.0001)
    }

    @Test
    fun measure_onMalformedPayload_returnsFailedInvalidResponse_notASuccessWithGarbageData() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(payload = ByteArray(3)) }
        val executor = executor(client)

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val failed = result as? LatencyMeasurement.Failed
        requireNotNull(failed) { "expected Failed, got $result" }
        assertTrue(failed.failure is MeasurementFailure.InvalidResponse)
    }

    // -- Endpoint / TLS / network-changed failure mapping ---------------

    @Test
    fun measure_onConnectionRefused_returnsFailedEndpointFailure() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("connection reset"))
        }
        val executor = executor(client)

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val failed = result as? LatencyMeasurement.Failed
        requireNotNull(failed)
        assertTrue(failed.failure is MeasurementFailure.EndpointFailure)
    }

    @Test
    fun measure_onTlsHandshakeFailure_returnsFailedTlsFailure_distinctFromEndpointFailure() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueFailure(NetworkClientOutcome.TlsHandshakeFailed("certificate rejected"))
        }
        val executor = executor(client)

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val failed = result as? LatencyMeasurement.Failed
        requireNotNull(failed)
        assertTrue(failed.failure is MeasurementFailure.TlsFailure)
    }

    @Test
    fun measure_onNetworkChangedMidCall_returnsFailedNetworkChangedDuringMeasurement() = runTest {
        val client = FakeNetworkClient().apply { enqueueFailure(NetworkClientOutcome.NetworkChangedMidCall) }
        val executor = executor(client)

        val result = executor.measure(id = 1, target = "host", context = availableContext)

        val failed = result as? LatencyMeasurement.Failed
        requireNotNull(failed)
        assertEquals(MeasurementFailure.NetworkChangedDuringMeasurement, failed.failure)
    }

    // -- Objective 11: duplicate/independent-identity, NOT retry dedup --

    @Test
    fun measure_twoIndependentRequests_remainSeparatelyIdentified_notMergedOrCached() = runTest {
        // Proves independently-requested measurements are never silently
        // merged/cached by target. Does NOT test retry-produced-duplicate
        // de-duplication (test strategy Section 5's "duplicate results"
        // row) -- this reference executor implements no retry logic;
        // see PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md's limitations.
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8))
            enqueueSuccess(ByteArray(8))
        }
        val executor = executor(client)

        val resultA = executor.measure(id = 10, target = "same-host", context = availableContext)
        val resultB = executor.measure(id = 11, target = "same-host", context = availableContext)

        assertEquals(10L, resultA?.id)
        assertEquals(11L, resultB?.id)
        assertEquals(2, client.callCount)
    }

    // -- Objective 10: concurrent measurements --------------------------

    @Test
    fun measure_concurrentCalls_doNotCorruptSharedState() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8), delayMillis = 100)
            enqueueSuccess(ByteArray(8), delayMillis = 50)
        }
        val executor = executor(client)

        val first = async { executor.measure(id = 1, target = "host-a", context = availableContext) }
        val second = async { executor.measure(id = 2, target = "host-b", context = availableContext) }
        val results = awaitAll(first, second)

        assertEquals(2, client.callCount)
        assertEquals(setOf("host-a", "host-b"), client.recordedTargets.toSet())
        assertEquals(setOf(1L, 2L), results.mapNotNull { it?.id }.toSet())
    }

    // -- Objective 11 (staleness): composes with the existing Freshness --
    // seam -- this does not re-test Freshness itself (FreshnessTest
    // already does), only that a LatencyMeasurement's measuredAt is
    // usable as Freshness.producedAt.

    @Test
    fun resultingMeasuredAt_composesWithFreshness_toIdentifyStaleness() {
        val measuredAt = Instant.parse("2026-01-01T00:00:00Z")
        val freshness = Freshness(producedAt = measuredAt, validUntil = measuredAt.plusSeconds(60))

        assertTrue(freshness.isStaleAt(measuredAt.plusSeconds(120)))
    }

    /**
     * The dispatcher is built from *this* [TestScope]'s own
     * [TestScope.testScheduler] -- required so it advances in lockstep
     * with `runTest`'s virtual time, per the official coroutine-testing
     * guidance quoted in this file's class KDoc. A [StandardTestDispatcher]
     * built with no scheduler argument gets its own independent one and
     * would not do this -- that mistake is exactly why this helper takes
     * a [TestScope] receiver instead of being scheduler-agnostic.
     */
    private fun TestScope.executor(
        client: FakeNetworkClient,
        now: () -> Instant = { Instant.EPOCH },
        timeoutMillis: Long = ReferenceLatencyProbeExecutor.DEFAULT_TIMEOUT_MILLIS
    ): ReferenceLatencyProbeExecutor = ReferenceLatencyProbeExecutor(
        dispatchers = TestAerivaDispatchers(StandardTestDispatcher(testScheduler)),
        networkClient = client,
        now = now,
        timeoutMillis = timeoutMillis
    )
}
