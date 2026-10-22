package com.aeriva.core.model.measurement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the Phase 4 extended [MeasurementFailure] taxonomy
 * (PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D5-4)
 * still compiles as a closed, exhaustive `when` with no `else` branch --
 * the same discipline [MeasurementBoundaryTest] already established for
 * the Phase 3A set, now covering every new case. If a future change
 * added an unhandled case, this file fails to compile, not silently
 * passes.
 */
class MeasurementFailureTaxonomyTest {

    @Test
    fun everyCase_isDistinctAndExhaustivelyHandled() {
        val cases: List<MeasurementFailure> = listOf(
            MeasurementFailure.Timeout(MeasurementStage.Connect),
            MeasurementFailure.Cancelled,
            MeasurementFailure.DnsFailure(DnsFailureKind.NOT_RESOLVED),
            MeasurementFailure.EndpointFailure("refused"),
            MeasurementFailure.EndpointFailure("refused", EndpointFailureKind.REFUSED),
            MeasurementFailure.TlsFailure("bad cert"),
            MeasurementFailure.TlsFailure("bad cert", TlsFailureKind.CERTIFICATE_INVALID),
            MeasurementFailure.UnexpectedRedirect(302),
            MeasurementFailure.InvalidResponse("wrong length"),
            MeasurementFailure.InvalidResponse("nonce", InvalidResponseKind.NONCE_MISMATCH),
            MeasurementFailure.CaptivePortalSuspected(
                CaptivePortalEvidence(interceptionSignal = "UnexpectedRedirect", platformFlagWasSet = true)
            ),
            MeasurementFailure.NoResponseFromEndpoint("udp"),
            MeasurementFailure.BlockedByDevicePolicy,
            MeasurementFailure.NetworkChangedDuringMeasurement,
            MeasurementFailure.Unclassified("IllegalStateException")
        )

        val described = cases.map(::describe)

        assertEquals(cases.size, described.distinct().size)
    }

    private fun describe(failure: MeasurementFailure): String = when (failure) {
        is MeasurementFailure.Timeout -> "timeout:${failure.stage}"
        is MeasurementFailure.Cancelled -> "cancelled"
        is MeasurementFailure.DnsFailure -> "dns:${failure.kind}"
        is MeasurementFailure.EndpointFailure -> "endpoint:${failure.reason}:${failure.kind}"
        is MeasurementFailure.TlsFailure -> "tls:${failure.reason}:${failure.kind}"
        is MeasurementFailure.UnexpectedRedirect -> "redirect:${failure.statusCode}"
        is MeasurementFailure.InvalidResponse -> "invalid:${failure.reason}:${failure.kind}"
        is MeasurementFailure.CaptivePortalSuspected -> "portal:${failure.evidence.interceptionSignal}"
        is MeasurementFailure.NoResponseFromEndpoint -> "noresponse:${failure.protocol}"
        is MeasurementFailure.BlockedByDevicePolicy -> "blocked"
        is MeasurementFailure.NetworkChangedDuringMeasurement -> "networkchanged"
        is MeasurementFailure.Unclassified -> "unclassified:${failure.exceptionClass}"
    }

    @Test
    fun timeout_carriesItsStage_distinctFromOtherStages() {
        assertEquals(
            MeasurementStage.Dns,
            (MeasurementFailure.Timeout(MeasurementStage.Dns) as MeasurementFailure.Timeout).stage
        )
    }

    @Test
    fun endpointFailure_kindDefaultsToUnspecified_whenNotGiven() {
        val failure = MeasurementFailure.EndpointFailure("refused")
        assertEquals(EndpointFailureKind.UNSPECIFIED, failure.kind)
    }

    @Test
    fun unclassified_carriesOnlyTheExceptionClassName_neverAMessage() {
        val failure = MeasurementFailure.Unclassified("NullPointerException")
        assertEquals("NullPointerException", failure.exceptionClass)
    }
}
