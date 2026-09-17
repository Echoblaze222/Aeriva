package com.aeriva.network.monitor

/**
 * One thing PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md asks AERIVA
 * to determine per capability. Deliberately small and closed (no
 * "unknown"/open case) -- add a case only when a real capability needs
 * distinguishing, per this codebase's existing sealed-type convention
 * ([com.aeriva.core.model.measurement.MeasurementFailure] is the
 * precedent).
 */
enum class MeasurementCapability {
    LATENCY,
    JITTER,
    PACKET_LOSS,
    THROUGHPUT,
    NETWORK_STABILITY,
    NETWORK_TRANSITIONS,
    WIFI_CHARACTERISTICS,
    CELLULAR_CHARACTERISTICS,
    DNS_RESPONSIVENESS,
    HTTPS_REACHABILITY
}

/**
 * PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md's four-way
 * classification, as a closed type rather than a free-text label -- so a
 * caller cannot silently invent a fifth category, and so a `when` over
 * this type is exhaustive and the compiler catches a missed case.
 * [reason] is required on every non-[Supported] case, per this
 * codebase's existing convention of never returning an unexplained
 * negative result (see [com.aeriva.core.model.measurement.MeasurementFailure]
 * and [com.aeriva.core.result.AerivaError]).
 */
sealed interface CapabilityClassification {
    data object Supported : CapabilityClassification
    data class SupportedWithLimitations(val reason: String) : CapabilityClassification
    data class Estimated(val reason: String) : CapabilityClassification
    data class NotReliablyAvailable(val reason: String) : CapabilityClassification
}

/**
 * Pure mapping from (SDK level, granted permissions) to a
 * [CapabilityClassification] per [MeasurementCapability]. No Android
 * import in this file, matching [NetworkStateMapper]'s existing
 * convention -- `android.os.Build.VERSION.SDK_INT` and
 * `Context.checkSelfPermission` results are read by a caller at the
 * Android-dependent edge and passed in as plain [Int]/[Set] values, so
 * this logic is unit-testable on the plain JVM without instrumentation.
 *
 * This is a **classification of what the platform permits**, per
 * PHASE_2_ANDROID_PLATFORM_AUDIT.md's and PHASE_3B's own findings -- it
 * is not itself a measurement, does not open a socket, does not read
 * Wi-Fi/cellular state, and does not decide *whether* AERIVA should ask
 * for a given permission (that remains the individually-justified,
 * per-feature decision PHASE_2_ANDROID_PLATFORM_AUDIT.md Section 10
 * requires). Callers use this to answer "what could AERIVA do right now,
 * given what it currently holds," not "what should AERIVA request next."
 */
object MeasurementCapabilityClassifier {

    const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    const val PERMISSION_ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    const val PERMISSION_INTERNET = "android.permission.INTERNET"

    /**
     * @param sdkInt the running device's `Build.VERSION.SDK_INT`.
     * @param grantedPermissions the manifest permission strings AERIVA
     *   currently holds (normal permissions it has requested plus any
     *   runtime permission actually granted by the user) -- not every
     *   permission declared in the manifest, since a declared runtime
     *   permission the user denied must classify the same as not
     *   declared at all.
     */
    fun classify(
        capability: MeasurementCapability,
        sdkInt: Int,
        grantedPermissions: Set<String>
    ): CapabilityClassification = when (capability) {
        // Socket-level active probes: not a privileged Android API
        // (PHASE_2_ANDROID_PLATFORM_AUDIT.md Section 7 / this report's
        // API analysis) -- gated only by the normal INTERNET permission,
        // not by anything version- or location-dependent.
        MeasurementCapability.LATENCY,
        MeasurementCapability.JITTER,
        MeasurementCapability.HTTPS_REACHABILITY ->
            if (PERMISSION_INTERNET in grantedPermissions) {
                CapabilityClassification.Supported
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "INTERNET permission not held -- not yet declared in this repository's manifest"
                )
            }

        // Fixed in the Phase 3B contract review
        // (PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md,
        // Task 10): this branch previously classified DNS responsiveness
        // as Supported alongside latency/jitter/HTTPS reachability. That
        // was a real inconsistency, not a documentation-only nuance --
        // PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md's own
        // capability table (row 9) and this task's own explicit
        // instruction ("Do not label an estimate as a direct
        // measurement") both require DNS timing to sit in the
        // ESTIMATION tier this codebase's domain model already
        // distinguishes at the type level
        // (`core:model`'s measurement package, Phase 3A), not the
        // MEASUREMENT tier `Supported` represents here. A timed
        // `InetAddress`/socket-level DNS resolution is confounded by
        // OS-level, carrier-level, and resolver-level caching -- see
        // `LinkProperties.getDnsServers()`/`isPrivateDnsActive()`
        // (current official reference docs), which expose *which*
        // resolver is configured (an observation) but nothing about that
        // resolver's live responsiveness, so a timed lookup is
        // necessarily an indirect proxy, not a controlled measurement of
        // the resolver itself.
        MeasurementCapability.DNS_RESPONSIVENESS ->
            if (PERMISSION_INTERNET in grantedPermissions) {
                CapabilityClassification.Estimated(
                    "a timed lookup is confounded by OS/carrier/resolver DNS caching -- an indirect proxy for responsiveness, not a controlled measurement of it"
                )
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "INTERNET permission not held -- not yet declared in this repository's manifest"
                )
            }

        // Same socket-level foundation as latency/jitter, but this
        // report's own findings (Section: Packet loss) note that
        // distinguishing "lost" from "slow" without root/raw sockets is
        // inherently imprecise, and a throughput probe's data/battery
        // cost is categorically higher than a single round-trip -- both
        // real platform-permission facts (INTERNET) plus a
        // measurement-fidelity/cost caveat this report requires stating,
        // not merely an API gate.
        MeasurementCapability.PACKET_LOSS ->
            if (PERMISSION_INTERNET in grantedPermissions) {
                CapabilityClassification.SupportedWithLimitations(
                    "no root/raw sockets on stock Android -- distinguishing a lost packet from a slow one is inherently imprecise"
                )
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "INTERNET permission not held -- not yet declared in this repository's manifest"
                )
            }

        MeasurementCapability.THROUGHPUT ->
            if (PERMISSION_INTERNET in grantedPermissions) {
                CapabilityClassification.SupportedWithLimitations(
                    "a real transfer moves real data -- must default to infrequent, user-consented, Wi-Fi/unmetered-aware"
                )
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "INTERNET permission not held -- not yet declared in this repository's manifest"
                )
            }

        // Already-implemented observation tier (AndroidNetworkMonitor /
        // ConnectivityManager.registerDefaultNetworkCallback) -- gated
        // only by the already-declared, normal ACCESS_NETWORK_STATE
        // permission, present since minSdk 26.
        MeasurementCapability.NETWORK_STABILITY,
        MeasurementCapability.NETWORK_TRANSITIONS ->
            if (PERMISSION_ACCESS_NETWORK_STATE in grantedPermissions) {
                CapabilityClassification.Supported
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "ACCESS_NETWORK_STATE permission not held"
                )
            }

        // WifiManager.getConnectionInfo() -- fine location required at
        // every currently-supported API level (this report's API
        // analysis; PHASE_2_ANDROID_PLATFORM_AUDIT.md Section 3). Real
        // Wi-Fi radio behavior is real-device-only regardless of
        // permission state (PHASE_2_ANDROID_PLATFORM_AUDIT.md Section
        // 13) -- this classifier states the permission boundary only,
        // not real-device fidelity, which is not classifiable from
        // (sdkInt, permissions) alone.
        MeasurementCapability.WIFI_CHARACTERISTICS ->
            if (PERMISSION_ACCESS_FINE_LOCATION in grantedPermissions) {
                CapabilityClassification.SupportedWithLimitations(
                    "requires device location services enabled in addition to the permission; real SSID/RSSI behavior is real-device-only"
                )
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "requires ACCESS_FINE_LOCATION, not currently requested (PHASE_2_ANDROID_PLATFORM_AUDIT.md Section 10)"
                )
            }

        // TelephonyManager.getAllCellInfo() / TelephonyCallback --
        // requires BOTH READ_PHONE_STATE and ACCESS_FINE_LOCATION per
        // this report's own re-verification against current official
        // reference docs (TelephonyCallback.CellInfoListener), which is
        // a stricter requirement than PHASE_2_ANDROID_PLATFORM_AUDIT.md
        // Section 3 stated (fine location alone). On API 29+, a plain
        // getAllCellInfo() call may return a cached rather than live
        // result -- a fidelity caveat, not a permission gate, so it
        // does not change the classification below by itself.
        MeasurementCapability.CELLULAR_CHARACTERISTICS ->
            if (PERMISSION_READ_PHONE_STATE in grantedPermissions &&
                PERMISSION_ACCESS_FINE_LOCATION in grantedPermissions
            ) {
                CapabilityClassification.SupportedWithLimitations(
                    "real signal values are real-device/OEM-dependent; API 29+ getAllCellInfo() without requestCellInfoUpdate() may return a cached result"
                )
            } else {
                CapabilityClassification.NotReliablyAvailable(
                    "requires both READ_PHONE_STATE and ACCESS_FINE_LOCATION, neither currently requested"
                )
            }
    }
}
