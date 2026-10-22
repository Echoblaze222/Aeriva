package com.aeriva.core.model.measurement

/**
 * Which stage of a probe had not made progress when a deadline fired, or
 * -- for [Unknown] -- when a probe failed at no identifiable stage. Per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D5-3.
 *
 * A production client is expected to report the real stage it was in
 * (own-deadline timeouts, Decision D3-4). [Unknown] is reserved for the
 * engine's own outer backstop timeout (Decision D5-10): if that fires,
 * the client's own stage tracking did not, which is itself a defect
 * signal worth keeping distinguishable from an honestly-attributed
 * client-side stage timeout.
 */
enum class MeasurementStage {
    PreFlight,
    Dns,
    Connect,
    Tls,
    Request,
    Response,
    Unknown
}
