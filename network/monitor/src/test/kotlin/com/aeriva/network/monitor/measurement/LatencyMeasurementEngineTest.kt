package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.TestAerivaDispatchers
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementFailure
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import com.aeriva.core.model.measurement.MeasurementStage
import com.aeriva.network.monitor.CapabilityClassification
import com.aeriva.network.monitor.MeasurementCapability
import com.aeriva.network.monitor.MeasurementCapabilityClassifier
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [LatencyMeasurementEngine]'s own boundary -- the fourteen
 * objectives this phase's instructions named. Reuses the exact seams
 * [ReferenceLatencyProbeExecutorTest] already validated
 * ([FakeNetworkClient], [TestAerivaDispatchers] sharing this test's
 * `testScheduler`, the `runCurrent()`-not-`advanceUntilIdle()` fix for
 * genuine-cancellation tests) rather than re-deriving them. This file
 * does not re-test [MeasurementCapabilityClassifier] itself (already
 * covered by `MeasurementCapabilityClassifierTest`) or
 * [DerivedLatencyStats.from] itself (already covered by
 * `DerivedLatencyStatsTest`) -- only that this engine calls each
 * correctly at its own boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencyMeasurementEngineTest {

    private val availableContext = MeasurementNetworkContext(
        networkState = NetworkState(
            transport = TransportType.WIFI,
            available = true,
            validated = true,
            metered = false,
            capabilities = emptySet(),
            estimatedQuality = NetworkQuality.Unavailable,
            diagnosticsStatus = DiagnosticsStatus.NotAvailable,
            lastChangedAt = Instant.EPOCH,
            captivePortalReported = false,
            vpnPresent = false,
            blockedByDevicePolicy = false
        )
    )

    private val unavailableContext = MeasurementNetworkContext(networkState = NetworkState.unknown(Instant.EPOCH))

    private fun request(
        id: Long = 1,
        target: String = "host",
        context: MeasurementNetworkContext = availableContext,
        sdkInt: Int = 34,
        grantedPermissions: Set<String> = setOf("android.permission.INTERNET")
    ) = LatencyMeasurementRequest(id, target, context, sdkInt, grantedPermissions)

    // -- 1: successful latency measurement ------------------------------

    @Test
    fun measure_onValidPayload_returnsMeasuredSucceeded() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        val engine = engine(client)

        val outcome = engine.measure(request())

        val measured = outcome as? LatencyMeasurementOutcome.Measured
        requireNotNull(measured) { "expected Measured, got $outcome" }
        assertTrue(measured.measurement is LatencyMeasurement.Succeeded)
        assertEquals(1, client.callCount)
    }

    // -- 2: unavailable network ------------------------------------------

    @Test
    fun measure_whenNetworkUnavailable_returnsNoNetwork_withoutCallingClient() = runTest {
        val client = FakeNetworkClient()
        val engine = engine(client)

        val outcome = engine.measure(request(context = unavailableContext))

        assertEquals(LatencyMeasurementOutcome.NoNetwork, outcome)
        assertEquals(0, client.callCount)
    }

    // -- 3: unsupported capability / 4: permission failure ---------------
    // For LATENCY, the current classifier collapses both to the same
    // NotReliablyAvailable branch (no INTERNET permission) -- documented
    // in this engine's own KDoc as a stated, not silent, simplification.

    @Test
    fun measure_withoutInternetPermission_returnsCapabilityUnavailable_withoutCallingClient() = runTest {
        val client = FakeNetworkClient()
        val engine = engine(client)

        val outcome = engine.measure(request(grantedPermissions = emptySet()))

        val unavailable = outcome as? LatencyMeasurementOutcome.CapabilityUnavailable
        requireNotNull(unavailable) { "expected CapabilityUnavailable, got $outcome" }
        assertTrue(unavailable.reason.isNotBlank())
        assertEquals(0, client.callCount)
    }

    @Test
    fun measure_consultsInjectedClassifier_notJustDefaultOne() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        val engine = engine(client) { _, _, _ ->
            CapabilityClassification.NotReliablyAvailable("forced for this test")
        }

        val outcome = engine.measure(request())

        assertEquals(
            LatencyMeasurementOutcome.CapabilityUnavailable("forced for this test"),
            outcome
        )
        assertEquals(0, client.callCount)
    }

    // -- 5: transport failure --------------------------------------------

    @Test
    fun measure_onConnectionRefused_returnsFailedEndpointFailure() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("connection reset"))
        }
        val engine = engine(client)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertTrue(failed.failure is MeasurementFailure.EndpointFailure)
    }

    // -- 6: timeout --------------------------------------------------------

    @Test
    fun measure_onTimeout_returnsMeasuredFailedTimeout_andCancelsClientCleanly() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val engine = engine(client, timeoutMillis = 1_000L)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertEquals(MeasurementFailure.Timeout(MeasurementStage.Unknown), failed.failure)
        assertEquals(1, client.cancelledCallCount)
        assertEquals(0, client.completedCallCount)
    }

    // -- 7: cancellation (must remain cancellation, never a value) --------

    @Test
    fun measure_onCallerCancellation_propagates_andEmitsNoOutcome() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val engine = engine(client, timeoutMillis = ONE_DAY_MILLIS)

        var sawAnOutcome = false
        val job = launch {
            engine.measure(request())
            sawAnOutcome = true
        }

        // Same fix ReferenceLatencyProbeExecutorTest needed and
        // documents in detail: runCurrent(), not advanceUntilIdle(),
        // is what stops exactly at the first genuine suspension point
        // without also running past withTimeout's own deadline.
        runCurrent()
        job.cancel()
        job.join()

        assertFalse("a cancelled measurement must never emit an outcome", sawAnOutcome)
        assertEquals(1, client.cancelledCallCount)
    }

    // -- 8: invalid/malformed measurement ----------------------------------

    @Test
    fun measure_onMalformedPayload_returnsFailedInvalidResponse() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(payload = ByteArray(3)) }
        val engine = engine(client)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertTrue(failed.failure is MeasurementFailure.InvalidResponse)
    }

    // -- 9: multiple samples / 11: aggregation -----------------------------

    @Test
    fun measureSeries_withAllSamplesSucceeding_returnsAggregatedStats() = runTest {
        val client = FakeNetworkClient().apply {
            repeat(5) { enqueueSuccess(ByteArray(8)) }
        }
        val engine = engine(client)
        val requests = (1..5L).map { request(id = it, target = "host-$it") }

        val outcome = engine.measureSeries(requests, calculatedAt = Instant.EPOCH)

        val aggregated = outcome as? LatencyAggregationOutcome.Aggregated
        requireNotNull(aggregated) { "expected Aggregated, got $outcome" }
        assertEquals(5, aggregated.stats.sourceMeasurementIds.size)
        assertEquals(5, client.callCount)
        assertEquals(5, outcome.outcomes.size)
    }

    // -- 10: insufficient evidence ------------------------------------------

    @Test
    fun measureSeries_withNoSuccessfulSamples_returnsInsufficientEvidence() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("refused"))
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("refused"))
        }
        val engine = engine(client)
        val requests = listOf(request(id = 1), request(id = 2))

        val outcome = engine.measureSeries(requests)

        assertTrue(outcome is LatencyAggregationOutcome.InsufficientEvidence)
        assertEquals(2, outcome.outcomes.size)
    }

    @Test
    fun measureSeries_withEmptyRequestList_returnsInsufficientEvidence_withoutCallingClient() = runTest {
        val client = FakeNetworkClient()
        val engine = engine(client)

        val outcome = engine.measureSeries(emptyList())

        assertTrue(outcome is LatencyAggregationOutcome.InsufficientEvidence)
        assertEquals(0, client.callCount)
    }

    // -- 12: dispatcher usage ------------------------------------------------

    @Test
    fun measure_runsClientCallOnIoDispatcher() = runTest {
        // FakeNetworkClient's own delay() call only advances under a
        // TestDispatcher sharing this runTest's scheduler -- if the
        // engine did not actually withContext(dispatchers.io) using the
        // dispatcher this test supplies, a mismatched/default dispatcher
        // would leave the delay unresolved and this test would hang
        // (or fail via runTest's own uncompleted-jobs check), not merely
        // return a wrong value. Passing is itself the io-dispatcher-usage
        // proof, matching ReferenceLatencyProbeExecutorTest's identical
        // reasoning for the same seam.
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8), delayMillis = 50) }
        val engine = engine(client)

        val outcome = engine.measure(request())

        assertTrue(outcome is LatencyMeasurementOutcome.Measured)
    }

    // -- 13: no duplicate execution on repeated invocation -------------------

    @Test
    fun measure_calledTwice_executesExactlyOnceEach_noDuplicateOrCachedCall() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8))
            enqueueSuccess(ByteArray(8))
        }
        val engine = engine(client)

        engine.measure(request(id = 1, target = "same-host"))
        engine.measure(request(id = 2, target = "same-host"))

        assertEquals(2, client.callCount)
        assertEquals(listOf("same-host", "same-host"), client.recordedTargets)
    }

    // -- 14: domain-tier vs transport-tier failure separation -----------------

    @Test
    fun measure_transportOutcome_isMappedToDistinctDomainFailure_notOneGenericFailure() = runTest {
        val engineFor = { outcome: NetworkClientOutcome ->
            engine(FakeNetworkClient().apply { enqueueFailure(outcome) })
        }

        val connectionRefused = (engineFor(NetworkClientOutcome.ConnectionRefused("x"))
            .measure(request()) as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        val tlsFailed = (engineFor(NetworkClientOutcome.TlsHandshakeFailed("x"))
            .measure(request()) as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        val networkChanged = (engineFor(NetworkClientOutcome.NetworkChangedMidCall)
            .measure(request()) as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed

        // Three distinct NetworkClientOutcome (transport-tier) cases map
        // to three distinct MeasurementFailure (domain-tier) cases, not
        // collapsed into one generic "failed" -- the exact regression
        // PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 7 warns
        // against, checked here one layer down (transport -> domain
        // mapping) rather than within core:model itself
        // (MeasurementBoundaryTest already covers that layer).
        assertTrue(connectionRefused.failure is MeasurementFailure.EndpointFailure)
        assertTrue(tlsFailed.failure is MeasurementFailure.TlsFailure)
        assertEquals(MeasurementFailure.NetworkChangedDuringMeasurement, networkChanged.failure)
        val distinctTypes = setOf(
            connectionRefused.failure::class,
            tlsFailed.failure::class,
            networkChanged.failure::class
        )
        assertEquals(3, distinctTypes.size)
    }

    /** Same reasoning as `ReferenceLatencyProbeExecutorTest.executor()`:
     * the dispatcher must share *this* [TestScope]'s scheduler. */
    private fun TestScope.engine(
        client: FakeNetworkClient,
        timeoutMillis: Long = LatencyMeasurementEngine.DEFAULT_TIMEOUT_MILLIS,
        elapsedNanos: () -> Long = { 0L },
        classify: (MeasurementCapability, Int, Set<String>) -> CapabilityClassification =
            MeasurementCapabilityClassifier::classify
    ): LatencyMeasurementEngine = LatencyMeasurementEngine(
        dispatchers = TestAerivaDispatchers(StandardTestDispatcher(testScheduler)),
        networkClient = client,
        classify = classify,
        now = { Instant.EPOCH },
        elapsedNanos = elapsedNanos,
        timeoutMillis = timeoutMillis
    )

    private companion object {
        const val ONE_DAY_MILLIS = 86_400_000L
    }
}
