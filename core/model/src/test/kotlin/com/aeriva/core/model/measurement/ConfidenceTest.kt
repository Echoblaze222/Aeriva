package com.aeriva.core.model.measurement

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfidenceTest {

    @Test
    fun of_belowMinimumSamples_isInsufficient() {
        assertEquals(Confidence.Insufficient, Confidence.of(sampleCount = 0, consistent = true))
        assertEquals(Confidence.Insufficient, Confidence.of(sampleCount = 2, consistent = true))
    }

    @Test
    fun of_fewSamples_isLow() {
        assertEquals(Confidence.Low, Confidence.of(sampleCount = 3, consistent = true))
    }

    @Test
    fun of_manySamplesButInconsistent_isMedium() {
        assertEquals(Confidence.Medium, Confidence.of(sampleCount = 20, consistent = false))
    }

    @Test
    fun of_manyConsistentSamples_isHigh() {
        assertEquals(Confidence.High, Confidence.of(sampleCount = 20, consistent = true))
    }
}
