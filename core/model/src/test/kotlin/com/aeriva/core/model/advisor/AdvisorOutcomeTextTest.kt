package com.aeriva.core.model.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the engineering statements carried by the advisor result enums
 * against the repository wording rules (no em dash, no emoji). ASCII only is
 * a strict proxy for both.
 */
class AdvisorOutcomeTextTest {

    private fun assertPlainAscii(label: String, text: String) {
        assertTrue("$label must not be blank", text.isNotBlank())
        assertTrue("$label must be plain ASCII: $text", text.all { it.code in 32..126 })
    }

    @Test
    fun noRecommendationStatements_arePlainAscii() {
        NoRecommendationReason.entries.forEach { assertPlainAscii(it.name, it.statement) }
    }

    @Test
    fun recommendationCriteriaStatements_arePlainAscii() {
        RecommendationCriteria.entries.forEach { assertPlainAscii(it.name, it.statement) }
    }

    @Test
    fun specWording_isPreservedForTheRulesThatQuoteIt() {
        assertEquals("Only one network is available", NoRecommendationReason.ONLY_ONE_NETWORK.statement)
        assertEquals(
            "Not enough measured evidence to compare",
            NoRecommendationReason.NOT_ENOUGH_EVIDENCE_TO_COMPARE.statement
        )
        assertEquals(
            "Current network has no validated internet; this network does.",
            RecommendationCriteria.CURRENT_NOT_VALIDATED_OTHER_VALIDATED.statement
        )
    }

    @Test
    fun evidenceBasis_hasNoConfidenceTier() {
        // Leader decision L3: the only basis is the platform validation flag.
        assertEquals(listOf(EvidenceBasis.PLATFORM_VALIDATION), EvidenceBasis.entries)
    }

    @Test
    fun candidateNetworkIds_compareByValue() {
        assertEquals(CandidateNetworkId(7), CandidateNetworkId(7))
        assertNotEquals(CandidateNetworkId(7), CandidateNetworkId(8))
    }
}
