package com.aeriva.core.model.measurement

/**
 * The controlled method-identifier vocabulary. Per
 * PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D3-7:
 * a measurement's `method` field must name what was actually timed
 * (PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md Section 7.1's own requirement)
 * instead of the free-text `"tcp-round-trip"` default the Phase 3B
 * engine currently ships, which this registry retires as a value new
 * code should produce.
 *
 * [ALL] and [isRegistered] exist so a test can assert "every measurement
 * this build can currently produce uses a value from this list" without
 * hand-maintaining a duplicate list at the call site. Wiring the Phase
 * 3B engine itself to use these constants instead of its current
 * free-text default is deferred to the slice that touches the engine
 * (see the accompanying implementation notes) -- this registry is the
 * target that slice migrates to, added first so the failure-taxonomy
 * and evidence work in this change do not have to guess its shape.
 */
object MeasurementMethod {

    /** One request/response on an already-established HTTPS/1.1 connection. Excludes DNS, TCP, TLS. */
    const val HTTPS_H1_WARM_EXCHANGE = "https-h1-warm-exchange"

    /** DNS, TCP, TLS, request and response on a new connection. Phases also recorded. */
    const val HTTPS_H1_COLD_TOTAL = "https-h1-cold-total"

    /** Full-stack reachability judgment against the production measurement endpoint. */
    const val HTTPS_REACHABILITY = "https-reachability"

    /** Sequenced UDP echo probes with a fixed window. */
    const val UDP_ECHO_TRAIN = "udp-echo-train"

    /** Timed, byte-capped download. */
    const val HTTPS_DOWNLOAD_STREAM = "https-download-stream"

    /** Timed, byte-capped upload. */
    const val HTTPS_UPLOAD_STREAM = "https-upload-stream"

    val ALL: Set<String> = setOf(
        HTTPS_H1_WARM_EXCHANGE,
        HTTPS_H1_COLD_TOTAL,
        HTTPS_REACHABILITY,
        UDP_ECHO_TRAIN,
        HTTPS_DOWNLOAD_STREAM,
        HTTPS_UPLOAD_STREAM
    )

    fun isRegistered(method: String): Boolean = method in ALL
}
