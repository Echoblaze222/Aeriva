package com.aeriva.core.model.measurement

/**
 * Which DNS-resolution failure occurred. [NOT_RESOLVED] deliberately
 * cannot separate NXDOMAIN from a resolver-level failure -- that
 * distinction is not available at this API level (Decision D5-4).
 */
enum class DnsFailureKind { NOT_RESOLVED, CLIENT_TIMED_OUT, UNKNOWN }

/** Kind refinement for [MeasurementFailure.EndpointFailure]. [UNSPECIFIED] is the safe default: message-parsing a platform exception for a more specific kind is prohibited as brittle (Decision D5-7). */
enum class EndpointFailureKind { REFUSED, UNREACHABLE, RESET, UNSPECIFIED }

/** Kind refinement for [MeasurementFailure.TlsFailure]. */
enum class TlsFailureKind { CERTIFICATE_INVALID, HOSTNAME_MISMATCH, PROTOCOL_OR_CIPHER, UNSPECIFIED }

/** Kind refinement for [MeasurementFailure.InvalidResponse]. */
enum class InvalidResponseKind { WRONG_LENGTH, NONCE_MISMATCH, MALFORMED, UNEXPECTED_STATUS, TOO_LARGE, UNSPECIFIED }

/**
 * Which interception-like signal(s) supported a
 * [MeasurementFailure.CaptivePortalSuspected] classification, plus
 * confirmation that the platform's own captive-portal capability flag
 * was also set. Per Decision D5-5, a portal is only ever "suspected" --
 * never "detected" -- and only when both an interception signal and the
 * platform flag held at the same time; this type records which signal
 * it was, both for validation (PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md
 * Section 6.2) and so the classification itself is auditable, not a
 * bare boolean guess.
 */
data class CaptivePortalEvidence(
    val interceptionSignal: String,
    val platformFlagWasSet: Boolean
)

/**
 * Phase 3A skeleton type, extended for Phase 4 per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D5-4 --
 * see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md Section 12 for why
 * this stays deliberately closed (sealed, no open/unknown escape hatch)
 * and separate from [com.aeriva.core.result.AerivaError] (permission
 * and unsupported-capability failures) and from "no network"
 * (an observation-tier fact, not a measurement failure).
 *
 * [Unclassified] is the one case whose entire purpose is to be that
 * escape hatch, and it is intentionally loud about it: it always
 * signals a defect (an exception no rule in
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D5-7
 * mapped), never a normal outcome, and its validation-plan target rate
 * is zero (Decision D5-11 consequences). It is not a general-purpose
 * "anything else" bucket to reach for casually.
 *
 * [Timeout] gained a [MeasurementStage] parameter in Phase 4 (Decision
 * D5-3) so a caller can tell which stage never made progress, instead
 * of one undifferentiated timeout for every cause.
 */
sealed interface MeasurementFailure {

    data class Timeout(val stage: MeasurementStage) : MeasurementFailure

    /** Caller cancelled. Recorded by an orchestrator, never constructed by the engine itself (Decision D5-2). */
    data object Cancelled : MeasurementFailure

    data class DnsFailure(val kind: DnsFailureKind) : MeasurementFailure

    data class EndpointFailure(
        val reason: String,
        val kind: EndpointFailureKind = EndpointFailureKind.UNSPECIFIED
    ) : MeasurementFailure

    data class TlsFailure(
        val reason: String,
        val kind: TlsFailureKind = TlsFailureKind.UNSPECIFIED
    ) : MeasurementFailure

    /** Any 3xx response from a resource that must never redirect (Decision D2-3 requirement R7) -- itself a possible interception signal, see [CaptivePortalSuspected]. */
    data class UnexpectedRedirect(val statusCode: Int) : MeasurementFailure

    data class InvalidResponse(
        val reason: String,
        val kind: InvalidResponseKind = InvalidResponseKind.UNSPECIFIED
    ) : MeasurementFailure

    /**
     * Produced only by an interpretation step (Decision D5-1's L3), never
     * directly by a client or engine -- see [CaptivePortalEvidence] and
     * Decision D5-5's two-signal rule.
     */
    data class CaptivePortalSuspected(val evidence: CaptivePortalEvidence) : MeasurementFailure

    /** A probe or train received nothing. Ambiguous between loss, filtering and endpoint-down -- never itself a loss claim (Decision D4-6). */
    data class NoResponseFromEndpoint(val protocol: String) : MeasurementFailure

    /** The platform reported the network blocked for this app during the probe (Decision D5-2). */
    data object BlockedByDevicePolicy : MeasurementFailure

    data object NetworkChangedDuringMeasurement : MeasurementFailure

    /**
     * An exception no rule in Decision D5-7 mapped. Carries only the
     * exception class's simple name -- never its message, which may
     * contain untrusted server- or platform-supplied text. Always a
     * defect signal; see this type's own KDoc above.
     */
    data class Unclassified(val exceptionClass: String) : MeasurementFailure
}
