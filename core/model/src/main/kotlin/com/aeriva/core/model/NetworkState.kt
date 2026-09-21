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
 *
 * [vpnPresent] and [captivePortalReported] were added in Phase 4
 * (PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md Decision D4-8).
 * [vpnPresent] exists separately from [transport] specifically so a VPN
 * riding over Wi-Fi is no longer invisible to logic that needs the
 * underlying-transport question answered honestly -- [transport] still
 * reports `VPN` for display (the transport the user's traffic actually
 * observes), but a caller that needs to know "is a VPN involved at all,
 * regardless of what transport is shown" now has a direct answer instead
 * of having to special-case `TransportType.VPN`.
 *
 * [blockedByDevicePolicy] reflects the platform's own blocked-status
 * callback (`NetworkCallback.onBlockedStatusChanged`, confirmed to exist
 * per PHASE_4_REAL_DEVICE_VALIDATION_PLAN.md's own research) rather than
 * being inferred from anything else. All three new fields are typed as
 * plain, non-nullable [Boolean] -- per Decision D4-8 they are reliably
 * observable from the platform's own capability and callback bits, so
 * there is no uncertain "cannot tell" state to represent for them, only
 * a value the mapper must actually compute rather than default.
 */
data class NetworkState(
    val transport: TransportType,
    val available: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val capabilities: Set<String>,
    val estimatedQuality: NetworkQuality,
    val diagnosticsStatus: DiagnosticsStatus,
    val lastChangedAt: Instant,
    val captivePortalReported: Boolean,
    val vpnPresent: Boolean,
    val blockedByDevicePolicy: Boolean
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
            lastChangedAt = at,
            captivePortalReported = false,
            vpnPresent = false,
            blockedByDevicePolicy = false
        )
    }
}
