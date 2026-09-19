package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.TestAerivaDispatchers
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE_3B_ENGINE_TEST_GATE_REPORT.md's independent addition to
 * [LatencyMeasurementEngineTest] -- three gaps found by comparing that
 * file's actual assertions against [LatencyMeasurementEngine]'s
 * production code line by line, not a duplicate of what it already
 * covers (that file's own docstring already lists what it deliberately
 * doesn't re-test; this one does the same for what IT already covers).
 * [LatencyMeasurementEngineTest] itself is not modified -- this is a
 * separate file, added independently.
 *
 * Gap 1 (the significant one): every test in
 * [LatencyMeasurementEngineTest] that reaches a successful measurement
 * uses that file's own `engine()` helper's default
 * `elapsedNanos: () -> Long = { 0L }` -- meaning `elapsedNanos() -
 * startNanos` is `0L - 0L = 0` in literally every case, so
 * `valueMillis`'s actual arithmetic
 * (`(elapsedNanos() - startNanos) / NANOS_PER_MILLI`) has never been
 * exercised with a real, non-zero pair anywhere in this branch before
 * this file. An off-by-1000 unit error, an integer-division truncation,
 * or a sign error in that one line would pass every existing test
 * unchanged. [valueMillis_reflectsInjectedElapsedNanosSeam_notAlwaysZero]
 * below closes that gap.
 *
 * Gap 2: nothing in [LatencyMeasurementEngineTest] asserts that a
 * successful measurement's resulting
 * [com.aeriva.core.model.measurement.MeasurementNetworkContext.networkState]
 * ([NetworkState.estimatedQuality] specifically) is the exact same
 * value the request supplied -- i.e. that this engine never fabricates,
 * recomputes, or overwrites a quality/score value of its own.
 * [measure_neverFabricatesOrMutatesEstimatedQuality] below is the test
 * this phase's own instructions explicitly asked for ("no accidental
 * generic NetworkQuality score generation").
 *
 * Gap 3: [LatencyMeasurementEngine.measureSeries]'s own KDoc states it
 * runs sequentially, "deliberately not concurrently," but no existing
 * test actually distinguishes sequential from concurrent execution --
 * [measureSeries_withAllSamplesSucceeding] in the other file would pass
 * either way. [measureSeries_executesRequestsSequentially_notConcurrently]
 * below discriminates between the two using virtual time itself as the
 * proof, the same technique this codebase already uses for dispatcher-
 * boundary proofs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencyMeasurementEngineGateTest {

    private val availableContext = contextWith(NetworkQuality.Unavailable)

    private fun contextWith(quality: NetworkQuality) =
        MeasurementNetworkContext(
            networkState = NetworkState(
                transport = TransportType.WIFI,
                available = true,
                validated = true,
                metered = false,
                capabilities = emptySet(),
                estimatedQuality = quality,
                diagnosticsStatus = DiagnosticsStatus.NotAvailable,
                lastChangedAt = Instant.EPOCH
            )
        )

    // -- Gap 1: valueMillis must reflect the real elapsedNanos seam ----

    @Test
    fun valueMillis_reflectsInjectedElapsedNanosSeam_notAlwaysZero() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        // 1,500,000 ns = 1.5 ms specifically: chosen so an integer-
        // division bug (Long / Long truncating to 1) would produce a
        // different, wrong, but easy-to-miss-as-"close enough" result
        // (1.0) instead of matching this exactly (1.5). A round number
        // of milliseconds would not have caught that class of bug.
        val nanoReadings = ArrayDeque(listOf(0L, 1_500_000L))
        val engine = engine(client, elapsedNanos = { nanoReadings.removeFirst() })

        val outcome = engine.measure(request())

        val succeeded = (outcome as LatencyMeasurementOutcome.Measured).measurement
            as LatencyMeasurement.Succeeded
        assertEquals(1.5, succeeded.valueMillis, 0.0001)
    }

    // -- Gap 2: no accidental generic NetworkQuality score generation --

    /**
     * This test's real point: [NetworkQuality.Measured] is exactly the
     * type a violation of "no accidental generic NetworkQuality score
     * generation" would misuse -- constructing a new one instead of
     * passing the request's own value through unchanged. A distinctive
     * score/label pair, not present anywhere else in either test file,
     * so a bug that silently replaced it would be visible rather than
     * accidentally matching by coincidence.
     */
    @Test
    fun measure_neverFabricatesOrMutatesEstimatedQuality() = runTest {
        val distinctiveContext = contextWith(NetworkQuality.Measured(score = 77, label = "pre-existing-marker"))
        val client = FakeNetworkClient().apply { enqueueSuccess(ByteArray(8)) }
        val engine = engine(client)

        val outcome = engine.measure(request(context = distinctiveContext))

        val succeeded = (outcome as LatencyMeasurementOutcome.Measured).measurement
            as LatencyMeasurement.Succeeded
        // Same instance, not merely an equal one -- proves the engine
        // passes the context through untouched rather than
        // reconstructing an equivalent-looking one that happens to
        // carry the same quality value.
        assertSame(distinctiveContext, succeeded.context)
        assertEquals(
            NetworkQuality.Measured(score = 77, label = "pre-existing-marker"),
            succeeded.context.networkState.estimatedQuality
        )
    }

    // -- Gap 3: measureSeries is genuinely sequential, not concurrent --

    @Test
    fun measureSeries_executesRequestsSequentially_notConcurrently() = runTest {
        val client = FakeNetworkClient().apply {
            repeat(3) { enqueueSuccess(ByteArray(8), delayMillis = 100) }
        }
        val engine = engine(client)
        val requests = (1..3L).map { request(id = it, target = "host-$it") }

        engine.measureSeries(requests)

        // Three 100ms (virtual-time) calls in true sequence advance the
        // scheduler by 300ms total. If measureSeries ever became
        // concurrent (e.g. requests.map { async { measure(it) } }
        // instead of the current requests.map { measure(it) }), all
        // three delays would overlap under virtual time and this would
        // read ~100ms instead -- this test would then fail, which is
        // the point: it discriminates the two, rather than merely
        // being compatible with either.
        assertEquals(300L, testScheduler.currentTime)
        assertEquals(listOf("host-1", "host-2", "host-3"), client.recordedTargets)
    }

    // -- Documents rather than tests: no maximum-sample-count exists ---

    @Test
    fun measureSeries_withManySamples_aggregatesAllOfThem_noCapEnforced() = runTest {
        // PHASE_3B_ENGINE_TEST_GATE_REPORT.md's ARCHITECTURAL CONCERN
        // finding: LatencyMeasurementEngine.measureSeries has no upper
        // bound on request-list size anywhere in its production code --
        // this test demonstrates that today (50 requests are all
        // accepted and aggregated, not truncated or rejected), it does
        // not assert a cap exists, because none does. See the report
        // for why that absence is worth a decision, not a silent gap.
        val sampleCount = 50
        val client = FakeNetworkClient().apply { repeat(sampleCount) { enqueueSuccess(ByteArray(8)) } }
        val engine = engine(client)
        val requests = (1..sampleCount.toLong()).map { request(id = it, target = "host-$it") }

        val outcome = engine.measureSeries(requests, calculatedAt = Instant.EPOCH)

        val aggregated = outcome as? LatencyAggregationOutcome.Aggregated
        requireNotNull(aggregated) { "expected Aggregated, got $outcome" }
        assertEquals(sampleCount, aggregated.stats.sourceMeasurementIds.size)
        assertEquals(sampleCount, client.callCount)
        assertTrue(aggregated.stats.averageMillis.isFinite())
    }

    private fun request(
        id: Long = 1,
        target: String = "host",
        context: MeasurementNetworkContext = availableContext,
        sdkInt: Int = 34,
        grantedPermissions: Set<String> = setOf("android.permission.INTERNET")
    ) = LatencyMeasurementRequest(id, target, context, sdkInt, grantedPermissions)

    /** Same reasoning as LatencyMeasurementEngineTest's own identically-
     * named helper: the dispatcher must share this TestScope's scheduler. */
    private fun TestScope.engine(
        client: FakeNetworkClient,
        elapsedNanos: () -> Long = { 0L }
    ): LatencyMeasurementEngine = LatencyMeasurementEngine(
        dispatchers = TestAerivaDispatchers(StandardTestDispatcher(testScheduler)),
        networkClient = client,
        now = { Instant.EPOCH },
        elapsedNanos = elapsedNanos
    )
}
