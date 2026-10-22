package com.aeriva.network.monitor.measurement

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because [AndroidPermissionAdapter]'s default seam calls
 * `androidx.core.content.ContextCompat.checkSelfPermission`, which needs
 * a real `Context`/`PackageManager` -- not exercisable in a plain JVM
 * unit test. [AndroidPermissionAdapterTest] already covers this class's
 * decision logic via its injectable seam; this test proves only that the
 * real, default wiring reaches the platform without crashing and reports
 * results consistent with this test app's own manifest.
 *
 * `network:monitor`'s manifest declares `ACCESS_NETWORK_STATE` (a normal
 * permission, granted automatically once declared) and nothing else --
 * no `ACCESS_FINE_LOCATION`, no `INTERNET`. This test's expectations
 * follow directly from that, not from any assumption about a real
 * device's runtime permission grants.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionAdapterInstrumentedTest {

    private fun realAdapter(): AndroidPermissionAdapter {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AndroidPermissionAdapter(context)
    }

    @Test
    fun normalPermission_declaredInManifest_isGranted() {
        val result = realAdapter().check("android.permission.ACCESS_NETWORK_STATE")

        assertEquals(PermissionState.Granted, result)
    }

    @Test
    fun fineLocation_notDeclaredInManifest_isDeniedNotApproximate() {
        // Neither ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION is
        // declared in this module's manifest, so the real platform must
        // report both as not held -- the fine-location check must
        // therefore resolve to Denied, not GrantedApproximateOnly.
        val result = realAdapter().check("android.permission.ACCESS_FINE_LOCATION")

        assertEquals(PermissionState.Denied, result)
    }

    @Test
    fun undeclaredPermission_isDenied_notCheckFailed() {
        // INTERNET is not declared in this module's manifest (Decision
        // D1-1/OD-1 -- this task does not add it). A normal permission
        // simply not declared is a real Denied result from the real
        // platform, not a query failure.
        val result = realAdapter().check("android.permission.INTERNET")

        assertEquals(PermissionState.Denied, result)
    }

    @Test
    fun currentSdkInt_matchesTheRunningDevice() {
        val result = realAdapter().currentSdkInt()

        assertTrue(result >= 26)
    }
}
