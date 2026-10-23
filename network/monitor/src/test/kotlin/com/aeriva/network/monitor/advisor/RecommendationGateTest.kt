package com.aeriva.network.monitor.advisor

import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.AdvisorNote
import com.aeriva.core.model.advisor.AdvisorOutcome
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.core.model.advisor.EvidenceBasis
import com.aeriva.core.model.advisor.NoRecommendationReason
import com.aeriva.core.model.advisor.RecommendationCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * JVM tests for [RecommendationGate] with a controllable clock (spec
 * sections 5.4 and 7): dwell, cooldown, immediate withdrawal.
 */
class RecommendationGateTest {

    private class TestClock(var now: Instant)

    private val start = Instant.parse("2026-10-23T10:00:00Z")

    private fun clock() = TestClock(start)

    private fun TestClock.atMillis(offset: Long) {
        now = start.plusMillis(offset)
    }

    private fun TestClock.atSeconds(offset: Long) = atMillis(offset * 1000)

    private fun gate(clock: TestClock, config: AdvisorTimingConfig = AdvisorTimingConfig()) =
        RecommendationGate(config) { clock.now }

    private fun recommend(current: Long = 1, target: Long = 2, notes: Set<AdvisorNote> = emptySet()) =
        AdvisorOutcome.Recommend(
            current = CandidateNetworkId(current),
            target = CandidateNetworkId(target),
            targetTransport = TransportType.CELLULAR,
            criteria = RecommendationCriteria.CURRENT_NOT_VALIDATED_OTHER_VALIDATED,
            basis = EvidenceBasis.PLATFORM_VALIDATION,
            notes = notes
        )

    private val noRecommendation: AdvisorOutcome =
        AdvisorOutcome.NoRecommendation(NoRecommendationReason.NO_VALIDATED_ALTERNATIVE, emptySet())

    private fun reasonOf(outcome: AdvisorOutcome): NoRecommendationReason {
        assertTrue("expected NoRecommendation but was $outcome", outcome is AdvisorOutcome.NoRecommendation)
        return (outcome as AdvisorOutcome.NoRecommendation).reason
    }

    // ---- Configuration ------------------------------------------------

    @Test
    fun defaultConfig_isTenSecondsAndFiveMinutes() {
        val config = AdvisorTimingConfig()

        assertEquals(Duration.ofSeconds(10), config.dwell)
        assertEquals(Duration.ofMinutes(5), config.cooldown)
    }

    @Test
    fun config_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) { AdvisorTimingConfig(dwell = Duration.ofSeconds(-1)) }
        assertThrows(IllegalArgumentException::class.java) { AdvisorTimingConfig(cooldown = Duration.ofSeconds(-1)) }
    }

    @Test
    fun customConfig_isHonored() {
        val clock = clock()
        val gate = gate(clock, AdvisorTimingConfig(dwell = Duration.ofSeconds(2), cooldown = Duration.ofSeconds(3)))
        val rec = recommend()

        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))
        clock.atSeconds(2)
        assertEquals(rec, gate.update(rec))
        clock.atSeconds(3)
        gate.update(noRecommendation)
        clock.atSeconds(4)
        gate.update(rec)
        clock.atSeconds(6)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(rec)))
        clock.atSeconds(6)
        clock.atMillis(5_999)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(rec)))
        clock.atMillis(6_000)
        assertEquals(rec, gate.update(rec))
    }

    // ---- Dwell --------------------------------------------------------

    @Test
    fun dwell_recommendationIsNotShownBeforeTheDwellTime_andIsShownAtIt() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()

        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))

        clock.atMillis(9_999)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))

        clock.atMillis(10_000)
        assertEquals(rec, gate.update(rec))
    }

    @Test
    fun dwell_restartsWhenTheConditionIsInterrupted() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()

        gate.update(rec)
        clock.atSeconds(5)
        gate.update(noRecommendation)
        clock.atSeconds(6)
        gate.update(rec)

        clock.atSeconds(15)
        assertEquals(
            "only 9 seconds since the condition returned",
            NoRecommendationReason.CONDITION_NOT_STABLE_YET,
            reasonOf(gate.update(rec))
        )
        clock.atSeconds(16)
        assertEquals(rec, gate.update(rec))
    }

    @Test
    fun dwell_gatedResultKeepsTheNotes() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend(notes = setOf(AdvisorNote.VPN_MAY_AFFECT_COMPARISON))

        val gated = gate.update(rec)

        assertEquals(setOf(AdvisorNote.VPN_MAY_AFFECT_COMPARISON), gated.notes)
    }

    @Test
    fun zeroDwell_showsImmediately() {
        val clock = clock()
        val gate = gate(clock, AdvisorTimingConfig(dwell = Duration.ZERO))
        val rec = recommend()

        assertEquals(rec, gate.update(rec))
    }

    // ---- Immediate withdrawal ----------------------------------------

    @Test
    fun withdrawal_isImmediate_withNoDwell() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        assertEquals(rec, gate.update(rec))

        // Same instant: the condition ends and the recommendation goes away in that call.
        val withdrawn = gate.update(noRecommendation)

        assertEquals(noRecommendation, withdrawn)
    }

    @Test
    fun rawNoRecommendation_isPassedThroughUnchanged() {
        val clock = clock()
        val gate = gate(clock)

        assertSame(noRecommendation, gate.update(noRecommendation))
    }

    @Test
    fun shownRecommendation_staysShown_whileTheConditionHolds_beyondTheCooldown() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        assertEquals(rec, gate.update(rec))

        clock.atSeconds(1_200)

        assertEquals(rec, gate.update(rec))
    }

    // ---- Cooldown -----------------------------------------------------

    @Test
    fun cooldown_suppressesAReShowAfterWithdrawal_untilItHasPassed() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        assertEquals(rec, gate.update(rec))
        clock.atSeconds(20)
        gate.update(noRecommendation)

        // The condition returns 5 seconds later and holds for the dwell time.
        clock.atSeconds(25)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))
        clock.atSeconds(35)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(rec)))

        // Cooldown runs from the withdrawal at 20 seconds, so it ends at 320 seconds.
        clock.atMillis(319_999)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(rec)))
        clock.atMillis(320_000)
        assertEquals(rec, gate.update(rec))
    }

    @Test
    fun cooldown_doesNotApplyToADifferentRecommendation_factsChanged() {
        val clock = clock()
        val gate = gate(clock)
        val first = recommend(current = 1, target = 2)
        val different = recommend(current = 1, target = 3)
        gate.update(first)
        clock.atSeconds(10)
        gate.update(first)
        clock.atSeconds(20)
        gate.update(noRecommendation)

        clock.atSeconds(21)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(different)))
        clock.atSeconds(31)

        assertEquals(different, gate.update(different))
    }

    @Test
    fun replacement_withdrawsTheOldOneImmediately_andTheNewOneNeedsItsOwnDwell() {
        val clock = clock()
        val gate = gate(clock)
        val first = recommend(current = 1, target = 2)
        val second = recommend(current = 1, target = 3)
        gate.update(first)
        clock.atSeconds(10)
        assertEquals(first, gate.update(first))

        clock.atSeconds(60)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(second)))
        clock.atSeconds(69)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(second)))
        clock.atSeconds(70)
        assertEquals(second, gate.update(second))
    }

    @Test
    fun cooldown_isTrackedPerRecommendation_aReplacedOneStaysOnCooldown() {
        val clock = clock()
        val gate = gate(clock)
        val first = recommend(current = 1, target = 2)
        val second = recommend(current = 1, target = 3)
        gate.update(first)
        clock.atSeconds(10)
        gate.update(first)
        clock.atSeconds(60)
        gate.update(second)
        clock.atSeconds(70)
        assertEquals(second, gate.update(second))

        // The first returns at 80 seconds and has been visible until 60 seconds.
        clock.atSeconds(80)
        gate.update(first)
        clock.atSeconds(90)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(first)))

        clock.atMillis(359_999)
        assertEquals(NoRecommendationReason.RECENTLY_SHOWN, reasonOf(gate.update(first)))
        clock.atMillis(360_000)
        assertEquals(first, gate.update(first))
    }

    @Test
    fun zeroCooldown_allowsAReShowAfterTheDwellTime() {
        val clock = clock()
        val gate = gate(clock, AdvisorTimingConfig(cooldown = Duration.ZERO))
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        gate.update(rec)
        clock.atSeconds(11)
        gate.update(noRecommendation)

        clock.atSeconds(12)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))
        clock.atSeconds(22)

        assertEquals(rec, gate.update(rec))
    }

    // ---- Scheduling hint ----------------------------------------------

    @Test
    fun nextEvaluationAt_isNullWhenNothingIsWaiting() {
        val clock = clock()
        val gate = gate(clock)
        assertNull(gate.nextEvaluationAt())

        gate.update(noRecommendation)
        assertNull(gate.nextEvaluationAt())

        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        gate.update(rec)
        assertNull("nothing waits once the recommendation is shown", gate.nextEvaluationAt())
    }

    @Test
    fun nextEvaluationAt_isTheEndOfTheDwell_whileDwelling() {
        val clock = clock()
        val gate = gate(clock)
        clock.atSeconds(3)

        gate.update(recommend())

        assertEquals(start.plusSeconds(13), gate.nextEvaluationAt())
    }

    @Test
    fun nextEvaluationAt_isTheEndOfTheCooldown_whenThatIsLater() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        gate.update(rec)
        clock.atSeconds(20)
        gate.update(noRecommendation)
        clock.atSeconds(25)

        gate.update(rec)

        assertEquals(start.plusSeconds(320), gate.nextEvaluationAt())
    }

    @Test
    fun nextEvaluationAt_clearsWhenTheConditionEnds() {
        val clock = clock()
        val gate = gate(clock)
        gate.update(recommend())

        gate.update(noRecommendation)

        assertNull(gate.nextEvaluationAt())
    }

    // ---- Reset --------------------------------------------------------

    @Test
    fun reset_forgetsShownPendingAndCooldownState() {
        val clock = clock()
        val gate = gate(clock)
        val rec = recommend()
        gate.update(rec)
        clock.atSeconds(10)
        gate.update(rec)
        clock.atSeconds(20)
        gate.update(noRecommendation)

        gate.reset()

        clock.atSeconds(21)
        assertEquals(NoRecommendationReason.CONDITION_NOT_STABLE_YET, reasonOf(gate.update(rec)))
        clock.atSeconds(31)
        assertEquals("no cooldown remains after reset", rec, gate.update(rec))
    }
}
