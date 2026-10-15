package com.aeriva.core.model.measurement

import com.aeriva.core.model.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Test-hardening tranche for [DerivedJitterStats.from]. Complements
 * [DerivedJitterStatsTest] and [DerivedJitterStatsInvariantTest] without
 * relying on their helpers.
 *
 * Contract under test, all of it taken from [DerivedJitterStats]'s own
 * KDoc and constants (Decisions D4-3 and D4-4 as quoted there and in
 * PHASE_4_JITTER_TEST_GATE_REPORT.md):
 *
 *  - input not in non-decreasing `measuredAt` order is rejected (null);
 *  - a Succeeded sample whose value is NaN, infinite or negative is not a
 *    real reading; it breaks adjacency exactly as a Failed sample does and
 *    is never removed to bridge its neighbours (rule adopted for this
 *    tranche, consistent with the KDoc: "can never be part of a counted
 *    pair" and "a failed sample ... breaks adjacency and is never
 *    bridged");
 *  - confidence is by counted pair count: below 3 is [Confidence.Insufficient],
 *    3 through 9 is [Confidence.Low], 10 or more is [Confidence.High].
 *    The 3 and 10 cutoffs are provisional placeholders (JT-19, gate G3);
 *    these tests pin today's values so that changing them is a deliberate,
 *    visible act, not a claim that the numbers are right for release.
 *
 * Every assertion on a result first asserts the result is non-null, so no
 * test here can pass vacuously.
 */
class DerivedJitterStatsHardeningTest {

    private val epoch = Instant.parse("2026-10-01T00:00:00Z")
    private val context = MeasurementNetworkContext(networkState = NetworkState.unknown(epoch))

    private fun succeeded(id: Long, valueMillis: Double, second: Long = id) = LatencyMeasurement.Succeeded(
        id = id,
        context = context,
        measuredAt = epoch.plusSeconds(second),
        method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
        valueMillis = valueMillis,
        sampleCount = 1,
        evidence = ProbeEvidence(
            networkHandle = 1L,
            addressFamily = AddressFamily.IPv4,
            negotiatedProtocol = "http/1.1",
            connectionState = ConnectionState.Warm,
            proxyUsed = false,
            phases = null,
            serverProcessingMillis = null,
            serverRegionId = null,
            bytesSent = 8,
            bytesReceived = 8,
            engineElapsedMillis = null
        )
    )

    private fun failed(id: Long, second: Long = id) = LatencyMeasurement.Failed(
        id = id,
        context = context,
        measuredAt = epoch.plusSeconds(second),
        method = MeasurementMethod.HTTPS_H1_WARM_EXCHANGE,
        failure = MeasurementFailure.Timeout(MeasurementStage.Request)
    )

    private val invalidValues: List<Pair<String, Double>> = listOf(
        "NaN" to Double.NaN,
        "+Infinity" to Double.POSITIVE_INFINITY,
        "-Infinity" to Double.NEGATIVE_INFINITY,
        "negative" to -5.0
    )

    // ------------------------------------------------------------------
    // 1. Timestamp ordering
    // ------------------------------------------------------------------

    @Test
    fun timestampOrder_lastPairReversed_rejectsEvenThoughThreePairsAreOtherwiseValid() {
        // S1..S5 are in order and form 4 valid pairs; the variant below moves S5 before S4. Without the
        // order check the result would be non-null (4 pairs from the in-order prefix).
        val inOrder = listOf(
            succeeded(1, 40.0, second = 1),
            succeeded(2, 44.0, second = 2),
            succeeded(3, 41.0, second = 3),
            succeeded(4, 45.0, second = 4),
            succeeded(5, 43.0, second = 5)
        )
        val control = DerivedJitterStats.from(inOrder, epoch)
        assertNotNull("control series must produce a result", control)
        assertEquals(4, control!!.pairCount)

        val lastReversed = inOrder.dropLast(1) + succeeded(5, 43.0, second = 3)
        assertNull(DerivedJitterStats.from(lastReversed, epoch))
    }

    @Test
    fun timestampOrder_reversalInTheMiddle_rejectsTheWholeSeries() {
        val series = listOf(
            succeeded(1, 40.0, second = 1),
            succeeded(2, 44.0, second = 2),
            succeeded(3, 41.0, second = 9),
            succeeded(4, 45.0, second = 4),
            succeeded(5, 43.0, second = 10)
        )
        assertNull(DerivedJitterStats.from(series, epoch))
    }

    // ------------------------------------------------------------------
    // 2. Invalid samples break adjacency and are never bridged
    // ------------------------------------------------------------------

    @Test
    fun invalidSampleBetweenTwoValidOnes_formsNoPairAcrossIt() {
        for ((label, bad) in invalidValues) {
            val series = listOf(succeeded(1, 40.0), succeeded(2, bad), succeeded(3, 44.0))
            assertNull("valid-invalid-valid must not pair across the $label sample", DerivedJitterStats.from(series, epoch))
        }
    }

    @Test
    fun consecutiveInvalidSamples_formNoPairAcrossThem() {
        for ((label, bad) in invalidValues) {
            val series = listOf(succeeded(1, 40.0), succeeded(2, bad), succeeded(3, bad), succeeded(4, 44.0))
            assertNull("two adjacent $label samples must not be bridged", DerivedJitterStats.from(series, epoch))
        }
    }

    @Test
    fun invalidSampleSplitsTwoRuns_exactResultAndLineageForEachInvalidKind() {
        // Pairs (1,2) and (4,5). Bridging over #3 would add the pair (2,4): pairCount 3, mean 44/3.
        for ((label, bad) in invalidValues) {
            val series = listOf(
                succeeded(1, 40.0), succeeded(2, 44.0), succeeded(3, bad), succeeded(4, 10.0), succeeded(5, 16.0)
            )

            val stats = DerivedJitterStats.from(series, epoch)

            assertNotNull("a result must exist for $label", stats)
            stats!!
            assertEquals("pairCount for $label", 2, stats.pairCount)
            assertEquals("mean for $label: (|44-40| + |16-10|) / 2", 5.0, stats.meanAbsIpdvMillis, 1e-9)
            assertEquals("lineage for $label", listOf(1L, 2L, 4L, 5L), stats.sourceMeasurementIds)
            assertEquals("sampleCount for $label", 4, stats.sampleCount)
            assertEquals("range for $label: 44 - 10", 34.0, stats.pdvRangeMillis, 1e-9)
            assertEquals("confidence for $label", Confidence.Insufficient, stats.confidence)
        }
    }

    @Test
    fun invalidFirstSample_isSkippedAndTheRemainingPairStillCounts() {
        for ((label, bad) in invalidValues) {
            val series = listOf(succeeded(1, bad), succeeded(2, 10.0), succeeded(3, 14.0))

            val stats = DerivedJitterStats.from(series, epoch)

            assertNotNull("a result must exist for $label", stats)
            stats!!
            assertEquals("pairCount for $label", 1, stats.pairCount)
            assertEquals("mean for $label", 4.0, stats.meanAbsIpdvMillis, 1e-9)
            assertEquals("lineage for $label", listOf(2L, 3L), stats.sourceMeasurementIds)
            assertEquals("sampleCount for $label", 2, stats.sampleCount)
            assertEquals("range for $label", 4.0, stats.pdvRangeMillis, 1e-9)
        }
    }

    @Test
    fun invalidLastSample_isSkippedAndTheEarlierPairStillCounts() {
        for ((label, bad) in invalidValues) {
            val series = listOf(succeeded(1, 10.0), succeeded(2, 14.0), succeeded(3, bad))

            val stats = DerivedJitterStats.from(series, epoch)

            assertNotNull("a result must exist for $label", stats)
            stats!!
            assertEquals("pairCount for $label", 1, stats.pairCount)
            assertEquals("mean for $label", 4.0, stats.meanAbsIpdvMillis, 1e-9)
            assertEquals("lineage for $label", listOf(1L, 2L), stats.sourceMeasurementIds)
        }
    }

    @Test
    fun onlyInvalidSamples_yieldNoResult() {
        for ((label, bad) in invalidValues) {
            val series = listOf(succeeded(1, bad), succeeded(2, bad), succeeded(3, bad))
            assertNull("a series of only $label samples has no valid pair", DerivedJitterStats.from(series, epoch))
        }
    }

    @Test
    fun zeroLatency_isAValidReading_notTreatedAsNegative() {
        val stats = DerivedJitterStats.from(listOf(succeeded(1, 0.0), succeeded(2, 3.0)), epoch)

        assertNotNull(stats)
        stats!!
        assertEquals(1, stats.pairCount)
        assertEquals(3.0, stats.meanAbsIpdvMillis, 1e-9)
        assertEquals(listOf(1L, 2L), stats.sourceMeasurementIds)
        assertEquals(3.0, stats.pdvRangeMillis, 1e-9)
    }

    @Test
    fun invalidSampleFollowedByFailedSample_stillBreaksTheSameWayAsEither() {
        val series = listOf(
            succeeded(1, 40.0), succeeded(2, 44.0), succeeded(3, Double.NaN), failed(4),
            succeeded(5, 10.0), succeeded(6, 13.0)
        )

        val stats = DerivedJitterStats.from(series, epoch)

        assertNotNull(stats)
        stats!!
        assertEquals(2, stats.pairCount)
        assertEquals(3.5, stats.meanAbsIpdvMillis, 1e-9)
        assertEquals(listOf(1L, 2L, 5L, 6L), stats.sourceMeasurementIds)
    }

    // ------------------------------------------------------------------
    // 3. Confidence boundaries, by counted pairs (N pairs = N + 1 samples)
    // ------------------------------------------------------------------

    /** N+1 consecutive warm samples whose values alternate, giving exactly N counted pairs of difference 2. */
    private fun seriesWithPairs(pairs: Int): List<LatencyMeasurement> =
        (1..pairs + 1).map { index -> succeeded(index.toLong(), if (index % 2 == 1) 40.0 else 42.0) }

    private fun statsForPairs(pairs: Int): DerivedJitterStats {
        val stats = DerivedJitterStats.from(seriesWithPairs(pairs), epoch)
        assertNotNull("$pairs pairs must produce a result", stats)
        stats!!
        assertEquals("the series must contain exactly the intended pair count", pairs, stats.pairCount)
        assertEquals(pairs + 1, stats.sampleCount)
        return stats
    }

    @Test
    fun confidence_twoPairs_isInsufficient() =
        assertEquals(Confidence.Insufficient, statsForPairs(2).confidence)

    @Test
    fun confidence_threePairs_isLow() =
        assertEquals(Confidence.Low, statsForPairs(3).confidence)

    @Test
    fun confidence_ninePairs_isStillLow() =
        assertEquals(Confidence.Low, statsForPairs(9).confidence)

    @Test
    fun confidence_tenPairs_isHigh() =
        assertEquals(Confidence.High, statsForPairs(10).confidence)

    @Test
    fun confidence_countsPairs_notSamples() {
        // 3 samples = 2 pairs: Insufficient, even though 3 is the pair threshold.
        val threeSamples = statsForPairs(2)
        assertEquals(3, threeSamples.sampleCount)
        assertEquals(Confidence.Insufficient, threeSamples.confidence)

        // 10 samples = 9 pairs: Low, even though 10 is the high threshold.
        val tenSamples = statsForPairs(9)
        assertEquals(10, tenSamples.sampleCount)
        assertEquals(Confidence.Low, tenSamples.confidence)
    }
}
