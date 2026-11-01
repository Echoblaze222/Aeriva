package com.aeriva.app.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.AdvisorNote
import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.core.model.advisor.EvidenceBasis
import com.aeriva.core.model.advisor.NoRecommendationReason
import com.aeriva.core.model.advisor.RecommendationCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The mapping table of ADVISOR_UI_SPEC.md section 3 and the label numbering of
 * section 5.7. Pure JVM: no Android class is touched.
 */
class AdvisorUiStateMappingTest {

    private fun candidate(id: Long, transport: TransportType) = CandidateNetwork(
        id = CandidateNetworkId(id),
        transport = transport,
        validated = true,
        metered = false,
        captivePortalReported = false,
        blockedByDevicePolicy = false,
        vpnPresent = false,
        isDefault = false,
        observedAt = Instant.EPOCH
    )

    private fun stateFor(reason: NoRecommendationReason, notes: Set<AdvisorNote> = emptySet()): AdvisorUiState =
        AdvisorOutcome.NoRecommendation(reason, notes).toUiState(emptyList(), NetworkLabeler())

    @Test
    fun everyNoRecommendationReason_mapsToTheCardStateInTheSpecTable() {
        val idle = AdvisorUiState.Idle(emptySet())
        assertEquals(idle, stateFor(NoRecommendationReason.CURRENT_NETWORK_VALIDATED))
        assertEquals(idle, stateFor(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE))
        assertEquals(idle, stateFor(NoRecommendationReason.CONDITION_NOT_STABLE_YET))
        assertEquals(idle, stateFor(NoRecommendationReason.RECENTLY_SHOWN))
        assertEquals(AdvisorUiState.SingleNetwork(emptySet()), stateFor(NoRecommendationReason.ONLY_ONE_NETWORK))
        assertEquals(AdvisorUiState.NoNetwork(false), stateFor(NoRecommendationReason.NO_NETWORK))
        assertEquals(AdvisorUiState.NoNetwork(true), stateFor(NoRecommendationReason.ALL_BLOCKED_BY_DEVICE_POLICY))
        assertEquals(
            AdvisorUiState.Insufficient(InsufficientReason.MULTIPLE_VALIDATED, emptySet()),
            stateFor(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE)
        )
        assertEquals(
            AdvisorUiState.Insufficient(InsufficientReason.CURRENT_UNKNOWN, emptySet()),
            stateFor(NoRecommendationReason.DEFAULT_NETWORK_NOT_REPORTED)
        )
        assertEquals(
            AdvisorUiState.Insufficient(InsufficientReason.VPN_ON_CURRENT, emptySet()),
            stateFor(NoRecommendationReason.VPN_ACTIVE_ON_CURRENT)
        )
    }

    @Test
    fun noReasonEverProducesARecommendationOrAStaleCard() {
        NoRecommendationReason.entries.forEach { reason ->
            val state = stateFor(reason)
            assertTrue("$reason", state !is AdvisorUiState.Recommend)
            assertTrue("$reason", state !is AdvisorUiState.Stale)
            assertTrue("$reason", state !is AdvisorUiState.Absent)
        }
    }

    @Test
    fun notesAreCarriedToTheCardState() {
        val state = stateFor(
            NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE,
            setOf(AdvisorNote.METERED_STATUS_DIFFERS)
        )
        assertEquals(
            AdvisorUiState.Insufficient(
                InsufficientReason.MULTIPLE_VALIDATED,
                setOf(AdvisorNote.METERED_STATUS_DIFFERS)
            ),
            state
        )
    }

    @Test
    fun aSingleNetworkOfATransportIsNeverNumbered() {
        val candidates = listOf(candidate(1, TransportType.WIFI), candidate(2, TransportType.CELLULAR))
        val labeler = NetworkLabeler().apply { update(candidates) }

        assertNull(labeler.labelFor(CandidateNetworkId(1), TransportType.WIFI, candidates).number)
        assertNull(labeler.labelFor(CandidateNetworkId(2), TransportType.CELLULAR, candidates).number)
    }

    @Test
    fun twoNetworksOfOneTransport_areNumberedByFirstAppearance() {
        val candidates = listOf(candidate(7, TransportType.CELLULAR), candidate(3, TransportType.CELLULAR))
        val labeler = NetworkLabeler().apply { update(candidates) }

        assertEquals(1, labeler.labelFor(CandidateNetworkId(7), TransportType.CELLULAR, candidates).number)
        assertEquals(2, labeler.labelFor(CandidateNetworkId(3), TransportType.CELLULAR, candidates).number)
    }

    @Test
    fun aNumberIsNeverReassigned_whenAnEarlierNetworkDisappears() {
        val first = candidate(1, TransportType.CELLULAR)
        val second = candidate(2, TransportType.CELLULAR)
        val labeler = NetworkLabeler()
        labeler.update(listOf(first, second))

        val third = candidate(3, TransportType.CELLULAR)
        val afterFirstLeaves = listOf(second, third)
        labeler.update(afterFirstLeaves)

        assertEquals(2, labeler.labelFor(second.id, TransportType.CELLULAR, afterFirstLeaves).number)
        assertEquals(3, labeler.labelFor(third.id, TransportType.CELLULAR, afterFirstLeaves).number)
    }

    @Test
    fun aRecommendationNamesBothNetworks_usingTheirCurrentTransports() {
        val current = candidate(1, TransportType.WIFI)
        val target = candidate(2, TransportType.CELLULAR)
        val candidates = listOf(current, target)
        val labeler = NetworkLabeler().apply { update(candidates) }
        val outcome = AdvisorOutcome.Recommend(
            current = current.id,
            target = target.id,
            targetTransport = TransportType.CELLULAR,
            criteria = RecommendationCriteria.CURRENT_NOT_VALIDATED_OTHER_VALIDATED,
            basis = EvidenceBasis.PLATFORM_VALIDATION,
            notes = emptySet()
        )

        assertEquals(
            AdvisorUiState.Recommend(
                target = NetworkLabel(TransportType.CELLULAR, null),
                current = NetworkLabel(TransportType.WIFI, null),
                notes = emptySet()
            ),
            outcome.toUiState(candidates, labeler)
        )
    }
}
