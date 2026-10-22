package com.aeriva.core.model.measurement

/**
 * Which IP address family a probe actually used. Recorded, never
 * forced -- Decision D2's endpoint requirements ask for dual-stack
 * reachability precisely so a probe's address family is an observation,
 * not a client-side choice AERIVA silently biases (Architecture doc
 * Section 10.6, PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md
 * Section 13.3).
 */
enum class AddressFamily {
    IPv4,
    IPv6,
    Unknown
}

/**
 * Whether a probe reused an already-established connection. Recorded
 * because a latency value with unknown cold/warm state cannot be
 * labeled honestly -- this is the exact reason `HttpsURLConnection` was
 * rejected as a client (Decision D3, D3.9). [NotApplicable] covers
 * probe kinds with no connection-reuse concept (for example a UDP
 * exchange).
 */
enum class ConnectionState {
    Cold,
    Warm,
    NotApplicable
}

/**
 * Per-phase timings for one probe, in milliseconds, as observed by the
 * client's own event listener with its own injected monotonic clock
 * (Decision D3-6). Every field is nullable because not every client or
 * every probe kind can separate every phase -- see
 * PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md Section 7.3's own requirement
 * that only what is actually separable is reported, never a guessed
 * split of an opaque total.
 */
data class PhaseTimings(
    val dnsMillis: Double? = null,
    val connectMillis: Double? = null,
    val tlsMillis: Double? = null,
    val firstByteMillis: Double? = null,
    val lastByteMillis: Double? = null
)

/**
 * Shared, per-probe evidence attached to a measurement result. Per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-1 --
 * the same evidence shape is reused across every MEASUREMENT-tier
 * result (latency today; jitter is derived from latency evidence;
 * future capabilities attach the same type) instead of each probe kind
 * inventing its own diagnostic fields.
 *
 * This is diagnostic evidence, not a second measurement result -- it
 * exists to make a measurement's own honesty checkable (was this really
 * cold? did it really use the pinned network? did the server actually
 * echo this exchange's own processing time?), never to be read as a
 * quality signal on its own. See [com.aeriva.core.model.NetworkQuality]'s
 * own KDoc on why a raw diagnostic must never collapse into a score.
 *
 * [serverRegionId] and [serverProcessingMillis] are server-reported
 * values (Decision D2-3, requirement R2) and must be validated (bounded
 * length, constrained charset for the region id) before being stored,
 * per Decision D5-7's prohibition on trusting untyped server strings --
 * validation happens at the client boundary that parses the server
 * response, not in this type.
 */
data class ProbeEvidence(
    val networkHandle: Long?,
    val addressFamily: AddressFamily,
    val negotiatedProtocol: String?,
    val connectionState: ConnectionState,
    val proxyUsed: Boolean,
    val phases: PhaseTimings?,
    val serverProcessingMillis: Double?,
    val serverRegionId: String?,
    val bytesSent: Long,
    val bytesReceived: Long,
    val engineElapsedMillis: Double?
)
