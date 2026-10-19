package com.aeriva.app.home

import com.aeriva.core.model.DiagnosticsStatus
import com.aeriva.core.model.NetworkQuality
import com.aeriva.core.model.NetworkState
import com.aeriva.core.model.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * [HomeStateHolder] driven by a MutableStateFlow standing in for
 * ConnectionStateRepository.state, with virtual time for the
 * "no report yet" grace period. Real reports are different instances with
 * their own timestamps, exactly like the repository's StateFlow produces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolderTest {

    private val delayMillis = 1_000L

    private fun state(
        available: Boolean,
        atSecond: Long,
        transport: TransportType = if (available) TransportType.WIFI else TransportType.NONE,
        validated: Boolean = available
    ) = NetworkState(
        transport = transport,
        available = available,
        validated = validated,
        metered = false,
        capabilities = emptySet(),
        estimatedQuality = NetworkQuality.Unavailable,
        diagnosticsStatus = DiagnosticsStatus.NotAvailable,
        lastChangedAt = Instant.EPOCH.plusSeconds(atSecond),
        captivePortalReported = false,
        vpnPresent = false,
        blockedByDevicePolicy = false
    )

    private class Harness(
        val source: MutableStateFlow<NetworkState>,
        val holder: HomeStateHolder
    )

    private fun TestScope.harness(seed: NetworkState): Harness {
        val source = MutableStateFlow(seed)
        val holder = HomeStateHolder(
            networkState = source,
            seed = seed,
            scope = backgroundScope,
            noReportDelayMillis = delayMillis
        )
        runCurrent()
        return Harness(source, holder)
    }

    @Test
    fun beforeAnyReport_stateIsInitializing_notOfflineAndNotConnected() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        assertEquals(HomeUiState.Initializing, h.holder.uiState.value)
    }

    @Test
    fun noReportWithinGracePeriod_becomesNoNetworkReported() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        advanceTimeBy(delayMillis - 1)
        runCurrent()
        assertEquals(HomeUiState.Initializing, h.holder.uiState.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(HomeUiState.NoNetworkReported, h.holder.uiState.value)
    }

    @Test
    fun realConnectedReport_beforeGracePeriodEnds_cancelsTheNoReportTimer() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        advanceTimeBy(delayMillis / 2)
        h.source.value = state(available = true, atSecond = 5)
        runCurrent()
        assertEquals(
            HomeUiState.Connected(TransportType.WIFI, InternetStatus.VALIDATED, metered = false, vpnPresent = false),
            h.holder.uiState.value
        )

        // Past the original deadline: the stale timer must not overwrite real state.
        advanceTimeBy(delayMillis * 5)
        runCurrent()
        assertEquals(
            HomeUiState.Connected(TransportType.WIFI, InternetStatus.VALIDATED, metered = false, vpnPresent = false),
            h.holder.uiState.value
        )
    }

    @Test
    fun genuineOfflineReport_isOffline_notMistakenForTheUninitializedSeed() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        // Structurally the same shape as the seed, but a different instance
        // with its own timestamp -- a real "no usable network" report.
        h.source.value = state(available = false, atSecond = 7)
        runCurrent()

        assertEquals(HomeUiState.Offline, h.holder.uiState.value)

        advanceTimeBy(delayMillis * 5)
        runCurrent()
        assertEquals(HomeUiState.Offline, h.holder.uiState.value)
    }

    @Test
    fun connectedThenOfflineThenConnected_followsEveryTransition_andRecovers() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        h.source.value = state(available = true, atSecond = 1, transport = TransportType.WIFI)
        runCurrent()
        assertEquals(TransportType.WIFI, (h.holder.uiState.value as HomeUiState.Connected).transport)

        h.source.value = state(available = false, atSecond = 2)
        runCurrent()
        assertEquals(HomeUiState.Offline, h.holder.uiState.value)
        assertEquals(emptyList<DetailRow>(), h.holder.uiState.value.detailRows())

        h.source.value = state(available = true, atSecond = 3, transport = TransportType.CELLULAR)
        runCurrent()
        assertEquals(TransportType.CELLULAR, (h.holder.uiState.value as HomeUiState.Connected).transport)
    }

    @Test
    fun connectedButNotValidated_updatesToValidated_whenTheMonitorReportsValidation() = runTest {
        val h = harness(seed = state(available = false, atSecond = 0))

        h.source.value = state(available = true, atSecond = 1, validated = false)
        runCurrent()
        assertEquals(InternetStatus.NOT_VALIDATED, (h.holder.uiState.value as HomeUiState.Connected).internet)

        h.source.value = state(available = true, atSecond = 2, validated = true)
        runCurrent()
        assertEquals(InternetStatus.VALIDATED, (h.holder.uiState.value as HomeUiState.Connected).internet)
    }
}
