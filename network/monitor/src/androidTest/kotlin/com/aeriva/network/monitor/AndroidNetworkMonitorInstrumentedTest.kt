package com.aeriva.network.monitor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.core.logging.AndroidAerivaLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because android.net.ConnectivityManager cannot be
 * exercised in a plain JVM unit test.
 *
 * IMPORTANT -- what this test does and does not prove: it proves the
 * monitor wires up to the real ConnectivityManager, registers/unregisters
 * its callback without crashing, and emits a [com.aeriva.core.model.NetworkState]
 * reflecting whatever connectivity the device or emulator actually has at
 * test time. It does NOT exercise the transition path (Wi-Fi to cellular,
 * connect to disconnect), because that requires manually or
 * programmatically changing the device's real network state during the
 * test run, which needs a physical device or a configured emulator
 * network and is exactly the "real hardware" step the Phase 2 exit
 * criteria calls out as a separate requirement. That verification still
 * has not happened -- see the Phase 2 report.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorInstrumentedTest {

    @Test
    fun observe_emitsAStateWithoutCrashing() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = AndroidNetworkMonitor(
            context = context,
            logger = AndroidAerivaLogger()
        )

        val firstState = withTimeout(10_000) {
            monitor.observe().first()
        }

        assertNotNull(firstState)
    }
}
