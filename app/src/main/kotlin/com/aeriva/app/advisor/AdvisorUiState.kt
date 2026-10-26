package com.aeriva.app.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.AdvisorNote
import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.core.model.advisor.NoRecommendationReason

/**
 * A network as the card names it. [number] is set only when more than one
 * candidate currently shares the transport ("Mobile data 2"), and is never
 * derived from a carrier, SIM or network name.
 */
data class NetworkLabel(val transport: TransportType, val number: Int?)

enum class InsufficientReason {
    MULTIPLE_VALIDATED,
    CURRENT_UNKNOWN,
    VPN_ON_CURRENT
}

/**
 * What the Home advisor card shows. Six states from ADVISOR_UI_SPEC.md
 * section 3, plus [Absent] (no candidate list has arrived yet, so the card is
 * not shown, decision D2).
 */
sealed interface AdvisorUiState {
    data object Absent : AdvisorUiState

    /** Events may have been lost. Any recommendation is withdrawn (decision D1). */
    data object Stale : AdvisorUiState

    data class Recommend(
        val target: NetworkLabel,
        val current: NetworkLabel,
        val notes: Set<AdvisorNote>
    ) : AdvisorUiState

    data class Idle(val notes: Set<AdvisorNote>) : AdvisorUiState

    data class SingleNetwork(val notes: Set<AdvisorNote>) : AdvisorUiState

    data class NoNetwork(val blockedByPolicy: Boolean) : AdvisorUiState

    data class Insufficient(val reason: InsufficientReason, val notes: Set<AdvisorNote>) : AdvisorUiState
}

/**
 * Assigns the numbers in [NetworkLabel]. A candidate gets the next number for
 * its transport the first time it is seen and keeps it for the rest of the
 * session, so "Mobile data 2" never refers to a different network from one
 * minute to the next. Numbers are not reused and never persisted
 * (ADVISOR_UI_SPEC.md section 5.7, risk R-UI-2).
 */
class NetworkLabeler {
    private val numbers = HashMap<CandidateNetworkId, Int>()
    private val counters = HashMap<TransportType, Int>()

    fun update(candidates: List<CandidateNetwork>) {
        candidates.forEach { candidate ->
            numbers.getOrPut(candidate.id) {
                val next = (counters[candidate.transport] ?: 0) + 1
                counters[candidate.transport] = next
                next
            }
        }
    }

    fun labelFor(
        id: CandidateNetworkId,
        transport: TransportType,
        candidates: List<CandidateNetwork>
    ): NetworkLabel {
        val sameTransport = candidates.count { it.transport == transport }
        return NetworkLabel(transport, if (sameTransport > 1) numbers[id] else null)
    }
}

fun AdvisorOutcome.toUiState(candidates: List<CandidateNetwork>, labeler: NetworkLabeler): AdvisorUiState =
    when (this) {
        is AdvisorOutcome.Recommend -> AdvisorUiState.Recommend(
            target = labeler.labelFor(target, targetTransport, candidates),
            current = labeler.labelFor(
                id = current,
                transport = candidates.firstOrNull { it.id == current }?.transport ?: TransportType.NONE,
                candidates = candidates
            ),
            notes = notes
        )

        is AdvisorOutcome.NoRecommendation -> reason.toUiState(notes)
    }

/**
 * The mapping table of ADVISOR_UI_SPEC.md section 3. The `when` has no `else`
 * on purpose: a new [NoRecommendationReason] must be given a card state before
 * this compiles.
 */
private fun NoRecommendationReason.toUiState(notes: Set<AdvisorNote>): AdvisorUiState = when (this) {
    NoRecommendationReason.CURRENT_NETWORK_VALIDATED,
    NoRecommendationReason.NO_VALIDATED_ALTERNATIVE,
    NoRecommendationReason.CONDITION_NOT_STABLE_YET,
    NoRecommendationReason.RECENTLY_SHOWN -> AdvisorUiState.Idle(notes)

    NoRecommendationReason.ONLY_ONE_NETWORK -> AdvisorUiState.SingleNetwork(notes)
    NoRecommendationReason.NO_NETWORK -> AdvisorUiState.NoNetwork(blockedByPolicy = false)
    NoRecommendationReason.ALL_BLOCKED_BY_DEVICE_POLICY -> AdvisorUiState.NoNetwork(blockedByPolicy = true)
    NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE ->
        AdvisorUiState.Insufficient(InsufficientReason.MULTIPLE_VALIDATED, notes)
    NoRecommendationReason.DEFAULT_NETWORK_NOT_REPORTED ->
        AdvisorUiState.Insufficient(InsufficientReason.CURRENT_UNKNOWN, notes)
    NoRecommendationReason.VPN_ACTIVE_ON_CURRENT ->
        AdvisorUiState.Insufficient(InsufficientReason.VPN_ON_CURRENT, notes)
}
