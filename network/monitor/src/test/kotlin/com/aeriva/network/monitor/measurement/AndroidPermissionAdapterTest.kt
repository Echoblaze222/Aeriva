package com.aeriva.network.monitor.measurement

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
private const val NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"

/**
 * Exercises [AndroidPermissionAdapter]'s own decision logic on the
 * plain JVM via its injectable [AndroidPermissionAdapter.checkPermission]
 * seam -- no real `Context`, no Robolectric, no mocking library (this
 * repository has neither as a dependency). Confirms the platform-facing
 * behavior the corrected specification requires; it does not exercise
 * `ContextCompat.checkSelfPermission` itself, which
 * [AndroidPermissionAdapterInstrumentedTest] covers on a real device.
 */
class AndroidPermissionAdapterTest {

    private fun adapter(
        sdkInt: Int = 34,
        checkPermission: (String) -> Int
    ) = AndroidPermissionAdapter(context = null, checkPermission = checkPermission, sdkInt = sdkInt)

    // -- 1: granted --------------------------------------------------

    @Test
    fun normalOrGrantedRuntimePermission_returnsGranted() {
        val adapter = adapter { PackageManager.PERMISSION_GRANTED }

        assertEquals(PermissionState.Granted, adapter.check(NETWORK_STATE))
    }

    // -- 2: denied ----------------------------------------------------

    @Test
    fun deniedPermission_returnsDenied() {
        val adapter = adapter { PackageManager.PERMISSION_DENIED }

        assertEquals(PermissionState.Denied, adapter.check(NETWORK_STATE))
    }

    // -- 3: approximate-location-only ----------------------------------

    @Test
    fun fineLocation_deniedButCoarseGranted_returnsGrantedApproximateOnly() {
        val adapter = adapter { permission ->
            when (permission) {
                FINE -> PackageManager.PERMISSION_DENIED
                COARSE -> PackageManager.PERMISSION_GRANTED
                else -> error("unexpected permission $permission")
            }
        }

        assertEquals(PermissionState.GrantedApproximateOnly, adapter.check(FINE))
    }

    @Test
    fun fineLocation_granted_returnsGranted_withoutCheckingCoarseAtAll() {
        var coarseWasChecked = false
        val adapter = adapter { permission ->
            when (permission) {
                FINE -> PackageManager.PERMISSION_GRANTED
                COARSE -> {
                    coarseWasChecked = true
                    PackageManager.PERMISSION_GRANTED
                }
                else -> error("unexpected permission $permission")
            }
        }

        val result = adapter.check(FINE)

        assertEquals(PermissionState.Granted, result)
        assertTrue("coarse should be short-circuited once fine is granted", !coarseWasChecked)
    }

    @Test
    fun fineLocation_bothDenied_returnsDenied_notApproximate() {
        val adapter = adapter { PackageManager.PERMISSION_DENIED }

        assertEquals(PermissionState.Denied, adapter.check(FINE))
    }

    @Test
    fun coarseLocationCheckedDirectly_isUnaffectedByFineLocationSpecialCase() {
        // The FINE-specific coarse-fallback logic must not accidentally
        // apply when COARSE itself is the permission being asked about.
        val adapter = adapter { PackageManager.PERMISSION_GRANTED }

        assertEquals(PermissionState.Granted, adapter.check(COARSE))
    }

    // -- 4: check failure -----------------------------------------------

    @Test
    fun queryThrows_returnsCheckFailed_neverPropagatesAndNeverFabricatesAGrant() {
        val adapter = adapter { throw SecurityException("simulated platform failure") }

        val result = adapter.check(NETWORK_STATE)

        val failed = result as? PermissionState.CheckFailed
        requireNotNull(failed) { "expected CheckFailed, got $result" }
        assertEquals("SecurityException", failed.reason)
    }

    @Test
    fun checkFailed_reason_isExceptionClassNameOnly_neverTheRawMessage() {
        val adapter = adapter { throw IllegalStateException("contains sensitive platform-internal detail") }

        val failed = adapter.check(NETWORK_STATE) as PermissionState.CheckFailed

        assertEquals("IllegalStateException", failed.reason)
        assertTrue(!failed.reason.contains("sensitive"))
    }

    @Test
    fun fineLocationCheck_thrownDuringCoarseFallback_alsoReturnsCheckFailed() {
        val adapter = adapter { permission ->
            if (permission == FINE) PackageManager.PERMISSION_DENIED else throw SecurityException()
        }

        assertTrue(adapter.check(FINE) is PermissionState.CheckFailed)
    }

    // -- 5: repeated checks reflect changed state, not stale cache ------

    @Test
    fun repeatedCalls_reflectCurrentSeamState_notAMemoizedFirstResult() {
        var currentResult = PackageManager.PERMISSION_DENIED
        val adapter = adapter { currentResult }

        val first = adapter.check(NETWORK_STATE)
        currentResult = PackageManager.PERMISSION_GRANTED
        val second = adapter.check(NETWORK_STATE)

        assertEquals(PermissionState.Denied, first)
        assertEquals(PermissionState.Granted, second)
    }

    // -- 6: sdkInt pass-through (API-level-specific data, not branching) --

    @Test
    fun currentSdkInt_returnsWhatWasSupplied() {
        val adapter = adapter(sdkInt = 26) { PackageManager.PERMISSION_GRANTED }

        assertEquals(26, adapter.currentSdkInt())
    }

    @Test
    fun approximateLocationLogic_isIdenticalAcrossSupportedApiLevels() {
        // Per this module's minSdk (26): ACCESS_FINE_LOCATION and
        // ACCESS_COARSE_LOCATION are independent booleans on every
        // supported level, not just API 31+. This test constructs the
        // same fine-denied/coarse-granted scenario at the module's
        // minSdk floor and at a modern SDK level and confirms both
        // produce GrantedApproximateOnly -- there is no SDK-gated branch
        // in the fine-location logic to diverge.
        for (sdkInt in listOf(26, 30, 31, 34)) {
            val adapter = adapter(sdkInt = sdkInt) { permission ->
                if (permission == FINE) PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
            }

            assertEquals(
                "expected GrantedApproximateOnly at API $sdkInt",
                PermissionState.GrantedApproximateOnly,
                adapter.check(FINE)
            )
        }
    }
}
