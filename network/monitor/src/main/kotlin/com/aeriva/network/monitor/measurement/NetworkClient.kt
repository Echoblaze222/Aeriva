package com.aeriva.network.monitor.measurement

/**
 * PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md / PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md
 * Section 6 -- "Network client: Small interface wrapping the actual
 * socket/HTTP call a measurement provider issues." This is the seam a
 * future measurement provider depends on instead of touching sockets
 * directly, kept separate from provider-level logic (retries, result
 * shaping) so that logic can be tested independently of transport-level
 * fakes -- same reasoning as [com.aeriva.network.monitor.NetworkMonitor]
 * existing purely as an interface Android's ConnectivityManager sits
 * behind.
 *
 * No implementation of this interface is added by Phase 3B. Per this
 * phase's explicit scope, the real socket-backed implementation is
 * production measurement-engine work, not test-foundation work --
 * see [com.aeriva.network.monitor.measurement.FakeNetworkClient] (test
 * source set) for the only implementation this phase adds, and
 * [com.aeriva.network.monitor.measurement.ReferenceLatencyProbeExecutor]
 * for how a caller is expected to use this interface.
 *
 * No Android import in this file, matching
 * [com.aeriva.network.monitor.NetworkStateMapper]'s established
 * convention -- this keeps the interface itself instantiable/fakeable in
 * a plain JVM unit test, with no instrumentation required.
 */
interface NetworkClient {

    /**
     * Performs one probe against [target] and suspends until a definite
     * outcome is known. Does not itself enforce a timeout or react to
     * cancellation beyond normal coroutine cancellation cooperation --
     * per PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 6, timeout
     * is the calling provider's concern (e.g. wrapping this call in
     * `withTimeout`), not this seam's.
     *
     * @param target an opaque identifier for the endpoint being probed
     *   (e.g. a host:port or URL) -- this interface takes no position on
     *   its format; that is a production-implementation decision this
     *   phase does not make.
     */
    suspend fun probe(target: String): NetworkClientOutcome
}

/**
 * What a single [NetworkClient.probe] call actually returned. Deliberately
 * narrower than [com.aeriva.core.model.measurement.MeasurementFailure]:
 * this describes what happened at the transport level; mapping that to a
 * domain-tier [com.aeriva.core.model.measurement.MeasurementFailure] is
 * the calling provider's job (Section 6's "provider produces raw
 * samples; a separate function scores/interprets them" split, applied
 * one layer down).
 */
sealed interface NetworkClientOutcome {

    /**
     * A response was received. [payload] is raw bytes -- this interface
     * takes no position on protocol/framing; whether [payload] represents
     * a *valid* probe reply (objective 16, malformed responses) is
     * determined by the caller, not by this client succeeding at the
     * transport level. A successful connection returning garbage bytes
     * is exactly the case that must remain distinguishable from a
     * successful connection returning a valid reply -- see
     * [ReferenceLatencyProbeExecutor] for where that check lives.
     */
    data class Success(val payload: ByteArray) : NetworkClientOutcome

    /** The connection attempt itself was refused/reset/unreachable. */
    data class ConnectionRefused(val reason: String) : NetworkClientOutcome

    /** TLS/handshake-level failure, kept distinct per the design doc's
     * Section 12 (whether a retry is ever sensible differs from a plain
     * endpoint failure). */
    data class TlsHandshakeFailed(val reason: String) : NetworkClientOutcome

    /**
     * The active network changed while this call was in flight, per
     * PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 5's row on
     * network transitions -- "JVM can test the code's reaction to a
     * simulated mid-measurement network-changed event via the fake
     * client." A real implementation would only be able to report this
     * if it happens to observe the transition itself (e.g. a socket
     * error correlated with a connectivity callback); this phase does
     * not design that correlation, only the outcome shape a provider
     * needs to be able to react to.
     */
    data object NetworkChangedMidCall : NetworkClientOutcome
}
