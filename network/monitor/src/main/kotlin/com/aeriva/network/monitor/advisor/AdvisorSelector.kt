package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.advisor.AdvisorNote
import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.EvidenceBasis
import com.aeriva.core.model.advisor.NoRecommendationReason
import com.aeriva.core.model.advisor.RecommendationCriteria

/**
 * Network Advisor A1 selection rules (spec section 5.3) as a pure function.
 * Deterministic and explainable: every outcome names the rule that
 * produced it, and the result does not depend on the order of the input.
 *
 * Hard limits, from the spec and the leader decisions:
 * - No score, no confidence tier (L3), no use of NetworkQuality.Measured.
 * - Two or more validated networks are never ranked. That needs measured
 *   evidence and is the A2 boundary.
 * - Nothing here claims an improvement. A recommendation says only that
 *   the platform reports validated internet on the target and not on the
 *   current network.
 *
 * Interpretation decisions beyond the literal spec text, each covered by a
 * test and listed in the branch report:
 * - A network "works" when it is validated and reports no captive portal.
 * - A network that is itself a VPN is never a recommendation target and is
 *   not counted among the networks being compared. If the current network
 *   is a VPN, no recommendation is made, because the platform data cannot
 *   attribute its failure to an underlying network.
 * - If the current network is not working and exactly one other working
 *   network exists, that network is recommended. If two or more exist, the
 *   spec rule 4 "no ranking" boundary applies and nothing is recommended.
 */
object AdvisorSelector {

    fun select(candidates: List<CandidateNetwork>): AdvisorOutcome {
        if (candidates.isEmpty()) return none(NoRecommendationReason.NO_NETWORK)

        // Rule 1: a network blocked by device policy is not a candidate.
        val usable = candidates.filterNot { it.blockedByDevicePolicy }
        if (usable.isEmpty()) return none(NoRecommendationReason.ALL_BLOCKED_BY_DEVICE_POLICY)

        // Rule 5 (informational part): a VPN may distort any comparison.
        val baseNotes: Set<AdvisorNote> =
            if (usable.any { it.vpnPresent }) setOf(AdvisorNote.VPN_MAY_AFFECT_COMPARISON) else emptySet()

        val current = usable.firstOrNull { it.isDefault }
        val workingPaths = usable.filter { !it.vpnPresent && it.hasWorkingInternet() }

        // Rule 2: current network does not work, exactly one other does.
        if (current != null && !current.vpnPresent && !current.hasWorkingInternet()) {
            val alternatives = workingPaths.filter { it.id != current.id }
            if (alternatives.size == 1) {
                val target = alternatives.single()
                return AdvisorOutcome.Recommend(
                    current = current.id,
                    target = target.id,
                    targetTransport = target.transport,
                    criteria = RecommendationCriteria.CURRENT_NOT_VALIDATED_OTHER_VALIDATED,
                    basis = EvidenceBasis.PLATFORM_VALIDATION,
                    notes = baseNotes
                )
            }
            // Two or more alternatives fall through to rule 4: no ranking.
        }

        // Rule 3: the current network works and it is the only candidate.
        if (usable.size == 1 && current != null && !current.vpnPresent && current.hasWorkingInternet()) {
            return none(NoRecommendationReason.ONLY_ONE_NETWORK, baseNotes)
        }

        // Rule 4: two or more working networks cannot be ranked without measurement.
        if (workingPaths.size >= 2) {
            val meteredDiffers = workingPaths.map { it.metered }.distinct().size > 1
            val notes = if (meteredDiffers) baseNotes + AdvisorNote.METERED_STATUS_DIFFERS else baseNotes
            return none(NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE, notes)
        }

        // Nothing actionable. Say why, never imply the VPN is the cause.
        return when {
            current == null -> none(NoRecommendationReason.DEFAULT_NETWORK_NOT_REPORTED, baseNotes)
            current.vpnPresent -> none(NoRecommendationReason.VPN_ACTIVE_ON_CURRENT, baseNotes)
            current.hasWorkingInternet() -> none(NoRecommendationReason.CURRENT_NETWORK_VALIDATED, baseNotes)
            else -> none(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, baseNotes)
        }
    }

    private fun CandidateNetwork.hasWorkingInternet(): Boolean = validated && !captivePortalReported

    private fun none(
        reason: NoRecommendationReason,
        notes: Set<AdvisorNote> = emptySet()
    ): AdvisorOutcome = AdvisorOutcome.NoRecommendation(reason, notes)
}
