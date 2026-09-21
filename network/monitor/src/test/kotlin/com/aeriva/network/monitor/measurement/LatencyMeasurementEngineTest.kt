package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.common.TestAerivaDispatchers
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementFailure
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import com.aeriva.network.monitor.CapabilityClassification
import com.aeriva.network.monitor.MeasurementCapability
import com.aeriva.network.monitor.MeasurementCapabilityClassifier
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            lastChangedAt = Instant.EPOCH
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
        assertEquals(MeasurementFailure.Timeout, failed.failure)
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

    // =====================================================================
    // Phase 4 engine hardening (AI 2). Each test below either exposes a
    // real behavior of the engine that the original fourteen did not
    // pin down, or pins a fix made in the same change. Test doubles used
    // here exist only where FakeNetworkClient has no hook for the
    // behavior under test (a throwing client, a client that advances a
    // fake clock, a concurrency-tracking client).
    // =====================================================================

    // -- Timeout: a deadline must never yield a success ---------------------

    @Test
    fun measure_whenValidPayloadWouldArriveAfterDeadline_returnsTimeout_neverSucceeded() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8), delayMillis = 2_000) }
        val engine = engine(client, timeoutMillis = 1_000L)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertTrue(failed.failure is MeasurementFailure.Timeout)
        assertEquals(0, client.completedCallCount)
        assertEquals(1, client.cancelledCallCount)
    }

    @Test
    fun measure_whenClientIgnoresCancellationAndReturnsValidPayloadLate_stillReportsTimeout() = runTest {
        val client = object : NetworkClient {
            override suspend fun probe(target: String): NetworkClientOutcome =
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    // A misbehaving client that swallows the timeout's
                    // cancellation and hands back a perfectly valid reply.
                    NetworkClientOutcome.Success(ByteArray(8))
                }
        }
        val engine = engine(client, timeoutMillis = 1_000L)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertTrue(failed.failure is MeasurementFailure.Timeout)
    }

    @Test
    fun measure_whenClientThrowsItsOwnTimeout_whileCallerIsActive_returnsTimeoutFailure_notSilentCancellation() = runTest {
        val client = object : NetworkClient {
            override suspend fun probe(target: String): NetworkClientOutcome =
                withTimeout(10L) { awaitCancellation() }
        }
        val engine = engine(client, timeoutMillis = ONE_DAY_MILLIS)

        val outcome = engine.measure(request())

        val failed = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
        assertTrue(failed.failure is MeasurementFailure.Timeout)
        assertTrue("the caller was never cancelled", isActive)
    }

    // -- Cancellation: the caller's own timeout is cancellation, not a failure --

    @Test
    fun measure_whenCallersOwnTimeoutFires_propagatesCancellation_insteadOfReturningTimeoutFailure() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val engine = engine(client, timeoutMillis = ONE_DAY_MILLIS)

        var deliveredOutcome: LatencyMeasurementOutcome? = null
        var callerTimeout: TimeoutCancellationException? = null
        try {
            withTimeout(500L) {
                deliveredOutcome = engine.measure(request())
            }
        } catch (e: TimeoutCancellationException) {
            callerTimeout = e
        }

        assertNotNull("the caller's own timeout must still surface to the caller", callerTimeout)
        assertNull(
            "engine handed a value to a caller whose own timeout had already fired: $deliveredOutcome",
            deliveredOutcome
        )
        assertEquals(1, client.cancelledCallCount)
    }

    @Test
    fun measureSeries_whenCallersOwnTimeoutFires_propagatesCancellation_andDeliversNoPartialResult() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val engine = engine(client, timeoutMillis = ONE_DAY_MILLIS)
        val requests = (1..3L).map { request(id = it) }

        var deliveredSeries: LatencyAggregationOutcome? = null
        var callerTimeout: TimeoutCancellationException? = null
        try {
            withTimeout(500L) {
                deliveredSeries = engine.measureSeries(requests)
            }
        } catch (e: TimeoutCancellationException) {
            callerTimeout = e
        }

        assertNotNull(callerTimeout)
        assertNull(
            "series kept running and returned a result after the caller's timeout fired: $deliveredSeries",
            deliveredSeries
        )
        assertEquals(1, client.callCount)
    }

    @Test
    fun measure_whenCallerIsAlreadyCancelled_evenOnDeclinePath_throwsCancellation_notNoNetwork() = runTest {
        val client = FakeNetworkClient()
        val engine = engine(client)

        var deliveredOutcome: LatencyMeasurementOutcome? = null
        val job = launch {
            coroutineContext.job.cancel()
            deliveredOutcome = engine.measure(request(context = unavailableContext))
        }
        job.join()

        assertNull("a cancelled caller must never receive an outcome: $deliveredOutcome", deliveredOutcome)
        assertEquals(0, client.callCount)
    }

    @Test
    fun measureSeries_onCancellationMidSeries_stopsAtCancelledProbe_andRunsNoFurtherProbes() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8), delayMillis = 10)
            enqueueHang()
            enqueueSuccess(ByteArray(8)) // must never be consumed
        }
        val engine = engine(client, timeoutMillis = ONE_DAY_MILLIS)
        val requests = (1..3L).map { request(id = it) }

        var deliveredSeries: LatencyAggregationOutcome? = null
        val job = launch { deliveredSeries = engine.measureSeries(requests) }

        advanceTimeBy(10)
        runCurrent()
        assertEquals("series should be inside request 2's hung probe", 2, client.callCount)

        job.cancel()
        job.join()

        assertNull(deliveredSeries)
        assertEquals(2, client.callCount)
        assertEquals(1, client.completedCallCount)
        assertEquals(1, client.cancelledCallCount)
    }

    // -- Monotonic timing ------------------------------------------------------

    @Test
    fun measure_onSuccess_reportsNonZeroElapsedTime_fromInjectedMonotonicClock() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8), delayMillis = 42) }
        val engine = engine(client, elapsedNanos = { testScheduler.currentTime * NANOS_PER_MILLI })

        val outcome = engine.measure(request())

        val succeeded = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Succeeded
        assertEquals(42.0, succeeded.valueMillis, 0.0001)
    }

    @Test
    fun measure_duration_isNotAffectedByWallClockJumpDuringProbe() = runTest {
        val wallClock = MutableClock(Instant.EPOCH)
        var monotonicNanos = 0L
        val client = object : NetworkClient {
            override suspend fun probe(target: String): NetworkClientOutcome {
                monotonicNanos += 25 * NANOS_PER_MILLI
                wallClock.advanceBy(-3_600_000L) // wall clock stepped back an hour mid-probe
                return NetworkClientOutcome.Success(ByteArray(8))
            }
        }
        val engine = engine(client, elapsedNanos = { monotonicNanos }, now = wallClock::now)

        val outcome = engine.measure(request())

        val succeeded = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Succeeded
        assertEquals(25.0, succeeded.valueMillis, 0.0001)
        assertEquals(Instant.EPOCH, succeeded.measuredAt)
    }

    @Test
    fun measure_doesNotCountDispatcherQueueingTowardProbeLatency() = runTest {
        var monotonicNanos = 0L
        val hopCost = 40 * NANOS_PER_MILLI
        val hopping = HopCostDispatcher(StandardTestDispatcher(testScheduler)) { monotonicNanos += hopCost }
        val dispatchers = object : AerivaDispatchers {
            override val main = hopping
            override val io = hopping
            override val default = hopping
        }
        val client = object : NetworkClient {
            override suspend fun probe(target: String): NetworkClientOutcome {
                monotonicNanos += 50 * NANOS_PER_MILLI
                return NetworkClientOutcome.Success(ByteArray(8))
            }
        }
        val engine = engine(client, elapsedNanos = { monotonicNanos }, dispatchers = dispatchers)

        val outcome = engine.measure(request())

        val succeeded = (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Succeeded
        assertEquals(
            "latency must be the probe's own duration, not probe + time spent waiting for the io dispatcher",
            50.0,
            succeeded.valueMillis,
            0.0001
        )
    }

    @Test
    fun measure_withNonMonotonicElapsedClock_failsLoudly_insteadOfReportingNegativeLatency() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        var reads = 0
        val engine = engine(client, elapsedNanos = { if (reads++ == 0) 1_000_000L else 400_000L })

        var delivered: LatencyMeasurementOutcome? = null
        try {
            delivered = engine.measure(request())
            fail("a clock that runs backwards must not produce a measurement: $delivered")
        } catch (expected: IllegalStateException) {
            // loud programming-error signal, not a fabricated Succeeded
        }
        assertNull(delivered)
    }

    @Test
    fun constructor_rejectsNonPositiveTimeout() = runTest {
        assertThrows(IllegalArgumentException::class.java) { engine(FakeNetworkClient(), timeoutMillis = 0L) }
        assertThrows(IllegalArgumentException::class.java) { engine(FakeNetworkClient(), timeoutMillis = -1L) }
    }

    // -- Failure propagation -----------------------------------------------------

    @Test
    fun measure_transportFailureReasons_areCarriedThrough_andFailedResultKeepsRequestIdentity() = runTest {
        val refused = measureFailed(NetworkClientOutcome.ConnectionRefused("refused by peer"), id = 7)
        val tls = measureFailed(NetworkClientOutcome.TlsHandshakeFailed("bad certificate"), id = 8)

        assertEquals(MeasurementFailure.EndpointFailure("refused by peer"), refused.failure)
        assertEquals(MeasurementFailure.TlsFailure("bad certificate"), tls.failure)

        assertEquals(7L, refused.id)
        assertEquals(8L, tls.id)
        assertSame(availableContext, refused.context)
        assertSame(availableContext, tls.context)
        assertEquals("tcp-round-trip", refused.method)
        assertEquals(Instant.EPOCH, refused.measuredAt)
    }

    @Test
    fun measure_everyFailureCause_mapsToItsOwnDomainCase_andNoneIsSucceeded() = runTest {
        val endpoint = measureFailed(NetworkClientOutcome.ConnectionRefused("x"))
        val tls = measureFailed(NetworkClientOutcome.TlsHandshakeFailed("x"))
        val changed = measureFailed(NetworkClientOutcome.NetworkChangedMidCall)
        val invalid = measureFailed(NetworkClientOutcome.Success(ByteArray(3)))
        val timedOut = (engine(FakeNetworkClient().apply { enqueueHang() }, timeoutMillis = 1_000L)
            .measure(request()) as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed

        val classes = listOf(endpoint, tls, changed, invalid, timedOut).map { it.failure::class }

        assertEquals("five causes must stay five distinct cases: $classes", 5, classes.toSet().size)
    }

    // -- Exception boundary --------------------------------------------------------

    @Test
    fun measure_onUnexpectedClientException_propagates_andNeverBecomesAMeasurement() = runTest {
        val client = ThrowingNetworkClient(IllegalStateException("programming error in client"))
        val engine = engine(client)

        var delivered: LatencyMeasurementOutcome? = null
        var thrown: Throwable? = null
        try {
            delivered = engine.measure(request())
        } catch (e: IllegalStateException) {
            thrown = e
        }

        assertNull(delivered)
        assertNotNull(thrown)
        assertEquals("programming error in client", thrown?.message)
        assertEquals(1, client.calls)
    }

    @Test
    fun measureSeries_onUnexpectedClientException_abandonsSeries_withoutPartialResult() = runTest {
        val client = ThrowingNetworkClient(IllegalStateException("programming error in client"))
        val engine = engine(client)

        var delivered: LatencyAggregationOutcome? = null
        try {
            delivered = engine.measureSeries((1..3L).map { request(id = it) })
            fail("an unexpected exception must not be folded into an aggregate: $delivered")
        } catch (expected: IllegalStateException) {
            // propagated
        }

        assertNull(delivered)
        assertEquals("series must stop at the failing request", 1, client.calls)
    }

    // -- measureSeries ----------------------------------------------------------------

    @Test
    fun measureSeries_runsProbesSequentially_inRequestOrder() = runTest {
        val client = InFlightTrackingClient(probeMillis = 100)
        val engine = engine(client, elapsedNanos = { testScheduler.currentTime * NANOS_PER_MILLI })
        val requests = (1..4L).map { request(id = it, target = "host-$it") }

        val outcome = engine.measureSeries(requests)

        assertEquals("probes must never overlap", 1, client.maxInFlight)
        assertEquals(listOf("host-1", "host-2", "host-3", "host-4"), client.targets)
        assertEquals("sequential probes take the sum of their durations", 400L, testScheduler.currentTime)
        val measurements = outcome.outcomes.map {
            (it as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Succeeded
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), measurements.map { it.id })
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0), measurements.map { it.valueMillis })
    }

    @Test
    fun measureSeries_withMixedOutcomes_keepsRequestOrder_andAggregatesOnlySuccesses() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8))
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("refused"))
            enqueueSuccess(ByteArray(8))
        }
        val engine = engine(client)
        val requests = listOf(
            request(id = 1),
            request(id = 2),
            request(id = 3, context = unavailableContext),
            request(id = 4, grantedPermissions = emptySet()),
            request(id = 5)
        )

        val outcome = engine.measureSeries(requests)

        val outcomes = outcome.outcomes
        assertEquals(5, outcomes.size)
        assertTrue(((outcomes[0] as LatencyMeasurementOutcome.Measured).measurement) is LatencyMeasurement.Succeeded)
        assertTrue(((outcomes[1] as LatencyMeasurementOutcome.Measured).measurement) is LatencyMeasurement.Failed)
        assertEquals(LatencyMeasurementOutcome.NoNetwork, outcomes[2])
        assertTrue(outcomes[3] is LatencyMeasurementOutcome.CapabilityUnavailable)
        assertTrue(((outcomes[4] as LatencyMeasurementOutcome.Measured).measurement) is LatencyMeasurement.Succeeded)
        val aggregated = outcome as LatencyAggregationOutcome.Aggregated
        assertEquals(listOf(1L, 5L), aggregated.stats.sourceMeasurementIds)
        assertEquals("declined requests must not reach the client", 3, client.callCount)
    }

    @Test
    fun measureSeries_withTimeouts_cancelsEachProbe_continuesSequentially_andLeavesNothingRunning() = runTest {
        val client = FakeNetworkClient().apply { repeat(3) { enqueueHang() } }
        val engine = engine(client, timeoutMillis = 100L)

        val outcome = engine.measureSeries((1..3L).map { request(id = it) })

        assertTrue(outcome is LatencyAggregationOutcome.InsufficientEvidence)
        assertEquals(3, outcome.outcomes.size)
        outcome.outcomes.forEach {
            val failed = (it as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
            assertTrue(failed.failure is MeasurementFailure.Timeout)
        }
        assertEquals(3, client.cancelledCallCount)
        assertEquals(0, client.completedCallCount)
        assertEquals("three sequential 100ms deadlines", 300L, testScheduler.currentTime)
    }

    @Test
    fun measureSeries_derivedStatsAreCalculatedAfterTheirSourceMeasurements() = runTest {
        val client = FakeNetworkClient().apply { repeat(3) { enqueueSuccess(ByteArray(8), delayMillis = 100) } }
        val engine = engine(
            client,
            elapsedNanos = { testScheduler.currentTime * NANOS_PER_MILLI },
            now = { Instant.EPOCH.plusMillis(testScheduler.currentTime) }
        )

        val outcome = engine.measureSeries((1..3L).map { request(id = it) })

        val aggregated = outcome as LatencyAggregationOutcome.Aggregated
        val lastMeasuredAt = outcome.outcomes
            .map { ((it as LatencyMeasurementOutcome.Measured).measurement).measuredAt }
            .max()
        assertEquals(Instant.EPOCH.plusMillis(200), lastMeasuredAt)
        assertEquals(
            "calculatedAt must not predate the samples it was calculated from",
            Instant.EPOCH.plusMillis(300),
            aggregated.stats.calculatedAt
        )
    }

    // -- Context ------------------------------------------------------------------------

    @Test
    fun measureSeries_attachesEachRequestsOwnContextVerbatim_evenWhenThatContextIsStale() = runTest {
        val staleCellular = MeasurementNetworkContext(
            networkState = availableContext.networkState.copy(
                transport = TransportType.CELLULAR,
                metered = true,
                lastChangedAt = Instant.EPOCH.minusSeconds(3_600)
            )
        )
        val client = FakeNetworkClient().apply {
            enqueueSuccess(ByteArray(8))
            enqueueSuccess(ByteArray(8))
        }
        val engine = engine(client)

        val outcome = engine.measureSeries(
            listOf(
                request(id = 1, context = availableContext),
                request(id = 2, context = staleCellular),
                request(id = 3, context = unavailableContext)
            )
        )

        val first = (outcome.outcomes[0] as LatencyMeasurementOutcome.Measured).measurement
        val second = (outcome.outcomes[1] as LatencyMeasurementOutcome.Measured).measurement
        assertSame(availableContext, first.context)
        // The engine never refreshes or validates the caller's snapshot: a
        // context an hour out of date is attached exactly as supplied.
        assertSame(staleCellular, second.context)
        assertEquals(LatencyMeasurementOutcome.NoNetwork, outcome.outcomes[2])
    }

    // -- NetworkQuality: the engine never fabricates a generic score ------------------------

    @Test
    fun engine_neverPopulatesNetworkQuality_onAnyOutcomePath() = runTest {
        val success = engine(FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) })
            .measure(request())
        val refused = engine(FakeNetworkClient().apply {
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("x"))
        }).measure(request())
        val invalid = engine(FakeNetworkClient().apply { enqueueSuccess(ByteArray(3)) })
            .measure(request())
        val timedOut = engine(FakeNetworkClient().apply { enqueueHang() }, timeoutMillis = 1_000L)
            .measure(request())

        listOf(success, refused, invalid, timedOut).forEach { outcome ->
            val measurement = (outcome as LatencyMeasurementOutcome.Measured).measurement
            assertSame(availableContext, measurement.context)
            assertEquals(
                NetworkQuality.Unavailable,
                measurement.context.networkState.estimatedQuality
            )
        }
    }

    // -- Test doubles (only where FakeNetworkClient has no hook) -----------------------------

    private class ThrowingNetworkClient(private val error: Throwable) : NetworkClient {
        var calls = 0
            private set

        override suspend fun probe(target: String): NetworkClientOutcome {
            calls++
            throw error
        }
    }

    private class InFlightTrackingClient(private val probeMillis: Long) : NetworkClient {
        var maxInFlight = 0
            private set
        val targets = mutableListOf<String>()
        private var inFlight = 0

        override suspend fun probe(target: String): NetworkClientOutcome {
            targets += target
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(probeMillis)
                return NetworkClientOutcome.Success(ByteArray(8))
            } finally {
                inFlight--
            }
        }
    }

    /** Charges a caller-supplied cost every time work is dispatched onto
     * it, standing in for real io-pool queueing delay. */
    private class HopCostDispatcher(
        private val delegate: CoroutineDispatcher,
        private val onDispatch: () -> Unit
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            onDispatch()
            delegate.dispatch(context, block)
        }
    }

    /** Same reasoning as `ReferenceLatencyProbeExecutorTest.executor()`:
     * the dispatcher must share *this* [TestScope]'s scheduler.
     *
     * Phase 4 hardening: [client] widened from [FakeNetworkClient] to
     * [NetworkClient], and [now]/[dispatchers] added as defaulted
     * parameters, so the hardening tests reuse this one factory instead
     * of building a second one. [classify] must stay the LAST parameter:
     * existing call sites pass it as a trailing lambda. */
    private fun TestScope.engine(
        client: NetworkClient,
        timeoutMillis: Long = LatencyMeasurementEngine.DEFAULT_TIMEOUT_MILLIS,
        elapsedNanos: () -> Long = { 0L },
        now: () -> Instant = { Instant.EPOCH },
        dispatchers: AerivaDispatchers = TestAerivaDispatchers(StandardTestDispatcher(testScheduler)),
        classify: (MeasurementCapability, Int, Set<String>) -> CapabilityClassification =
            MeasurementCapabilityClassifier::classify
    ): LatencyMeasurementEngine = LatencyMeasurementEngine(
        dispatchers = dispatchers,
        networkClient = client,
        classify = classify,
        now = now,
        elapsedNanos = elapsedNanos,
        timeoutMillis = timeoutMillis
    )

    private suspend fun TestScope.measureFailed(
        clientOutcome: NetworkClientOutcome,
        id: Long = 1
    ): LatencyMeasurement.Failed {
        val client = FakeNetworkClient().apply { enqueueFailure(clientOutcome) }
        val outcome = engine(client).measure(request(id = id))
        return (outcome as LatencyMeasurementOutcome.Measured).measurement as LatencyMeasurement.Failed
    }

    private companion object {
        const val ONE_DAY_MILLIS = 86_400_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
