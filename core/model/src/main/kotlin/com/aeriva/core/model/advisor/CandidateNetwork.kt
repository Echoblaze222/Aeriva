package com.aeriva.core.model.advisor

import com.aeriva.core.model.TransportType
import java.time.Instant

/**
 * Opaque, process-local identity of one network the device can currently
 * use. Never persisted, never uploaded, and never logged beyond this value
 * (NETWORK_ADVISOR_PHASE_A_SPEC.md sections 5.1 and 5.6). The Android
 * adapter derives it from the platform network handle; nothing in this
 * module knows how, and nothing may depend on what [value] means.
 */
@JvmInline
value class CandidateNetworkId(val value: Long)

/**
 * One network the device can currently use, described only by facts the
 * platform reports without any permission beyond ACCESS_NETWORK_STATE.
 *
 * No `android.net` type appears here. The boolean fields have the same
 * meaning as in [com.aeriva.core.model.NetworkState]. [transport] is the
 * display transport with the same priority rule as the existing mapper
 * (VPN first), and [vpnPresent] answers "is this network a VPN" directly.
 *
 * Deliberately not included: SSID, BSSID, carrier name, cell id, signal
 * strength, location. Reading them needs location permission, which the
 * advisor must not request. Two candidates of the same transport (dual SIM,
 * two Wi-Fi networks) are distinguished only by [id].
 *
 * [observedAt] is when the reducer last applied an event for this network.
 */
data class CandidateNetwork(
    val id: CandidateNetworkId,
    val transport: TransportType,
    val validated: Boolean,
    val metered: Boolean,
    val captivePortalReported: Boolean,
    val blockedByDevicePolicy: Boolean,
    val vpnPresent: Boolean,
    val isDefault: Boolean,
    val observedAt: Instant
)
