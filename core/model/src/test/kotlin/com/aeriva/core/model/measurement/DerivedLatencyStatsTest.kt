package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md's coverage for objectives
 * 1 (latency measurement) and 5 (stability): exercises
 * [DerivedLatencyStats.from] -- pure JVM arithmetic, hand-constructed
 * samples, no Android/network/clock dependency beyond the explicit
 * `calculatedAt` parameter -- following
 * PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 2's convention
 * exactly (the [NetworkStateMapperTest] precedent for this codebase).
 *
 * Deliberately does NOT test outlier rejection: [DerivedLatencyStats.from]
 * does not implement any (see its own KDoc) -- there is nothing to test
 * yet, and a test asserting "no outlier rejection happens" would just be
 * pinning today's absence of a feature, not a real requirement.
 */
class DerivedLatencyStatsTest {

    private val calculatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val context = MeasurementNetworkContext(networkState = NetworkState.unknown(calculatedAt))

    @Test
    fun from_emptyList_returnsNull() {
        val result = DerivedLatencyStats.from(emptyList(), calculatedAt)

        assertNull(result)
    }

    @Test
    fun from_allSamplesInvalid_returnsNull() {
        val samples = listOf(
            succeeded(id = 1, valueMillis = -5.0),
            succeeded(id = 2, valueMillis = Double.NaN),
            succeeded(id = 3, valueMillis = Double.POSITIVE_INFINITY)
        )

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        assertNull(result)
    }

    @Test
    fun from_negativeSample_isExcludedFromAverage_notAveragedIn() {
        // A probe that somehow reports negative latency must be rejected,
        // not averaged in -- PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md
        // Section 2's explicit numerical-edge-case requirement.
        val samples = listOf(
            succeeded(id = 1, valueMillis = 100.0),
            succeeded(id = 2, valueMillis = -999.0),
            succeeded(id = 3, valueMillis = 200.0)
        )

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        requireNotNull(result)
        assertEquals(150.0, result.averageMillis, 0.0001)
        assertEquals(listOf(1L, 3L), result.sourceMeasurementIds)
    }

    @Test
    fun from_singleSample_isBelowMinimumSamples_confidenceInsufficient() {
        val result = DerivedLatencyStats.from(listOf(succeeded(id = 1, valueMillis = 42.0)), calculatedAt)

        requireNotNull(result)
        assertEquals(42.0, result.averageMillis, 0.0001)
        assertEquals(42.0, result.minMillis, 0.0001)
        assertEquals(42.0, result.maxMillis, 0.0001)
        assertEquals(Confidence.Insufficient, result.confidence)
    }

    @Test
    fun from_twoSamples_stillBelowMinimumSamples_confidenceInsufficient() {
        val samples = listOf(succeeded(id = 1, valueMillis = 10.0), succeeded(id = 2, valueMillis = 20.0))

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        requireNotNull(result)
        assertEquals(Confidence.Insufficient, result.confidence)
    }

    @Test
    fun from_threeToNineSamples_isLowRegardlessOfConsistency() {
        // Confidence.of treats anything below MEDIUM_SAMPLES (10) as Low
        // once past the Insufficient threshold, independent of spread --
        // this pins that boundary against this aggregation function
        // specifically, not just Confidence in isolation (ConfidenceTest
        // already covers Confidence.of directly).
        val tightSamples = (1..5).map { succeeded(id = it.toLong(), valueMillis = 50.0) }
        val wideSamples = listOf(
            succeeded(id = 1, valueMillis = 10.0),
            succeeded(id = 2, valueMillis = 10.0),
            succeeded(id = 3, valueMillis = 500.0)
        )

        assertEquals(Confidence.Low, DerivedLatencyStats.from(tightSamples, calculatedAt)?.confidence)
        assertEquals(Confidence.Low, DerivedLatencyStats.from(wideSamples, calculatedAt)?.confidence)
    }

    @Test
    fun from_tenPlusConsistentSamples_isHigh() {
        val samples = (1..10).map { succeeded(id = it.toLong(), valueMillis = 40.0 + it) }

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        assertEquals(Confidence.High, result?.confidence)
    }

    @Test
    fun from_tenPlusInconsistentSamples_isMedium() {
        val samples = (1..10).map { i ->
            // Alternate a tight cluster with one wildly-off value per
            // pair, so spread stays large relative to the average.
            succeeded(id = i.toLong(), valueMillis = if (i % 2 == 0) 500.0 else 5.0)
        }

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        assertEquals(Confidence.Medium, result?.confidence)
    }

    @Test
    fun from_veryLargeValue_doesNotProduceNaNOrInfinity() {
        // Per Section 2: a very large value must not silently produce NaN
        // or an unusable result. This function does not implement outlier
        // rejection (see class KDoc), so the large value legitimately
        // dominates the average -- that is the documented, current
        // behavior this test pins, not a claim that it's the eventual
        // production behavior.
        val samples = listOf(
            succeeded(id = 1, valueMillis = 10.0),
            succeeded(id = 2, valueMillis = 10_000_000.0)
        )

        val result = DerivedLatencyStats.from(samples, calculatedAt)

        requireNotNull(result)
        assertTrue(result.averageMillis.isFinite())
        assertEquals(5_000_005.0, result.averageMillis, 0.0001)
    }

    @Test
    fun from_calculatedAt_isTheExplicitParameter_neverComputedInternally() {
        val distinctInstant = Instant.parse("2026-06-15T12:00:00Z")

        val result = DerivedLatencyStats.from(listOf(succeeded(id = 1, valueMillis = 1.0)), distinctInstant)

        assertEquals(distinctInstant, result?.calculatedAt)
    }

    private fun succeeded(id: Long, valueMillis: Double): LatencyMeasurement.Succeeded =
        LatencyMeasurement.Succeeded(
            id = id,
            context = context,
            measuredAt = calculatedAt,
            method = "tcp-round-trip",
            valueMillis = valueMillis,
            sampleCount = 1
        )
}
