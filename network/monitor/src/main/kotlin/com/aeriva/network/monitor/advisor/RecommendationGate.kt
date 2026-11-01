package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.core.model.advisor.NoRecommendationReason
import java.time.Duration
import java.time.Instant

/**
 * Anti flapping for Network Advisor A1 (spec section 5.4). Feed it the raw
 * [AdvisorSelector] outcome each time the candidate list changes, and again
 * at [nextEvaluationAt] when that is not null; show what [update] returns.
 *
 * Behavior, all timing from the injected [now] and [config]:
 * - Dwell: a recommendation is shown only after the same recommendation has
 *   been the raw outcome continuously for [AdvisorTimingConfig.dwell].
 *   Until then the result is NoRecommendation(CONDITION_NOT_STABLE_YET).
 * - Immediate withdrawal: as soon as the raw outcome is anything other than
 *   the shown recommendation, the shown recommendation is withdrawn in that
 *   same call, with no dwell. The raw outcome is returned as is.
 * - Cooldown: once a recommendation has been visible, the same
 *   recommendation is not shown again until [AdvisorTimingConfig.cooldown]
 *   has passed since it was last visible (the moment it was withdrawn).
 *   Until then the result is NoRecommendation(RECENTLY_SHOWN). A
 *   recommendation that stays continuously valid stays shown; cooldown only
 *   delays a re-show after withdrawal.
 * - Facts change: a recommendation is identified by its current network and
 *   its target network. A different pair is a different recommendation and
 *   is not held back by another pair's cooldown.
 *
 * Boundaries are inclusive: a dwell or cooldown of N is satisfied when
 * exactly N has elapsed.
 *
 * The default timing values are unvalidated placeholders (leader decision
 * L5). Not thread safe: call from a single collector.
 */
class RecommendationGate(
    private val config: AdvisorTimingConfig = AdvisorTimingConfig(),
    private val now: () -> Instant
) {
    private data class Key(val current: CandidateNetworkId, val target: CandidateNetworkId)

    private data class Pending(val key: Key, val since: Instant)

    private var shown: AdvisorOutcome.Recommend? = null
    private var pending: Pending? = null
    private val lastVisible = HashMap<Key, Instant>()

    fun update(raw: AdvisorOutcome): AdvisorOutcome {
        val at = now()
        pruneExpiredCooldowns(at)

        if (raw !is AdvisorOutcome.Recommend) {
            withdraw(at)
            return raw
        }

        val key = keyOf(raw)
        val visible = shown
        if (visible != null) {
            if (keyOf(visible) == key) {
                shown = raw
                return raw
            }
            // A different recommendation: the old one ends now, the new one starts its dwell.
            withdraw(at)
        }

        val candidate = pending?.takeIf { it.key == key } ?: Pending(key, at).also { pending = it }
        if (Duration.between(candidate.since, at) < config.dwell) {
            return AdvisorOutcome.NoRecommendation(NoRecommendationReason.CONDITION_NOT_STABLE_YET, raw.notes)
        }

        val last = lastVisible[key]
        if (last != null && Duration.between(last, at) < config.cooldown) {
            return AdvisorOutcome.NoRecommendation(NoRecommendationReason.RECENTLY_SHOWN, raw.notes)
        }

        shown = raw
        pending = null
        return raw
    }

    /**
     * The earliest instant at which calling [update] again with the same raw
     * outcome could change the result, or null when nothing is waiting. An
     * instant at or before now means evaluate now.
     */
    fun nextEvaluationAt(): Instant? {
        val waiting = pending ?: return null
        val dwellEnds = waiting.since.plus(config.dwell)
        val cooldownEnds = lastVisible[waiting.key]?.plus(config.cooldown)
        return if (cooldownEnds != null && cooldownEnds.isAfter(dwellEnds)) cooldownEnds else dwellEnds
    }

    /** Forget everything, for example when the advisor is stopped. */
    fun reset() {
        shown = null
        pending = null
        lastVisible.clear()
    }

    private fun withdraw(at: Instant) {
        shown?.let { lastVisible[keyOf(it)] = at }
        shown = null
        pending = null
    }

    private fun pruneExpiredCooldowns(at: Instant) {
        lastVisible.values.removeAll { Duration.between(it, at) >= config.cooldown }
    }

    private fun keyOf(recommendation: AdvisorOutcome.Recommend): Key =
        Key(recommendation.current, recommendation.target)
}
