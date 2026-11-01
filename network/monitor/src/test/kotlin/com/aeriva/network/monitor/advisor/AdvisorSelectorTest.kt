package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.AdvisorNote
import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.core.model.advisor.EvidenceBasis
import com.aeriva.core.model.advisor.NoRecommendationReason
import com.aeriva.core.model.advisor.RecommendationCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM tests for [AdvisorSelector] (spec section 5.3 and section 7). Every
 * rule has a positive and a negative test. No test asserts a ranking
 * between two validated candidates; several assert that none is made.
 */
class AdvisorSelectorTest {

    private val observed = Instant.parse("2026-10-23T10:00:00Z")

    private fun net(
        id: Long,
        transport: TransportType,
        validated: Boolean = true,
        metered: Boolean = false,
        captive: Boolean = false,
        blocked: Boolean = false,
        vpn: Boolean = false,
        isDefault: Boolean = false
    ) = CandidateNetwork(
        id = CandidateNetworkId(id),
        transport = transport,
        validated = validated,
        metered = metered,
        captivePortalReported = captive,
        blockedByDevicePolicy = blocked,
        vpnPresent = vpn,
        isDefault = isDefault,
        observedAt = observed
    )

    private fun assertNone(expected: NoRecommendationReason, outcome: AdvisorOutcome): AdvisorOutcome.NoRecommendation {
        assertTrue("expected NoRecommendation($expected) but was $outcome", outcome is AdvisorOutcome.NoRecommendation)
        val none = outcome as AdvisorOutcome.NoRecommendation
        assertEquals(expected, none.reason)
        return none
    }

    private fun assertRecommend(outcome: AdvisorOutcome): AdvisorOutcome.Recommend {
        assertTrue("expected Recommend but was $outcome", outcome is AdvisorOutcome.Recommend)
        return outcome as AdvisorOutcome.Recommend
    }

    // ---- Empty and rule 1: blocked by device policy -------------------

    @Test
    fun emptyList_isNoNetwork() {
        assertNone(NoRecommendationReason.NO_NETWORK, AdvisorSelector.select(emptyList()))
    }

    @Test
    fun rule1_everyCandidateBlocked_isAllBlocked() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, blocked = true, isDefault = true),
                net(2, TransportType.CELLULAR, blocked = true)
            )
        )

        assertNone(NoRecommendationReason.ALL_BLOCKED_BY_DEVICE_POLICY, outcome)
    }

    @Test
    fun rule1_blockedCandidate_isNeverRecommended() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR, blocked = true)
            )
        )

        assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
    }

    @Test
    fun rule1_blockedDefault_isIgnored_andNoBaselineRemains() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, blocked = true, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        assertNone(NoRecommendationReason.DEFAULT_NETWORK_NOT_REPORTED, outcome)
    }

    @Test
    fun rule1_blockedCandidate_doesNotCountAsAnAlternative() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR, blocked = true),
                net(3, TransportType.CELLULAR)
            )
        )

        val recommend = assertRecommend(outcome)
        assertEquals(CandidateNetworkId(3), recommend.target)
    }

    @Test
    fun rule1_runsBeforeRule4_aBlockedSecondNetworkLeavesOnlyOneNetwork() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, isDefault = true),
                net(2, TransportType.CELLULAR, blocked = true)
            )
        )

        assertNone(NoRecommendationReason.ONLY_ONE_NETWORK, outcome)
    }

    // ---- Rule 2: current not working, another validated ---------------

    @Test
    fun rule2_defaultNotValidated_otherValidated_recommendsTheOther() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR, metered = true)
            )
        )

        val recommend = assertRecommend(outcome)
        assertEquals(CandidateNetworkId(1), recommend.current)
        assertEquals(CandidateNetworkId(2), recommend.target)
        assertEquals(TransportType.CELLULAR, recommend.targetTransport)
        assertEquals(RecommendationCriteria.CURRENT_NOT_VALIDATED_OTHER_VALIDATED, recommend.criteria)
        assertEquals(EvidenceBasis.PLATFORM_VALIDATION, recommend.basis)
        assertEquals(emptySet<AdvisorNote>(), recommend.notes)
    }

    @Test
    fun rule2_captivePortalOnDefault_recommendsValidatedOther() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = true, captive = true, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        val recommend = assertRecommend(outcome)
        assertEquals(CandidateNetworkId(2), recommend.target)
    }

    @Test
    fun rule2_negative_defaultValidated_doesNotRecommend() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
    }

    @Test
    fun rule2_negative_noOtherCandidateIsValidated() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR, validated = false)
            )
        )

        assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
    }

    @Test
    fun rule2_twoCandidatesOfTheSameTransport_dualSim_recommendsTheValidatedOne() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.CELLULAR, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        val recommend = assertRecommend(outcome)
        assertEquals(CandidateNetworkId(1), recommend.current)
        assertEquals(CandidateNetworkId(2), recommend.target)
        assertEquals(TransportType.CELLULAR, recommend.targetTransport)
    }

    @Test
    fun rule2_twoValidatedAlternatives_areNotRanked_inAnyInputOrder() {
        val current = net(1, TransportType.WIFI, validated = false, isDefault = true)
        val first = net(2, TransportType.CELLULAR)
        val second = net(3, TransportType.CELLULAR)

        val forward = AdvisorSelector.select(listOf(current, first, second))
        val reversed = AdvisorSelector.select(listOf(second, first, current))

        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun rule2_alternativeWithCaptivePortal_doesNotCountAsValidated() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR, validated = true, captive = true)
            )
        )

        assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
    }

    // ---- Rule 3: only one network -------------------------------------

    @Test
    fun rule3_defaultValidated_singleCandidate_isOnlyOneNetwork() {
        val outcome = AdvisorSelector.select(listOf(net(1, TransportType.WIFI, isDefault = true)))

        assertNone(NoRecommendationReason.ONLY_ONE_NETWORK, outcome)
    }

    @Test
    fun rule3_negative_singleCandidateThatIsNotDefault_isNotOnlyOneNetwork() {
        val outcome = AdvisorSelector.select(listOf(net(1, TransportType.WIFI)))

        assertNone(NoRecommendationReason.DEFAULT_NETWORK_NOT_REPORTED, outcome)
    }

    @Test
    fun rule3_negative_singleDefaultNotValidated_isNotOnlyOneNetwork() {
        val outcome = AdvisorSelector.select(listOf(net(1, TransportType.WIFI, validated = false, isDefault = true)))

        assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
    }

    @Test
    fun rule3_negative_singleDefaultWithCaptivePortal_isNotOnlyOneNetwork() {
        val outcome = AdvisorSelector.select(
            listOf(net(1, TransportType.WIFI, validated = true, captive = true, isDefault = true))
        )

        assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
    }

    // ---- Rule 4: two or more validated, no ranking --------------------

    @Test
    fun rule4_twoValidated_noRecommendation_whicheverIsDefault() {
        val wifiDefault = AdvisorSelector.select(
            listOf(net(1, TransportType.WIFI, isDefault = true), net(2, TransportType.CELLULAR))
        )
        val cellularDefault = AdvisorSelector.select(
            listOf(net(1, TransportType.WIFI), net(2, TransportType.CELLULAR, isDefault = true))
        )

        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, wifiDefault)
        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, cellularDefault)
    }

    @Test
    fun rule4_neverRecommends_acrossEveryInputOrder() {
        val x = net(1, TransportType.WIFI, isDefault = true)
        val y = net(2, TransportType.CELLULAR, metered = true)
        val z = net(3, TransportType.WIFI)
        val orders = listOf(
            listOf(x, y), listOf(y, x),
            listOf(x, y, z), listOf(x, z, y), listOf(y, x, z), listOf(y, z, x), listOf(z, x, y), listOf(z, y, x)
        )

        orders.forEach { order ->
            val outcome = AdvisorSelector.select(order)
            assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
        }
    }

    @Test
    fun rule4_meteredDiffers_addsInformationalNote() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, metered = false, isDefault = true),
                net(2, TransportType.CELLULAR, metered = true)
            )
        )

        val none = assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
        assertEquals(setOf(AdvisorNote.METERED_STATUS_DIFFERS), none.notes)
    }

    @Test
    fun rule4_meteredSame_hasNoMeteredNote() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, metered = false, isDefault = true),
                net(2, TransportType.WIFI, metered = false)
            )
        )

        val none = assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
        assertEquals(emptySet<AdvisorNote>(), none.notes)
    }

    @Test
    fun rule4_twoCandidatesOfTheSameTransport_areNotRanked() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.CELLULAR, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
    }

    @Test
    fun rule4_noDefaultReported_twoValidated_stillNoRanking() {
        val outcome = AdvisorSelector.select(
            listOf(net(1, TransportType.WIFI), net(2, TransportType.CELLULAR))
        )

        assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
    }

    @Test
    fun rule4_negative_onlyOneValidated_isNotRule4() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, isDefault = true),
                net(2, TransportType.CELLULAR, validated = false)
            )
        )

        assertNone(NoRecommendationReason.CURRENT_NETWORK_VALIDATED, outcome)
    }

    // ---- Rule 5: VPN --------------------------------------------------

    @Test
    fun rule5_vpnIsCurrent_noRecommendation_andNoteIsAttached() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.VPN, vpn = true, isDefault = true),
                net(2, TransportType.WIFI)
            )
        )

        val none = assertNone(NoRecommendationReason.VPN_ACTIVE_ON_CURRENT, outcome)
        assertEquals(setOf(AdvisorNote.VPN_MAY_AFFECT_COMPARISON), none.notes)
    }

    @Test
    fun rule5_vpnNotValidated_doesNotRecommendSwitchingUnderneath() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.VPN, validated = false, vpn = true, isDefault = true),
                net(2, TransportType.WIFI, validated = false),
                net(3, TransportType.CELLULAR)
            )
        )

        val none = assertNone(NoRecommendationReason.VPN_ACTIVE_ON_CURRENT, outcome)
        assertTrue(AdvisorNote.VPN_MAY_AFFECT_COMPARISON in none.notes)
    }

    @Test
    fun rule5_vpnNetwork_isNeverARecommendationTarget() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.VPN, vpn = true)
            )
        )

        val none = assertNone(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, outcome)
        assertTrue(AdvisorNote.VPN_MAY_AFFECT_COMPARISON in none.notes)
    }

    @Test
    fun rule5_vpnPresentButNotCurrent_stillRecommends_withTheNote() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR),
                net(3, TransportType.VPN, vpn = true)
            )
        )

        val recommend = assertRecommend(outcome)
        assertEquals(CandidateNetworkId(2), recommend.target)
        assertEquals(setOf(AdvisorNote.VPN_MAY_AFFECT_COMPARISON), recommend.notes)
    }

    @Test
    fun rule5_vpnWithTwoValidatedUnderlyingNetworks_isNotRanked_andNoted() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.VPN, vpn = true, isDefault = true),
                net(2, TransportType.WIFI),
                net(3, TransportType.CELLULAR)
            )
        )

        val none = assertNone(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, outcome)
        assertTrue(AdvisorNote.VPN_MAY_AFFECT_COMPARISON in none.notes)
    }

    @Test
    fun rule5_negative_noVpn_hasNoVpnNote() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, validated = false, isDefault = true),
                net(2, TransportType.CELLULAR)
            )
        )

        assertTrue(AdvisorNote.VPN_MAY_AFFECT_COMPARISON !in outcome.notes)
    }

    // ---- Other fall through outcomes ----------------------------------

    @Test
    fun defaultValidated_otherNotValidated_isCurrentNetworkValidated() {
        val outcome = AdvisorSelector.select(
            listOf(
                net(1, TransportType.WIFI, isDefault = true),
                net(2, TransportType.CELLULAR, validated = false)
            )
        )

        assertNone(NoRecommendationReason.CURRENT_NETWORK_VALIDATED, outcome)
    }

    // ---- Determinism --------------------------------------------------

    @Test
    fun sameInput_givesEqualOutcome_andInputOrderDoesNotMatter() {
        val current = net(1, TransportType.WIFI, validated = false, isDefault = true)
        val target = net(2, TransportType.CELLULAR)
        val blocked = net(3, TransportType.CELLULAR, blocked = true)

        val first = AdvisorSelector.select(listOf(current, target, blocked))
        val again = AdvisorSelector.select(listOf(current, target, blocked))
        val shuffled = AdvisorSelector.select(listOf(blocked, target, current))

        assertEquals(first, again)
        assertEquals(first, shuffled)
        assertRecommend(first)
    }
}
