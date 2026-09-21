package com.aeriva.network.monitor.measurement

import com.aeriva.core.common.AerivaDispatchers
import com.aeriva.core.model.measurement.DerivedLatencyStats
import com.aeriva.core.model.measurement.LatencyMeasurement
import com.aeriva.core.model.measurement.MeasurementFailure
import com.aeriva.core.model.measurement.MeasurementNetworkContext
import com.aeriva.core.model.measurement.MeasurementStage
import com.aeriva.network.monitor.CapabilityClassification
import com.aeriva.network.monitor.MeasurementCapability
import com.aeriva.network.monitor.MeasurementCapabilityClassifier
import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The production Phase 3B measurement engine for the LATENCY capability
 * only -- per this phase's explicit scope, jitter/packet-loss/throughput
 * are not implemented here (their `core:model` types don't exist yet;
 * Phase 3A modeled latency as the one illustrative metric, and this
 * engine extends that same scope, not beyond it).
 *
 * This class is the production counterpart
 * [ReferenceLatencyProbeExecutor]'s own KDoc names as "expected to
 * follow this validated pattern... not expected to reuse this exact
 * class." It follows that pattern (seams, timeout/cancellation shape,
 * the unavailable-network short-circuit) and adds what the reference
 * harness deliberately left out: a capability-validation step ahead of
 * execution, and a multi-sample aggregation entry point using the
 * existing [DerivedLatencyStats.from] function -- it still adds no
 * retries, no de-duplication, no persistence, and no scheduling (same
 * exclusions [ReferenceLatencyProbeExecutor] documents).
 *
 * ## Ownership note (does not redefine existing seams)
 * This engine depends on, and does not redefine, redeclare, or fork:
 * [NetworkClient]/[NetworkClientOutcome] (AI 4's `phase-3b-measurement-
 * tests` branch) and [DerivedLatencyStats.from] (also AI 4's branch,
 * additive to the Phase 3A type). Both were reviewed against this
 * engine's needs before writing this file, and both are reused as-is
 * -- see `PHASE_3B_MEASUREMENT_ENGINE_IMPLEMENTATION.md` Section 3 for
 * the explicit compatibility check. No competing interface is created.
 *
 * ## Capability validation
 * Every [measure] call consults [MeasurementCapabilityClassifier] for
 * [MeasurementCapability.LATENCY] before attempting a probe -- an
 * engine that skipped this and let a caller attempt a probe the
 * platform boundary already knows is unavailable would duplicate logic
 * the classifier exists specifically to centralize (AI 3's own report,
 * Section 10). For latency specifically, the classifier's current logic
 * (see its `LATENCY` branch) has exactly two outcomes -- `Supported`
 * (INTERNET held) or `NotReliablyAvailable` (INTERNET not held) -- so
 * this engine's capability-unavailable outcome and its
 * permission-unavailable outcome are, today, the same underlying case.
 * This is stated explicitly rather than left implicit: if a future
 * classifier change ever gives `LATENCY` a genuine
 * `SupportedWithLimitations` branch (unlike today), this engine's
 * `when` below does not currently have an arm for it and would need
 * one added deliberately, not silently falling through.
 *
 * ## Endpoint strategy (explicitly not decided here)
 * This engine takes no position on which endpoint(s) a caller probes
 * -- [LatencyMeasurementRequest.target] is supplied by the caller,
 * opaque to this class, exactly as [NetworkClient.probe]'s own contract
 * already specifies. Baking a specific hostname/IP into this engine as
 * "the" AERIVA latency-test endpoint would be an architecture-level
 * decision (reliability, geographic distribution, who operates it, what
 * happens if it goes down) this phase's instructions do not authorize
 * and this file does not make -- see Section 14 (Open decisions) in the
 * accompanying implementation document.
 *
 * ## Timing: monotonic duration, wall-clock timestamp
 * [measuredAt] on the resulting [LatencyMeasurement] is wall-clock
 * ([now], the existing `() -> Instant` seam), matching
 * [ReferenceLatencyProbeExecutor] and Phase 3A Section 11's convention
 * for anything persisted/compared across a session. The probe's
 * *duration* (`valueMillis`) is measured differently, and deliberately:
 * via [elapsedNanos] (defaulting to `System.nanoTime()`), not by
 * subtracting two [Instant] values. Phase 3A Section 11 explicitly
 * left this exact question open ("flags... whether any single in-flight
 * measurement's own duration calculation needs a monotonic source
 * instead of wall-clock subtraction"); this file resolves it for this
 * engine specifically: a wall-clock adjustment (NTP sync, DST) landing
 * mid-probe would corrupt an `Instant`-subtraction duration even though
 * the real elapsed time was normal, and a latency number is exactly
 * the kind of value where that silent corruption would be worst --
 * `System.nanoTime()` cannot be affected by wall-clock adjustments and
 * is the standard JDK/Android-safe choice for measuring elapsed time.
 * It is isolated behind a plain injectable `() -> Long` seam, following
 * this codebase's existing clock-seam convention exactly (no new
 * interface), so it remains fakeable in a JVM test without
 * instrumentation, same as [now].
 *
 * ## Cancellation (per this phase's explicit requirement)
 * A [kotlinx.coroutines.CancellationException] that is NOT a
 * [TimeoutCancellationException] (i.e. real caller-initiated
 * cancellation, not this engine's own timeout) is never caught here --
 * it propagates unchanged, and [measure] never returns a value for a
 * cancelled attempt. [MeasurementFailure.Cancelled] exists in the
 * domain model (Phase 3A) for a caller/orchestrator layer above this
 * engine to record "the user cancelled this attempt" as history if it
 * chooses to -- this engine itself never constructs that case, per this
 * phase's explicit instruction not to convert cancellation into an
 * ordinary measurement failure. [ReferenceLatencyProbeExecutorTest]'s
 * `measure_onCallerCancellation_propagates_andEmitsNoResult` already
 * proves this exact shape works under `runTest`; this engine follows
 * the identical catch shape (only [TimeoutCancellationException]) for
 * the same reason.
 */
class LatencyMeasurementEngine(
    private val dispatchers: AerivaDispatchers,
    private val networkClient: NetworkClient,
    private val classify: (MeasurementCapability, Int, Set<String>) -> CapabilityClassification =
        MeasurementCapabilityClassifier::classify,
    private val now: () -> Instant = Instant::now,
    private val elapsedNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {

    /**
     * Executes exactly one latency probe for [request], or declines
     * without attempting one. Never throws for any outcome this phase's
     * requirements name, except genuine caller cancellation (see class
     * KDoc), which is not an outcome this function returns a value for.
     */
    suspend fun measure(request: LatencyMeasurementRequest): LatencyMeasurementOutcome {
        val classification = classify(
            MeasurementCapability.LATENCY,
            request.sdkInt,
            request.grantedPermissions
        )
        if (classification is CapabilityClassification.NotReliablyAvailable) {
            return LatencyMeasurementOutcome.CapabilityUnavailable(classification.reason)
        }

        if (!request.context.networkState.available) {
            // Observation-tier fact (Phase 3A Section 12's own mapping
            // table), not a measurement failure -- this engine declines
            // to even attempt, matching ReferenceLatencyProbeExecutor's
            // established precedent, rather than representing "no
            // network" via MeasurementFailure.
            return LatencyMeasurementOutcome.NoNetwork
        }

        val startedAt = now()
        val startNanos = elapsedNanos()

        val measurement: LatencyMeasurement = try {
            withContext(dispatchers.io) {
                withTimeout(timeoutMillis) {
                    toMeasurement(request, startedAt, startNanos, networkClient.probe(request.target))
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            LatencyMeasurement.Failed(
                request.id, request.context, startedAt, request.method,
                MeasurementFailure.Timeout(MeasurementStage.Unknown)
            )
        }

        return LatencyMeasurementOutcome.Measured(measurement)
    }

    /**
     * Runs [requests] sequentially (deliberately not concurrently -- no
     * additional coroutine scope is introduced beyond what a single
     * [measure] call already uses, per this phase's explicit "do not
     * introduce unnecessary coroutine scopes" instruction) and derives
     * [DerivedLatencyStats] from whichever attempts succeeded, via the
     * existing [DerivedLatencyStats.from] function -- this engine adds
     * no second aggregation implementation.
     *
     * A caller that specifically needs concurrent sampling can already
     * achieve it by calling [measure] directly from multiple coroutines
     * itself (proven safe by
     * `ReferenceLatencyProbeExecutorTest.measure_concurrentCalls_doNotCorruptSharedState`
     * for the identical underlying pattern) -- this function does not
     * need to duplicate that capability to remain correct, only to
     * remain simple, per this phase's own minimalism instruction.
     */
    suspend fun measureSeries(
        requests: List<LatencyMeasurementRequest>,
        calculatedAt: Instant = now()
    ): LatencyAggregationOutcome {
        val outcomes = requests.map { measure(it) }
        val succeeded = outcomes
            .filterIsInstance<LatencyMeasurementOutcome.Measured>()
            .map { it.measurement }
            .filterIsInstance<LatencyMeasurement.Succeeded>()

        val stats = DerivedLatencyStats.from(succeeded, calculatedAt)
        return if (stats == null) {
            LatencyAggregationOutcome.InsufficientEvidence(outcomes)
        } else {
            LatencyAggregationOutcome.Aggregated(stats, outcomes)
        }
    }

    private fun toMeasurement(
        request: LatencyMeasurementRequest,
        startedAt: Instant,
        startNanos: Long,
        outcome: NetworkClientOutcome
    ): LatencyMeasurement = when (outcome) {
        is NetworkClientOutcome.Success -> toLatencyResult(request, startedAt, startNanos, outcome)

        is NetworkClientOutcome.ConnectionRefused -> LatencyMeasurement.Failed(
            request.id, request.context, startedAt, request.method,
            MeasurementFailure.EndpointFailure(outcome.reason)
        )

        is NetworkClientOutcome.TlsHandshakeFailed -> LatencyMeasurement.Failed(
            request.id, request.context, startedAt, request.method,
            MeasurementFailure.TlsFailure(outcome.reason)
        )

        NetworkClientOutcome.NetworkChangedMidCall -> LatencyMeasurement.Failed(
            request.id, request.context, startedAt, request.method,
            MeasurementFailure.NetworkChangedDuringMeasurement
        )
    }

    /**
     * A successful transport-level connection whose payload doesn't
     * match [EXPECTED_PAYLOAD_BYTES] is a measurement *failure*
     * ([MeasurementFailure.InvalidResponse]), never a successful
     * measurement carrying garbage data -- same discipline
     * [ReferenceLatencyProbeExecutor] already established and this
     * engine's own tests re-verify independently.
     */
    private fun toLatencyResult(
        request: LatencyMeasurementRequest,
        startedAt: Instant,
        startNanos: Long,
        success: NetworkClientOutcome.Success
    ): LatencyMeasurement {
        if (success.payload.size != EXPECTED_PAYLOAD_BYTES) {
            return LatencyMeasurement.Failed(
                request.id, request.context, startedAt, request.method,
                MeasurementFailure.InvalidResponse(
                    "expected $EXPECTED_PAYLOAD_BYTES byte payload, got ${success.payload.size}"
                )
            )
        }

        val elapsedMillis = (elapsedNanos() - startNanos) / NANOS_PER_MILLI
        return LatencyMeasurement.Succeeded(
            id = request.id,
            context = request.context,
            measuredAt = startedAt,
            method = request.method,
            valueMillis = elapsedMillis,
            sampleCount = 1
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** This engine's own made-up reference-protocol constant, not a
         * real wire format -- matches [ReferenceLatencyProbeExecutor]'s
         * identical placeholder pending a real protocol decision
         * (Section 14 of the accompanying implementation document). */
        const val EXPECTED_PAYLOAD_BYTES = 8

        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/**
 * One request to measure latency once. [sdkInt]/[grantedPermissions]
 * are passed through to [MeasurementCapabilityClassifier] unchanged --
 * this engine does not read `Build.VERSION.SDK_INT` or call
 * `Context.checkSelfPermission` itself, matching that classifier's own
 * no-Android-import convention (a caller at the Android-dependent edge
 * supplies these as plain values).
 */
data class LatencyMeasurementRequest(
    val id: Long,
    val target: String,
    val context: MeasurementNetworkContext,
    val sdkInt: Int,
    val grantedPermissions: Set<String>,
    val method: String = "tcp-round-trip"
)

/**
 * What a single [LatencyMeasurementEngine.measure] call returned. A
 * distinct type from [LatencyMeasurement] itself -- [NoNetwork] and
 * [CapabilityUnavailable] are "this engine declined to attempt," which
 * per Phase 3A Section 12 is not the same kind of thing as
 * [MeasurementFailure] ("this engine attempted and failed"). Genuine
 * caller cancellation is deliberately not a case here at all -- it
 * propagates as a real [kotlinx.coroutines.CancellationException]
 * instead (see [LatencyMeasurementEngine]'s class KDoc).
 */
sealed interface LatencyMeasurementOutcome {
    data class Measured(val measurement: LatencyMeasurement) : LatencyMeasurementOutcome
    data object NoNetwork : LatencyMeasurementOutcome
    data class CapabilityUnavailable(val reason: String) : LatencyMeasurementOutcome
}

/**
 * What [LatencyMeasurementEngine.measureSeries] returned. [outcomes]
 * (every individual [LatencyMeasurementOutcome], in request order) is
 * always present on both cases, so a caller can inspect why a
 * particular sample didn't contribute even when the aggregate result
 * itself is [InsufficientEvidence].
 */
sealed interface LatencyAggregationOutcome {
    val outcomes: List<LatencyMeasurementOutcome>

    data class Aggregated(
        val stats: DerivedLatencyStats,
        override val outcomes: List<LatencyMeasurementOutcome>
    ) : LatencyAggregationOutcome

    data class InsufficientEvidence(
        override val outcomes: List<LatencyMeasurementOutcome>
    ) : LatencyAggregationOutcome
}
