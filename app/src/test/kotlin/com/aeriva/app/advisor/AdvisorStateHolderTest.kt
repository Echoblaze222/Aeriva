package com.aeriva.app.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Drives [AdvisorStateHolder] with fake flows and virtual time. Covers the card
 * being absent until the first list, the dwell before a recommendation shows,
 * a recommendation being withdrawn when the situation changes, and stale taking
 * priority and withdrawing a recommendation (ADVISOR_UI_SPEC.md D1 and D2).
 *
 * The default timing is used (dwell 10 seconds, cooldown 5 minutes), which is
 * Agent 2's placeholder, not a tuned value.
 */
class AdvisorStateHolderTest {

    private fun network(
        id: Long,
        transport: TransportType,
        validated: Boolean,
        isDefault: Boolean = false
    ) = CandidateNetwork(
        id = CandidateNetworkId(id),
        transport = transport,
        validated = validated,
        metered = false,
        captivePortalReported = false,
        blockedByDevicePolicy = false,
        vpnPresent = false,
        isDefault = isDefault,
        observedAt = Instant.EPOCH
    )

    private val wifiWithoutInternet = network(1, TransportType.WIFI, validated = false, isDefault = true)
    private val workingMobileData = network(2, TransportType.CELLULAR, validated = true)
    private val workingWifi = network(1, TransportType.WIFI, validated = true, isDefault = true)

    @Test
    fun theCardIsAbsentUntilTheFirstCandidateListArrives() = runTest {
        val holder = AdvisorStateHolder(
            candidates = MutableSharedFlow(),
            stale = MutableStateFlow(false),
            scope = backgroundScope,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        runCurrent()

        assertEquals(AdvisorUiState.Absent, holder.uiState.value)
    }

    @Test
    fun aRecommendationOnlyAppearsAfterTheConditionHasHeldForTheDwell() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = AdvisorStateHolder(
            candidates = candidates,
            stale = MutableStateFlow(false),
            scope = backgroundScope,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        candidates.emit(listOf(wifiWithoutInternet, workingMobileData))
        runCurrent()
        assertTrue("the dwell has not passed yet", holder.uiState.value is AdvisorUiState.Idle)

        advanceTimeBy(10_001L)
        runCurrent()

        assertEquals(
            AdvisorUiState.Recommend(
                target = NetworkLabel(TransportType.CELLULAR, null),
                current = NetworkLabel(TransportType.WIFI, null),
                notes = emptySet()
            ),
            holder.uiState.value
        )
    }

    @Test
    fun aRecommendationIsWithdrawnWhenTheCurrentNetworkRecovers() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = AdvisorStateHolder(
            candidates = candidates,
            stale = MutableStateFlow(false),
            scope = backgroundScope,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        candidates.emit(listOf(wifiWithoutInternet, workingMobileData))
        runCurrent()
        advanceTimeBy(10_001L)
        runCurrent()
        assertTrue(holder.uiState.value is AdvisorUiState.Recommend)

        candidates.emit(listOf(workingWifi, workingMobileData))
        runCurrent()

        assertTrue(holder.uiState.value !is AdvisorUiState.Recommend)
    }

    @Test
    fun staleTakesPriority_withdrawsTheRecommendation_andRestartsTheDwellWhenItClears() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val stale = MutableStateFlow(false)
        val holder = AdvisorStateHolder(
            candidates = candidates,
            stale = stale,
            scope = backgroundScope,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        candidates.emit(listOf(wifiWithoutInternet, workingMobileData))
        runCurrent()
        advanceTimeBy(10_001L)
        runCurrent()
        assertTrue(holder.uiState.value is AdvisorUiState.Recommend)

        stale.value = true
        runCurrent()
        assertEquals(AdvisorUiState.Stale, holder.uiState.value)

        stale.value = false
        runCurrent()
        assertTrue(
            "a recommendation must be earned again after a pause, not restored",
            holder.uiState.value is AdvisorUiState.Idle
        )
    }

    @Test
    fun twoNetworksOfOneTransportAreNumberedInTheRecommendation() = runTest {
        val candidates = MutableSharedFlow<List<CandidateNetwork>>(replay = 1)
        val holder = AdvisorStateHolder(
            candidates = candidates,
            stale = MutableStateFlow(false),
            scope = backgroundScope,
            now = { Instant.ofEpochMilli(testScheduler.currentTime) }
        )
        val brokenMobileData = network(3, TransportType.CELLULAR, validated = false)
        candidates.emit(listOf(wifiWithoutInternet, workingMobileData, brokenMobileData))
        runCurrent()
        advanceTimeBy(10_001L)
        runCurrent()

        val state = holder.uiState.value as AdvisorUiState.Recommend
        assertEquals(NetworkLabel(TransportType.CELLULAR, 1), state.target)
        assertEquals(NetworkLabel(TransportType.WIFI, null), state.current)
    }
}
