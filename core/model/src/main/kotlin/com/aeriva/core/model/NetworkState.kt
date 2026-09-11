package com.aeriva.core.model

import java.time.Instant

/**
 * Normalized AERIVA network state, per architecture doc Section 6. This
 * is the single representation every layer above the platform network
 * monitor consumes -- no feature should read android.net APIs directly
 * (architecture doc Section 5: "The UI must not directly control ...
 * low-level Android network APIs").
 *
 * [capabilities] carries only the raw capability names Android actually
 * reported, for diagnostics/debugging visibility. It is not a general
 * capability query API -- if a feature needs a specific capability to
 * drive behavior, give it its own named field instead of parsing this set.
 */
data class NetworkState(
    val transport: TransportType,
    val available: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val capabilities: Set<String>,
    val estimatedQuality: NetworkQuality,
    val diagnosticsStatus: DiagnosticsStatus,
    val lastChangedAt: Instant
) {
    companion object {
        /** State before the monitor has produced its first real reading. */
        fun unknown(at: Instant): NetworkState = NetworkState(
            transport = TransportType.NONE,
            available = false,
            validated = false,
            metered = false,
            capabilities = emptySet(),
            estimatedQuality = NetworkQuality.Unavailable,
            diagnosticsStatus = DiagnosticsStatus.NotAvailable,
            lastChangedAt = at
        )
    }
}
