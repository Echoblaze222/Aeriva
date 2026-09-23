package com.aeriva.network.monitor.measurement

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The real [PermissionAdapter] implementation. Holds an application
 * `Context` (matching [com.aeriva.network.monitor.AndroidNetworkMonitor]'s
 * own existing constructor pattern) and calls
 * [ContextCompat.checkSelfPermission] -- the AndroidX-compat entry
 * point, safe across this module's `minSdk 26`. `androidx.core` (via
 * `core-ktx`, already a version-pinned, in-use project dependency --
 * see `network:monitor/build.gradle.kts`) is what makes `ContextCompat`
 * available here.
 *
 * [checkPermission] is an injectable seam over the actual platform
 * query, following this codebase's established clock-seam convention
 * (compare [com.aeriva.network.monitor.measurement.LatencyMeasurementEngine]'s
 * `elapsedNanos: () -> Long = System::nanoTime`) -- it exists so
 * [PermissionState.CheckFailed] behavior is unit-testable on the plain
 * JVM (a fake seam that throws) without needing Robolectric or a mocking
 * library, neither of which this repository currently depends on.
 *
 * Per `PHASE_4_ANDROID_PERMISSION_PLATFORM_SPEC.md` Section 7.
 *
 * @param context required for the real, default [checkPermission] seam;
 *   nullable so a JVM unit test can construct this class with a fake
 *   [checkPermission] and no real `Context` at all (see
 *   [AndroidPermissionAdapterTest]). Every real call site supplies a
 *   real, non-null `Context` and never overrides [checkPermission].
 */
class AndroidPermissionAdapter(
    context: Context?,
    private val checkPermission: (String) -> Int = { permission ->
        val realContext = checkNotNull(context) {
            "AndroidPermissionAdapter needs a real Context unless checkPermission is overridden"
        }
        ContextCompat.checkSelfPermission(realContext, permission)
    },
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : PermissionAdapter {

    /**
     * Never returns a cached value -- delegates to [checkPermission] on
     * every call, which for the real seam always reaches the live
     * platform state. Never lets a [checkPermission] failure propagate
     * as an uncaught exception: this would crash a measurement attempt
     * over a platform-query problem, not a real permission or network
     * condition, which is exactly the class of failure this codebase's
     * "never throws" measurement-layer convention already establishes
     * as the wrong shape. [RuntimeException] is caught specifically
     * (not [Throwable]) so a genuine platform error such as
     * `OutOfMemoryError` is not silently swallowed as a permission
     * check result.
     */
    override fun check(permission: String): PermissionState = try {
        if (permission == PERMISSION_ACCESS_FINE_LOCATION) {
            checkFineLocation()
        } else {
            toState(checkPermission(permission))
        }
    } catch (exception: RuntimeException) {
        PermissionState.CheckFailed(exception::class.java.simpleName)
    }

    override fun currentSdkInt(): Int = sdkInt

    /**
     * `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are two
     * independent booleans on every API level this module supports
     * (`minSdk 26` -- runtime permissions, and the ability to hold one
     * location permission without the other, have both applied since
     * API 23; Android 12/API 31 changed only the *request* flow, which
     * now must ask for both together -- it did not change what
     * `checkSelfPermission` reports for each permission independently).
     * So this check needs no `Build.VERSION.SDK_INT` branch to be
     * correct across this module's whole supported range: on every
     * version from 26 up, a user who denied fine but granted coarse
     * produces exactly this pair of results, and a user who granted
     * neither, or granted fine, does too. [currentSdkInt] still exists
     * on the adapter (for [MeasurementCapabilityClassifier]'s own
     * `sdkInt` parameter), but this specific check does not need to
     * consult it.
     */
    private fun checkFineLocation(): PermissionState {
        val fine = checkPermission(PERMISSION_ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) return PermissionState.Granted

        val coarse = checkPermission(PERMISSION_ACCESS_COARSE_LOCATION)
        return if (coarse == PackageManager.PERMISSION_GRANTED) {
            PermissionState.GrantedApproximateOnly
        } else {
            PermissionState.Denied
        }
    }

    private fun toState(result: Int): PermissionState =
        if (result == PackageManager.PERMISSION_GRANTED) PermissionState.Granted else PermissionState.Denied

    private companion object {
        // Defined locally rather than imported from
        // MeasurementCapabilityClassifier's own permission constants --
        // this class stays a standalone, capability-agnostic seam with
        // no dependency on the classifier at all, matching this seam's
        // own KDoc.
        const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val PERMISSION_ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    }
}
