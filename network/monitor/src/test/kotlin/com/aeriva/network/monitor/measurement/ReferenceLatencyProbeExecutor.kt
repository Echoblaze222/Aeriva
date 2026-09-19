package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementFailure
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ---------------------------------------------------------------------
 * TEST-ONLY REFERENCE CODE. Lives in `src/test`, not `src/main` -- it is
 * never compiled into the app and is not the Phase 3B measurement
 * engine. Its only purpose is to prove, with real running tests, that
 * [NetworkClient] + [FakeNetworkClient] + the existing
 * [AerivaDispatchers]/clock seams are actually *sufficient* to
 * deterministically test every concurrency/lifecycle/failure-mode
 * concern PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md's task instructions
 * named (timeout, cancellation, concurrent measurements, stale/duplicate
 * results, cleanup, malformed responses) -- see
 * [ReferenceLatencyProbeExecutorTest] for that proof.
 *
 * When the real Phase 3B measurement engine is implemented, it is
 * expected to follow this validated pattern (seams, timeout/cancellation
 * handling shape, the observation-tier-unavailable-network short-circuit)
 * -- it is not expected to reuse this exact class, which deliberately
 * omits everything a real engine needs and this phase's instructions
 * exclude: retries, de-duplication, persistence, scheduling, jitter/
 * packet-loss/throughput, and any real socket I/O.
 * ---------------------------------------------------------------------
 *
 * Single metric (latency), matching Phase 3A's "one illustrative
 * concrete type per tier" precedent for the same reason: this proves the
 * pattern, not full metric coverage.
 */
class ReferenceLatencyProbeExecutor(
    private val dispatchers: AerivaDispatchers,
    private val networkClient: NetworkClient,
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {

    /**
     * @return null when [context]'s network is unavailable -- per the
     *   domain design doc Section 12, "unavailable network" is an
     *   observation-tier fact, not a measurement failure, so declining to
     *   even attempt is the correct response, not an error to represent
     *   via [MeasurementFailure] (objective 7). A non-timeout
     *   [kotlinx.coroutines.CancellationException] from real caller
     *   cancellation is deliberately not caught anywhere in this
     *   function -- it propagates, and no [LatencyMeasurement] is ever
     *   returned for a cancelled attempt (objectives 9/13/14).
     */
    suspend fun measure(
        id: Long,
        target: String,
        context: MeasurementNetworkContext,
        method: String = "tcp-round-trip"
    ): LatencyMeasurement? {
        if (!context.networkState.available) return null

        val startedAt = now()

        return try {
            withContext(dispatchers.io) {
                withTimeout(timeoutMillis) {
                    toMeasurement(id, context, method, startedAt, networkClient.probe(target))
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            LatencyMeasurement.Failed(id, context, startedAt, method, MeasurementFailure.Timeout)
        }
    }

    private fun toMeasurement(
        id: Long,
        context: MeasurementNetworkContext,
        method: String,
        startedAt: Instant,
        outcome: NetworkClientOutcome
    ): LatencyMeasurement = when (outcome) {
        is NetworkClientOutcome.Success -> toLatencyResult(id, context, method, startedAt, outcome)

        is NetworkClientOutcome.ConnectionRefused -> LatencyMeasurement.Failed(
            id, context, startedAt, method, MeasurementFailure.EndpointFailure(outcome.reason)
        )

        is NetworkClientOutcome.TlsHandshakeFailed -> LatencyMeasurement.Failed(
            id, context, startedAt, method, MeasurementFailure.TlsFailure(outcome.reason)
        )

        NetworkClientOutcome.NetworkChangedMidCall -> LatencyMeasurement.Failed(
            id, context, startedAt, method, MeasurementFailure.NetworkChangedDuringMeasurement
        )
    }

    /**
     * A successful transport-level connection whose payload doesn't
     * match this reference protocol's expected shape is a measurement
     * *failure* (objective 16), not a successful measurement with
     * garbage data -- this is the check
     * [com.aeriva.network.monitor.measurement.NetworkClient]'s own KDoc
     * on [NetworkClientOutcome.Success] points to.
     */
    private fun toLatencyResult(
        id: Long,
        context: MeasurementNetworkContext,
        method: String,
        startedAt: Instant,
        success: NetworkClientOutcome.Success
    ): LatencyMeasurement {
        if (success.payload.size != EXPECTED_PAYLOAD_BYTES) {
            return LatencyMeasurement.Failed(
                id, context, startedAt, method,
                MeasurementFailure.InvalidResponse(
                    "expected $EXPECTED_PAYLOAD_BYTES byte payload, got ${success.payload.size}"
                )
            )
        }

        // Wall-clock elapsed time via the injected clock seam, per this
        // design doc's Section 11 recommendation. Whether a per-probe
        // duration should instead use a monotonic source is Section 18
        // item 2's own open decision, not resolved by this reference
        // harness -- see PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md.
        val elapsedMillis = Duration.between(startedAt, now()).toMillis().toDouble()
        return LatencyMeasurement.Succeeded(
            id = id,
            context = context,
            measuredAt = startedAt,
            method = method,
            valueMillis = elapsedMillis,
            sampleCount = 1
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** This reference harness's own made-up protocol constant, not a
         * real wire format -- any real implementation defines its own. */
        const val EXPECTED_PAYLOAD_BYTES = 8
    }
}
