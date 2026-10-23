package com.aeriva.network.monitor

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aeriva.core.logging.AndroidLogcatLogger
import com.aeriva.core.model.NetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the "registerDefaultNetworkCallback throws" path of
 * [AndroidNetworkMonitor] against the REAL ConnectivityManager, with no
 * production seam and no new dependency.
 *
 * Method: the platform documents that registration throws a RuntimeException
 * when the app already has too many callbacks registered (limit of 100
 * outstanding requests per UID, shared across callbacks). This test fills that
 * allowance with inert callbacks of its own, then asks the monitor to observe.
 * The registration inside the monitor's `callbackFlow` block then fails for a
 * real platform reason. Everything the test registers is unregistered in a
 * `finally`.
 *
 * ASSERTED: the failure reaches the collector as a RuntimeException (it is not
 * swallowed and does not hang), and a fresh collection works again once the
 * allowance is released.
 *
 * NOT ASSERTED: what the app does with that exception. In the app it reaches
 * ConnectionStateRepository, which does not catch it; that is characterized on
 * the JVM by ConnectionStateRepositoryUpstreamFailureTest. The limit value is
 * not assumed: the test registers until the platform refuses, and is skipped
 * (assumption failure, not a pass) if the platform never refuses.
 *
 * Emulator result, not a real device result. The recovery step polls because
 * the platform releases a request asynchronously after unregistration.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkMonitorRegistrationFailureInstrumentedTest {

    private class InertCallback : ConnectivityManager.NetworkCallback()

    @Test
    fun observe_whenThePlatformRefusesTheRegistration_failsWithARuntimeException_andRecoversAfterTheAllowanceIsReleased() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivityManager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val monitor = AndroidNetworkMonitor(
            context = context,
            logger = AndroidLogcatLogger(debugBuild = true)
        )
        val held = mutableListOf<ConnectivityManager.NetworkCallback>()
        var limitReached = false
        val failedAttempt: Result<NetworkState>
        try {
            for (attempt in 1..MAX_INERT_REGISTRATIONS) {
                val callback = InertCallback()
                try {
                    connectivityManager.registerDefaultNetworkCallback(callback)
                    held += callback
                } catch (expected: RuntimeException) {
                    limitReached = true
                    break
                }
            }
            assumeTrue("the platform never refused a registration, nothing to exercise", limitReached)

            failedAttempt = attemptFirstState(monitor)
        } finally {
            held.forEach { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        }

        assertTrue(
            "observe() must surface the refused registration instead of hanging or returning a state",
            failedAttempt.isFailure
        )
        assertTrue(
            "the surfaced failure must be a RuntimeException, as documented for too many callbacks",
            failedAttempt.exceptionOrNull() is RuntimeException
        )

        var recovered = attemptFirstState(monitor)
        var polls = 0
        while (recovered.isFailure && polls < MAX_RECOVERY_POLLS) {
            delay(RECOVERY_POLL_MILLIS)
            recovered = attemptFirstState(monitor)
            polls++
        }
        assertTrue("a fresh collection must work once the allowance is released", recovered.isSuccess)
    }

    /**
     * A timeout is NOT a registration failure: it is a CancellationException and
     * is rethrown so it fails the test instead of being counted as one.
     */
    private suspend fun attemptFirstState(monitor: NetworkMonitor): Result<NetworkState> =
        try {
            Result.success(withTimeout(FIRST_STATE_TIMEOUT_MILLIS) { monitor.observe().first() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RuntimeException) {
            Result.failure(failure)
        }

    private companion object {
        const val MAX_INERT_REGISTRATIONS = 300
        const val FIRST_STATE_TIMEOUT_MILLIS = 10_000L
        const val MAX_RECOVERY_POLLS = 20
        const val RECOVERY_POLL_MILLIS = 250L
    }
}
