package com.aeriva.network.monitor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.core.logging.AndroidLogcatLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because android.net.ConnectivityManager cannot be
 * exercised in a plain JVM unit test.
 *
 * IMPORTANT -- what this test does and does not prove: it proves the
 * monitor wires up to the real ConnectivityManager, registers/unregisters
 * its callback (now including `onBlockedStatusChanged`) without
 * crashing, and emits a [com.aeriva.core.model.NetworkState]
 * reflecting whatever connectivity the device or emulator actually has at
 * test time. It does NOT exercise the transition path (Wi-Fi to cellular,
 * connect to disconnect, a device-policy block appearing or clearing),
 * because that requires manually or programmatically changing the
 * device's real network/policy state during the test run, which needs a
 * physical device or a configured emulator network and is exactly the
 * "real hardware" step the Phase 2 exit criteria calls out as a separate
 * requirement. That verification still has not happened -- see the
 * Phase 2 report. The transition, stale-state and blocked-status *fold
 * logic* itself (what AndroidNetworkMonitor does with whatever events
 * arrive) is covered on the plain JVM instead, in
 * NetworkEventReducerTest -- see that file's own KDoc for why a plain
 * JVM test can exercise the exact same decision logic this monitor runs
 * against real android.net.Network instances.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorInstrumentedTest {

    @Test
    fun observe_emitsAStateWithoutCrashing() = runBlocking {
        // Deliberately runBlocking (real time), not runTest (virtual
        // time). Confirmed from this repo's own CI run: runTest's
        // virtual-time scheduler cannot advance for a real
        // ConnectivityManager callback firing on a real system thread --
        // the test hung until its 10s withTimeout, then failed with
        // "Timed out after 10s of _virtual_ time", exactly the scenario
        // kotlinx-coroutines-test warns about mixing virtual time with
        // real asynchronous system events. This test waits on a real OS
        // callback, not simulated coroutine logic, so it needs real time.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidNetworkMonitor(
            context = context,
            logger = AndroidLogcatLogger(debugBuild = true)
        )

        val firstState = withTimeout(10_000) {
            monitor.observe().first()
        }

        assertNotNull(firstState)
        // Not a real assertion about device-policy state -- just confirms
        // the onBlockedStatusChanged wiring produced a real, non-default-
        // looking-by-accident boolean rather than crashing or hanging.
        // CI/emulator runs have no reason to be blocked, so false is the
        // expected value here, not an assumption baked into production
        // logic (see NetworkEventReducerTest for that logic's real
        // coverage).
        assertFalse(firstState.blockedByDevicePolicy)
    }
}
