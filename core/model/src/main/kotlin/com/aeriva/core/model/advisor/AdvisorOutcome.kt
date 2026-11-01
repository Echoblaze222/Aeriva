package com.aeriva.core.model.advisor

import com.aeriva.core.model.TransportType

/**
 * Result of the Network Advisor A1 selection rules (spec section 5.3).
 *
 * This is a new typed result rather than
 * [com.aeriva.core.model.measurement.ConnectivityRecommendation] because
 * that type requires a Confidence tier and a Freshness value, and leader
 * decision L3 says A1 shows no confidence tier. It also carries typed
 * identities instead of free text targets.
 *
 * The `statement` strings on the enums below are engineering descriptions
 * of the rule that fired, so every outcome names its reason. They are not
 * final user interface copy; the UI owner decides wording.
 */
sealed interface AdvisorOutcome {

    /** Informational facts attached to the outcome. Never a ranking. */
    val notes: Set<AdvisorNote>

    data class Recommend(
        val current: CandidateNetworkId,
        val target: CandidateNetworkId,
        val targetTransport: TransportType,
        val criteria: RecommendationCriteria,
        val basis: EvidenceBasis,
        override val notes: Set<AdvisorNote>
    ) : AdvisorOutcome

    data class NoRecommendation(
        val reason: NoRecommendationReason,
        override val notes: Set<AdvisorNote>
    ) : AdvisorOutcome
}

/**
 * What a recommendation rests on. Exactly one value in A1: the platform's
 * own validation flag. Leader decision L3 fixes the display wording as
 * "Based on Android's own connectivity check". It is not an AERIVA
 * measurement and must never be shown as a confidence tier or a number.
 */
enum class EvidenceBasis {
    PLATFORM_VALIDATION
}

enum class RecommendationCriteria(val statement: String) {
    CURRENT_NOT_VALIDATED_OTHER_VALIDATED(
        "Current network has no validated internet; this network does."
    )
}

enum class AdvisorNote {
    /** A VPN network is present, so a comparison of underlying transports may not reflect VPN performance. */
    VPN_MAY_AFFECT_COMPARISON,

    /** The validated candidates differ in metered status. Informational only. */
    METERED_STATUS_DIFFERS
}

enum class NoRecommendationReason(val statement: String) {
    NO_NETWORK("No network is available"),
    ALL_BLOCKED_BY_DEVICE_POLICY("Device policy blocks every available network"),
    ONLY_ONE_NETWORK("Only one network is available"),
    NOT_ENOUGH_EVIDENCE_TO_COMPARE("Not enough measured evidence to compare"),
    CURRENT_NETWORK_VALIDATED("Current network has validated internet and no other network does"),
    NO_VALIDATED_ALTERNATIVE("No other network has validated internet"),
    DEFAULT_NETWORK_NOT_REPORTED("The current network is not known"),
    VPN_ACTIVE_ON_CURRENT("A VPN is active on the current network"),

    /** Produced by the anti flapping gate only: the condition has not held for the dwell time yet. */
    CONDITION_NOT_STABLE_YET("The condition has not held long enough"),

    /** Produced by the anti flapping gate only: the same recommendation was visible within the cooldown. */
    RECENTLY_SHOWN("This recommendation was shown recently")
}
