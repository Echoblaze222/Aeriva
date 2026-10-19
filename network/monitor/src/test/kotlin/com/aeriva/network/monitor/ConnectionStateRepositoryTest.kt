package com.aeriva.network.monitor

import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Behavior of [ConnectionStateRepository] in isolation, driven by a fake
 * [NetworkMonitor]: what [ConnectionStateRepository.state] holds before the
 * monitor has produced anything, and how the first real monitor emission
 * reaches it. Deliberately limited to the repository's own contract. No
 * AndroidNetworkMonitor, no reducer and no Android types are involved, so
 * this runs on the plain JVM like the other tests in this module.
 *
 * Conventions follow [NetworkStatePipelineTest]: kotlinx-coroutines-test
 * `runTest` with virtual time, and no real sleeping. The repository's
 * `stateIn(scope, SharingStarted.Eagerly, ...)` collector lives in
 * `backgroundScope`, which `runTest` cancels at the end of each test.
 * `runCurrent()` is required after construction because `runTest`'s default
 * dispatcher queues the eagerly launched collector instead of running it
 * inline.
 *
 * The fake's shared flow has no replay and one slot of extra buffer, so an
 * emission made before the repository's collector subscribes would be lost.
 * Every test therefore asserts `subscriptionCount == 1` before emitting, which
 * turns a would-be silent lost emission into a clear failure.
 *
 * The initial value comes from `NetworkState.unknown(at = Instant.now())`
 * inside the repository, which this test cannot inject. Its timestamp is
 * therefore only checked to be close to the real clock at construction, with
 * a generous tolerance so a wall-clock adjustment cannot make the test flaky.
 */
class ConnectionStateRepositoryTest {

    private class FakeNetworkMonitor : NetworkMonitor {
        val source = MutableSharedFlow<NetworkState>(extraBufferCapacity = 1)

        var observeCalls = 0
            private set

        override fun observe(): Flow<NetworkState> {
            observeCalls++
            return source
        }
    }

    private val wifiSnapshot = RawCapabilitiesSnapshot(
        hasInternet = true,
        isValidated = true,
        isNotMetered = true,
        transports = setOf(TransportType.WIFI),
        rawCapabilityNames = setOf("INTERNET", "VALIDATED", "NOT_METERED")
    )

    private val firstEmissionAt = Instant.EPOCH.plusSeconds(1)

    // ---- Initial value -----------------------------------------------------

    @Test
    fun initialState_beforeAnyMonitorEmission_isTheUnknownStateAndRepositoryReportsOffline() = runTest {
        val monitor = FakeNetworkMonitor()
        val before = Instant.now()

        val repository = ConnectionStateRepository(monitor, backgroundScope, NoOpLogger)
        runCurrent()

        val initial = repository.state.value
        assertEquals(TransportType.NONE, initial.transport)
        assertFalse(initial.available)
        assertFalse(initial.validated)
        assertFalse(initial.metered)
        assertTrue(initial.capabilities.isEmpty())
        assertEquals(NetworkQuality.Unavailable, initial.estimatedQuality)
        assertEquals(DiagnosticsStatus.NotAvailable, initial.diagnosticsStatus)
        assertFalse(initial.captivePortalReported)
        assertFalse(initial.vpnPresent)
        assertFalse(initial.blockedByDevicePolicy)
        assertTrue(
            "lastChangedAt must be the construction-time clock reading",
            Duration.between(before, initial.lastChangedAt).abs() < Duration.ofSeconds(5)
        )
        assertTrue("no usable default network is known yet", repository.isOffline)
    }

    @Test
    fun construction_registersExactlyOnceEagerly_andStateIsNotReplacedWithoutAnEmission() = runTest {
        val monitor = FakeNetworkMonitor()

        val repository = ConnectionStateRepository(monitor, backgroundScope, NoOpLogger)
        // Eagerly: observe() is called at construction, before anything subscribes.
        assertEquals(1, monitor.observeCalls)

        runCurrent()
        assertEquals("the one shared collector must be subscribed", 1, monitor.source.subscriptionCount.value)

        val initial = repository.state.value
        runCurrent()

        assertSame("no emission means the initial value object is untouched", initial, repository.state.value)
        assertEquals("reading state must not register a second callback", 1, monitor.observeCalls)
        assertEquals(1, monitor.source.subscriptionCount.value)
    }

    // ---- First real emission -----------------------------------------------

    @Test
    fun firstMonitorEmission_ofAConnectedNetwork_replacesTheInitialValueAndClearsOffline() = runTest {
        val monitor = FakeNetworkMonitor()
        val repository = ConnectionStateRepository(monitor, backgroundScope, NoOpLogger)
        runCurrent()
        assertEquals(1, monitor.source.subscriptionCount.value)
        assertTrue(repository.isOffline)

        val connected = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = wifiSnapshot,
            changedAt = firstEmissionAt,
            blocked = false
        )
        assertTrue("fake monitor must accept the emission", monitor.source.tryEmit(connected))
        runCurrent()

        assertEquals(connected, repository.state.value)
        assertEquals(TransportType.WIFI, repository.state.value.transport)
        assertEquals(firstEmissionAt, repository.state.value.lastChangedAt)
        assertFalse(repository.isOffline)
    }

    @Test
    fun firstMonitorEmission_ofAnAvailableButUnvalidatedNetwork_isNotOffline() = runTest {
        val monitor = FakeNetworkMonitor()
        val repository = ConnectionStateRepository(monitor, backgroundScope, NoOpLogger)
        runCurrent()
        assertEquals(1, monitor.source.subscriptionCount.value)

        // The class contract: a captive-portal-like network is available but not
        // validated, and is deliberately NOT folded into isOffline.
        val unvalidated = NetworkStateMapper.buildNetworkState(
            available = true,
            snapshot = wifiSnapshot.copy(isValidated = false),
            changedAt = firstEmissionAt,
            blocked = false
        )
        assertTrue(monitor.source.tryEmit(unvalidated))
        runCurrent()

        assertEquals(unvalidated, repository.state.value)
        assertTrue(repository.state.value.available)
        assertFalse(repository.state.value.validated)
        assertFalse(repository.isOffline)
    }

    @Test
    fun firstMonitorEmission_ofCanonicalOffline_replacesTheUnknownPlaceholderAndStaysOffline() = runTest {
        val monitor = FakeNetworkMonitor()
        val repository = ConnectionStateRepository(monitor, backgroundScope, NoOpLogger)
        runCurrent()
        assertEquals(1, monitor.source.subscriptionCount.value)
        val placeholder = repository.state.value

        val offline = NetworkStateMapper.buildNetworkState(
            available = false,
            snapshot = null,
            changedAt = firstEmissionAt,
            blocked = false
        )
        assertTrue(monitor.source.tryEmit(offline))
        runCurrent()

        // A real offline reading is distinguishable from the unknown placeholder
        // by the monitor's own timestamp.
        assertEquals(offline, repository.state.value)
        assertEquals(firstEmissionAt, repository.state.value.lastChangedAt)
        assertFalse("the placeholder must have been replaced", placeholder == repository.state.value)
        assertTrue(repository.isOffline)
    }
}
