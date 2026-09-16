package com.aeriva.core.model.measurement

/**
 * Phase 3A skeleton type -- see PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md
 * Section 12. Covers only the measurement-tier failure modes that
 * table assigns here specifically -- NOT permission/unsupported-capability
 * failures (those stay [com.aeriva.core.result.AerivaError], reused
 * rather than duplicated) and NOT "unavailable network" (that is an
 * observation-tier fact, [com.aeriva.core.model.NetworkState.available],
 * not a measurement failure).
 *
 * Deliberately closed (sealed, no open/unknown case) so a caller must
 * handle every kind this design has actually named -- add a case only
 * when a real measurement method needs to distinguish it, per this
 * codebase's existing AerivaError doc-comment convention.
 */
sealed interface MeasurementFailure {
    data object Timeout : MeasurementFailure
    data object Cancelled : MeasurementFailure
    data class EndpointFailure(val reason: String) : MeasurementFailure
    data class TlsFailure(val reason: String) : MeasurementFailure
    data class InvalidResponse(val reason: String) : MeasurementFailure
    data object NetworkChangedDuringMeasurement : MeasurementFailure
}
