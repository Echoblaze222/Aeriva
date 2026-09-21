package com.aeriva.network.monitor.measurement

/**
 * A probe's opaque target, resolved from [MeasurementEndpointConfig] --
 * never a raw string a caller supplies directly. The
 * [com.aeriva.network.monitor.measurement.NetworkClient] KDoc already
 * refuses to hardcode a host; this type is the seam that keeps that
 * true once a real endpoint exists (Decision D2-4).
 */
data class ProbeTarget(
    val host: String,
    val port: Int,
    val protocolVersion: Int
)

/**
 * Compile-time endpoint configuration for the production measurement
 * endpoint (PME). Per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D2-4:
 * endpoint identity is compile-time configuration behind this seam, not
 * remote-configured -- changing where a hostname points is an
 * infrastructure action, not an app release (and a remote-configured
 * endpoint list is itself an injection vector for a measurement app,
 * Decision D2-9's rejected-alternatives reasoning).
 *
 * [current] returns `null` until an owner-approved [ProbeTarget] exists
 * (OD-2, OD-3, OD-4 in the decision contract). This is deliberate: this
 * change does not invent a production endpoint URL or host (an explicit
 * stop condition for this implementation task), so [current] stays
 * unconfigured, and any code path that needs a target must treat `null`
 * as a real, expected state. Decision D2-6 requires that with no
 * configuration, the engine declines cleanly with a dedicated
 * `NoEndpointConfigured` reason -- adding that decline case to the
 * engine's own outcome type is engine-slice work this change does not
 * do; this seam exists now so that slice has something real to consume.
 */
object MeasurementEndpointConfig {

    /**
     * The current production measurement endpoint, or `null` if none is
     * configured yet. Always `null` in this change -- see this object's
     * own KDoc.
     */
    val current: ProbeTarget? = null
}
