package com.aeriva.network.monitor

import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What [ConnectionStateRepository] does when the monitor's flow FAILS, for
 * example because `registerDefaultNetworkCallback` throws inside
 * [AndroidNetworkMonitor]'s `callbackFlow` block. The failure is induced by a
 * scripted fake [NetworkMonitor] whose flow throws; no Android type is needed.
 *
 * The scope mirrors `AppContainer.appScope`: a [SupervisorJob], and an
 * exception handler that RECORDS what reaches it. In production there is no
 * handler, so the same exception would reach the thread's uncaught-exception
 * handler; that last step cannot be observed on the plain JVM and is NOT
 * asserted here. What these tests do pin is everything before it:
 * the repository does not catch, log or retry the failure, and `state` is left
 * frozen on its last value.
 *
 * OBSERVED (asserted here, on the JVM): a failure thrown by the upstream
 * flow is not caught by [ConnectionStateRepository]; with `stateIn(scope,
 * SharingStarted.Eagerly, ...)` it fails the sharing coroutine, which reports
 * to the scope's exception handler; the scope's SupervisorJob keeps the scope
 * itself alive; `state` keeps its last value; nothing re-subscribes.
 * INFERRED (NOT proven by any test): that in the real app, where
 * `AppContainer.appScope` has no handler, the same exception would reach the
 * thread's uncaught-exception handler and terminate the process. These tests do
 * not demonstrate an Android crash.
 *
 * These are characterization tests of current behavior. If the repository or
 * monitor later gains error handling, they are the tests to revise on purpose.
 * Conventions follow [ConnectionStateRepositoryTest]: virtual time, no sleeps.
 */
class ConnectionStateRepositoryUpstreamFailureTest {

    private class ScriptedMonitor(
        private val script: suspend FlowCollector<NetworkState>.() -> Unit
    ) : NetworkMonitor {
        var observeCalls = 0
            private set

        override fun observe(): Flow<NetworkState> {
            observeCalls++
            return flow(script)
        }
    }

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED", "NOT_METERED")
    )

    @Test
    fun failureBeforeTheFirstEmission_escapesTheRepository_andStateStaysTheUnknownOfflinePlaceholder() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> failures += throwable }
        )
        try {
            val monitor = ScriptedMonitor { throw IllegalStateException("registration failed") }

            val repository = ConnectionStateRepository(monitor, scope, NoOpLogger)
            val placeholder = repository.state.value
            runCurrent()

            assertEquals("the failure must reach the scope's handler exactly once", 1, failures.size)
            assertEquals("registration failed", failures.single().message)
            assertSame("no emission ever happened", placeholder, repository.state.value)
            assertTrue("with no data the repository reports offline", repository.isOffline)
            assertTrue("a SupervisorJob scope survives the child's failure", scope.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failureAfterAConnectedState_freezesStateAsConnected_withNoRetryAndNoOfflineSignal() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> failures += throwable }
        )
        try {
            val connected = NetworkStateMapper.buildNetworkState(
                available = true,
                snapshot = wifiSnapshot,
                changedAt = Instant.EPOCH.plusSeconds(1),
                blocked = false
            )
            val monitor = ScriptedMonitor {
                emit(connected)
                throw IllegalStateException("collector died")
            }

            val repository = ConnectionStateRepository(monitor, scope, NoOpLogger)
            runCurrent()
            // Far beyond any debounce or retry interval; nothing may restart.
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, failures.size)
            assertEquals("collector died", failures.single().message)
            // The last value survives and is never replaced by an offline state.
            assertEquals(connected, repository.state.value)
            assertFalse(repository.isOffline)
            assertEquals("the repository must not re-subscribe on its own", 1, monitor.observeCalls)
        } finally {
            scope.cancel()
        }
    }
}
