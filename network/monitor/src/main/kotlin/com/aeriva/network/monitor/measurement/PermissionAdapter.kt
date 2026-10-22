package com.aeriva.network.monitor.measurement

/**
 * One permission's state, as the platform can currently answer it.
 * Deliberately not a Boolean -- an approximate-only location grant
 * (Android 12+) and a platform-query failure are both real, distinct
 * states a bare Boolean cannot carry. Sealed, no open/unknown case,
 * following this codebase's established convention
 * (`MeasurementFailure`, `CapabilityClassification`, `NetworkClientOutcome`
 * are the same shape) so a future addition fails to compile at every
 * exhaustive `when` over this type rather than silently falling through.
 *
 * Per `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` Section 6.2.
 */
sealed interface PermissionState {

    /** Held right now -- a normal permission (always this, once declared)
     *  or a runtime permission the user has actually granted. */
    data object Granted : PermissionState

    /** Not held right now. Denial and "never requested" are deliberately
     *  not distinguished here -- both mean the permission is not
     *  currently held, which is all a caller deciding whether a
     *  capability is usable needs to know. */
    data object Denied : PermissionState

    /** `ACCESS_FINE_LOCATION` specifically, on a device where the user
     *  has granted only `ACCESS_COARSE_LOCATION`. Distinct from [Denied]
     *  so the information is not discarded even though nothing today
     *  consumes the distinction -- [MeasurementCapabilityClassifier]'s
     *  current table has no capability satisfied by coarse location
     *  alone, so this state currently collapses to "not sufficient"
     *  wherever it feeds into [buildGrantedPermissionSet]. */
    data object GrantedApproximateOnly : PermissionState

    /** The platform could not answer the query itself -- a
     *  `PackageManager` query failure, never silently treated as
     *  [Denied], since that would misreport a platform/query problem as
     *  a user decision. [reason] is the failing exception's class name
     *  only, never a raw message, matching this codebase's existing
     *  `MeasurementFailure.Unclassified` sanitization convention. */
    data class CheckFailed(val reason: String) : PermissionState
}

/**
 * The Android-edge seam that reports whether a given manifest permission
 * is currently held. Deliberately capability-agnostic: this interface
 * knows about Android permission strings and SDK levels, nothing about
 * [com.aeriva.network.monitor.MeasurementCapability] --
 * [MeasurementCapabilityClassifier] remains the only place that
 * interprets permission facts together with other platform/network
 * observations to decide what AERIVA can measure. An adapter that took
 * a `MeasurementCapability` and returned a classification would
 * duplicate the classifier's own per-capability logic in a second
 * place; this interface does not do that.
 *
 * No Android type appears in this interface's own signature --
 * [AndroidPermissionAdapter] holds a `Context` internally, but nothing
 * here leaks that type outward, preserving the same no-Android-import
 * boundary [MeasurementCapabilityClassifier] and `core:model` already
 * keep.
 *
 * Per `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` Section 6.2.
 */
interface PermissionAdapter {

    /**
     * @param permission a manifest permission string (for example
     *   `"android.permission.ACCESS_FINE_LOCATION"`). Never a
     *   `MeasurementCapability` -- see this interface's own KDoc.
     *
     * Always re-checks the live platform state -- an implementation
     * must never return a cached or memoized value from an earlier
     * call. Runtime permission grants are not stable for the life of a
     * process: auto-reset can revoke a grant in the background, and the
     * user can revoke one at any time via system settings, so a
     * snapshot taken once at construction or at app startup would
     * silently go stale. A caller that wants to avoid repeated checks
     * within one measurement attempt is responsible for calling this
     * once per attempt and holding the result only for that attempt's
     * duration, not across attempts.
     */
    fun check(permission: String): PermissionState

    /** The running device's `Build.VERSION.SDK_INT`, as a plain `Int` --
     *  the one piece of Android-derived data
     *  [MeasurementCapabilityClassifier] already accepts directly (its
     *  own `sdkInt` parameter), so this lives on the same adapter
     *  rather than requiring a second seam for it. */
    fun currentSdkInt(): Int
}

/**
 * Maps this adapter's richer per-permission states down to the plain
 * `Set<String>` [MeasurementCapabilityClassifier.classify] already
 * accepts -- the classifier's own signature is not changed by this
 * seam. [PermissionState.Granted] and [PermissionState.GrantedApproximateOnly]
 * for `ACCESS_COARSE_LOCATION` would both count as "coarse location
 * held" if a future capability ever needed that; today, only
 * `ACCESS_FINE_LOCATION` is checked, so [PermissionState.GrantedApproximateOnly]
 * for it is correctly excluded -- the classifier's own table has no
 * case where approximate location is sufficient.
 *
 * [PermissionState.CheckFailed] is deliberately excluded from the
 * granted set (a query failure must never be silently treated as a
 * grant) and is also deliberately not surfaced as an exception from
 * this function -- a caller that needs to distinguish "not held" from
 * "could not be checked" should call [PermissionAdapter.check] directly
 * per permission instead; this function exists specifically to feed
 * the classifier's existing binary contract, which has no slot for a
 * third state.
 *
 * Per `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` Section 6.5.
 */
fun buildGrantedPermissionSet(
    adapter: PermissionAdapter,
    permissionsToCheck: Set<String>
): Set<String> = permissionsToCheck.filter { permission ->
    adapter.check(permission) == PermissionState.Granted
}.toSet()
